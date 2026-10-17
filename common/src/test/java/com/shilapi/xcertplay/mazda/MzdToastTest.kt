package com.shilapi.xcertplay.mazda

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-mdpi", manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class MzdToastTest {
    private fun layout(view: View, width: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, 1080)
    }

    private fun page(activity: Activity, night: Boolean = false): MzdHomeView =
        MzdHomeView(activity, night, {}, {}, {}, {}, {}).also {
            activity.window.decorView.visibility = View.VISIBLE
            activity.setContentView(it)
            // Model ViewRootImpl's visibility update before dispatch.
            ReflectionHelpers.setField(ReflectionHelpers.getField<Any>(it, "mAttachInfo"),
                "mWindowVisibility", View.VISIBLE)
            it.dispatchWindowVisibilityChanged(View.VISIBLE)
            layout(it, 1728)
        }

    private fun showHint(page: MzdHomeView) = page.showToast(
        page.context.getString(R.string.turn_on_wi_fi_in_the_head_unit_s_settings_to_connect))

    @Test fun repeatedHintsReplaceExpireAndAreRemovedWhenThePageCloses() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        try {
            val page = page(activity)
            showHint(page)
            val original = requireNotNull(page.findViewWithTag<TextView>(MzdToast.VIEW_TAG))
            assertFalse(original.isClickable)
            assertFalse(original.isFocusable)
            showHint(page)
            assertNull(original.parent)
            assertEquals(1, (0 until page.childCount).count { page.getChildAt(it).tag == MzdToast.VIEW_TAG })
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(4_000))
            assertNull(page.findViewWithTag<TextView>(MzdToast.VIEW_TAG))
            showHint(page)
            val retiring = requireNotNull(page.findViewWithTag<TextView>(MzdToast.VIEW_TAG))
            activity.setContentView(View(activity))
            assertNull(retiring.parent)
            showHint(page)
            assertNull(page.findViewWithTag<TextView>(MzdToast.VIEW_TAG))
        } finally { activity.finish() }
    }

    @Test fun renderWifiHintInCompactAndFullPanelsWithLargeFontsAndRtl() {
        try {
            for (locale in listOf("zh-rCN", "ar")) {
                RuntimeEnvironment.setQualifiers("$locale-hdpi")
                RuntimeEnvironment.setFontScale(1.5f)
                val app = RuntimeEnvironment.getApplication()
                app.applicationInfo.flags = app.applicationInfo.flags or ApplicationInfo.FLAG_SUPPORTS_RTL
                for (night in listOf(false, true)) {
                    val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
                    try {
                        val page = page(activity, night)
                        page.layoutDirection = if (locale == "ar") View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                        for (width in listOf(1728, 2496)) {
                            layout(page, width)
                            showHint(page)
                            val toast = requireNotNull(page.findViewWithTag<TextView>(MzdToast.VIEW_TAG))
                            toast.animate().cancel()
                            toast.alpha = 1f
                            layout(page, width)
                            assertEquals(48f, toast.textSize, .01f)
                            assertTrue(toast.width <= width - 112)
                            assertTrue(kotlin.math.abs(width / 2 - toast.left - toast.width / 2) <= 1)
                            assertEquals(56, 1080 - toast.bottom)
                            assertTrue(toast.layout.lineCount <= 2)
                            assertEquals("The full Wi-Fi hint must fit", 0,
                                toast.layout.getEllipsisCount(toast.layout.lineCount - 1))
                            val bitmap = Bitmap.createBitmap(width, 1080, Bitmap.Config.ARGB_8888)
                            page.draw(Canvas(bitmap))
                            val module = File(requireNotNull(System.getProperty("user.dir"))).let {
                                if (it.name == "common") it else File(it, "common")
                            }
                            val out = File(module, "build/reports/mazda-ui/wifi-off-toast-$width-${if (night) "dark" else "light"}-$locale-font-1.5.png")
                            requireNotNull(out.parentFile).mkdirs()
                            out.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                            bitmap.recycle()
                        }
                    } finally { activity.finish() }
                }
            }
        } finally {
            RuntimeEnvironment.setQualifiers("zh-rCN-mdpi")
            RuntimeEnvironment.setFontScale(1f)
        }
    }
}
