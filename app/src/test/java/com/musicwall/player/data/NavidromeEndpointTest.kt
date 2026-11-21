package com.musicwall.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NavidromeEndpointTest {
    @Test
    fun preservesBasePathAndEscapesParametersWithoutSendingPlainPassword() {
        val profile = ServerProfile.validate("https://nas.example/music", "user+name", "sample-password")
        val url = Navidrome.endpoint(profile, "stream", "id" to "a/b&c", "format" to "raw")

        assertEquals("/music/rest/stream", url.encodedPath)
        assertEquals("a/b&c", url.queryParameter("id"))
        assertEquals("user+name", url.queryParameter("u"))
        assertEquals("raw", url.queryParameter("format"))
        assertEquals(32, url.queryParameter("t")?.length)
        assertFalse(url.toString().contains("sample-password"))
        assertNotEquals(url.queryParameter("s"), Navidrome.endpoint(profile, "ping").queryParameter("s"))
    }

    @Test
    fun acceptsPrivateHttpAndRejectsPublicAndLookalikeHosts() {
        assertEquals("192.168.1.20", parseServerAddress("http://192.168.1.20:4533").host)
        assertEquals("10.0.2.2", parseServerAddress("http://10.0.2.2:4533").host)
        assertTrue(parseServerAddress("https://nas.example:4533").isHttps)
        for (address in listOf("http://nas.example", "http://10.foo.0.0.1", "http://172.32.0.1", "http://010.0.0.1", "http://u:p@10.0.0.1")) {
            assertThrows(address, IllegalArgumentException::class.java) { parseServerAddress(address) }
        }
    }
}
