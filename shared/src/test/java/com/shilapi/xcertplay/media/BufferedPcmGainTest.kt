package com.shilapi.xcertplay.media

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class BufferedPcmGainTest {
    private fun pcm(frames: Int, channels: Int, value: Int) = ByteBuffer.allocate(frames * channels * 2)
        .order(ByteOrder.LITTLE_ENDIAN).apply { repeat(frames * channels) { putShort(value.toShort()) } }.array()
    private fun samples(bytes: ByteArray) = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        .asShortBuffer().let { buffer -> ShortArray(buffer.remaining()).also { buffer.get(it) } }

    @Test fun stereoAndSpatialUseTheSameProgramGainExactlyOnce() {
        for (channels in listOf(2, 12)) {
            val bytes = pcm(1024, channels, 12000)
            BufferedPcmGain(48000, channels).apply(bytes, 0, bytes.size, 0.5f)
            assertTrue(samples(bytes).all { it == 6000.toShort() })
        }
    }

    @Test fun aQueuedFutureSongsGainCannotChangeEarlierAacOutput() {
        val timeline = BufferedGainTimeline()
        timeline.record(1000, 0.5f)
        timeline.record(22000, 0.25f)
        assertEquals(1f, timeline.at(0), 0f)
        assertEquals(0.5f, timeline.at(1000), 0f)
        assertEquals(0.5f, timeline.at(21999), 0f)
        assertEquals(0.25f, timeline.at(22000), 0f)
        timeline.record(44000, 1f)
        assertEquals(0.25f, timeline.at(43000), 0f)
        assertEquals(1f, timeline.at(44000), 0f)
    }

    @Test fun metadataChangesRampAcrossBuffersWithoutMovingTheStereoImage() {
        val gain = BufferedPcmGain(48000, 2)
        gain.apply(pcm(1, 2, 10000), 0, 4, 1f)
        val first = pcm(480, 2, 10000)
        val second = pcm(480, 2, 10000)
        gain.apply(first, 0, first.size, 0f)
        gain.apply(second, 0, second.size, 0f)
        val all = samples(first + second)
        assertTrue(all.first() in 9980..10000)
        assertEquals(0.toShort(), all.last())
        all.toList().chunked(2).forEach { assertEquals(it[0], it[1]) }
        all.toList().zipWithNext().forEach { (a, b) -> assertTrue(a >= b) }
    }

    @Test fun fullScaleSamplesCannotWrapAndOtherBytesRemainUntouched() {
        val bytes = byteArrayOf(99, 98) + pcm(1, 2, -32768) + byteArrayOf(97, 96)
        BufferedPcmGain(48000, 2).apply(bytes, 2, 4, 0.5f)
        assertArrayEquals(shortArrayOf(-16384, -16384), samples(bytes.copyOfRange(2, 6)))
        assertArrayEquals(byteArrayOf(99, 98), bytes.copyOfRange(0, 2))
        assertArrayEquals(byteArrayOf(97, 96), bytes.copyOfRange(6, 8))
        for (invalid in listOf(Float.NaN, -1f, 1.1f)) {
            assertThrows(IllegalArgumentException::class.java) {
                BufferedPcmGain(48000, 2).apply(bytes, 2, 4, invalid)
            }
        }
    }

    @Test fun levelDiagnosticsDistinguishSilenceSamplePeakAndClipping() {
        val levels = Pcm16Levels()
        assertTrue(levels.summary().contains("rmsDbfs=silence"))
        levels.add(pcm(10, 2, 16384))
        assertTrue(levels.summary().contains("rmsDbfs=-6.02"))
        assertTrue(levels.summary().contains("clippedSamples=0"))
        levels.add(pcm(1, 2, -32768))
        assertTrue(levels.summary().contains("clippedSamples=2"))
    }
}
