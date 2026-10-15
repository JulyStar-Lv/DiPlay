package com.shilapi.xcertplay.mazda

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.Handler
import android.view.View
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.CarPlayBackgroundSession
import com.shilapi.xcertplay.CarPlayHostActivity
import com.shilapi.xcertplay.network.WirelessStartupFailure
import com.shilapi.xcertplay.orchestration.CarPlayStatus
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ExecutorService

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

    private fun page(activity: Activity, night: Boolean = false): MzdConnectionView =
        MzdConnectionView(activity, night, {}, {}, {}, wireless = true).also {
            activity.window.decorView.visibility = View.VISIBLE
            activity.setContentView(it)
            // Match ViewRootImpl's visibility update before dispatch (not modeled by Robolectric).
            ReflectionHelpers.setField(ReflectionHelpers.getField<Any>(it, "mAttachInfo"),
                "mWindowVisibility", View.VISIBLE)
            it.dispatchWindowVisibilityChanged(View.VISIBLE)
            layout(it, 1728)
        }

    @Test fun hotspotPreparationFailureShowsTheLocalizedHintAndPreservesRetry() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        try {
            val page = page(activity)
            val entered = CountDownLatch(1)
            val worker = AtomicReference<Thread>()
            var ready = false
            var failed = false
            val preparation = MzdHotspotConnection(activity) { _, _ ->
                worker.set(Thread.currentThread())
                entered.countDown()
                error("Synthetic hotspot failure")
            }
            preparation.start({}, { ready = true }, {
                failed = true
                page.showHotspotFailure()
            })
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            worker.get().join(5_000)
            assertFalse(worker.get().isAlive)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(failed)
            assertFalse(ready)
            val toast = requireNotNull(page.findViewWithTag<TextView>(MzdToast.VIEW_TAG))
            assertEquals(activity.getString(R.string.hotspot_control_failed), toast.text.toString())
            assertFalse(toast.isClickable)
            assertFalse(toast.isFocusable)
        } finally { activity.finish() }
    }

    @Test fun duplicateHintsReplaceTheCurrentOneExpireAndAreRemovedOnDetach() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        try {
            val page = page(activity)
            page.showHotspotFailure()
            val original = page.findViewWithTag<TextView>(MzdToast.VIEW_TAG)
            page.showHotspotFailure()
            assertNull(original.parent)
            assertEquals(1, (0 until page.childCount).count { page.getChildAt(it).tag == MzdToast.VIEW_TAG })
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(4_000))
            assertNull(page.findViewWithTag<TextView>(MzdToast.VIEW_TAG))
            page.showHotspotFailure()
            val retiring = page.findViewWithTag<TextView>(MzdToast.VIEW_TAG)
            activity.setContentView(View(activity))
            assertNull(retiring.parent)
            page.showHotspotFailure()
            assertNull(page.findViewWithTag<TextView>(MzdToast.VIEW_TAG))
        } finally { activity.finish() }
    }

    @Test fun controllerHotspotFailuresShowTheHintButPhoneTimeoutAndDuplicateReportsDoNot() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        host.setTheme(android.R.style.Theme_Material_NoActionBar)
        CarPlayBackgroundSession.clear()
        ReflectionHelpers.setField(CarPlayBackgroundSession, "owner", host)
        try {
            val page = page(activity)
            ReflectionHelpers.setField(host, "connectionPanel", page)
            fun reporter(generation: Int): (CarPlayStatus) -> Unit {
                ReflectionHelpers.setField(host, "restartGeneration", generation)
                return ReflectionHelpers.callInstanceMethod(host, "createStatusReporter",
                    ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, generation))
            }
            reporter(0)(CarPlayStatus.Failed("phone timeout", startupFailure = WirelessStartupFailure.FIRST_TCP_TIMEOUT))
            assertNull(page.findViewWithTag<TextView>(MzdToast.VIEW_TAG))
            val report = reporter(1)
            val failure = CarPlayStatus.Failed("hotspot unavailable", startupFailure = WirelessStartupFailure.HOTSPOT_NOT_READY)
            report(failure)
            val toast = requireNotNull(page.findViewWithTag<TextView>(MzdToast.VIEW_TAG))
            report(failure)
            assertSame(toast, page.findViewWithTag<TextView>(MzdToast.VIEW_TAG))
            reporter(2)(CarPlayStatus.Failed("hotspot configuration", startupFailure = WirelessStartupFailure.HOTSPOT_CONFIGURATION))
            assertNull(toast.parent)
            assertNotNull(page.findViewWithTag<TextView>(MzdToast.VIEW_TAG))
        } finally {
            ReflectionHelpers.getField<AtomicBoolean>(host, "shuttingDown").set(true)
            ReflectionHelpers.getField<Handler>(host, "mainHandler").removeCallbacksAndMessages(null)
            ReflectionHelpers.getField<ExecutorService>(host, "teardownExecutor").shutdownNow()
            ReflectionHelpers.getField<ExecutorService>(host, "airPlayCommandExecutor").shutdownNow()
            CarPlayBackgroundSession.clear()
            activity.finish()
        }
    }

    @Test fun renderLauncherStyleHintInBothPanelSizesAndRtlWithLargeFonts() {
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
                            page.showHotspotFailure()
                            val toast = requireNotNull(page.findViewWithTag<TextView>(MzdToast.VIEW_TAG))
                            // Freeze at the end of the entrance for a deterministic layout render.
                            toast.animate().cancel()
                            toast.alpha = 1f
                            layout(page, width)
                            assertEquals(48f, toast.textSize, .01f)
                            assertTrue(toast.width <= width - 112)
                            assertTrue(kotlin.math.abs(width / 2 - toast.left - toast.width / 2) <= 1)
                            assertEquals(56, 1080 - toast.bottom)
                            assertTrue(toast.layout.lineCount <= 2)
                            assertEquals("The full hint must fit", 0,
                                toast.layout.getEllipsisCount(toast.layout.lineCount - 1))
                            val bitmap = Bitmap.createBitmap(width, 1080, Bitmap.Config.ARGB_8888)
                            page.draw(Canvas(bitmap))
                            val module = File(requireNotNull(System.getProperty("user.dir"))).let {
                                if (it.name == "common") it else File(it, "common")
                            }
                            val out = File(module, "build/reports/mazda-ui/hotspot-toast-$width-${if (night) "dark" else "light"}-$locale-font-1.5.png")
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
