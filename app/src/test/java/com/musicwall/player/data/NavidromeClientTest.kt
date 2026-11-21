package com.musicwall.player.data

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress

class NavidromeClientTest {
    @Test
    fun readsAndSearchesAServerLibrary() = runBlocking {
        val http = OkHttpClient()
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            server.enqueue(response(""""status":"ok""""))
            server.enqueue(response(""""status":"ok","albumList2":{"album":[{"id":"a1","name":"夜航","artist":"晚风","songCount":1}]}"""))
            server.enqueue(response(""""status":"ok","album":{"coverArt":"cover1","song":[$TRACK]}"""))
            server.enqueue(response(""""status":"ok","searchResult3":{"song":[$TRACK]}"""))
            server.enqueue(response(""""status":"failed","error":{"code":40}"""))
            val navidrome = Navidrome(http)
            val profile = ServerProfile.validate("http://127.0.0.1:${server.port}/music", "wall", "test")
            try {
                navidrome.ping(profile)
                val albums = navidrome.albums(profile, 0, 40)
                assertEquals(listOf(Album("a1", "夜航", "晚风", 1)), albums)
                val track = navidrome.albumTracks(profile, albums.single()).single()
                assertEquals("cover1", track.coverId)
                assertEquals("FLAC", track.format)
                assertEquals("24-bit / 96.0 kHz", track.details)
                assertEquals(170_000L, track.durationMs)
                assertEquals(track.id, navidrome.search(profile, "夜", 0, 40).single().id)
                val error = runCatching { navidrome.ping(profile) }.exceptionOrNull()
                assertTrue(error is IOException)
                assertEquals("用户名或密码错误。", error?.message)
                for (path in listOf("ping", "getAlbumList2", "getAlbum", "search3", "ping")) {
                    assertEquals("/music/rest/$path", server.takeRequest().requestUrl?.encodedPath)
                }
            } finally {
                http.connectionPool.evictAll()
                http.dispatcher.executorService.shutdownNow()
            }
        }
    }

    @Test
    fun readsPlaylistsAndPreservesRepeatedEntriesInServerOrder() = runBlocking {
        val http = OkHttpClient()
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val list = """{"id":"p/1","name":"通勤","owner":"wall","songCount":3}"""
            val other = """{"id":"t2","title":"下一站","coverArt":"track-cover"}"""
            server.enqueue(response(""""status":"ok","playlists":{"playlist":[$list]}"""))
            server.enqueue(response(""""status":"ok","playlist":{"coverArt":"list-cover","entry":[$other,$TRACK,$other]}"""))
            server.enqueue(response(""""status":"ok","playlist":{"id":"empty","name":"空列表","songCount":0}"""))
            val navidrome = Navidrome(http)
            val profile = ServerProfile.validate("http://127.0.0.1:${server.port}/music", "wall", "test")
            try {
                val playlist = navidrome.playlists(profile).single()
                assertEquals(Playlist("p/1", "通勤", "wall", 3), playlist)
                val tracks = navidrome.playlistTracks(profile, playlist)
                assertEquals(listOf("t2", "t1", "t2"), tracks.map(Track::id))
                assertEquals(listOf("track-cover", null, "track-cover"), tracks.map(Track::coverId))
                assertTrue(navidrome.playlistTracks(profile, Playlist("empty", "空列表", "wall", 0)).isEmpty())
                assertEquals("/music/rest/getPlaylists", server.takeRequest().requestUrl?.encodedPath)
                val request = server.takeRequest().requestUrl
                assertEquals("/music/rest/getPlaylist", request?.encodedPath)
                assertEquals("p/1", request?.queryParameter("id"))
            } finally {
                http.connectionPool.evictAll()
                http.dispatcher.executorService.shutdownNow()
            }
        }
    }

    private fun response(content: String) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("""{"subsonic-response":{$content}}""")

    private companion object {
        const val TRACK = """{"id":"t1","title":"今晚","artist":"晚风","album":"夜航","suffix":"flac","bitDepth":24,"samplingRate":96000,"duration":170}"""
    }
}
