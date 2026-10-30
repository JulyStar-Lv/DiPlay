package com.shilapi.vehicle.mzd

import com.shilapi.display.api.NavigationLane
import com.shilapi.display.api.NavigationLaneGuidance
import org.json.JSONObject

/** J90K left-strip icons, checked against CommonLaneLine + t8.a.b and the 2026-10-27 atlas.
 * A whole row is accepted or hidden: never drop an unsupported lane and shift its neighbours. */
internal object MzdLaneMapping {
    private enum class Direction { STRAIGHT, LEFT, RIGHT, U_LEFT, U_RIGHT }
    private data class Icons(val background: Int, val foreground: Map<Direction, Int>)
    private fun icons(background: Int, vararg foreground: Pair<Direction, Int>) = Icons(background, mapOf(*foreground))
    private val table = mapOf(
        setOf(Direction.STRAIGHT) to icons(0, Direction.STRAIGHT to 15),
        setOf(Direction.LEFT) to icons(1, Direction.LEFT to 16),
        setOf(Direction.RIGHT) to icons(3, Direction.RIGHT to 18),
        setOf(Direction.U_LEFT) to icons(5, Direction.U_LEFT to 20),
        setOf(Direction.U_RIGHT) to icons(8, Direction.U_RIGHT to 23),
        setOf(Direction.STRAIGHT, Direction.LEFT) to icons(2, Direction.STRAIGHT to 30, Direction.LEFT to 31),
        setOf(Direction.STRAIGHT, Direction.RIGHT) to icons(4, Direction.STRAIGHT to 32, Direction.RIGHT to 33),
        setOf(Direction.LEFT, Direction.RIGHT) to icons(6, Direction.LEFT to 34, Direction.RIGHT to 35),
        setOf(Direction.STRAIGHT, Direction.LEFT, Direction.RIGHT) to icons(7,
            Direction.STRAIGHT to 36, Direction.LEFT to 37, Direction.RIGHT to 38),
        setOf(Direction.STRAIGHT, Direction.U_LEFT) to icons(9, Direction.STRAIGHT to 39, Direction.U_LEFT to 40),
        setOf(Direction.STRAIGHT, Direction.U_RIGHT) to icons(10, Direction.STRAIGHT to 41, Direction.U_RIGHT to 42),
        setOf(Direction.LEFT, Direction.U_LEFT) to icons(11, Direction.LEFT to 43, Direction.U_LEFT to 44),
        setOf(Direction.RIGHT, Direction.U_RIGHT) to icons(12, Direction.RIGHT to 45, Direction.U_RIGHT to 46),
        setOf(Direction.RIGHT, Direction.U_LEFT) to icons(50, Direction.RIGHT to 74, Direction.U_LEFT to 75),
        setOf(Direction.STRAIGHT, Direction.LEFT, Direction.U_LEFT) to icons(49,
            Direction.STRAIGHT to 71, Direction.LEFT to 72, Direction.U_LEFT to 73),
        setOf(Direction.LEFT, Direction.U_RIGHT) to icons(53, Direction.LEFT to 82, Direction.U_RIGHT to 83),
        setOf(Direction.LEFT, Direction.RIGHT, Direction.U_LEFT) to icons(51,
            Direction.LEFT to 76, Direction.RIGHT to 77, Direction.U_LEFT to 78),
        setOf(Direction.STRAIGHT, Direction.RIGHT, Direction.U_LEFT) to icons(59,
            Direction.STRAIGHT to 79, Direction.RIGHT to 80, Direction.U_LEFT to 81),
    )

    fun lanes(value: NavigationLaneGuidance?, now: Long): List<Int> {
        if (value == null || now - value.receivedAtMillis !in 0..MzdHudMapping.NAVIGATION_TIMEOUT_MILLIS) return emptyList()
        if (value.lanes.size !in 1..8) return emptyList()
        val sorted = value.lanes.sortedBy { it.index }
        if (sorted.map { it.index } != sorted.indices.toList()) return emptyList()
        return sorted.map { lane -> icon(lane) ?: return emptyList() }
    }

    private fun icon(lane: NavigationLane): Int? {
        if (lane.anglesDegrees.size !in 1..8) return null
        // The native atlas has cardinal arrows only. Do not invent a turn or choose an
        // arbitrary branch for an angle this firmware cannot accurately represent.
        val base = lane.anglesDegrees.map { direction(it) ?: return null }.toSet()
        val highlighted = lane.highlightedAngleDegrees?.let { direction(it) ?: return null }
        val shape = table[base + listOfNotNull(highlighted)] ?: return null
        if (!lane.recommended) return shape.background
        val selected = highlighted ?: base.singleOrNull() ?: return shape.background
        return shape.foreground[selected]
    }

    private fun direction(degrees: Int): Direction? = when (degrees) {
        0 -> Direction.STRAIGHT
        -90 -> Direction.LEFT
        90 -> Direction.RIGHT
        -180 -> Direction.U_LEFT
        180 -> Direction.U_RIGHT
        else -> null
    }

    fun json(lanes: List<Int>): String = JSONObject().apply {
        require(lanes.size <= 8)
        put("laneNum", lanes.size)
        lanes.forEachIndexed { index, icon -> put("lane${index + 1}", icon) }
    }.toString()
}
