package com.shilapi.xcertplay.mazda

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.AppAppearance
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.setup.SetupGuide
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class MzdDefaultsTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun clearBefore() = clearPreferences()
    @After fun clearAfter() = clearPreferences()

    private fun clearPreferences() {
        context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("diplay", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun assertMazdaDefaults() {
        val preferences = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
        assertFalse(preferences.contains("wireless_hotspot_mode"))
        assertFalse(preferences.contains("main_buffered_audio"))
        assertEquals(WirelessHotspotMode.MANUAL, AirPlayPersistence.loadWirelessHotspotMode(context))
        assertFalse(AirPlayPersistence.loadHideTopBar(context))
        assertFalse(AirPlayPersistence.loadHideBottomBar(context))
        assertEquals(60, AirPlayPersistence.loadFps(context))
        assertEquals(4, AirPlayPersistence.loadSettingsGestureFingers(context))
        assertEquals(AppAppearance.AUTO, AirPlayPersistence.loadAppAppearance(context))
        assertTrue(AirPlayPersistence.loadDisableMediaKeyAudioFocus(context))
        assertTrue(AirPlayPersistence.loadMtkDecoderTuning(context))
        assertFalse(AirPlayPersistence.loadMainBufferedAudio(context))
        assertEquals(1000, AirPlayPersistence.loadMediaBufferMillis(context, wireless = true))
        assertEquals(300, AirPlayPersistence.loadMediaBufferMillis(context, wireless = false))
        assertEquals(AirPlayPersistence.DEFAULT_OEM_LABEL, AirPlayPersistence.loadOemLabel(context))
        assertTrue(SetupGuide.seen(context))
        assertFalse(SetupGuide.shouldOpenOnLaunch(SetupGuide.seen(context), phoneChosen = false))
    }

    @Test fun freshMazdaInitializationSeedsDefaultsWithoutChangingOriginalFallbacks() {
        assertTrue(AirPlayPersistence.loadHideTopBar(context))
        assertTrue(AirPlayPersistence.loadHideBottomBar(context))
        assertEquals(30, AirPlayPersistence.loadFps(context))
        assertEquals(3, AirPlayPersistence.loadSettingsGestureFingers(context))
        assertEquals(AppAppearance.DARK, AirPlayPersistence.loadAppAppearance(context))
        assertEquals(AirPlayPersistence.DEFAULT_OEM_LABEL, AirPlayPersistence.loadOemLabel(context))
        assertTrue(SetupGuide.shouldOpenOnLaunch(SetupGuide.seen(context), phoneChosen = false))
        assertFalse(AirPlayPersistence.loadMtkDecoderTuning(context))
        assertFalse(AirPlayPersistence.loadDisableMediaKeyAudioFocus(context))
        assertFalse(AirPlayPersistence.loadMainBufferedAudio(context))
        assertEquals(300, AirPlayPersistence.loadMediaBufferMillis(context, wireless = true))
        assertEquals(300, AirPlayPersistence.loadMediaBufferMillis(context, wireless = false))

        MzdDefaults.initialize(context)

        assertMazdaDefaults()
        assertFalse(AirPlayPersistence.loadAdaptPipResolution(context))
    }

    @Test fun replacingAnExistingInstallationPreservesSavedChoicesAndFillsOnlyMissingSettings() {
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.MANUAL)
        AirPlayPersistence.saveHideTopBar(context, true)
        AirPlayPersistence.saveFps(context, 30)
        AirPlayPersistence.saveSettingsGestureFingers(context, 0)
        AirPlayPersistence.saveAppAppearance(context, AppAppearance.LIGHT)
        AirPlayPersistence.saveAdaptPipResolution(context, true)
        AirPlayPersistence.saveOemLabel(context, "My car")
        AirPlayPersistence.saveDisableMediaKeyAudioFocus(context, false)

        MzdDefaults.initialize(context)

        assertTrue(AirPlayPersistence.loadHideTopBar(context))
        assertFalse(AirPlayPersistence.loadHideBottomBar(context))
        assertEquals(WirelessHotspotMode.MANUAL, AirPlayPersistence.loadWirelessHotspotMode(context))
        assertEquals(30, AirPlayPersistence.loadFps(context))
        assertEquals(0, AirPlayPersistence.loadSettingsGestureFingers(context))
        assertEquals(AppAppearance.LIGHT, AirPlayPersistence.loadAppAppearance(context))
        assertTrue(AirPlayPersistence.loadAdaptPipResolution(context))
        assertEquals("My car", AirPlayPersistence.loadOemLabel(context))
        assertFalse(AirPlayPersistence.loadDisableMediaKeyAudioFocus(context))
        assertTrue(SetupGuide.seen(context))
    }

    @Test fun laterLaunchesDoNotResetUserChangesAndClearingDataRestoresInitialization() {
        MzdDefaults.initialize(context)
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.EXISTING_WIFI)
        AirPlayPersistence.saveHideTopBar(context, true)
        AirPlayPersistence.saveHideBottomBar(context, true)
        AirPlayPersistence.saveFps(context, 30)
        AirPlayPersistence.saveSettingsGestureFingers(context, 3)
        AirPlayPersistence.saveAppAppearance(context, AppAppearance.DARK)
        AirPlayPersistence.saveOemLabel(context, "BYD")
        AirPlayPersistence.saveMtkDecoderTuning(context, false)
        AirPlayPersistence.saveDisableMediaKeyAudioFocus(context, false)
        AirPlayPersistence.saveMainBufferedAudio(context, true)
        AirPlayPersistence.saveMediaBufferMillis(context, wireless = true, millis = 500)
        AirPlayPersistence.saveMediaBufferMillis(context, wireless = false, millis = 50)

        MzdDefaults.initialize(context)

        assertTrue(AirPlayPersistence.loadHideTopBar(context))
        assertTrue(AirPlayPersistence.loadHideBottomBar(context))
        assertEquals(30, AirPlayPersistence.loadFps(context))
        assertEquals(3, AirPlayPersistence.loadSettingsGestureFingers(context))
        assertEquals(AppAppearance.DARK, AirPlayPersistence.loadAppAppearance(context))
        assertEquals("BYD", AirPlayPersistence.loadOemLabel(context))
        assertFalse(AirPlayPersistence.loadMtkDecoderTuning(context))
        assertTrue(AirPlayPersistence.loadMainBufferedAudio(context))
        assertFalse(AirPlayPersistence.loadDisableMediaKeyAudioFocus(context))
        assertEquals(500, AirPlayPersistence.loadMediaBufferMillis(context, wireless = true))
        assertEquals(50, AirPlayPersistence.loadMediaBufferMillis(context, wireless = false))

        assertEquals(WirelessHotspotMode.EXISTING_WIFI, AirPlayPersistence.loadWirelessHotspotMode(context))

        clearPreferences()
        MzdDefaults.initialize(context)
        assertMazdaDefaults()
    }

    @Test fun existingMazdaInstallationsKeepTheirOemLabelAndOtherSavedChoices() {
        for (label in listOf(null, "BYD", "")) {
            clearPreferences()
            context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit()
                .putBoolean("mazda_defaults_initialized", true).commit()
            if (label != null) AirPlayPersistence.saveOemLabel(context, label)
            AirPlayPersistence.saveFps(context, 30)
            AirPlayPersistence.saveAppAppearance(context, AppAppearance.LIGHT)

            MzdDefaults.initialize(context)

            assertEquals(label?.takeIf { it.isNotBlank() } ?: AirPlayPersistence.DEFAULT_OEM_LABEL,
                AirPlayPersistence.loadOemLabel(context))
            assertEquals(30, AirPlayPersistence.loadFps(context))
            assertEquals(AppAppearance.LIGHT, AirPlayPersistence.loadAppAppearance(context))
        }
    }

    @Test fun oemLabelUsesTheBuildDefaultWithoutReplacingSavedNames() {
        assertEquals("BYD", AirPlayPersistence.loadOemLabel(context))
        val mazdaResources = mock(Resources::class.java)
        `when`(mazdaResources.getString(R.string.config_oem_label)).thenReturn("Mazda")
        val mazdaContext = object : ContextWrapper(context) {
            override fun getResources(): Resources = mazdaResources
        }
        for (label in listOf(null, "", " ", "BYD", "My car")) {
            clearPreferences()
            if (label != null) AirPlayPersistence.saveOemLabel(context, label)
            assertEquals(label?.takeIf { it.isNotBlank() } ?: "Mazda", AirPlayPersistence.loadOemLabel(mazdaContext))
        }
    }

    @Test fun previouslyInitializedMazdaGetsMissingDefaultsAndKeepsExplicitChoices() {
        for (mtk in listOf(null, false, true)) for (bufferedMusic in listOf(null, false, true)) {
            clearPreferences()
            context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit()
                .putBoolean("mazda_defaults_initialized", true).commit()
            if (mtk != null) AirPlayPersistence.saveMtkDecoderTuning(context, mtk)
            if (bufferedMusic != null) AirPlayPersistence.saveMainBufferedAudio(context, bufferedMusic)
            MzdDefaults.initialize(context)
            assertEquals(mtk ?: true, AirPlayPersistence.loadMtkDecoderTuning(context))
            assertEquals(bufferedMusic ?: false, AirPlayPersistence.loadMainBufferedAudio(context))
            assertEquals(bufferedMusic != null, context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
                .contains("main_buffered_audio"))
        }
    }

    @Test fun mediaKeyFocusDefaultsPreserveSavedChoicesOnFreshAndInitializedMazda() {
        for (initialized in listOf(false, true)) for (disabled in listOf(null, false, true)) {
            clearPreferences()
            context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit()
                .putBoolean("mazda_defaults_initialized", initialized).commit()
            if (disabled != null) AirPlayPersistence.saveDisableMediaKeyAudioFocus(context, disabled)

            repeat(2) { MzdDefaults.initialize(context) }

            assertEquals(disabled ?: true, AirPlayPersistence.loadDisableMediaKeyAudioFocus(context))
        }
    }

    @Test fun missingWirelessBufferIsSeededOnFreshAndInitializedMazdaWithoutChangingWired() {
        for (initialized in listOf(false, true)) for (wired in listOf(null, 50, 300, 1000)) {
            clearPreferences()
            context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit()
                .putBoolean("mazda_defaults_initialized", initialized).commit()
            if (wired != null) AirPlayPersistence.saveMediaBufferMillis(context, wireless = false, millis = wired)

            repeat(2) { MzdDefaults.initialize(context) }

            assertEquals(1000, AirPlayPersistence.loadMediaBufferMillis(context, wireless = true))
            assertEquals(wired ?: 300, AirPlayPersistence.loadMediaBufferMillis(context, wireless = false))
        }
    }

    @Test fun savedWirelessAndLegacySharedBuffersAreRetainedAcrossMazdaUpgrade() {
        for (initialized in listOf(false, true)) for (wireless in listOf(300, 500, 1000)) {
            for (wired in listOf(null, 50)) {
                clearPreferences()
                // A pre-existing shared preference also supplied the wired delay in older versions.
                val preferences = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
                preferences.edit().putBoolean("mazda_defaults_initialized", initialized)
                    .putInt("media_buffer_ms", wireless).commit()
                if (wired != null) AirPlayPersistence.saveMediaBufferMillis(context, wireless = false, millis = wired)

                repeat(2) { MzdDefaults.initialize(context) }

                assertEquals(wireless, AirPlayPersistence.loadMediaBufferMillis(context, wireless = true))
                assertEquals(wired ?: wireless, AirPlayPersistence.loadMediaBufferMillis(context, wireless = false))
            }
        }
    }

    @Test fun freshAndInitializedMazdaLeaveWirelessModeUnseededAndPreserveHotspotDetails() {
        for (initialized in listOf(false, true)) for (mode in listOf(null, WirelessHotspotMode.MANUAL, WirelessHotspotMode.WIFI_P2P,
                WirelessHotspotMode.EXISTING_WIFI)) {
            clearPreferences()
            val preferences = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
            preferences.edit().putBoolean("mazda_defaults_initialized", initialized).commit()
            if (mode != null) AirPlayPersistence.saveWirelessHotspotMode(context, mode)
            AirPlayPersistence.saveManualHotspotSsid(context, "Saved car hotspot")
            AirPlayPersistence.saveManualHotspotPassphrase(context, "saved-password")

            repeat(2) { MzdDefaults.initialize(context) }

            assertEquals(mode != null, preferences.contains("wireless_hotspot_mode"))
            assertEquals(mode ?: WirelessHotspotMode.MANUAL, AirPlayPersistence.loadWirelessHotspotMode(context))
            assertEquals("Saved car hotspot", AirPlayPersistence.loadManualHotspotSsid(context))
            assertEquals("saved-password", AirPlayPersistence.loadManualHotspotPassphrase(context))
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = MzdApplication::class)
class MzdApplicationTest {
    @Test fun applicationStartupInitializesSettingsBeforeAnyActivityIsCreated() {
        val application = RuntimeEnvironment.getApplication()
        assertTrue(application is MzdApplication)
        val preferences = application.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
        assertFalse(preferences.contains("wireless_hotspot_mode"))
        assertFalse(preferences.contains("main_buffered_audio"))
        assertEquals(WirelessHotspotMode.MANUAL, AirPlayPersistence.loadWirelessHotspotMode(application))
        assertEquals(60, AirPlayPersistence.loadFps(application))
        assertEquals(AppAppearance.AUTO, AirPlayPersistence.loadAppAppearance(application))
        assertEquals(4, AirPlayPersistence.loadSettingsGestureFingers(application))
        assertEquals(AirPlayPersistence.DEFAULT_OEM_LABEL, AirPlayPersistence.loadOemLabel(application))
        assertFalse(AirPlayPersistence.loadHideTopBar(application))
        assertFalse(AirPlayPersistence.loadHideBottomBar(application))
        assertTrue(AirPlayPersistence.loadMtkDecoderTuning(application))
        assertTrue(AirPlayPersistence.loadDisableMediaKeyAudioFocus(application))
        assertFalse(AirPlayPersistence.loadMainBufferedAudio(application))
        assertEquals(1000, AirPlayPersistence.loadMediaBufferMillis(application, wireless = true))
        assertEquals(300, AirPlayPersistence.loadMediaBufferMillis(application, wireless = false))
        assertTrue(SetupGuide.seen(application))
    }
}
