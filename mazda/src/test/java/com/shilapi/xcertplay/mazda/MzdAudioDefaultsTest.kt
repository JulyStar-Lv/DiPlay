package com.shilapi.xcertplay.mazda

import android.content.Context
import android.content.pm.PackageManager
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.CarPlayHostActivity
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.VehicleDisplayProvider
import com.shilapi.display.api.VehicleDisplayFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** Exercise the real Mazda manifest/Application, including merged application resources. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MzdAudioDefaultsTest {
    private val application get() = RuntimeEnvironment.getApplication()

    @Test fun defaultMazdaBuildEnablesHudWithoutRequestingAnAuxiliaryMap() {
        @Suppress("DEPRECATION")
        val metadata = application.packageManager.getApplicationInfo(application.packageName,
            PackageManager.GET_META_DATA).metaData
        assertTrue(metadata.getBoolean("com.shilapi.vehicle.mzd.HUD_ENABLED"))
        assertTrue(metadata.getBoolean("com.shilapi.vehicle.mzd.HUD_AUTOMATIC_A2DP_HANDOVER"))
        assertEquals(20, metadata.getInt("com.shilapi.vehicle.mzd.HUD_MEDIA_SOURCE_TYPE"))
        val provider = application as VehicleDisplayProvider
        assertTrue(provider.vehicleDisplayFactory !== VehicleDisplayFactory.NONE)
        AirPlayPersistence.saveClusterMapEnabled(application, true)
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        assertNull(ReflectionHelpers.callInstanceMethod<Any?>(host, "clusterDisplayConfig"))
    }

    @Test fun mazdaApplicationStartsWithBufferedProtocolAndSharedWirelessBufferDefault() {
        assertTrue(application is MzdApplication)
        val preferences = application.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
        assertFalse(preferences.contains("wireless_hotspot_mode"))
        assertTrue(preferences.contains("main_buffered_audio"))
        assertFalse(preferences.contains("media_buffer_ms"))
        assertFalse(preferences.contains("wired_media_buffer_ms"))
        assertEquals(WirelessHotspotMode.MANUAL, AirPlayPersistence.loadWirelessHotspotMode(application))
        assertTrue(AirPlayPersistence.loadMainBufferedAudio(application))
        assertTrue(AirPlayPersistence.loadDisableMediaKeyAudioFocus(application))
        assertEquals(300, AirPlayPersistence.loadMediaBufferMillis(application, wireless = true))
        assertEquals(300, AirPlayPersistence.loadMediaBufferMillis(application, wireless = false))
    }

    @Test fun subsequentApplicationStartupRetainsUserAudioChoices() {
        AirPlayPersistence.saveMainBufferedAudio(application, false)
        AirPlayPersistence.saveDisableMediaKeyAudioFocus(application, false)
        AirPlayPersistence.saveMediaBufferMillis(application, wireless = true, millis = 500)
        AirPlayPersistence.saveMediaBufferMillis(application, wireless = false, millis = 50)

        application.onCreate()

        assertFalse(AirPlayPersistence.loadMainBufferedAudio(application))
        assertFalse(AirPlayPersistence.loadDisableMediaKeyAudioFocus(application))
        assertEquals(500, AirPlayPersistence.loadMediaBufferMillis(application, wireless = true))
        assertEquals(50, AirPlayPersistence.loadMediaBufferMillis(application, wireless = false))
    }

    @Test fun oldApplicationWithMissingWirelessSettingKeepsItsWiredSetting() {
        application.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit()
            .remove("media_buffer_ms").remove("main_buffered_audio").remove("disable_media_key_audio_focus")
            .putInt("wired_media_buffer_ms", 100).commit()

        application.onCreate()

        assertTrue(AirPlayPersistence.loadMainBufferedAudio(application))
        assertEquals(300, AirPlayPersistence.loadMediaBufferMillis(application, wireless = true))
        assertEquals(100, AirPlayPersistence.loadMediaBufferMillis(application, wireless = false))
        assertTrue(AirPlayPersistence.loadDisableMediaKeyAudioFocus(application))
        assertTrue(application.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
            .contains("main_buffered_audio"))
        assertFalse(application.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
            .contains("media_buffer_ms"))
    }
}
