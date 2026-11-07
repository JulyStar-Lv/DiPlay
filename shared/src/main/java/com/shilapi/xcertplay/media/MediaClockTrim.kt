package com.shilapi.xcertplay.media

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Keeps the head unit's audio clock in step with the iPhone's during music (#610).
 *
 * The iPhone sends music at its own 48 kHz and the head unit plays it at its own. A Humax BHCN-T3103C played
 * 0.76 % fast, so the 300 ms start buffer ran dry about every 70 s and music paused while it refilled. After
 * each write the renderer reports how many frames are queued; a slow average of that level over time, as the
 * track drains it between writes, drives a PI controller that, once a second, trims the track's playback rate
 * so the level stays at its target.
 * The trim is bounded by [MAX_TRIM] (26 cents at most) and settles at the head unit's own drift, which is a
 * hardware constant, so the next music stream starts from the trim this one learned.
 * The level music holds once it plays is not the start threshold. Buffered music (#1031) is fed a second ahead of
 * real time, and realtime music over Wi-Fi comes in bursts, so playback starts partway through one and the rest
 * lands on top (#1068).
 */
internal class MediaClockTrim(
    private val sampleRate: Int,
    private var targetFrames: Long,
    private var integral: Double = learnedTrim,
    private val measuresTarget: Boolean = false,
) {
    private class Measuring(val halfStartNs: Long? = null, val earlierHalfMean: Double? = null) {
        var levelFrameNs = 0.0
    }

    private var level: Double? = null
    private var lastLevelNs = 0L
    private var windowStartNs = 0L
    private var windowPeak: Double? = null
    private var lastUpdateNs: Long? = null
    private var compressedBacklog = false
    private var settleUntilNs: Long? = null
    private var measurementWindows = 0L
    private var settlingRestarts = 0L
    /** When the level was last sampled. */
    var lastSampleNs = 0L
        private set
    private var lastBuffered = 0L
    private var measuring: Measuring? = if (measuresTarget) Measuring() else null

    fun measureAgain() {
        if (measuresTarget) measuring = Measuring()
    }

    /** The playback rate last asked of the track, in Hz. */
    var rate = sampleRate
        private set

    /** A known playback restart/rebuffer, rather than a gap between delivery batches. */
    fun restart() {
        level = null
        windowPeak = null
        settleUntilNs = null
        compressedBacklog = false
        measureAgain()
    }

    /**
     * Include compressed packets waiting for decode: those frames are already queued audio. [arrivedFrameNs] sums,
     * over each frame that arrived since [lastSampleNs], how long it has been queued.
     * Supplying it enables time averaging, including zero for an instantaneous burst. Callers without
     * arrival timing retain refill-peak sampling for their fixed target.
     */
    fun sample(queuedFrames: Long, nowNs: Long, compressedFrames: Long = 0L, backpressured: Boolean = false,
        headMoving: Boolean = true, arrivedFrameNs: Double? = null) {
        if (targetFrames <= 0L) return
        // Decoder backpressure is not evidence that the head unit's clock runs at a different
        // rate; do not teach the *next* music stream an artificial clock correction.
        val congested = backpressured || compressedFrames > targetFrames
        val congestionEnded = compressedBacklog && !congested
        compressedBacklog = congested
        val buffered = queuedFrames.coerceAtLeast(0L) +
            compressedFrames.coerceIn(0L, sampleRate.toLong() * 16L)
        if (measuresTarget || arrivedFrameNs != null) {
            sampleTimeAverage(buffered, nowNs, headMoving, arrivedFrameNs ?: 0.0)
            return
        }
        // A fixed target is also used by the paced writer models. Keep its refill-phase
        // measurement separate from the arrival-weighted target the renderer measures.
        val error = (buffered - targetFrames).toDouble() / targetFrames
        // A batch every ~390 ms is normal on the measured Mazda link. It must neither restart
        // the 3-second settling period nor overweight the first (lowest) write in every burst.
        // Average completed short-window peaks so the measurement has a consistent refill phase.
        // STALE_NS still prevents rate updates without fresh audio; PAUSE_NS only provides a
        // fallback reset when the renderer has not already signalled a restart explicitly.
        val restartAverage = level == null || nowNs - lastSampleNs > PAUSE_NS
        if (restartAverage || congestionEnded) {
            level = error
            lastLevelNs = nowNs
            windowStartNs = nowNs
            windowPeak = error
        } else if (nowNs - windowStartNs >= MEASUREMENT_NS) {
            measurementWindows++
            val previous = level!!
            level = previous + (windowPeak!! - previous) *
                (1.0 - exp(-(lastSampleNs - lastLevelNs).toDouble() / AVERAGE_NS))
            lastLevelNs = lastSampleNs
            windowStartNs = nowNs
            windowPeak = error
        } else {
            windowPeak = maxOf(windowPeak ?: error, error)
        }
        if (restartAverage) {
            settlingRestarts++
            settleUntilNs = nowNs + SETTLE_NS
        }
        lastSampleNs = nowNs
    }

    private fun sampleTimeAverage(buffered: Long, nowNs: Long, headMoving: Boolean, arrivedFrameNs: Double) {
        val elapsedNs = nowNs - lastSampleNs
        // Samples come only after writes, and over Wi-Fi the writes come in bursts 0.4 s apart: the level
        // just after one is its peak. Average what was queued over the time since the last write, while
        // the track drained it, or a 300 ms target reads as overfull and the trim plays fast (#807).
        // A write that finds the track full blocks while more audio arrives; that audio counts from its
        // arrival, or a full track reads up to 300 ms low and the trim wanders for minutes (#1074).
        val meanSinceLastWrite = drainedMean(lastBuffered.toDouble(), rate * elapsedNs / 1e9) +
            if (elapsedNs > 0) arrivedFrameNs / elapsedNs else 0.0
        val measure = measuring
        if (measure != null) {
            if (!headMoving) return
            addToMeasurement(measure, nowNs, elapsedNs, meanSinceLastWrite)
        } else {
            val previous = level
            level = if (previous == null || elapsedNs > STALE_NS) error(buffered.toDouble())
            else previous + (error(meanSinceLastWrite) - previous) * (1.0 - exp(-elapsedNs.toDouble() / AVERAGE_NS))
        }
        lastBuffered = buffered
        lastSampleNs = nowNs
    }

    private fun addToMeasurement(measure: Measuring, nowNs: Long, elapsedNs: Long, meanSinceLastWrite: Double) {
        val halfStartNs = measure.halfStartNs
        if (halfStartNs == null) {
            measuring = Measuring(nowNs)
            return
        }
        measure.levelFrameNs += meanSinceLastWrite * elapsedNs
        if (nowNs - halfStartNs < MEASURE_HALF_NS) return
        val halfMean = measure.levelFrameNs / (nowNs - halfStartNs)
        val earlierHalfMean = measure.earlierHalfMean
        if (earlierHalfMean == null || abs(halfMean - earlierHalfMean) > sampleRate * HALVES_AGREE_SECONDS) {
            measuring = Measuring(nowNs, halfMean)
            return
        }
        // Use both agreeing halves: choosing only the first keeps its burst phase bias
        // when the paced writer moves audio in 400 ms batches.
        targetFrames = ((earlierHalfMean + halfMean) / 2).roundToLong()
        level = 0.0
        measuring = null
    }

    private fun error(frames: Double): Double = (frames - targetFrames) / targetFrames

    /** The mean of a level that starts at [start] and drains [drained] frames at a steady pace, stopping at 0. */
    private fun drainedMean(start: Double, drained: Double): Double = when {
        drained <= 0.0 -> start
        drained <= start -> start - drained / 2
        else -> start * start / (2 * drained)
    }

    /** The playback rate to set now, or null to keep the current one. */
    fun rateDue(nowNs: Long): Int? {
        if (measuring != null) {
            val learned = (sampleRate * (1.0 + integral)).roundToInt()
            if (learned == rate) return null
            rate = learned
            return learned
        }
        val error = level ?: return null
        // Without new audio the level says nothing about the clocks: keep the trim as it is.
        if (nowNs - lastSampleNs > STALE_NS) return null
        val previousUpdate = lastUpdateNs
        if (previousUpdate != null && nowNs - previousUpdate < UPDATE_NS) return null
        // A pause between updates is not drift: integrate at most one normal step over it.
        val seconds = if (previousUpdate == null) 0.0 else ((nowNs - previousUpdate) / 1e9).coerceAtMost(MAX_STEP_SECONDS)
        lastUpdateNs = nowNs
        val settling = nowNs < (settleUntilNs ?: nowNs)
        if (!settling && !compressedBacklog) {
            integral = (integral + KI * error * seconds).coerceIn(-MAX_TRIM, MAX_TRIM)
            learnedTrim = integral
        }
        val correction = if (settling) integral else (KP * error + integral).coerceIn(-MAX_TRIM, MAX_TRIM)
        val desired = (sampleRate * (1.0 + correction)).roundToInt()
        // A restart burst is not a reason for an immediate +/-1.5% pitch step. A known vehicle
        // correction may be restored immediately; subsequent changes move by <=0.1% per second.
        val step = (sampleRate * MAX_SLEW_PER_SECOND * seconds).roundToInt().coerceAtLeast(1)
        val next = if (previousUpdate == null) desired else desired.coerceIn(rate - step, rate + step)
        if (next == rate) return null
        rate = next
        return next
    }

    fun diagnostics(nowNs: Long): String {
        val state = when {
            level == null -> "idle"
            nowNs - lastSampleNs > STALE_NS -> "stale"
            nowNs < (settleUntilNs ?: nowNs) -> "settling"
            compressedBacklog -> "backpressure"
            else -> "active"
        }
        return "clockState=$state clockLevelPpm=${level?.let { (it * 1e6).roundToInt() } ?: 0} " +
            "clockIntegralPpm=${(integral * 1e6).roundToInt()} " +
            "clockWindows=$measurementWindows clockRestarts=$settlingRestarts"
    }

    internal companion object {
        /** At most 1.5 % slower or faster: 26 cents, above the 0.76 % seen on #610. */
        const val MAX_TRIM = 0.015
        const val KP = 0.03
        /** Per second, per unit of level error. */
        const val KI = 0.001
        const val AVERAGE_NS = 5_000_000_000.0
        const val UPDATE_NS = 1_000_000_000L
        const val MEASUREMENT_NS = 100_000_000L
        const val PAUSE_NS = 2_000_000_000L
        const val SETTLE_NS = 3_000_000_000L
        const val MAX_SLEW_PER_SECOND = 0.001
        /** Longer than the 0.4 s between the iPhone's bursts over Wi-Fi: a longer silence is a pause or a stall. */
        const val STALE_NS = 1_000_000_000L
        const val MAX_STEP_SECONDS = 2.0
        // Two three-second halves cover several wireless bursts after PCM pacing.
        // Keep the learned hardware rate while measuring, rather than learning a burst's phase.
        const val MEASURE_HALF_NS = 3_000_000_000L
        const val HALVES_AGREE_SECONDS = 0.2

        /** The drift the last music stream settled on. Each process plays to one head unit. */
        @Volatile var learnedTrim = 0.0
    }
}
