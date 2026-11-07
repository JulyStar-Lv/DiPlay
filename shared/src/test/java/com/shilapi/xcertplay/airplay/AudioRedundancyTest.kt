package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

class AudioRedundancyTest {
    private val key = ByteArray(32) { it.toByte() }

    @Test fun decryptsAfterAllCleartextHeadersAndRestoresEveryBlockAcrossWrap() {
        for (outer in listOf(104, 105, 108, 109, 104 or 128)) {
            val wire = wire(outer, sequence = 0, timestamp = 160,
                blocks = listOf(Triple(96, 640, byteArrayOf(1, 2)), Triple(97, 320, byteArrayOf(3))),
                primary = byteArrayOf(4, 5, 6))
            val layout = AudioRedundancy.inspect(wire, true)
            assertEquals(21, layout.headerBytes)
            val opened = AudioPacketOpener(key).open(wire, layout.headerBytes)
            val packets = AudioRedundancy.unpack(opened, layout)
            assertEquals(listOf(65534, 65535, 0), packets.map(::sequence))
            assertEquals(listOf(-480, -160, 160), packets.map(AudioRedundancy::readTimestamp))
            assertEquals(listOf(96, 97, 96), packets.map { it[1].toInt() and 127 })
            assertArrayEquals(byteArrayOf(1, 2), packets[0].copyOfRange(12, packets[0].size))
            assertArrayEquals(byteArrayOf(3), packets[1].copyOfRange(12, packets[1].size))
            assertArrayEquals(byteArrayOf(4, 5, 6), packets[2].copyOfRange(12, packets[2].size))
            opened.fill(0)
            assertEquals(1, packets[0][12].toInt())
        }
    }

    @Test fun acceptsPrimaryOnlyRedAndMaximumOffsetAndLength() {
        for (blocks in listOf(emptyList(), listOf(Triple(96, 16383, ByteArray(1023) { 7 })))) {
            val wire = wire(blocks = blocks, primary = byteArrayOf(8))
            val layout = AudioRedundancy.inspect(wire, true)
            val packets = AudioRedundancy.unpack(AudioPacketOpener(key).open(wire, layout.headerBytes), layout)
            assertEquals(blocks.size + 1, packets.size)
            assertEquals(8, packets.last()[12].toInt())
            if (blocks.isNotEmpty()) assertEquals(1035, packets.first().size)
        }
    }

    @Test fun ordinaryRtpKeepsItsOriginalLayoutWithOrWithoutNegotiation() {
        val wire = MicrophonePacketizer.Sealer(key).sealPacket(100, MicrophoneCounters(), byteArrayOf(1, 2), 320)
        for (enabled in listOf(false, true)) {
            val layout = AudioRedundancy.inspect(wire, enabled)
            assertEquals(12, layout.headerBytes)
            val opened = AudioPacketOpener(key).open(wire, layout.headerBytes)
            assertSame(opened, AudioRedundancy.unpack(opened, layout).single())
        }
    }

    @Test fun rejectsUnnegotiatedTruncatedNestedOrOversizedRedHeaders() {
        val valid = wire()
        invalid { AudioRedundancy.inspect(valid, false) }
        for (length in 0..35) invalid { AudioRedundancy.inspect(valid.copyOf(length), true) }
        invalid { AudioRedundancy.inspect(valid.copyOf().apply { this[0] = 0x90.toByte() }, true) }
        invalid { AudioRedundancy.inspect(valid.copyOf().apply { this[12] = 0xe8.toByte() }, true) }
        invalid { AudioRedundancy.inspect(valid.copyOf().apply { this[14] = 3; this[15] = -1 }, true) }
        invalid { AudioRedundancy.inspect(ByteArray(12 + 17 * 4 + 1 + 24).apply {
            this[0] = 0x80.toByte(); this[1] = 104
            for (i in 0..16) this[12 + i * 4] = 0xe0.toByte()
        }, true) }
        for (offset in listOf(-1, 0, 11, Int.MAX_VALUE)) invalid { AudioPacketOpener(key).open(valid, offset) }
    }

    @Test fun tamperedCiphertextTagNonceOrTimestampNeverProducesRecoveredAudio() {
        val valid = wire()
        val opener = AudioPacketOpener(key)
        val layout = AudioRedundancy.inspect(valid, true)
        for (offset in listOf(4, layout.headerBytes, valid.size - 9, valid.lastIndex)) {
            val bad = valid.copyOf().apply { this[offset] = (this[offset].toInt() xor 1).toByte() }
            try { opener.open(bad, layout.headerBytes); fail("Accepted modified packet at $offset") }
            catch (_: java.security.GeneralSecurityException) { }
            catch (_: org.bouncycastle.crypto.InvalidCipherTextException) { }
        }
        assertEquals(2, AudioRedundancy.unpack(opener.open(valid, layout.headerBytes), layout).size)
    }

    internal fun wire(outer: Int = 104, sequence: Int = 10, timestamp: Int = 3200,
        blocks: List<Triple<Int, Int, ByteArray>> = listOf(Triple(96, 320, byteArrayOf(1, 2))),
        primary: ByteArray = byteArrayOf(3, 4)): ByteArray {
        val header = ByteArray(12 + blocks.size * 4 + 1)
        header[0] = 0x80.toByte(); header[1] = outer.toByte()
        header[2] = (sequence ushr 8).toByte(); header[3] = sequence.toByte()
        for (i in 0..3) header[4 + i] = (timestamp ushr (24 - 8 * i)).toByte()
        header[11] = 42
        blocks.forEachIndexed { index, (type, offset, payload) ->
            val p = 12 + 4 * index
            header[p] = (type or 128).toByte()
            header[p + 1] = (offset ushr 6).toByte()
            header[p + 2] = ((offset shl 2) or (payload.size ushr 8)).toByte()
            header[p + 3] = payload.size.toByte()
        }
        header[header.lastIndex] = 96
        val nonce = AirPlayCrypto.nonce64(123)
        val payload = blocks.fold(byteArrayOf()) { bytes, block -> bytes + block.third } + primary
        return header + AirPlayCrypto.chachaSealBouncyCastle(key, nonce, payload, header.copyOfRange(4, 12)) + nonce.copyOfRange(4, 12)
    }

    private fun sequence(rtp: ByteArray) = ((rtp[2].toInt() and 255) shl 8) or (rtp[3].toInt() and 255)
    private fun invalid(block: () -> Unit) {
        try { block(); fail("Expected invalid packet rejection") } catch (_: IllegalArgumentException) { }
    }
}
