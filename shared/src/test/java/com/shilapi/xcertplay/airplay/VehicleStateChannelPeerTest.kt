package com.shilapi.xcertplay.airplay

import java.net.InetAddress
import java.net.Socket
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class VehicleStateChannelPeerTest {
    @Test fun emptyAndPayloadBearingSyncsAreAcknowledgedAndFragmentedCommandsStayOffIap() {
        val readKey = ByteArray(32) { 11 }
        val writeKey = ByteArray(32) { 22 }
        val peerCipher = ControlCipher(writeKey, readKey)
        val requests = mutableListOf<ByteArray>()
        val loopback = InetAddress.getByName("127.0.0.1")
        val channel = VehicleStateChannel(readKey, writeKey, loopback, { bytes ->
            requests.add(bytes)
            listOf("response".toByteArray())
        }, {})
        try {
            val port = channel.listen(loopback)
            Socket(loopback, port).use { socket ->
                socket.soTimeout = 5000
                val body = ByteArray(5000) { (it % 251).toByte() }
                val packet = ByteBuffer.allocate(32 + body.size).putInt(32 + body.size).putInt(0x73796e63)
                    .putLong(0).putInt(0x636d6e64).putLong(0x11223344).putInt(0).put(body).array()
                val emptySync = ByteBuffer.allocate(32).putInt(32).putInt(0x73796e63)
                    .putLong(0).putInt(0).putLong(0x55667788).putInt(0).array()
                val wire = peerCipher.encrypt(emptySync + packet.copyOfRange(0, 17)) +
                    peerCipher.encrypt(packet.copyOfRange(17, packet.size))
                // TCP and AEAD package boundaries are deliberately unrelated.
                socket.getOutputStream().write(wire, 0, 1)
                socket.getOutputStream().write(wire, 1, wire.size - 1)
                var encrypted = ByteArray(0)
                var plain = ByteArray(0)
                while (plain.size < 104) {
                    val buffer = ByteArray(4096)
                    val n = socket.getInputStream().read(buffer)
                    assertTrue(n > 0)
                    encrypted += buffer.copyOf(n)
                    val decoded = peerCipher.decrypt(encrypted)
                    plain += decoded.data
                    encrypted = decoded.rest
                }
                for ((index, token) in listOf(0x55667788L, 0x11223344L).withIndex()) {
                    val ack = ByteBuffer.wrap(plain, index * 32, 32)
                    assertEquals(32, ack.int)
                    assertEquals(0x72706c79, ack.int)
                    assertEquals(token, ByteBuffer.wrap(plain, index * 32 + 20, 8).long)
                }
                assertEquals(0x6173796e, ByteBuffer.wrap(plain, 68, 4).int)
                assertEquals("response", String(plain.copyOfRange(96, 104)))
                assertArrayEquals(body, requests.single())
            }
        } finally { channel.close() }
    }
}
