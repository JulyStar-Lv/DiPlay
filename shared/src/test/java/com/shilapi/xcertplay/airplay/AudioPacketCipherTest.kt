package com.shilapi.xcertplay.airplay

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.AEADBadTagException
import org.bouncycastle.crypto.InvalidCipherTextException
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class AudioPacketCipherTest {
    private val key = ByteArray(32) { (it + 1).toByte() }

    @Test fun slicesAuthenticateOnlyTheRequestedAadAndPreserveOutputBoundaries() = bothImplementations {
        val cipher = AirPlayCrypto.PacketCipher(key)
        for (size in listOf(0, 1, 63, 64, 65, 512, 1400, 4096, 65_000)) {
            val plain = Random(size).nextBytes(size)
            val aad = byteArrayOf(7, 8, 9)
            val nonce = AirPlayCrypto.nonce64(size.toLong())
            val paddedInput = byteArrayOf(99) + plain + byteArrayOf(98)
            val paddedAad = byteArrayOf(97) + aad + byteArrayOf(96)
            val sealed = ByteArray(size + 16 + 4) { 42 }
            assertEquals(size + 16, cipher.cryptInto(true, nonce, paddedInput, 1, size,
                paddedAad, 1, aad.size, sealed, 2))
            val expected = AirPlayCrypto.chachaSealBouncyCastle(key, nonce, plain, aad)
            assertArrayEquals(expected, sealed.copyOfRange(2, sealed.size - 2))
            assertEquals(42, sealed.first().toInt())
            assertEquals(42, sealed.last().toInt())
            paddedAad[0] = 0 // Bytes outside AAD must not affect authentication.
            val opened = ByteArray(size + 4) { 43 }
            assertEquals(size, cipher.cryptInto(false, nonce, sealed, 2, expected.size,
                paddedAad, 1, aad.size, opened, 2))
            assertArrayEquals(plain, opened.copyOfRange(2, opened.size - 2))
            assertEquals(43, opened.first().toInt())
            assertEquals(43, opened.last().toInt())
        }
    }

    @Test fun tamperedAudioNeverReturnsPlaintextAndNextPacketStillOpens() = bothImplementations {
        val opener = AudioPacketOpener(key)
        val sealer = MicrophonePacketizer.Sealer(key)
        val counters = MicrophoneCounters()
        for (offset in listOf(4, 12, 12 + 80, 12 + 80 + 16)) {
            val wire = sealer.sealPacket(100, counters, ByteArray(80) { it.toByte() }, 480)
            val bad = wire.copyOf().apply { this[offset] = (this[offset].toInt() xor 1).toByte() }
            try {
                opener.open(bad)
                fail("accepted modified timestamp, ciphertext, tag or nonce at $offset")
            } catch (_: AEADBadTagException) {
                assertNotEquals("BouncyCastle", AirPlayCrypto.chachaImplementation)
            } catch (_: InvalidCipherTextException) { }
            assertArrayEquals(wire.copyOf(12) + ByteArray(80) { it.toByte() }, opener.open(wire))
        }
    }

    @Test fun streamScratchSpaceNeverChangesPreviouslyReturnedPacketsOrTheCallersKey() = bothImplementations {
        val suppliedKey = key.copyOf()
        val sealer = MicrophonePacketizer.Sealer(suppliedKey)
        val opener = AudioPacketOpener(suppliedKey)
        suppliedKey.fill(0) // Both stream objects own a snapshot of the negotiated key.
        val counters = MicrophoneCounters(sequence = 65535, timestamp = -480, nonce = 255)
        var savedWire: ByteArray? = null
        var savedRtp: ByteArray? = null
        var expectedWire: ByteArray? = null
        var expectedRtp: ByteArray? = null
        for ((index, size) in listOf(1, 1400, 0, 64, 4096, 17).withIndex()) {
            val plain = ByteArray(size) { (it + index).toByte() }
            val wire = sealer.sealPacket(100, counters, plain, 480)
            val wireCopy = wire.copyOf()
            val rtp = opener.open(wire)
            assertArrayEquals(wireCopy, wire)
            assertArrayEquals(wire.copyOf(12) + plain, rtp)
            if (index == 0) {
                savedWire = wire; savedRtp = rtp
                expectedWire = wire.copyOf(); expectedRtp = rtp.copyOf()
            }
            assertArrayEquals(expectedWire, savedWire)
            assertArrayEquals(expectedRtp, savedRtp)
        }
        assertEquals(5, counters.sequence)
        assertEquals(2400, counters.timestamp)
        assertEquals(261L, counters.nonce)
    }

    @Test fun invalidSlicesFailBeforeWritingOutput() {
        val cipher = AirPlayCrypto.PacketCipher(key)
        val nonce = AirPlayCrypto.nonce64(1)
        val input = ByteArray(32)
        val output = ByteArray(64) { 42 }
        val invalid = listOf<() -> Unit>(
            { cipher.cryptInto(true, nonce, input, -1, 1, input, 0, 0, output, 0) },
            { cipher.cryptInto(true, nonce, input, 0, Int.MAX_VALUE, input, 0, 0, output, 0) },
            { cipher.cryptInto(true, nonce, input, 0, 1, input, 32, 1, output, 0) },
            { cipher.cryptInto(true, nonce, input, 0, 32, input, 0, 0, output, 17) },
            { cipher.cryptInto(false, nonce, input, 0, 15, input, 0, 0, output, 0) },
            { cipher.cryptInto(true, ByteArray(8), input, 0, 1, input, 0, 0, output, 0) },
            { cipher.cryptInto(false, nonce, input, 0, 32, input, 0, 0, input, 0) },
        )
        for (operation in invalid) {
            try { operation(); fail("accepted invalid packet slice") } catch (_: IllegalArgumentException) { }
            assertArrayEquals(ByteArray(64) { 42 }, output)
        }
    }

    @Test fun concurrentStreamsKeepTheirKeysAndScratchSpaceSeparate() {
        val pool = Executors.newFixedThreadPool(4)
        try {
            val futures = (0..3).map { id -> pool.submit {
                val streamKey = ByteArray(32) { (it + id).toByte() }
                val sealer = MicrophonePacketizer.Sealer(streamKey)
                val opener = AudioPacketOpener(streamKey)
                val counters = MicrophoneCounters()
                repeat(30) { n ->
                    val plain = ByteArray(if (n % 2 == 0) 512 else 1400) { (it + n).toByte() }
                    val wire = sealer.sealPacket(100, counters, plain, 480)
                    assertArrayEquals(wire.copyOf(12) + plain, opener.open(wire))
                }
            } }
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
    }

    private fun bothImplementations(operation: () -> Unit) {
        operation()
        val state = AirPlayCrypto::class.java.getDeclaredField("platformState").apply { isAccessible = true }
            .get(null) as ThreadLocal<*>
        val saved = state.get()
        @Suppress("UNCHECKED_CAST")
        val mutable = state as ThreadLocal<Any?>
        mutable.set(null)
        try { operation() } finally { mutable.set(saved) }
    }
}
