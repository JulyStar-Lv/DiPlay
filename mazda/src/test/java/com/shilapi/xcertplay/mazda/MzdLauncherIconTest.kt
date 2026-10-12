package com.shilapi.xcertplay.mazda

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.view.Gravity
import androidx.core.content.ContextCompat
import com.shilapi.xcertplay.host.R as CommonR
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34], qualifiers = "mdpi", manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MzdLauncherIconTest {
    @Test @Config(sdk = [25]) fun legacyAndroidCanStillLoadTheLauncherIcon() {
        assertNotNull(ContextCompat.getDrawable(RuntimeEnvironment.getApplication(), R.drawable.ic_mazda_launcher))
    }

    @Test fun launcherFillsTheOemSlotWhilePageArtworkKeepsItsOriginalMargin() {
        for (size in listOf(48, 128, 192)) {
            val page = render(CommonR.drawable.ic_carplay, size)
            val previous = render(previousLauncher(), size)
            val launcher = render(R.drawable.ic_mazda_launcher, size)
            assertTrue(opaqueBounds(page).width() <= size * .92f)
            val bounds = opaqueBounds(launcher)
            assertTrue("$size: $bounds", bounds.width() >= size - 2)
            assertTrue("$size: $bounds", bounds.height() >= size - 2)
            assertEquals(opaqueBounds(page).centerX(), bounds.centerX())
            // Preserve every pixel of the original badge, rather than redrawing the logo.
            for (y in 0 until size) for (x in 0 until size) {
                val dx = x + .5f - size / 2f
                val dy = y + .5f - size / 2f
                if (dx * dx + dy * dy <= size * size * .39f * .39f) {
                    assertEquals("badge $size ($x,$y)", previous.getPixel(x, y), launcher.getPixel(x, y))
                }
            }
            assertTrue(Color.alpha(launcher.getPixel(0, 0)) < 128)
            assertTrue((0 until size).any { x ->
                val pixel = launcher.getPixel(x, size / 2)
                Color.red(pixel) > 170 && Color.green(pixel) > 230 && Color.blue(pixel) > 190
            })
            val comparison = Bitmap.createBitmap(size * 3, size, Bitmap.Config.ARGB_8888)
            Canvas(comparison).apply {
                drawBitmap(page, 0f, 0f, null)
                drawBitmap(previous, size.toFloat(), 0f, null)
                drawBitmap(launcher, size * 2f, 0f, null)
            }
            val file = File("build/reports/mazda-ui/launcher-icon-$size.png")
            file.parentFile!!.mkdirs()
            file.outputStream().use { comparison.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun cornersMatchTheFileManagerSilhouetteFromTheOemScreenshot() {
        val icon = render(R.drawable.ic_mazda_launcher, 128)
        // Observed orange File Manager edge in the 5120x1440 capture, relative to
        // its 128px icon slot; allow two pixels for capture and alpha-threshold differences.
        val referenceEdge = listOf(0 to 23, 2 to 15, 6 to 9, 10 to 5, 14 to 3, 18 to 1, 22 to 1, 26 to 0)
        for ((y, expected) in referenceEdge) {
            val actual = (0 until 128).first { Color.alpha(icon.getPixel(it, y)) >= 128 }
            assertEquals("corner row $y", expected.toDouble(), actual.toDouble(), 2.0)
            val rightMargin = 127 - (0 until 128).last { Color.alpha(icon.getPixel(it, y)) >= 128 }
            val bottomLeft = (0 until 128).first { Color.alpha(icon.getPixel(it, 127 - y)) >= 128 }
            val bottomRight = 127 - (0 until 128).last { Color.alpha(icon.getPixel(it, 127 - y)) >= 128 }
            for (edge in listOf(rightMargin, bottomLeft, bottomRight)) {
                assertEquals("symmetric corners row $y", actual.toDouble(), edge.toDouble(), 1.0)
            }
        }
    }

    private fun previousLauncher(): Drawable {
        val context = RuntimeEnvironment.getApplication()
        val bitmap = (ContextCompat.getDrawable(context, CommonR.drawable.ic_carplay)!!.mutate() as BitmapDrawable).apply {
            gravity = Gravity.FILL
            isFilterBitmap = true
        }
        return InsetDrawable(bitmap, -.05172414f)
    }

    private fun render(id: Int, size: Int): Bitmap =
        render(ContextCompat.getDrawable(RuntimeEnvironment.getApplication(), id)!!, size)

    private fun render(drawable: Drawable, size: Int): Bitmap {
        val image = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        drawable.apply {
            setBounds(0, 0, size, size)
            draw(Canvas(image))
        }
        return image
    }

    private fun opaqueBounds(image: Bitmap): Rect {
        val bounds = Rect(image.width, image.height, 0, 0)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            if (Color.alpha(image.getPixel(x, y)) >= 128) {
                bounds.left = minOf(bounds.left, x); bounds.top = minOf(bounds.top, y)
                bounds.right = maxOf(bounds.right, x + 1); bounds.bottom = maxOf(bounds.bottom, y + 1)
            }
        }
        return bounds
    }
}
