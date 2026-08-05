package dev.tplay.core

import android.content.Context
import dev.tplay.data.local.AppDatabase
import dev.tplay.data.local.LibraryRepository
import dev.tplay.data.local.RecentSongsRepository
import dev.tplay.data.lyrics.LyricsRepository
import dev.tplay.data.prefs.SettingsStore
import dev.tplay.data.youtube.OkHttpDownloader
import dev.tplay.data.youtube.YouTubeRepository
import dev.tplay.player.PlayerConnection
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class AppContainer(context: Context) {

    val appContext: Context = context.applicationContext

    val okHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    val downloader = OkHttpDownloader(okHttp)
    val youtubeRepository = YouTubeRepository(downloader)

    val libraryRepository = LibraryRepository(appContext)

    val settingsStore = SettingsStore(appContext)

    val lyricsRepository = LyricsRepository(appContext)

    val artCache = ArtCache(appContext, okHttp)

    val database: AppDatabase by lazy {
        AppDatabase.getDatabase(appContext)
    }

    val recentSongsRepository: RecentSongsRepository by lazy {
        RecentSongsRepository(database.recentSongsDao())
    }

    val playerConnection = PlayerConnection(appContext)
}
