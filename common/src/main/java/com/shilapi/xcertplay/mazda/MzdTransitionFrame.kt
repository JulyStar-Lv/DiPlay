package com.shilapi.xcertplay.mazda

import android.graphics.Bitmap
import android.graphics.Color
import com.shilapi.xcertplay.airplay.AirPlayViewArea

/** Geometry evidence from raw decoded pixels; a view-area command alone cannot prove presentation. */
internal object MzdTransitionFrame {
    const val CAPTURE_WIDTH = 384
    private const val BLACK_LEVEL = 12
    private const val SAMPLES = 8

    fun matchesArea(frame: Bitmap, canvasWidth: Int, canvasHeight: Int, area: AirPlayViewArea): Boolean {
        if (frame.isRecycled || canvasWidth <= 0 || canvasHeight <= 0 || area.width <= 0 || area.height <= 0 ||
            area.originX < 0 || area.originY < 0 || area.originX + area.width > canvasWidth ||
            area.originY + area.height > canvasHeight) return false
        val sx = frame.width.toFloat() / canvasWidth
        val sy = frame.height.toFloat() / canvasHeight
        val left = area.originX * sx
        val right = (area.originX + area.width - MzdVideoLayout.rightEdgeGuard(canvasWidth, area)) * sx
        val top = area.originY * sy
        val bottom = (area.originY + area.height) * sy
        fun hasPicture(from: Float, to: Float): Boolean {
            for (x in 0 until SAMPLES) for (y in 0 until SAMPLES) {
                val pixel = frame.getPixel((from + (to - from) * (x + .5f) / SAMPLES).toInt().coerceIn(0, frame.width - 1),
                    (top + (bottom - top) * (y + .5f) / SAMPLES).toInt().coerceIn(0, frame.height - 1))
                if (Color.alpha(pixel) == 255 && maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)) > BLACK_LEVEL) return true
            }
            return false
        }
        // A wide frame must fill its far side, not just contain the old narrow picture at the left.
        // An entirely black/unavailable canvas cannot confirm geometry, so keep it covered and retry.
        val band = (right - left) / 4f
        if (!hasPicture(left, left + band) || !hasPicture(left + band, right - band) ||
            !hasPicture(right - maxOf(1f, 2f * sx), right)) return false
        // Conversely, a narrow target must have received its unused black padding: an old wide
        // frame still contains picture there. Ignore the boundary to tolerate filtering/edge seams.
        val margin = maxOf(2f, 16f * sx)
        if (left > margin && hasPicture(0f, left - margin)) return false
        val unusedRight = (area.originX + area.width) * sx + margin
        if (unusedRight < frame.width && hasPicture(unusedRight, frame.width.toFloat())) return false
        return true
    }
}
