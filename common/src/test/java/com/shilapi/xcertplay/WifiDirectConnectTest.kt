package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.net.wifi.WifiManager
import android.view.View
import android.widget.Button
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.mazda.MzdHomeView
import com.shilapi.xcertplay.mazda.MzdIntegration
import com.shilapi.xcertplay.mazda.MzdToast
import com.shilapi.xcertplay.network.CarHotspotSettings
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
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
import org.robolectric.shadows.ShadowResources
import org.robolectric.shadows.ShadowToast
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w5120dp-h1440dp-mdpi",
    shadows = [WifiDirectConnectTest.HeadUnitResources::class])
class WifiDirectConnectTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private var home: DiPlayActivity? = null

    @Before fun setUp() {
        clearPreferences()
        CarPlayBackgroundSession.clear()
        HeadUnitResources.mazda = true
        context.getSharedPreferences("diplay", Context.MODE_PRIVATE).edit()
            .putBoolean("notification_asked", true).commit()
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.WIFI_P2P)
        context.getSystemService(WifiManager::class.java).isWifiEnabled = false
        ShadowToast.reset()
    }

    @After fun tearDown() {
        home?.finish()
        MzdIntegration.closeWindows()
        AppAppearanceRuntime.resetForTest()
        CarPlayBackgroundSession.clear()
        HeadUnitResources.mazda = false
        clearPreferences()
    }

    private fun clearPreferences() {
        for (name in listOf("diplay", "xcertplay_airplay", "diplay_car_hotspot")) {
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    private fun openHome(): DiPlayActivity {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", "home")).setup().get()
        home = activity
        // The test APK has no private MFi assets. Exercise a driver's enabled Connect button.
        ReflectionHelpers.setField(activity, "setupError", null)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "refreshStatus")
        val decor = activity.window.decorView
        ReflectionHelpers.setField(ReflectionHelpers.getField<Any>(decor, "mAttachInfo"),
            "mWindowVisibility", View.VISIBLE)
        decor.dispatchWindowVisibilityChanged(View.VISIBLE)
        return activity
    }

    private fun connect(activity: DiPlayActivity, wireless: Boolean = true) {
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "connect",
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, wireless))
    }

    private fun choosePhone() {
        DiPlayPreferences.savePhone(context, "00:11:22:33:44:55", "iPhone")
    }

    @Test fun tappingConnectWithWifiOffShowsMazdaToastBeforeChoosingAPhone() {
        val activity = openHome()
        assertTrue(ReflectionHelpers.getField<Button>(activity, "connectButton").performClick())
        val toast = requireNotNull(activity.findViewById<View>(android.R.id.content)
            .findViewWithTag<TextView>(MzdToast.VIEW_TAG))
        assertEquals(activity.getString(R.string.turn_on_wi_fi_in_the_head_unit_s_settings_to_connect),
            toast.text.toString())
        assertNull(shadowOf(activity).nextStartedActivity)
        assertFalse(ReflectionHelpers.getField<Boolean>(activity, "pendingWireless"))
        assertFalse(CarPlayBackgroundSession.hasSession())
        assertEquals(0, ShadowToast.shownToastCount())
    }

    @Test fun turningWifiOnAfterTheHintAllowsTheNextTapToConnect() {
        choosePhone()
        val activity = openHome()
        connect(activity)
        assertNull(shadowOf(activity).nextStartedActivity)
        context.getSystemService(WifiManager::class.java).isWifiEnabled = true
        connect(activity)
        assertEquals(CarPlayHostActivity::class.java.name,
            requireNotNull(shadowOf(activity).nextStartedActivity).component!!.className)
    }

    @Test fun usbConnectionDoesNotRequireWifiEvenWithWifiDirectSelected() {
        val activity = openHome()
        connect(activity, wireless = false)
        assertEquals(CarPlayHostActivity::class.java.name,
            requireNotNull(shadowOf(activity).nextStartedActivity).component!!.className)
        assertNull(activity.findViewById<View>(android.R.id.content).findViewWithTag<View>(MzdToast.VIEW_TAG))
    }

    @Test fun carHotspotAndSameLanAreNotBlockedByTheWifiDirectCheck() {
        choosePhone()
        AirPlayPersistence.saveManualHotspotSsid(context, "Car 5G")
        AirPlayPersistence.saveManualHotspotPassphrase(context, "12345678")
        CarHotspotSettings.setOffReportIgnored(context, true)
        val activity = openHome()
        for (mode in listOf(WirelessHotspotMode.MANUAL, WirelessHotspotMode.EXISTING_WIFI)) {
            AirPlayPersistence.saveWirelessHotspotMode(context, mode)
            connect(activity)
            assertEquals(CarPlayHostActivity::class.java.name,
                requireNotNull(shadowOf(activity).nextStartedActivity).component!!.className)
            assertNull(activity.findViewById<View>(android.R.id.content).findViewWithTag<View>(MzdToast.VIEW_TAG))
        }
    }

    @Test fun otherBuildsUseTheStandardToastAndAlsoStopBeforeConnecting() {
        HeadUnitResources.mazda = false
        choosePhone()
        val activity = openHome()
        connect(activity)
        assertNull(ReflectionHelpers.getField<MzdHomeView?>(activity, "mazdaHomeView"))
        assertEquals(activity.getString(R.string.turn_on_wi_fi_in_the_head_unit_s_settings_to_connect),
            ShadowToast.getTextOfLatestToast())
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Implements(Resources::class)
    class HeadUnitResources : ShadowResources() {
        @RealObject private lateinit var resources: Resources
        @Implementation fun getBoolean(id: Int): Boolean = if (id == R.bool.config_mazda) mazda else
            Shadow.directlyOn(resources, Resources::class.java, "getBoolean",
                ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, id))

        companion object { var mazda = false }
    }
}
