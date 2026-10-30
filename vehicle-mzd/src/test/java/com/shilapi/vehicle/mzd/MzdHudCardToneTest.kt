package com.shilapi.vehicle.mzd

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.shilapi.display.android.MapStreamContent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MzdHudCardToneTest {
    private fun card(background: Int, foreground: Int, inactive: Int) =
        Bitmap.createBitmap(178, 178, Bitmap.Config.ARGB_8888).also { image ->
            val canvas = Canvas(image)
            canvas.drawColor(background)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = foreground; strokeWidth = 5f }
            canvas.drawLine(24f, 38f, 24f, 72f, paint)
            canvas.drawLine(24f, 38f, 14f, 49f, paint)
            canvas.drawLine(24f, 38f, 34f, 49f, paint)
            canvas.drawRect(20f, 18f, 30f, 28f, paint)
            paint.textSize = 24f
            canvas.drawText("60 m", 16f, 108f, paint)
            paint.textSize = 16f
            canvas.drawText("Turn around", 16f, 139f, paint)
            paint.color = inactive
            canvas.drawLine(91f, 38f, 91f, 72f, paint)
            canvas.drawLine(91f, 38f, 81f, 49f, paint)
            canvas.drawLine(91f, 38f, 101f, 49f, paint)
            canvas.drawRect(87f, 18f, 97f, 28f, paint)
        }

    private fun render(source: Bitmap, mode: MzdHudCardTone.Mode) =
        Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888).also {
            val palette = MzdHudCardTone.detect(source)?.takeIf { value -> value.mode == mode }
                ?: MzdHudCardTone.Palette(mode, if (mode == MzdHudCardTone.Mode.LIGHT) 248 else 24)
            renderInto(source, it, palette)
        }

    private fun renderInto(source: Bitmap, output: Bitmap, palette: MzdHudCardTone.Palette) {
        val filters = MzdHudCardTone.filters(palette)
        val intermediate = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        Canvas(intermediate).drawBitmap(source, 0f, 0f, filters?.source)
        Canvas(output).drawBitmap(intermediate, 0f, 0f, filters?.target)
        intermediate.recycle()
    }

    private fun save(name: String, image: Bitmap) {
        val directory = File("build/reports/hud-card-tone").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun dayCardBecomesBlackAndWhiteWithDistinctGreyInactiveArrows() {
        val source = card(Color.rgb(245, 245, 245), Color.BLACK, Color.rgb(170, 170, 170))
        assertEquals(MzdHudCardTone.Mode.LIGHT, MzdHudCardTone.detect(source)?.mode)
        val output = render(source, MzdHudCardTone.Mode.LIGHT)
        assertEquals(Color.BLACK, output.getPixel(3, 3))
        assertEquals(Color.WHITE, output.getPixel(24, 24))
        val inactive = output.getPixel(91, 24)
        assertEquals(Color.red(inactive), Color.green(inactive))
        assertEquals(Color.red(inactive), Color.blue(inactive))
        assertEquals(102f, Color.red(inactive).toFloat(), 1f)
        assertEquals(Color.rgb(245, 245, 245), source.getPixel(3, 3))
        save("day-source", source); save("day-black-hud", output)
    }

    @Test fun nightCardKeepsWhiteTextAndCrushesBlueGreyBackgroundToBlack() {
        val source = card(0xff111827.toInt(), Color.WHITE, Color.rgb(160, 160, 170))
        assertEquals(MzdHudCardTone.Mode.DARK, MzdHudCardTone.detect(source)?.mode)
        val output = render(source, MzdHudCardTone.Mode.DARK)
        assertEquals(Color.BLACK, output.getPixel(3, 3))
        assertEquals(Color.WHITE, output.getPixel(24, 24))
        val inactive = output.getPixel(91, 24)
        assertEquals(102f, Color.red(inactive).toFloat(), 1f)
        assertEquals(Color.red(inactive), Color.green(inactive))
        assertEquals(Color.red(inactive), Color.blue(inactive))
        save("night-source", source); save("night-black-hud", output)
    }

    @Test fun phoneGreyLevelsInEitherThemeMatchTheOemLanePalette() {
        for (mode in listOf(MzdHudCardTone.Mode.LIGHT, MzdHudCardTone.Mode.DARK)) {
            for (grey in listOf(96, 128, 160, 170, 192)) {
                val source = card(if (mode == MzdHudCardTone.Mode.LIGHT) Color.rgb(245, 245, 245)
                    else Color.rgb(24, 24, 24), if (mode == MzdHudCardTone.Mode.LIGHT) Color.BLACK
                    else Color.WHITE, Color.rgb(grey, grey, grey))
                val output = render(source, mode)
                assertEquals("$mode grey=$grey", Color.BLACK, output.getPixel(3, 3))
                assertEquals("$mode grey=$grey", Color.WHITE, output.getPixel(24, 24))
                assertEquals("$mode grey=$grey", 102f, Color.red(output.getPixel(91, 24)).toFloat(), 1f)
            }
        }
    }

    @Test fun transparentFitMarginsLeaveTheBlackHudBackgroundUntouched() {
        val source = Bitmap.createBitmap(178, 178, Bitmap.Config.ARGB_8888)
        Canvas(source).drawRect(20f, 0f, 158f, 178f, Paint().apply { color = Color.rgb(245, 245, 245) })
        val output = Bitmap.createBitmap(178, 178, Bitmap.Config.ARGB_8888)
        renderInto(source, output, MzdHudCardTone.Palette(MzdHudCardTone.Mode.LIGHT, 248, 170))
        assertEquals(0, Color.alpha(output.getPixel(3, 3)))
        val hud = Bitmap.createBitmap(178, 178, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(hud)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(output, 0f, 0f, null)
        assertEquals(Color.BLACK, hud.getPixel(3, 3))
        assertEquals(Color.BLACK, hud.getPixel(89, 3))
    }

    @Test fun classifierAcceptsSmallVideoBackgroundNoiseButRejectsAnEmptyReadback() {
        val sample = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        assertNull(MzdHudCardTone.detect(sample))
        sample.eraseColor(Color.rgb(244, 245, 246))
        sample.setPixel(4, 1, Color.rgb(235, 238, 240))
        assertEquals(MzdHudCardTone.Mode.LIGHT, MzdHudCardTone.detect(sample)?.mode)
        sample.eraseColor(Color.rgb(21, 29, 39))
        assertEquals(MzdHudCardTone.Mode.DARK, MzdHudCardTone.detect(sample)?.mode)
    }

    @Test fun unknownOrColouredPicturesArePassedThroughWithoutPaletteConversion() {
        val image = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        image.eraseColor(Color.GRAY)
        assertEquals(MzdHudCardTone.Mode.ORIGINAL, MzdHudCardTone.detect(image)?.mode)
        image.eraseColor(Color.WHITE)
        Canvas(image).drawRect(4f, 4f, 12f, 12f, Paint().apply { color = Color.CYAN })
        assertEquals(MzdHudCardTone.Mode.ORIGINAL, MzdHudCardTone.detect(image)?.mode)
        val copy = render(image, MzdHudCardTone.Mode.ORIGINAL)
        assertTrue(image.sameAs(copy))
        assertNull(MzdHudCardTone.filters(MzdHudCardTone.Palette(MzdHudCardTone.Mode.ORIGINAL)))
    }

    @Test fun onlyInstructionCardModeEnablesTheFilter() {
        assertTrue(MzdHudCardTone.enabled(MapStreamContent.INSTRUCTION_CARD))
        assertFalse(MzdHudCardTone.enabled(MapStreamContent.MAP))
        assertFalse(MzdHudCardTone.enabled(MapStreamContent.MAP_WITH_INSTRUCTION_CARD))
    }

    @Test fun backgroundAnchorAdaptsToOffWhiteAndElevatedDarkCardLevels() {
        for (background in listOf(208, 224, 245, 255, 0, 24, 48, 64)) {
            val mode = if (background >= 208) MzdHudCardTone.Mode.LIGHT else MzdHudCardTone.Mode.DARK
            val source = card(Color.rgb(background, background, background),
                if (mode == MzdHudCardTone.Mode.LIGHT) Color.BLACK else Color.WHITE, Color.GRAY)
            assertEquals(mode, MzdHudCardTone.detect(source)?.mode)
            assertEquals("background=$background", Color.BLACK, render(source, mode).getPixel(3, 3))
        }
    }

    @Test fun antialiasedLevelsRemainMonotonicAndAlphaIsNotInvented() {
        val image = Bitmap.createBitmap(256, 1, Bitmap.Config.ARGB_8888)
        for (x in 0..255) image.setPixel(x, 0, Color.rgb(x, x, x))
        for (mode in listOf(MzdHudCardTone.Mode.LIGHT, MzdHudCardTone.Mode.DARK)) {
            for (secondary in listOf(null, 160)) {
                val palette = MzdHudCardTone.Palette(mode, if (mode == MzdHudCardTone.Mode.LIGHT) 248 else 24, secondary)
                val result = Bitmap.createBitmap(256, 1, Bitmap.Config.ARGB_8888)
                renderInto(image, result, palette)
                val levels = (0..255).map { Color.red(result.getPixel(it, 0)) }
                assertTrue(levels.distinct().size > 150)
                assertTrue(levels.zipWithNext().all { (a, b) -> if (mode == MzdHudCardTone.Mode.LIGHT) a >= b else a <= b })
                assertEquals(255, Color.alpha(result.getPixel(100, 0)))
            }
        }
    }
}
