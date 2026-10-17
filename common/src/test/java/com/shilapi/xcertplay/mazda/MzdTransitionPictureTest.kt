package com.shilapi.xcertplay.mazda

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Matrix
import android.graphics.drawable.BitmapDrawable
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
class MzdTransitionPictureTest {
    @Test fun cropExcludesTheUnusedRightStripAndItsBlackBoundaryBeforeFiltering() {
        for (canvasWidth in listOf(2496, 1248)) for (origin in listOf(0, 768)) {
            val ratio = canvasWidth / 2496f
            val width = (1728 * ratio).toInt()
            val area = AirPlayViewArea(width, (1080 * ratio).toInt(), (origin * ratio).toInt())
            val stream = Bitmap.createBitmap(canvasWidth, area.height, Bitmap.Config.ARGB_8888)
            stream.eraseColor(Color.BLACK)
            Canvas(stream).drawRect(area.originX.toFloat(), 0f,
                (area.originX + area.width - 1).toFloat(), area.height.toFloat(),
                Paint().apply { color = Color.GREEN })
            val result = MzdTransitionPicture.crop(stream, canvasWidth, area.height, area)
            for (x in 0 until result.width) {
                assertEquals("No black canvas or boundary may enter the snapshot at x=$x", Color.GREEN,
                    result.getPixel(x, result.height / 2))
            }
        }
    }

    @Test fun narrowPictureCoversEveryAnimatedWidthWithUniformBlurAndNoBlackEdge() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        RuntimeEnvironment.setFontScale(1.5f)
        // Match the reported failure: a narrow active picture in a full-width encoded canvas.
        val stream = Bitmap.createBitmap(1248, 540, Bitmap.Config.ARGB_8888)
        stream.eraseColor(Color.BLACK)
        val canvas = Canvas(stream)
        canvas.drawColor(Color.BLACK)
        canvas.drawRect(0f, 0f, 863f, 540f, Paint().apply { color = Color.rgb(42, 153, 186) })
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 48f * 1.5f }
        canvas.drawText("CarPlay", 48f, 120f, text)
        canvas.drawCircle(110f, 250f, 48f, Paint().apply { color = Color.rgb(43, 205, 83) })
        canvas.drawCircle(750f, 250f, 48f, Paint().apply { color = Color.rgb(239, 76, 111) })
        val captured = MzdTransitionPicture.crop(stream, 2496, 1080, AirPlayViewArea(1728, 1080))
        val output = View(activity)
        val root = FrameLayout(activity).apply { addView(output, FrameLayout.LayoutParams(-1, -1)) }
        activity.setContentView(root)
        val mask = MzdTransitionMask(root, output, Handler(Looper.getMainLooper()),
            capture = { it(captured) }, requestPicture = {}, renderedFrames = { 0 }, textureOutput = true)
        try {
            mask.begin(2496)
            assertEquals("A sharp underlay must not ghost through the blur", 1, mask.view.childCount)
            assertEquals(24, (mask.blur.drawable as BitmapDrawable).bitmap.width)
            for (width in listOf(1728, 1840, 2112, 2384, 2496, 2112, 1728)) {
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, width, 1080)
                assertEquals(width, mask.blur.width)
                val transform = FloatArray(9)
                mask.blur.imageMatrix.getValues(transform)
                assertEquals("Blur shapes must not be stretched when the window widens",
                    transform[Matrix.MSCALE_X], transform[Matrix.MSCALE_Y], .001f)
                val rendered = Bitmap.createBitmap(width, 1080, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(rendered))
                for (x in 0 until width) {
                    val pixel = rendered.getPixel(x, 540)
                    assertTrue("No quarter-screen or thin black strip at $width x=$x",
                        Color.blue(pixel) > 50 && Color.alpha(pixel) == 255)
                }
                if (width == 1728 || width == 2496) {
                    val file = File("build/reports/mazda-ui/transition-${width}-large-font.png")
                    file.parentFile.mkdirs()
                    file.outputStream().use { rendered.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            }
        } finally { mask.dispose(); activity.finish() }
    }

    @Test fun blurredPictureDoesNotLeakTheSharpControlsUnderIt() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val source = Bitmap.createBitmap(384, 240, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.rgb(42, 153, 186))
        val canvas = Canvas(source)
        // Thin edges fall between thumbnail samples. A sharp underlay makes them reappear as
        // bright stripes even at the old 8% opacity; the blurred-only image must stay smooth.
        for (x in 6 until source.width step 16) {
            canvas.drawRect(x.toFloat(), 0f, x + 1f, 240f, Paint().apply { color = Color.WHITE })
        }
        val output = View(activity)
        val root = FrameLayout(activity).apply { addView(output, FrameLayout.LayoutParams(-1, -1)) }
        activity.setContentView(root)
        val mask = MzdTransitionMask(root, output, Handler(Looper.getMainLooper()),
            capture = { it(source) }, requestPicture = {}, renderedFrames = { 0 }, textureOutput = true)
        try {
            mask.begin(2496)
            for (width in listOf(1728, 2496)) {
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, width, 1080)
                val rendered = Bitmap.createBitmap(width, 1080, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(rendered))
                val reds = (16 until width - 16).map { Color.red(rendered.getPixel(it, 540)) }
                assertTrue("Sharp edges must not survive as ghosts at width=$width", reds.max() - reds.min() <= 2)
            }
        } finally { mask.dispose(); activity.finish() }
    }

    @Test fun missingSnapshotStillCoversAnExpandingBlackOutput() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val output = View(activity).apply { setBackgroundColor(Color.BLACK) }
        val root = FrameLayout(activity).apply { addView(output, FrameLayout.LayoutParams(-1, -1)) }
        activity.setContentView(root)
        val mask = MzdTransitionMask(root, output, Handler(Looper.getMainLooper()),
            capture = { it(null) }, requestPicture = {}, renderedFrames = { 0 }, textureOutput = true)
        try {
            mask.begin(2496)
            for (width in listOf(1728, 1840, 2112, 2384, 2496)) {
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, width, 1080)
                val rendered = Bitmap.createBitmap(width, 1080, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(rendered))
                for (x in 0 until width) {
                    val pixel = rendered.getPixel(x, 540)
                    assertEquals(255, Color.alpha(pixel))
                    assertNotEquals("The black output must remain covered at width=$width x=$x", Color.BLACK, pixel)
                }
            }
        } finally { mask.dispose(); activity.finish() }
    }
}
