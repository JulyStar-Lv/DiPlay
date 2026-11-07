package com.shilapi.xcertplay.media

import android.media.AudioAttributes
import android.media.AudioTrack
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE, shadows = [AudioWritePacingIntegrationTest.PacedTrack::class])
class AudioWritePacingIntegrationTest {
    @Before fun reset() {
        PacedTrack.output.reset(); PacedTrack.headReads = 0; PacedTrack.advanceHead = false
        PacedTrack.zeros = 0; PacedTrack.maxWrite = Int.MAX_VALUE; PacedTrack.error = false
    }

    @Test fun aCoarseHeadAdvanceResumesPacingWithoutLosingOrRepeatingPcm() {
        for (millis in listOf(300, 1000)) {
            reset()
            PacedTrack.advanceHead = true
            withRenderer(millis) { type, renderer, reports ->
                // Begin at the pacing ceiling. The fake device consumes 20 ms after one wait.
                val ceiling = 48L * millis + 1920
                val progress = field(type, renderer, "bufferProgress") as AudioBufferProgress
                progress.written((ceiling * 4).toInt())
                set(type, renderer, "totalWrittenFrames", ceiling)
                val pcm = ByteArray(960 * 4) { (it / 7).toByte() }
                write(type, renderer, pcm)
                assertArrayEquals(pcm, PacedTrack.output.toByteArray())
                stats(type, renderer)
                assertTrue(reports.toString(), reports.any { it.contains("pacingWaits=1 writeWaits=1") })
                assertTrue(reports.toString(), reports.any { it.contains("zeroWrites=0") })
                assertEquals(ceiling * 4, progress.queuedBytes(960))
            }
        }
    }

    @Test fun zeroAndPartialWritesKeepEveryByteAndReportWaitsSeparatelyFromErrors() {
        PacedTrack.zeros = 3; PacedTrack.maxWrite = 256
        withRenderer { type, renderer, reports ->
            val pcm = ByteArray(1920) { it.toByte() }
            write(type, renderer, pcm)
            assertArrayEquals(pcm, PacedTrack.output.toByteArray())
            stats(type, renderer)
            assertTrue(reports.toString(), reports.any { it.contains("zeroWrites=3 partialWrites=7") })
            assertTrue(reports.toString(), reports.any { it.contains("pacingWaits=0 writeWaits=3") })
            assertTrue(reports.toString(), reports.any { it.contains("writeErrors=0") })
            stats(type, renderer)
            assertTrue(reports.last { it.startsWith("Audio: queue stats") }
                .contains("pacingWaits=0 writeWaits=0 writeWaitMs=0"))
        }
    }

    @Test fun aPermanentWriteErrorIsReportedInsteadOfRetriedForever() {
        PacedTrack.error = true
        withRenderer { type, renderer, reports ->
            write(type, renderer, ByteArray(1920))
            assertEquals(0, PacedTrack.output.size())
            stats(type, renderer)
            assertTrue(reports.toString(), reports.any { it.contains("writeErrors=1 lastWriteError=-3") })
            assertTrue(reports.toString(), reports.any { it.contains("writeWaits=0") })
        }
    }

    private fun withRenderer(millis: Int = 300, test: (Class<*>, Any, MutableList<String>) -> Unit) {
        val reports = mutableListOf<String>()
        val track = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().build())
            .setAudioFormat(android.media.AudioFormat.Builder().setSampleRate(48_000)
                .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes((millis + 200) * 192).build()
        val type = Class.forName("com.shilapi.xcertplay.media.AudioRenderer")
        val renderer = type.declaredConstructors.single { it.parameterCount == 13 }.apply { isAccessible = true }
            .newInstance(AudioFormat(AudioCodecKind.LPCM, 48_000, 2, 103, "media"), false, false, 0, 0,
                AudioFocusCoordinator(null, false, false), 0, millis, { s: String -> reports.add(s); Unit },
                0L, false, null, false)
        set(type, renderer, "track", track)
        set(type, renderer, "playbackStarted", true)
        set(type, renderer, "fadeApplied", true)
        set(type, renderer, "startThresholdBytes", millis * 192)
        try { test(type, renderer, reports) } finally { track.release() }
    }

    private fun set(type: Class<*>, renderer: Any, name: String, value: Any) =
        type.getDeclaredField(name).apply { isAccessible = true }.set(renderer, value)
    private fun field(type: Class<*>, renderer: Any, name: String) =
        type.getDeclaredField(name).apply { isAccessible = true }.get(renderer)
    private fun write(type: Class<*>, renderer: Any, pcm: ByteArray) =
        type.getDeclaredMethod("writePcm", ByteArray::class.java, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(renderer, pcm, 0, pcm.size)
    private fun stats(type: Class<*>, renderer: Any) = type.getDeclaredMethod("logStatsIfDue",
        Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(renderer, true)

    @Implements(AudioTrack::class)
    class PacedTrack {
        @Implementation fun getPlaybackHeadPosition(): Int = if (advanceHead && headReads++ > 0) 960 else 0
        @Implementation fun getUnderrunCount() = 0
        @Implementation fun write(bytes: ByteArray, offset: Int, size: Int, mode: Int): Int {
            assertEquals(AudioTrack.WRITE_NON_BLOCKING, mode)
            if (error) return AudioTrack.ERROR_INVALID_OPERATION
            if (zeros > 0) { zeros--; return 0 }
            val count = minOf(size, maxWrite)
            output.write(bytes, offset, count)
            return count
        }
        companion object {
            val output = ByteArrayOutputStream()
            var advanceHead = false
            var headReads = 0
            var zeros = 0
            var maxWrite = Int.MAX_VALUE
            var error = false
        }
    }
}
