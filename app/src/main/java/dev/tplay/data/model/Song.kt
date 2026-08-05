package dev.tplay.data.model

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

enum class SongSource { LOCAL, YOUTUBE }

data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val uri: String,
    val artUri: String?,
    val source: SongSource,
    val albumId: Long = 0L,
    val videoId: String? = null,
    val channel: String? = null,
) {
    fun mediaItem(): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title.ifBlank { "unknown" })
            .setArtist(artist.ifBlank { "unknown" })
            .setAlbumTitle(album.ifBlank { null })
            .setArtworkUri(artUri?.let { android.net.Uri.parse(it) })
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setIsPlayable(true)
            .build()
        return MediaItem.Builder()
            .setMediaId(id)
            .setUri(uri)
            .setTag(this)
            .setMediaMetadata(metadata)
            .build()
    }

    companion object {
        // MediaItem.tag is omitted when a MediaItem crosses the MediaSession binder, so the
        // controller only sees mediaId + mediaMetadata. This reconstructs a best-effort Song
        // (used as a fallback when the item was set outside PlayerConnection, e.g. after an
        // app-process restart while the service keeps playing).
        fun fromMediaItem(item: MediaItem, durationMs: Long): Song {
            val md = item.mediaMetadata
            val id = item.mediaId
            return Song(
                id = id,
                title = md.title?.toString() ?: "unknown",
                artist = md.artist?.toString() ?: "",
                album = md.albumTitle?.toString() ?: "",
                durationMs = durationMs,
                uri = "",
                artUri = md.artworkUri?.toString(),
                source = if (id.startsWith("yt:")) SongSource.YOUTUBE else SongSource.LOCAL,
                videoId = id.removePrefix("yt:").takeIf { id.startsWith("yt:") },
                channel = md.artist?.toString(),
            )
        }
    }
}
