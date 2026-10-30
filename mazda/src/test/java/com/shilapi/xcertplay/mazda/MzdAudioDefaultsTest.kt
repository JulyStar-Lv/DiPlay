package com.shilapi.xcertplay.mazda

import android.content.Context
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.VehicleDisplayProvider
import com.shilapi.display.api.VehicleDisplayFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Exercise the real Mazda manifest/Application, including merged application resources. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MzdAudioDefaultsTest {
    private val application get() = RuntimeEnvironment.getApplication()

    @Test fun defaultBuildHasNoActiveHudDriver() {
        val provider = application as VehicleDisplayProvider
        assertTrue(provider.vehicleDisplayFactory === VehicleDisplayFactory.NONE)
    }

    @Test fun mazdaApplicationStartsWithUnbufferedProtocolAndOneSecondWirelessBuffer() {
        assertTrue(application is MzdApplication)
        val preferences = application.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
        assertFalse(preferences.contains("wireless_hotspot_mode"))
        assertFalse(preferences.contains("main_buffered_audio"))
        assertEquals(WirelessHotspotMode.MANUAL, AirPlayPersistence.loadWirelessHotspotMode(application))
        assertFalse(AirPlayPersistence.loadMainBufferedAudio(application))
        assertTrue(AirPlayPersistence.loadDisableMediaKeyAudioFocus(application))
        assertEquals(1000, AirPlayPersistence.loadMediaBufferMillis(application, wireless = true))
        assertEquals(300, AirPlayPersistence.loadMediaBufferMillis(application, wireless = false))
    }

    @Test fun subsequentApplicationStartupRetainsUserAudioChoices() {
        AirPlayPersistence.saveMainBufferedAudio(application, true)
        AirPlayPersistence.saveDisableMediaKeyAudioFocus(application, false)
        AirPlayPersistence.saveMediaBufferMillis(application, wireless = true, millis = 500)
        AirPlayPersistence.saveMediaBufferMillis(application, wireless = false, millis = 50)

        application.onCreate()

        assertTrue(AirPlayPersistence.loadMainBufferedAudio(application))
        assertFalse(AirPlayPersistence.loadDisableMediaKeyAudioFocus(application))
        assertEquals(500, AirPlayPersistence.loadMediaBufferMillis(application, wireless = true))
        assertEquals(50, AirPlayPersistence.loadMediaBufferMillis(application, wireless = false))
    }

    @Test fun oldApplicationWithMissingWirelessSettingKeepsItsWiredSetting() {
        application.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit()
            .remove("media_buffer_ms").remove("main_buffered_audio").remove("disable_media_key_audio_focus")
            .putInt("wired_media_buffer_ms", 100).commit()

        application.onCreate()

        assertFalse(AirPlayPersistence.loadMainBufferedAudio(application))
        assertEquals(1000, AirPlayPersistence.loadMediaBufferMillis(application, wireless = true))
        assertEquals(100, AirPlayPersistence.loadMediaBufferMillis(application, wireless = false))
        assertTrue(AirPlayPersistence.loadDisableMediaKeyAudioFocus(application))
        assertFalse(application.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
            .contains("main_buffered_audio"))
    }
}
