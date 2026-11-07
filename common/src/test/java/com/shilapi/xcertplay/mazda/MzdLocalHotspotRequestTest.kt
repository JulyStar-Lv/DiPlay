package com.shilapi.xcertplay.mazda

import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiManager
import android.os.Looper
import com.shilapi.xcertplay.LocalOnlyHotspotManagerTest
import com.shilapi.xcertplay.host.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [MzdConnectionFlowTest.MazdaResources::class, LocalOnlyHotspotManagerTest.Radio::class,
    LocalOnlyHotspotManagerTest.Reservation::class])
class MzdLocalHotspotRequestTest {
    @Test fun requestsAppleDeviceIeWithFixedCredentialsAndFiveGhzAndClosesLateReservation() {
        val app = RuntimeEnvironment.getApplication()
        val radio = shadowOf(app.getSystemService(WifiManager::class.java)) as LocalOnlyHotspotManagerTest.Radio
        var failures = 0
        val manager = MzdWirelessConnection.hotspotFactory(app) { failures++ }!!.invoke(app) {}
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit<Boolean> { runCatching { manager.start(5000) }.isFailure }
            assertTrue(radio.requested.await(2, TimeUnit.SECONDS))
            manager.close()
            assertTrue(result.get(2, TimeUnit.SECONDS))
            assertEquals(0, failures)
            assertEquals(1, radio.customRequests)
            assertEquals(0, radio.standardRequests)
            val config = radio.configuration!!
            assertEquals(SoftApConfiguration.BAND_5GHZ, config.javaClass.getMethod("getBand").invoke(config))
            assertEquals(0, config.javaClass.getMethod("getChannel").invoke(config))
            val expected = MzdLocalHotspot.credentials(app, app.getString(R.string.app_name))
            assertEquals(expected.ssid, config.ssid)
            assertEquals(expected.password, config.passphrase)
            assertEquals(SoftApConfiguration.SECURITY_TYPE_WPA2_PSK, config.securityType)
            val elements = config.javaClass.getMethod("getVendorElements").invoke(config) as List<*>
            val element = elements.single()!!
            assertEquals(221, element.javaClass.getField("id").getInt(element))
            assertEquals(0, element.javaClass.getField("idExt").getInt(element))
            assertArrayEquals(byteArrayOf(0, 0xa0.toByte(), 0x40, 0, 0, 2, 0, 0x21),
                element.javaClass.getField("bytes").get(element) as ByteArray)
            val late = Shadow.newInstanceOf(WifiManager.LocalOnlyHotspotReservation::class.java)
            radio.callback.onStarted(late)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, Shadow.extract<LocalOnlyHotspotManagerTest.Reservation>(late).closes)
        } finally { manager.close(); worker.shutdownNow() }
    }

    @Test fun customConfigurationDenialDoesNotSilentlyCreateARandomOrTwoPointFourGhzHotspot() {
        val app = RuntimeEnvironment.getApplication()
        val radio = shadowOf(app.getSystemService(WifiManager::class.java)) as LocalOnlyHotspotManagerTest.Radio
        radio.denyCustom = true
        var failures = 0
        val manager = MzdWirelessConnection.hotspotFactory(app) { failures++ }!!.invoke(app) {}
        val worker = Executors.newSingleThreadExecutor()
        try {
            assertTrue(worker.submit<Boolean> { runCatching { manager.start(1000) }.isFailure }.get(2, TimeUnit.SECONDS))
            assertEquals(1, radio.customRequests)
            assertEquals(0, radio.standardRequests)
            assertEquals(1, failures)
        } finally { manager.close(); worker.shutdownNow() }
    }

    @Test @Config(shadows = [UnsupportedVendorElements::class])
    fun unsupportedVendorElementApiStillRequestsTheFixedFiveGhzHotspot() {
        val app = RuntimeEnvironment.getApplication()
        val radio = shadowOf(app.getSystemService(WifiManager::class.java)) as LocalOnlyHotspotManagerTest.Radio
        val diagnostics = mutableListOf<String>()
        val manager = MzdLocalHotspot.manager(app, "DiPlay", diagnostics::add)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit<Boolean> { runCatching { manager.start(5000) }.isFailure }
            assertTrue(radio.requested.await(2, TimeUnit.SECONDS))
            manager.close()
            assertTrue(result.get(2, TimeUnit.SECONDS))
            val config = radio.configuration!!
            assertEquals(MzdLocalHotspot.credentials(app, "DiPlay").ssid, config.ssid)
            assertEquals(SoftApConfiguration.BAND_5GHZ, config.javaClass.getMethod("getBand").invoke(config))
            assertEquals(emptyList<Any>(), config.javaClass.getMethod("getVendorElements").invoke(config))
            assertTrue(diagnostics.any { it.contains("Apple Device IE unavailable") })
        } finally { manager.close(); worker.shutdownNow() }
    }

    @Implements(SoftApConfiguration.Builder::class)
    class UnsupportedVendorElements {
        @Implementation fun setVendorElements(elements: List<Any>): SoftApConfiguration.Builder {
            throw UnsupportedOperationException("test firmware has no vendor element support")
        }
    }
}
