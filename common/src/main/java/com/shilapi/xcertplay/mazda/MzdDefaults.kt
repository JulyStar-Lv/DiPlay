package com.shilapi.xcertplay.mazda

import android.content.Context
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.AppAppearance
import com.shilapi.xcertplay.setup.SetupGuide
import androidx.core.content.edit

/** Seed missing preferences once; installations and later user choices keep their saved values. */
internal object MzdDefaults {
    fun initialize(context: Context) {
        // These keys belong to AirPlayPersistence; keep its existing readers and writers unchanged.
        val preferences = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
        // Also seed upgrades already initialized by an earlier Mazda build. Explicit choices
        // (including off) are retained, and this only applies at the next CarPlay connection.
        if (!preferences.contains("mtk_decoder_tuning")) AirPlayPersistence.saveMtkDecoderTuning(context, true)
        if (preferences.getBoolean("mazda_defaults_initialized", false)) return
        if (!preferences.contains("hide_top_bar")) AirPlayPersistence.saveHideTopBar(context, false)
        if (!preferences.contains("hide_bottom_bar")) AirPlayPersistence.saveHideBottomBar(context, false)
        if (!preferences.contains("display_fps")) AirPlayPersistence.saveFps(context, 60)
        if (!preferences.contains("settings_gesture_fingers")) AirPlayPersistence.saveSettingsGestureFingers(context, 4)
        if (!preferences.contains("app_appearance")) AirPlayPersistence.saveAppAppearance(context, AppAppearance.AUTO)

        SetupGuide.markSeen(context)
        preferences.edit { putBoolean("mazda_defaults_initialized", true) }
    }
}
