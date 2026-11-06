package com.shilapi.xcertplay.mazda

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.provider.Settings
import android.view.View
import android.widget.FrameLayout
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
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
    @Test fun movingPanelLeavesTheOemAreaVisibleInBothDirectionsAndOnReversal() {
        RuntimeEnvironment.setFontScale(1.5f)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        val content = View(activity).apply { setBackgroundColor(Color.CYAN) }
        val shell = MzdCarAppLayout(activity).apply { setPanelContent(content) }
        // Exercise physical OEM coordinates even with an inset Android decor.
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
        fun verifyPixels(screenshot: String? = null) {
            val bitmap = Bitmap.createBitmap(2560, 1440, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.MAGENTA) // The independent OEM window below us.
                val origin = IntArray(2)
                parent.getLocationOnScreen(origin)
                parent.draw(Canvas(bitmap).apply { translate(origin[0].toFloat(), origin[1].toFloat()) })
                val position = IntArray(2)
                content.getLocationOnScreen(position)
                for (x in 80 until 2544 step 16) assertEquals("No extra backdrop at physical x=$x",
                    if (x < position[0]) Color.MAGENTA else Color.CYAN, bitmap.getPixel(x, 800))
                for ((x, y) in listOf(32 to 800, 80 to 170, 80 to 1290, 1000 to 1350))
                    assertEquals("Leave OEM chrome/margins transparent ($x,$y)", Color.MAGENTA, bitmap.getPixel(x, y))
                screenshot?.let {
                    val file = File("build/reports/mazda-ui/transition-oem-$it-large-font.png")
                    file.parentFile?.mkdirs()
                    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            } finally { bitmap.recycle() }
        }
        try {
            ShadowChoreographer.setPaused(true)
            layout()
            val attach = ReflectionHelpers.getField<Any>(shell, "mAttachInfo")
            ReflectionHelpers.setField(attach, "mWindowVisibility", View.VISIBLE)
            shell.dispatchWindowVisibilityChanged(View.VISIBLE)
            for (narrow in listOf(true, false)) {
                val animator = transition(narrow)
                assertEquals(400L, animator.duration)
                for (time in listOf(0L, 50L, 100L, 200L, 300L, 383L, 400L)) {
                    animator.currentPlayTime = time
                    layout()
                    verifyPixels()
                }
                animator.end()
                layout()
                verifyPixels(if (narrow) "1728" else "2496")
            }
            transition(true).currentPlayTime = 200
            layout()
            verifyPixels()
            transition(false).apply { currentPlayTime = 50; layout(); verifyPixels(); end() }
            layout()
            verifyPixels()
        } finally {
            activity.finish()
            Settings.Global.putInt(activity.contentResolver, "key_screen_show", 0)
            ShadowChoreographer.setPaused(false)
        }
    }
}
