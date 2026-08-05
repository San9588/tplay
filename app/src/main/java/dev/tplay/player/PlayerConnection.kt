package dev.tplay.player

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dev.tplay.data.model.Song
import dev.tplay.data.model.SongSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

data class PlayerUiState(
    val queue: List<Song> = emptyList(),
    val currentIndex: Int = C.INDEX_UNSET,
    val currentSong: Song? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val shuffleEnabled: Boolean = false,
    val speed: Float = 1f,
    val sleepRemainingMs: Long = 0L,
    val isLoading: Boolean = false,
) {
    val progress: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

class PlayerConnection(private val context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: PlayerConnection? = null

        fun init(context: Context) {
            if (INSTANCE == null) {
                synchronized(this) {
                    if (INSTANCE == null) {
                        INSTANCE = PlayerConnection(context)
                        INSTANCE!!.connect()
                    }
                }
            }
        }

        fun get(): PlayerConnection? = INSTANCE
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controller: MediaController? = null
    private var sleepJob: Job? = null
    private var tickerJob: Job? = null

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val listener = object : MediaController.Listener {
        override fun onConnected(controller: MediaController) {
            sync(controller)
            startTicker(controller)
        }

        override fun onEvents(player: Player, events: Player.Events) {
            sync(player)
        }
    }

    private fun connect() {
        scope.launch(Dispatchers.IO) {
            val token = SessionToken(
                context,
                ComponentName(context, PlayerService::class.java),
            )
            val c = runCatching {
                MediaController.Builder(context, token)
                    .buildAsync()
                    .get(4, TimeUnit.SECONDS)
            }.getOrNull() ?: return@launch
            controller = c
            c.addListener(listener)
            if (c.isConnected) {
                sync(c)
                startTicker(c)
            }
        }
    }

    private fun startTicker(c: MediaController) {
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch(Dispatchers.Main) { positionTicker(c) }
    }

    private suspend fun positionTicker(c: MediaController) {
        while (scope.isActive) {
            if (!c.isConnected) break
            if (c.isPlaying) {
                _state.update {
                    it.copy(positionMs = c.currentPosition, durationMs = c.duration.coerceAtLeast(0))
                }
            }
            val remaining = sleepRemainingMs()
            if (remaining >= 0) {
                _state.update { it.copy(sleepRemainingMs = remaining) }
                if (remaining <= 0) {
                    sleepJob = null
                    c.pause()
                    _state.update { it.copy(sleepRemainingMs = 0) }
                }
            }
            delay(500)
        }
    }

    private fun sleepRemainingMs(): Long {
        val until = sleepUntilEpochMs ?: return -1
        return until - System.currentTimeMillis()
    }

    @Volatile
    private var sleepUntilEpochMs: Long? = null

    private fun sync(player: Player) {
        if (player is MediaController && !player.isConnected) return
        val count = player.mediaItemCount
        val queue = (0 until count).mapNotNull { idx ->
            player.getMediaItemAt(idx).localConfiguration?.tag as? Song
        }
        val current = player.currentMediaItem?.localConfiguration?.tag as? Song
        _state.update {
            it.copy(
                queue = queue,
                currentIndex = player.currentMediaItemIndex,
                currentSong = current,
                isPlaying = player.isPlaying,
                isBuffering = player.playbackState == Player.STATE_BUFFERING,
                positionMs = player.currentPosition,
                durationMs = player.duration.coerceAtLeast(0),
                repeatMode = player.repeatMode,
                shuffleEnabled = player.shuffleModeEnabled,
                speed = player.playbackParameters.speed,
            )
        }
    }

    // ---- commands ----

    fun play() {
        controller?.play()
    }

    fun pause() {
        controller?.pause()
    }

    fun toggle() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun next() {
        controller?.seekToNextMediaItem()
    }

    fun previous() {
        controller?.let { c ->
            if (c.currentPosition > 3_000) c.seekTo(0) else c.seekToPreviousMediaItem()
        }
    }

    fun seekTo(ms: Long) {
        controller?.seekTo(ms)
        _state.update { it.copy(positionMs = ms) }
    }

    fun cycleRepeat() {
        controller?.let { c ->
            c.repeatMode = (c.repeatMode + 1) % 3
        }
    }

    fun toggleShuffle() {
        controller?.let { c ->
            c.shuffleModeEnabled = !c.shuffleModeEnabled
        }
    }

    fun setSpeed(speed: Float) {
        controller?.setPlaybackSpeed(speed)
    }

    fun playSong(song: Song) {
        val c = controller ?: return
        c.setMediaItem(song.mediaItem())
        c.prepare()
        c.play()
    }

    fun playQueue(songs: List<Song>, index: Int) {
        val c = controller ?: return
        val items = songs.map { it.mediaItem() }
        c.setMediaItems(items, index, 0)
        c.prepare()
        c.play()
    }

    fun enqueue(song: Song) {
        val c = controller ?: return
        c.addMediaItem(song.mediaItem())
        if (c.playbackState == Player.STATE_IDLE) {
            c.prepare()
        }
    }

    fun enqueueNext(song: Song) {
        val c = controller ?: return
        val nextIndex = if (c.currentMediaItemIndex == C.INDEX_UNSET) 0
        else c.currentMediaItemIndex + 1
        c.addMediaItem(nextIndex, song.mediaItem())
    }

    fun removeFromQueue(index: Int) {
        controller?.removeMediaItem(index)
    }

    fun jumpTo(index: Int) {
        controller?.seekToDefaultPosition(index)
    }

    fun clearQueue() {
        controller?.clearMediaItems()
        _state.update { it.copy(queue = emptyList(), currentSong = null) }
    }

    fun setLoading(loading: Boolean) {
        _state.update { it.copy(isLoading = loading) }
    }

    fun startSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        sleepUntilEpochMs = System.currentTimeMillis() + minutes * 60_000L
        _state.update { it.copy(sleepRemainingMs = minutes * 60_000L) }
    }

    fun cancelSleepTimer() {
        sleepJob?.cancel()
        sleepUntilEpochMs = null
        _state.update { it.copy(sleepRemainingMs = 0L) }
    }

    fun onCleared() {
        scope.cancel()
    }
}
