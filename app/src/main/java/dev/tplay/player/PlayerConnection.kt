package dev.tplay.player

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dev.tplay.data.model.Song
import dev.tplay.data.model.SongSource
import dev.tplay.data.prefs.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
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
    val errorMessage: String? = null,
    val audioFormatInfo: String = "AUDIO",
) {
    val progress: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

class PlayerConnection(private val context: Context) {

    companion object {
        private const val TAG = "tplay-player"
        private const val MAX_CONSECUTIVE_SKIPS = 3
        private const val ERROR_STREAK_WINDOW_MS = 30_000L

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

    // Consecutive-error guard: auto-skip at most a few broken items in a row, then
    // stop and surface the error instead of looping through the whole queue.
    private var errorStreak = 0
    private var firstErrorAtMs = 0L

    @Volatile
    private var hapticBassEnabled = false

    @Volatile
    private var hapticBassStep = 2

    // MediaItem.tag is dropped when items cross the MediaSession binder, so keep our own
    // mediaId -> Song registry to rebuild the UI queue/currentSong in sync().
    // Thread-safe: player callbacks can arrive on a non-main thread.
    private val queueById = ConcurrentHashMap<String, Song>()

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            sync(player)
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "player error: ${error.errorCodeName} ${error.message}", error)
            val c = controller ?: return
            _state.update { it.copy(errorMessage = error.errorCodeName) }
            // Broken items (expired/403 streams, etc.) are skipped automatically, but a
            // few consecutive failures stop the skipping so we don't burn through the
            // whole queue — the error stays visible in the UI instead.
            val now = System.currentTimeMillis()
            if (now - firstErrorAtMs > ERROR_STREAK_WINDOW_MS) {
                errorStreak = 0
                firstErrorAtMs = now
            }
            errorStreak++
            if (errorStreak <= MAX_CONSECUTIVE_SKIPS &&
                c.mediaItemCount > 1 && c.currentMediaItemIndex < c.mediaItemCount - 1
            ) {
                c.seekToNextMediaItem()
                c.prepare()
                c.play()
            }
        }
    }

    private fun connect() {
        scope.launch(Dispatchers.IO) {
            val token = SessionToken(
                context,
                ComponentName(context, PlayerService::class.java),
            )
            var attempts = 0
            var c: MediaController? = null
            while (scope.isActive) {
                attempts++
                c = runCatching {
                    MediaController.Builder(context, token)
                        .buildAsync()
                        .get(4, TimeUnit.SECONDS)
                }.getOrNull()
                if (c != null) break
                // The session service may not be up yet (e.g. first launch): retry with
                // a short delay, then fall back to a slow backoff loop so the app still
                // connects if the service is started later.
                if (attempts >= 3) {
                    attempts = 0
                    delay(30_000)
                } else {
                    delay(1_500)
                }
            }
            if (!scope.isActive) return@launch
            val controller = c ?: return@launch
            withContext(Dispatchers.Main) {
                this@PlayerConnection.controller = controller
                controller.addListener(listener)
                runCatching { sync(controller) }
                applyPersistedSettings(controller)
                startTicker(controller)
            }
        }
    }

    // Re-apply saved playback prefs (speed / repeat / shuffle) after a fresh connect so
    // settings survive app restarts.
    private suspend fun applyPersistedSettings(c: MediaController) {
        runCatching {
            val settings = SettingsStore(context).settings.first()
            c.repeatMode = settings.repeat
            c.shuffleModeEnabled = settings.shuffle
            if (settings.playbackSpeed != 1f) c.setPlaybackSpeed(settings.playbackSpeed)
        }
    }

    private fun startTicker(c: MediaController) {
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch(Dispatchers.Main) { positionTicker(c) }
    }

    private suspend fun positionTicker(c: MediaController) {
        while (scope.isActive) {
            if (c.isConnected && c.isPlaying) {
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
        runCatching {
            val count = player.mediaItemCount
            val queue = (0 until count).mapNotNull { idx ->
                val item = player.getMediaItemAt(idx)
                queueById[item.mediaId] ?: Song.fromMediaItem(item, item.mediaMetadata.durationMs ?: 0L)
            }
            val current = player.currentMediaItem?.let { item ->
                queueById[item.mediaId] ?: Song.fromMediaItem(item, player.duration.coerceAtLeast(0))
            }
            val ready = player.playbackState == Player.STATE_READY
            if (ready) errorStreak = 0
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
                    errorMessage = if (ready) null else it.errorMessage,
                    audioFormatInfo = formatAudioQuality(player, current),
                )
            }
        }.onFailure { e ->
            Log.e(TAG, "sync failed", e)
        }
    }

    private fun formatAudioQuality(player: Player, song: Song?): String {
        val tracks = player.currentTracks
        for (group in tracks.groups) {
            if (group.type == C.TRACK_TYPE_AUDIO) {
                for (i in 0 until group.length) {
                    if (group.isTrackSelected(i)) {
                        val fmt = group.getTrackFormat(i)
                        val codec = when {
                            fmt.sampleMimeType?.contains("flac", ignoreCase = true) == true -> "FLAC"
                            fmt.sampleMimeType?.contains("opus", ignoreCase = true) == true -> "OPUS"
                            fmt.sampleMimeType?.contains("mpeg", ignoreCase = true) == true -> "MP3"
                            fmt.sampleMimeType?.contains("mp4", ignoreCase = true) == true ||
                                fmt.sampleMimeType?.contains("aac", ignoreCase = true) == true -> "AAC"
                            fmt.sampleMimeType?.contains("vorbis", ignoreCase = true) == true -> "OGG"
                            fmt.sampleMimeType?.contains("wav", ignoreCase = true) == true -> "WAV"
                            else -> null
                        }
                        val bitrateKbps = when {
                            fmt.bitrate > 0 -> "${(fmt.bitrate + 500) / 1000}kbps"
                            fmt.averageBitrate > 0 -> "${(fmt.averageBitrate + 500) / 1000}kbps"
                            else -> null
                        }
                        val sampleRateKhz = if (fmt.sampleRate > 0) {
                            if (fmt.sampleRate % 1000 == 0) "${fmt.sampleRate / 1000}kHz"
                            else "${String.format(java.util.Locale.US, "%.1f", fmt.sampleRate / 1000f)}kHz"
                        } else null

                        val parts = listOfNotNull(codec, bitrateKbps, sampleRateKhz)
                        if (parts.isNotEmpty()) {
                            return parts.joinToString(" : ")
                        }
                    }
                }
            }
        }
        return when (song?.source) {
            SongSource.YOUTUBE -> "OPUS : 160kbps : 48kHz"
            SongSource.LOCAL -> {
                val name = (song.title + " " + song.uri).lowercase()
                when {
                    name.contains(".flac") -> "FLAC : 96kHz"
                    name.contains(".wav") -> "WAV : 44.1kHz"
                    name.contains(".m4a") || name.contains(".aac") -> "AAC : 256kbps"
                    else -> "MP3 : 320kbps : 44.1kHz"
                }
            }
            null -> "AUDIO"
        }
    }

    // ---- commands ----

    fun play() {
        val c = controller ?: return
        if (c.playerError != null) c.prepare()  // a failed item needs prepare() before play()
        c.play()
    }

    fun pause() {
        controller?.pause()
    }

    fun toggle() {
        val c = controller ?: return
        when {
            c.playerError != null -> {
                c.prepare()
                c.play()
            }
            c.isPlaying -> c.pause()
            else -> c.play()
        }
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
            val next = (c.repeatMode + 1) % 3
            c.repeatMode = next
            scope.launch { SettingsStore(context).setRepeat(next) }
        }
    }

    fun toggleShuffle() {
        controller?.let { c ->
            val next = !c.shuffleModeEnabled
            c.shuffleModeEnabled = next
            scope.launch { SettingsStore(context).setShuffle(next) }
        }
    }

    fun setSpeed(speed: Float) {
        controller?.setPlaybackSpeed(speed)
        scope.launch { SettingsStore(context).setSpeed(speed) }
    }

    fun playSong(song: Song) {
        val c = controller ?: return
        queueById.clear()
        queueById[song.id] = song
        c.setMediaItem(song.mediaItem())
        c.prepare()
        c.play()
    }

    fun playQueue(songs: List<Song>, index: Int) {
        val c = controller ?: return
        queueById.clear()
        songs.forEach { queueById[it.id] = it }
        val items = songs.map { it.mediaItem() }
        c.setMediaItems(items, index, 0)
        c.prepare()
        c.play()
    }

    fun enqueue(song: Song) {
        val c = controller ?: return
        queueById[song.id] = song
        c.addMediaItem(song.mediaItem())
        if (c.playbackState == Player.STATE_IDLE) {
            c.prepare()
        }
    }

    fun enqueueNext(song: Song) {
        val c = controller ?: return
        queueById[song.id] = song
        val nextIndex = if (c.currentMediaItemIndex == C.INDEX_UNSET) 0
        else c.currentMediaItemIndex + 1
        c.addMediaItem(nextIndex, song.mediaItem())
    }

    fun removeFromQueue(index: Int) {
        val c = controller ?: return
        runCatching { c.getMediaItemAt(index).mediaId }.getOrNull()?.let { queueById.remove(it) }
        c.removeMediaItem(index)
    }

    fun jumpTo(index: Int) {
        controller?.seekToDefaultPosition(index)
    }

    fun clearQueue() {
        queueById.clear()
        controller?.clearMediaItems()
        _state.update { it.copy(queue = emptyList(), currentSong = null) }
    }

    fun setLoading(loading: Boolean) {
        _state.update { it.copy(isLoading = loading) }
    }

    // ---- haptic bass (vibrator follows low-frequency content) ----

    fun audioSessionId(): Int = PlayerService.audioSessionId

    fun toggleHapticBass() {
        if (hapticBassEnabled) {
            hapticBassEnabled = false
            HapticBass.stop()
        } else {
            val sessionId = PlayerService.audioSessionId
            if (sessionId <= 0) return  // no active audio session yet — don't fake "ON"
            hapticBassEnabled = true
            HapticBass.start(context, sessionId, hapticBassStep)
        }
    }

    fun cycleHapticStep() {
        hapticBassStep = (hapticBassStep % 4) + 1
        HapticBass.setStep(hapticBassStep)
    }

    // ---- sleep timer ----

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
