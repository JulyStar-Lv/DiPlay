package com.shilapi.xcertplay.mazda

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.AppAppearance
import com.shilapi.xcertplay.host.R
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
        assertFalse(AirPlayPersistence.loadHideTopBar(context))
        assertFalse(AirPlayPersistence.loadHideBottomBar(context))
        assertEquals(60, AirPlayPersistence.loadFps(context))
        assertEquals(4, AirPlayPersistence.loadSettingsGestureFingers(context))
        assertEquals(AppAppearance.AUTO, AirPlayPersistence.loadAppAppearance(context))
        assertTrue(AirPlayPersistence.loadMtkDecoderTuning(context))
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

        MzdDefaults.initialize(context)

        assertMazdaDefaults()
        assertFalse(AirPlayPersistence.loadAdaptPipResolution(context))
    }

    @Test fun replacingAnExistingInstallationPreservesSavedChoicesAndFillsOnlyMissingSettings() {
        AirPlayPersistence.saveHideTopBar(context, true)
        AirPlayPersistence.saveFps(context, 30)
        AirPlayPersistence.saveSettingsGestureFingers(context, 0)
        AirPlayPersistence.saveAppAppearance(context, AppAppearance.LIGHT)
        AirPlayPersistence.saveAdaptPipResolution(context, true)
        AirPlayPersistence.saveOemLabel(context, "My car")

        MzdDefaults.initialize(context)

        assertTrue(AirPlayPersistence.loadHideTopBar(context))
        assertFalse(AirPlayPersistence.loadHideBottomBar(context))
        assertEquals(30, AirPlayPersistence.loadFps(context))
        assertEquals(0, AirPlayPersistence.loadSettingsGestureFingers(context))
        assertEquals(AppAppearance.LIGHT, AirPlayPersistence.loadAppAppearance(context))
        assertTrue(AirPlayPersistence.loadAdaptPipResolution(context))
        assertEquals("My car", AirPlayPersistence.loadOemLabel(context))
        assertTrue(SetupGuide.seen(context))
    }

    @Test fun laterLaunchesDoNotResetUserChangesAndClearingDataRestoresInitialization() {
        MzdDefaults.initialize(context)
        AirPlayPersistence.saveHideTopBar(context, true)
        AirPlayPersistence.saveHideBottomBar(context, true)
        AirPlayPersistence.saveFps(context, 30)
        AirPlayPersistence.saveSettingsGestureFingers(context, 3)
        AirPlayPersistence.saveAppAppearance(context, AppAppearance.DARK)
        AirPlayPersistence.saveOemLabel(context, "BYD")
        AirPlayPersistence.saveMtkDecoderTuning(context, false)

        MzdDefaults.initialize(context)

        assertTrue(AirPlayPersistence.loadHideTopBar(context))
        assertTrue(AirPlayPersistence.loadHideBottomBar(context))
        assertEquals(30, AirPlayPersistence.loadFps(context))
        assertEquals(3, AirPlayPersistence.loadSettingsGestureFingers(context))
        assertEquals(AppAppearance.DARK, AirPlayPersistence.loadAppAppearance(context))
        assertEquals("BYD", AirPlayPersistence.loadOemLabel(context))
        assertFalse(AirPlayPersistence.loadMtkDecoderTuning(context))

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

    @Test fun previouslyInitializedMazdaGetsMissingMtkDefaultAndKeepsExplicitChoices() {
        for (saved in listOf(null, false, true)) {
            clearPreferences()
            context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit()
                .putBoolean("mazda_defaults_initialized", true).commit()
            if (saved != null) AirPlayPersistence.saveMtkDecoderTuning(context, saved)
            MzdDefaults.initialize(context)
            assertEquals(saved ?: true, AirPlayPersistence.loadMtkDecoderTuning(context))
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = MzdApplication::class)
class MzdApplicationTest {
    @Test fun applicationStartupInitializesSettingsBeforeAnyActivityIsCreated() {
        val application = RuntimeEnvironment.getApplication()
        assertTrue(application is MzdApplication)
        assertEquals(60, AirPlayPersistence.loadFps(application))
        assertEquals(AppAppearance.AUTO, AirPlayPersistence.loadAppAppearance(application))
        assertEquals(4, AirPlayPersistence.loadSettingsGestureFingers(application))
        assertEquals(AirPlayPersistence.DEFAULT_OEM_LABEL, AirPlayPersistence.loadOemLabel(application))
        assertFalse(AirPlayPersistence.loadHideTopBar(application))
        assertFalse(AirPlayPersistence.loadHideBottomBar(application))
        assertTrue(AirPlayPersistence.loadMtkDecoderTuning(application))
        assertTrue(SetupGuide.seen(application))
    }
}
