package com.shilapi.xcertplay.projection.carplay

import com.shilapi.display.api.DrivingSide
import com.shilapi.display.api.JunctionKind
import com.shilapi.display.api.ManeuverKind
import com.shilapi.display.api.NavigationJunction
import com.shilapi.display.api.NavigationPhase
import com.shilapi.display.api.NavigationState
import com.shilapi.display.api.NavigationLaneGuidance
import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.iap2.wire.Iap2WireCodec
import java.util.Collections

/** Independent source state: no OEM icon mapping, display timeout or device side effect. */
internal class CarPlayNavigationReducer(private val clock: () -> Long) {
    private data class Maneuver(
        val type: Int?, val side: Int?, val road: String?, val junction: NavigationJunction?,
    )
    private val maneuvers = linkedMapOf<Int, Maneuver>()
    private var activeIndex: Int? = null
    private val laneBlocks = linkedMapOf<Int, NavigationLaneGuidance>()
    private var activeLaneIndex: Int? = null
    private var lanesShowing = false
    var state = NavigationState()
        private set
    var lastFrameValid = false
        private set

    fun accept(frame: Iap2Frame): Boolean {
        lastFrameValid = false
        if (frame.messageId == 0x5204) {
            val block = CarPlayLaneCapture.parse(frame) ?: return false
            lastFrameValid = true
            // A late old-route block cannot seed the next route after an explicit end.
            if (state.phase == NavigationPhase.ENDED || state.phase == NavigationPhase.ARRIVED) return false
            if (laneBlocks.size >= 8 && block.index !in laneBlocks) {
                val oldestUnused = laneBlocks.keys.firstOrNull { it != activeLaneIndex } ?: laneBlocks.keys.first()
                laneBlocks.remove(oldestUnused)
            }
            laneBlocks[block.index] = NavigationLaneGuidance(block.lanes, clock())
            if (block.index != activeLaneIndex || !lanesShowing) return false
            val next = withLanes(state)
            val changed = state != next
            state = next
            return changed
        }
        if (frame.messageId != 0x5201 && frame.messageId != 0x5202) return false
        val before = state
        // Read and validate a complete patch before mutating retained state.
        runCatching {
            val body = Iap2BodyReader.of(frame)
            if (frame.messageId == 0x5202) {
                val index = body.optionalU16(1) ?: return false
                val type = body.optionalU8(3)
                val road = body.optionalString(4)?.trim()?.ifEmpty { null }
                val side = body.optionalU8(8)
                val old = maneuvers[index]
                val junction = readJunction(body, old?.junction)
                if (maneuvers.size >= 128 && index !in maneuvers) {
                    val oldestUnused = maneuvers.keys.firstOrNull { it != activeIndex } ?: maneuvers.keys.first()
                    maneuvers.remove(oldestUnused)
                }
                maneuvers[index] = Maneuver(type ?: old?.type, side ?: old?.side,
                    if (body.has(4)) road else old?.road, junction)
                if (index == activeIndex) state = withManeuver(state)
            } else {
                val phase = body.optionalU8(1)
                val road = body.optionalString(3)?.trim()?.ifEmpty { null }
                val arrival = body.optionalU64(5)?.takeIf { it > 0 && it <= Long.MAX_VALUE / 1000 }?.times(1000)
                val remaining = body.optionalU64(6)?.takeIf { it >= 0 && it <= Long.MAX_VALUE / 1000 }?.times(1000)
                val meters = body.optionalU32(7)
                val distance = body.optionalU32(10)
                val indices = body.optionalU16List(13)
                // Lane-block selection is independent of the maneuver list (field 13).
                val laneIndex = body.optionalU16(16)
                val laneCount = body.optionalU16(17)
                val showing = body.optionalU8(18)
                require(showing == null || showing in 0..1)
                if (phase == 0 || phase == 2) {
                    maneuvers.clear()
                    activeIndex = null
                    laneBlocks.clear(); activeLaneIndex = null; lanesShowing = false
                    state = NavigationState(
                        phase = if (phase == 2) NavigationPhase.ARRIVED else NavigationPhase.ENDED,
                        receivedAtMillis = clock(),
                    )
                } else {
                    val nextIndex = if (body.has(13)) indices?.firstOrNull() else activeIndex
                    val indexChanged = nextIndex != activeIndex
                    if (indexChanged && laneIndex == null) {
                        // Do not carry the previous junction's strip across a sparse maneuver switch.
                        activeLaneIndex = null; lanesShowing = false
                    }
                    if (laneIndex != null) activeLaneIndex = laneIndex.takeUnless { it == 0xFFFF }
                    if (showing != null) lanesShowing = showing == 1
                    if (laneCount == 0) {
                        laneBlocks.clear(); activeLaneIndex = null; lanesShowing = false
                    }
                    activeIndex = nextIndex
                    val nextPhase = if (phase != null || nextIndex != null) NavigationPhase.GUIDING else state.phase
                    state = withLanes(withManeuver(state.copy(
                        phase = nextPhase,
                        currentRoad = if (body.has(3)) road else state.currentRoad,
                        arrivalEpochMillis = if (body.has(5)) arrival else state.arrivalEpochMillis,
                        remainingMillis = if (body.has(6)) remaining else state.remainingMillis,
                        remainingMeters = meters ?: state.remainingMeters,
                        distanceMeters = distance ?: if (indexChanged) null else state.distanceMeters,
                        receivedAtMillis = clock(),
                    )))
                }
            }
        }.getOrElse { return false }
        lastFrameValid = true
        return state != before
    }

    private fun readJunction(body: Iap2BodyReader, old: NavigationJunction?): NavigationJunction? {
        if (!body.has(9) && !body.has(10) && !body.has(11)) return old
        require(body.all(9).size <= 1 && body.all(11).size <= 1)
        val kind = when (body.optionalU8(9)) {
            null -> old?.kind ?: JunctionKind.UNKNOWN
            0 -> JunctionKind.INTERSECTION
            1 -> JunctionKind.ROUNDABOUT
            else -> JunctionKind.UNKNOWN
        }
        val branches = if (body.has(10)) {
            // The bench sends repeated BE i16 TLVs. Also accept packed i16 arrays, retaining
            // order and sign. Missing geometry never turns into a synthetic straight road.
            val angles = ArrayList<Int>()
            for (parameter in body.all(10)) {
                val bytes = parameter.payload
                require(bytes.size % 2 == 0 && bytes.size / 2 <= 32 - angles.size)
                for (offset in bytes.indices step 2) {
                    val angle = Iap2WireCodec.readI16(bytes, offset)
                    require(angle in -180..180)
                    angles.add(angle)
                }
            }
            Collections.unmodifiableList(angles)
        } else old?.branchAnglesDegrees
        val exit = if (body.has(11)) body.optionalI16(11) else old?.exitAngleDegrees
        require(exit == null || exit in -180..180)
        return NavigationJunction(kind, branches, exit)
    }

    private fun withLanes(value: NavigationState): NavigationState = value.copy(
        // Blocks are preloaded static geometry. The current route's genuine showing update
        // renews their display lease even if that block arrived minutes before the junction.
        laneGuidance = if (value.phase == NavigationPhase.GUIDING && lanesShowing)
            laneBlocks[activeLaneIndex]?.copy(receivedAtMillis = clock()) else null,
    )

    private fun withManeuver(value: NavigationState): NavigationState {
        val maneuver = maneuvers[activeIndex]
        val side = when (maneuver?.side) { 0 -> DrivingSide.RIGHT; 1 -> DrivingSide.LEFT; else -> DrivingSide.UNKNOWN }
        val type = maneuver?.type
        val kind = when (type) {
            1, 20 -> ManeuverKind.LEFT
            2, 21 -> ManeuverKind.RIGHT
            47 -> ManeuverKind.SHARP_LEFT
            48 -> ManeuverKind.SHARP_RIGHT
            13, 22, 49, 52 -> ManeuverKind.SLIGHT_LEFT
            14, 23, 50, 53 -> ManeuverKind.SLIGHT_RIGHT
            4, 18, 19, 26 -> when (side) {
                DrivingSide.LEFT -> ManeuverKind.U_TURN_RIGHT
                DrivingSide.RIGHT -> ManeuverKind.U_TURN_LEFT
                else -> ManeuverKind.UNKNOWN
            }
            6 -> ManeuverKind.ROUNDABOUT_ENTER
            7 -> ManeuverKind.ROUNDABOUT_EXIT
            in 28..46 -> ManeuverKind.ROUNDABOUT_NUMBERED
            10, 12, 24, 25, 27 -> ManeuverKind.DESTINATION
            3, 5, 8, 9, 11, 51 -> ManeuverKind.STRAIGHT
            else -> ManeuverKind.UNKNOWN
        }
        return value.copy(maneuver = kind, drivingSide = side,
            roundaboutExit = type?.takeIf { it in 28..46 }?.minus(27), nextRoad = maneuver?.road,
            junction = if (value.phase == NavigationPhase.GUIDING) maneuver?.junction else null)
    }
}
