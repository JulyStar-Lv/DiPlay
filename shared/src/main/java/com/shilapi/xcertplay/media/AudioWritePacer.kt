package com.shilapi.xcertplay.media

/** Keep music near its selected start level rather than eagerly filling the device's burst headroom. */
internal object AudioWritePacer {
    fun allowedBytes(remaining: Int, queuedFrames: Long, targetFrames: Long,
        sampleRate: Int, frameBytes: Int, pace: Boolean): Int {
        if (!pace || targetFrames <= 0 || sampleRate <= 0) return remaining
        val ceiling = targetFrames + sampleRate.toLong() * HEADROOM_MILLIS / 1000
        val available = (ceiling - queuedFrames.coerceAtLeast(0)).coerceAtLeast(0)
        return minOf(remaining.toLong(), available * frameBytes).toInt() / frameBytes * frameBytes
    }

    fun pacingWaitMillis(queuedFrames: Long, targetFrames: Long, sampleRate: Int): Long {
        val ceiling = targetFrames + sampleRate.toLong() * HEADROOM_MILLIS / 1000
        // Wait for another 10-ms PCM chunk, allowing for a coarse hardware playback head.
        val excess = (queuedFrames - ceiling).coerceAtLeast(0)
        return (excess * 1000 / sampleRate.coerceAtLeast(1) + 10).coerceIn(2, 20)
    }

    fun zeroWriteWaitMillis(consecutiveZeros: Int, music: Boolean): Long =
        (2L shl (consecutiveZeros - 1).coerceIn(0, 4)).coerceAtMost(if (music) 20 else 5)

    const val HEADROOM_MILLIS = 40
}
