package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import org.junit.Assert.*
import org.junit.Test

class RealtimeAudioJitterBufferTest {
    private val policy = AudioPlayoutPolicy.forStream(AudioFormat(AudioCodecKind.OPUS, 48000, 1, 100, "default", 16000), false)

    @Test fun startupReordersOriginalsAndRedDuplicatesBeforeDeadline() {
        val buffer = buffer()
        offer(buffer, 12, 640, 0)
        offer(buffer, 10, 0, 10)
        offer(buffer, 11, 320, 20)
        offer(buffer, 11, 320, 30)
        assertNull(buffer.poll(ms(59)))
        assertEquals(listOf(0, 320, 640), (1..3).map { received(buffer, 60).packet.sample })
        assertNull(buffer.poll(ms(61)))
        assertEquals(1, buffer.duplicates)
        offer(buffer, 10, 0, 70)
        assertEquals(1, buffer.late)
    }

    @Test fun missingPacketWaitsForRecoveryAndIsNeverPlayedTwice() {
        val buffer = buffer()
        offer(buffer, 1, 0, 0)
        received(buffer, 60)
        offer(buffer, 3, 640, 70)
        assertNull(buffer.poll(ms(100)))
        offer(buffer, 2, 320, 110)
        assertEquals(320, received(buffer, 110).packet.sample)
        assertEquals(640, received(buffer, 110).packet.sample)
        assertEquals(0, buffer.concealed)
    }

    @Test fun deadlineConcealsOnlyTheMissingDurationThenRejectsLateRecovery() {
        val buffer = buffer()
        offer(buffer, 1, 0, 0)
        received(buffer, 60)
        offer(buffer, 4, 960, 70)
        assertNull(buffer.poll(ms(129)))
        repeat(2) { assertEquals(320, (buffer.poll(ms(130)) as RealtimeAudioJitterBuffer.Output.Missing).ticks) }
        assertFalse(received(buffer, 130).resetDecoder)
        offer(buffer, 2, 320, 140)
        assertEquals(1, buffer.late)
        assertEquals(2, buffer.concealed)
        assertNull("no unbounded PLC after a prompt ends", buffer.poll(ms(10_000)))
    }

    @Test fun sequenceAndTimestampWrapPreserveTheTimeline() {
        val buffer = buffer()
        offer(buffer, 65535, -320, 0)
        offer(buffer, 1, 320, 10)
        assertEquals(-320, received(buffer, 60).packet.sample)
        assertEquals(320, (buffer.poll(ms(70)) as RealtimeAudioJitterBuffer.Output.Missing).ticks)
        assertEquals(320, received(buffer, 70).packet.sample)
        assertEquals(0, buffer.resets)
    }

    @Test fun longLossAndDtxResetInsteadOfGeneratingLongPlc() {
        val buffer = buffer()
        offer(buffer, 1, 0, 0)
        received(buffer, 60)
        offer(buffer, 100, 32000, 100)
        assertTrue(received(buffer, 160).resetDecoder)
        offer(buffer, 101, 40000, 200)
        assertTrue(received(buffer, 200).resetDecoder)
        assertEquals(0, buffer.concealed)
    }

    @Test fun changedSsrcAndIdlePromptStartFreshAndStayIsolated() {
        val a = buffer(); val b = buffer()
        offer(a, 1, 0, 0); offer(b, 500, 5000, 0)
        received(a, 60); received(b, 60)
        offer(a, 1, 100, 100, ssrc = 2)
        assertTrue(received(a, 160).resetDecoder)
        offer(b, 501, 5320, 100)
        assertFalse(received(b, 160).resetDecoder)
        offer(a, 2, 420, 1000, ssrc = 2)
        assertNull(a.poll(ms(1059)))
        assertTrue(received(a, 1060).resetDecoder)
    }

    @Test fun redundantTailOfAnOldPromptCannotReplayAfterSilence() {
        val buffer = buffer()
        offer(buffer, 1, 0, 0); received(buffer, 60)
        offer(buffer, 1, 0, 1000)
        assertEquals(1L, buffer.late)
        assertNull(buffer.poll(ms(1060)))
        offer(buffer, 2, 16000, 1000)
        assertTrue(received(buffer, 1060).resetDecoder)
        assertEquals(0L, buffer.concealed)
    }

    @Test fun pcmAt44100HzConcealsItsExactGapWithoutOpusFrameQuantization() {
        val buffer = RealtimeAudioJitterBuffer(44100, policy)
        offer(buffer, 1, 0, 0, duration = 441); received(buffer, 60)
        offer(buffer, 3, 882, 100, duration = 441)
        assertEquals(441, (buffer.poll(ms(160)) as RealtimeAudioJitterBuffer.Output.Missing).ticks)
        assertFalse(received(buffer, 160).resetDecoder)
    }

    @Test fun variableFrameSizesUseTimestampGapRatherThanAssumingTwentyMillis() {
        val buffer = buffer()
        offer(buffer, 1, 0, 0, duration = 160)
        received(buffer, 60)
        offer(buffer, 3, 800, 100, duration = 640)
        assertEquals(640, (buffer.poll(ms(160)) as RealtimeAudioJitterBuffer.Output.Missing).ticks)
        assertEquals(800, received(buffer, 160).packet.sample)
    }

    @Test fun impossibleDurationAndQueueOverflowAreBounded() {
        val buffer = RealtimeAudioJitterBuffer(16000, policy, 8)
        repeat(100) { offer(buffer, it, it * 320, 0) }
        assertTrue(buffer.size <= 8)
        assertTrue(buffer.resets > 0)
        received(buffer, 60)
        val malformed = buffer()
        offer(malformed, 1, 0, 0); received(malformed, 60)
        offer(malformed, 3, 321, 100)
        assertTrue(received(malformed, 160).resetDecoder)
        malformed.offer(ByteArray(3), 0, 320, 0)
        malformed.offer(ByteArray(12), 0, 0, 0)
        assertEquals(0, malformed.size)
    }

    private fun buffer() = RealtimeAudioJitterBuffer(16000, policy, concealQuantumTicks = 40)
    private fun ms(value: Long) = value * 1_000_000L
    private fun offer(buffer: RealtimeAudioJitterBuffer, sequence: Int, sample: Int, millis: Long,
        duration: Int = 320, ssrc: Int = 1) {
        val rtp = ByteArray(13)
        rtp[2] = (sequence ushr 8).toByte(); rtp[3] = sequence.toByte(); rtp[11] = ssrc.toByte()
        buffer.offer(rtp, sample, duration, ms(millis))
    }
    private fun received(buffer: RealtimeAudioJitterBuffer, millis: Long) =
        buffer.poll(ms(millis)) as RealtimeAudioJitterBuffer.Output.Received
}
