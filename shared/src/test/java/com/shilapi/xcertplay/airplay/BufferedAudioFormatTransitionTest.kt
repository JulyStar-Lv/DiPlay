package com.shilapi.xcertplay.airplay

import java.math.BigInteger
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class BufferedAudioFormatTransitionTest {
    private val stereo = AudioFormat(AudioCodecKind.AAC_LC, 48000, 2, 103)
    private val spatial = AudioFormat(AudioCodecKind.LPCM, 48000, 12, 103,
        pcmChannelMask = 0xb58fc, pcmLittleEndian = true)

    @Test fun bothFormatTransitionsWaitForDrainAndDeliverTheNextFrameExactlyOnce() {
        for ((first, second) in listOf(stereo to spatial, spatial to stereo)) {
            val sink = DrainSink()
            stream(first, second, sink).use { stream ->
                stream.setRate(0L, 1, BigInteger.ZERO)
                stream.start()
                assertTrue("the old output must be asked to drain", sink.requested.await(3, TimeUnit.SECONDS))
                assertFalse(sink.next.await(100, TimeUnit.MILLISECONDS))
                assertEquals(listOf(first), sink.formats)
                assertEquals(listOf(first), sink.packets)
                sink.ready.set(true)
                assertTrue(sink.next.await(3, TimeUnit.SECONDS))
                assertEquals(listOf(first, second), sink.formats)
                assertEquals(listOf(first, second), sink.packets)
            }
        }
    }

    @Test fun pauseFlushAndCloseCanCancelAnUnfinishedFormatHandoff() {
        val controls: List<(BufferedAudioStream) -> Unit> = listOf(
            { it.setRate(null, 0, BigInteger.ZERO); Unit },
            { it.flush(2048L) },
            { it.close() },
        )
        for (control in controls) {
            val sink = DrainSink()
            stream(stereo, spatial, sink).use { stream ->
                stream.setRate(0L, 1, BigInteger.ZERO)
                stream.start()
                assertTrue(sink.requested.await(3, TimeUnit.SECONDS))
                control(stream)
                sink.ready.set(true)
                assertFalse("a cancelled handoff must not start the next format",
                    sink.next.await(100, TimeUnit.MILLISECONDS))
                assertEquals(listOf(stereo), sink.formats)
            }
        }
    }

    private class DrainSink : MediaSink {
        val formats = CopyOnWriteArrayList<AudioFormat>()
        val packets = CopyOnWriteArrayList<AudioFormat>()
        val requested = CountDownLatch(1)
        val ready = AtomicBoolean(false)
        val next = CountDownLatch(1)
        override fun onAudioStarted(id: AudioStreamId, format: AudioFormat, firstSample: Int) {
            formats += format
        }
        override fun onBufferedAudioDrain(id: AudioStreamId): Boolean {
            requested.countDown()
            return ready.get()
        }
        override fun onBufferedAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int, gain: Float) {
            packets += format
            if (packets.size == 2) next.countDown()
        }
    }

    private fun stream(first: AudioFormat, second: AudioFormat, sink: MediaSink): BufferedAudioStream {
        val stream = BufferedAudioStream(ByteArray(32), stereo, sink, nanoTime = { 0L },
            apap = true, spatialFormats = listOf(68))
        val frames = listOf(first, second).mapIndexed { index, format ->
            val audio = ByteArray(if (format.pcmChannelMask == null) 1 else 1024 * format.channels * 2)
            val packet = ApapPacket(index * 1024L, index, audio, null, null, null, 0, 0, 1f)
            BufferedAudioStream.Frame(index, packet.timestamp, audio, packet).apply {
                // Supply already-decoded frames so handoff tests do not need the optional native codec.
                decoded = BufferedApacOutput.Output(format, ByteArray(12) + audio, 1f)
                decodedOnce = true
            }
        }
        @Suppress("UNCHECKED_CAST")
        val queue = BufferedAudioStream::class.java.getDeclaredField("queue").apply { isAccessible = true }
            .get(stream) as ArrayDeque<BufferedAudioStream.Frame>
        queue.addAll(frames)
        BufferedAudioStream::class.java.getDeclaredField("queuedBytes").apply { isAccessible = true }
            .setLong(stream, frames.sumOf { it.bytes.toLong() })
        return stream
    }
}
