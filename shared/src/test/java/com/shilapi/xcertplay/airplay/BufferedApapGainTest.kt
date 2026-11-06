package com.shilapi.xcertplay.airplay

import java.math.BigInteger
import java.net.Socket
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BufferedApapGainTest {
    @Test fun startingAfterTheMetadataFrameKeepsItsProgramGain() {
        val received = CountDownLatch(1)
        val outputs = mutableListOf<Pair<Int, Float>>()
        val sink = object : MediaSink {
            override fun onBufferedAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray,
                sample: Int, gain: Float) {
                synchronized(outputs) { outputs.add(sample to gain) }
                received.countDown()
            }
        }
        withStream(sink) { stream, socket ->
            queue(stream, socket, listOf(record(0, -12f), record(1), record(2)))
            stream.setRate(1024L, 1, BigInteger.ZERO)
            assertTrue("no buffered output", received.await(3, TimeUnit.SECONDS))
            val first = synchronized(outputs) { outputs.first() }
            assertEquals(1024, first.first)
            assertEquals(0.501187f, first.second, 0.0001f)
        }
    }

    @Test fun flushingDecodedFramesDoesNotApplyTheFollowingProgramsGain() {
        val initial = CountDownLatch(3)
        val replayed = CountDownLatch(2)
        val outputs = mutableListOf<Pair<Int, Float>>()
        val sink = object : MediaSink {
            override fun onBufferedAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray,
                sample: Int, gain: Float) {
                synchronized(outputs) {
                    outputs.add(sample to gain)
                    if (outputs.size <= 3) initial.countDown() else replayed.countDown()
                }
            }
        }
        withStream(sink) { stream, socket ->
            queue(stream, socket, listOf(record(0, -12f), record(1), record(2, -6f)))
            stream.setRate(0L, 1, BigInteger.ZERO)
            assertTrue("initial output incomplete", initial.await(3, TimeUnit.SECONDS))
            stream.flush(1024L)
            stream.setRate(1024L, 1, BigInteger.ZERO)
            assertTrue("flushed frames not replayed", replayed.await(3, TimeUnit.SECONDS))
            val actual = synchronized(outputs) { outputs.toList() }
            assertEquals(0.501187f, actual[1].second, 0.0001f)
            assertEquals(0.251189f, actual[2].second, 0.0001f)
            assertEquals(actual.subList(1, 3), actual.subList(3, 5))
        }
    }

    @Test fun metadataReceivedBeforeTheFlushBoundaryStillAppliesToLaterFrames() {
        val received = CountDownLatch(1)
        val outputs = mutableListOf<Pair<Int, Float>>()
        val sink = object : MediaSink {
            override fun onBufferedAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray,
                sample: Int, gain: Float) {
                synchronized(outputs) { outputs.add(sample to gain) }
                received.countDown()
            }
        }
        withStream(sink) { stream, socket ->
            stream.flush(1024L)
            queue(stream, socket, listOf(record(0, -12f), record(1), record(2)), expectedFrames = 2)
            stream.setRate(1024L, 1, BigInteger.ZERO)
            assertTrue("no buffered output", received.await(3, TimeUnit.SECONDS))
            val first = synchronized(outputs) { outputs.first() }
            assertEquals(1024, first.first)
            assertEquals(0.501187f, first.second, 0.0001f)
        }
    }

    private fun withStream(sink: MediaSink, check: (BufferedAudioStream, Socket) -> Unit) {
        BufferedAudioStream(ByteArray(32), AudioFormat(AudioCodecKind.AAC_LC, 48000, 2, 103), sink,
            nanoTime = { 0L }, apap = true, spatialFormats = listOf(65)).use { stream ->
            stream.storeMagicCookie(1, BufferedAacCookieTest.cookie())
            stream.start()
            Socket("127.0.0.1", stream.port).use { socket -> check(stream, socket) }
        }
    }

    private fun queue(stream: BufferedAudioStream, socket: Socket, records: List<ByteArray>,
        expectedFrames: Int = records.size) {
        records.forEach { socket.getOutputStream().write(it) }
        socket.getOutputStream().flush()
        val lock = BufferedAudioStream::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(stream)!!
        val queue = BufferedAudioStream::class.java.getDeclaredField("queue").apply { isAccessible = true }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        var size = 0
        while (System.nanoTime() < deadline) {
            size = synchronized(lock) { (queue.get(stream) as ArrayDeque<*>).size }
            if (size == expectedFrames) break
            Thread.sleep(5)
        }
        assertEquals("receiver did not queue the retained test frames", expectedFrames, size)
    }

    private fun record(sequence: Int, loudness: Float? = null): ByteArray {
        val metadata = loudness?.let {
            byteArrayOf(11, 12) + ByteBuffer.allocate(12).putFloat(it).putFloat(-1f).putFloat(-2f).array()
        } ?: ByteArray(0)
        val plain = byteArrayOf(2, 1, 23, 17, 1, 1) + metadata + byteArrayOf(0, 0x21, 0x22, 0x23)
        val header = ByteBuffer.allocate(15).putLong(sequence * 1024L).putInt(48000)
            .put(byteArrayOf(0, 0, sequence.toByte())).array()
        val nonce = ByteArray(12).also { it[11] = sequence.toByte() }
        val body = header + AirPlayCrypto.chachaSeal(ByteArray(32), nonce, plain,
            header.copyOfRange(0, 12)) + nonce.copyOfRange(4, 12)
        return ByteBuffer.allocate(4 + body.size).putInt(4 + body.size).put(body).array()
    }
}
