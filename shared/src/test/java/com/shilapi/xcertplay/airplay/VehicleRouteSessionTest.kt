package com.shilapi.xcertplay.airplay

import java.net.InetAddress
import java.net.Socket
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VehicleRouteSessionTest {
    private fun handle(session: AirPlaySession, method: String, payload: Map<String, Any?>): RtspMessage.Response =
        AirPlaySession::class.java.getDeclaredMethod("handle", RtspMessage.Request::class.java).apply { isAccessible = true }
            .invoke(session, RtspMessage.Request(method, "rtsp://test", "RTSP/1.0", emptyMap(), BplistCodec.encode(payload))) as RtspMessage.Response

    @Test fun independentStreamIdsReplacementAndSelectiveTeardownPreserveIap() {
        val engine = CarPlayMediaEngine(object : MediaSink {})
        val loopback = InetAddress.getByName("127.0.0.1")
        val session = AirPlaySession(object : Socket() {
            override fun getInetAddress(): InetAddress = loopback
            override fun getLocalAddress(): InetAddress = loopback
            override fun getRemoteSocketAddress(): java.net.SocketAddress = java.net.InetSocketAddress(loopback, 12345)
        }, AirPlayConfig("test", "id", "bt", "1", AirPlayDisplayConfig(800, 480), routeSharing = true),
            AirPlayIdentity.generate(), PairingStore(), null, object : AirPlaySessionListener {}, engine)
        PairVerify::class.java.getDeclaredField("sharedSecret").apply { isAccessible = true }.set(session.pairVerify, ByteArray(32))
        fun setup(uuid: String) = engine.onDataStream(session, mapOf("clientTypeUUID" to uuid, "seed" to 7L))
        try {
            assertNull(setup(VehicleRouteSharing.HIGH_UUID))
            handle(session, "SETUP", mapOf("features" to listOf(VehicleRouteSharing.FEATURE)))
            assertTrue(session.routeSharingNegotiated)
            val actualIap = setup("E9459FD0-BCAD-4C45-820F-1E72447EF2F2")!!
            val high = setup(VehicleRouteSharing.HIGH_UUID)!!
            val normal = setup(VehicleRouteSharing.NORMAL_UUID)!!
            val replacement = setup(VehicleRouteSharing.HIGH_UUID)!!
            assertEquals(1L, actualIap["streamID"])
            assertEquals(4, setOf(actualIap["streamID"], high["streamID"], normal["streamID"], replacement["streamID"]).size)
            handle(session, "TEARDOWN", mapOf("streams" to listOf(mapOf("type" to 130L, "streamID" to high["streamID"]))))
            Socket(loopback, replacement["dataPort"] as Int).use {}
            handle(session, "TEARDOWN", mapOf("streams" to listOf(mapOf("type" to 130L, "streamID" to normal["streamID"]))))
            assertThrows(Exception::class.java) { Socket(loopback, normal["dataPort"] as Int).use {} }
            Socket(loopback, actualIap["dataPort"] as Int).use {}
        } finally { session.close() }
    }
}
