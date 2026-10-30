package com.shilapi.xcertplay.mazda.hudbench

import com.shilapi.display.api.*
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.cos
import kotlin.math.sin

/** Original synthetic metric samples; no map download, geographic position or phone data. */
internal object HudRouteReplayFixtures {
    data class Case(val id: String, val route: RouteLine, val roads: List<RouteLine>, val scale: Double = 1.0) {
        fun scene(progress: Double, session: SourceSessionKey, revision: Long, now: Long,
            farViewSpanScale: Double = 1.0, distanceToManeuverMeters: Long? = null): RouteScene {
            val lengths = route.points.zipWithNext { a, b -> hypot(b.eastMeters - a.eastMeters, b.northMeters - a.northMeters) }
            var remaining = lengths.sum() * progress.coerceIn(0.0, 0.95)
            var segment = 0
            while (segment < lengths.lastIndex && remaining > lengths[segment]) remaining -= lengths[segment++]
            val a = route.points[segment]
            val b = route.points[segment + 1]
            val fraction = if (lengths[segment] == 0.0) 0.0 else remaining / lengths[segment]
            val position = RoutePoint(a.eastMeters + (b.eastMeters - a.eastMeters) * fraction,
                a.northMeters + (b.northMeters - a.northMeters) * fraction)
            val heading = (Math.toDegrees(atan2(b.eastMeters - a.eastMeters, b.northMeters - a.northMeters)) + 360) % 360
            return RouteScene(session, revision, "sample-$id", NavigationPhase.GUIDING, route, roads, position,
                heading, scale * 178 * farViewSpanScale, now, RouteSceneOrigin.SAMPLE_REPLAY, distanceToManeuverMeters)
        }
    }
    private fun line(vararg values: Pair<Int, Int>) = RouteLine(values.map { RoutePoint(it.first.toDouble(), it.second.toDouble()) })
    private val cross = listOf(line(-200 to 70, 200 to 70), line(0 to -200, 0 to 230),
        line(-130 to -90, -130 to 200), line(100 to -100, 100 to 200))
    private val right = line(0 to -90, 0 to 45, 3 to 57, 10 to 66, 22 to 70, 200 to 70)
    private val left = line(0 to -90, 0 to 45, -3 to 57, -10 to 66, -22 to 70, -200 to 70)
    val cases = listOf(
        Case("straight", line(0 to -100, 0 to 250), cross),
        Case("right", right, cross),
        Case("left", left, cross),
        Case("fork", line(0 to -100, 0 to 45, 25 to 80, 100 to 150),
            listOf(line(0 to -130, 0 to 45, -80 to 160), line(0 to 45, 100 to 150))),
        Case("roundabout", RouteLine(listOf(RoutePoint(0.0, -100.0), RoutePoint(0.0, 25.0)) +
            (0..12).map { val a = -Math.PI / 2 + it * Math.PI / 24; RoutePoint(45 * cos(a), 70 + 45 * sin(a)) } +
            listOf(RoutePoint(200.0, 70.0))),
            listOf(RouteLine((0..32).map { val a = it * Math.PI / 16; RoutePoint(45 * cos(a), 70 + 45 * sin(a)) }),
                line(0 to -120, 0 to 25), line(45 to 70, 220 to 70), line(-200 to 70, -45 to 70))),
        Case("u-turn", line(-12 to -100, -12 to 45, -8 to 58, 0 to 62, 8 to 58, 12 to 45, 12 to -100),
            listOf(line(-12 to -130, -12 to 100), line(12 to -130, 12 to 100))),
        Case("zoom-near", right, cross, 0.6),
        Case("reroute-left", left, cross),
    )
}
