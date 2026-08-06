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
import kotlinx.coroutines.channels.Channel

object HapticBass : TeeAudioProcessor.AudioBufferSink {
    val audioBufferSink: TeeAudioProcessor.AudioBufferSink = this
    private const val TAG = "tplay-haptic"
    private var cutoffFreqHz: Int = 100
    private var vibrator: Vibrator? = null
    private var running = false

    @Volatile
    private var isPaused = false
    private var step = 2
    private var envelope = 0f
    private var movingAvg = 0f

    // How often the render loop pushes a fresh amplitude to the vibrator, independent
    // of audio buffer size/arrival timing. This is what makes the feel continuous
    // instead of "loop-y" - a fixed clock, not audio-driven event triggers.
    private const val TICK_MS = 30L

    // Gate hysteresis: vibration turns ON once envelope climbs above START, and stays
    // ON until it decays below STOP. Prevents rapid on/off flicker for bass that
    // hovers right around the threshold.
    private const val GATE_START = 0.12f
    private const val GATE_STOP = 0.05f

    // Envelope value that maps to full amplitude (255). Content quieter than this
    // scales down proportionally toward FLOOR_AMP; anything above it is clamped to max -
    // this is the "how hard should the loudest bass hit" knob.
    private const val ENVELOPE_FOR_MAX_AMP = 0.85f

    // Most phone LRA/ERM motors don't meaningfully move below roughly this amplitude,
    // so it's the floor for the *quietest felt* bass, not a fixed minimum buzz -
    // amplitude still scales continuously above it with envelope.
    private const val FLOOR_AMP = 45

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Single dispatcher for every vibrator command - only this coroutine ever calls
    // vibrator.vibrate()/cancel(). CONFLATED means if the render loop produces a new
    // amplitude before the previous vibrate() IPC call has been dispatched, only the
    // newest value survives - no backlog, no stale amplitudes queued up.
    private sealed class VibrationCommand {
        data class Pulse(val amp: Int, val durationMs: Long) : VibrationCommand()
        object Stop : VibrationCommand()
    }

    private val vibrationChannel = Channel<VibrationCommand>(Channel.CONFLATED)
    private var dispatcherJob: Job? = null
    private var rendererJob: Job? = null

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
        isPaused = false
        envelope = 0f
        movingAvg = 0f

        // The only coroutine that ever touches vibrator.vibrate()/cancel() directly.
        dispatcherJob = scope.launch {
            for (cmd in vibrationChannel) {
                when (cmd) {
                    is VibrationCommand.Pulse ->
                        vibrator?.vibrate(VibrationEffect.createOneShot(cmd.durationMs, cmd.amp))
                    is VibrationCommand.Stop -> vibrator?.cancel()
                }
            }
        }

        // The continuous envelope-follower: runs on its own fixed clock the entire
        // time VBASS is on, reading whatever `envelope` currently is and mapping it
        // straight to amplitude - like a subwoofer's excursion tracking the signal,
        // not discrete "event" pulses tied to when a threshold was crossed.
        rendererJob = scope.launch {
            var gateOpen = false
            while (isActive && running) {
                if (!isPaused) {
                    gateOpen = when {
                        envelope > GATE_START -> true
                        envelope < GATE_STOP -> false
                        else -> gateOpen
                    }
                    if (gateOpen) {
                        val norm = (envelope / ENVELOPE_FOR_MAX_AMP).coerceIn(0f, 1f)
                        val amp = (FLOOR_AMP + norm * (255 - FLOOR_AMP)).toInt().coerceIn(FLOOR_AMP, 255)
                        // Duration overlaps the next tick slightly so there's no gap
                        // between pulses even if this coroutine gets scheduled a few ms late.
                        vibrationChannel.trySend(VibrationCommand.Pulse(amp, TICK_MS + 20))
                        Log.d(TAG, "VBASS render: amp=$amp cutoff=${cutoffFreqHz}Hz level=$envelope")
                    } else {
                        vibrationChannel.trySend(VibrationCommand.Stop)
                    }
                }
                delay(TICK_MS)
            }
        }
    }

    fun setStep(value: Int) {
        step = value.coerceIn(1, 4)
    }

    fun stop() {
        running = false
        isPaused = false
        envelope = 0f
        movingAvg = 0f
        rendererJob?.cancel()
        rendererJob = null
        dispatcherJob?.cancel()
        dispatcherJob = null
        vibrator?.cancel()
        vibrator = null
    }

    // Lighter than stop(): called on pause/buffering so the vibrator falls silent
    // immediately without tearing down the vibrator handle, dispatcher, or renderer
    // loop (resume() just lets the loop pick back up). Resetting envelope here
    // matters - otherwise it stays frozen at its last value (handleBuffer() stops
    // being called while paused) and the loop would think bass is still loud the
    // instant playback resumes.
    fun pause() {
        if (!running) return
        isPaused = true
        envelope = 0f
        movingAvg = 0f
        vibrationChannel.trySend(VibrationCommand.Stop)
    }

    fun resume() {
        isPaused = false
        // envelope rebuilds naturally from the next handleBuffer() call; the render
        // loop is still ticking in the background and will pick it up within TICK_MS.
    }

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {}

    // Purely an envelope updater now - it does NOT trigger vibration itself. It just
    // keeps `envelope` current; the renderer loop (running on its own fixed clock)
    // is what actually drives the motor. This is what decouples haptic timing from
    // audio buffer timing.
    override fun handleBuffer(buffer: ByteBuffer) {
        if (!running || isPaused || !buffer.hasRemaining()) return
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
        // Still detect transients to shape the envelope curve: a sudden hit rises
        // fast (punchy attack), steady bass decays slowly (smooth sustain) - this is
        // what lets a kick drum still feel like a "hit" inside the continuous loop,
        // without needing a separate discrete pulse code path for it.
        val transient = rawLevel > movingAvg * 1.35f && rawLevel > 0.12f

        envelope = if (transient) {
            kotlin.math.max(rawLevel, envelope * 0.3f)
        } else {
            kotlin.math.max(rawLevel * 0.9f, envelope * 0.85f)
        }
        if (envelope < 0.03f) envelope = 0f
    }
}
