package com.shilapi.xcertplay.airplay

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 31], manifest = Config.NONE)
class AudioStreamReceiveTest {
    @Test fun negotiatedRedUdpDeliversRecoveredAndPrimaryRtpAndSurvivesABadDatagram() {
        val packets = CopyOnWriteArrayList<ByteArray>()
        val started = CopyOnWriteArrayList<Int>()
        val errors = CopyOnWriteArrayList<Throwable>()
        val received = CountDownLatch(2)
        val stream = AudioStream(ByteArray(32) { it.toByte() }, 100, redundancy = true)
        val port = stream.listen(object : AudioStream.Listener {
            override fun onStarted(firstSample: Int) { started += firstSample }
            override fun onRtp(rtp: ByteArray, sample: Int) { packets += rtp; received.countDown() }
            override fun onPacket(wire: ByteArray, rtp: ByteArray?, sample: Int?, error: Throwable?) {
                if (error != null) errors += error
            }
        }).first
        val wire = AudioRedundancyTest().wire()
        try {
            DatagramSocket().use { socket ->
                val bad = wire.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
                for (packet in listOf(bad, wire)) socket.send(DatagramPacket(packet, packet.size, InetAddress.getLoopbackAddress(), port))
            }
            assertTrue(received.await(5, TimeUnit.SECONDS))
            assertEquals(1, errors.size)
            assertEquals(listOf(2880), started.toList())
            assertEquals(listOf(2880, 3200), packets.map(AudioRedundancy::readTimestamp))
            assertArrayEquals(byteArrayOf(1, 2), packets[0].copyOfRange(12, packets[0].size))
            assertArrayEquals(byteArrayOf(3, 4), packets[1].copyOfRange(12, packets[1].size))
        } finally { stream.close() }
    }

    @Test fun alternatingDatagramSizesRetainOwnedPacketsAndReportDecryptTiming() {
        val key = ByteArray(32) { it.toByte() }
        val payloads = listOf(1, 1400, 0, 512).map { size -> ByteArray(size) { (it + size).toByte() } }
        val wires = payloads.mapIndexed { index, body ->
            MicrophonePacketizer.sealPacket(key, 100, MicrophoneCounters(index, index * 480, index.toLong()), body, 480)
        }
        val received = CopyOnWriteArrayList<Pair<ByteArray, ByteArray>>()
        val diagnostics = CopyOnWriteArrayList<String>()
        val latch = CountDownLatch(wires.size)
        val ended = CountDownLatch(1)
        val stream = AudioStream(key, 100) {
            diagnostics.add(it)
            if (it.contains("ended=true")) ended.countDown()
        }
        val ports = stream.listen(object : AudioStream.Listener {
            override fun onPacket(wire: ByteArray, rtp: ByteArray?, sample: Int?, error: Throwable?) {
                if (rtp != null) received.add(wire to rtp)
                latch.countDown()
            }
        })
        try {
            DatagramSocket().use { sender ->
                for (wire in wires) sender.send(DatagramPacket(wire, wire.size, InetAddress.getLoopbackAddress(), ports.first))
            }
            assertTrue("audio receiver did not finish", latch.await(10, TimeUnit.SECONDS))
        } finally { stream.close() }
        assertTrue("receiver did not flush its timing", ended.await(10, TimeUnit.SECONDS))
        assertEquals(wires.size, received.size)
        for (index in wires.indices) {
            assertArrayEquals(wires[index], received[index].first)
            assertArrayEquals(wires[index].copyOf(12) + payloads[index], received[index].second)
        }
        val summary = diagnostics.last { it.contains("ended=true") }
        assertTrue(summary.contains("decryptAvgUs="))
        assertTrue(summary.contains("seqForwardGaps=0"))
    }
}
