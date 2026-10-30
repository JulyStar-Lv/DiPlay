package com.shilapi.display.api

/** No phone protocol, Android object or OEM enum crosses this boundary. Times are monotonic ms. */
data class SourceSessionKey(val sourceInstanceId: String, val epoch: Long)

enum class NavigationPhase { NONE, GUIDING, ARRIVED, ENDED, UNKNOWN }
enum class ManeuverKind {
    UNKNOWN, STRAIGHT, LEFT, RIGHT, SLIGHT_LEFT, SLIGHT_RIGHT, SHARP_LEFT, SHARP_RIGHT,
    U_TURN_LEFT, U_TURN_RIGHT, ROUNDABOUT_ENTER, ROUNDABOUT_EXIT, ROUNDABOUT_NUMBERED, DESTINATION,
}
enum class DrivingSide { UNKNOWN, LEFT, RIGHT }

/** Outgoing bearings relative to straight ahead: positive is right, negative is left.
 * Keep all branches of one physical lane together. No OEM icon or phone status crosses this API. */
data class NavigationLane(
    val index: Int,
    val anglesDegrees: List<Int>,
    val recommended: Boolean = false,
    val highlightedAngleDegrees: Int? = null,
)

data class NavigationLaneGuidance(val lanes: List<NavigationLane>, val receivedAtMillis: Long)

enum class JunctionKind { UNKNOWN, INTERSECTION, ROUNDABOUT }

/** Road bearings relative to straight ahead: positive is right, negative is left.
 * Other branches exclude the selected exit. Null means unavailable; an empty list means no
 * other branches were supplied. This is junction geometry, independent of lane guidance. */
data class NavigationJunction(
    val kind: JunctionKind = JunctionKind.UNKNOWN,
    val branchAnglesDegrees: List<Int>? = null,
    val exitAngleDegrees: Int? = null,
)

data class NavigationState(
    val phase: NavigationPhase = NavigationPhase.NONE,
    val maneuver: ManeuverKind = ManeuverKind.UNKNOWN,
    val drivingSide: DrivingSide = DrivingSide.UNKNOWN,
    val roundaboutExit: Int? = null,
    val distanceMeters: Long? = null,
    val currentRoad: String? = null,
    val nextRoad: String? = null,
    val remainingMeters: Long? = null,
    val remainingMillis: Long? = null,
    val arrivalEpochMillis: Long? = null,
    val receivedAtMillis: Long = 0,
    val laneGuidance: NavigationLaneGuidance? = null,
    val junction: NavigationJunction? = null,
)

enum class PlaybackState { UNKNOWN, PLAYING, PAUSED }
enum class LyricsSupport { NONE, CURRENT_LINE, TIMED_LINES }

data class MediaState(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val playback: PlaybackState = PlaybackState.UNKNOWN,
    val elapsedMillis: Long? = null,
    val durationMillis: Long? = null,
    val lyricsSupport: LyricsSupport = LyricsSupport.NONE,
    val currentLyric: String? = null,
)

enum class CallPhase { UNKNOWN, RINGING, DIALING, CONNECTING, ACTIVE, HELD }

data class CallState(
    val id: String,
    val phase: CallPhase,
    val name: String? = null,
    val number: String? = null,
    val connectedAtMillis: Long? = null,
)

/** Full immutable state; omitted protocol fields must already have been merged by the source. */
data class DisplaySnapshot(
    val session: SourceSessionKey,
    val revision: Long,
    val navigation: NavigationState = NavigationState(),
    val media: MediaState = MediaState(),
    val calls: List<CallState> = emptyList(),
)

data class DisplayCapabilities(
    val navigation: Boolean = false,
    val mediaText: Boolean = false,
    val calls: Boolean = false,
    val artwork: Boolean = false,
    val auxiliaryMap: Boolean = false,
    val callControl: Boolean = false,
    val lanes: Boolean = false,
)

/** submit/close must return promptly; drivers own their workers and clear only their own output. */
interface VehicleDisplayEndpoint : AutoCloseable {
    val capabilities: DisplayCapabilities
    fun submit(snapshot: DisplaySnapshot)
    override fun close()
}

fun interface VehicleDisplayFactory {
    fun open(session: SourceSessionKey): VehicleDisplayEndpoint

    companion object {
        val NONE = VehicleDisplayFactory { NoVehicleDisplay }
    }
}

object NoVehicleDisplay : VehicleDisplayEndpoint {
    override val capabilities = DisplayCapabilities()
    override fun submit(snapshot: DisplaySnapshot) = Unit
    override fun close() = Unit
}
