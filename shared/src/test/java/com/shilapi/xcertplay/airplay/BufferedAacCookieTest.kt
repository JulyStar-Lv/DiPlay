package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

class BufferedAacCookieTest {
    @Test fun acceptsBoundedMpegDescriptorsAndRejectsUnadvertisedAacConfigurations() {
        val valid = cookie()
        assertTrue(BufferedAacCookie.supported(valid))
        assertFalse(BufferedAacCookie.supported(cookie(byteArrayOf(0x12, 0x10)))) // 44.1k
        assertFalse(BufferedAacCookie.supported(cookie(byteArrayOf(0x11, 0x88.toByte())))) // mono
        assertFalse(BufferedAacCookie.supported(cookie(byteArrayOf(0x11, 0x94.toByte())))) // 960 frames
        assertFalse(BufferedAacCookie.supported(cookie(decoderStreamType = 0x11))) // visual stream
        assertFalse(BufferedAacCookie.supported(cookie(decoderStreamType = 0x17))) // upstream audio
        for (end in valid.indices) assertFalse(BufferedAacCookie.supported(valid.copyOf(end)))
    }

    @Test fun iphoneStereoCookieWithClearedReservedBitUsesAacInsteadOfApac() {
        // Captured when Apple Music switched from Atmos to a lossless stereo item.
        val cookie = "0380808022000000048080801440140018000001f4000001f40005808080021190068080800102"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertTrue(BufferedAacCookie.supported(cookie))
        BufferedApacOutput(listOf(68)) {}.use { output ->
            val audio = byteArrayOf(0x21, 0x22)
            val packet = ApapPacket(0, 0, audio, cookie, null, 23, 0, 0, null)
            val rendered = output.render(packet)!!
            assertEquals(AudioCodecKind.AAC_LC, rendered.format.codec)
            assertEquals(48000, rendered.format.sampleRate)
            assertEquals(2, rendered.format.channels)
            assertNull(rendered.format.pcmChannelMask)
            assertArrayEquals(audio, rendered.rtp.copyOfRange(12, rendered.rtp.size))
        }
    }

    @Test fun cookieRegistryKeepsTheFirstValueAndAacOutputRequiresNoNativeCodec() {
        BufferedApacOutput(listOf(65)) {}.use { output ->
            output.storeCookie(7, cookie())
            output.storeCookie(7, byteArrayOf(1))
            val packet = ApapPacket(0, 0, byteArrayOf(0x21), null, 7, 23, 0, 0, null)
            val rendered = output.render(packet)!!
            assertEquals(AudioCodecKind.AAC_LC, rendered.format.codec)
            assertEquals(48000, rendered.format.sampleRate)
            assertEquals(2, rendered.format.channels)
            assertArrayEquals(byteArrayOf(0x21), rendered.rtp.copyOfRange(12, rendered.rtp.size))
        }
    }

    @Test fun inlineCookieAndIdRemainUsableByLaterReferencesIncludingAfterReset() {
        for (firstAudio in listOf(ByteArray(0), byteArrayOf(0x21))) {
            BufferedApacOutput(listOf(65)) {}.use { output ->
                val first = ApapPacket(0, 0, firstAudio, cookie(), 7, 23, 0, 0, null)
                output.render(first)
                val reference = first.copy(sequence = 1, audio = byteArrayOf(0x22), cookie = null)
                assertArrayEquals(reference.audio, output.render(reference)!!.rtp.copyOfRange(12, 13))
                output.reset()
                assertArrayEquals(reference.audio, output.render(reference)!!.rtp.copyOfRange(12, 13))
            }
        }
    }

    companion object {
        fun cookie(asc: ByteArray = byteArrayOf(0x11, 0x90.toByte()), decoderStreamType: Int = 0x15): ByteArray {
            fun descriptor(tag: Int, data: ByteArray) = byteArrayOf(tag.toByte(), 0x80.toByte(),
                0x80.toByte(), 0x80.toByte(), data.size.toByte()) + data
            val config = byteArrayOf(0x40, decoderStreamType.toByte()) + ByteArray(11) + descriptor(5, asc)
            return descriptor(3, ByteArray(3) + descriptor(4, config))
        }
    }
}
