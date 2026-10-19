package com.shilapi.xcertplay.mazda

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import com.shilapi.xcertplay.airplay.AirPlayViewArea
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MzdVideoEdgeTest {
    @Test fun measuredNarrowWallpaperEdgeStaysOutsideLiveVideoAndTransitionPictures() {
        RuntimeEnvironment.setFontScale(1.5f)
        for (divisor in listOf(1, 2)) for (origin in listOf(0, 768)) {
            val canvasWidth = 2496 / divisor
            val canvasHeight = 1080 / divisor
            val area = AirPlayViewArea(1728 / divisor, canvasHeight, origin / divisor)
            val source = Bitmap.createBitmap(canvasWidth, canvasHeight, Bitmap.Config.ARGB_8888)
            source.eraseColor(Color.BLACK)
            val picture = Color.rgb(88, 64, 48)
            val sourceCanvas = Canvas(source)
            // The 2026-10-17 capture has about ten source pixels of wallpaper/edge highlight
            // inside the advertised narrow area. The old two-pixel guard exposes that band.
            sourceCanvas.drawRect(area.originX.toFloat(), 0f,
                (area.originX + area.width).toFloat(), canvasHeight.toFloat(),
                Paint().apply { color = Color.BLUE })
            sourceCanvas.drawRect(area.originX.toFloat(), 0f,
                (area.originX + area.width - 10 / divisor).toFloat(), canvasHeight.toFloat(),
                Paint().apply { color = picture })
            sourceCanvas.drawText("CarPlay", area.originX + 48f / divisor, 120f / divisor,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    textSize = 48f * RuntimeEnvironment.getFontScale() / divisor
                })
            try {
                for (width in listOf(1728, 1840, 2112, 2384, 2496)) {
                    val content = MzdVideoLayout.cover(canvasWidth, canvasHeight, area, width, 1080)
                    val matrix = Matrix().apply {
                        setScale(content.width / canvasWidth, content.height / canvasHeight)
                        postTranslate(content.left, content.top)
                    }
                    val rendered = Bitmap.createBitmap(width, 1080, Bitmap.Config.ARGB_8888)
                    try {
                        Canvas(rendered).drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
                        for (x in width / 2 until width) {
                            assertEquals("No wallpaper at width=$width divisor=$divisor origin=$origin x=$x",
                                picture, rendered.getPixel(x, 540))
                        }
                        if (divisor == 1 && origin == 0 && (width == 1728 || width == 2496)) {
                            val file = File("build/reports/mazda-ui/video-edge-${width}-large-font.png")
                            file.parentFile?.mkdirs()
                            file.outputStream().use { rendered.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        }
                    } finally { rendered.recycle() }
                }
            } finally { source.recycle() }
        }
    }

    @Test fun fullAreaRetainsItsExistingTwoPixelGuard() {
        for (divisor in listOf(1, 2)) {
            val width = 2496 / divisor
            val height = 1080 / divisor
            val content = MzdVideoLayout.cover(width, height, AirPlayViewArea(width, height), 2496, 1080)
            assertEquals(2496f / (width - 2), content.width / width, .00001f)
        }
    }

    @Test fun leftBlackStripIsExcludedFromFixedVideoBlurAndTouchAtBothEndpoints() {
        RuntimeEnvironment.setFontScale(1.5f)
        for (divisor in listOf(1, 2)) {
            val width = 2496 / divisor
            val height = 1080 / divisor
            val area = AirPlayViewArea(1728 / divisor, height, 768 / divisor)
            val source = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val picture = Color.rgb(31, 94, 146)
            source.eraseColor(Color.BLACK)
            Canvas(source).drawRect((area.originX + 8 / divisor).toFloat(), 0f,
                (area.originX + area.width - 10 / divisor).toFloat(), height.toFloat(),
                Paint().apply { color = picture })
            try {
                val blur = requireNotNull(MzdTransitionPicture.blur(source, width, height, area))
                try {
                    for (x in 0 until blur.width) assertEquals(picture, blur.getPixel(x, blur.height / 2))
                } finally { blur.recycle() }
                for (viewportWidth in listOf(1728, 2496)) {
                    val content = MzdVideoLayout.fixedCanvas(width, height, area, viewportWidth)
                    val matrix = Matrix().apply {
                        setScale(content.width / width, content.height / height)
                        postTranslate(content.left, content.top)
                    }
                    val rendered = Bitmap.createBitmap(viewportWidth, 1080, Bitmap.Config.ARGB_8888)
                    try {
                        Canvas(rendered).drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
                        // The active narrow area is right anchored behind the moving crop.
                        val left = viewportWidth - 1728
                        for (x in maxOf(0, left) until viewportWidth) assertEquals(
                            "No left/right padding at $viewportWidth/$divisor/$x", picture, rendered.getPixel(x, 540))
                        val event = android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_DOWN,
                            left + 864f, 540f, 0)
                        try {
                            val contact = com.shilapi.xcertplay.media.CarPlayTouchMapper.contacts(event, content).single()
                            assertEquals((768.0 + 10 + (1728 - 10 - 12) / 2.0) / 2496, contact.x, .0001)
                            assertEquals(.5, contact.y, .0001)
                        } finally { event.recycle() }
                        val file = File("build/reports/mazda-ui/video-left-edge-$viewportWidth-$divisor-large-font.png")
                        file.parentFile?.mkdirs()
                        file.outputStream().use { rendered.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    } finally { rendered.recycle() }
                }
            } finally { source.recycle() }
        }
    }
}
