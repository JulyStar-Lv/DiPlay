package com.shilapi.vehicle.mzd

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import android.os.SystemClock
import android.text.TextPaint
import com.shilapi.display.api.*
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/** Local guidance card from the immutable CarPlay snapshot; no road geometry is inferred. */
internal class MzdHudTbtRenderer(context: Context, private val clock: () -> Long = SystemClock::uptimeMillis) {
    private var marqueeRoad: String? = null
    private var marqueeStartedAt = 0L
    private val latinTypeface = Typeface.createFromAsset(context.assets, "hud-fonts/Roboto-Regular.ttf")
    private val cjkTypeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val oemIcons = (listOf(3) + (16..24) + (42..51) + listOf(53, 62, 63, 67, 68, 69, 70) + (74..83))
        .associateWith { id -> context.assets.open("hud-icons/tbt/$id.png").use { BitmapFactory.decodeStream(it)!! } }
    private val roundaboutRings = listOf(DrivingSide.LEFT, DrivingSide.RIGHT).associateWith { side ->
        val name = if (side == DrivingSide.LEFT) "left-traffic" else "right-traffic"
        context.assets.open("hud-icons/roundabout-$name.png").use { BitmapFactory.decodeStream(it)!! }
    }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        typeface = latinTypeface
    }

    fun draw(canvas: Canvas, state: NavigationState?): Boolean {
        val saved = canvas.save()
        try {
            canvas.clipRect(0, 0, MzdHudProfile.SIZE, MzdHudProfile.SIZE)
            canvas.drawColor(Color.BLACK)
            if (state?.phase != NavigationPhase.GUIDING) { marqueeRoad = null; return false }
            val lanes = lanes(state.laneGuidance)
            // Same 54px design grid as the OEM glyphs, fitted to the local icon column.
            canvas.save(); canvas.translate(8f, 18f); canvas.scale(.85f, .85f)
            maneuver(canvas, state)
            canvas.restore()
            state.distanceMeters?.let {
                val value = distance(it)
                val unit = if (value.endsWith("km")) "km" else "m"
                val number = value.removeSuffix(unit)
                paint.style = Paint.Style.FILL; paint.color = Color.WHITE; paint.textSize = 26f
                val unitWidth = paint.measureText(unit)
                paint.textSize = 44f
                while (paint.measureText(number) + unitWidth + 1f > 112f && paint.textSize > 22f) paint.textSize -= 1f
                canvas.drawText(number, 60f, 60f, paint)
                val unitX = 61f + paint.measureText(number)
                paint.textSize = 26f
                canvas.drawText(unit, unitX, 60f, paint)
            }
            val roadBottom = road(canvas, state.nextRoad)
            val remainingDistance = label(canvas, state.remainingMeters?.let(::distance) ?: "—", 8f, 164f, 23f, 78f)
            val remainingTime = label(canvas, state.remainingMillis?.let(::duration) ?: "—", 94f, 164f, 23f, 76f)
            val laneCenterY = (roadBottom + minOf(remainingDistance.top, remainingTime.top)) / 2f
            lanes.forEachIndexed { index, lane ->
                val size = minOf(27f, 162f / lanes.size)
                val angles = (lane.anglesDegrees + listOfNotNull(lane.highlightedAngleDegrees)).distinct()
                val stem = when {
                    angles.any { it < 0 } && angles.none { it > 0 } -> 18f
                    angles.any { it > 0 } && angles.none { it < 0 } -> 9f
                    else -> 13.5f
                }
                canvas.save(); canvas.translate(8f + size * index, laneCenterY - size / 2f)
                canvas.scale(size / 27f, size / 27f)
                angles.forEach { laneArrow(canvas, it, stem, OTHER_LANE, 0 in angles) }
                if (lane.recommended) {
                    val selected = lane.highlightedAngleDegrees ?: lane.anglesDegrees.singleOrNull()
                    if (selected != null) laneArrow(canvas, selected, stem, RECOMMENDED, 0 in angles) else {
                        paint.color = RECOMMENDED; paint.style = Paint.Style.FILL
                        canvas.drawRect(stem - 1.6f, 15f, stem + 1.6f, 25f, paint)
                    }
                }
                canvas.restore()
            }
            return marqueeRoad != null
        } finally { canvas.restoreToCount(saved) }
    }

    private fun road(canvas: Canvas, value: String?): Int {
        if (value.isNullOrBlank()) { marqueeRoad = null; return 88 }
        paint.style = Paint.Style.FILL; paint.color = Color.WHITE; paint.textSize = 18f
        val overflow = measureText(value) - 162f
        var offset = 0f
        if (overflow > 0f) {
            val now = clock()
            if (marqueeRoad != value) { marqueeRoad = value; marqueeStartedAt = now }
            // Hold both ends for reading; distance updates do not restart the road name.
            val travelMillis = (overflow / .024f).toLong().coerceAtLeast(1)
            val elapsed = (now - marqueeStartedAt).coerceAtLeast(0) % (travelMillis + 2400)
            offset = ((elapsed - 1200).coerceAtLeast(0) * .024f).coerceAtMost(overflow)
        } else marqueeRoad = null
        val saved = canvas.save()
        try {
            canvas.clipRect(8f, 66f, 170f, 98f)
            drawText(canvas, value, 8f - offset, 88f)
        } finally { canvas.restoreToCount(saved) }
        // Full-name bounds keep the lane row stationary as different letters scroll past.
        return textBounds(value, 8f, 88f).bottom
    }

    /** Compact solid heads, shared stems and rounded elbows follow the OEM's 27px lane glyphs. */
    private fun laneArrow(canvas: Canvas, degrees: Int, stem: Float, color: Int, hasStraight: Boolean) {
        val path = Path().apply { moveTo(stem, 25f) }
        val endX: Float; val endY: Float
        val dx: Float; val dy: Float
        when (degrees) {
            0 -> { endX = stem; endY = 2f; dx = 0f; dy = -1f; path.lineTo(stem, 8f) }
            -90, 90 -> {
                dx = if (degrees < 0) -1f else 1f; dy = 0f
                endX = if (degrees < 0) 2f else 25f; endY = if (hasStraight) 16f else 8f
                if (hasStraight) path.lineTo(stem, endY) else {
                    path.lineTo(stem, 14f); path.quadTo(stem, 8f, stem + dx * 6f, 8f)
                }
                path.lineTo(endX - dx * 5f, endY)
            }
            -180, 180 -> {
                val side = if (degrees < 0) -1f else 1f
                endX = stem + side * 12f; endY = 23f; dx = 0f; dy = 1f
                path.lineTo(stem, 10f)
                path.cubicTo(stem, 0f, endX, 0f, endX, 10f)
                path.lineTo(endX, 17f)
            }
            else -> {
                val radians = Math.toRadians(degrees.toDouble())
                dx = sin(radians).toFloat(); dy = -cos(radians).toFloat()
                endX = (stem + dx * 12f).coerceIn(4f, 23f)
                endY = 14f + dy * 12f
                path.lineTo(stem, 16f); path.lineTo(endX - dx * 5f, endY - dy * 5f)
            }
        }
        paint.color = color; paint.style = Paint.Style.STROKE; paint.strokeWidth = 3.2f
        paint.strokeCap = Paint.Cap.BUTT
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
        canvas.drawPath(Path().apply {
            moveTo(endX, endY)
            lineTo(endX - dx * 8f + dy * 5f, endY - dy * 8f - dx * 5f)
            lineTo(endX - dx * 8f - dy * 5f, endY - dy * 8f + dx * 5f)
            close()
        }, paint)
        paint.strokeCap = Paint.Cap.ROUND
    }

    private fun label(canvas: Canvas, value: String, x: Float, y: Float, size: Float, width: Float): Rect {
        paint.style = Paint.Style.FILL; paint.color = Color.WHITE; paint.textSize = size
        while (measureText(value) > width && paint.textSize > 16f && size > 16f) paint.textSize -= 1f
        var shown = value
        if (measureText(shown) > width) {
            while (shown.isNotEmpty() && measureText("$shown…") > width) shown = shown.dropLast(1)
            shown += "…"
        }
        drawText(canvas, shown, x, y)
        return textBounds(shown, x, y)
    }

    /** Roboto has no CJK glyphs; use the system Medium family for those runs. */
    private fun textRuns(value: String, draw: (Int, Int) -> Unit) {
        fun font(codePoint: Int): Typeface = when (Character.UnicodeScript.of(codePoint)) {
            Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL,
            Character.UnicodeScript.BOPOMOFO -> cjkTypeface
            else -> if (codePoint in 0x3000..0x303f || codePoint in 0xff00..0xffef) cjkTypeface else latinTypeface
        }
        var start = 0
        while (start < value.length) {
            val typeface = font(value.codePointAt(start))
            var end = start + Character.charCount(value.codePointAt(start))
            while (end < value.length && font(value.codePointAt(end)) === typeface)
                end += Character.charCount(value.codePointAt(end))
            paint.typeface = typeface
            draw(start, end)
            start = end
        }
        paint.typeface = latinTypeface
    }

    private fun measureText(value: String): Float {
        var width = 0f
        textRuns(value) { start, end -> width += paint.measureText(value, start, end) }
        return width
    }

    private fun drawText(canvas: Canvas, value: String, x: Float, y: Float) {
        var left = x
        textRuns(value) { start, end ->
            canvas.drawText(value, start, end, left, y, paint)
            left += paint.measureText(value, start, end)
        }
    }

    private fun textBounds(value: String, x: Float, y: Float): Rect {
        val bounds = Rect()
        var left = x
        textRuns(value) { start, end ->
            val run = Rect().also { paint.getTextBounds(value, start, end, it) }
            run.offset(left.toInt(), y.toInt())
            bounds.union(run)
            left += paint.measureText(value, start, end)
        }
        return bounds
    }

    /** Use factory textures where available; draw only the additional CarPlay actions. */
    private fun maneuver(canvas: Canvas, state: NavigationState) {
        val kind = state.maneuver
        val oemIcon = if (kind == ManeuverKind.ROUNDABOUT_U_TURN) when (state.drivingSide) {
            DrivingSide.RIGHT -> 62
            DrivingSide.LEFT -> 67
            DrivingSide.UNKNOWN -> 0
        } else MzdHudMapping.icon(state)
        val extraRoundabout = kind == ManeuverKind.ROUNDABOUT_NUMBERED && (state.roundaboutExit ?: 0) > 10
        if (extraRoundabout && state.drivingSide != DrivingSide.UNKNOWN) {
            canvas.drawBitmap(roundaboutRings.getValue(state.drivingSide), 0f, 0f, bitmapPaint)
            val text = state.roundaboutExit.toString()
            paint.style = Paint.Style.FILL; paint.color = Color.WHITE; paint.textSize = 18f
            canvas.drawText(text, 27f - paint.measureText(text) / 2f, 35f, paint)
            return
        }
        if (oemIcon != 0 && !extraRoundabout) {
            canvas.drawBitmap(oemIcons.getValue(oemIcon), 0f, 0f, bitmapPaint)
            return
        }
        paint.strokeCap = Paint.Cap.BUTT; paint.strokeJoin = Paint.Join.ROUND
        val mirror = when (kind) {
            ManeuverKind.RIGHT, ManeuverKind.SLIGHT_RIGHT, ManeuverKind.SHARP_RIGHT,
            ManeuverKind.U_TURN_RIGHT, ManeuverKind.KEEP_RIGHT, ManeuverKind.OFF_RAMP_RIGHT,
            ManeuverKind.START_ROUTE_U_TURN_RIGHT, ManeuverKind.AT_END_RIGHT, ManeuverKind.DESTINATION_RIGHT,
            ManeuverKind.U_TURN_WHEN_POSSIBLE_RIGHT, ManeuverKind.CHANGE_HIGHWAY_RIGHT -> true
            ManeuverKind.ROUNDABOUT_ENTER, ManeuverKind.ROUNDABOUT_EXIT,
            ManeuverKind.ROUNDABOUT_NUMBERED, ManeuverKind.ROUNDABOUT_U_TURN -> state.drivingSide == DrivingSide.LEFT
            else -> false
        }
        canvas.save()
        if (mirror) { canvas.translate(54f, 0f); canvas.scale(-1f, 1f) }
        when (kind) {
            ManeuverKind.UNKNOWN -> Unit
            ManeuverKind.FOLLOW_ROAD -> {
                guidanceArrow(canvas, Path().apply {
                    moveTo(27f, 49f); lineTo(27f, 43f)
                    cubicTo(27f, 36f, 11f, 34f, 21f, 27f); quadTo(27f, 23f, 27f, 20f)
                }, 27f, 5f, 0f, -1f)
            }
            ManeuverKind.KEEP_LEFT, ManeuverKind.KEEP_RIGHT -> fork(canvas, false)
            ManeuverKind.OFF_RAMP_LEFT, ManeuverKind.OFF_RAMP_RIGHT -> fork(canvas, true)
            ManeuverKind.OFF_RAMP, ManeuverKind.ON_RAMP, ManeuverKind.CHANGE_HIGHWAY -> highway(canvas, kind)
            ManeuverKind.CHANGE_HIGHWAY_LEFT, ManeuverKind.CHANGE_HIGHWAY_RIGHT -> {
                guidanceArrow(canvas, Path().apply {
                    moveTo(40f, 49f); lineTo(40f, 37f); cubicTo(40f, 27f, 22f, 30f, 22f, 24f)
                }, 22f, 5f, 0f, -1f)
            }
            ManeuverKind.AT_END_LEFT, ManeuverKind.AT_END_RIGHT -> {
                // Square elbow for an end-road turn, with no separate bar or protruding branch.
                if (kind == ManeuverKind.AT_END_RIGHT) canvas.translate(-1f, 0f)
                paint.style = Paint.Style.FILL; paint.color = Color.WHITE
                canvas.drawPath(Path().apply {
                    moveTo(44.5f, 47f); lineTo(38.5f, 47f); lineTo(38.5f, 21f)
                    lineTo(24f, 21f); lineTo(24f, 15f); lineTo(44.5f, 15f); close()
                }, paint)
                head(canvas, if (kind == ManeuverKind.AT_END_RIGHT) 10f else 11f, 18f, -1f, 0f)
            }
            ManeuverKind.U_TURN_WHEN_POSSIBLE_LEFT, ManeuverKind.U_TURN_WHEN_POSSIBLE_RIGHT -> {
                uTurn(canvas, 37f)
                stroke(canvas, Path().apply { moveTo(37f, 43f); lineTo(37f, 48f) })
            }
            ManeuverKind.START_ROUTE_U_TURN_LEFT, ManeuverKind.START_ROUTE_U_TURN_RIGHT -> {
                uTurn(canvas, 36f)
                paint.style = Paint.Style.FILL; paint.color = Color.WHITE
                canvas.drawCircle(37f, 46f, 5f, paint)
            }
            ManeuverKind.START_ROUTE -> startRoute(canvas)
            ManeuverKind.DESTINATION_LEFT, ManeuverKind.DESTINATION_RIGHT -> {
                flag(canvas, 8f, 5f, .65f)
                guidanceArrow(canvas, Path().apply {
                    moveTo(45f, 49f); lineTo(45f, 40f); quadTo(45f, 33f, 38f, 33f)
                }, 11f, 33f, -1f, 0f, .7f)
            }
            ManeuverKind.FERRY_ENTER, ManeuverKind.FERRY_EXIT, ManeuverKind.FERRY_CHANGE -> ferry(canvas, kind)
            ManeuverKind.ROUNDABOUT_ENTER, ManeuverKind.ROUNDABOUT_EXIT,
            ManeuverKind.ROUNDABOUT_NUMBERED, ManeuverKind.ROUNDABOUT_U_TURN -> {
                paint.style = Paint.Style.STROKE; paint.color = Color.WHITE; paint.strokeWidth = 4.5f
                canvas.drawCircle(27f, 25f, 16f, paint)
                if (state.drivingSide != DrivingSide.UNKNOWN) {
                    // Top tangent keeps the circulation head clear of the exit number and entry stems.
                    head(canvas, 27f, 9f, -1f, 0f, .45f)
                }
                when (kind) {
                    ManeuverKind.ROUNDABOUT_ENTER -> guidanceArrow(canvas,
                        Path().apply { moveTo(27f, 50f) }, 27f, 38f, 0f, -1f, .45f)
                    ManeuverKind.ROUNDABOUT_EXIT -> guidanceArrow(canvas,
                        Path().apply { moveTo(27f, 40f) }, 27f, 52f, 0f, 1f, .45f)
                    ManeuverKind.ROUNDABOUT_U_TURN -> {
                        // Separate entry and return stems remain readable without choosing a traffic side.
                        guidanceArrow(canvas, Path().apply { moveTo(18f, 50f) }, 18f, 38f, 0f, -1f, .4f)
                        guidanceArrow(canvas, Path().apply { moveTo(36f, 38f) }, 36f, 52f, 0f, 1f, .45f)
                    }
                    else -> stroke(canvas, Path().apply { moveTo(27f, 49f); lineTo(27f, 41f) }, 5f)
                }
                // Restore the text orientation after mirroring the traffic circulation.
                if (mirror) { canvas.translate(54f, 0f); canvas.scale(-1f, 1f) }
                if (kind == ManeuverKind.ROUNDABOUT_NUMBERED) state.roundaboutExit?.let {
                    val text = it.toString()
                    paint.style = Paint.Style.FILL; paint.color = Color.WHITE; paint.textSize = 18f
                    canvas.drawText(text, 27f - paint.measureText(text) / 2f, 32f, paint)
                }
            }
            // Verified OEM actions are rendered above, including numbered roundabouts.
            else -> Unit
        }
        canvas.restore()
    }

    private fun stroke(canvas: Canvas, path: Path, width: Float = 6f, color: Int = Color.WHITE) {
        paint.color = color; paint.style = Paint.Style.STROKE; paint.strokeWidth = width
        paint.strokeCap = Paint.Cap.BUTT; paint.strokeJoin = Paint.Join.ROUND
        canvas.drawPath(path, paint)
    }

    /** Filled, clipped-shoulder arrowhead instead of the previous hollow chevron. */
    private fun head(canvas: Canvas, x: Float, y: Float, dx: Float, dy: Float, scale: Float = 1f) {
        paint.style = Paint.Style.FILL; paint.color = Color.WHITE
        canvas.drawPath(Path().apply {
            fun point(along: Float, across: Float, first: Boolean = false) {
                val px = x - dx * along * scale - dy * across * scale
                val py = y - dy * along * scale + dx * across * scale
                if (first) moveTo(px, py) else lineTo(px, py)
            }
            point(0f, 0f, true); point(10f, 12f); point(20f, 12f)
            point(13f, 3f); point(13f, -3f); point(20f, -12f); point(10f, -12f); close()
        }, paint)
    }

    /** One endpoint/direction controls both the stem join and head, including small symbols. */
    private fun guidanceArrow(canvas: Canvas, path: Path, x: Float, y: Float, dx: Float, dy: Float, scale: Float = 1f) {
        path.lineTo(x - dx * 13f * scale, y - dy * 13f * scale)
        stroke(canvas, path, 6f * scale)
        head(canvas, x, y, dx, dy, scale)
    }

    private fun uTurn(canvas: Canvas, bottom: Float) {
        stroke(canvas, Path().apply {
            moveTo(37f, bottom); lineTo(37f, 17f)
            cubicTo(37f, 6.3f, 22f, 6.3f, 22f, 17f); lineTo(22f, 35f)
        })
        head(canvas, 22f, 46f, 0f, 1f, .85f)
    }

    private fun fork(canvas: Canvas, ramp: Boolean) {
        val path = Path().apply {
            moveTo(40f, 49f)
            if (ramp) {
                lineTo(40f, 39f); cubicTo(40f, 30f, 32f, 26f, 27f, 21f)
            } else lineTo(40f, 34f)
        }
        guidanceArrow(canvas, path, 12f, 6f, -.70710677f, -.70710677f)
    }

    /** Generic ramp messages contain no left/right direction; do not invent one. */
    private fun highway(canvas: Canvas, kind: ManeuverKind) {
        stroke(canvas, Path().apply { moveTo(7f, 48f); lineTo(15f, 7f); moveTo(47f, 48f); lineTo(39f, 7f) }, 4f)
        stroke(canvas, Path().apply { moveTo(11f, 27f); lineTo(43f, 27f) }, 4f)
        when (kind) {
            ManeuverKind.OFF_RAMP -> stroke(canvas, Path().apply { moveTo(7f, 10f); lineTo(47f, 46f) }, 5f)
            ManeuverKind.ON_RAMP -> {
                stroke(canvas, Path().apply { moveTo(27f, 48f); lineTo(27f, 13f) }, 4f)
                head(canvas, 27f, 6f, 0f, -1f, .6f)
            }
            else -> {
                stroke(canvas, Path().apply { moveTo(19f, 46f); lineTo(19f, 36f); lineTo(35f, 18f); lineTo(35f, 11f) }, 4f)
                head(canvas, 35f, 5f, 0f, -1f, .5f)
            }
        }
    }

    private fun flag(canvas: Canvas, x: Float, y: Float, scale: Float = 1f) {
        canvas.save(); canvas.translate(x, y); canvas.scale(scale, scale)
        canvas.drawBitmap(oemIcons.getValue(3), -11f, -6f, bitmapPaint)
        canvas.restore()
    }

    private fun ferry(canvas: Canvas, kind: ManeuverKind) {
        // Boat on the left, travel direction on the right: no arrow through the cabin or hull.
        canvas.save(); canvas.translate(0f, 5f); canvas.scale(.78f, .78f)
        canvas.drawBitmap(oemIcons.getValue(69), 0f, 0f, bitmapPaint)
        canvas.restore()
        when (kind) {
            ManeuverKind.FERRY_ENTER -> guidanceArrow(canvas,
                Path().apply { moveTo(45f, 7f) }, 45f, 43f, 0f, 1f, .45f)
            ManeuverKind.FERRY_EXIT -> guidanceArrow(canvas,
                Path().apply { moveTo(45f, 43f) }, 45f, 7f, 0f, -1f, .45f)
            else -> {
                stroke(canvas, Path().apply { moveTo(45f, 13.75f); lineTo(45f, 36.25f) }, 3.15f)
                head(canvas, 45f, 7f, 0f, -1f, .45f)
                head(canvas, 45f, 43f, 0f, 1f, .45f)
            }
        }
    }

    private fun startRoute(canvas: Canvas) {
        paint.color = Color.WHITE; paint.style = Paint.Style.STROKE; paint.strokeWidth = 5f
        canvas.drawArc(3f, 6f, 43f, 47f, 35f, 290f, false, paint)
        paint.strokeCap = Paint.Cap.BUTT
        canvas.drawLine(16f, 22f, 16f, 32f, paint)
        canvas.drawLine(23f, 22f, 23f, 32f, paint)
        stroke(canvas, Path().apply { moveTo(29f, 27f); lineTo(45f, 27f) }, 8f)
        head(canvas, 51f, 27f, 1f, 0f, .85f)
    }

    companion object {
        const val RECOMMENDED = Color.WHITE
        const val OTHER_LANE = 0xFF666666.toInt()

        fun lanes(value: NavigationLaneGuidance?): List<NavigationLane> {
            if (value == null || value.lanes.size !in 1..12) return emptyList()
            val lanes = value.lanes.sortedBy { it.index }
            if (lanes.map { it.index } != lanes.indices.toList() || lanes.any {
                it.anglesDegrees.size !in 1..8 || it.anglesDegrees.any { angle -> angle !in -180..180 } ||
                    it.highlightedAngleDegrees?.let { angle -> angle !in -180..180 } == true
            }) return emptyList()
            return lanes
        }

        fun distance(meters: Long): String = if (meters < 1000) "${meters}m"
            else if (meters % 1000 == 0L) "${meters / 1000}km"
            else String.format(Locale.ROOT, "%.1fkm", meters / 1000.0)

        fun duration(millis: Long): String {
            val minutes = millis / 60_000 + if (millis % 60_000 > 0) 1 else 0
            return if (minutes < 60) "${minutes}分" else "${minutes / 60}时${minutes % 60}分"
        }
    }
}
