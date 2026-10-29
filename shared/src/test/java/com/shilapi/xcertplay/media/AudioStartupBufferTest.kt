package com.shilapi.xcertplay.media

import android.media.AudioAttributes
import android.media.AudioTrack
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE, shadows = [AudioStartupBufferTest.StartTrack::class])
class AudioStartupBufferTest {
    @Before fun reset() { StartTrack.plays = 0; StartTrack.frames = -1; StartTrack.head = 0; StartTrack.underruns = 0 }

    @Test fun a741MillisTransportGapCannotReleaseAnIncompleteOneSecondMusicBuffer() {
        withRenderer("media") { h ->
            h.write(16_384 * 4) // The B2 recording started at 341.3 ms instead of 1000 ms.
            for (gap in listOf(500L, 741L, 1_000L, 1_900L)) {
                h.idle(gap)
                h.maintain()
                assertEquals("gap=$gap", 0, StartTrack.plays)
            }
            h.write(48_000 * 4 - 16_384 * 4)
            assertEquals(1, StartTrack.plays)
            if (android.os.Build.VERSION.SDK_INT >= 31) assertEquals(48_000, StartTrack.frames)
            assertTrue(h.reports.toString(), h.reports.any {
                it.contains("reason=buffer-full prebufferFrames=48000 targetFrames=48000 requestedStartFrames=48000")
            })
        }
    }

    @Test fun aGenuinelyShortMusicFragmentStillPlaysAfterBoundedIdleWithAnExplicitReason() {
        withRenderer("media") { h ->
            h.write(4_800 * 4)
            h.idle(2_100)
            h.maintain()
            h.maintain()
            assertEquals(1, StartTrack.plays)
            if (android.os.Build.VERSION.SDK_INT >= 31) assertEquals(4_800, StartTrack.frames)
            assertTrue(h.reports.toString(), h.reports.any { it.contains("reason=idle-tail prebufferFrames=4800") })
        }
    }

    @Test fun freshInputAndPendingDecodedPcmBothPreventIdleTailStartup() {
        withRenderer("media") { h ->
            h.write(4_800 * 4)
            h.idle(2_100)
            (h.get("lastArrivalNs") as AtomicLong).set(System.nanoTime())
            h.maintain()
            assertEquals(0, StartTrack.plays)
            (h.get("lastArrivalNs") as AtomicLong).set(0)
            val pcm = h.get("pcmQueue") as AudioPcmQueue
            pcm.put(ByteArray(1920), 0, 1920) { true }
            h.maintain()
            assertEquals(0, StartTrack.plays)
            pcm.poll(0)
            // A compressed packet waiting for decode must also hold back the idle fallback.
            @Suppress("UNCHECKED_CAST")
            val queue = h.get("queue") as java.util.concurrent.BlockingQueue<Any>
            val packet = Class.forName("com.shilapi.xcertplay.media.AudioRenderer\$AudioPacket")
                .declaredConstructors.single().apply { isAccessible = true }.newInstance(ByteArray(12), 0, System.nanoTime())
            queue.offer(packet)
            h.maintain()
            assertEquals(0, StartTrack.plays)
            queue.clear()
            h.maintain()
            assertEquals(1, StartTrack.plays)
        }
    }

    @Test fun rebufferRecoveryKeepsTheFullMusicThresholdAcrossTheSameTransportGap() {
        withRenderer("media") { h ->
            h.write(192_000)
            assertEquals(1, StartTrack.plays)
            StartTrack.head = 48_000
            StartTrack.underruns = 1
            h.maintain()
            assertEquals(false, h.get("playbackStarted"))
            h.write(16_384 * 4)
            h.idle(741)
            h.maintain()
            assertEquals(1, StartTrack.plays)
            h.write(192_000 - 16_384 * 4)
            assertEquals(2, StartTrack.plays)
            assertTrue(h.reports.toString(), h.reports.any {
                it.contains("reason=buffer-full prebufferFrames=48000") && it.contains("rebufferCount=1")
            })
        }
    }

    @Test fun closingDoesNotReleaseAPartialMusicBufferAsAShortFragment() {
        withRenderer("media") { h ->
            h.write(4_800 * 4)
            h.type.getDeclaredMethod("close").apply { isAccessible = true }.invoke(h.renderer)
            h.idle(2_100)
            h.maintain()
            assertEquals(0, StartTrack.plays)
        }
    }

    @Test fun navigationSiriAndCallsKeepTheirShortFragmentFallback() {
        for (audioType in listOf("default", "alert", "speechrecognition", "telephony")) {
            StartTrack.plays = 0
            withRenderer(audioType) { h ->
                h.write(240 * 4)
                h.idle(100)
                h.maintain()
                assertEquals(audioType, 1, StartTrack.plays)
                if (android.os.Build.VERSION.SDK_INT >= 31) assertEquals(240, StartTrack.frames)
            }
        }
    }

    @Test fun idlePolicyKeepsSmallMusicPresetsBoundedAndVoiceLatencyUnchanged() {
        val music = AudioPlayoutPolicy.forStream(AudioFormat(AudioCodecKind.LPCM, 48000, 2, 103, "media"), true)
        assertEquals(1500L, music.idleStartMillis(300))
        assertEquals(1500L, music.idleStartMillis(500))
        assertEquals(2000L, music.idleStartMillis(1000))
        val voice = AudioPlayoutPolicy.forStream(AudioFormat(AudioCodecKind.LPCM, 48000, 2, 96, "default"), false)
        assertEquals(40L, voice.idleStartMillis(60))
    }

    private class Harness(val type: Class<*>, val renderer: Any, val reports: MutableList<String>) {
        fun set(name: String, value: Any) = type.getDeclaredField(name).apply { isAccessible = true }.set(renderer, value)
        fun get(name: String): Any = requireNotNull(type.getDeclaredField(name).apply { isAccessible = true }.get(renderer))
        fun write(bytes: Int) = type.getDeclaredMethod("writePcm", ByteArray::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).apply { isAccessible = true }
            .invoke(renderer, ByteArray(bytes), 0, bytes)
        fun idle(millis: Long) = set("lastPcmWriteNs", System.nanoTime() - millis * 1_000_000L)
        fun maintain() = type.getDeclaredMethod("maintainPlaybackBuffer").apply { isAccessible = true }.invoke(renderer)
    }

    private fun withRenderer(audioType: String, test: (Harness) -> Unit) {
        val reports = mutableListOf<String>()
        val track = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().build())
            .setAudioFormat(android.media.AudioFormat.Builder().setSampleRate(48_000)
                .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(230_400).build()
        val type = Class.forName("com.shilapi.xcertplay.media.AudioRenderer")
        val renderer = type.declaredConstructors.single { it.parameterCount == 13 }.apply { isAccessible = true }
            .newInstance(AudioFormat(AudioCodecKind.LPCM, 48_000, 2, 103, audioType), false, false, 0, 0,
                AudioFocusCoordinator(null, false, false), 0, 1000, { s: String -> reports.add(s); Unit },
                0L, false, null, false)
        val h = Harness(type, renderer, reports)
        h.set("track", track)
        h.set("fadeApplied", true)
        h.set("startThresholdBytes", if (audioType == "media") 192_000 else 11_520)
        h.set("bytesPerSecond", 192_000)
        try { test(h) } finally { track.release() }
    }

    @Implements(AudioTrack::class)
    class StartTrack {
        @Implementation fun getPlaybackHeadPosition() = head
        @Implementation fun getUnderrunCount() = underruns
        @Implementation fun play() { plays++ }
        @Implementation fun pause() = Unit
        @Implementation fun write(data: ByteArray, offset: Int, length: Int, mode: Int) = length
        @Implementation(minSdk = 31) fun setStartThresholdInFrames(value: Int): Int { frames = value; return value }
        companion object { var plays = 0; var frames = -1; var head = 0; var underruns = 0 }
    }
}
