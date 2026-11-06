package com.shilapi.xcertplay.media

import android.media.AudioTrack
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE, shadows = [RealtimeAudioRendererTest.TestTrack::class])
class RealtimeAudioRendererTest {
    @Test fun reorderedOpusAndLossConcealmentReachTheIndependentWriterAndBothWorkersClose() {
        TestTrack.reset(7680)
        val reports = CopyOnWriteArrayList<String>()
        val (type, renderer) = renderer("default", reports)
        try {
            type.getDeclaredMethod("start").apply { isAccessible = true }.invoke(renderer)
            submit(type, renderer, 1, 0)
            // Packet 2 was lost. Out-of-order packet 4 arrives before 3, which recovers the order.
            submit(type, renderer, 4, 960)
            submit(type, renderer, 3, 640)
            // Four 20-ms outputs: the lost second packet is explicit 20-ms PLC.
            assertTrue(reports.joinToString("\n"), TestTrack.completed.await(5, TimeUnit.SECONDS))
            assertEquals(7680, TestTrack.bytes.get())
            assertEquals(setOf("carplay-audio"), TestTrack.writeThreads.toSet())
            assertTrue(reports.toString(), reports.any { it.contains("name=Concentus PLC=true") })
            val concealed = type.getDeclaredField("concealedFrames").apply { isAccessible = true }
                .get(renderer) as java.util.concurrent.atomic.AtomicLong
            assertEquals(960L, concealed.get())
        } finally { closeAndJoin(type, renderer) }
    }

    @Test fun temporaryZeroAndPartialWritesPreservePcmAndShortPromptsStartOnModernAndroid() {
        TestTrack.reset(1920)
        TestTrack.zeros.set(3)
        TestTrack.partial = true
        val (type, renderer) = renderer("telephony", CopyOnWriteArrayList())
        try {
            type.getDeclaredMethod("start").apply { isAccessible = true }.invoke(renderer)
            submit(type, renderer, 1, 0)
            assertTrue(TestTrack.completed.await(5, TimeUnit.SECONDS))
            assertEquals(1920, TestTrack.bytes.get())
            assertTrue(TestTrack.played.await(2, TimeUnit.SECONDS))
            if (android.os.Build.VERSION.SDK_INT >= 31) assertTrue(TestTrack.startFrames.get() in 1..960)
        } finally { closeAndJoin(type, renderer) }
    }

    @Test fun closingWithAStalledTrackInterruptsBackpressureAndReleasesResources() {
        TestTrack.reset(Int.MAX_VALUE)
        TestTrack.zeros.set(Int.MAX_VALUE)
        val (type, renderer) = renderer("speechrecognition", CopyOnWriteArrayList())
        try {
            type.getDeclaredMethod("start").apply { isAccessible = true }.invoke(renderer)
            repeat(20) { submit(type, renderer, it, it * 320) }
            assertTrue(TestTrack.attempted.await(5, TimeUnit.SECONDS))
        } finally { closeAndJoin(type, renderer) }
        assertEquals(1, TestTrack.released.get())
    }

    @Test fun malformedOpusWithAKnownDurationDoesNotShortenThePlayoutTimeline() {
        TestTrack.reset(5760)
        val (type, renderer) = renderer("default", CopyOnWriteArrayList())
        try {
            type.getDeclaredMethod("start").apply { isAccessible = true }.invoke(renderer)
            submit(type, renderer, 1, 0)
            // One 20-ms frame, but the padding flag has no following padding length byte.
            submit(type, renderer, 2, 320, byteArrayOf(0xfb.toByte(), 0x41))
            submit(type, renderer, 3, 640)
            assertTrue(TestTrack.completed.await(5, TimeUnit.SECONDS))
            assertEquals(5760, TestTrack.bytes.get())
            val invalid = type.getDeclaredField("invalidPackets").apply { isAccessible = true }
                .get(renderer) as java.util.concurrent.atomic.AtomicLong
            assertEquals(1L, invalid.get())
        } finally { closeAndJoin(type, renderer) }
    }

    @Test fun navigationUsesSixtyMillisOnTheMeasuredMazdaTrackAndKeepsShortPromptsPlayable() {
        for (packets in listOf(1, 3)) {
            TestTrack.reset(packets * 1920)
            TestTrack.bufferFrames = 6156 // The real Mazda capture grants 128.25 ms.
            val reports = CopyOnWriteArrayList<String>()
            val (type, renderer) = renderer("default", reports)
            try {
                type.getDeclaredMethod("start").apply { isAccessible = true }.invoke(renderer)
                repeat(packets) { submit(type, renderer, it + 1, it * 320) }
                assertTrue(TestTrack.completed.await(5, TimeUnit.SECONDS))
                assertTrue(TestTrack.played.await(2, TimeUnit.SECONDS))
                assertEquals(packets * 1920, TestTrack.bytesAtPlay.get())
                if (android.os.Build.VERSION.SDK_INT >= 31) {
                    assertEquals(packets * 960, TestTrack.startFrames.get())
                }
                assertTrue(reports.toString(), reports.any { it.contains("playoutRevision=B3") })
            } finally { closeAndJoin(type, renderer) }
        }
    }

    @Test fun musicStopsAtItsTargetHeadroomAndCloseInterruptsThePacedWriter() {
        TestTrack.reset(32_640) // (300-ms target + 40-ms headroom) * 48k * mono S16.
        TestTrack.bufferFrames = 24_000
        val reports = CopyOnWriteArrayList<String>()
        val (type, renderer) = renderer("media", reports)
        try {
            type.getDeclaredMethod("start").apply { isAccessible = true }.invoke(renderer)
            repeat(40) { submit(type, renderer, it + 1, it * 320) }
            assertTrue(reports.toString(), TestTrack.completed.await(5, TimeUnit.SECONDS))
        } finally { closeAndJoin(type, renderer) }
        assertEquals(32_640, TestTrack.bytes.get())
        assertEquals(1, TestTrack.released.get())
        assertTrue(reports.toString(), reports.any { it.contains("pcmBudgetMs=40") })
    }

    @Test fun pcmBackpressureCannotTurnAnAlreadyQueuedReorderedPacketIntoExtraPlc() {
        TestTrack.reset(7 * 1920)
        TestTrack.zeros.set(Int.MAX_VALUE)
        val reports = CopyOnWriteArrayList<String>()
        val (type, renderer) = renderer("default", reports)
        try {
            type.getDeclaredMethod("start").apply { isAccessible = true }.invoke(renderer)
            repeat(4) { submit(type, renderer, it + 1, it * 320) }
            assertTrue(TestTrack.attempted.await(5, TimeUnit.SECONDS))
            val pcmQueue = type.getDeclaredField("pcmQueue").apply { isAccessible = true }.get(renderer) as AudioPcmQueue
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (pcmQueue.size < 6 && System.nanoTime() < deadline) Thread.sleep(5)
            assertEquals(6, pcmQueue.size)
            // Decoder is blocked by owned PCM. Packet 5 is lost, but 6 is already queued
            // behind out-of-order packet 7 when their 60-ms reorder deadlines expire.
            submit(type, renderer, 7, 1920)
            submit(type, renderer, 6, 1600)
            Thread.sleep(80)
            TestTrack.zeros.set(0)
            assertTrue(reports.toString(), TestTrack.completed.await(5, TimeUnit.SECONDS))
            assertEquals(7 * 1920, TestTrack.bytes.get())
            val concealed = type.getDeclaredField("concealedFrames").apply { isAccessible = true }
                .get(renderer) as java.util.concurrent.atomic.AtomicLong
            assertEquals("Only the truly lost fifth packet needs PLC", 960L, concealed.get())
        } finally { closeAndJoin(type, renderer) }
    }

    private fun renderer(audioType: String, reports: CopyOnWriteArrayList<String>): Pair<Class<*>, Any> {
        val type = Class.forName("com.shilapi.xcertplay.media.AudioRenderer")
        val renderer = type.declaredConstructors.single { it.parameterCount == 13 }.apply { isAccessible = true }
            .newInstance(AudioFormat(AudioCodecKind.OPUS, 48000, 1, 100, audioType, 16000),
                false, false, 0, 0, AudioFocusCoordinator(null, false, false), 0, 300,
                { line: String -> reports.add(line); Unit }, 0L, false, null, false)
        return type to renderer
    }

    private fun submit(type: Class<*>, renderer: Any, sequence: Int, sample: Int,
        payload: ByteArray = byteArrayOf(0xf8.toByte(), -1, -2)) {
        val rtp = ByteArray(12 + payload.size)
        rtp[0] = 0x80.toByte(); rtp[1] = 96
        rtp[2] = (sequence ushr 8).toByte(); rtp[3] = sequence.toByte(); rtp[11] = 1
        payload.copyInto(rtp, 12)
        type.getDeclaredMethod("submit", ByteArray::class.java, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(renderer, rtp, sample)
    }

    private fun closeAndJoin(type: Class<*>, renderer: Any) {
        type.getDeclaredMethod("close").apply { isAccessible = true }.invoke(renderer)
        for (name in listOf("thread", "decodeThread")) {
            val worker = type.getDeclaredField(name).apply { isAccessible = true }.get(renderer) as Thread
            worker.join(2000)
            assertFalse("$name did not close", worker.isAlive)
        }
    }

    @Implements(AudioTrack::class)
    class TestTrack {
        @Implementation fun getState() = AudioTrack.STATE_INITIALIZED
        @Implementation fun getSampleRate() = 48000
        @Implementation fun getBufferSizeInFrames() = bufferFrames
        @Implementation fun getPlaybackHeadPosition() = 0
        @Implementation fun getUnderrunCount() = 0
        @Implementation fun play() { bytesAtPlay.set(bytes.get()); played.countDown() }
        @Implementation fun pause() = Unit
        @Implementation fun flush() = Unit
        @Implementation fun release() { released.incrementAndGet() }
        @Implementation(minSdk = 31) fun setStartThresholdInFrames(frames: Int): Int {
            startFrames.set(frames)
            return frames
        }
        @Implementation fun write(data: ByteArray, offset: Int, length: Int, mode: Int): Int {
            assertEquals(AudioTrack.WRITE_NON_BLOCKING, mode)
            attempted.countDown()
            if (zeros.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) return 0
            val count = if (partial && length > 2) length / 4 * 2 else length
            writeThreads += Thread.currentThread().name
            if (bytes.addAndGet(count) >= expectedBytes) completed.countDown()
            return count
        }
        companion object {
            @JvmStatic @Implementation
            fun getMinBufferSize(rate: Int, mask: Int, encoding: Int) = 960
            val bytes = AtomicInteger()
            val zeros = AtomicInteger()
            val released = AtomicInteger()
            val startFrames = AtomicInteger()
            val bytesAtPlay = AtomicInteger()
            var bufferFrames = 1920
            val writeThreads = CopyOnWriteArrayList<String>()
            var partial = false
            var expectedBytes = 0
            lateinit var completed: CountDownLatch
            lateinit var played: CountDownLatch
            lateinit var attempted: CountDownLatch
            fun reset(expected: Int) {
                bytes.set(0); zeros.set(0); released.set(0); startFrames.set(0)
                bytesAtPlay.set(0); bufferFrames = 1920
                partial = false; expectedBytes = expected; writeThreads.clear()
                completed = CountDownLatch(1); played = CountDownLatch(1); attempted = CountDownLatch(1)
            }
        }
    }
}
