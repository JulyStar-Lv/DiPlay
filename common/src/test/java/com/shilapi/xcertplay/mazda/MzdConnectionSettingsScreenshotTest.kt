package com.shilapi.xcertplay.mazda

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.wifi.WifiManager
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.DiPlayActivity
import com.shilapi.xcertplay.DiPlayPreferences
import com.shilapi.xcertplay.appDialogBuilder
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import java.io.File
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [MzdConnectionFlowTest.MazdaResources::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MzdConnectionSettingsScreenshotTest {
    @Test fun wifiReminderCheckboxFitsBeforeActionsWithLargeFontsAndRtl() {
        MzdConnectionFlowTest.MazdaResources.enabled = true
        for ((name, qualifiers) in listOf("compact-zh" to "zh-rCN-w360dp-h800dp-mdpi",
            "full-en" to "en-w960dp-h800dp-mdpi", "compact-ar" to "ar-w360dp-h800dp-xxhdpi")) {
            RuntimeEnvironment.setQualifiers(qualifiers)
            val app = RuntimeEnvironment.getApplication()
            app.applicationInfo.flags = app.applicationInfo.flags or ApplicationInfo.FLAG_SUPPORTS_RTL
            @Suppress("DEPRECATION")
            app.resources.updateConfiguration(Configuration(app.resources.configuration).apply { fontScale = 1.5f },
                app.resources.displayMetrics)
            app.getSharedPreferences("mazda_connection", 0).edit().clear().commit()
            AirPlayPersistence.saveWirelessHotspotMode(app, WirelessHotspotMode.MANUAL)
            AirPlayPersistence.saveManualHotspotSsid(app, "")
            AirPlayPersistence.saveManualHotspotPassphrase(app, "")
            shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.NEARBY_WIFI_DEVICES)
            val adapter = app.getSystemService(BluetoothManager::class.java).adapter!!
            shadowOf(adapter).setEnabled(true)
            val phone = adapter.getRemoteDevice("12:34:56:78:9A:BC")
            shadowOf(adapter).setBondedDevices(setOf(phone))
            DiPlayPreferences.savePhone(app, phone.address, "iPhone")
            @Suppress("DEPRECATION")
            app.getSystemService(WifiManager::class.java).isWifiEnabled = true
            val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
            activity.setTheme(R.style.Theme_Xcertplay)
            val flow = MzdConnectionFlow(activity, {}, {}, {}, { activity.appDialogBuilder() })
            try {
                assertFalse(flow.prepare())
                shadowOf(Looper.getMainLooper()).idle()
                val dialog = ShadowAlertDialog.getLatestAlertDialog()
                val decor = dialog.window!!.decorView
                val width = app.resources.displayMetrics.widthPixels
                val height = app.resources.displayMetrics.heightPixels
                decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST))
                decor.layout(0, 0, decor.measuredWidth, decor.measuredHeight)
                val checkbox = descendants(decor).filterIsInstance<CheckBox>().single()
                assertEquals("$name: checkbox follows locale direction", app.resources.configuration.layoutDirection, checkbox.layoutDirection)
                assertEquals(activity.getString(R.string.mazda_skip_wifi_check), checkbox.text.toString())
                assertFalse(checkbox.isChecked)
                assertTrue("$name: checkbox has a touch target", checkbox.width > 0 && checkbox.height > 0)
                assertEquals("$name: checkbox label is not truncated", 0, checkbox.layout.getEllipsisCount(checkbox.lineCount - 1))
                val checkboxPosition = IntArray(2).also(checkbox::getLocationInWindow)
                val button = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                assertEquals(activity.getString(R.string.mazda_connect_anyway), button.text.toString())
                assertEquals("$name: only two actions remain", View.GONE, dialog.getButton(AlertDialog.BUTTON_NEUTRAL).visibility)
                val buttonPosition = IntArray(2).also(button::getLocationInWindow)
                assertTrue("$name: checkbox precedes Connect anyway", checkboxPosition[1] + checkbox.height <= buttonPosition[1])
                val image = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
                try {
                    decor.draw(Canvas(image))
                    val output = File("build/reports/mazda-ui/wifi-reminder-$name-font-1.5.png")
                    output.parentFile!!.mkdirs()
                    output.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                } finally { image.recycle() }
            } finally { flow.cancel(); activity.finish() }
        }
    }

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
