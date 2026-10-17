package com.shilapi.xcertplay.mazda

import android.content.Context
import android.content.Intent
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.AppAppearanceRuntime
import com.shilapi.xcertplay.CarHotspotOffConnectTest
import com.shilapi.xcertplay.CarPlayBackgroundSession
import com.shilapi.xcertplay.DiPlayActivity
import com.shilapi.xcertplay.host.R
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
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.util.ReflectionHelpers

/** Firmware grants must not bypass the upstream setup checks or the driver's hotspot choice. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-w5120dp-h1440dp-mdpi",
    shadows = [MzdConnectionScaleTest.MazdaResources::class, CarHotspotOffConnectTest.HotspotState::class])
class MzdHotspotFlowTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private var home: DiPlayActivity? = null

    @Before fun setUp() {
        clearPreferences()
        CarPlayBackgroundSession.clear()
        CarHotspotOffConnectTest.HotspotState.on = false
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
            "android.permission.NETWORK_SETTINGS", "android.permission.TETHER_PRIVILEGED")
        MzdDefaults.initialize(context)
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.MANUAL)
    }

    @After fun tearDown() {
        home?.finish()
        MzdIntegration.closeWindows()
        AppAppearanceRuntime.resetForTest()
        CarPlayBackgroundSession.clear()
        CarHotspotOffConnectTest.HotspotState.on = false
        clearPreferences()
    }

    private fun clearPreferences() {
        for (name in listOf("diplay", "xcertplay_airplay", "diplay_car_hotspot")) {
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    private fun connect(): DiPlayActivity {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", "home")).setup().get()
        home = activity
        // Exercise the driver's connection checks without requiring MFi assets in the test APK.
        ReflectionHelpers.setField(activity, "setupError", null)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "connect",
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true))
        return activity
    }

    @Test fun missingSavedCredentialsOpenConnectionSetupDespitePlatformGrants() {
        val activity = connect()
        assertTrue(MzdIntegration.enabled(activity))
        assertEquals("connection", ReflectionHelpers.getField<String>(activity, "page"))
        assertTrue(ReflectionHelpers.getField<Boolean>(activity, "pendingCarHotspotSetup"))
        assertEquals("", AirPlayPersistence.loadManualHotspotSsid(context))
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun aSavedHotspotThatIsOffShowsTheUpstreamWarningDespitePlatformGrants() {
        AirPlayPersistence.saveManualHotspotSsid(context, "Car 5G")
        AirPlayPersistence.saveManualHotspotPassphrase(context, "12345678")
        val activity = connect()
        assertFalse(CarHotspotSettings.enabled(context))
        val dialog = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
        assertEquals(activity.getString(R.string.car_hotspot_is_off), shadowOf(dialog).title)
        assertNull(shadowOf(activity).nextStartedActivity)
    }
}
