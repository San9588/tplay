package dev.tplay.player

import android.content.Context
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dev.tplay.TermPlayApp
import dev.tplay.data.youtube.OkHttpDownloader

class PlayerService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    companion object {
        private const val TAG = "tplay-player"

        // Exposed to PlayerConnection/HapticBass; ExoPlayer's audio session id is not
        // available on the media3 1.4.1 Player interface, so it is allocated in-process.
        @Volatile
        var audioSessionId: Int = 0
    }

    override fun onCreate() {
        super.onCreate()
        val player = buildPlayer(this)
        audioSessionId = player.audioSessionId
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "player error: ${error.errorCodeName} ${error.message}", error)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_IDLE) {
                    player.playerError?.let { Log.e(TAG, "player idle: ${it.errorCodeName} ${it.message}") }
                }
                if (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING) {
                    val id = player.audioSessionId
                    if (id != 0 && id != audioSessionId) audioSessionId = id
                }
            }
        })
        val session = MediaSession.Builder(this, player).build()
        mediaSession = session
        // DefaultMediaNotificationProvider.Builder has no notification-timeout option in
        // media3 1.4.1 (the "shedding" in the logs was caused by the stream-error loop,
        // which the extractor upgrade fixes at the root).
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build(),
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    private fun buildPlayer(context: Context): ExoPlayer {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                50_000,
                100_000,
                2_500,
                5_000,
            )
            .build()
        val renderersFactory = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
        // YouTube (googlevideo) streams frequently reject requests that don't carry a
        // browser-like User-Agent / Referer, so route media through an HTTP factory that
        // sends them on every request. OkHttpDataSource reuses the same OkHttp client as
        // NewPipeExtractor (same UA, connection pool, TLS config) for consistent behavior.
        val httpFactory = OkHttpDataSource.Factory((application as TermPlayApp).container.okHttp)
            // (Redirects incl. cross-protocol are followed by OkHttp itself by default.)
            .setDefaultRequestProperties(
                mapOf(
                    "User-Agent" to OkHttpDownloader.USER_AGENT,
                    "Referer" to "https://www.youtube.com/",
                ),
            )
        // Wrap httpFactory in DefaultDataSource.Factory so that local content:// and file://
        // audio files work alongside online HTTP/HTTPS YouTube streams.
        val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)
        val player = ExoPlayer.Builder(context, renderersFactory)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(context).setDataSourceFactory(dataSourceFactory),
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus= */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setPauseAtEndOfMediaItems(false)
            .build()
        // Audio offload is disabled so that android.media.audiofx.Visualizer can attach
        // to the audio session and inspect FFT frequencies for VBASS (haptic bass).
        // (Hardware DSP offload bypasses the software audio mixer and breaks Visualizer.)
        val offloadPrefs = TrackSelectionParameters.AudioOffloadPreferences.Builder()
            .setAudioOffloadMode(
                TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED,
            )
            .setIsGaplessSupportRequired(true)
            .build()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setAudioOffloadPreferences(offloadPrefs)
            .build()
        return player
    }
}
