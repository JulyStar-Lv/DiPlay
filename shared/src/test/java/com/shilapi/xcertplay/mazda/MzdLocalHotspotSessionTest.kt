package com.shilapi.xcertplay.mazda

import com.shilapi.xcertplay.network.WirelessHotspotBackend
import com.shilapi.xcertplay.network.WirelessHotspotInfo
import com.shilapi.xcertplay.network.WirelessHotspotManager
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MzdLocalHotspotSessionTest {
    private val credentials = MzdLocalHotspot.Credentials("DiPlay-5G-1234", "123456789012")
    private val created = mutableListOf<Hotspot>()
    private var interfaceReady = true
    private val session = MzdLocalHotspotSession({
        if (!interfaceReady) throw IOException("test AP interface disappeared")
    }) { key, report -> Hotspot(key, report).also(created::add) }

    private class Hotspot(key: MzdLocalHotspot.Credentials, val report: (String) -> Unit) : WirelessHotspotManager {
        val info = WirelessHotspotInfo(key.ssid, key.password, Iap2WirelessSecurity.WPA_WPA2,
            44, 5220, "02:12:34:56:78:90", "ap0", InetAddress.getByName("192.168.10.1"),
            "5 GHz", WirelessHotspotBackend.LOCAL_ONLY_HOTSPOT)
        var starts = 0
        var closes = 0
        var ready = true
        var confirmations = 0
        override fun start(timeoutMillis: Long): WirelessHotspotInfo { starts++; return info }
        override fun close() { closes++ }
        override fun validateReady() { if (!ready) throw IOException("test reservation stopped") }
        override fun onCarPlayConfirmed() { confirmations++ }
        override fun connectionDiagnosticSnapshot() = "association=1"
    }

    private fun manager(key: MzdLocalHotspot.Credentials = credentials) = session.manager(key) {}

    @Test fun tcpTimeoutAndReplacementControllerReuseTheReservationAndInterface() {
        val first = manager()
        val info = first.start(15_000)
        first.close()
        val next = manager()
        try {
            assertSame(info, next.start(15_000))
            next.validateReady()
            next.onCarPlayConfirmed()
            assertEquals("association=1", next.connectionDiagnosticSnapshot())
            assertEquals(1, created.size)
            assertEquals(1, created.single().starts)
            assertEquals(0, created.single().closes)
            assertEquals(1, created.single().confirmations)
        } finally { next.close(); session.close() }
        assertEquals(1, created.single().closes)
    }

    @Test fun changedCredentialsReplaceTheHotspotAndLateOldCloseCannotCloseTheReplacement() {
        val first = manager()
        first.start(15_000)
        val next = manager(credentials.copy(password = "987654321098"))
        try {
            assertEquals("987654321098", next.start(15_000).passphrase)
            first.close()
            next.validateReady()
            assertEquals(2, created.size)
            assertEquals(1, created.first().closes)
            assertEquals(0, created.last().closes)
        } finally { first.close(); next.close(); session.close() }
    }

    @Test fun stoppedReservationIsRecreatedEvenWhenCredentialsStillMatch() {
        val first = manager()
        first.start(15_000)
        first.close()
        created.single().ready = false
        val next = manager()
        try {
            next.start(15_000)
            assertEquals(2, created.size)
            assertEquals(1, created.first().closes)
        } finally { next.close(); session.close() }
    }

    @Test fun changedInterfacePreventsReusingCachedHotspotDetails() {
        val first = manager()
        first.start(15_000)
        first.close()
        interfaceReady = false
        val next = manager()
        try {
            next.start(15_000)
            assertEquals(2, created.size)
            assertEquals(1, created.first().closes)
        } finally { next.close(); session.close() }
    }

    @Test fun disconnectOrModeChangeReleasesTheReadyHotspotEvenAfterItsHandleClosed() {
        val first = manager()
        first.start(15_000)
        first.close()
        session.close()
        session.close()
        assertEquals(1, created.single().closes)
        val next = manager()
        try {
            next.start(15_000)
            first.close()
            assertEquals(2, created.size)
            assertEquals(0, created.last().closes)
        } finally { next.close(); session.close() }
    }

    @Test fun diagnosticsFollowTheCurrentControllerWithoutRetainingThePreviousCallback() {
        val oldLog = mutableListOf<String>()
        val newLog = mutableListOf<String>()
        val first = session.manager(credentials, oldLog::add)
        first.start(15_000)
        created.single().report("first")
        first.close()
        created.single().report("between attempts")
        val next = session.manager(credentials, newLog::add)
        try {
            next.start(15_000)
            first.close()
            created.single().report("next")
            assertEquals(listOf("first"), oldLog)
            assertTrue(newLog.any { it.contains("reusing ready") })
            assertEquals("next", newLog.last())
        } finally { next.close(); session.close() }
    }

    @Test fun failedStartIsClosedAndNeverCached() {
        var closes = 0
        var starts = 0
        val failing = MzdLocalHotspotSession({}) { _, _ ->
            object : WirelessHotspotManager {
                override fun start(timeoutMillis: Long): WirelessHotspotInfo { starts++; throw IOException("test failure") }
                override fun close() { closes++ }
            }
        }
        repeat(2) {
            val handle = failing.manager(credentials) {}
            assertThrows(IOException::class.java) { handle.start(15_000) }
            handle.close()
        }
        failing.close()
        assertEquals(2, starts)
        assertEquals(2, closes)
    }

    @Test fun incorrectReturnedCredentialsAreClosedInsteadOfReused() {
        var closes = 0
        val wrong = MzdLocalHotspotSession({}) { _, _ ->
            object : WirelessHotspotManager {
                override fun start(timeoutMillis: Long) = Hotspot(credentials.copy(ssid = "wrong")) {}.info
                override fun close() { closes++ }
            }
        }
        val handle = wrong.manager(credentials) {}
        assertThrows(IOException::class.java) { handle.start(15_000) }
        handle.close(); wrong.close()
        assertEquals(1, closes)
    }

    @Test fun cancellingAnIncompleteStartClosesItAndRejectsItsLateResult() {
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        var closes = 0
        val pending = MzdLocalHotspotSession({}) { _, _ ->
            object : WirelessHotspotManager {
                override fun start(timeoutMillis: Long): WirelessHotspotInfo {
                    entered.countDown()
                    assertTrue(finish.await(2, TimeUnit.SECONDS))
                    return Hotspot(credentials) {}.info
                }
                override fun close() { closes++ }
            }
        }
        val handle = pending.manager(credentials) {}
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit<Boolean> { runCatching { handle.start(15_000) }.isFailure }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            handle.close()
            assertEquals(1, closes)
            finish.countDown()
            assertTrue(result.get(2, TimeUnit.SECONDS))
        } finally { finish.countDown(); handle.close(); pending.close(); worker.shutdownNow() }
    }
}
