package com.shilapi.xcertplay.mazda

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.FrameLayout
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w2560dp-h1440dp-mdpi", manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class MzdPanelCoverageTest {
    @Test fun aSurfaceViewStyleClearCannotLeaveTransparentPixelsInTheBackdrop() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        try {
            val content = object : View(activity) {
                override fun onDraw(canvas: Canvas) {
                    // Model SurfaceView's software-canvas hole punch for its separate surface.
                    canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                }
            }
            val shell = MzdCarAppLayout(activity).apply { setPanelContent(content) }
            shell.measure(View.MeasureSpec.makeMeasureSpec(2560, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1440, View.MeasureSpec.EXACTLY))
            shell.layout(0, 0, 2560, 1440)
            ReflectionHelpers.callInstanceMethod<Void>(shell, "captureBackdrop",
                ReflectionHelpers.ClassParameter.from(Bitmap::class.java, null))
            val snapshot = ReflectionHelpers.getField<Bitmap>(shell, "transitionBackdrop")
            for (x in 0 until snapshot.width) for (y in 0 until snapshot.height) {
                assertEquals("A cleared video surface must still occlude the background window",
                    255, Color.alpha(snapshot.getPixel(x, y)))
            }
        } finally { activity.finish() }
    }

    @Test fun delayedUnderlyingWindowCannotShowThroughEitherDirectionOrAReversal() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        val content = View(activity).apply { setBackgroundColor(Color.CYAN) }
        val shell = MzdCarAppLayout(activity).apply { setPanelContent(content) }
        // Exercise the real OEM coordinates even when the Android decor is inset by 66 px.
        val parent = FrameLayout(activity).apply {
            addView(shell, FrameLayout.LayoutParams(2560, 1374).apply { topMargin = 66 })
        }
        activity.setContentView(parent)
        fun layout() {
            parent.measure(View.MeasureSpec.makeMeasureSpec(2560, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1440, View.MeasureSpec.EXACTLY))
            parent.layout(0, 0, 2560, 1440)
        }
        fun transition(narrow: Boolean): ValueAnimator {
            ReflectionHelpers.setField(shell, "vehiclePanel", narrow)
            ReflectionHelpers.callInstanceMethod<Void>(shell, "movePanel")
            return requireNotNull(ReflectionHelpers.getField<ValueAnimator?>(shell, "panelAnimator")).apply { pause() }
        }
        fun bitmap(): Bitmap = Bitmap.createBitmap(2560, 1440, Bitmap.Config.ARGB_8888).also {
            // A lower AppList window deliberately stays at its OLD full bounds. It does not
            // share our animation clock and can lag throughout the foreground transition.
            it.eraseColor(Color.MAGENTA)
            val origin = IntArray(2)
            parent.getLocationOnScreen(origin)
            val canvas = Canvas(it)
            canvas.translate(origin[0].toFloat(), origin[1].toFloat())
            parent.draw(canvas)
        }
        fun covered() {
            bitmap().let {
                for (x in 80 until 2544 step 16) assertEquals("Opaque at physical x=$x", Color.CYAN, it.getPixel(x, 800))
                for ((x, y) in listOf(32 to 800, 80 to 170, 80 to 1290, 1000 to 1350)) {
                    assertEquals("Leave OEM chrome/margins transparent ($x,$y)", Color.MAGENTA, it.getPixel(x, y))
                }
                it.recycle()
            }
        }
        try {
            ShadowChoreographer.setPaused(true)
            layout()
            val attach = ReflectionHelpers.getField<Any>(shell, "mAttachInfo")
            ReflectionHelpers.setField(attach, "mWindowVisibility", View.VISIBLE)
            shell.dispatchWindowVisibilityChanged(View.VISIBLE)
            val shrinking = transition(true)
            for (time in listOf(0L, 50L, 100L, 200L, 300L, 400L)) {
                shrinking.currentPlayTime = time
                layout()
                covered()
                if (time == 200L) {
                    // Negative control: the same geometry without the backdrop reproduces
                    // the exposed AppList. Checking edge coordinates alone misses this bug.
                    val snapshot = ReflectionHelpers.getField<Bitmap>(shell, "transitionBackdrop")
                    ReflectionHelpers.setField(shell, "transitionBackdrop", null)
                    bitmap().let {
                        assertEquals(Color.MAGENTA, it.getPixel(100, 800))
                        it.recycle()
                    }
                    ReflectionHelpers.setField(shell, "transitionBackdrop", snapshot)
                }
            }
            shrinking.end()
            layout()
            val root = ReflectionHelpers.callInstanceMethod<Any>(shell, "getViewRootImpl")
            ReflectionHelpers.callInstanceMethod<Void>(root, "unscheduleTraversals")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(399))
            covered()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
            bitmap().let {
                assertEquals("Release the temporary coverage after background windows settle",
                    Color.MAGENTA, it.getPixel(100, 800))
                assertEquals(Color.CYAN, it.getPixel(1000, 800))
                it.recycle()
            }
            val expanding = transition(false)
            for (time in listOf(0L, 50L, 100L, 200L)) {
                expanding.currentPlayTime = time
                layout()
                covered()
            }
            val snapshot = ReflectionHelpers.getField<Bitmap>(shell, "transitionBackdrop")
            val reversing = transition(true)
            reversing.currentPlayTime = 50
            layout()
            assertSame("Reversal must retain the original picture", snapshot,
                ReflectionHelpers.getField<Bitmap>(shell, "transitionBackdrop"))
            covered()
            shell.dispatchWindowVisibilityChanged(View.GONE)
            assertNull(ReflectionHelpers.getField<Bitmap?>(shell, "transitionBackdrop"))
            assertFalse(reversing.isRunning)
            shell.dispatchWindowVisibilityChanged(View.VISIBLE)
            layout()
            transition(true).currentPlayTime = 0
            assertNotNull(ReflectionHelpers.getField<Bitmap?>(shell, "transitionBackdrop"))
            ReflectionHelpers.setField(shell, "vehiclePanel", false)
            ReflectionHelpers.callInstanceMethod<Void>(shell, "movePanel")
            assertNull("Reversal before the first frame must release the snapshot too",
                ReflectionHelpers.getField<Bitmap?>(shell, "transitionBackdrop"))
            assertNull(ReflectionHelpers.getField<ValueAnimator?>(shell, "panelAnimator"))
        } finally {
            activity.finish()
            Settings.Global.putInt(activity.contentResolver, "key_screen_show", 0)
            ShadowChoreographer.setPaused(false)
        }
    }

    @Test fun slowSystemAnimationKeepsCoverageUntilTheOtherWindowHasSettledAndDetachCancelsIt() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        val content = View(activity).apply { setBackgroundColor(Color.CYAN) }
        val shell = MzdCarAppLayout(activity).apply { setPanelContent(content) }
        activity.setContentView(shell)
        try {
            ShadowChoreographer.setPaused(true)
            shell.measure(View.MeasureSpec.makeMeasureSpec(2560, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1440, View.MeasureSpec.EXACTLY))
            shell.layout(0, 0, 2560, 1440)
            val attach = ReflectionHelpers.getField<Any>(shell, "mAttachInfo")
            ReflectionHelpers.setField(attach, "mWindowVisibility", View.VISIBLE)
            shell.dispatchWindowVisibilityChanged(View.VISIBLE)
            Settings.Global.putFloat(activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 2f)
            ReflectionHelpers.setField(shell, "vehiclePanel", true)
            ReflectionHelpers.callInstanceMethod<Void>(shell, "movePanel")
            requireNotNull(ReflectionHelpers.getField<ValueAnimator?>(shell, "panelAnimator")).end()
            val root = ReflectionHelpers.callInstanceMethod<Any>(shell, "getViewRootImpl")
            ReflectionHelpers.callInstanceMethod<Void>(root, "unscheduleTraversals")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(799))
            assertNotNull(ReflectionHelpers.getField<Bitmap?>(shell, "transitionBackdrop"))
            activity.setContentView(View(activity))
            assertNull(ReflectionHelpers.getField<Bitmap?>(shell, "transitionBackdrop"))
        } finally {
            Settings.Global.putFloat(activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
            activity.finish()
            ShadowChoreographer.setPaused(false)
        }
    }
}
