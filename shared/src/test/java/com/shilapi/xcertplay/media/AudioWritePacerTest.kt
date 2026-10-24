package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class AudioWritePacerTest {
    @Test fun musicWritesAreFrameAlignedAndCannotFillBurstHeadroomPastTheTarget() {
        for (rate in listOf(8_000, 16_000, 44_100, 48_000)) for (channels in 1..2) {
            val frame = channels * 2
            for (millis in MediaAudioBuffer.presets) {
                val target = rate.toLong() * millis / 1000
                val ceiling = target + rate.toLong() * 40 / 1000
                assertEquals(0, AudioWritePacer.allowedBytes(4096, ceiling, target, rate, frame, true))
                assertEquals(0, AudioWritePacer.allowedBytes(4096, ceiling + rate, target, rate, frame, true))
                val allowed = AudioWritePacer.allowedBytes(4095, ceiling - 5, target, rate, frame, true)
                assertEquals(5 * frame, allowed)
                assertEquals(0, allowed % frame)
                assertEquals(4096, AudioWritePacer.allowedBytes(4096, 0, target, rate, frame, false))
            }
        }
    }

    @Test fun startupAndInteractiveWritesBypassMusicPacing() {
        assertEquals(1920, AudioWritePacer.allowedBytes(1920, 50_000, 14_400, 48_000, 4, false))
        assertEquals(1920, AudioWritePacer.allowedBytes(1920, 50_000, 0, 48_000, 4, true))
        assertEquals(1920, AudioWritePacer.allowedBytes(1920, -100, 14_400, 48_000, 4, true))
    }

    @Test fun retryBackoffReducesFullTrackPollingAndRemainsBoundedForCloseAndVoiceLatency() {
        assertEquals(listOf(2L, 4L, 8L, 16L, 20L, 20L), (1..6).map { AudioWritePacer.zeroWriteWaitMillis(it, true) })
        assertEquals(listOf(2L, 4L, 5L, 5L), (1..4).map { AudioWritePacer.zeroWriteWaitMillis(it, false) })
        assertEquals(20L, AudioWritePacer.zeroWriteWaitMillis(Int.MAX_VALUE, true))
        val ceiling = 14_400L + 1920
        assertEquals(10L, AudioWritePacer.pacingWaitMillis(ceiling, 14_400, 48_000))
        assertEquals(20L, AudioWritePacer.pacingWaitMillis(ceiling + 48_000, 14_400, 48_000))
    }
}
