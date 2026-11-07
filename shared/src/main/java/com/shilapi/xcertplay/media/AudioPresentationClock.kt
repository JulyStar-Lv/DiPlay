package com.shilapi.xcertplay.media

/** Relative, wrap-safe codec timestamps, using the negotiated RTP clock rather than PCM rate. */
internal class AudioPresentationClock(private val clockRate: Int) {
    private var previous: Int? = null
    private var ticks = 0L
    fun timestampUs(sample: Int): Long {
        previous?.let { ticks += (sample - it).toLong() }
        previous = sample
        return ticks * 1_000_000L / clockRate
    }
}
