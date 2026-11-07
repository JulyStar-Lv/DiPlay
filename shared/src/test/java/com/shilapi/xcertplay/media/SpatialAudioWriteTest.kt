package com.shilapi.xcertplay.media

import android.media.AudioTrack
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, shadows = [SpatialAudioWriteTest.FrameTrack::class])
class SpatialAudioWriteTest {
    @Test fun prebufferWritesKeepEverySixAndTwelveChannelFrameIntact() {
        val type = Class.forName("com.shilapi.xcertplay.media.AudioRenderer")
        for ((channels, mask) in listOf(6 to 0xfc, 12 to 0xb58fc)) {
            val format = AudioFormat(AudioCodecKind.LPCM, 48000, channels, 103,
                pcmChannelMask = mask, pcmLittleEndian = true)
            val report: (String) -> Unit = {}
            val renderer = type.declaredConstructors.single { it.parameterCount == 13 }.apply { isAccessible = true }
                .newInstance(format, false, false, 0, 0, AudioFocusCoordinator(null, false, false),
                    0, 300, report, 0L, false, null, false)
            // The host Android framework limits tracks to eight channels. Exercise the writer
            // with a shadow track; the actual twelve-channel builder was checked on the car.
            val track = Shadow.newInstanceOf(AudioTrack::class.java)
            fun set(name: String, value: Any) = type.getDeclaredField(name).apply { isAccessible = true }.set(renderer, value)
            FrameTrack.stride = channels * 2
            FrameTrack.written = 0
            set("track", track)
            set("startThresholdBytes", 24000 * channels * 2)
            val pcm = ByteArray(1024 * channels * 2)
            try {
                type.getDeclaredMethod("writePcm", ByteArray::class.java, Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(renderer, pcm, 0, pcm.size)
                assertEquals(pcm.size, FrameTrack.written)
                assertEquals(1024L, type.getDeclaredField("totalWrittenFrames").apply { isAccessible = true }.getLong(renderer))
            } finally {
                type.getDeclaredMethod("close").apply { isAccessible = true }.invoke(renderer)
            }
        }
    }

    @Implements(AudioTrack::class)
    class FrameTrack {
        @Implementation fun write(data: ByteArray, offset: Int, length: Int, mode: Int): Int {
            assertEquals(0, offset % stride)
            assertEquals(0, length % stride)
            written += length
            return length
        }
        companion object { var stride = 0; var written = 0 }
    }
}
