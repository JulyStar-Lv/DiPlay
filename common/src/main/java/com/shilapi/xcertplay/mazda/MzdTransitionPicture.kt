package com.shilapi.xcertplay.mazda

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import com.shilapi.xcertplay.airplay.AirPlayViewArea

/** TextureView/PixelCopy read the stream, including pixels outside CarPlay's active view area. */
internal object MzdTransitionPicture {
    const val CAPTURE_WIDTH = 1248
    private const val PICTURE_WIDTH = 384

    fun crop(stream: Bitmap, canvasWidth: Int, canvasHeight: Int, area: AirPlayViewArea): Bitmap {
        val sx = stream.width.toFloat() / canvasWidth
        val sy = stream.height.toFloat() / canvasHeight
        // Crop before downsampling: shrinking the whole canvas first blends the unused black
        // strip into the final active columns. Freeze this area when capture starts, not on reply.
        val source = RectF(area.originX * sx, area.originY * sy,
            (area.originX + (area.width - MzdVideoLayout.rightEdgeGuard(canvasWidth, area)).coerceAtLeast(1f)) * sx,
            (area.originY + area.height) * sy)
        val height = (PICTURE_WIDTH * area.height / area.width).coerceAtLeast(1)
        return Bitmap.createBitmap(PICTURE_WIDTH, height, Bitmap.Config.ARGB_8888).also {
            val transform = Matrix().apply {
                setRectToRect(source, RectF(0f, 0f, it.width.toFloat(), it.height.toFloat()), Matrix.ScaleToFit.FILL)
            }
            Canvas(it).drawBitmap(stream, transform, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        }
    }
}
