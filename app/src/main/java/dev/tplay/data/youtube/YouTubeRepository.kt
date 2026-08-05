package dev.tplay.data.youtube

import android.util.Log
import dev.tplay.data.model.Song
import dev.tplay.data.model.SongSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.StreamExtractor
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.utils.Parser
import java.util.concurrent.ConcurrentHashMap

class YouTubeRepository(
    private val downloader: OkHttpDownloader,
) {

    private val io = Dispatchers.IO

    fun init() {
        if (NewPipe.getDownloader() == null) {
            NewPipe.init(downloader)
        }
    }

    suspend fun search(query: String, limit: Int = 30): List<Song> = withContext(io) {
        init()
        val extractor = ServiceList.YouTube.getSearchExtractor(query)
        extractor.fetchPage()
        extractor.initialPage.items
            .filterIsInstance<StreamInfoItem>()
            .take(limit)
            .mapNotNull { it.toSong() }
    }

    /**
     * India trending list (YouTube Music charts — the music-player equivalent of the
     * old Trending kiosk, which YouTube removed in July 2025). Falls back to the
     * legacy Trending kiosk and then to the service default kiosk.
     */
    suspend fun trendingIndia(limit: Int = 30): List<Song> = withContext(io) {
        init()
        val kioskList = ServiceList.YouTube.getKioskList()
        kioskList.forceContentCountry(ContentCountry("IN"))
        val extractor = listOf("trending_music", "Trending")
            .firstNotNullOfOrNull { id ->
                runCatching { kioskList.getExtractorById(id, null) }.getOrNull()
            }
            ?: kioskList.getDefaultKioskExtractor()
        extractor.fetchPage()
        extractor.initialPage.items
            .filterIsInstance<StreamInfoItem>()
            .take(limit)
            .mapNotNull { it.toSong() }
    }

    suspend fun resolveVideo(urlOrId: String): Song = withContext(io) {
        init()
        val url = if (urlOrId.startsWith("http")) urlOrId
        else "https://www.youtube.com/watch?v=$urlOrId"
        val extractor = ServiceList.YouTube.getStreamExtractor(url)
        extractor.fetchPage()
        val streamUrl = resolveStreamUrl(extractor)
        if (streamUrl.isBlank()) throw ExtractionException("no playable stream found")
        val videoId = runCatching {
            Parser.matchGroup1("v=([^&]*)", url).takeIf { it.isNotEmpty() }
        }.getOrNull() ?: url.hashCode().toString()
        Song(
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
    suspend fun resolveQueue(songs: List<Song>): List<Song> = withContext(io) {
        val resolvedByVideoId = ConcurrentHashMap<String, Song>()
        suspend fun resolveOne(song: Song): Song {
            if (song.source != SongSource.YOUTUBE || song.videoId == null) return song
            resolvedByVideoId[song.videoId]?.let { return it }
            val result = runCatching { resolveVideo(song.videoId!!) }
                .onFailure { Log.e(TAG, "resolve failed for ${song.videoId}: ${it.message}") }
                .getOrElse { song }
            resolvedByVideoId[song.videoId!!] = result
            return result
        }
        songs.chunked(4).flatMap { batch ->
            coroutineScope { batch.map { async(io) { resolveOne(it) } }.awaitAll() }
        }
    }

    // Try direct audio streams first, then fall back to the smallest muxed video+audio stream
    // (the app only ever plays the audio track).
    private fun resolveStreamUrl(extractor: StreamExtractor): String {
        runCatching { extractor.audioStreams }.getOrNull().orEmpty().let { streams ->
            if (streams.isNotEmpty()) {
                val preferred = streams
                    .filter { it.format == MediaFormat.OPUS || it.format == MediaFormat.M4A }
                    .maxByOrNull { it.averageBitrate }
                    ?: streams.maxByOrNull { it.averageBitrate }
                if (preferred != null) {
                    streamUrlOf(preferred)?.let { return it }
                }
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
