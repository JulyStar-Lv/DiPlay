package com.shilapi.xcertplay.network.mazda

import com.shilapi.xcertplay.network.*

import android.net.wifi.SoftApConfiguration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 34], manifest = Config.NONE)
class MzdCarHotspotConfigurationTest {
    @Test fun bothPlatformHotspotPermissionsMustActuallyBeGranted() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val shadow = org.robolectric.Shadows.shadowOf(app)
        shadow.denyPermissions("android.permission.NETWORK_SETTINGS", "android.permission.TETHER_PRIVILEGED")
        org.junit.Assert.assertFalse(MzdCarHotspot.permitted(app))
        shadow.grantPermissions("android.permission.NETWORK_SETTINGS")
        org.junit.Assert.assertFalse(MzdCarHotspot.permitted(app))
        shadow.grantPermissions("android.permission.TETHER_PRIVILEGED")
        org.junit.Assert.assertTrue(MzdCarHotspot.permitted(app))
    }

    @Test fun bothPlatformPermissionsMustActuallyBeGranted() {
        val context = RuntimeEnvironment.getApplication()
        val app = shadowOf(context)
        app.denyPermissions("android.permission.NETWORK_SETTINGS", "android.permission.TETHER_PRIVILEGED")
        assertFalse(MzdCarHotspot.permitted(context))
        app.grantPermissions("android.permission.NETWORK_SETTINGS")
        assertFalse(MzdCarHotspot.permitted(context))
        app.grantPermissions("android.permission.TETHER_PRIVILEGED")
        assertTrue(MzdCarHotspot.permitted(context))
        app.denyPermissions("android.permission.NETWORK_SETTINGS")
        assertFalse(MzdCarHotspot.permitted(context))
    }
    @Test fun changingBandCopiesCredentialsSecurityHiddenSsidAndClientLimits() {
        val type = SoftApConfiguration.Builder::class.java
        val builder = SoftApConfiguration.Builder().setPassphrase("synthetic-password", SoftApConfiguration.SECURITY_TYPE_WPA3_SAE_TRANSITION)
        type.getMethod("setSsid", String::class.java).invoke(builder, "Car SSID")
        type.getMethod("setBand", Int::class.javaPrimitiveType).invoke(builder, 1)
        type.getMethod("setHiddenSsid", Boolean::class.javaPrimitiveType).invoke(builder, true)
        type.getMethod("setMaxNumberOfClients", Int::class.javaPrimitiveType).invoke(builder, 3)
        type.getMethod("setAutoShutdownEnabled", Boolean::class.javaPrimitiveType).invoke(builder, false)
        val original = builder.build()
        val target = MzdCarHotspot.fiveGhzCopy(original)
        assertEquals(MzdCarHotspot.credentials(original), MzdCarHotspot.credentials(target))
        // Changing just the band back must recover the entire original object.
        val restored = type.getConstructor(SoftApConfiguration::class.java).newInstance(target)
        type.getMethod("setBand", Int::class.javaPrimitiveType).invoke(restored, 1)
        assertEquals(original, restored.build())
        assertEquals(2, target.javaClass.getMethod("getBand").invoke(target))
    }

    @Config(sdk = [34])
    @Test fun incompatibleOweSecurityIsNotMisreportedAsWpa2() {
        val builder = SoftApConfiguration.Builder().setPassphrase(null, SoftApConfiguration.SECURITY_TYPE_WPA3_OWE)
        builder.javaClass.getMethod("setSsid", String::class.java).invoke(builder, "Car SSID")
        assertThrows(WirelessStartupException::class.java) { MzdCarHotspot.credentials(builder.build()) }
    }
}
