package com.shilapi.xcertplay.mazda.hudbench

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.shilapi.display.android.RasterJunctionImage
import java.io.ByteArrayOutputStream

/** Original synthetic raster road + transparent arrow; not a phone image or an OEM asset. */
internal object HudJunctionReplaySample {
    fun create(): RasterJunctionImage {
        fun encode(draw: (Canvas) -> Unit): ByteArray {
            val bitmap = Bitmap.createBitmap(178, 178, Bitmap.Config.ARGB_8888)
            draw(Canvas(bitmap))
            return ByteArrayOutputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                bitmap.recycle()
                it.toByteArray()
            }
        }
        val road = encode { canvas ->
            canvas.drawColor(Color.BLACK)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(87, 96, 104); strokeWidth = 34f }
            canvas.drawLine(89f, 178f, 89f, 0f, paint)
            canvas.drawLine(0f, 65f, 178f, 65f, paint)
        }
        val arrow = encode { canvas ->
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xff00ffa4.toInt(); strokeWidth = 10f
                style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND
            }
            canvas.drawPath(Path().apply {
                moveTo(89f, 156f); lineTo(89f, 83f); quadTo(89f, 65f, 107f, 65f); lineTo(156f, 65f)
            }, paint)
            paint.style = Paint.Style.FILL
            canvas.drawPath(Path().apply { moveTo(163f, 65f); lineTo(147f, 55f); lineTo(147f, 75f); close() }, paint)
        }
        return requireNotNull(RasterJunctionImage.decode(road, arrow))
    }
}
