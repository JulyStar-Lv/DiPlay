package com.shilapi.xcertplay.mazda

import android.content.res.Resources
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import com.shilapi.xcertplay.CarPlayHostActivity
import com.shilapi.xcertplay.DiPlayActivity
import com.shilapi.xcertplay.InterfaceSize
import com.shilapi.xcertplay.host.R
import java.io.File
import java.util.concurrent.ExecutorService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowResources
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-sw1440dp-w5120dp-h1440dp-mdpi",
    shadows = [MzdConnectionScaleTest.MazdaResources::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MzdConnectionScaleTest {
    @Test fun actualHostUsesTheHomeScaleForEveryChoiceWithoutScalingVideoOrTouch() {
        for (choice in InterfaceSize.CHOICES) {
            InterfaceSize.save(RuntimeEnvironment.getApplication(), choice)
            withPages { home, waiting, root, host ->
                layout(home, 2496)
                layout(root, 2496)
                assertMatchingScale(home, waiting)
                val density = if (choice == InterfaceSize.AUTO) 2f else choice / 100f
                assertEquals(density, waiting.resources.displayMetrics.density, .01f)
                assertEquals(1f, host.resources.displayMetrics.density, 0f)
                assertEquals(160, host.resources.configuration.densityDpi)
                assertEquals(5120, host.resources.configuration.screenWidthDp)
                val video = root.getChildAt(0) as TextureView
                val touches = root.getChildAt(1)
                for (layer in listOf(root, video, touches)) {
                    assertSame(host, layer.context)
                    assertEquals(2496, layer.width)
                    assertEquals(1080, layer.height)
                }
                val retry = descendants(waiting).filterIsInstance<Button>().single {
                    it.text == host.getString(R.string.retry_carplay_connection)
                }
                retry.visibility = View.VISIBLE
                layout(root, 2496)
                assertEquals(home.connectButton.textSize, retry.textSize, 0f)
                assertEquals(home.connectButton.height, retry.height)
                assertEquals(home.connectButton.paddingLeft, retry.paddingLeft)
            }
        }
    }

    @Test fun matchedPagesRenderAtFullAndCompactSizesWithLargeFontsAndRtl() {
        InterfaceSize.save(RuntimeEnvironment.getApplication(), InterfaceSize.AUTO)
        for ((locale, font) in listOf("zh-rCN" to 1f, "zh-rCN" to 1.5f, "ar" to 1.5f)) {
            RuntimeEnvironment.setQualifiers("$locale-sw1440dp-w5120dp-h1440dp-mdpi")
            RuntimeEnvironment.setFontScale(font)
            val app = RuntimeEnvironment.getApplication()
            app.applicationInfo.flags = app.applicationInfo.flags or ApplicationInfo.FLAG_SUPPORTS_RTL
            for (width in listOf(1728, 2496)) for (night in listOf(false, true)) {
                withPages { home, waiting, root, _ ->
                    assertEquals(font, home.resources.configuration.fontScale, 0f)
                    assertEquals(font, waiting.resources.configuration.fontScale, 0f)
                    val direction = if (locale == "ar") View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                    home.layoutDirection = direction
                    root.layoutDirection = direction
                    waiting.layoutDirection = direction
                    home.stage.text = home.context.getString(R.string.connecting_to_your_iphone)
                    waiting.stage.text = waiting.context.getString(R.string.looking_for_your_paired_iphone)
                    home.paint(night)
                    waiting.paint(night)
                    layout(home, width)
                    layout(root, width)
                    assertMatchingScale(home, waiting)
                    if (locale == "ar") {
                        val close = descendants(waiting).filterIsInstance<android.widget.ImageButton>().single()
                        val settings = descendants(waiting).filterIsInstance<Button>().single {
                            it.text == waiting.context.getString(R.string.settings)
                        }
                        assertTrue("Arabic header must mirror its controls", close.left > settings.right)
                    }
                    for ((name, view) in listOf("home" to home, "waiting" to waiting)) {
                        val bitmap = Bitmap.createBitmap(width, 1080, Bitmap.Config.ARGB_8888)
                        view.draw(Canvas(bitmap))
                        val module = File(requireNotNull(System.getProperty("user.dir"))).let {
                            if (it.name == "common") it else File(it, "common")
                        }
                        val output = File(module, "build/reports/mazda-ui/matched-$name-$width-${if (night) "dark" else "light"}-$locale-font-$font.png")
                        requireNotNull(output.parentFile).mkdirs()
                        output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                        bitmap.recycle()
                    }
                }
            }
        }
    }

    private fun withPages(check: (MzdHomeView, MzdConnectionView, FrameLayout, CarPlayHostActivity) -> Unit) {
        // Use real Activity contexts but skip onCreate's sensors, networking and session startup.
        val homeActivity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        try {
            homeActivity.setTheme(R.style.Theme_Xcertplay)
            host.setTheme(R.style.Theme_Xcertplay)
            val home = MzdHomeView(homeActivity, false, {}, {}, {}, {}, {})
            ReflectionHelpers.setField(host, "wirelessEnabled", true)
            val root = ReflectionHelpers.callInstanceMethod<FrameLayout>(host, "buildContentView")
            val waiting = root.getChildAt(2) as MzdConnectionView
            check(home, waiting, root, host)
        } finally {
            homeActivity.finish()
            host.finish()
            ReflectionHelpers.getField<Handler>(host, "mainHandler").removeCallbacksAndMessages(null)
            ReflectionHelpers.getField<ExecutorService>(host, "teardownExecutor").shutdownNow()
            ReflectionHelpers.getField<ExecutorService>(host, "airPlayCommandExecutor").shutdownNow()
        }
    }

    private fun assertMatchingScale(home: MzdHomeView, waiting: MzdConnectionView) {
        for (page in listOf(home, waiting)) {
            val exit = descendants(page).filterIsInstance<android.widget.ImageButton>().single()
            val brand = descendants(page).filterIsInstance<TextView>().single {
                it.text == page.context.getString(R.string.diplay)
            }
            val logo = descendants(page).filterIsInstance<android.widget.ImageView>().single {
                it.contentDescription == page.context.getString(R.string.carplay)
            }
            assertEquals(64, exit.width)
            assertEquals(64, exit.height)
            assertEquals(64, exit.top + exit.height / 2)
            assertEquals(64, brand.top + brand.height / 2)
            assertEquals(64, logo.top + logo.height / 2)
            assertEquals(40, logo.width)
            assertEquals(40, logo.height)
            assertEquals(40f, brand.textSize, 0f)
            if (page.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
                assertEquals(40, (exit.parent as View).width - exit.right)
                assertEquals(32, exit.left - logo.right)
                assertEquals(12, logo.left - brand.right)
            } else {
                assertEquals(40, exit.left)
                assertEquals(32, logo.left - exit.right)
                assertEquals(12, brand.left - logo.right)
            }
        }
        assertEquals(home.resources.displayMetrics.density, waiting.resources.displayMetrics.density, 0f)
        assertEquals(home.stage.textSize, waiting.stage.textSize, 0f)
        assertEquals(home.instructions.textSize, waiting.instructions.textSize, 0f)
        fun text(view: View, id: Int) = descendants(view).filterIsInstance<TextView>().single {
            it.text == view.context.getString(id)
        }
        for (id in listOf(R.string.carplay, R.string.settings, R.string.diplay)) {
            assertEquals(text(home, id).textSize, text(waiting, id).textSize, 0f)
        }
        val back = text(waiting, R.string.back_to_diplay)
        assertEquals(home.connectButton.textSize, back.textSize, 0f)
        assertEquals(home.connectButton.height, back.height)
        val homeArt = descendants(home).single { it.javaClass.simpleName == "ConnectionIllustration" }
        val waitingArt = descendants(waiting).single { it.javaClass.simpleName == "ConnectionIllustration" }
        assertEquals(homeArt.width, waitingArt.width)
        assertEquals(homeArt.height, waitingArt.height)
    }

    private fun layout(view: View, width: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, 1080)
    }

    private fun descendants(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) addAll(descendants(view.getChildAt(i)))
    }

    @Implements(Resources::class)
    class MazdaResources : ShadowResources() {
        @RealObject private lateinit var resources: Resources
        @Implementation fun getBoolean(id: Int): Boolean = if (id == R.bool.config_mazda) true else
            Shadow.directlyOn(resources, Resources::class.java, "getBoolean",
                ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, id))
    }
}
