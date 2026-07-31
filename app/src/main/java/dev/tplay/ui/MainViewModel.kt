package dev.tplay.ui

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
import dev.tplay.data.local.SongJson
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

    // playlists
    val playlists: StateFlow<List<PlaylistEntity>> = container.database.playlistDao()
        .observePlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var currentPlaylistId by mutableStateOf<Long?>(null)
        private set

    // cover
    var asciiCover by mutableStateOf<ImageBitmap?>(null)
        private set
    var coverError by mutableStateOf(false)
        private set

    private var lastCoverSongId: String? = null

    init {
        viewModelScope.launch {
            playerState.collect { st ->
                if (st.currentSong?.id != lastCoverSongId) {
                    lastCoverSongId = st.currentSong?.id
                    loadCover(st.currentSong)
                }
            }
        }
        refreshLibrary()
    }

    // ---- navigation ----

    fun selectTab(t: Tab) {
        tab = t
        if (t == Tab.LIBRARY) refreshLibrary()
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
        showPlayer = true
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
            searchResults = container.youtubeRepository.search(q)
            searchLoading = false
        }
    }

    fun playYoutube(song: Song) {
        val id = song.videoId ?: return
        viewModelScope.launch {
            resolvingId = id
            player?.setLoading(true)
            runCatching {
                val resolved = container.youtubeRepository.resolveVideo(id)
                player?.playSong(resolved)
            }.onFailure {
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
        viewModelScope.launch {
            player?.setLoading(true)
            val resolved = container.youtubeRepository.resolveQueue(songs)
            player?.setLoading(false)
            player?.playQueue(resolved, index)
            showPlayer = true
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

    fun playPlaylist(id: Long) {
        viewModelScope.launch {
            val songs = container.database.playlistDao().getSongs(id).map { it.toSong() }
            if (songs.isEmpty()) return@launch
            val needsResolve = songs.any { it.source == SongSource.YOUTUBE && it.videoId != null }
            if (needsResolve) {
                player?.setLoading(true)
                val resolved = container.youtubeRepository.resolveQueue(songs)
                player?.setLoading(false)
                player?.playQueue(resolved, 0)
            } else {
                player?.playQueue(songs, 0)
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
                player?.enqueue(resolved)
            } else {
                player?.enqueue(song)
            }
        }
    }

    fun startSleep(minutes: Int) = player?.startSleepTimer(minutes)
    fun cancelSleep() = player?.cancelSleepTimer()

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
}
