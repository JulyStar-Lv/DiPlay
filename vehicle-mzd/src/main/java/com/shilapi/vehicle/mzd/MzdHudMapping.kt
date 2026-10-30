package com.shilapi.vehicle.mzd

import com.shilapi.display.api.*
import org.json.JSONObject

internal data class MzdNavigation(val icon: Int, val distance: Int, val remaining: Int, val seconds: Int,
    val lanes: List<Int> = emptyList())
internal data class MzdPhone(val id: Int, val name: String, val number: String, val state: Int, val seconds: Int)

/** Firmware profile from the 2026-10-27 bench. These values are not a universal Mazda ABI. */
internal object MzdHudMapping {
    const val MAX_CALL_SECONDS = 65535
    // Static phone guidance was observed at ~59-second intervals on the 2026-10-29 bench.
    // Keep a bounded lease; terminal phases/session closure still clear immediately.
    const val NAVIGATION_TIMEOUT_MILLIS = 90_000L
    private const val MAX_TURN_METERS = 131_070L
    // RemainInfo uses two Int32 fields, unlike the turn-distance blank sentinel.
    // 1018 km and 9999 km were checked on this receiver, not inferred from IPC success.
    private const val MAX_REMAINING_METERS = 9_999_000L
    private const val MAX_REMAINING_SECONDS = 65_534L
    private const val MUSIC_TEXT_BYTES = 128

    fun navigation(value: NavigationState, now: Long): MzdNavigation? {
        if (navigationBlockReason(value, now) != null) return null
        // This firmware has no proven blank representation for these numeric fields.
        // Wait for a complete numeric snapshot rather than show a fabricated distance/time.
        val distance = value.distanceMeters?.toInt() ?: return null
        val remaining = value.remainingMeters?.toInt() ?: return null
        val seconds = value.remainingMillis?.div(1000)?.toInt() ?: return null
        return MzdNavigation(icon(value), distance, remaining, seconds, MzdLaneMapping.lanes(value.laneGuidance, now))
    }

    /** Same gate used by output and diagnostics; identifiers contain no phone text. */
    fun navigationBlockReason(value: NavigationState, now: Long): String? {
        if (value.phase != NavigationPhase.GUIDING) return "not_guiding"
        if (now - value.receivedAtMillis !in 0..NAVIGATION_TIMEOUT_MILLIS) return "stale"
        val distance = value.distanceMeters ?: return "missing_distance"
        val remaining = value.remainingMeters ?: return "missing_remaining"
        val millis = value.remainingMillis ?: return "missing_time"
        return when {
            distance !in 0..MAX_TURN_METERS -> "distance_range"
            remaining !in 0..MAX_REMAINING_METERS -> "remaining_range"
            millis < 0 || millis / 1000 !in 0..MAX_REMAINING_SECONDS -> "time_range"
            else -> null
        }
    }

    fun icon(value: NavigationState): Int = when (value.maneuver) {
        ManeuverKind.STRAIGHT -> 16
        ManeuverKind.LEFT -> 19
        ManeuverKind.RIGHT -> 20
        ManeuverKind.SLIGHT_LEFT -> 18
        ManeuverKind.SLIGHT_RIGHT -> 17
        ManeuverKind.SHARP_LEFT -> 21
        ManeuverKind.SHARP_RIGHT -> 22
        ManeuverKind.U_TURN_LEFT -> 23
        ManeuverKind.U_TURN_RIGHT -> 24
        ManeuverKind.DESTINATION -> 3
        ManeuverKind.ROUNDABOUT_ENTER, ManeuverKind.ROUNDABOUT_NUMBERED -> {
            val exit = value.roundaboutExit
            when (value.drivingSide) {
                DrivingSide.LEFT -> if (exit != null && exit in 1..10) 73 + exit else 70
                DrivingSide.RIGHT -> if (exit != null && exit in 1..10) 41 + exit else 53
                else -> 0
            }
        }
        ManeuverKind.ROUNDABOUT_EXIT -> when (value.drivingSide) {
            DrivingSide.LEFT -> 68
            DrivingSide.RIGHT -> 63
            else -> 0
        }
        else -> 0
    }

    fun selectedCall(calls: List<CallState>): CallState? = calls.minByOrNull {
        when (it.phase) {
            CallPhase.RINGING -> 0
            CallPhase.ACTIVE -> 1
            CallPhase.HELD -> 2
            CallPhase.DIALING, CallPhase.CONNECTING -> 3
            else -> 4
        }
    }?.takeIf { it.phase != CallPhase.UNKNOWN }

    fun phone(call: CallState, now: Long): MzdPhone = MzdPhone(
        id = (call.id.hashCode() and Int.MAX_VALUE).coerceAtLeast(1),
        name = text(call.name.orEmpty(), 128), number = text(call.number.orEmpty(), 32),
        state = when (call.phase) { CallPhase.RINGING -> 1; CallPhase.DIALING, CallPhase.CONNECTING -> 2; else -> 3 },
        // Despite the JSON name callTimeMs, this tested HUD firmware renders seconds.
        seconds = call.connectedAtMillis?.let { ((now - it).coerceAtLeast(0) / 1000).coerceAtMost(MAX_CALL_SECONDS.toLong()).toInt() } ?: 0,
    )

    fun phoneJson(value: MzdPhone?): String = JSONObject().apply {
        put("contactID", value?.id ?: 0); put("contactName", value?.name.orEmpty())
        put("telNumber", value?.number.orEmpty()); put("lineStatus", value?.state ?: 0)
        put("callTimeMs", value?.seconds ?: 0)
    }.toString()

    fun mediaJson(value: MediaState?, source: MzdMediaSource = MzdMediaSource.TEXT_COMPAT_27): String {
        val title = musicText(value?.title.orEmpty())
        val artist = musicText(value?.artist.orEmpty())
        val lyric = musicText(value?.currentLyric.orEmpty())
        val line = if (value?.lyricsSupport != null && value.lyricsSupport != LyricsSupport.NONE && lyric.isNotBlank()) {
            lyric
        } else mediaLine(title, artist)
        return JSONObject().apply {
            put("sourceID", if (value == null) 0 else 1); put("sourceType", if (value == null) 0 else source.sourceType)
            put("songName", title); put("singerName", artist); put("programName", musicText(value?.album.orEmpty()))
            put("playStatus", if (value?.playback == PlaybackState.PLAYING) 2 else 1)
            put("lyricObj", JSONObject().apply {
                put("sourceID", if (value == null) 0 else 1); put("lyricStatus", if (value == null) 0 else 1)
                put("curLyric", line); put("nextLyric", ""); put("next2Lyric", "")
            })
        }.toString()
    }

    /** This receiver drops the whole music line for supplementary UTF-8 characters.
     * Keep the source snapshot intact; apply its BMP fallback only at the OEM boundary. */
    private fun musicText(value: String): String {
        val compatible = StringBuilder()
        var offset = 0
        while (offset < value.length) {
            val point = value.codePointAt(offset)
            if (point > 0xFFFF || point in 0xD800..0xDFFF) compatible.append(' ')
            else compatible.appendCodePoint(point)
            offset += Character.charCount(point)
        }
        return text(compatible.toString(), MUSIC_TEXT_BYTES).trim()
    }

    private fun mediaLine(title: String, artist: String): String {
        if (title.isBlank()) return artist
        if (artist.isBlank()) return title
        // Reserve part of the single line for the artist instead of truncating it
        // completely when the title fills the fixed-size native lyric field.
        val artistBytes = text(artist, 48).toByteArray(Charsets.UTF_8).size
        val shownTitle = text(title, MUSIC_TEXT_BYTES - 3 - artistBytes).trimEnd()
        val shownArtist = text(artist, MUSIC_TEXT_BYTES - 3 - shownTitle.toByteArray(Charsets.UTF_8).size).trimEnd()
        return "$shownTitle / $shownArtist"
    }

    /** UTF-8 boundary, no split surrogate and no embedded control/newline in a one-line HUD. */
    internal fun text(value: String, maxBytes: Int): String {
        val result = StringBuilder()
        var offset = 0
        var bytes = 0
        while (offset < value.length) {
            val raw = value.codePointAt(offset)
            val point = when {
                Character.isISOControl(raw) -> 32
                raw in 0xD800..0xDFFF -> 0xFFFD
                else -> raw
            }
            val size = when { point < 0x80 -> 1; point < 0x800 -> 2; point < 0x10000 -> 3; else -> 4 }
            if (bytes + size > maxBytes) break
            result.appendCodePoint(point)
            bytes += size
            offset += Character.charCount(raw)
        }
        return result.toString()
    }
}
