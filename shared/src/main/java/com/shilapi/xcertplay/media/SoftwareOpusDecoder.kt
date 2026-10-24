package com.shilapi.xcertplay.media

import org.concentus.OpusDecoder
import org.concentus.OpusPacketInfo

/** One continuous decoder state for received packets, RED recovery and explicit Opus PLC. */
internal class SoftwareOpusDecoder(private val channels: Int) {
    private val decoder = OpusDecoder(48_000, channels)
    private val scratch = ShortArray(MAX_SAMPLES * channels)

    fun decode(payload: ByteArray): ByteArray {
        require(payload.isNotEmpty()) { "Empty Opus access unit" }
        val count = decoder.decode(payload, 0, payload.size, scratch, 0, MAX_SAMPLES, false)
        return pcm(count)
    }

    fun conceal(samples: Int): ByteArray {
        require(samples in 120..MAX_SAMPLES && samples % 120 == 0) { "Invalid Opus PLC duration" }
        val count = decoder.decode(null, 0, 0, scratch, 0, samples, false)
        return pcm(count)
    }

    fun reset() = decoder.resetState()

    private fun pcm(samples: Int): ByteArray = ByteArray(samples * channels * 2).also { output ->
        for (i in 0 until samples * channels) {
            output[i * 2] = scratch[i].toByte()
            output[i * 2 + 1] = (scratch[i].toInt() shr 8).toByte()
        }
    }

    companion object {
        private const val MAX_SAMPLES = 5_760
        fun samples(payload: ByteArray): Int =
            if (payload.isEmpty()) 0 else OpusPacketInfo.getNumSamples(payload, 0, payload.size, 48_000)
                .takeIf { it in 120..MAX_SAMPLES } ?: 0
    }
}
