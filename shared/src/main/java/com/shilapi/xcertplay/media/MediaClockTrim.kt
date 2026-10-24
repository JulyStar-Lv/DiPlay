package com.shilapi.xcertplay.media

import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Keeps the head unit's audio clock in step with the iPhone's during music (#610).
 *
 * The iPhone sends music at its own 48 kHz and the head unit plays it at its own. A Humax BHCN-T3103C played
 * 0.76 % fast, so the 300 ms start buffer ran dry about every 70 s and music paused while it refilled. After
 * each write the renderer reports how many frames are queued; a slow average of that level drives a PI
 * controller that, once a second, trims the track's playback rate so the level stays at the start threshold.
 * The trim is bounded by [MAX_TRIM] (26 cents at most) and settles at the head unit's own drift, which is a
 * hardware constant, so the next music stream starts from the trim this one learned.
 */
internal class MediaClockTrim(
    private val sampleRate: Int,
    private val targetFrames: Long,
    private var integral: Double = learnedTrim,
) {
    private var level: Double? = null
    private var lastLevelNs = 0L
    private var lastSampleNs = 0L
    private var windowStartNs = 0L
    private var windowPeak: Double? = null
    private var lastUpdateNs: Long? = null
    private var compressedBacklog = false
    private var settleUntilNs: Long? = null
    private var measurementWindows = 0L
    private var settlingRestarts = 0L

    /** The playback rate last asked of the track, in Hz. */
    var rate = sampleRate
        private set

    /** A known playback restart/rebuffer, rather than a gap between delivery batches. */
    fun restart() {
        level = null
        windowPeak = null
        settleUntilNs = null
        compressedBacklog = false
    }

    /** Include compressed packets waiting for decode: those frames are already queued audio. */
    fun sample(queuedFrames: Long, nowNs: Long, compressedFrames: Long = 0L,
        backpressured: Boolean = false) {
        if (targetFrames <= 0L) return
        // Decoder backpressure is not evidence that the head unit's clock runs at a different
        // rate; do not teach the *next* music stream an artificial clock correction.
        val congested = backpressured || compressedFrames > targetFrames
        val congestionEnded = compressedBacklog && !congested
        compressedBacklog = congested
        val buffered = queuedFrames.coerceAtLeast(0L) +
            compressedFrames.coerceIn(0L, sampleRate.toLong() * 16L)
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

    /** The playback rate to set now, or null to keep the current one. */
    fun rateDue(nowNs: Long): Int? {
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
        const val STALE_NS = 250_000_000L
        const val MEASUREMENT_NS = 100_000_000L
        const val PAUSE_NS = 2_000_000_000L
        const val MAX_STEP_SECONDS = 2.0
        const val SETTLE_NS = 3_000_000_000L
        const val MAX_SLEW_PER_SECOND = 0.001

        /** The drift the last music stream settled on. Each process plays to one head unit. */
        @Volatile var learnedTrim = 0.0
    }
}
