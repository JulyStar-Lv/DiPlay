package com.shilapi.xcertplay.mazda

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.res.Resources
import android.net.wifi.WifiManager
import android.os.Looper
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.DiPlayActivity
import com.shilapi.xcertplay.DiPlayPreferences
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowResources
import org.robolectric.shadows.ShadowWifiManager
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-w1000dp-h700dp-land",
    shadows = [MzdConnectionFlowTest.MazdaResources::class, MzdConnectionFlowTest.Radio::class])
class MzdConnectionFlowTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var activity: DiPlayActivity
    private lateinit var flow: MzdConnectionFlow
    private var retries = 0
    private var edits = 0
    private var phoneChoices = 0
    private val adapter get() = app.getSystemService(BluetoothManager::class.java).adapter!!
    private val wifi get() = app.getSystemService(WifiManager::class.java)

    @Before fun setUp() {
        MazdaResources.enabled = true
        Radio.apEnabled = true
        for (name in listOf("diplay", "xcertplay_airplay", "mazda_local_hotspot"))
            app.getSharedPreferences(name, 0).edit().clear().commit()
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.NEARBY_WIFI_DEVICES)
        shadowOf(adapter).setEnabled(true)
        val phone = adapter.getRemoteDevice("12:34:56:78:9A:BC")
        shadowOf(adapter).setBondedDevices(setOf(phone))
        DiPlayPreferences.savePhone(app, phone.address, "iPhone")
        @Suppress("DEPRECATION")
        wifi.isWifiEnabled = false
        AirPlayPersistence.saveWirelessHotspotMode(app, WirelessHotspotMode.MANUAL)
        activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(R.style.Theme_Xcertplay)
        flow = MzdConnectionFlow(activity, { retries++ }, { phoneChoices++ }, { edits++ }, { AlertDialog.Builder(activity) })
    }

    @After fun tearDown() { flow.cancel(); activity.finish(); MazdaResources.enabled = true }

    @Test fun noManualDetailsAllowsAutomaticHotspotWithoutOpeningAForm() {
        assertTrue(flow.prepare())
        assertEquals(0, edits)
        assertEquals(WirelessHotspotMode.LOCAL_ONLY_HOTSPOT, MzdWirelessConnection.runtimeMode(app, WirelessHotspotMode.MANUAL))
    }

    @Test fun onlyMazdaLocalHotspotGetsAdditionalTimeForIphoneDiscovery() {
        for (mode in WirelessHotspotMode.entries) {
            assertEquals(if (mode == WirelessHotspotMode.LOCAL_ONLY_HOTSPOT) 45_000L else 30_000L,
                MzdWirelessConnection.firstTcpTimeoutMillis(app, mode))
        }
        MazdaResources.enabled = false
        assertEquals(30_000L, MzdWirelessConnection.firstTcpTimeoutMillis(app, WirelessHotspotMode.LOCAL_ONLY_HOTSPOT))
    }

    @Test fun savedCarHotspotChoiceUsesTheEffectiveAutomaticModeForItsTcpBudget() {
        val saved = AirPlayPersistence.loadWirelessHotspotMode(app)
        assertEquals(WirelessHotspotMode.MANUAL, saved)
        assertEquals(45_000L, MzdWirelessConnection.firstTcpTimeoutMillis(app,
            MzdWirelessConnection.runtimeMode(app, saved)))
        saveManual()
        assertEquals(30_000L, MzdWirelessConnection.firstTcpTimeoutMillis(app,
            MzdWirelessConnection.runtimeMode(app, saved)))
    }

    @Test fun savedManualDetailsTakePriorityAndDoNotGenerateAutomaticCredentials() {
        saveManual()
        assertTrue(flow.prepare())
        for (mode in listOf(WirelessHotspotMode.MANUAL, WirelessHotspotMode.WIFI_P2P, WirelessHotspotMode.LOCAL_ONLY_HOTSPOT)) {
            assertEquals(WirelessHotspotMode.MANUAL, MzdWirelessConnection.runtimeMode(app, mode))
        }
        assertTrue(app.getSharedPreferences("mazda_local_hotspot", 0).all.isEmpty())
    }

    @Test fun partialManualConfigurationOpensOnlyOneExistingFormAndDoesNotFallBackToAutomatic() {
        AirPlayPersistence.saveManualHotspotPassphrase(app, "12345678")
        repeat(3) { assertFalse(flow.prepare()) }
        assertEquals(1, edits)
        assertFalse(MzdWirelessConnection.automatic(app, WirelessHotspotMode.MANUAL))
    }

    @Test fun savedManualHotspotMustBeEnabledAndReturnFromOemResumesOnlyAfterItIsOn() {
        saveManual()
        Radio.apEnabled = false
        assertFalse(flow.prepare())
        assertEquals(0, edits)
        click(AlertDialog.BUTTON_POSITIVE)
        assertOem("INTENT_TO_NETWORK")
        flow.onPause()
        flow.onResume()
        assertEquals(0, retries)
        assertFalse(flow.prepare())
        click(AlertDialog.BUTTON_POSITIVE)
        assertOem("INTENT_TO_NETWORK")
        flow.onPause()
        Radio.apEnabled = true
        flow.onResume()
        assertEquals(1, retries)
        assertTrue(flow.prepare())
    }

    @Test fun unreadableHotspotStateRetainsUpstreamCompatibility() {
        saveManual()
        Radio.apEnabled = null
        assertTrue(flow.prepare())
    }

    @Test fun localFailureOpensOemThenExistingFormAndSavedDetailsStillRequireHotspotOn() {
        activity.intent = Intent().putExtra(MzdWirelessConnection.MANUAL_FALLBACK, true)
        flow.onResume()
        click(AlertDialog.BUTTON_POSITIVE)
        assertOem("INTENT_TO_NETWORK")
        flow.onPause(); flow.onResume()
        assertEquals(1, edits)
        saveManual()
        flow.manualDetailsSaved()
        Radio.apEnabled = false
        assertFalse(flow.prepare())
        assertEquals(1, edits)
        assertEquals(WirelessHotspotMode.MANUAL, MzdWirelessConnection.runtimeMode(app, WirelessHotspotMode.MANUAL))
        assertTrue(ShadowAlertDialog.getLatestAlertDialog().isShowing)
    }

    @Test fun wifiReminderReturnsAfterEachNewConnectionAndDoesNotDisableWifi() {
        @Suppress("DEPRECATION")
        wifi.isWifiEnabled = true
        assertFalse(flow.prepare())
        click(AlertDialog.BUTTON_NEUTRAL)
        assertEquals(1, retries)
        assertTrue(flow.prepare())
        assertTrue(wifi.isWifiEnabled)
        flow.projectionOpened(); flow.onResume()
        assertFalse(flow.prepare())
        assertTrue(ShadowAlertDialog.getLatestAlertDialog().isShowing)
    }

    @Test fun turningOffWifiInOemAutomaticallyResumesThePendingConnection() {
        @Suppress("DEPRECATION")
        wifi.isWifiEnabled = true
        assertFalse(flow.prepare())
        click(AlertDialog.BUTTON_POSITIVE)
        assertOem("INTENT_TO_NETWORK")
        flow.onPause()
        @Suppress("DEPRECATION")
        wifi.isWifiEnabled = false
        flow.onResume()
        assertEquals(1, retries)
        assertTrue(flow.prepare())
    }

    @Test fun existingWifiSkipsCarHotspotAndWifiOffChecks() {
        AirPlayPersistence.saveWirelessHotspotMode(app, WirelessHotspotMode.EXISTING_WIFI)
        @Suppress("DEPRECATION")
        wifi.isWifiEnabled = true
        Radio.apEnabled = false
        assertTrue(flow.prepare())
        assertNull(MzdWirelessConnection.wifiWarning(app, WirelessHotspotMode.EXISTING_WIFI))
        assertEquals(WirelessHotspotMode.EXISTING_WIFI, MzdWirelessConnection.runtimeMode(app, WirelessHotspotMode.EXISTING_WIFI))
    }

    @Test fun bluetoothSettingsUseOemStringArrayAndDoNotConnectWhenOpenedOutsideAnAttempt() {
        assertTrue(flow.openBluetoothSettings())
        assertOem("INTENT_TO_BLUETOOTH")
        flow.onPause(); flow.onResume()
        assertEquals(0, retries)
    }

    @Test fun bluetoothReturnRechecksTheSwitchAndContinuesThePendingAttempt() {
        shadowOf(adapter).setEnabled(false)
        assertFalse(flow.prepare())
        click(AlertDialog.BUTTON_POSITIVE)
        assertOem("INTENT_TO_BLUETOOTH")
        flow.onPause()
        shadowOf(adapter).setEnabled(true)
        flow.onResume()
        assertEquals(1, retries)
    }

    @Test fun duplicateClicksDoNotOpenMorePhoneChoosers() {
        shadowOf(adapter).setBondedDevices(emptySet())
        repeat(3) { assertFalse(flow.prepare()) }
        assertEquals(1, phoneChoices)
    }

    @Test fun pairingFromThePhoneChooserReturnsToTheChooserInsteadOfGettingStuck() {
        shadowOf(adapter).setBondedDevices(emptySet())
        assertFalse(flow.prepare())
        assertEquals(1, phoneChoices)
        assertTrue(flow.openBluetoothSettings())
        assertOem("INTENT_TO_BLUETOOTH")
        flow.onPause(); flow.onResume()
        assertEquals(2, phoneChoices)
        assertEquals(0, retries)
    }

    @Test fun automaticCredentialStorageDoesNotOverwriteManualDetailsAndSurvivesAppRename() {
        saveManual()
        val generated = MzdLocalHotspot.credentials(app, "DiPlay")
        assertEquals(generated, MzdLocalHotspot.credentials(app, "Another name"))
        assertEquals("Car hotspot", AirPlayPersistence.loadManualHotspotSsid(app))
        assertEquals("12345678", AirPlayPersistence.loadManualHotspotPassphrase(app))
    }

    @Test fun otherBuildsKeepTheirModeAndDoNotGetAMazdaFactory() {
        MazdaResources.enabled = false
        assertEquals(WirelessHotspotMode.MANUAL, MzdWirelessConnection.runtimeMode(app, WirelessHotspotMode.MANUAL))
        assertNull(MzdWirelessConnection.hotspotFactory(app) { fail("not Mazda") })
    }

    @Test fun onlyWirelessLocalHotspotModeKeepsTheAutomaticFactory() {
        for (mode in WirelessHotspotMode.entries) {
            val factory = MzdWirelessConnection.hotspotFactory(app, mode) { fail("not starting a hotspot") }
            assertEquals(mode == WirelessHotspotMode.LOCAL_ONLY_HOTSPOT, factory != null)
        }
        assertNull(MzdWirelessConnection.hotspotFactory(app, WirelessHotspotMode.LOCAL_ONLY_HOTSPOT,
            CarPlayTransport.WIRED) { fail("wired mode") })
    }

    private fun saveManual() {
        AirPlayPersistence.saveManualHotspotSsid(app, "Car hotspot")
        AirPlayPersistence.saveManualHotspotPassphrase(app, "12345678")
    }
    private fun click(button: Int) {
        ShadowAlertDialog.getLatestAlertDialog().getButton(button).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun assertOem(target: String) {
        val intent = shadowOf(activity).nextStartedActivity
        assertEquals("com.wt.vehiclecenter.activities.VehicleCenterActivity", intent.component!!.className)
        assertArrayEquals(arrayOf(target, activity.packageName), intent.getStringArrayExtra("INTENT_TO_VEHICLE_CENTER"))
        assertEquals(activity.packageName, intent.getStringExtra("start_by_app"))
    }

    @Implements(WifiManager::class)
    class Radio : ShadowWifiManager() {
        @Implementation fun getWifiApState(): Int = apEnabled?.let { if (it) 13 else 11 } ?: 0
        @Implementation fun isWifiApEnabled(): Boolean = apEnabled ?: throw UnsupportedOperationException()
        companion object { var apEnabled: Boolean? = true }
    }
    @Implements(Resources::class)
    class MazdaResources : ShadowResources() {
        @RealObject private lateinit var resources: Resources
        @Implementation fun getBoolean(id: Int): Boolean = if (id == R.bool.config_mazda) enabled else
            Shadow.directlyOn(resources, Resources::class.java, "getBoolean",
                ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, id))
        companion object { var enabled = true }
    }
}
