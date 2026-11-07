package com.shilapi.xcertplay.mazda

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.DiPlayActivity
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import java.io.File
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [MzdConnectionFlowTest.MazdaResources::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MzdConnectionSettingsScreenshotTest {
    @Test fun carHotspotAndSameLanRemainReadableWithLargeFontsAndRtl() {
        MzdConnectionFlowTest.MazdaResources.enabled = true
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(R.style.Theme_Xcertplay)
        AirPlayPersistence.saveWirelessHotspotMode(activity, WirelessHotspotMode.MANUAL)
        AirPlayPersistence.saveManualHotspotSsid(activity, "")
        AirPlayPersistence.saveManualHotspotPassphrase(activity, "")
        try {
            for ((name, widthDp, tag) in listOf(Triple("compact-zh", 360, "zh-CN"),
                Triple("full-en", 960, "en-US"), Triple("compact-ar", 360, "ar-SA"))) {
                val locale = Locale.forLanguageTag(tag)
                val config = Configuration(activity.resources.configuration).apply {
                    fontScale = 1.5f; screenWidthDp = widthDp; setLocale(locale); setLayoutDirection(locale)
                }
                @Suppress("DEPRECATION")
                activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
                val content = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutDirection = config.layoutDirection
                }
                DiPlayActivity::class.java.getDeclaredMethod("wirelessLinkControls", LinearLayout::class.java)
                    .apply { isAccessible = true }.invoke(activity, content)
                val width = (widthDp * activity.resources.displayMetrics.density).toInt()
                content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                content.layout(0, 0, width, content.measuredHeight)
                val labels = descendants(content).filterIsInstance<TextView>().toList()
                assertFalse(labels.any { it.text.contains(activity.getString(R.string.wifi_direct)) })
                for (id in listOf(R.string.settings_built_in_car_hotspot_recommended, R.string.existing_wifi_title,
                    R.string.mazda_hotspot_mode_description, R.string.mazda_hotspot_automatic)) {
                    val label = labels.single { it.text.contains(activity.getString(id)) }
                    assertTrue("$name: ${label.text}", label.width > 0)
                    assertEquals(0, label.layout.getEllipsisCount(label.lineCount - 1))
                }
                val image = Bitmap.createBitmap(width, content.measuredHeight, Bitmap.Config.ARGB_8888)
                try {
                    content.draw(Canvas(image))
                    val output = File("build/reports/mazda-ui/connection-$name-font-1.5.png")
                    output.parentFile!!.mkdirs()
                    output.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                } finally { image.recycle() }
            }
        } finally { activity.finish() }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
