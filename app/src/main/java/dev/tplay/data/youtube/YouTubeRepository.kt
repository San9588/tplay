package dev.tplay.data.youtube

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import dev.tplay.data.model.Song
import dev.tplay.data.model.SongSource
import dev.tplay.data.model.YtQuality
import dev.tplay.data.model.YtStage
import dev.tplay.data.prefs.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.StreamExtractor
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.utils.Parser
import java.util.concurrent.ConcurrentHashMap

class YouTubeRepository(
    private val downloader: OkHttpDownloader,
    private val settingsStore: SettingsStore,
    private val appContext: Context,
) {

    private val io = Dispatchers.IO

    fun init() {
        if (NewPipe.getDownloader() == null) {
            NewPipe.init(downloader)
        }
    }

    suspend fun search(
        query: String,
        limit: Int = 30,
        onStage: (YtStage) -> Unit = {},
    ): List<Song> = withContext(io) {
        init()
        onStage(YtStage.FETCH)
        // Primary: YouTube Music "music_songs" filter so only official songs are returned.
        // Fallback 1: "music_videos" filter if no song results exist.
        // Fallback 2: General YouTube search if both music filters yield no results.
        val extractor = listOf("music_songs", "music_videos", "")
            .firstNotNullOfOrNull { filter ->
                runCatching {
                    val ex = if (filter.isNotEmpty()) {
                        ServiceList.YouTube.getSearchExtractor(query, listOf(filter), null)
                    } else {
                        ServiceList.YouTube.getSearchExtractor(query)
                    }
                    ex.fetchPage()
                    ex.takeIf { it.initialPage.items.isNotEmpty() }
                }.getOrNull()
            }
            ?: ServiceList.YouTube.getSearchExtractor(query).also {
                runCatching { it.fetchPage() }
            }
        onStage(YtStage.PARSE)
        val items = extractor.initialPage.items
            .filterIsInstance<StreamInfoItem>()
            .take(limit)
            .mapNotNull { it.toSong() }
        onStage(YtStage.READY)
        items
    }

    /**
     * India trending list (YouTube Music charts — the music-player equivalent of the
     * old Trending kiosk, which YouTube removed in July 2025). Falls back to the
     * legacy Trending kiosk and then to the service default kiosk.
     */
    suspend fun trendingIndia(
        limit: Int = 30,
        onStage: (YtStage) -> Unit = {},
    ): List<Song> = withContext(io) {
        init()
        onStage(YtStage.FETCH)
        val kioskList = ServiceList.YouTube.getKioskList()
        kioskList.forceContentCountry(ContentCountry("IN"))
        val extractor = listOf("trending_music", "Trending")
            .firstNotNullOfOrNull { id ->
                runCatching { kioskList.getExtractorById(id, null) }.getOrNull()
            }
            ?: kioskList.getDefaultKioskExtractor()
        extractor.fetchPage()
        onStage(YtStage.PARSE)
        val items = extractor.initialPage.items
            .filterIsInstance<StreamInfoItem>()
            .take(limit)
            .mapNotNull { it.toSong() }
        onStage(YtStage.READY)
        items
    }

    /**
     * Resolves a video to a playable stream. Retries once after a short delay on
     * transient failures (rate limiting, flaky extraction).
     */
    suspend fun resolveVideo(
        urlOrId: String,
        onStage: (YtStage) -> Unit = {},
    ): Song = withContext(io) {
        var lastError: Exception? = null
        for (attempt in 0 until 2) {
            try {
                return@withContext doResolveVideo(urlOrId, onStage)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                if (attempt == 0) delay(800)
            }
        }
        throw lastError ?: ExtractionException("resolve failed")
    }

    private suspend fun doResolveVideo(urlOrId: String, onStage: (YtStage) -> Unit): Song {
        init()
        onStage(YtStage.FETCH)
        val url = if (urlOrId.startsWith("http")) urlOrId
        else "https://www.youtube.com/watch?v=$urlOrId"
        val extractor = ServiceList.YouTube.getStreamExtractor(url)
        extractor.fetchPage()
        onStage(YtStage.PARSE)
        val target = currentTargetQuality()
        val streamUrl = resolveStreamUrl(extractor, target)
        if (streamUrl.isBlank()) throw ExtractionException("no playable stream found")
        val videoId = runCatching {
            Parser.matchGroup1("v=([^&]*)", url).takeIf { it.isNotEmpty() }
        }.getOrNull() ?: url.hashCode().toString()
        onStage(YtStage.READY)
        return Song(
            id = "yt:$videoId",
            title = extractor.name ?: "unknown",
            artist = extractor.uploaderName ?: "",
            album = "",
            durationMs = runCatching { extractor.length * 1000L }.getOrDefault(0L)
                .coerceAtLeast(0L),
            uri = streamUrl,
            artUri = extractor.thumbnails.firstOrNull()?.url,
            source = SongSource.YOUTUBE,
            videoId = videoId,
            channel = extractor.uploaderName ?: "",
        )
    }

    /**
     * Resolves a whole queue efficiently: duplicate video ids are resolved only once
     * and the list is processed in small parallel batches so YouTube doesn't
     * rate-limit the app (sequential resolution of a 30-song queue took minutes).
     */
    suspend fun resolveQueue(
        songs: List<Song>,
        onStage: (YtStage) -> Unit = {},
    ): List<Song> = withContext(io) {
        onStage(YtStage.FETCH)
        val resolvedByVideoId = ConcurrentHashMap<String, Song>()
        suspend fun resolveOne(song: Song): Song {
            if (song.source != SongSource.YOUTUBE || song.videoId == null) return song
            resolvedByVideoId[song.videoId]?.let { return it }
            val result = runCatching { doResolveVideo(song.videoId!!) { } }
                .onFailure { Log.e(TAG, "resolve failed for ${song.videoId}: ${it.message}") }
                .getOrElse { song }
            resolvedByVideoId[song.videoId!!] = result
            return result
        }
        val resolved = songs.chunked(4).flatMap { batch ->
            coroutineScope { batch.map { async(io) { resolveOne(it) } }.awaitAll() }
        }
        onStage(YtStage.READY)
        resolved
    }

    /** Reads the quality ceiling for the network the user is currently on. */
    private suspend fun currentTargetQuality(): YtQuality {
        val settings = settingsStore.settings.first()
        val name = if (isOnWifi()) settings.wifiQuality else settings.mobileQuality
        return YtQuality.fromName(name)
    }

    private fun isOnWifi(): Boolean {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /**
     * Picks the best audio-only stream at or below the configured ceiling. If every
     * stream is above the ceiling it falls back up to the closest (lowest-bitrate)
     * one. Only when no audio-only stream exists at all does it use the smallest
     * muxed video+audio stream (the app plays the audio track only).
     */
    private fun resolveStreamUrl(extractor: StreamExtractor, target: YtQuality): String {
        runCatching { extractor.audioStreams }.getOrNull().orEmpty().let { streams ->
            if (streams.isNotEmpty()) {
                val audio = streams.filter {
                    it.format == MediaFormat.OPUS || it.format == MediaFormat.M4A
                }
                val pool = audio.ifEmpty { streams }
                val known = pool.filter { it.averageBitrate > 0 }
                val pick = when {
                    known.isEmpty() -> pool.maxByOrNull { it.averageBitrate }
                    else -> {
                        val atOrBelow = known.filter { it.averageBitrate <= target.maxBitrate }
                        if (atOrBelow.isNotEmpty()) {
                            // best stream at-or-below the ceiling (falls down)
                            atOrBelow.maxByOrNull { it.averageBitrate }
                        } else {
                            // nothing below the ceiling -> closest up (lowest bitrate)
                            known.minByOrNull { it.averageBitrate }
                        }
                    }
                }
                if (pick != null) streamUrlOf(pick)?.let { return it }
            }
        }
        runCatching { extractor.videoStreams }.getOrNull().orEmpty().let { streams ->
            val muxed = streams
                .filter { it.isVideoOnly.not() }
                .minByOrNull { it.bitrate }
                ?: streams.minByOrNull { it.bitrate }
            if (muxed != null) {
                streamUrlOf(muxed)?.let { return it }
            }
        }
        return ""
    }

    private fun streamUrlOf(stream: Stream): String? {
        // In newer extractor versions the URL lives in `content` (getUrl() is deprecated
        // and returns null when the stream isn't flagged as a URL).
        val content = stream.content
        if (!content.isNullOrBlank() && (stream.isUrl || content.startsWith("http"))) {
            return content
        }
        return null
    }

    private fun StreamInfoItem.toSong(): Song {
        val videoId = runCatching {
            Parser.matchGroup1("v=([^&]*)", url).takeIf { it.isNotEmpty() }
        }.getOrNull() ?: url.hashCode().toString()
        return Song(
            id = "yt:$videoId",
            title = name,
            artist = uploaderName,
            album = "",
            durationMs = runCatching { duration * 1000L }.getOrDefault(0L)
                .coerceAtLeast(0L),
            uri = url,
            artUri = thumbnails.firstOrNull()?.url,
            source = SongSource.YOUTUBE,
            videoId = videoId,
            channel = uploaderName,
        )
    }

    companion object {
        private const val TAG = "tplay-youtube"
    }
}
