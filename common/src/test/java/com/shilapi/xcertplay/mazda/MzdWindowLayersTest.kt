package com.shilapi.xcertplay.mazda

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.provider.Settings
import android.view.View
import android.widget.FrameLayout
import com.shilapi.xcertplay.CarPlayHostActivity
import java.io.File
import java.util.concurrent.ExecutorService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.util.ReflectionHelpers as R

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w5120dp-h1440dp-mdpi",
    shadows = [MzdOemReturnTest.MazdaResources::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class MzdWindowLayersTest {
    private lateinit var home: Activity
    private lateinit var projection: CarPlayHostActivity
    private lateinit var lower: MzdCarAppLayout
    private lateinit var upper: MzdCarAppLayout
    private lateinit var content: View
    private lateinit var desktop: FrameLayout
    private val lifecycle get() = R.getField<Application.ActivityLifecycleCallbacks>(MzdIntegration, "windowLifecycle")

    @Before fun setUp() {
        MzdOemReturnTest.MazdaResources.enabled = true
        RuntimeEnvironment.setFontScale(1.5f)
        home = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        projection = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        content = View(projection).apply { setBackgroundColor(Color.CYAN) }
        lower = MzdIntegration.wrap(home, MzdPageView(home, false, {}, {})) as MzdCarAppLayout
        upper = MzdIntegration.wrap(projection, content) as MzdCarAppLayout
        desktop = FrameLayout(home).apply {
            setBackgroundColor(Color.MAGENTA) // Simulate the OEM background beneath both page crops.
            addView(lower, FrameLayout.LayoutParams(-1, -1))
            addView(upper, FrameLayout.LayoutParams(-1, -1))
        }
        home.setContentView(desktop)
        ShadowChoreographer.setPaused(true)
        layout()
        val attach = R.getField<Any>(upper, "mAttachInfo")
        R.setField(attach, "mWindowVisibility", View.VISIBLE)
        upper.dispatchWindowVisibilityChanged(View.VISIBLE)
        lifecycle.onActivityResumed(home)
        lifecycle.onActivityResumed(projection)
    }

    @After fun tearDown() {
        lifecycle.onActivityDestroyed(projection)
        lifecycle.onActivityDestroyed(home)
        MzdIntegration.closeWindows()
        home.finish()
        projection.finish()
        R.getField<android.os.Handler>(projection, "mainHandler").removeCallbacksAndMessages(null)
        R.getField<ExecutorService>(projection, "teardownExecutor").shutdownNow()
        R.getField<ExecutorService>(projection, "airPlayCommandExecutor").shutdownNow()
        Settings.Global.putInt(home.contentResolver, "key_screen_show", 0)
        ShadowChoreographer.setPaused(false)
    }

    private fun layout() {
        desktop.measure(View.MeasureSpec.makeMeasureSpec(5120, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1440, View.MeasureSpec.EXACTLY))
        desktop.layout(0, 0, 5120, 1440)
    }

    @Test fun coveredWhitePageCannotLeakBesideTheMovingPanelInEitherDirectionOrOnReversal() {
        assertEquals(View.INVISIBLE, lower.getChildAt(0).visibility)
        assertEquals("Never hide or recreate the projection output", View.VISIBLE, content.visibility)
        fun move(narrow: Boolean): ValueAnimator {
            R.setField(upper, "vehiclePanel", narrow)
            R.callInstanceMethod<Void>(upper, "movePanel")
            return requireNotNull(R.getField<ValueAnimator?>(upper, "panelAnimator")).apply { pause() }
        }
        fun pixels(name: String? = null) {
            layout()
            val frame = Bitmap.createBitmap(2560, 1440, Bitmap.Config.ARGB_8888)
            try {
                val origin = IntArray(2)
                desktop.getLocationOnScreen(origin)
                desktop.draw(Canvas(frame).apply { translate(origin[0].toFloat(), origin[1].toFloat()) })
                content.getLocationOnScreen(origin)
                // In the recording, two moving window edges differing by one frame exposed
                // 3–19px of white. Verify EVERY pixel outside/inside the upper straight edge.
                for (x in 32 until 2540) assertEquals("No lower white page at x=$x",
                    if (x < origin[0]) Color.MAGENTA else Color.CYAN, frame.getPixel(x, 800))
                name?.let {
                    val file = File("build/reports/mazda-ui/window-layers-$it-large-font.png")
                    file.parentFile?.mkdirs()
                    file.outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            } finally { frame.recycle() }
        }
        // The covered page deliberately remains wide throughout: even a stale background
        // layout or late background animator must never become visible through the top crop.
        for (narrow in listOf(true, false)) {
            val animator = move(narrow)
            assertEquals(400L, animator.duration)
            for (time in listOf(0L, 16L, 100L, 200L, 300L, 383L, 400L)) {
                animator.currentPlayTime = time
                pixels()
            }
            animator.end()
            pixels(if (narrow) "compact" else "full")
        }
        move(true).currentPlayTime = 200
        pixels()
        move(false).apply { currentPlayTime = 50; pixels("reversal"); end() }
        pixels()
    }

    @Test fun returningStoppingDestroyingAndRerenderingRestoreOnlyTheCorrectPage() {
        assertEquals(View.INVISIBLE, lower.getChildAt(0).visibility)
        val replacement = MzdIntegration.wrap(home, View(home).apply { setBackgroundColor(Color.WHITE) })
            as MzdCarAppLayout
        assertEquals("A rerender while covered must not flash a new white page", View.INVISIBLE,
            replacement.getChildAt(0).visibility)
        lifecycle.onActivityResumed(home)
        assertEquals("Returning to Settings/Home restores controls", View.VISIBLE, replacement.getChildAt(0).visibility)
        lifecycle.onActivityResumed(projection)
        assertEquals(View.INVISIBLE, replacement.getChildAt(0).visibility)
        lifecycle.onActivityStopped(projection)
        assertEquals(View.VISIBLE, replacement.getChildAt(0).visibility)
        lifecycle.onActivityResumed(projection)
        lifecycle.onActivityDestroyed(projection)
        assertEquals(View.VISIBLE, replacement.getChildAt(0).visibility)
        assertFalse(home.isFinishing)
        assertEquals(View.VISIBLE, content.visibility)
    }

    @Test fun transferringTheHomeWindowToTheOtherAreaRestoresItWithoutHidingVideo() {
        val decor = home.window.decorView
        decor.layout(0, 0, 2560, 1440)
        val attach = R.getField<Any>(decor, "mAttachInfo")
        R.setField(attach, "mWindowLeft", 2560)
        MzdIntegration.updateWindowLayers()
        assertEquals("A passenger page is not covered by the driver's projection", View.VISIBLE,
            lower.getChildAt(0).visibility)
        R.setField(attach, "mWindowLeft", 0)
        MzdIntegration.updateWindowLayers()
        assertEquals(View.INVISIBLE, lower.getChildAt(0).visibility)
        assertEquals(View.VISIBLE, content.visibility)
    }
}
