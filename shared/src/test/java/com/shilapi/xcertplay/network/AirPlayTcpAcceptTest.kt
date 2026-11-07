package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.airplay.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class AirPlayTcpAcceptTest {
    @Test fun oemCommandThroughAcceptedSocketReachesTheAttachedListener() {
        val service = Robolectric.buildService(CarPlayVpnService::class.java).create().get()
        val requests = LinkedBlockingQueue<AirPlaySession>()
        val address = InetAddress.getByName("127.0.0.1")
        val config = AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480), port = 0)
        try {
            assertEquals(CarPlayVpnService.AttachResult.Started, service.attachWireless(address, config,
                AirPlayIdentity(ByteArray(32), ByteArray(32), "test"), PairingStore(), null,
                object : AirPlaySessionListener {
                    override fun onHostUiRequested(session: AirPlaySession): Boolean {
                        requests.add(session)
                        return true
                    }
                }, object : AirPlayMediaHandler {}, listenerIdentity = AirPlayListenerIdentity(1)))
            Socket(address, service.boundPort()!!).use { socket ->
                socket.soTimeout = 2_000
                val body = BplistCodec.encode(mapOf("type" to "requestUI",
                    "params" to emptyMap<String, Any?>()))
                val header = "POST /command RTSP/1.0\r\nCSeq: 1\r\nContent-Length: ${body.size}\r\n\r\n"
                socket.getOutputStream().apply {
                    write(header.toByteArray(Charsets.US_ASCII))
                    write(body)
                    flush()
                }
                val response = socket.getInputStream().bufferedReader().readLine()
                assertEquals("RTSP/1.0 200 OK", response)
                assertNotNull("The transport wrapper must forward OEM return, not inherit the no-op default",
                    requests.poll(2, TimeUnit.SECONDS))
                assertNull("One command must trigger one return", requests.poll())
            }
        } finally { service.detach() }
    }

    @Test fun bothAddressFamiliesReportOwnerAndOldCleanupPreservesNewListener() {
        val service = Robolectric.buildService(CarPlayVpnService::class.java).create().get()
        val received = LinkedBlockingQueue<AirPlayTcpAccepted>()
        val address = InetAddress.getByName("127.0.0.1")
        val addresses = listOf(address, InetAddress.getByName("::1"))
        val config = AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480), port = 0)
        fun attach(owner: AirPlayListenerIdentity) {
            assertEquals(CarPlayVpnService.AttachResult.Started, service.attachWireless(address, config,
                AirPlayIdentity(ByteArray(32), ByteArray(32), "test"), PairingStore(), null,
                object : AirPlaySessionListener {
                    override fun onTcpAccepted(event: AirPlayTcpAccepted) { received.add(event) }
                }, object : AirPlayMediaHandler {},
                additionalBindAddresses = addresses.drop(1), listenerIdentity = owner))
        }
        try {
            val old = AirPlayListenerIdentity(1)
            attach(old)
            addresses.forEach { peer ->
                Socket(peer, service.boundPort()!!).use {
                    val event = received.poll(2, TimeUnit.SECONDS)!!
                    assertSame(old, event.listener)
                    assertTrue(event.internal)
                    assertTrue(event.acceptedAtNanos > 0)
                }
            }
            val current = AirPlayListenerIdentity(2)
            attach(current)
            service.detachWireless(old)
            assertTrue(service.isAttached())
            addresses.forEach { peer ->
                Socket(peer, service.boundPort()!!).use {
                    assertSame(current, received.poll(2, TimeUnit.SECONDS)!!.listener)
                }
            }
            service.detachWireless(current)
            assertFalse(service.isAttached())
        } finally { service.detach() }
    }

    @Test fun remotePhoneAddressIsExternal() {
        assertFalse(isInternalAirPlayPeer(InetAddress.getByName("192.0.2.12"), InetAddress.getByName("192.0.2.1")))
    }
}
