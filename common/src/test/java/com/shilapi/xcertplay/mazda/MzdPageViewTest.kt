package com.shilapi.xcertplay.mazda

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-hdpi", manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MzdPageViewTest {
    @Test fun homeButtonsInvokeTheExistingActionsAndStatusCanChangeWithoutRebuilding() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val calls = mutableListOf<String>()
        val home = MzdHomeView(activity, true, { calls += "close" }, { calls += "settings" },
            { calls += "connect" }, { calls += "phone" }, { calls += "usb" })
        home.connectButton.performClick()
        home.usbButton.performClick()
        descendants(home).filterIsInstance<Button>().single {
            it.text == activity.getString(R.string.choose_iphone)
        }.performClick()
        descendants(home).filterIsInstance<ImageButton>().single().performClick()
        descendants(home).filterIsInstance<Button>().single {
            it.text == activity.getString(R.string.settings)
        }.performClick()
        home.stage.text = "iPhone A"
        home.paint(false)
        assertEquals("iPhone A", home.stage.text.toString())
        assertEquals(listOf("connect", "usb", "phone", "close", "settings"), calls)
        activity.finish()
    }

    @Test fun renderBothPagesAtReferenceSizesAndLargeFontIncludingRtl() {
        for ((locale, font) in listOf("zh-rCN" to 1f, "zh-rCN" to 1.5f, "ar" to 1.5f)) {
            RuntimeEnvironment.setQualifiers("$locale-hdpi")
            val app = RuntimeEnvironment.getApplication()
            app.applicationInfo.flags = app.applicationInfo.flags or ApplicationInfo.FLAG_SUPPORTS_RTL
            app.resources.updateConfiguration(Configuration(app.resources.configuration).apply { fontScale = font },
                app.resources.displayMetrics)
            for (width in listOf(1728, 2496)) for (night in listOf(false, true)) for (waiting in listOf(false, true)) {
                val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
                try {
                    val view = if (waiting) MzdConnectionView(activity, night, {}, {}, {}, wireless = true).apply {
                        stage.text = activity.getString(R.string.looking_for_your_paired_iphone)
                        hint.text = activity.getString(R.string.in_carplay_swipe_down_with_three_fingers_to_open_diplay_se)
                    } else MzdHomeView(activity, night, {}, {}, {}, {}, {}).apply {
                        stage.text = activity.getString(R.string.ready_when_you_are)
                    }
                    val direction = if (locale == "ar") View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                    view.layoutDirection = direction
                    view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
                    view.layout(0, 0, width, 1080)
                    assertEquals(direction, view.layoutDirection)
                    val buttons = descendants(view).filterIsInstance<Button>()
                    assertTrue(buttons.all { it.width > 0 && it.height > 0 })
                    val settings = buttons.single { it.text == activity.getString(R.string.settings) }
                    val visible = Rect()
                    assertTrue(settings.getLocalVisibleRect(visible))
                    assertEquals(settings.width, visible.width())
                    assertHeaderVisible(view)
                    if (font == 1f) {
                        val action = if (view is MzdHomeView) view.connectButton else
                            buttons.single { it.text == activity.getString(R.string.back_to_diplay) }
                        assertTrue("Primary action must be visible at reference size", action.getLocalVisibleRect(visible))
                        assertEquals(action.height, visible.height())
                    }
                    val bitmap = Bitmap.createBitmap(width, 1080, Bitmap.Config.ARGB_8888)
                    view.draw(Canvas(bitmap))
                    if (font == 1f) {
                        val edge = bitmap.getPixel(width / 32, 1080 / 8)
                        val centre = bitmap.getPixel(width / 2, 1080 / 8)
                        if (night) {
                            assertTrue("Reference background has near-black edges", Color.blue(edge) < 20)
                            assertTrue("Blue glow must be concentrated at the centre", Color.blue(centre) > Color.blue(edge) + 10)
                        } else {
                            assertTrue("Reference background has near-white edges", Color.red(edge) >= 248)
                            assertTrue("Blue glow must fade towards the edges", Color.red(edge) > Color.red(centre) + 8)
                        }
                    }
                    val module = File(requireNotNull(System.getProperty("user.dir"))).let { if (it.name == "common") it else File(it, "common") }
                    val out = File(module, "build/reports/mazda-ui/${if(waiting) "waiting" else "home"}-$width-${if(night) "dark" else "light"}-$locale-font-$font.png")
                    requireNotNull(out.parentFile).mkdirs()
                    out.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    bitmap.recycle()
                } finally { activity.finish() }
            }
        }
        RuntimeEnvironment.setQualifiers("zh-rCN-hdpi")
    }

    @Test fun headerMatchesFileManagerAtLowAndHighDensityWithLargeFonts() {
        try {
            for (density in listOf("mdpi", "xxxhdpi")) {
                RuntimeEnvironment.setQualifiers("zh-rCN-$density")
                RuntimeEnvironment.setFontScale(1.5f)
                val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
                try {
                    val view = MzdHomeView(activity, false, {}, {}, {}, {}, {})
                    view.measure(View.MeasureSpec.makeMeasureSpec(1728, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
                    view.layout(0, 0, 1728, 1080)
                    assertHeaderVisible(view)
                    val exit = descendants(view).filterIsInstance<ImageButton>().single()
                    val lightTint = exit.imageTintList?.defaultColor
                    view.paint(true)
                    assertNotEquals(lightTint, exit.imageTintList?.defaultColor)
                } finally { activity.finish() }
            }
        } finally { RuntimeEnvironment.setQualifiers("zh-rCN-hdpi") }
    }

    @Test fun homeKeepsTwoActionSlotsForBothSessionStatesWithLargeFonts() {
        val app = RuntimeEnvironment.getApplication()
        val previousFontScale = app.resources.configuration.fontScale
        try {
            for (locale in listOf("zh-rCN", "ar")) for (width in listOf(1728, 2496)) {
                RuntimeEnvironment.setQualifiers("$locale-hdpi")
                app.applicationInfo.flags = app.applicationInfo.flags or ApplicationInfo.FLAG_SUPPORTS_RTL
                app.resources.updateConfiguration(Configuration(app.resources.configuration).apply { fontScale = 1.5f },
                    app.resources.displayMetrics)
                val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
                try {
                    val home = MzdHomeView(activity, false, {}, {}, {}, {}, {})
                    home.layoutDirection = if (locale == "ar") View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                    val action = home.phoneSessionButton
                    for (running in listOf(false, true)) {
                        home.stage.text = activity.getString(if (running) R.string.carplay_connected else R.string.ready_when_you_are)
                        home.connectButton.text = activity.getString(if (running) R.string.open_carplay else R.string.connect_phone)
                        action.text = activity.getString(if (running) R.string.disconnect else R.string.choose_iphone)
                        home.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
                        home.layout(0, 0, width, 1080)
                        val scroll = descendants(home).filterIsInstance<ScrollView>().single()
                        scroll.scrollTo(0, maxOf(0, scroll.getChildAt(0).height - scroll.height + scroll.paddingTop + scroll.paddingBottom))
                        assertSame(home.connectButton.parent, action.parent)
                        assertEquals(2, (action.parent as ViewGroup).childCount)
                        if ((action.parent as LinearLayout).orientation == LinearLayout.HORIZONTAL) {
                            assertEquals(home.connectButton.top, action.top)
                        } else {
                            assertTrue(home.connectButton.bottom <= action.top)
                        }
                        assertEquals(home.connectButton.height, action.height)
                        assertEquals(home.connectButton.width, action.width)
                        val visible = Rect()
                        assertTrue("Home session action must be reachable", action.getLocalVisibleRect(visible))
                        assertEquals(action.height, visible.height())
                        val bitmap = Bitmap.createBitmap(width, 1080, Bitmap.Config.ARGB_8888)
                        try {
                            home.draw(Canvas(bitmap))
                            val out = File("build/reports/mazda-ui/home-session-$width-$locale-font-1.5-${if (running) "connected" else "idle"}.png")
                            out.parentFile!!.mkdirs()
                            out.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                        } finally { bitmap.recycle() }
                    }
                } finally { activity.finish() }
            }
        } finally {
            RuntimeEnvironment.setQualifiers("zh-rCN-hdpi")
            app.resources.updateConfiguration(Configuration(app.resources.configuration).apply { fontScale = previousFontScale },
                app.resources.displayMetrics)
        }
    }

    private fun assertHeaderVisible(view: View) {
        val title = descendants(view).filterIsInstance<TextView>().single {
            it.text == view.context.getString(R.string.diplay)
        }
        val exit = descendants(view).filterIsInstance<ImageButton>().single()
        val logo = descendants(view).filterIsInstance<ImageView>().single {
            it.contentDescription == view.context.getString(R.string.carplay)
        }
        assertEquals(40f, title.textSize, 0f)
        assertEquals(40, logo.width)
        assertEquals(40, logo.height)
        assertEquals(64, logo.top + logo.height / 2)
        assertEquals(64, exit.width)
        assertEquals(64, exit.height)
        assertEquals(64, exit.top + exit.height / 2)
        assertEquals(title.top + title.height / 2, exit.top + exit.height / 2)
        if (view.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
            assertEquals(40, (exit.parent as View).width - exit.right)
            assertEquals(32, exit.left - logo.right)
            assertEquals(12, logo.left - title.right)
            assertEquals("RTL title text must stay beside the close control", (title.width - title.compoundPaddingRight).toFloat(),
                title.layout.getLineRight(0), 1f)
        } else {
            assertEquals(40, exit.left)
            assertEquals(32, logo.left - exit.right)
            assertEquals(12, title.left - logo.right)
        }
        for (control in listOf(title, logo, exit)) {
            val visible = Rect()
            assertTrue("Header control must remain visible", control.getLocalVisibleRect(visible))
            assertEquals(control.width, visible.width())
            assertEquals(control.height, visible.height())
        }
        val usbButtons = descendants(view).filterIsInstance<Button>().filter {
            it.text == view.context.getString(R.string.mazda_usb_connection)
        }
        if (view is MzdHomeView) {
            assertEquals(listOf(view.usbButton), usbButtons)
            val settings = descendants(view).filterIsInstance<Button>().single {
                it.text == view.context.getString(R.string.settings)
            }
            assertSame(settings.parent, view.usbButton.parent)
            assertEquals(settings.top, view.usbButton.top)
            assertEquals(settings.height, view.usbButton.height)
            if (view.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
                assertTrue(view.usbButton.left >= settings.right)
            } else {
                assertTrue(view.usbButton.right <= settings.left)
            }
            val visible = Rect()
            assertTrue("USB connection must remain visible in the header", view.usbButton.getLocalVisibleRect(visible))
            assertEquals(view.usbButton.width, visible.width())
            assertEquals(view.usbButton.height, visible.height())
        } else assertTrue(usbButtons.isEmpty())
    }

    private fun descendants(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) addAll(descendants(view.getChildAt(i)))
    }
}
