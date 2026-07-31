package dev.tplay.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "playlist_items")
data class PlaylistItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val songId: String,
    val position: Int,
)

data class PlaylistWithSongs(
    val playlist: PlaylistEntity,
    val songs: List<SongJson>,
)

@Entity(tableName = "songs")
data class SongJson(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val uri: String,
    val artUri: String?,
    val source: String,
    val albumId: Long,
    val videoId: String?,
    val channel: String?,
)

@Dao
interface PlaylistDao {

    @Query("SELECT * FROM playlists ORDER BY name COLLATE NOCASE ASC")
    fun observePlaylists(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun getPlaylist(id: Long): PlaylistEntity?

    @Insert
    suspend fun addPlaylist(playlist: PlaylistEntity): Long

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deletePlaylist(id: Long)

    @Query("DELETE FROM playlist_items WHERE playlistId = :playlistId")
    suspend fun clearPlaylist(playlistId: Long)

    @Insert
    suspend fun addItems(items: List<PlaylistItemEntity>)

    @Query(
        """
        SELECT s.* FROM playlist_items pi
        JOIN songs s ON s.id = pi.songId
        WHERE pi.playlistId = :playlistId
        ORDER BY pi.position ASC
        """
    )
    suspend fun getSongs(playlistId: Long): List<SongJson>

    @Query("SELECT * FROM songs WHERE id IN (:ids)")
    suspend fun getSongsByIds(ids: List<String>): List<SongJson>

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsertSong(song: SongJson)

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsertSongs(songs: List<SongJson>)

    @Query("SELECT COUNT(*) FROM playlist_items WHERE playlistId = :playlistId")
    suspend fun countItems(playlistId: Long): Int

    @Transaction
    suspend fun addToPlaylist(playlistId: Long, songs: List<SongJson>) {
        upsertSongs(songs)
        val next = countItems(playlistId)
        addItems(songs.mapIndexed { i, song ->
            PlaylistItemEntity(playlistId = playlistId, songId = song.id, position = next + i)
        })
    }
}

@Database(
    entities = [PlaylistEntity::class, PlaylistItemEntity::class, SongJson::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: android.content.Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: androidx.room.Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "tplay.db",
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
