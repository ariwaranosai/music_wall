package com.musicwall.player.data

import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class Album(val id: String, val title: String, val artist: String, val songs: Int)
data class Playlist(val id: String, val name: String, val owner: String, val songs: Int)

class Navidrome(private val http: OkHttpClient) {
    suspend fun ping(profile: ServerProfile) {
        request(profile, "ping")
    }

    suspend fun albums(profile: ServerProfile, offset: Int, size: Int): List<Album> {
        val data = request(profile, "getAlbumList2", "type" to "alphabeticalByName",
            "offset" to offset.toString(), "size" to size.toString())
        return data.optJSONObject("albumList2")?.optJSONArray("album").objects().mapNotNull { item ->
            val id = item.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Album(id, item.optString("name").ifBlank { item.optString("title", "未命名专辑") },
                item.optString("artist"), item.optInt("songCount"))
        }
    }

    suspend fun albumTracks(profile: ServerProfile, album: Album): List<Track> {
        val data = request(profile, "getAlbum", "id" to album.id).optJSONObject("album")
        val albumCover = data?.optString("coverArt").orEmpty()
        return data?.optJSONArray("song").objects().mapNotNull { it.track(albumCover) }
    }

    suspend fun playlists(profile: ServerProfile): List<Playlist> {
        val data = request(profile, "getPlaylists").optJSONObject("playlists")
        return data?.optJSONArray("playlist").objects().mapNotNull { item ->
            val id = item.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Playlist(id, item.optString("name").ifBlank { "未命名播放列表" }, item.optString("owner"),
                item.optInt("songCount"))
        }
    }

    suspend fun playlistTracks(profile: ServerProfile, playlist: Playlist): List<Track> =
        request(profile, "getPlaylist", "id" to playlist.id).optJSONObject("playlist")
            ?.optJSONArray("entry").objects().mapNotNull { it.track("") }

    suspend fun search(profile: ServerProfile, query: String, offset: Int, size: Int): List<Track> {
        val data = request(profile, "search3", "query" to query, "artistCount" to "0",
            "albumCount" to "0", "songOffset" to offset.toString(), "songCount" to size.toString())
        return data.optJSONObject("searchResult3")?.optJSONArray("song").objects().mapNotNull { it.track("") }
    }

    suspend fun artwork(profile: ServerProfile, coverId: String): Bitmap? = withContext(Dispatchers.Default) {
        get(endpoint(profile, "getCoverArt", "id" to coverId, "size" to "384")) { response ->
            val source = response.body?.source() ?: return@get null
            if (source.request(2_000_001L)) return@get null
            decodeArtwork(source.readByteArray())
        }
    }

    private suspend fun request(profile: ServerProfile, path: String, vararg parameters: Pair<String, String>): JSONObject =
        get(endpoint(profile, path, *parameters)) { response ->
            val source = response.body?.source() ?: throw IOException("Navidrome 响应为空。")
            if (source.request(4_000_001L)) throw IOException("Navidrome 响应过大。")
            val envelope = runCatching { JSONObject(source.readUtf8()) }.getOrNull()
                ?: throw IOException("地址未返回 Navidrome API 响应，请检查部署路径。")
            val result = envelope.optJSONObject("subsonic-response") ?: throw IOException("无法识别 Navidrome 响应。")
            if (result.optString("status") != "ok") {
                val code = result.optJSONObject("error")?.optInt("code")
                throw IOException(when (code) {
                    40 -> "用户名或密码错误。"
                    50 -> "此账号无权访问曲库。"
                    70 -> "请求的内容已从曲库移除。"
                    else -> "Navidrome 请求失败${code?.let { "（$it）" }.orEmpty()}。"
                })
            }
            result
        }

    private suspend fun <T> get(url: HttpUrl, parse: (Response) -> T): T = suspendCancellableCoroutine { pending ->
        val call = http.newCall(Request.Builder().url(url).build())
        pending.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (pending.isActive) pending.resumeWithException(IOException("无法连接 Navidrome，请检查地址和网络。"))
            }

            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                        if (!it.isSuccessful) throw IOException("Navidrome 返回 HTTP ${it.code}。")
                        parse(it)
                    }
                }
                if (pending.isActive) pending.resumeWith(result)
            }
        })
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private fun JSONObject.track(albumCover: String): Track? {
        val id = optString("id").takeIf { it.isNotBlank() } ?: return null
        val depth = optInt("bitDepth").takeIf { it > 0 }?.let { "$it-bit" }
        val rate = optInt("samplingRate").takeIf { it > 0 }?.let { "${it / 1000.0} kHz" }
        return Track(
            id = id,
            title = optString("title").ifBlank { "未命名曲目" },
            artist = optString("artist"),
            album = optString("album"),
            format = optString("suffix").uppercase(),
            durationMs = optLong("duration") * 1000L,
            coverId = optString("coverArt").ifBlank { albumCover }.ifBlank { null },
            details = listOfNotNull(depth, rate).joinToString(" / "),
        )
    }

    companion object {
        const val STREAM_SCHEME = "musicwall"
        private const val STREAM_HOST = "navidrome"
        const val ORIGINAL = "raw"
        const val TRANSCODED = "mp3"

        fun streamUri(profile: ServerProfile, songId: String): Uri = Uri.Builder()
            .scheme(STREAM_SCHEME)
            .authority(STREAM_HOST)
            .appendPath(songId)
            .appendQueryParameter("profile", profileId(profile))
            .appendQueryParameter("format", ORIGINAL)
            .build()

        fun transcodeUri(uri: Uri): Uri? {
            if (uri.scheme != STREAM_SCHEME || uri.host != STREAM_HOST ||
                uri.getQueryParameter("format") != ORIGINAL) return null
            val profile = uri.getQueryParameter("profile") ?: return null
            return uri.buildUpon().clearQuery().appendQueryParameter("profile", profile)
                .appendQueryParameter("format", TRANSCODED).build()
        }

        fun resolveStream(profile: ServerProfile, uri: Uri): HttpUrl {
            require(uri.scheme == STREAM_SCHEME && uri.host == STREAM_HOST)
            if (uri.getQueryParameter("profile") != profileId(profile)) {
                throw IOException("Navidrome 连接已经变更，请在曲库重新选歌。")
            }
            val id = uri.pathSegments.singleOrNull() ?: throw IOException("曲目地址无效。")
            val format = uri.getQueryParameter("format").takeIf { it == TRANSCODED } ?: ORIGINAL
            return endpoint(profile, "stream", "id" to id, "format" to format)
        }

        private fun profileId(profile: ServerProfile) =
            digest("SHA-256", "${profile.address}\n${profile.username}").take(24)

        internal fun endpoint(profile: ServerProfile, action: String, vararg params: Pair<String, String>): HttpUrl {
            val salt = UUID.randomUUID().toString().replace("-", "")
            return profile.address.newBuilder()
                .addPathSegment("rest")
                .addPathSegment(action)
                .addQueryParameter("u", profile.username)
                .addQueryParameter("s", salt)
                .addQueryParameter("t", digest("MD5", profile.password + salt))
                .addQueryParameter("v", "1.16.1")
                .addQueryParameter("c", "MusicWall")
                .addQueryParameter("f", "json")
                .apply { params.forEach { (key, value) -> addQueryParameter(key, value) } }
                .build()
        }

        private fun digest(algorithm: String, content: String): String =
            MessageDigest.getInstance(algorithm).digest(content.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
