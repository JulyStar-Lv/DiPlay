package com.shilapi.xcertplay.media

import java.util.TreeMap
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/** AAC output may arrive after later inputs: select gain by decoded presentation time. */
internal class BufferedGainTimeline {
    private val changes = TreeMap<Long, Float>()
    fun record(timeUs: Long, gain: Float) {
        require(gain.isFinite() && gain in 0f..1f)
        if (changes.lastEntry()?.value != gain) changes[timeUs] = gain
    }
    fun at(timeUs: Long): Float {
        val entry = changes.floorEntry(timeUs) ?: return 1f
        changes.headMap(entry.key, false).clear()
        return entry.value
    }
}

/** One scalar for every speaker. First metadata is immediate; later changes ramp over 20 ms. */
internal class BufferedPcmGain(private val rate: Int, private val channels: Int) {
    private var current: Float? = null
    private var target = 1f
    private var step = 0f
    private var remaining = 0

    fun apply(data: ByteArray, offset: Int, length: Int, gain: Float) {
        require(gain.isFinite() && gain in 0f..1f && length % (channels * 2) == 0)
        if (current == null) { current = gain; target = gain }
        if (gain != target) {
            target = gain
            remaining = (rate / 50).coerceAtLeast(1)
            step = (target - current!!) / remaining
        }
        if (remaining == 0 && target == 1f) return
        for (frame in offset until offset + length step channels * 2) {
            if (remaining > 0) {
                remaining--
                current = if (remaining == 0) target else current!! + step
            }
            val scale = current!!
            for (channel in 0 until channels) {
                val at = frame + channel * 2
                val sample = ((data[at + 1].toInt() shl 8) or (data[at].toInt() and 255))
                val scaled = (sample * scale).toInt()
                data[at] = scaled.toByte(); data[at + 1] = (scaled shr 8).toByte()
            }
        }
    }
}

/** Sample peak and RMS in dBFS, not perceptual loudness or a DRC verdict. Decode worker only. */
internal class Pcm16Levels {
    var samples = 0L; private set
    private var sumSquares = 0.0
    private var peak = 0
    private var clipped = 0L
    fun add(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        require(length % 2 == 0)
        for (at in offset until offset + length step 2) {
            val value = (data[at + 1].toInt() shl 8) or (data[at].toInt() and 255)
            sumSquares += value.toDouble() * value
            peak = maxOf(peak, abs(value))
            if (value == -32768 || value == 32767) clipped++
        }
        samples += length / 2
    }
    fun summary(): String {
        fun db(value: Double) = if (value == 0.0) "silence" else
            java.lang.String.format(java.util.Locale.ROOT, "%.2f", 20 * log10(value / 32768.0))
        return "samples=$samples rmsDbfs=${db(if (samples == 0L) 0.0 else sqrt(sumSquares / samples))} " +
            "samplePeakDbfs=${db(peak.toDouble())} clippedSamples=$clipped"
    }
}
