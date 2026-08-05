package dev.tplay.ui

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.tplay.core.AppContainer
import dev.tplay.data.local.PlaylistEntity
import dev.tplay.data.local.PlaylistWithSongs
import dev.tplay.data.local.RecentSongEntity
import dev.tplay.data.local.RecentSongsRepository
import dev.tplay.data.local.SongJson
import dev.tplay.data.lyrics.Lyrics
import dev.tplay.data.model.Song
import dev.tplay.data.model.SongSource
import dev.tplay.player.PlayerConnection
import dev.tplay.player.PlayerUiState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class Tab { LIBRARY, YOUTUBE, PLAYLISTS, QUEUE, SETTINGS }

fun Song.toJson(): SongJson = SongJson(
    id = id,
    title = title,
    artist = artist,
    album = album,
    durationMs = durationMs,
    uri = uri,
    artUri = artUri,
    source = source.name,
    albumId = albumId,
    videoId = videoId,
    channel = channel,
)

fun SongJson.toSong(): Song = Song(
    id = id,
    title = title,
    artist = artist,
    album = album,
    durationMs = durationMs,
    uri = uri,
    artUri = artUri,
    source = if (source == SongSource.YOUTUBE.name) SongSource.YOUTUBE else SongSource.LOCAL,
    albumId = albumId,
    videoId = videoId,
    channel = channel,
)

class MainViewModel(
    private val container: AppContainer,
) : ViewModel() {

    private val player: PlayerConnection? get() = PlayerConnection.get()

    val playerState: StateFlow<PlayerUiState> =
        (player?.state ?: kotlinx.coroutines.flow.MutableStateFlow(PlayerUiState()))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlayerUiState())

    val settings = container.settingsStore.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), dev.tplay.data.prefs.Settings())

    // navigation
    var tab by mutableStateOf(Tab.LIBRARY)
        private set
    var showPlayer by mutableStateOf(false)
        private set

    // library
    var localSongs by mutableStateOf<List<Song>>(emptyList())
        private set
    var libraryLoading by mutableStateOf(false)
        private set
    var libraryScanned by mutableStateOf(false)
        private set

    // youtube
    var searchQuery by mutableStateOf("")
        private set
    var searchResults by mutableStateOf<List<Song>>(emptyList())
        private set
    var searchLoading by mutableStateOf(false)
        private set
    var resolvingId by mutableStateOf<String?>(null)
        private set
    var youtubeError by mutableStateOf<String?>(null)
        private set

    // playlists
    val playlists: StateFlow<List<PlaylistEntity>> = container.database.playlistDao()
        .observePlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var currentPlaylistId by mutableStateOf<Long?>(null)
        private set

    // haptic bass (vbass / vstep)
    var hapticBassEnabled by mutableStateOf(false)
        private set
    var hapticBassStep by mutableStateOf(2)
        private set

    // cover
    var asciiCover by mutableStateOf<ImageBitmap?>(null)
        private set
    var coverError by mutableStateOf(false)
        private set

    private var lastCoverSongId: String? = null

    // lyrics
    var lyrics by mutableStateOf<Lyrics?>(null)
        private set
    var lyricsLoading by mutableStateOf(false)
        private set
    var showLyrics by mutableStateOf(false)
        private set

    private var lastLyricsSongId: String? = null

    // ---- recent songs cache (in-memory until the app closes, then one batch write) ----

    private val pendingRecents = LinkedHashMap<String, RecentSongEntity>()

    private fun recordRecent(song: Song) {
        if (song.source != SongSource.YOUTUBE) return
        val entry = RecentSongEntity(
            id = song.id,
            title = song.title,
            artist = song.artist,
            album = song.album,
            durationMs = song.durationMs,
            uri = song.uri,
            artUri = song.artUri,
            source = song.source.name,
            albumId = song.albumId,
            videoId = song.videoId,
            channel = song.channel,
            playedAt = System.currentTimeMillis(),
        )
        pendingRecents.remove(entry.id)  // re-insert at the tail = freshest last
        pendingRecents[entry.id] = entry
        // Keep the in-memory cache inside the same 2 MB budget as the disk cache.
        var total = pendingRecents.values.sumOf { it.estimatedBytes() }
        val it = pendingRecents.entries.iterator()
        while (total > RecentSongsRepository.MAX_CACHE_BYTES && it.hasNext()) {
            val oldest = it.next()
            it.remove()
            total -= oldest.value.estimatedBytes()
        }
    }

    /** Called when the app is closing — persists all pending entries in one batch. */
    fun flushRecentSongs() {
        if (pendingRecents.isEmpty()) return
        val batch = pendingRecents.values.toList()
        pendingRecents.clear()
        viewModelScope.launch { container.recentSongsRepository.batchWrite(batch) }
    }

    init {
        viewModelScope.launch {
            playerState.collect { st ->
                if (st.currentSong?.id != lastCoverSongId) {
                    lastCoverSongId = st.currentSong?.id
                    loadCover(st.currentSong)
                }
                if (st.currentSong?.id != lastLyricsSongId) {
                    lastLyricsSongId = st.currentSong?.id
                    loadLyrics(st.currentSong)
                }
                // Surface playback failures (expired/403 streams) in the YouTube tab.
                val err = st.errorMessage
                if (err != null && st.currentSong?.source == SongSource.YOUTUBE &&
                    (youtubeError == null || youtubeError!!.startsWith("playback"))
                ) {
                    youtubeError = "playback: $err"
                } else if (err == null && youtubeError?.startsWith("playback") == true) {
                    youtubeError = null
                }
            }
        }
        refreshLibrary()
    }

    // ---- navigation ----

    fun selectTab(t: Tab) {
        tab = t
        when (t) {
            Tab.LIBRARY -> refreshLibrary()
            Tab.YOUTUBE -> ensureYoutubeContent()
            else -> Unit
        }
    }

    /**
     * First open of the YouTube tab (or after a restart, when nothing has been searched
     * yet): show the last played songs (freshest on top); on a truly first run show the
     * India trending list instead of a blank page.
     */
    private fun ensureYoutubeContent() {
        if (searchResults.isNotEmpty() || searchLoading) return
        viewModelScope.launch {
            searchLoading = true
            youtubeError = null
            val recents = container.recentSongsRepository.loadRecent(60)
                .map { it.toSongJson().toSong() }
            if (recents.isNotEmpty()) {
                searchResults = recents
            } else {
                val trending = runCatching { container.youtubeRepository.trendingIndia() }
                    .onFailure { Log.e(TAG, "trending failed", it) }
                    .getOrDefault(emptyList())
                searchResults = trending
                if (trending.isEmpty()) youtubeError = "trending unavailable — try a search"
            }
            searchLoading = false
        }
    }

    fun openPlayer() {
        showPlayer = true
    }

    fun closePlayer() {
        showPlayer = false
    }

    // ---- library ----

    fun refreshLibrary() {
        if (libraryLoading) return
        viewModelScope.launch {
            libraryLoading = true
            val songs = container.libraryRepository.scan()
            localSongs = songs
            libraryScanned = true
            libraryLoading = false
        }
    }

    fun playLibraryFrom(index: Int) {
        val songs = localSongs
        if (songs.isEmpty()) return
        player?.playQueue(songs.map { it }, index)
    }

    // ---- youtube ----

    fun updateSearchQuery(q: String) {
        searchQuery = q
    }

    fun search() {
        val q = searchQuery.trim()
        if (q.isEmpty() || searchLoading) return
        viewModelScope.launch {
            searchLoading = true
            youtubeError = null
            val results = runCatching { container.youtubeRepository.search(q) }
                .onFailure { Log.e(TAG, "search failed", it) }
                .getOrDefault(emptyList())
            searchResults = results
            if (results.isEmpty()) youtubeError = "search failed or no results"
            searchLoading = false
        }
    }

    fun playYoutube(song: Song) {
        val id = song.videoId ?: return
        youtubeError = null
        viewModelScope.launch {
            resolvingId = id
            player?.setLoading(true)
            runCatching {
                val resolved = container.youtubeRepository.resolveVideo(id)
                recordRecent(resolved)
                player?.playSong(resolved)
            }.onFailure { e ->
                youtubeError = e.message ?: "failed to resolve stream"
                resolvingId = null
                player?.setLoading(false)
            }
        }.invokeOnCompletion {
            resolvingId = null
            player?.setLoading(false)
        }
    }

    fun playYoutubeQueue(index: Int) {
        val songs = searchResults
        if (songs.isEmpty()) return
        youtubeError = null
        viewModelScope.launch {
            player?.setLoading(true)
            runCatching {
                val resolved = container.youtubeRepository.resolveQueue(songs)
                resolved.forEach { recordRecent(it) }
                player?.playQueue(resolved, index)
            }.onFailure { e ->
                youtubeError = e.message ?: "failed to resolve queue"
            }.onSuccess {
                showPlayer = true
            }
            player?.setLoading(false)
        }
    }

    // ---- playlists ----

    fun createPlaylist(name: String) {
        viewModelScope.launch {
            container.database.playlistDao().addPlaylist(PlaylistEntity(name = name))
        }
    }

    fun deletePlaylist(id: Long) {
        viewModelScope.launch {
            container.database.playlistDao().deletePlaylist(id)
            if (currentPlaylistId == id) currentPlaylistId = null
        }
    }

    fun openPlaylist(id: Long) {
        currentPlaylistId = id
    }

    fun closePlaylist() {
        currentPlaylistId = null
    }

    fun addToPlaylist(playlistId: Long, songs: List<Song>) {
        viewModelScope.launch {
            container.database.playlistDao().addToPlaylist(playlistId, songs.map { it.toJson() })
        }
    }

    suspend fun playlistSongs(id: Long): List<Song> =
        container.database.playlistDao().getSongs(id).map { it.toSong() }

    fun playPlaylist(id: Long, index: Int = 0) {
        viewModelScope.launch {
            val songs = container.database.playlistDao().getSongs(id).map { it.toSong() }
            if (songs.isEmpty()) return@launch
            val start = index.coerceIn(0, songs.lastIndex)
            val needsResolve = songs.any { it.source == SongSource.YOUTUBE && it.videoId != null }
            if (needsResolve) {
                player?.setLoading(true)
                val resolved = container.youtubeRepository.resolveQueue(songs)
                player?.setLoading(false)
                resolved.forEach { recordRecent(it) }
                player?.playQueue(resolved, start)
            } else {
                player?.playQueue(songs, start)
            }
            showPlayer = true
        }
    }

    fun addCurrentToPlaylist(playlistId: Long) {
        val song = playerState.value.currentSong ?: return
        addToPlaylist(playlistId, listOf(song))
    }

    // ---- player passthrough ----

    fun toggle() = player?.toggle()
    fun next() = player?.next()
    fun previous() = player?.previous()
    fun seekTo(ms: Long) = player?.seekTo(ms)
    fun cycleRepeat() = player?.cycleRepeat()
    fun toggleShuffle() = player?.toggleShuffle()
    fun setSpeed(speed: Float) = player?.setSpeed(speed)
    fun jumpTo(index: Int) = player?.jumpTo(index)
    fun removeFromQueue(index: Int) = player?.removeFromQueue(index)
    fun enqueue(song: Song) {
        viewModelScope.launch {
            if (song.source == SongSource.YOUTUBE && song.videoId != null) {
                player?.setLoading(true)
                val resolved = runCatching { container.youtubeRepository.resolveVideo(song.videoId!!) }
                    .getOrElse { song }
                player?.setLoading(false)
                recordRecent(resolved)
                player?.enqueue(resolved)
            } else {
                player?.enqueue(song)
            }
        }
    }

    fun startSleep(minutes: Int) = player?.startSleepTimer(minutes)
    fun cancelSleep() = player?.cancelSleepTimer()

    // ---- haptic bass ----

    fun toggleHapticBass() {
        if (hapticBassEnabled) {
            hapticBassEnabled = false
            dev.tplay.player.HapticBass.stop()
        } else {
            val pc = player ?: return
            if (pc.audioSessionId() <= 0) return  // no audio session yet — don't fake "ON"
            hapticBassEnabled = true
            dev.tplay.player.HapticBass.start(container.appContext, pc.audioSessionId(), hapticBassStep)
        }
    }

    fun cycleHapticStep() {
        hapticBassStep = if (hapticBassStep >= 4) 1 else hapticBassStep + 1
        dev.tplay.player.HapticBass.setStep(hapticBassStep)
    }

    // ---- settings ----

    fun setSystemAccent(enabled: Boolean) {
        viewModelScope.launch {
            container.settingsStore.setSystemAccent(enabled)
        }
    }

    // ---- lyrics ----

    fun toggleLyricsPanel() {
        showLyrics = !showLyrics
    }

    fun showLyricsPanel() {
        showLyrics = true
    }

    fun showQueuePanel() {
        showLyrics = false
    }

    private fun loadLyrics(song: Song?) {
        lyricsLoading = true
        lyrics = null
        if (song == null) {
            lyricsLoading = false
            return
        }
        viewModelScope.launch {
            lyrics = container.lyricsRepository.load(song)
            lyricsLoading = false
        }
    }

    // ---- cover ----

    private suspend fun loadCover(song: Song?) {
        asciiCover = null
        coverError = false
        if (song == null) return
        val bitmap = when (song.source) {
            SongSource.LOCAL -> container.artCache.loadArtAsync(
                song.artUri?.let { android.net.Uri.parse(it) },
                song.id,
                512,
            )

            SongSource.YOUTUBE -> song.artUri?.let {
                container.artCache.loadFromUrl(it, song.id, 512)
            }
        }
        asciiCover = if (bitmap != null) {
            coverError = false
            container.artCache.toAsciiAsync(song.id, bitmap)
        } else {
            coverError = true
            container.artCache.placeholderAsync(song.id, song.id.hashCode().toLong())
        }
    }

    companion object {
        private const val TAG = "tplay-vm"
    }
}
