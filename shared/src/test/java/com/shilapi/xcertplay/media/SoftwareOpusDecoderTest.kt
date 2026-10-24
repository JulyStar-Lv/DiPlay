package com.shilapi.xcertplay.media

import org.concentus.OpusDecoder
import org.junit.Assert.*
import org.junit.Test

class SoftwareOpusDecoderTest {
    @Test fun receivedRecoveredAndConcealedFramesUseOneContinuousDecoderState() {
        val encoder = SoftwareOpusEncoder(48000)
        val decoder = SoftwareOpusDecoder(1)
        val reference = OpusDecoder(48000, 1)
        repeat(8) { index ->
            val packet = encoder.encode(ByteArray(1920) { (it * 3 + index).toByte() }).single()
            val referencePcm = ShortArray(960)
            val output = if (index in listOf(2, 3)) {
                reference.decode(null, 0, 0, referencePcm, 0, 960, false)
                decoder.conceal(960)
            } else {
                assertEquals(960, SoftwareOpusDecoder.samples(packet))
                reference.decode(packet, 0, packet.size, referencePcm, 0, 960, false)
                decoder.decode(packet)
            }
            assertEquals(1920, output.size)
            for (i in referencePcm.indices) {
                assertEquals(referencePcm[i].toInt(), ((output[i * 2].toInt() and 255) or
                    (output[i * 2 + 1].toInt() shl 8)).toShort().toInt())
            }
        }
        encoder.close()
    }

    @Test fun shortDtxPacketsAreDecodedRatherThanDiscardedByAByteCountHeuristic() {
        val decoder = SoftwareOpusDecoder(1)
        for (packet in listOf(byteArrayOf(0xf8.toByte()), byteArrayOf(0xf8.toByte(), -1), byteArrayOf(0xf8.toByte(), -1, -2))) {
            assertEquals(960, SoftwareOpusDecoder.samples(packet))
            assertEquals(1920, decoder.decode(packet).size)
        }
    }

    @Test fun stereoPlcAndResetPreserveSampleCountsForAllValidDurations() {
        val decoder = SoftwareOpusDecoder(2)
        for (samples in listOf(120, 240, 480, 960, 1920, 2880, 5760)) {
            assertEquals(samples * 4, decoder.conceal(samples).size)
            decoder.reset()
        }
        for (samples in listOf(-1, 0, 119, 121, 5761)) {
            try { decoder.conceal(samples); fail("Invalid PLC duration accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun malformedPacketCannotChangeAnotherStreamsDecoder() {
        val a = SoftwareOpusDecoder(1); val b = SoftwareOpusDecoder(1)
        assertEquals(0, SoftwareOpusDecoder.samples(byteArrayOf()))
        assertEquals(0, SoftwareOpusDecoder.samples(byteArrayOf(3)))
        try { a.decode(byteArrayOf()); fail("Empty access unit accepted") }
        catch (_: IllegalArgumentException) { a.reset() }
        val packet = SoftwareOpusEncoder(48000).encode(ByteArray(1920) { it.toByte() }).single()
        assertArrayEquals(a.decode(packet), b.decode(packet))
    }
}
