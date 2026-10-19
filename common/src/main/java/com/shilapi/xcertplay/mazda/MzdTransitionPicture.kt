package com.shilapi.xcertplay.mazda

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.shilapi.xcertplay.airplay.AirPlayViewArea
import kotlin.math.roundToInt

/** A tiny, already blurred active-area picture. No GPU effect or full-size readback in animation frames. */
internal object MzdTransitionPicture {
    fun blur(source: Bitmap, canvasWidth: Int, canvasHeight: Int, area: AirPlayViewArea): Bitmap? {
        if (!MzdTransitionFrame.matchesArea(source, canvasWidth, canvasHeight, area)) return null
        val sx = source.width.toFloat() / canvasWidth
        val sy = source.height.toFloat() / canvasHeight
        val crop = Rect(((area.originX + MzdVideoLayout.leftEdgeGuard(canvasWidth, area)) * sx).roundToInt(),
            (area.originY * sy).roundToInt(),
            ((area.originX + area.width - MzdVideoLayout.rightEdgeGuard(canvasWidth, area)) * sx).toInt(),
            ((area.originY + area.height) * sy).roundToInt())
        if (crop.width() <= 0 || crop.height() <= 0) return null
        val width = 64
        val height = (width * area.height / area.width).coerceAtLeast(1)
        val small = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(small).drawBitmap(source, crop, Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG))
        val pixels = IntArray(width * height)
        small.getPixels(pixels, 0, width, 0, 0, width, height)
        val horizontal = IntArray(pixels.size)
        fun average(input: IntArray, x: Int, y: Int, vertical: Boolean): Int {
            var red = 0; var green = 0; var blue = 0
            for (delta in -2..2) {
                val xx = (x + if (vertical) 0 else delta).coerceIn(0, width - 1)
                val yy = (y + if (vertical) delta else 0).coerceIn(0, height - 1)
                val pixel = input[yy * width + xx]
                red += pixel ushr 16 and 255; green += pixel ushr 8 and 255; blue += pixel and 255
            }
            return (255 shl 24) or ((red / 5) shl 16) or ((green / 5) shl 8) or (blue / 5)
        }
        for (y in 0 until height) for (x in 0 until width)
            horizontal[y * width + x] = average(pixels, x, y, false)
        for (y in 0 until height) for (x in 0 until width)
            pixels[y * width + x] = average(horizontal, x, y, true)
        small.setPixels(pixels, 0, width, 0, 0, width, height)
        return small
    }
}
