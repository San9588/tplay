package dev.tplay.data.youtube

import android.util.Log
import dev.tplay.data.model.Song
import dev.tplay.data.model.SongSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.stream.StreamExtractor
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.utils.Parser

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
        runCatching {
            val extractor = ServiceList.YouTube.getSearchExtractor(query)
            extractor.fetchPage()
            extractor.initialPage.items
                .filterIsInstance<StreamInfoItem>()
                .take(limit)
                .mapNotNull { it.toSong() }
        }.getOrElse {
            Log.e(TAG, "search failed: ${it.message}", it)
            emptyList()
        }
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

    suspend fun resolveQueue(songs: List<Song>): List<Song> = withContext(io) {
        songs.map { song ->
            if (song.source == SongSource.YOUTUBE && song.videoId != null) {
                runCatching { resolveVideo(song.videoId!!) }
                    .onFailure { Log.e(TAG, "resolve failed for ${song.videoId}: ${it.message}") }
                    .getOrElse { song }
            } else song
        }
    }

    // Try direct audio streams first, then fall back to muxed video+audio (audio track only).
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
            if (streams.isNotEmpty()) {
                val muxed = streams.maxByOrNull { it.averageBitrate }
                if (muxed != null) {
                    streamUrlOf(muxed)?.let { return it }
                }
            }
        }
        return ""
    }

    private fun streamUrlOf(stream: org.schabi.newpipe.extractor.stream.Stream): String? {
        if (!stream.url.isNullOrBlank()) return stream.url
        val alt = runCatching { stream.downloadUrls }.getOrNull()
            ?.firstOrNull { it.isNotBlank() }
        return alt
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
