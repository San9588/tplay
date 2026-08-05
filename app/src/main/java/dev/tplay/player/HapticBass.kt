package dev.tplay.player

import android.content.Context
import android.media.audiofx.Visualizer
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import kotlin.math.max
import kotlin.math.min

/**
 * "VBASS" — emulates a subwoofer low-pass filter by driving the device vibrator with
 * only the low-frequency content of the currently playing audio.
 *
 * A [Visualizer] attached to the active audio session provides FFT data. The energy in the
 * bass band (<= 250 Hz) is mapped to a vibration amplitude scaled by the user selected step
 * (1..4). When VBASS is off (or no bass is present) vibration is stopped.
 */
object HapticBass {

    private const val TAG = "tplay-haptic"
    private const val BASS_LIMIT_HZ = 250
    private const val DRIVE_INTERVAL_MS = 60L
    private const val STALE_THRESHOLD_MS = 600L

    private val lock = Any()

    private var vibrator: Vibrator? = null
    private var visualizer: Visualizer? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var driveThread: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    private var step = 2

    @Volatile
    private var bassLevel = 0f

    @Volatile
    private var lastCaptureMs = 0L

    fun start(context: Context, audioSessionId: Int, step: Int) {
        synchronized(lock) {
            stop()
            if (audioSessionId <= 0) return
            this.step = step.coerceIn(1, 4)
            try {
                vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                    vm.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                }

                val thread = HandlerThread("tplay-haptic-capture").apply { start() }
                captureThread = thread
                captureHandler = Handler(thread.looper)

                captureHandler?.post {
                    runCatching {
                        val vis = Visualizer(audioSessionId)
                        val range = Visualizer.getCaptureSizeRange()
                        vis.captureSize = range[1]
                        vis.setDataCaptureListener(
                            object : Visualizer.OnDataCaptureListener {
                                override fun onWaveFormDataCapture(
                                    v: Visualizer?,
                                    waveform: ByteArray?,
                                    samplingRate: Int,
                                ) = Unit

                                override fun onFftDataCapture(
                                    v: Visualizer?,
                                    fft: ByteArray?,
                                    samplingRate: Int,
                                ) {
                                    if (fft == null || !running) return
                                    bassLevel = computeBass(fft, samplingRate)
                                    lastCaptureMs = System.currentTimeMillis()
                                }
                            },
                            Visualizer.getMaxCaptureRate() / 2,
                            /* waveform= */ false,
                            /* fft= */ true,
                        )
                        vis.enabled = true
                        synchronized(lock) {
                            if (running) visualizer = vis else vis.release()
                        }
                    }.onFailure { e ->
                        Log.w(TAG, "visualizer failed: ${e.message}")
                        synchronized(lock) {
                            running = false
                        }
                    }
                }

                running = true
                driveThread = Thread(::driveVibrator, "tplay-haptic-drive").apply {
                    isDaemon = true
                    start()
                }
            } catch (e: Exception) {
                Log.w(TAG, "failed to start haptic bass: ${e.message}")
                stop()
            }
        }
    }

    fun setStep(value: Int) {
        synchronized(lock) {
            step = value.coerceIn(1, 4)
        }
    }

    fun stop() {
        synchronized(lock) {
            running = false
            bassLevel = 0f
            lastCaptureMs = 0L
            driveThread?.interrupt()
            driveThread = null
            captureHandler?.post {
                runCatching { visualizer?.enabled = false }
                runCatching { visualizer?.release() }
                visualizer = null
            }
            captureThread?.quitSafely()
            captureThread = null
            captureHandler = null
            runCatching { vibrator?.cancel() }
            vibrator = null
        }
    }

    // FFT data is pairs of (real, imag) bytes. Magnitude of the first `bins` covers the
    // low frequencies up to BASS_LIMIT_HZ. Returns a normalized 0..1 bass level.
    private fun computeBass(fft: ByteArray, samplingRate: Int): Float {
        if (samplingRate <= 0 || fft.size < 8) return 0f
        val nyquist = samplingRate / 2.0
        val totalBins = fft.size / 2
        val bandBins = ((BASS_LIMIT_HZ / nyquist) * totalBins).toInt().coerceIn(2, totalBins)
        var energy = 0.0
        for (i in 1 until bandBins) {
            val real = fft[i * 2].toDouble() / 128.0
            val imag = fft[i * 2 + 1].toDouble() / 128.0
            energy += real * real + imag * imag
        }
        val avg = energy / (bandBins - 1)
        // Empirically scaled so typical music bass sits in a useful range.
        return (avg / 0.25).toFloat().coerceIn(0f, 1f)
    }

    private fun driveVibrator() {
        while (running) {
            val stale = System.currentTimeMillis() - lastCaptureMs > STALE_THRESHOLD_MS
            val lvl = if (stale) 0f else bassLevel
            val amp = computeAmplitude(lvl)
            val vib = vibrator
            if (vib != null && amp > 0 && running) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    runCatching { vib.vibrate(VibrationEffect.createOneShot(45, amp)) }
                } else {
                    @Suppress("DEPRECATION")
                    runCatching { vib.vibrate(45) }
                }
            } else {
                runCatching { vib?.cancel() }
            }
            try {
                Thread.sleep(DRIVE_INTERVAL_MS)
            } catch (e: InterruptedException) {
                break
            }
        }
        runCatching { vibrator?.cancel() }
    }

    private fun computeAmplitude(level: Float): Int {
        if (level <= 0.03f) return 0
        // step 1..4 linearly scales intensity.
        val scaled = level * step * 0.30f
        return min(255, max(1, (scaled * 255).toInt()))
    }
}
