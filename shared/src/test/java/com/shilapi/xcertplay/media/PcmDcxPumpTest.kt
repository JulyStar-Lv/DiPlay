package com.shilapi.xcertplay.media

import java.nio.ByteBuffer
import java.util.ArrayDeque
import org.junit.Assert.*
import org.junit.Test

class PcmDcxPumpTest {
    private class Rig {
        var now = 0L
        var running = true
        var inputCapacity = 1536 * PcmDcxPump.FRAME_BYTES
        var blockedInputs = 0
        var echo = true
        var outputFormat = PcmDcxPump.EXPECTED
        var stoppedOnInput = false
        var flushes = 0
        var closes = 0
        val queued = mutableListOf<Triple<ByteArray, Long, Boolean>>()
        val outputs = ArrayDeque<PcmDcxPump.Output>()
        val played = mutableListOf<ByteArray>()
        val logs = mutableListOf<String>()
        val pump = PcmDcxPump(object : PcmDcxPump.Port {
            override fun input(timeoutUs: Long): PcmDcxPump.Input? {
                if (stoppedOnInput) { running = false; return null }
                if (blockedInputs-- > 0) { now += timeoutUs * 1000; return null }
                return PcmDcxPump.Input(0, ByteBuffer.allocate(inputCapacity))
            }
            override fun queue(input: PcmDcxPump.Input, size: Int, timeUs: Long, eos: Boolean) {
                val bytes = ByteArray(size)
                input.bytes.duplicate().apply { position(0); limit(size) }.get(bytes)
                queued += Triple(bytes, timeUs, eos)
                if (echo) outputs.add(PcmDcxPump.Output(bytes, outputFormat, timeUs, eos))
            }
            override fun output(timeoutUs: Long): PcmDcxPump.Output? {
                if (outputs.isEmpty()) { now += timeoutUs * 1000; return null }
                return outputs.removeFirst()
            }
            override fun flush() { flushes++; outputs.clear() }
            override fun close() { closes++ }
        }, { running }, { played += it }, { logs += it }, { now })
        fun bytes(frames: Int) = ByteArray(frames * PcmDcxPump.FRAME_BYTES) { (it * 17).toByte() }
        fun allPlayed() = played.fold(ByteArray(0)) { all, next -> all + next }
    }

    @Test fun batchesVariableApacPacketsWithoutLosingShortTail() {
        val r = Rig()
        val first = r.bytes(1024); val second = r.bytes(1024)
        r.pump.submit(first, 0)
        assertTrue(r.queued.isEmpty())
        r.pump.submit(second, 21333)
        assertEquals(6, r.queued.size)
        assertTrue(r.queued.all { it.first.size == 256 * 24 })
        r.now += 60_000_000L
        r.pump.poll()
        assertArrayEquals(first + second, r.allPlayed())
        assertEquals(8, r.queued.size)
        assertEquals(256 * 24, r.queued.last().first.size)
    }

    @Test fun largeJavaBuffersCannotExceedVendorInputBoundary() {
        val r = Rig(); r.inputCapacity = 65536
        val bytes = r.bytes(3072 + 53)
        r.pump.submit(bytes, 1000)
        r.now += 60_000_000L
        r.pump.poll()
        assertArrayEquals(bytes, r.allPlayed())
        assertEquals(13, r.queued.size)
        var frames = 0L
        for ((data, time, eos) in r.queued) {
            assertFalse(eos)
            assertTrue("vendor input must stay below the 8192-byte unchecked path", data.size < 8192)
            assertTrue(data.size <= 6144)
            assertEquals(1000L + frames * 1_000_000L / 48000, time)
            frames += data.size / 24
        }
        assertEquals(53 * 24, r.queued.last().first.size)
    }

    @Test fun splitsSmallCodecBuffersOnWholeFramesAndKeepsTimestamps() {
        val r = Rig(); r.inputCapacity = 7 * 24 + 3
        val bytes = r.bytes(1536)
        r.pump.submit(bytes, 1000)
        assertArrayEquals(bytes, r.allPlayed())
        var offset = 0
        for ((data, time, eos) in r.queued) {
            assertFalse(eos)
            assertEquals(0, data.size % 24)
            assertTrue(data.size <= 7 * 24)
            assertEquals(1000L + offset / 24 * 1_000_000L / 48000, time)
            offset += data.size
        }
    }

    @Test fun inputBackpressureRetainsEverySample() {
        val r = Rig(); r.blockedInputs = 9
        val bytes = r.bytes(3072)
        r.pump.submit(bytes, 0)
        assertArrayEquals(bytes, r.allPlayed())
        assertEquals(12, r.queued.size)
    }

    @Test fun idleCompletionDrainsEosAndNextSongFlushesBeforeFeeding() {
        val r = Rig(); val tail = r.bytes(53)
        r.pump.submit(tail, 1000)
        r.now += 1_100_000_000L
        r.pump.poll()
        assertArrayEquals(tail, r.allPlayed())
        assertTrue(r.queued.last().third)
        val next = r.bytes(10)
        r.pump.submit(next, 2_000_000)
        assertEquals(1, r.flushes)
        r.now += 60_000_000L
        r.pump.poll()
        assertArrayEquals(tail + next, r.allPlayed())
    }

    @Test fun explicitDigitalProbeCompletionDrainsTheExactShortTail() {
        val r = Rig()
        val input = r.bytes(1024 + 17)
        r.pump.submit(input, 0)
        r.pump.finish()
        r.pump.finish()
        assertArrayEquals(input, r.allPlayed())
        assertEquals(1, r.queued.count { it.third })
        assertTrue(r.queued.all { it.first.size <= 6144 })
    }

    @Test fun wrongOutputEncodingOrLayoutIsRejectedBeforePlayback() {
        for (bad in listOf(PcmDcxPump.EXPECTED.copy(encoding = 4),
                PcmDcxPump.EXPECTED.copy(channels = 8), PcmDcxPump.EXPECTED.copy(mask = 0x2d63f),
                PcmDcxPump.EXPECTED.copy(rate = 44100))) {
            val r = Rig(); r.outputFormat = bad
            assertThrows(IllegalStateException::class.java) { r.pump.submit(r.bytes(1536), 0) }
            assertTrue(r.played.isEmpty())
        }
    }

    @Test fun cancellingBlockedInputStopsWithoutQueuingAudioOrEos() {
        val r = Rig(); r.stoppedOnInput = true
        assertThrows(InterruptedException::class.java) { r.pump.submit(r.bytes(1536), 0) }
        assertTrue(r.queued.isEmpty())
        r.pump.close(); r.pump.close()
        assertEquals(1, r.closes)
    }

    @Test fun missingOutputFailsInsteadOfAccumulatingPcmForever() {
        val r = Rig(); r.echo = false
        r.pump.submit(r.bytes(1536), 0)
        r.now += 2_100_000_000L
        assertThrows(IllegalStateException::class.java) { r.pump.submit(r.bytes(1536), 32000) }
        assertTrue(r.played.isEmpty())
    }

    @Test fun missingEosFailsWithBoundedWait() {
        val r = Rig(); r.echo = false
        r.pump.submit(r.bytes(1536), 0)
        r.now += 1_100_000_000L
        val failure = assertThrows(IllegalStateException::class.java) { r.pump.poll() }
        assertTrue(failure.message.orEmpty().contains("EOS timed out"))
        assertTrue(r.now < 3_200_000_000L)
    }

    @Test fun closeDiscardsPendingAudioAndUnalignedPcmIsRejected() {
        val r = Rig()
        assertThrows(IllegalArgumentException::class.java) { r.pump.submit(ByteArray(25), 0) }
        r.pump.submit(r.bytes(100), 0)
        r.pump.close()
        assertTrue(r.queued.isEmpty())
        assertEquals(1, r.closes)
        assertThrows(InterruptedException::class.java) { r.pump.poll() }
    }
}
