package com.shilapi.xcertplay

import android.content.res.Configuration
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import java.io.File
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowContextImpl

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpatialAudioSettingsTest {
    @Test fun sourceBuildDefaultsOffAndSavedOptInCanAlwaysBeTurnedOff() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        try {
            activity.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
            assertFalse(AirPlayPersistence.loadSpatialAudio(activity))
            AirPlayPersistence.saveSpatialPcmDcx(activity, true)
            assertFalse(AirPlayPersistence.loadSpatialPcmDcx(activity))
            var control = descendants(card(activity)).filterIsInstance<Switch>().single()
            assertFalse(control.isChecked)
            assertFalse(control.isEnabled)
            AirPlayPersistence.saveSpatialAudio(activity, true)
            control = descendants(card(activity)).filterIsInstance<Switch>().single()
            assertTrue(control.isEnabled)
            control.isChecked = false
            assertFalse(AirPlayPersistence.loadSpatialAudio(activity))
        } finally { activity.finish() }
    }

    @Test @Config(shadows = [MazdaTestContext::class])
    fun spatialToggleDisablesDcxAndRestoresItsSavedSelection() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        try {
            activity.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
            assertFalse(AirPlayPersistence.loadSpatialPcmDcx(activity))
            AirPlayPersistence.saveSpatialAudio(activity, true)
            assertFalse(AirPlayPersistence.loadSpatialPcmDcx(activity))
            val content = card(activity)
            val spatial = descendants(content).filterIsInstance<Switch>().single {
                it.contentDescription == activity.getString(R.string.settings_spatial_audio)
            }
            val dcx = descendants(content).filterIsInstance<Switch>().single {
                it.contentDescription == activity.getString(R.string.settings_spatial_pcm_dcx)
            }
            val dcxRow = dcx.parent as View
            assertTrue(dcx.isEnabled)
            dcxRow.performClick()
            assertTrue(AirPlayPersistence.loadSpatialPcmDcx(activity))
            assertTrue(AirPlayPersistence.loadSpatialAudio(activity))
            spatial.isChecked = false
            assertFalse(dcx.isEnabled)
            assertFalse(dcxRow.isEnabled)
            assertFalse(dcxRow.isFocusable)
            assertFalse(dcxRow.createAccessibilityNodeInfo().isEnabled)
            assertTrue(dcx.isChecked)
            dcxRow.performClick()
            assertTrue(AirPlayPersistence.loadSpatialPcmDcx(activity))
            assertFalse(AirPlayPersistence.loadSpatialAudio(activity))
            val restored = descendants(card(activity)).filterIsInstance<Switch>().single {
                it.contentDescription == activity.getString(R.string.settings_spatial_pcm_dcx)
            }
            assertFalse(restored.isEnabled)
            assertTrue(restored.isChecked)
            spatial.isChecked = true
            assertTrue(dcx.isEnabled)
            assertTrue(dcxRow.isEnabled)
            assertTrue(dcxRow.isFocusable)
            assertTrue(dcx.isChecked)
            dcxRow.performClick()
            assertTrue(AirPlayPersistence.loadSpatialAudio(activity))
            assertFalse(AirPlayPersistence.loadSpatialPcmDcx(activity))
        } finally { activity.finish() }
    }

    @Test @Config(shadows = [MazdaReleaseContext::class])
    fun mazdaReleaseExposesDcxButKeepsItOffByDefault() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        try {
            activity.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
            assertTrue(AirPlayPersistence.isSpatialPcmDcxExperiment(activity))
            assertFalse(AirPlayPersistence.loadSpatialPcmDcx(activity))
            val dcx = descendants(card(activity)).filterIsInstance<Switch>().single {
                it.contentDescription == activity.getString(R.string.settings_spatial_pcm_dcx)
            }
            assertFalse(dcx.isChecked)
            assertFalse(dcx.isEnabled)
            (dcx.parent as View).performClick()
            assertFalse(AirPlayPersistence.loadSpatialPcmDcx(activity))
            AirPlayPersistence.saveSpatialAudio(activity, true)
            val enabledDcx = descendants(card(activity)).filterIsInstance<Switch>().single {
                it.contentDescription == activity.getString(R.string.settings_spatial_pcm_dcx)
            }
            assertTrue(enabledDcx.isEnabled)
            (enabledDcx.parent as View).performClick()
            assertTrue(AirPlayPersistence.loadSpatialPcmDcx(activity))
            assertTrue(AirPlayPersistence.loadSpatialAudio(activity))
        } finally { activity.finish() }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "xxhdpi", shadows = [MazdaReleaseContext::class])
    fun compactAndFullRowsWrapAtLargeFontIncludingRtl() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        try {
            activity.applicationInfo.flags = activity.applicationInfo.flags or ApplicationInfo.FLAG_SUPPORTS_RTL
            for ((name, widthDp, tag) in listOf(Triple("compact-zh", 320, "zh-CN"),
                Triple("full-en", 960, "en-US"), Triple("compact-ar", 320, "ar-SA"))) {
                for (spatialEnabled in listOf(true, false)) {
                    val locale = Locale.forLanguageTag(tag)
                    val configuration = Configuration(activity.resources.configuration).apply {
                        fontScale = 1.5f; setLocale(locale); setLayoutDirection(locale)
                    }
                    @Suppress("DEPRECATION")
                    activity.resources.updateConfiguration(configuration, activity.resources.displayMetrics)
                    AirPlayPersistence.saveSpatialAudio(activity, spatialEnabled)
                    AirPlayPersistence.saveSpatialPcmDcx(activity, true)
                    val content = card(activity)
                    content.layoutDirection = configuration.layoutDirection
                    val width = (widthDp * activity.resources.displayMetrics.density).toInt()
                    content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                    content.layout(0, 0, width, content.measuredHeight)
                    assertEquals(configuration.layoutDirection, content.layoutDirection)
                    assertTrue(descendants(content).filterIsInstance<TextView>().any {
                        it.text == activity.getString(R.string.settings_spatial_audio)
                    })
                    assertTrue(descendants(content).filterIsInstance<TextView>().any {
                        it.text == activity.getString(R.string.settings_spatial_pcm_dcx)
                    })
                    val dcx = descendants(content).filterIsInstance<Switch>().single {
                        it.contentDescription == activity.getString(R.string.settings_spatial_pcm_dcx)
                    }
                    assertEquals(spatialEnabled, dcx.isEnabled)
                    assertTrue(dcx.isChecked)
                    assertTrue(((dcx.parent as View).parent as View).paddingStart > 0)
                    for (text in descendants(content).filterIsInstance<TextView>().filter { it.text.isNotEmpty() }) {
                        assertTrue(text.text.toString(), text.layout != null && text.lineCount > 0)
                        assertEquals(text.text.toString(), 0, text.layout.getEllipsisCount(text.lineCount - 1))
                    }
                    val bitmap = Bitmap.createBitmap(width, content.measuredHeight, Bitmap.Config.ARGB_8888)
                    try {
                        content.draw(Canvas(bitmap))
                        val folder = File("build/reports/spatial-audio-ui").apply { mkdirs() }
                        File(folder, name + (if (spatialEnabled) "-on" else "-off") + ".png").outputStream().use {
                            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                        }
                    } finally { bitmap.recycle() }
                }
            }
        } finally { activity.finish() }
    }

    private fun card(activity: DiPlayActivity) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        DiPlayActivity::class.java.getDeclaredMethod("spatialAudioControls", LinearLayout::class.java)
            .apply { isAccessible = true }.invoke(activity, this)
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }

    @Implements(className = "android.app.ContextImpl", isInAndroidSdk = false)
    class MazdaTestContext : ShadowContextImpl() {
        @Implementation fun getPackageName(): String = "com.thunder.carplay.mazdatest"
    }

    @Implements(className = "android.app.ContextImpl", isInAndroidSdk = false)
    class MazdaReleaseContext : ShadowContextImpl() {
        @Implementation fun getPackageName(): String = "com.thunder.carplay"
    }
}
