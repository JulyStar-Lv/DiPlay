package com.shilapi.display.android

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.shilapi.display.api.RouteScene
import com.shilapi.display.api.RouteSceneOrigin
import com.shilapi.display.api.RouteSceneProjector
import com.shilapi.display.api.RouteSegment

/** Original Canvas implementation. No phone protocol, OEM coordinates or map SDK is involved. */
class RouteSceneRenderer(private val presentation: RouteMapPresentation = RouteMapPresentation.DEFAULT) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    fun draw(canvas: Canvas, scene: RouteScene?, width: Int = canvas.width, height: Int = canvas.height) {
        require(width in 1..8192 && height in 1..8192)
        val saved = canvas.save()
        canvas.clipRect(0, 0, width, height)
        canvas.drawColor(Color.BLACK)
        if (scene == null || scene.phase != com.shilapi.display.api.NavigationPhase.GUIDING) {
            canvas.restoreToCount(saved)
            return
        }
        val camera = presentation.camera(scene)
        val style = presentation.style
        val frame = RouteSceneProjector.layout(scene, width, height, camera.viewSpanMeters, camera.markerYFraction)
        val scale = minOf(width, height) / style.referenceSize
        fun lines(segments: List<RouteSegment>, color: Int, width: Float) {
            paint.color = color; paint.strokeWidth = width * scale; paint.style = Paint.Style.STROKE
            for (segment in segments) canvas.drawLine(segment.start.x.toFloat(), segment.start.y.toFloat(),
                segment.end.x.toFloat(), segment.end.y.toFloat(), paint)
        }
        lines(frame.roads, style.roadColor, style.roadWidth)
        lines(frame.route, Color.BLACK, style.routeOutlineWidth)
        lines(frame.route, style.routeColor, style.routeWidth)
        canvas.save()
        canvas.translate(frame.marker.x.toFloat(), frame.marker.y.toFloat())
        canvas.scale(scale, scale)
        val marker = Path().apply {
            moveTo(0f, -13f); lineTo(9f, 10f); lineTo(0f, 6f); lineTo(-9f, 10f); close()
        }
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 5f; paint.color = Color.BLACK
        canvas.drawPath(marker, paint)
        paint.style = Paint.Style.FILL; paint.color = Color.WHITE
        canvas.drawPath(marker, paint)
        canvas.restore()
        if (scene.origin == RouteSceneOrigin.SAMPLE_REPLAY) {
            paint.style = Paint.Style.FILL; paint.color = Color.rgb(255, 192, 64)
            paint.textSize = 12f * scale
            canvas.drawText("SIM", 5f * scale, 14f * scale, paint)
        }
        canvas.restoreToCount(saved)
    }
}
