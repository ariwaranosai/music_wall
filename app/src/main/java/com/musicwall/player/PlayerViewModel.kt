package com.musicwall.player

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.musicwall.player.data.Album
import com.musicwall.player.data.Playlist
import com.musicwall.player.data.ServerProfile
import com.musicwall.player.data.Track
import com.musicwall.player.playback.PlaybackService
import com.musicwall.player.playback.TRACK_COVER
import com.musicwall.player.playback.TRACK_DETAILS
import com.musicwall.player.playback.TRACK_FORMAT
import com.musicwall.player.playback.TRACK_LOCAL_URI
import com.musicwall.player.playback.TRACK_SOURCE
import com.musicwall.player.playback.TRACK_TRANSCODED
import com.musicwall.player.playback.toMediaItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

enum class Screen { Player, Library }
enum class Source { Local, Navidrome }
enum class NavidromeSection { Albums, Playlists }
private const val ThemePreference = "app_theme"
enum class AppTheme(val key: String, val label: String) {
    Minimal("minimal", "简约"),
    Cyberpunk("cyberpunk", "赛博朋克"),
    Apple("apple", "苹果风"),
}

data class NowPlaying(
    val id: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val source: String = "",
    val format: String = "",
    val details: String = "",
    val duration: Long = 0,
    val position: Long = 0,
    val seekable: Boolean = false,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val transcoded: Boolean = false,
    val error: String? = null,
)

data class PlayerUiState(
    val screen: Screen = Screen.Player,
    val source: Source = Source.Local,
    val wall: Boolean = false,
    val theme: AppTheme = AppTheme.Minimal,
    val playerReady: Boolean = false,
    val shuffle: Boolean = false,
    val playing: NowPlaying = NowPlaying(),
    val artwork: Bitmap? = null,
    val localTracks: List<Track> = emptyList(),
    val importing: Boolean = false,
    val server: ServerProfile? = null,
    val connecting: Boolean = false,
    val serverError: String? = null,
    val albums: List<Album> = emptyList(),
    val selectedAlbum: Album? = null,
    val navidromeSection: NavidromeSection = NavidromeSection.Albums,
    val playlists: List<Playlist> = emptyList(),
    val selectedPlaylist: Playlist? = null,
    val serverTracks: List<Track> = emptyList(),
    val searchQuery: String = "",
    val catalogBusy: Boolean = false,
    val hasMore: Boolean = false,
    val catalogError: String? = null,
    val notice: String? = null,
)

class PlayerViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MusicWallApplication
    private val appearance = app.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(
        PlayerUiState(
            theme = (appearance.getString(ThemePreference, null) ?: appearance.getString("wall_theme", null))?.let { key ->
                AppTheme.entries.firstOrNull { it.key == key }
            } ?: AppTheme.Minimal,
        ),
    )
    val state = mutableState.asStateFlow()
    private val controllerFuture = MediaController.Builder(
        app, SessionToken(app, ComponentName(app, PlaybackService::class.java)),
    ).buildAsync()
    private var controller: MediaController? = null
    private var catalogJob: Job? = null
    private var artworkJob: Job? = null
    private var artworkKey = ""

    init {
        controllerFuture.addListener({
            val mediaController = runCatching { controllerFuture.get() }.getOrNull()
            if (mediaController == null) {
                showNotice("无法打开播放服务。")
                return@addListener
            }
            controller = mediaController
            mutableState.update { it.copy(playerReady = true) }
            mediaController.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    refreshPlayer()
                }
            })
            refreshPlayer()
        }, ContextCompat.getMainExecutor(app))

        viewModelScope.launch {
            app.library.tracks.collect { tracks ->
                mutableState.update { it.copy(localTracks = tracks.map { row -> row.asTrack() }) }
            }
        }
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) { app.servers.load() }
            if (mutableState.value.server == null && !mutableState.value.connecting) {
                mutableState.update { it.copy(server = saved) }
                if (saved != null && mutableState.value.source == Source.Navidrome) loadCatalog()
            }
        }
        viewModelScope.launch {
            while (isActive) {
                controller?.let { media ->
                    val position = media.currentPosition.coerceAtLeast(0L)
                    mutableState.update { it.copy(playing = it.playing.copy(position = position)) }
                }
                delay(250)
            }
        }
    }

    fun show(screen: Screen) {
        mutableState.update { it.copy(screen = screen, wall = screen == Screen.Player && it.wall) }
        if (screen == Screen.Library) loadCatalogIfNeeded()
    }

    fun selectSource(source: Source) {
        mutableState.update { it.copy(source = source) }
        loadCatalogIfNeeded()
    }

    private fun loadCatalogIfNeeded() {
        val current = mutableState.value
        if (current.source != Source.Navidrome || current.server == null || current.catalogBusy || current.catalogError != null) return
        val empty = when {
            current.selectedAlbum != null || current.selectedPlaylist != null || current.searchQuery.isNotBlank() ->
                current.serverTracks.isEmpty()
            current.navidromeSection == NavidromeSection.Playlists -> current.playlists.isEmpty()
            else -> current.albums.isEmpty()
        }
        if (empty) loadCatalog()
    }

    fun setWall(enabled: Boolean) {
        mutableState.update { it.copy(wall = enabled, screen = Screen.Player) }
    }

    fun selectAppTheme(theme: AppTheme) {
        if (mutableState.value.theme == theme) return
        appearance.edit().putString(ThemePreference, theme.key).apply()
        mutableState.update { it.copy(theme = theme) }
    }

    fun importFiles(uris: List<Uri>) {
        if (uris.isNotEmpty()) import { app.library.importFiles(uris) }
    }

    fun importFolder(uri: Uri) = import { app.library.importFolder(uri) }

    fun removeLocal(uri: String) {
        viewModelScope.launch {
            app.library.remove(uri)
            showNotice("已从音乐库移除。")
        }
    }

    private fun import(action: suspend () -> com.musicwall.player.data.ImportResult) {
        if (mutableState.value.importing) return
        viewModelScope.launch {
            mutableState.update { it.copy(importing = true) }
            try {
                val result = action()
                showNotice(if (result.added == 0) "没有可读的音频文件。" else {
                    "已导入 ${result.added} 首" +
                        if (result.skipped > 0) "，跳过 ${result.skipped} 项。" else "。"
                })
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                showNotice(e.message.takeIf { e is IllegalStateException } ?: "导入失败，请检查文件访问权限。")
            } finally {
                mutableState.update { it.copy(importing = false) }
            }
        }
    }

    fun connect(address: String, username: String, password: String, connected: () -> Unit) {
        if (mutableState.value.connecting) return
        viewModelScope.launch {
            mutableState.update { it.copy(connecting = true, serverError = null) }
            try {
                val profile = ServerProfile.validate(address, username, password)
                app.navidrome.ping(profile)
                withContext(Dispatchers.IO) { app.servers.save(profile) }
                if (profile != mutableState.value.server) clearRemotePlayback()
                catalogJob?.cancel()
                mutableState.update {
                    it.copy(server = profile, albums = emptyList(), selectedAlbum = null, playlists = emptyList(),
                        selectedPlaylist = null, serverTracks = emptyList(), searchQuery = "", catalogBusy = false,
                        hasMore = false, catalogError = null)
                }
                connected()
                showNotice("已连接 Navidrome。")
                loadCatalog()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                mutableState.update { it.copy(serverError = e.message ?: "无法连接 Navidrome。") }
            } finally {
                mutableState.update { it.copy(connecting = false) }
            }
        }
    }

    fun disconnect(closed: () -> Unit) {
        if (mutableState.value.connecting) return
        viewModelScope.launch {
            mutableState.update { it.copy(connecting = true, serverError = null) }
            try {
                withContext(Dispatchers.IO) { app.servers.clear() }
                clearRemotePlayback()
                catalogJob?.cancel()
                mutableState.update {
                    it.copy(server = null, selectedAlbum = null, selectedPlaylist = null, searchQuery = "",
                        albums = emptyList(), playlists = emptyList(), serverTracks = emptyList(), catalogBusy = false,
                        hasMore = false, catalogError = null)
                }
                closed()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                mutableState.update { it.copy(serverError = "无法移除所保存的连接。") }
            } finally {
                mutableState.update { it.copy(connecting = false) }
            }
        }
    }

    fun clearServerError() = mutableState.update { it.copy(serverError = null) }

    fun selectNavidromeSection(section: NavidromeSection) {
        if (section == mutableState.value.navidromeSection) return
        mutableState.update {
            it.copy(navidromeSection = section, selectedAlbum = null, selectedPlaylist = null, searchQuery = "",
                serverTracks = emptyList(), hasMore = false)
        }
        loadCatalog()
    }

    fun openAlbum(album: Album?) {
        mutableState.update { it.copy(selectedAlbum = album, selectedPlaylist = null, searchQuery = "", serverTracks = emptyList()) }
        loadCatalog()
    }

    fun openPlaylist(playlist: Playlist?) {
        mutableState.update {
            it.copy(selectedPlaylist = playlist, selectedAlbum = null, searchQuery = "", serverTracks = emptyList())
        }
        loadCatalog()
    }

    fun search(query: String) {
        mutableState.update { it.copy(selectedAlbum = null, selectedPlaylist = null, searchQuery = query.trim(), serverTracks = emptyList()) }
        loadCatalog()
    }

    fun loadCatalog(append: Boolean = false) {
        val current = mutableState.value
        val profile = current.server ?: return
        if (append && (current.catalogBusy || !current.hasMore)) return
        catalogJob?.cancel()
        catalogJob = viewModelScope.launch {
            mutableState.update { it.copy(catalogBusy = true, catalogError = null, hasMore = append && it.hasMore) }
            try {
                val album = current.selectedAlbum
                val playlist = current.selectedPlaylist
                when {
                    playlist != null -> {
                        val tracks = app.navidrome.playlistTracks(profile, playlist)
                        mutableState.update { it.copy(serverTracks = tracks, hasMore = false) }
                    }
                    current.navidromeSection == NavidromeSection.Playlists -> {
                        val playlists = app.navidrome.playlists(profile)
                        mutableState.update { it.copy(playlists = playlists, hasMore = false) }
                    }
                    album != null -> {
                        val tracks = app.navidrome.albumTracks(profile, album)
                        mutableState.update { it.copy(serverTracks = tracks, hasMore = false) }
                    }
                    current.searchQuery.isNotBlank() -> {
                        val previous = if (append) current.serverTracks else emptyList()
                        val tracks = app.navidrome.search(profile, current.searchQuery, previous.size, PAGE_SIZE)
                        mutableState.update {
                            it.copy(serverTracks = (previous + tracks).distinctBy(Track::id), hasMore = tracks.size == PAGE_SIZE)
                        }
                    }
                    else -> {
                        val previous = if (append) current.albums else emptyList()
                        val albums = app.navidrome.albums(profile, previous.size, PAGE_SIZE)
                        mutableState.update {
                            it.copy(albums = (previous + albums).distinctBy(Album::id), hasMore = albums.size == PAGE_SIZE)
                        }
                    }
                }
                mutableState.update { it.copy(catalogBusy = false) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                mutableState.update { it.copy(catalogBusy = false, catalogError = e.message ?: "无法读取音乐库。") }
            }
        }
    }

    fun play(queue: List<Track>, index: Int) {
        val track = queue.getOrNull(index) ?: return
        val media = controller ?: return showNotice("播放服务正在连接。")
        val profile = mutableState.value.server
        if (track.localUri == null && profile == null) return showNotice("请先连接 Navidrome。")
        media.setMediaItems(queue.map { it.toMediaItem(profile) }, index, 0L)
        media.prepare()
        media.play()
        show(Screen.Player)
        refreshPlayer()
    }

    fun playAll(queue: List<Track>) {
        if (queue.isEmpty()) return
        play(queue, if (mutableState.value.shuffle) Random.nextInt(queue.size) else 0)
    }

    fun toggleShuffle() {
        val media = controller ?: return
        if (media.isCommandAvailable(Player.COMMAND_SET_SHUFFLE_MODE)) {
            media.shuffleModeEnabled = !media.shuffleModeEnabled
        }
    }

    fun togglePlayback() {
        val media = controller ?: return
        if (media.mediaItemCount == 0) return show(Screen.Library)
        if (media.playWhenReady && media.playerError == null && media.playbackState != Player.STATE_ENDED) {
            media.pause()
        } else {
            if (media.playbackState == Player.STATE_ENDED) media.seekToDefaultPosition()
            if (media.playbackState == Player.STATE_IDLE) media.prepare()
            media.play()
        }
    }

    fun previous() { controller?.seekToPrevious() }
    fun next() { controller?.seekToNext() }
    fun seek(position: Long) {
        val media = controller ?: return
        media.seekTo(position)
        val updatedPosition = media.currentPosition.coerceAtLeast(0L)
        mutableState.update { it.copy(playing = it.playing.copy(position = updatedPosition)) }
    }
    fun consumeNotice() { mutableState.update { it.copy(notice = null) } }

    private fun showNotice(message: String) { mutableState.update { it.copy(notice = message) } }

    private fun clearRemotePlayback() {
        val media = controller ?: return
        if (media.currentMediaItem?.mediaId?.startsWith("navidrome:") == true) {
            media.stop()
            media.clearMediaItems()
        }
    }

    private fun refreshPlayer() {
        val media = controller ?: return
        val item = media.currentMediaItem
        val metadata = item?.mediaMetadata
        val extras = metadata?.extras
        val duration = media.duration.takeUnless { it == C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L
        val playing = NowPlaying(
            id = item?.mediaId.orEmpty(),
            title = metadata?.title?.toString().orEmpty(),
            artist = metadata?.artist?.toString().orEmpty(),
            album = metadata?.albumTitle?.toString().orEmpty(),
            source = extras?.getString(TRACK_SOURCE).orEmpty(),
            format = extras?.getString(TRACK_FORMAT).orEmpty(),
            details = extras?.getString(TRACK_DETAILS).orEmpty(),
            duration = duration,
            position = media.currentPosition.coerceAtLeast(0L),
            seekable = media.isCurrentMediaItemSeekable,
            playing = media.isPlaying,
            buffering = media.playbackState == Player.STATE_BUFFERING && media.playWhenReady,
            transcoded = extras?.getBoolean(TRACK_TRANSCODED) ?: false,
            error = media.playerError?.let {
                when (it.errorCode) {
                    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
                    PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> "文件已移动或无法读取，请重新导入。"
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "无法读取串流，请检查 Navidrome 连接。"
                    else -> "无法播放此曲目，请检查文件或格式。"
                }
            },
        )
        mutableState.update { it.copy(playing = playing, shuffle = media.shuffleModeEnabled) }
        if (artworkKey == playing.id) return
        artworkKey = playing.id
        artworkJob?.cancel()
        mutableState.update { it.copy(artwork = null) }
        val localUri = extras?.getString(TRACK_LOCAL_URI)
        val cover = extras?.getString(TRACK_COVER)
        val profile = mutableState.value.server
        artworkJob = viewModelScope.launch {
            val bitmap = try {
                when {
                    localUri != null -> app.library.artwork(localUri)
                    cover != null && profile != null -> app.navidrome.artwork(profile, cover)
                    else -> null
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                null
            }
            mutableState.update { it.copy(artwork = bitmap) }
        }
    }

    override fun onCleared() {
        MediaController.releaseFuture(controllerFuture)
        super.onCleared()
    }

    private companion object {
        const val PAGE_SIZE = 40
    }
}
