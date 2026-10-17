package com.shilapi.xcertplay.mazda

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import com.shilapi.xcertplay.airplay.AirPlayViewArea
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class MzdTransitionFrameTest {
    @Test @Config(sdk = [30, 34])
    fun blurredRightAreaDoesNotImportBlackPaddingOrExposeSharpOldControls() {
        RuntimeEnvironment.setFontScale(1.5f)
        for (divisor in listOf(1, 2)) {
            val area = AirPlayViewArea(1728 / divisor, 1080 / divisor, 768 / divisor)
            val source = picture(2496 / divisor, 1080 / divisor, area)
            val blur = requireNotNull(MzdTransitionPicture.blur(source, 2496 / divisor, 1080 / divisor, area))
            try {
                for (x in 0 until blur.width) for (y in 0 until blur.height)
                    assertEquals("Unused black pixels must stay out of the blurred fallback",
                        Color.rgb(31, 94, 146), blur.getPixel(x, y))
                // A sharp vertical division must become several intermediate colours.
                Canvas(source).drawRect((source.width * .65f), 0f, source.width.toFloat(), source.height.toFloat(),
                    Paint().apply { color = Color.WHITE })
                val softened = requireNotNull(MzdTransitionPicture.blur(source, 2496 / divisor, 1080 / divisor, area))
                try {
                    assertTrue((0 until softened.width).count {
                        val color = softened.getPixel(it, softened.height / 2)
                        color != Color.WHITE && color != Color.rgb(31, 94, 146)
                    } >= 3)
                    val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
                    val root = FrameLayout(activity)
                    val video = View(activity).apply { setBackgroundColor(Color.BLACK) }
                    root.addView(video, FrameLayout.LayoutParams(-1, -1))
                    val mask = MzdTransitionMask(root, video, Handler(Looper.getMainLooper()),
                        checkFrame = { it(false) }, requestPicture = {}, renderedFrames = { 0 }, textureOutput = true)
                    try {
                        mask.begin(2496)
                        mask.showPicture(softened)
                        for (width in listOf(1728, 2496)) {
                            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
                            root.layout(0, 0, width, 1080)
                            val rendered = Bitmap.createBitmap(width, 1080, Bitmap.Config.ARGB_8888)
                            try {
                                root.draw(Canvas(rendered))
                                for (x in 0 until width step 8) assertNotEquals(Color.BLACK, rendered.getPixel(x, 540))
                                if (divisor == 1) {
                                    val file = File("build/reports/mazda-ui/transition-blur-$width-large-font.png")
                                    file.parentFile?.mkdirs()
                                    file.outputStream().use { rendered.compress(Bitmap.CompressFormat.PNG, 100, it) }
                                }
                            } finally { rendered.recycle() }
                        }
                    } finally { mask.dispose(); activity.finish() }
                } finally { softened.recycle() }
            } finally { source.recycle(); blur.recycle() }
        }
    }

    @Test fun oldWideAndNarrowPicturesCannotConfirmTheOppositeTargetAtEitherCanvasScale() {
        for (divisor in listOf(1, 2)) for (origin in listOf(0, 768)) {
            val width = 2496 / divisor
            val height = 1080 / divisor
            val narrow = AirPlayViewArea(1728 / divisor, height, origin / divisor)
            val wide = AirPlayViewArea(width, height)
            val oldNarrow = picture(width, height, narrow)
            val newWide = picture(width, height, wide)
            try {
                assertFalse("Old narrow padding must not be exposed in the expanded window",
                    MzdTransitionFrame.matchesArea(oldNarrow, width, height, wide))
                assertFalse("An old wide picture does not confirm the requested narrow area",
                    MzdTransitionFrame.matchesArea(newWide, width, height, narrow))
                assertTrue(MzdTransitionFrame.matchesArea(oldNarrow, width, height, narrow))
                assertTrue(MzdTransitionFrame.matchesArea(newWide, width, height, wide))
            } finally { oldNarrow.recycle(); newWide.recycle() }
        }
    }

    @Test fun aMostlyExpandedFrameWithABlackRightPillarStillCannotReleaseTheMask() {
        val target = AirPlayViewArea(2496, 1080)
        for (fraction in listOf(.7f, .8f, .9f, .98f)) {
            val frame = picture(2496, 1080, target.copy(width = (target.width * fraction).toInt()))
            try { assertFalse(MzdTransitionFrame.matchesArea(frame, 2496, 1080, target)) }
            finally { frame.recycle() }
        }
    }

    @Test fun missingPixelsCannotProveReadinessAndNearBlackEncoderPaddingIsTolerated() {
        val area = AirPlayViewArea(1728, 1080)
        val frame = picture(2496, 1080, area, Color.rgb(8, 8, 8))
        try {
            assertTrue(MzdTransitionFrame.matchesArea(frame, 2496, 1080, area))
            frame.eraseColor(Color.BLACK)
            assertFalse(MzdTransitionFrame.matchesArea(frame, 2496, 1080, area))
            frame.eraseColor(Color.TRANSPARENT)
            assertFalse(MzdTransitionFrame.matchesArea(frame, 2496, 1080, area))
            assertFalse(MzdTransitionFrame.matchesArea(frame, 0, 1080, area))
        } finally { frame.recycle() }
        assertFalse(MzdTransitionFrame.matchesArea(frame, 2496, 1080, area))
    }

    @Test fun neutralMaskHasNoDockOrWallpaperAtCompactFullAndHighDensitySizes() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        RuntimeEnvironment.setFontScale(1.5f)
        val output = View(activity).apply { setBackgroundColor(Color.MAGENTA) }
        val root = FrameLayout(activity).apply { addView(output, FrameLayout.LayoutParams(-1, -1)) }
        activity.setContentView(root)
        val mask = MzdTransitionMask(root, output, Handler(Looper.getMainLooper()),
            checkFrame = { it(false) }, requestPicture = {}, renderedFrames = { 0 }, textureOutput = true)
        try {
            mask.begin(2496)
            for (density in listOf(160, 320)) for (width in listOf(1728, 1840, 2112, 2384, 2496)) {
                RuntimeEnvironment.setQualifiers("w2560dp-h1440dp-" + if (density == 160) "mdpi" else "xhdpi")
                RuntimeEnvironment.setFontScale(1.5f)
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, width, 1080)
                val rendered = Bitmap.createBitmap(width, 1080, Bitmap.Config.ARGB_8888)
                try {
                    root.draw(Canvas(rendered))
                    for (x in 0 until width step 8) for (y in 0 until 1080 step 32) {
                        assertEquals("No old controls or black output at $width/$density ($x,$y)",
                            MzdTransitionMask.BACKGROUND_COLOR, rendered.getPixel(x, y))
                    }
                    if (width == 1728 || width == 2496) {
                        val file = File("build/reports/mazda-ui/transition-neutral-$width-$density-large-font.png")
                        file.parentFile?.mkdirs()
                        file.outputStream().use { rendered.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }
                } finally { rendered.recycle() }
            }
        } finally { mask.dispose(); activity.finish() }
    }

    private fun picture(width: Int, height: Int, area: AirPlayViewArea, padding: Int = Color.BLACK): Bitmap {
        val scale = MzdTransitionFrame.CAPTURE_WIDTH.toFloat() / width
        return Bitmap.createBitmap(MzdTransitionFrame.CAPTURE_WIDTH, (height * scale).toInt(), Bitmap.Config.ARGB_8888).also {
            it.eraseColor(padding)
            Canvas(it).drawRect(area.originX * scale, area.originY * scale,
                (area.originX + area.width) * scale, (area.originY + area.height) * scale,
                Paint().apply { color = Color.rgb(31, 94, 146) })
        }
    }
}
