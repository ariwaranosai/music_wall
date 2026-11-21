package com.musicwall.player.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.musicwall.player.MainActivity
import com.musicwall.player.MusicWallApplication
import com.musicwall.player.data.Navidrome
import com.musicwall.player.data.ServerProfile
import com.musicwall.player.data.Track
import java.io.IOException
import kotlin.random.Random

internal const val TRACK_SOURCE = "musicwall.source"
internal const val TRACK_FORMAT = "musicwall.format"
internal const val TRACK_DETAILS = "musicwall.details"
internal const val TRACK_COVER = "musicwall.cover"
internal const val TRACK_LOCAL_URI = "musicwall.local_uri"
internal const val TRACK_TRANSCODED = "musicwall.transcoded"

internal fun Track.toMediaItem(profile: ServerProfile?): MediaItem {
    val uri = localUri?.let(Uri::parse) ?: Navidrome.streamUri(requireNotNull(profile), id)
    val extras = Bundle().apply {
        putString(TRACK_SOURCE, if (localUri == null) "极空间" else "本地")
        putString(TRACK_FORMAT, format)
        putString(TRACK_DETAILS, details)
        putString(TRACK_COVER, coverId)
        putString(TRACK_LOCAL_URI, localUri)
    }
    return MediaItem.Builder()
        .setMediaId(if (localUri == null) "navidrome:$id" else id)
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setExtras(extras)
                .build()
        )
        .build()
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var playbackSession: MediaSession? = null
    private var player: ExoPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        val app = application as MusicWallApplication
        val pcmRenderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioOutputPlaybackParams: Boolean,
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(false)
                .setAudioProcessors(arrayOf(StereoMeter()))
                .build()
        }.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        val upstream = DefaultDataSource.Factory(
            this, OkHttpDataSource.Factory(app.http).setUserAgent("MusicWall/0.1"),
        )
        val source = ResolvingDataSource.Factory(upstream, object : ResolvingDataSource.Resolver {
            override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
                if (dataSpec.uri.scheme != Navidrome.STREAM_SCHEME) return dataSpec
                val profile = app.servers.load() ?: throw IOException("请先连接 Navidrome。")
                return dataSpec.withUri(Uri.parse(Navidrome.resolveStream(profile, dataSpec.uri).toString()))
            }

            override fun resolveReportedUri(uri: Uri): Uri =
                if (uri.scheme == "http" || uri.scheme == "https") uri.buildUpon().clearQuery().build() else uri
        })

        val engine = ExoPlayer.Builder(this, pcmRenderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(source))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        val preferences = getSharedPreferences("playback", MODE_PRIVATE)
        engine.shuffleModeEnabled = preferences.getBoolean("shuffle", false)
        engine.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) = maybeTranscode(engine, error)

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                preferences.edit().putBoolean("shuffle", shuffleModeEnabled).apply()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                MeterFeed.clear()
            }
        })
        player = engine
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val sessionPlayer = object : ForwardingPlayer(engine) {
            override fun setMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
                super.setMediaItems(mediaItems, startIndex, startPositionMs)
                if (engine.shuffleModeEnabled) engine.shuffleFromCurrentItem()
            }

            override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) {
                if (shuffleModeEnabled && !engine.shuffleModeEnabled) engine.shuffleFromCurrentItem()
                super.setShuffleModeEnabled(shuffleModeEnabled)
            }
        }
        playbackSession = MediaSession.Builder(this, sessionPlayer).setSessionActivity(openApp).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = playbackSession

    private fun ExoPlayer.shuffleFromCurrentItem() {
        val current = currentMediaItemIndex
        if (mediaItemCount < 2 || current !in 0 until mediaItemCount) return
        // Anchor the user's selection first so the rest of the shuffled queue can play once.
        val order = listOf(current) + (0 until mediaItemCount).filter { it != current }.shuffled()
        setShuffleOrder(DefaultShuffleOrder(order.toIntArray(), Random.nextLong()))
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        playbackSession?.release()
        playbackSession = null
        player?.release()
        player = null
        MeterFeed.clear()
        super.onDestroy()
    }

    private fun maybeTranscode(engine: ExoPlayer, error: PlaybackException) {
        if (error.errorCode !in FORMAT_ERRORS) return
        val item = engine.currentMediaItem ?: return
        val original = item.localConfiguration?.uri ?: return
        val fallback = Navidrome.transcodeUri(original) ?: return
        mainHandler.post {
            if (engine.currentMediaItem?.localConfiguration?.uri != original || engine.playerError !== error) {
                return@post
            }
            val index = engine.currentMediaItemIndex
            val resume = engine.playWhenReady
            val extras = Bundle(item.mediaMetadata.extras ?: Bundle()).apply { putBoolean(TRACK_TRANSCODED, true) }
            val replacement = item.buildUpon()
                .setUri(fallback)
                .setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(extras).build())
                .build()
            engine.replaceMediaItem(index, replacement)
            engine.seekToDefaultPosition(index)
            engine.prepare()
            engine.playWhenReady = resume
        }
    }

    private companion object {
        val FORMAT_ERRORS = setOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        )
    }
}
