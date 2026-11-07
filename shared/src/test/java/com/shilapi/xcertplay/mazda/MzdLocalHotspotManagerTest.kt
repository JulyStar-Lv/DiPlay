package com.shilapi.xcertplay.mazda

import com.shilapi.xcertplay.network.WirelessHotspotBackend
import com.shilapi.xcertplay.network.WirelessHotspotInfo
import com.shilapi.xcertplay.network.WirelessHotspotManager
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class MzdLocalHotspotManagerTest {
    private val now = AtomicLong()
    private val lifecycle = MzdLocalHotspotLifecycle(now::get)
    private var requests = 0
    private val diagnostics = mutableListOf<String>()
    private val info = WirelessHotspotInfo("test", "123456789012", Iap2WirelessSecurity.WPA_WPA2,
        36, 5180, null, "ap0", null, "5 GHz", WirelessHotspotBackend.LOCAL_ONLY_HOTSPOT)

    private fun manager(failures: List<Int>, shutdown: () -> Unit = {
        lifecycle.stateChanged(11)
        now.addAndGet(1_000)
    }) = MzdLocalHotspotManager(lifecycle, diagnostics::add) { lease ->
        object : WirelessHotspotManager {
            override fun start(timeoutMillis: Long): WirelessHotspotInfo {
                lifecycle.begin(lease)
                val failure = failures.getOrNull(requests++)
                lifecycle.stateChanged(13)
                lifecycle.resolved(lease, failure)
                if (failure != null) throw IOException("test startup failure $failure")
                return info
            }
            override fun close() { shutdown() }
        }
    }

    @Test fun genericFailureRetriesAfterShutdownAndReturnsTheNewHotspot() {
        val manager = manager(listOf(2))
        try {
            assertSame(info, manager.start(15_000))
            assertEquals(2, requests)
            assertEquals(1, diagnostics.size)
        } finally { manager.close() }
    }

    @Test fun repeatedGenericFailureStopsAfterOneRetry() {
        val manager = manager(listOf(2, 2))
        try {
            assertThrows(IOException::class.java) { manager.start(15_000) }
            assertEquals(2, requests)
        } finally { manager.close() }
    }

    @Test fun incompatibleModeAndPermissionFailuresAreNotRetried() {
        for (reason in listOf(3, -1)) {
            requests = 0
            val manager = manager(listOf(reason))
            try {
                assertThrows(IOException::class.java) { manager.start(15_000) }
                assertEquals(1, requests)
            } finally { manager.close() }
        }
    }

    @Test fun closeWhileWaitingForShutdownPreventsTheRetry() {
        val closing = CountDownLatch(1)
        val manager = manager(listOf(2)) { closing.countDown() }
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit<Boolean> { runCatching { manager.start(15_000) }.isFailure }
            assertTrue(closing.await(2, TimeUnit.SECONDS))
            manager.close()
            assertTrue(result.get(2, TimeUnit.SECONDS))
            assertEquals(1, requests)
        } finally { manager.close(); worker.shutdownNow() }
    }

    @Test fun replacementManagerWaitsForThePreviousManagersShutdown() {
        val firstClosed = CountDownLatch(1)
        val first = manager(emptyList()) { firstClosed.countDown() }
        val next = manager(emptyList())
        val worker = Executors.newSingleThreadExecutor()
        try {
            first.start(15_000)
            first.close()
            assertTrue(firstClosed.await(2, TimeUnit.SECONDS))
            val started = worker.submit<WirelessHotspotInfo> { next.start(15_000) }
            assertThrows(java.util.concurrent.TimeoutException::class.java) { started.get(100, TimeUnit.MILLISECONDS) }
            assertEquals(1, requests)
            lifecycle.stateChanged(11)
            now.addAndGet(1_000)
            assertSame(info, started.get(2, TimeUnit.SECONDS))
            assertEquals(2, requests)
        } finally { first.close(); next.close(); worker.shutdownNow() }
    }
}
