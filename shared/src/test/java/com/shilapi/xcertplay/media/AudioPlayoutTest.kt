package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class AudioPlayoutTest {
    @Test fun interactiveBacklogPreservesShortBurstsAndTrimsOldSpeechAcrossTimestampWrap() {
        for (origin in listOf(0, Int.MAX_VALUE - 640, -640)) {
            val queue = java.util.concurrent.LinkedBlockingQueue<Int>()
            repeat(10) { queue.offer(origin + it * 320) }
            assertEquals(0, RealtimeAudioBacklog.trim(queue, origin + 3200, 16000) { it })
            assertEquals(7, RealtimeAudioBacklog.trim(queue, origin + 3520, 16000) { it })
            assertEquals(origin + 2240, queue.peek())
        }
    }

    @Test fun budgetsFollowTheUseEvenWhenACallIsRoutedToTheMusicSpeaker() {
        fun policy(type: String, media: Boolean = true) = AudioPlayoutPolicy.forStream(
            AudioFormat(AudioCodecKind.OPUS, 48000, 1, 100, type, 16000), media)
        assertEquals(20, policy("telephony").reorderMillis)
        assertEquals(40, policy("speechrecognition").reorderMillis)
        assertEquals(60, policy("default").reorderMillis)
        assertEquals(policy("default"), policy("alert"))
        for (type in listOf("telephony", "speechrecognition", "default", "alert")) {
            assertFalse(policy(type).music)
            assertEquals(policy(type), policy(type, false))
        }
        assertTrue(policy("media").music)
        assertEquals(40, policy("media").pcmQueueMillis)
        assertTrue(policy("compatibility").music)
        assertFalse(policy("compatibility", false).music)
        assertEquals(policy("telephony"), policy("TELEPHONY"))
    }

    @Test fun navigationStartsWithSixtyMillisWithoutIncreasingCallOrSiriFloors() {
        fun policy(type: String) = AudioPlayoutPolicy.forStream(
            AudioFormat(AudioCodecKind.OPUS, 48000, 1, 100, type, 16000), false)
        assertEquals(0, policy("telephony").startupFloorMillis)
        assertEquals(0, policy("speechrecognition").startupFloorMillis)
        for (type in listOf("default", "alert")) {
            for (rate in listOf(8_000, 16_000, 24_000, 44_100, 48_000)) for (channels in 1..2) {
                for (minimum in listOf(256, 6156, 32768)) {
                    val frame = channels * 2
                    val plan = policy(type).trackPlan(rate, channels, minimum)
                    assertEquals(rate.toLong() * 60 / 1000 * frame, plan.startBytes.toLong())
                    assertTrue(plan.trackBufferBytes >= 2 * plan.startBytes)
                    val granted = (plan.trackBufferBytes / 4 / frame).coerceAtLeast(1) * frame
                    val chunk = minOf(4096, (granted / 2 / frame * frame).coerceAtLeast(frame))
                    val start = MediaAudioBuffer.startBytesFor(plan.startBytes, granted, chunk)
                    assertTrue("Small device grant must not deadlock", start in frame..granted)
                }
            }
        }
    }

    @Test fun realtimeTrackPlansRespectTheDeviceMinimumAndKeepStartupBelowCapacity() {
        val policy = AudioPlayoutPolicy(20, 60, 40, 20, false)
        for (rate in listOf(8000, 16000, 24000, 44100, 48000)) for (channels in 1..2) {
            for (minimum in listOf(256, 4096, 16384)) {
                val plan = policy.trackPlan(rate, channels, minimum)
                assertTrue(plan.trackBufferBytes >= minimum)
                assertTrue(plan.startBytes > 0 && plan.startBytes < plan.trackBufferBytes)
                assertTrue(plan.startBytes <= rate / 50 * channels * 2)
            }
        }
    }

    @Test fun presentationClockUsesNegotiatedRateAndUnwrapsBothSignedAndUnsignedBoundaries() {
        for (rate in listOf(16000, 24000, 48000)) {
            for (first in listOf(Int.MAX_VALUE - rate / 100, -rate / 100, 0)) {
                val clock = AudioPresentationClock(rate)
                assertEquals(0L, clock.timestampUs(first))
                assertEquals(20_000L, clock.timestampUs(first + rate / 50))
                assertEquals(40_000L, clock.timestampUs(first + rate / 25))
            }
        }
    }

    @Test fun pcmQueueOwnsEachChunkAndPreservesTheExactByteOrder() {
        val queue = AudioPcmQueue(48000, 2, 40)
        val input = ByteArray(3 * 1920 + 8) { it.toByte() }
        val expected = input.copyOfRange(4, input.size - 4)
        queue.put(input, 4, input.size - 8) { true }
        input.fill(0)
        assertEquals(5760, queue.pendingBytes)
        assertEquals(3, queue.size)
        var received = byteArrayOf()
        while (!queue.isEmpty) received += queue.poll(0)!!
        assertArrayEquals(expected, received)
    }

    @Test fun stalledWriterBoundsPcmDurationAndCloseUnblocksTheProducer() {
        val queue = AudioPcmQueue(48000, 1, 20)
        val running = AtomicBoolean(true)
        val entered = CountDownLatch(1)
        val done = CountDownLatch(1)
        val producer = Thread {
            entered.countDown()
            try { queue.put(ByteArray(9600), 0, 9600) { running.get() } }
            finally { done.countDown() }
        }
        producer.start()
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            // Waiting for the producer must time out while the writer is stalled.
            assertFalse(done.await(50, TimeUnit.MILLISECONDS))
            assertEquals(1920, queue.pendingBytes)
            running.set(false)
            assertTrue(done.await(1, TimeUnit.SECONDS))
            assertEquals(1920, queue.pendingBytes)
        } finally { running.set(false); producer.interrupt(); producer.join(1000) }
    }

    @Test fun pcmQueueRejectsInvalidSlicesBeforeEnqueueing() {
        val queue = AudioPcmQueue(48000, 1, 20)
        for ((offset, length) in listOf(-1 to 1, 0 to -1, 1 to Int.MAX_VALUE, 3 to 2)) {
            try { queue.put(ByteArray(4), offset, length) { true }; fail("Invalid slice accepted") }
            catch (_: IllegalArgumentException) { }
        }
        assertTrue(queue.isEmpty)
    }
}
