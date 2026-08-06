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
import dev.tplay.data.model.YtQuality
import dev.tplay.data.model.YtStage
import dev.tplay.player.PlayerConnection
import dev.tplay.player.PlayerUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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

    // live pipeline status shown as a single strip on the YouTube tab
    var ytStage by mutableStateOf(YtStage.IDLE)
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
    private var lastCoverMode = "ascii"
    private var lastAsciiCols = 96

    // lyrics
    var lyrics by mutableStateOf<Lyrics?>(null)
        private set
    var lyricsLoading by mutableStateOf(false)
        private set
    var showLyrics by mutableStateOf(false)
        private set

    private var lastLyricsSongId: String? = null

    // ---- recent songs (what the user actually plays) ----
    // Held in memory while the app runs (2 MB budget), batch-written to disk on close,
    // and shown as the base list on the YouTube tab. `pendingRecents` is strictly this
    // session's plays; `dbRecents` is what was loaded from disk at startup.
    private val pendingRecents = LinkedHashMap<String, RecentSongEntity>()
    private var dbRecents: List<Song> = emptyList()
    private var recentList: List<Song> = emptyList()
    private var showingRecents = false
    private var lastRecentSongId: String? = null

    // Detached from viewModelScope so the final batch write survives onDestroy cancelling it.
    private val flushScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // In-flight background fill of a tapped song's suggestions; cancelled when the user
    // starts another queue so old suggestions can't leak into the new one.
    private var relatedFillJob: Job? = null

    private fun recordRecent(song: Song?) {
        if (song == null || song.source != SongSource.YOUTUBE) return
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
        rebuildRecentList()
        if (showingRecents) searchResults = recentList
    }

    /** Live recents view: this session's plays (newest first) over the disk-loaded base. */
    private fun rebuildRecentList() {
        val sessionIds = pendingRecents.keys
        val base = dbRecents.filter { it.id !in sessionIds }
        val session = pendingRecents.values.toList().asReversed().map { it.toSongJson().toSong() }
        recentList = session + base
    }

    /** Called when the app is closing/backgrounding — persists pending entries in one batch. */
    fun flushRecentSongs() {
        if (pendingRecents.isEmpty()) return
        val batch = pendingRecents.values.toList()
        pendingRecents.clear()
        // The batch is now on disk — fold it into the base so a background/resume cycle
        // doesn't drop these songs from the live recents list.
        val batchById = batch.associateBy { it.id }
        val sessionSongs = batchById.values.toList().map { it.toSongJson().toSong() }
        dbRecents = sessionSongs + dbRecents.filter { it.id !in batchById }
        rebuildRecentList()
        if (showingRecents) searchResults = recentList
        flushScope.launch { container.recentSongsRepository.batchWrite(batch) }
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
                // Recents = what actually starts playing (tap, next/prev, playlist advance),
                // not everything that was resolved/enqueued.
                if (st.currentSong?.id != lastRecentSongId) {
                    lastRecentSongId = st.currentSong?.id
                    recordRecent(st.currentSong)
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
                // Live pipeline status for the YouTube tab strip.
                if (st.isPlaying && st.currentSong?.source == SongSource.YOUTUBE) {
                    ytStage = YtStage.PLAYING
                } else if (ytStage == YtStage.PLAYING) {
                    ytStage = if (st.currentSong?.source == SongSource.YOUTUBE) {
                        YtStage.READY
                    } else {
                        YtStage.IDLE
                    }
                }
            }
        }
        // Reload the cover when the cover mode / ascii width setting changes, so the
        // change takes effect on the current track too.
        viewModelScope.launch {
            settings.collect { s ->
                if (s.coverMode != lastCoverMode || s.asciiCols != lastAsciiCols) {
                    lastCoverMode = s.coverMode
                    lastAsciiCols = s.asciiCols
                    playerState.value.currentSong?.let { loadCover(it) }
                }
            }
        }
        refreshLibrary()
    }

    // Repository callbacks arrive on the IO dispatcher; hop to main before writing the
    // compose state that drives the status strip.
    private fun stageUpdater(): (YtStage) -> Unit = { s ->
        viewModelScope.launch(Dispatchers.Main.immediate) { ytStage = s }
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
            dbRecents = container.recentSongsRepository.loadRecent(60)
                .map { it.toSongJson().toSong() }
            rebuildRecentList()
            if (recentList.isNotEmpty()) {
                searchResults = recentList
                showingRecents = true
                ytStage = YtStage.READY
            } else {
                val trending = runCatching {
                    container.youtubeRepository.trendingIndia(onStage = stageUpdater())
                }
                    .onFailure {
                        Log.e(TAG, "trending failed", it)
                        ytStage = YtStage.IDLE
                    }
                    .getOrDefault(emptyList())
                searchResults = trending
                showingRecents = true
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
        relatedFillJob?.cancel()
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
            val results = runCatching { container.youtubeRepository.search(q, onStage = stageUpdater()) }
                .onFailure {
                    Log.e(TAG, "search failed", it)
                    ytStage = YtStage.IDLE
                }
                .getOrDefault(emptyList())
            searchResults = results
            showingRecents = false
            if (results.isEmpty()) youtubeError = "search failed or no results"
            searchLoading = false
        }
    }

    fun playYoutubeQueue(index: Int) {
        val songs = searchResults
        if (songs.isEmpty()) return
        youtubeError = null
        viewModelScope.launch {
            relatedFillJob?.cancel()
            val target = songs[index]
            val targetId = target.videoId ?: return@launch
            resolvingId = targetId
            player?.setLoading(true)
            // Fast start: only the tapped song resolves before playback begins (~2s).
            val rv = runCatching {
                container.youtubeRepository.resolveVideo(targetId, onStage = stageUpdater())
            }.onFailure { e ->
                youtubeError = e.message ?: "failed to resolve stream"
                ytStage = YtStage.IDLE
                resolvingId = null
                player?.setLoading(false)
                return@launch
            }.getOrThrow()
            resolvingId = null
            player?.setLoading(false)
            recordRecent(rv.song)
            player?.playSong(rv.song)
            showPlayer = true
            // Search results are transient — after picking one, the tab goes back to the
            // (live) recents list with the picked song on top.
            if (!showingRecents) {
                showingRecents = true
                searchResults = recentList
            }
            // Background: resolve YouTube's suggested songs for the tapped track and append
            // them, so the queue (player panel + QUEUE tab) fills without blocking playback.
            val related = rv.related
            relatedFillJob = if (related.isNotEmpty()) {
                viewModelScope.launch {
                    runCatching {
                        val resolved = container.youtubeRepository.resolveQueue(related, onStage = stageUpdater())
                        resolved.forEach { player?.enqueue(it) }
                    }.onFailure { e ->
                        Log.e(TAG, "related queue resolve failed", e)
                        ytStage = YtStage.READY
                    }
                }
            } else {
                null
            }
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
            relatedFillJob?.cancel()
            val songs = container.database.playlistDao().getSongs(id).map { it.toSong() }
            if (songs.isEmpty()) return@launch
            val start = index.coerceIn(0, songs.lastIndex)
            val needsResolve = songs.any { it.source == SongSource.YOUTUBE && it.videoId != null }
            if (needsResolve) {
                player?.setLoading(true)
                val resolved = container.youtubeRepository.resolveQueue(songs)
                player?.setLoading(false)
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

    fun startSleep(minutes: Int) = player?.startSleepTimer(minutes)
    fun cancelSleep() = player?.cancelSleepTimer()

    // ---- haptic bass ----

    fun setVbassFreq(hz: Int) {
        viewModelScope.launch {
            container.settingsStore.setVbassFreq(hz)
        }
        dev.tplay.player.HapticBass.setFreq(hz)
    }

    fun setAsciiCols(cols: Int) {
        viewModelScope.launch {
            container.settingsStore.setAsciiCols(cols)
        }
    }

    fun setSearchMode(mode: String) {
        viewModelScope.launch {
            container.settingsStore.setSearchMode(mode)
        }
    }

    fun setCoverMode(mode: String) {
        viewModelScope.launch {
            container.settingsStore.setCoverMode(mode)
        }
    }

    fun toggleHapticBass() {
        if (hapticBassEnabled) {
            hapticBassEnabled = false
            dev.tplay.player.HapticBass.stop()
        } else {
            val pc = player ?: return
            if (pc.audioSessionId() <= 0) return
            hapticBassEnabled = true
            dev.tplay.player.HapticBass.start(container.appContext, hapticBassStep, settings.value.vbassFreq)
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

    fun setWifiQuality(q: YtQuality) {
        viewModelScope.launch {
            container.settingsStore.setWifiQuality(q.name)
        }
    }

    fun setMobileQuality(q: YtQuality) {
        viewModelScope.launch {
            container.settingsStore.setMobileQuality(q.name)
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
        val mode = settings.value.coverMode
        asciiCover = if (bitmap != null) {
            coverError = false
            if (mode == "normal") {
                bitmap.asImageBitmap()
            } else {
                container.artCache.toAsciiAsync(song.id, bitmap, settings.value.asciiCols)
            }
        } else {
            coverError = true
            container.artCache.placeholderAsync(song.id, song.id.hashCode().toLong())
        }
    }

    companion object {
        private const val TAG = "tplay-vm"
    }
}
