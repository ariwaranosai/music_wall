package com.musicwall.player

import android.app.Application
import androidx.room.Room
import com.musicwall.player.data.LibraryDatabase
import com.musicwall.player.data.LocalLibrary
import com.musicwall.player.data.Navidrome
import com.musicwall.player.data.ServerStore
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class MusicWallApplication : Application() {
    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    val servers by lazy { ServerStore(this) }
    val navidrome by lazy { Navidrome(http) }
    val library by lazy {
        val database = Room.databaseBuilder(this, LibraryDatabase::class.java, "music-wall.db").build()
        LocalLibrary(this, database.localTracks())
    }
}
