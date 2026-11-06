package com.shilapi.xcertplay.media

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class SpatialPcmTest {
    @Test fun reordersEverySpeakerBeforeTrimmingAndAttenuatingLittleEndianPcm() {
        for (tag in listOf(0x790006, 0x7c0006, 0xc0000c)) {
            val channels = tag and 65535
            val input = ByteBuffer.allocate(channels * 2 * 3).order(ByteOrder.LITTLE_ENDIAN)
            repeat(3) { frame -> repeat(channels) { ch -> input.putShort((-2000 + frame * 200 + ch * 2).toShort()) } }
            val output = ByteBuffer.wrap(SpatialPcm.convert(input.array(), tag, 1, 1, 0.5f)).order(ByteOrder.LITTLE_ENDIAN)
            val order = when (tag) {
                0x7c0006 -> listOf(1, 2, 0, 5, 3, 4)
                0xc0000c -> listOf(0, 1, 2, 3, 6, 7, 4, 5, 8, 9, 10, 11)
                else -> (0 until channels).toList()
            }
            assertEquals(channels * 2, output.remaining())
            order.forEach { assertEquals((-900 + it).toShort(), output.short) }
            assertTrue(SpatialPcm.convert(input.array(), tag, 1024, 0, 1f).isEmpty())
        }
    }

    @Test fun twelveChannelBufferKeepsTheSameDurationAsStereo() {
        val stereo = MediaAudioBuffer.plan(true, 48000, 2, 1024, 300)
        val surround = MediaAudioBuffer.plan(true, 48000, 12, 1024, 300)
        assertEquals(stereo.startBytes * 6, surround.startBytes)
        assertEquals(stereo.trackBufferBytes * 6, surround.trackBufferBytes)
    }

    @Test(expected = IllegalStateException::class) fun unknownSpeakerLayoutIsRejected() {
        SpatialPcm.convert(ByteArray(12), 6, 0, 0, 1f)
    }
}
