package dev.tplay.data.local

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Bounded cache of recently played YouTube songs (metadata + links only).
 *
 * New entries are kept in memory while the app runs and are written to disk in a
 * single batch when the app closes. Oldest entries are evicted once the cached
 * metadata exceeds [MAX_CACHE_BYTES] (2 MB), so the cache can never grow out of
 * control even after very long usage.
 */
class RecentSongsRepository(private val dao: RecentSongsDao) {

    /** Fresh entries first. */
    suspend fun loadRecent(limit: Int = 60): List<RecentSongEntity> = dao.loadRecent(limit)

    /**
     * Merges the pending in-memory entries with what is already on disk, enforces the
     * byte budget (evicting the oldest entries), then persists everything in one write.
     */
    suspend fun batchWrite(pending: List<RecentSongEntity>) {
        if (pending.isEmpty()) return
        withContext(Dispatchers.IO) {
            dao.withTransaction {
                val merged = LinkedHashMap<String, RecentSongEntity>()
                dao.allOldestFirst().forEach { merged[it.id] = it }
                pending.forEach { merged[it.id] = it }

                // Oldest first; evict from the oldest end until the budget fits again.
                val ordered = merged.values.sortedBy { it.playedAt }
                var total = ordered.sumOf { it.estimatedBytes() }
                val evicted = mutableListOf<String>()
                for (e in ordered) {
                    if (total <= MAX_CACHE_BYTES) break
                    evicted += e.id
                    total -= e.estimatedBytes()
                }
                if (evicted.isNotEmpty()) evicted.forEach { dao.delete(it) }

                val toUpsert = pending.filter { it.id !in evicted }
                if (toUpsert.isNotEmpty()) dao.upsertAll(toUpsert)
            }
        }
    }

    companion object {
        /** Hard cap for the recents cache: ~2 MB of metadata/links. */
        const val MAX_CACHE_BYTES = 2L * 1024L * 1024L
    }
}
