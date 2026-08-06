package dev.tplay.player

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import kotlinx.coroutines.*

object HapticBass : TeeAudioProcessor.AudioBufferSink {
    val audioBufferSink: TeeAudioProcessor.AudioBufferSink = this
    private const val TAG = "tplay-haptic"
    private var cutoffFreqHz: Int = 100
    private var vibrator: Vibrator? = null
    private var running = false
    private var step = 2
    private var lastPulseMs = 0L
    private var envelope = 0f
    private val threshold = 0.18f
    private var movingAvg = 0f
    private var sustainJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Simple biquad low-pass per sample
    private class BiquadLPF(sampleRate: Int, cutoffHz: Float, q: Float = 0.707f) {
        private var b0 = 0f; private var b1 = 0f; private var b2 = 0f
        private var a1 = 0f; private var a2 = 0f
        private var z1 = 0f; private var z2 = 0f
        init {
            val w0 = (2.0 * Math.PI * cutoffHz / sampleRate).toFloat()
            val alpha = kotlin.math.sin(w0.toDouble()) / (2.0 * q)
            val cosw0 = kotlin.math.cos(w0.toDouble())
            val a0 = 1.0 + alpha
            b0 = ((1.0 - cosw0) / 2.0 / a0).toFloat()
            b1 = ((1.0 - cosw0) / a0).toFloat()
            b2 = b0
            a1 = ((-2.0 * cosw0) / a0).toFloat()
            a2 = ((1.0 - alpha) / a0).toFloat()
        }
        fun process(x: Float): Float {
            val y = b0 * x + z1
            z1 = b1 * x + z2 - a1 * y
            z2 = b2 * x - a2 * y
            return y
        }
    }

    // Assume 48kHz for filter (ExoPlayer common); adjust if needed
    private val lpf = BiquadLPF(48000, cutoffFreqHz.toFloat())

    fun setFreq(hz: Int) {
        cutoffFreqHz = hz
        // Note: filter coeff not recalculated live for simplicity; in production rebuild filter
    }

    fun start(context: Context, step: Int, freqHz: Int = 100) {
        stop()
        this.step = step.coerceIn(1, 4)
        cutoffFreqHz = freqHz
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        running = true
    }

    fun setStep(value: Int) {
        step = value.coerceIn(1, 4)
    }

    fun stop() {
        running = false
        envelope = 0f
        movingAvg = 0f
        sustainJob?.cancel()
        sustainJob = null
        vibrator?.cancel()
        vibrator = null
    }

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {}

    override fun handleBuffer(buffer: ByteBuffer) {
        if (!running || !buffer.hasRemaining()) return
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        var sum = 0.0f
        var count = 0
        var i = 0
        while (i + 1 < bytes.size) {
            val raw = (bytes[i].toInt() and 0xFF) or ((bytes[i + 1].toInt() shl 8) and 0xFF00)
            val signed = if (raw >= 32768) raw - 65536 else raw
            val floatSample = signed / 32768f
            val filtered = lpf.process(floatSample)
            sum += kotlin.math.abs(filtered)
            count++
            i += 2
        }
        val avg = if (count > 0) sum / count else 0.0f
        val rawLevel = avg.coerceIn(0f, 1f)

        movingAvg = movingAvg * 0.92f + rawLevel * 0.08f
        val transient = rawLevel > movingAvg * 1.35f && rawLevel > 0.12f

        envelope = if (transient) {
            kotlin.math.max(rawLevel, envelope * 0.3f)
        } else {
            kotlin.math.max(rawLevel * 0.9f, envelope * 0.85f)
        }
        if (envelope < 0.05f) envelope = 0f

        driveVibrator(transient)
    }

    private fun driveVibrator(isTransient: Boolean) {
        val now = System.currentTimeMillis()

        if (isTransient && envelope > 0.18f && now - lastPulseMs > 250) {
            lastPulseMs = now
            val amp = (step * envelope * 0.30f * 255).toInt().coerceIn(1, 255)
            vibrator?.vibrate(VibrationEffect.createOneShot(40, amp))
            Log.d(TAG, "VBASS attack pulse: amp=$amp cutoff=${cutoffFreqHz}Hz level=$envelope transient=true")
            startSustain()
            return
        }

        if (envelope > 0.15f) {
            startSustain()
        } else {
            sustainJob?.cancel()
        }
    }

    private fun startSustain() {
        if (sustainJob?.isActive == true) return
        sustainJob = scope.launch {
            while (isActive && running && envelope > 0.10f) {
                val amp = (step * envelope * 0.30f * 255).toInt().coerceIn(1, 255)
                vibrator?.vibrate(VibrationEffect.createOneShot(60, amp))
                Log.d(TAG, "VBASS sustain pulse: amp=$amp cutoff=${cutoffFreqHz}Hz level=$envelope")
                delay(55)
            }
        }
    }

    private fun computeAmplitude(level: Float): Int {
        if (level <= 0.03f) return 0
        val scaled = level * step * 0.30f
        return (scaled * 255).toInt().coerceIn(1, 255)
    }
}
