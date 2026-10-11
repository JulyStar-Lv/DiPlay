package com.shilapi.xcertplay.mazda

import android.os.Looper
import com.shilapi.xcertplay.network.WirelessHotspotBackend
import com.shilapi.xcertplay.network.WirelessHotspotInfo
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.InterruptedIOException
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class MzdHotspotConnectionTest {
    private fun hotspot() = WirelessHotspotInfo(
        ssid = "Car network", passphrase = "car-password", security = Iap2WirelessSecurity.WPA_WPA2,
        channel = 0, frequencyMHz = null, bssid = null, interfaceName = "ap0",
        hostAddress = InetAddress.getByName("192.168.43.1"), bandLabel = "5 GHz",
        backend = WirelessHotspotBackend.MANUAL_HOTSPOT,
    )

    private fun finishWorker(worker: Thread) {
        worker.join(5_000)
        assertFalse("Preparation worker did not finish", worker.isAlive)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun waitsForHotspotOffTheUiThreadAndIgnoresDuplicateStartsBeforeBuildingOriginalConfig() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = AtomicReference<Thread>()
        val attempts = AtomicInteger()
        var runtime: CarPlayRuntimeConfig? = null
        val preparation = MzdHotspotConnection(RuntimeEnvironment.getApplication()) { _, _ ->
            attempts.incrementAndGet()
            worker.set(Thread.currentThread())
            assertNotSame(Looper.getMainLooper().thread, Thread.currentThread())
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            hotspot()
        }
        val ready: (WirelessHotspotInfo) -> Unit = { info ->
            assertSame(Looper.getMainLooper().thread, Thread.currentThread())
            runtime = CarPlayRuntimeConfig(
                mfiTarget = MfiTarget.LOCAL,
                identification = Iap2IdentificationConfig("test", "test", "test", "test", "1", "1", 3),
                transport = CarPlayTransport.WIRELESS, wirelessHotspotMode = WirelessHotspotMode.MANUAL,
                manualHotspotSsid = info.ssid, manualHotspotPassphrase = info.passphrase,
                manualHotspotBand = ManualHotspotBand.GHZ_5, manualHotspotChannel = info.channel,
            )
        }
        preparation.start({}, ready) { throw AssertionError(it) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        preparation.start({}, ready) { throw AssertionError(it) }
        assertNull(runtime)
        release.countDown()
        finishWorker(worker.get())
        assertEquals(1, attempts.get())
        assertEquals("Car network", runtime?.manualHotspotSsid)
        assertEquals("car-password", runtime?.manualHotspotPassphrase)
        assertEquals(0, runtime?.manualHotspotChannel)
    }

    @Test fun cancellationReachesTheWorkerAndDoesNotContinueCarPlayOnARetiredHost() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = AtomicReference<Thread>()
        var completions = 0
        val preparation = MzdHotspotConnection(RuntimeEnvironment.getApplication()) { _, cancelled ->
            worker.set(Thread.currentThread())
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            assertTrue(cancelled())
            throw InterruptedIOException("Cancelled")
        }
        preparation.start({}, { completions++ }, { completions++ })
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        preparation.cancel()
        release.countDown()
        finishWorker(worker.get())
        assertEquals(0, completions)
    }

    @Test fun failureIsReportedWithoutContinuingAndAManualRetryCanSucceed() {
        val worker = AtomicReference<Thread>()
        val attempts = AtomicInteger()
        val entered = AtomicReference(CountDownLatch(1))
        var failures = 0
        var ready = 0
        val preparation = MzdHotspotConnection(RuntimeEnvironment.getApplication()) { _, _ ->
            worker.set(Thread.currentThread())
            entered.get().countDown()
            if (attempts.incrementAndGet() == 1) error("Synthetic hotspot startup failure")
            hotspot()
        }
        preparation.start({}, { ready++ }, { failures++ })
        assertTrue(entered.get().await(5, TimeUnit.SECONDS))
        finishWorker(worker.get())
        assertEquals(1, failures)
        assertEquals(0, ready)

        // Capture the second worker before joining it; the first has already finished.
        worker.set(null)
        entered.set(CountDownLatch(1))
        preparation.start({}, { ready++ }, { failures++ })
        assertTrue(entered.get().await(5, TimeUnit.SECONDS))
        finishWorker(worker.get())
        assertEquals(2, attempts.get())
        assertEquals(1, failures)
        assertEquals(1, ready)
    }
}
