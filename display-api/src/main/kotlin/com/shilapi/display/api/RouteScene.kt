package com.shilapi.display.api

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Local metric coordinates. The source owns projection, route matching and the coordinate origin. */
data class RoutePoint(val eastMeters: Double, val northMeters: Double) {
    init {
        require(eastMeters.isFinite() && northMeters.isFinite())
        require(abs(eastMeters) <= 100_000 && abs(northMeters) <= 100_000)
    }
}

/** Defensive copy: a producer must not mutate a scene after submission. */
class RouteLine(points: List<RoutePoint>) {
    val points: List<RoutePoint> = java.util.Collections.unmodifiableList(points.toList())
    init { require(points.size in 2..256) }
}

enum class RouteSceneOrigin { NAVIGATION_SOURCE, SAMPLE_REPLAY }

/** A full local window, never a delta. Roads, route and pose share the same source coordinate frame.
 * sourceRouteId must identify the currently selected route, not a separately planned alternative.
 * receivedAtMillis uses the receiving process's monotonic clock, never a phone wall clock.
 * Heading is clockwise from local north. The source decides the scale; no maneuver is synthesized. */
class RouteScene(
    val session: SourceSessionKey,
    val revision: Long,
    val sourceRouteId: String,
    val phase: NavigationPhase,
    val route: RouteLine,
    roads: List<RouteLine>,
    val position: RoutePoint,
    val headingDegrees: Double,
    /** Metric span across the viewport's shorter side, independent of any vehicle's pixel size. */
    val viewSpanMeters: Double,
    val receivedAtMillis: Long,
    val origin: RouteSceneOrigin,
    val distanceToManeuverMeters: Long? = null,
) {
    val roads: List<RouteLine> = java.util.Collections.unmodifiableList(roads.toList())
    init {
        require(revision >= 0 && receivedAtMillis >= 0)
        require(sourceRouteId.isNotBlank() && sourceRouteId.length <= 128)
        require(headingDegrees.isFinite() && headingDegrees >= 0 && headingDegrees < 360)
        require(viewSpanMeters.isFinite() && viewSpanMeters in 20.0..5000.0)
        require(distanceToManeuverMeters == null || distanceToManeuverMeters in 0..10_000_000)
        require(roads.size <= 32 && roads.sumOf { it.points.size } + route.points.size <= 2048)
    }
}

/** Session-bound, monotonic input gate. Rejected stale/future frames never extend the display lease. */
class RouteSceneStore(
    private val session: SourceSessionKey,
    private val clock: () -> Long,
    private val allowSampleReplay: Boolean = false,
    private val maximumAgeMillis: Long = 3000,
) : AutoCloseable {
    private var revision = -1L
    private var value: RouteScene? = null
    private var closed = false
    init { require(maximumAgeMillis in 1..30_000) }

    @Synchronized fun submit(scene: RouteScene): Boolean {
        if (closed || scene.session != session || scene.revision <= revision) return false
        if (scene.origin == RouteSceneOrigin.SAMPLE_REPLAY && !allowSampleReplay) return false
        val age = clock() - scene.receivedAtMillis
        if (age !in 0..maximumAgeMillis) return false
        revision = scene.revision
        value = scene.takeIf { it.phase == NavigationPhase.GUIDING }
        return true
    }

    @Synchronized fun current(): RouteScene? {
        val scene = value ?: return null
        if (closed || clock() - scene.receivedAtMillis !in 0..maximumAgeMillis) value = null
        return value
    }

    @Synchronized override fun close() { closed = true; value = null }
}

data class RoutePixel(val x: Double, val y: Double)
data class RouteSegment(val start: RoutePixel, val end: RoutePixel)
data class RouteLayout(val roads: List<RouteSegment>, val route: List<RouteSegment>, val marker: RoutePixel)

/** Shared heading-up transform for every layer. Clipping each original segment avoids false
 * connections between separate road fragments or excursions outside the viewport. */
object RouteSceneProjector {
    fun layout(scene: RouteScene, width: Int, height: Int,
        viewSpanMeters: Double = scene.viewSpanMeters, markerYFraction: Double = 0.74): RouteLayout {
        require(width in 1..8192 && height in 1..8192)
        require(viewSpanMeters.isFinite() && viewSpanMeters in 20.0..5000.0)
        require(markerYFraction.isFinite() && markerYFraction in 0.1..0.9)
        val scale = minOf(width, height) / viewSpanMeters
        val marker = RoutePixel(width * 0.5, height * markerYFraction)
        val heading = Math.toRadians(scene.headingDegrees)
        val c = cos(heading)
        val s = sin(heading)
        fun project(point: RoutePoint): RoutePixel {
            val east = point.eastMeters - scene.position.eastMeters
            val north = point.northMeters - scene.position.northMeters
            return RoutePixel(marker.x + (east * c - north * s) * scale,
                marker.y - (east * s + north * c) * scale)
        }
        fun segments(line: RouteLine) = line.points.zipWithNext { a, b ->
            clip(project(a), project(b), width.toDouble(), height.toDouble())
        }.filterNotNull()
        return RouteLayout(scene.roads.flatMap(::segments), segments(scene.route), marker)
    }

    private fun clip(a: RoutePixel, b: RoutePixel, width: Double, height: Double): RouteSegment? {
        val dx = b.x - a.x
        val dy = b.y - a.y
        if (dx == 0.0 && dy == 0.0) return null
        var enter = 0.0
        var exit = 1.0
        for ((p, q) in listOf(-dx to a.x, dx to (width - a.x), -dy to a.y, dy to (height - a.y))) {
            if (p == 0.0) { if (q < 0) return null } else {
                val t = q / p
                if (p < 0) enter = maxOf(enter, t) else exit = minOf(exit, t)
                if (enter >= exit) return null
            }
        }
        return RouteSegment(RoutePixel(a.x + enter * dx, a.y + enter * dy),
            RoutePixel(a.x + exit * dx, a.y + exit * dy))
    }
}
