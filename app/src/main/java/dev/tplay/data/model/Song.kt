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
}
