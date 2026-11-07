package com.shilapi.xcertplay.airplay

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class ApapPacketTest {
    private val key = ByteArray(32) { it.toByte() }

    @Test fun authenticatesBothAadFormsWithoutMistakingAudioForExtensions() {
        val audio = byteArrayOf(0, 0x40, -1)
        val extension = ApapPacket.open(key, seal(tlv(2, byteArrayOf(65)) + byteArrayOf(0) + audio, true))!!
        assertEquals(65, extension.formatIndex)
        assertEquals(0xffffff, extension.sequence)
        assertEquals(123456L, extension.timestamp)
        assertArrayEquals(audio, extension.audio)
        val direct = ApapPacket.open(key, seal(audio, false))!!
        assertNull(direct.formatIndex)
        assertArrayEquals(audio, direct.audio)
    }

    @Test fun refusesTamperingWrongKeysBadScaleAndMalformedAuthenticatedTlvs() {
        val valid = seal(byteArrayOf(0, 0x40), true)
        for (offset in listOf(0, 8, 15, valid.lastIndex)) {
            assertNull(ApapPacket.open(key, valid.copyOf().also { it[offset] = (it[offset].toInt() xor 1).toByte() }))
        }
        assertNull(ApapPacket.open(ByteArray(32), valid))
        assertNull(ApapPacket.open(key, seal(byteArrayOf(0), true, 44100)))
        for (bad in listOf(byteArrayOf(18, 127, 1), byteArrayOf(2, 1, 65),
            byteArrayOf(-1, -1, -1, -1, -1, 0), tlv(8, byteArrayOf(1)) + byteArrayOf(0),
            tlv(18, ByteArray(4097)) + byteArrayOf(0))) {
            assertNull(ApapPacket.open(key, seal(bad, true)))
        }
    }

    @Test fun extractsBoundedCookieTrimAndGainOnlyAfterAuthentication() {
        val trim = ByteBuffer.allocate(12).putLong(1).putInt(100).array()
        val huge = ByteBuffer.allocate(12).putLong(Long.MAX_VALUE).putInt(1).array()
        val loudness = ByteBuffer.allocate(12).putFloat(-9f).putFloat(-1f).putFloat(-2f).array()
        val payload = tlv(17, ByteArray(8) { -1 }) + tlv(18, byteArrayOf(1, 2)) +
            tlv(4, trim) + tlv(5, huge) + tlv(11, loudness) + byteArrayOf(0)
        val packet = ApapPacket.open(key, seal(payload, true))!!
        assertEquals(-1L, packet.cookieId)
        assertArrayEquals(byteArrayOf(1, 2), packet.cookie)
        assertEquals(480, packet.trimStart)
        assertEquals(1024, packet.trimEnd)
        assertEquals(0.3548f, packet.gain!!, 0.001f)
        assertEquals(listOf(-9f, -1f, -2f), packet.loudnessParameters)
        assertTrue(packet.audio.isEmpty())
    }

    @Test fun retainsFullMediaTimeAndAuthenticatedTransitionForTimelineDiagnostics() {
        val mediaTime = 0x1_0000_0010L
        val transition = ByteBuffer.allocate(8).putLong(42).array()
        val sealed = seal(tlv(10, transition) + byteArrayOf(0, 0x40), true, time = mediaTime)
        val packet = ApapPacket.open(key, sealed)!!
        assertEquals(0x10L, packet.timestamp)
        assertEquals(mediaTime, packet.mediaTimeValue)
        assertEquals(42L, packet.transitionId)
        assertArrayEquals(byteArrayOf(0x40), packet.audio)
        assertNull(ApapPacket.open(key, sealed.copyOf().also { it[15] = (it[15].toInt() xor 1).toByte() }))
        assertEquals(mediaTime, ApapPacket.open(key, seal(byteArrayOf(0x40), false, time = mediaTime))!!.mediaTimeValue)
        // Diagnostics must not reject a previously ignored transition encoding.
        assertNull(ApapPacket.open(key, seal(tlv(10, ByteArray(9)) + byteArrayOf(0), true))!!.transitionId)
    }

    private fun tlv(id: Int, bytes: ByteArray): ByteArray {
        var length = bytes.size
        val encoded = ArrayList<Byte>()
        do {
            val next = length and 127
            length = length ushr 7
            encoded += (next or if (length > 0) 128 else 0).toByte()
        } while (length > 0)
        return byteArrayOf(id.toByte()) + encoded.toByteArray() + bytes
    }

    private fun seal(bytes: ByteArray, extension: Boolean, scale: Int = 48000, time: Long = 123456): ByteArray {
        val header = ByteBuffer.allocate(15).putLong(time).putInt(scale).put(byteArrayOf(-1, -1, -1)).array()
        val tail = ByteArray(8) { (it + 3).toByte() }
        val nonce = ByteArray(12).also { tail.copyInto(it, 4) }
        val aad = if (extension) header.copyOfRange(0, 12) else header.copyOfRange(4, 12)
        return header + AirPlayCrypto.chachaSeal(key, nonce, bytes, aad) + tail
    }
}
