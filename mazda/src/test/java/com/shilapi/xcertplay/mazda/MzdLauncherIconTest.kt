package com.shilapi.xcertplay.mazda

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import com.shilapi.xcertplay.host.R as CommonR
import java.io.File
import kotlin.math.abs
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
        assertMatchesReference(reference(cropped = false, size = 128), render(R.drawable.ic_mazda_launcher, 128))
    }

    @Test fun vectorMatchesTheSuppliedArtworkAndLauncherFillsTheOemSlot() {
        for (size in listOf(48, 128, 192)) {
            val pageReference = reference(cropped = false, size)
            val launcherReference = reference(cropped = true, size)
            val page = render(CommonR.drawable.ic_carplay, size)
            val launcher = render(R.drawable.ic_mazda_launcher, size)
            assertMatchesReference(pageReference, page)
            assertMatchesReference(launcherReference, launcher)
            val bounds = opaqueBounds(launcher)
            assertTrue("$size: $bounds", bounds.width() >= size - 2)
            assertTrue("$size: $bounds", bounds.height() >= size - 2)
            assertTrue(opaqueBounds(page).width() < bounds.width())
            assertEquals(opaqueBounds(page).centerX(), bounds.centerX())
            val comparison = Bitmap.createBitmap(size * 4, size, Bitmap.Config.ARGB_8888)
            Canvas(comparison).apply {
                for ((index, image) in listOf(pageReference, page, launcherReference, launcher).withIndex()) {
                    drawBitmap(image, index * size.toFloat(), 0f, null)
                }
            }
            val file = File("build/reports/mazda-ui/launcher-icon-$size.png")
            file.parentFile!!.mkdirs()
            file.outputStream().use { comparison.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun flatWhiteBadgeAndOriginalGradientReplaceTheOldFrame() {
        val icon = render(R.drawable.ic_mazda_launcher, 128)
        assertEquals(Color.WHITE, icon.getPixel(64, 64)) // Play triangle.
        assertEquals(Color.WHITE, icon.getPixel(29, 64)) // C stroke.
        assertEquals(0, Color.alpha(icon.getPixel(0, 0)))
        val top = icon.getPixel(64, 12)
        val bottom = icon.getPixel(64, 116)
        assertTrue(Color.red(bottom) > Color.red(top) + 70)
        assertTrue(Color.blue(bottom) > Color.blue(top) + 50)
        assertTrue(Color.green(bottom) > Color.green(top))
    }

    private fun reference(cropped: Boolean, size: Int): Bitmap {
        val source = requireNotNull(javaClass.getResourceAsStream("/wt_carplay_icon.png")).use {
            requireNotNull(BitmapFactory.decodeStream(it))
        }
        val bounds = if (cropped) Rect(24, 24, 1000, 1000) else Rect(0, 0, 1024, 1024)
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawBitmap(source, bounds, Rect(0, 0, size, size), Paint(Paint.FILTER_BITMAP_FLAG))
            source.recycle()
        }
    }

    private fun assertMatchesReference(expected: Bitmap, actual: Bitmap) {
        var error = 0L
        for (y in 0 until expected.height) for (x in 0 until expected.width) {
            val a = expected.getPixel(x, y)
            val b = actual.getPixel(x, y)
            error += abs(Color.alpha(a) - Color.alpha(b))
            // Compare premultiplied channels so transparent RGB does not affect the score.
            for (shift in listOf(16, 8, 0)) {
                error += abs(((a ushr shift) and 255) * Color.alpha(a) / 255 -
                    ((b ushr shift) and 255) * Color.alpha(b) / 255)
            }
        }
        val mean = error.toDouble() / (expected.width * expected.height * 4)
        // Vector AA and integer launcher insets differ slightly from the supplied 1024px raster.
        assertTrue("${expected.width}px reference mean channel error: $mean", mean < 8.0)
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
