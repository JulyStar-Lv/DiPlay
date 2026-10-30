package com.shilapi.display.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import com.shilapi.display.api.JunctionKind
import com.shilapi.display.api.NavigationJunction
import kotlin.math.cos
import kotlin.math.sin

/** Angular road schematic, not a reconstructed map. No lane data or maneuver icon is consumed. */
class JunctionDiagramRenderer(private val style: RouteMapStyle = RouteMapStyle()) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    fun draw(canvas: Canvas, junction: NavigationJunction?, width: Int = canvas.width,
        height: Int = canvas.height) {
        require(width in 1..8192 && height in 1..8192)
        val saved = canvas.save()
        try {
            canvas.clipRect(0, 0, width, height)
            canvas.drawColor(Color.BLACK)
            if (!supports(junction)) return
            val value = requireNotNull(junction)
            val size = style.referenceSize
            val edge = minOf(width, height).toFloat()
            canvas.translate((width - edge) / 2f, (height - edge) / 2f)
            canvas.scale(edge / size, edge / size)
            val cx = size * .5f
            val cy = size * .44f
            val radius = size * .4f
            fun endpoint(degrees: Int): Pair<Float, Float> {
                val radians = Math.toRadians(degrees.toDouble())
                return cx + sin(radians).toFloat() * radius to cy - cos(radians).toFloat() * radius
            }
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = style.roadWidth
            paint.color = style.roadColor
            for (angle in requireNotNull(value.branchAnglesDegrees)) {
                val (x, y) = endpoint(angle)
                canvas.drawLine(cx, cy, x, y, paint)
            }
            val (exitX, exitY) = endpoint(requireNotNull(value.exitAngleDegrees))
            val route = Path().apply {
                moveTo(cx, size); lineTo(cx, cy); lineTo(exitX, exitY)
            }
            paint.color = Color.BLACK; paint.strokeWidth = style.routeOutlineWidth
            canvas.drawPath(route, paint)
            paint.color = style.routeColor; paint.strokeWidth = style.routeWidth
            canvas.drawPath(route, paint)

            // A fixed vehicle marker on the approach; no text or repeated lane strip.
            canvas.translate(cx, size * .8f)
            val marker = Path().apply {
                moveTo(0f, -13f); lineTo(9f, 10f); lineTo(0f, 6f); lineTo(-9f, 10f); close()
            }
            paint.color = Color.BLACK; paint.strokeWidth = 5f
            canvas.drawPath(marker, paint)
            paint.style = Paint.Style.FILL; paint.color = Color.WHITE
            canvas.drawPath(marker, paint)
        } finally {
            canvas.restoreToCount(saved)
        }
    }

    companion object {
        /** Only the intersection geometry observed on the phone is enabled. A roundabout needs
         * its own verified ring/direction policy; missing fields retain the phone-video fallback. */
        fun supports(value: NavigationJunction?): Boolean {
            if (value?.kind != JunctionKind.INTERSECTION) return false
            val branches = value.branchAnglesDegrees ?: return false
            val exit = value.exitAngleDegrees ?: return false
            return branches.size <= 32 && branches.all { it in -180..180 } && exit in -180..180
        }
    }
}

/** A separate, opaque layer above an existing video viewport. Never writes its decoder Surface. */
class JunctionDiagramView(context: Context, style: RouteMapStyle = RouteMapStyle()) : View(context) {
    private val renderer = JunctionDiagramRenderer(style)
    private var junction: NavigationJunction? = null

    init { visibility = GONE }

    fun submit(value: NavigationJunction?) {
        val next = value?.takeIf(JunctionDiagramRenderer::supports)?.let {
            it.copy(branchAnglesDegrees = it.branchAnglesDegrees?.toList())
        }
        if (next == junction) return
        junction = next
        visibility = if (next == null) GONE else VISIBLE
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width > 0 && height > 0) renderer.draw(canvas, junction, width, height)
    }
}
