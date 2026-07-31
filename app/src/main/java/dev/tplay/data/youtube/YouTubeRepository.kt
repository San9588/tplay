package dev.tplay.data.youtube

import dev.tplay.data.model.Song
import dev.tplay.data.model.SongSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.stream.AudioStream
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
        }.getOrElse { emptyList() }
    }

    suspend fun resolveVideo(urlOrId: String): Song = withContext(io) {
        init()
        val url = if (urlOrId.startsWith("http")) urlOrId
        else "https://www.youtube.com/watch?v=$urlOrId"
        val extractor = ServiceList.YouTube.getStreamExtractor(url)
        extractor.fetchPage()
        val stream = bestAudioStream(extractor.audioStreams)
            ?: throw ExtractionException("no audio stream")
        val videoId = runCatching {
            Parser.matchGroup1("v=([^&]*)", url).takeIf { it.isNotEmpty() }
        }.getOrNull() ?: url.hashCode().toString()
        Song(
            id = "yt:$videoId",
            title = extractor.name ?: "unknown",
            artist = extractor.uploaderName ?: "",
            album = "",
            durationMs = extractor.length * 1000,
            uri = stream.url ?: "",
            artUri = extractor.thumbnails.firstOrNull()?.url,
            source = SongSource.YOUTUBE,
            videoId = videoId,
            channel = extractor.uploaderName ?: "",
        )
    }

    suspend fun resolveQueue(songs: List<Song>): List<Song> = withContext(io) {
        songs.map { song ->
            if (song.source == SongSource.YOUTUBE && song.videoId != null) {
                runCatching { resolveVideo(song.videoId!!) }.getOrElse { song }
            } else song
        }
    }

    private fun bestAudioStream(streams: List<AudioStream>): AudioStream? {
        if (streams.isEmpty()) return null
        val preferred = streams
            .filter { it.format == MediaFormat.OPUS || it.format == MediaFormat.M4A }
            .maxByOrNull { it.averageBitrate }
        return preferred ?: streams.maxByOrNull { it.averageBitrate }
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
            durationMs = duration * 1000,
            uri = url,
            artUri = thumbnails.firstOrNull()?.url,
            source = SongSource.YOUTUBE,
            videoId = videoId,
            channel = uploaderName,
        )
    }
}
