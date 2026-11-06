package com.shilapi.xcertplay.media

/** Explicit CoreAudio-to-Android speaker maps; unknown layouts are never guessed from channel count. */
internal object SpatialPcm {
    fun order(tag: Int): IntArray = when (tag) {
        0x790006 -> intArrayOf(0, 1, 2, 3, 4, 5) // MPEG 5.1 A: L R C LFE Ls Rs
        0x7c0006 -> intArrayOf(1, 2, 0, 5, 3, 4) // MPEG 5.1 D: C L R Ls Rs LFE
        0xc0000c -> intArrayOf(0, 1, 2, 3, 6, 7, 4, 5, 8, 9, 10, 11)
        else -> error("Unsupported APAC speaker layout")
    }

    fun mask(tag: Int): Int = when (tag) {
        0x790006, 0x7c0006 -> 0xfc
        0xc0000c -> 0xb58fc
        else -> error("Unsupported APAC speaker layout")
    }

    fun convert(pcm: ByteArray, tag: Int, trimStart: Int, trimEnd: Int, gain: Float): ByteArray {
        val order = order(tag)
        val stride = order.size * 2
        require(pcm.size % stride == 0 && gain.isFinite() && gain in 0f..1f)
        val frames = pcm.size / stride
        val start = trimStart.coerceIn(0, frames)
        val end = (frames - trimEnd.coerceAtLeast(0)).coerceIn(start, frames)
        val output = ByteArray((end - start) * stride)
        for (frame in start until end) for (channel in order.indices) {
            val from = frame * stride + order[channel] * 2
            val sample = (((pcm[from + 1].toInt() shl 8) or (pcm[from].toInt() and 255)) * gain).toInt()
            val to = (frame - start) * stride + channel * 2
            output[to] = sample.toByte(); output[to + 1] = (sample shr 8).toByte()
        }
        return output
    }
}
