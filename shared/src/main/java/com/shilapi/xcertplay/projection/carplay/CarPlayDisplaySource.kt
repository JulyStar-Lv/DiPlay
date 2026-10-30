package com.shilapi.xcertplay.projection.carplay

import com.shilapi.display.api.DisplaySnapshot
import com.shilapi.display.api.MediaState
import com.shilapi.display.api.NoVehicleDisplay
import com.shilapi.display.api.PlaybackState
import com.shilapi.display.api.SourceSessionKey
import com.shilapi.display.api.VehicleDisplayEndpoint
import com.shilapi.display.api.VehicleDisplayFactory
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import java.util.UUID

/** Optional data observer. Never takes over the Controller's existing MediaSession listeners. */
class CarPlayDisplaySource(
    private val factory: VehicleDisplayFactory,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val onFailure: (String) -> Unit = {},
    private val onNavigationDiagnostic: (String) -> Unit = {},
) : AutoCloseable {
    private val instance = "carplay-${UUID.randomUUID()}"
    private var epoch = 0L
    private var revision = 0L
    private var endpoint: VehicleDisplayEndpoint? = null
    private var navigation = CarPlayNavigationReducer(clock)
    private var calls = CarPlayCallsReducer(clock)
    private var media = MediaState()
    private var playbackKnown = false
    private var accepting = true // retain the first iAP2 metadata arriving before AirPlay activation
    private var closed = false

    @Synchronized fun start() {
        if (closed) return
        if (endpoint != null) { end(); reset() }
        accepting = true
        epoch++
        revision = 0
        endpoint = guarded { factory.open(key()) } ?: NoVehicleDisplay
        diagnostic("stage=session action=start")
        publish()
    }

    @Synchronized fun onFrame(frame: Iap2Frame) {
        if (closed || !accepting) return
        if (frame.messageId == 0x5204) {
            diagnostic(CarPlayLaneCapture.diagnostic(frame))
        }
        if (frame.messageId == 0x5001) {
            val status = runCatching { Iap2BodyReader.of(frame).optionalGroup(1)?.optionalU8(0) }.getOrNull()
            if (status != null) {
                playbackKnown = true
                val playback = if (status == 1) PlaybackState.PLAYING else PlaybackState.PAUSED
                if (media.playback != playback) { media = media.copy(playback = playback); publish() }
            }
        }
        val changed = navigation.accept(frame)
        if (frame.messageId == 0x5201 || frame.messageId == 0x5202) {
            val state = navigation.state
            // Deliberate whitelist: no roads, destination, arrival clock, coordinates or raw body.
            diagnostic("stage=source frame=${frame.messageId.toString(16)} valid=${navigation.lastFrameValid} " +
                "changed=$changed active=${endpoint != null} phase=${state.phase} maneuver=${state.maneuver} " +
                "distance=${state.distanceMeters} remaining=${state.remainingMeters} seconds=${state.remainingMillis?.div(1000)} " +
                "junction=${state.junction?.kind} junctionBranches=${state.junction?.branchAnglesDegrees?.size} " +
                "junctionExit=${state.junction?.exitAngleDegrees}")
        }
        if (changed || calls.accept(frame)) publish()
    }

    @Synchronized fun onMedia(value: CarPlayNowPlaying) {
        if (closed || !accepting) return
        if (value.playing) playbackKnown = true
        media = MediaState(value.title, value.artist, value.album,
            if (value.playing) PlaybackState.PLAYING else if (playbackKnown) PlaybackState.PAUSED else PlaybackState.UNKNOWN,
            value.elapsedMillis, value.durationMillis)
        // CarPlay metadata currently has no distinct lyrics field. Never invent one from title.
        publish()
    }

    @Synchronized fun end() {
        if (endpoint != null) diagnostic("stage=session action=end")
        accepting = false
        val owned = endpoint
        endpoint = null
        guarded { owned?.close() }
        reset()
    }

    @Synchronized override fun close() {
        closed = true
        end()
    }

    private fun reset() {
        navigation = CarPlayNavigationReducer(clock)
        calls = CarPlayCallsReducer(clock)
        media = MediaState()
        playbackKnown = false
    }

    private fun key() = SourceSessionKey(instance, epoch)
    private fun diagnostic(event: String) {
        // An optional diagnostic sink must never interrupt phone ingress or endpoint cleanup.
        try { onNavigationDiagnostic("hudnav $event session=$instance epoch=$epoch") } catch (_: Exception) { }
    }
    private fun publish() {
        val target = endpoint ?: return
        val snapshot = DisplaySnapshot(key(), ++revision, navigation.state, media, calls.snapshot())
        guarded { target.submit(snapshot) }
    }

    private inline fun <T> guarded(action: () -> T): T? = try { action() } catch (failure: Exception) {
        // Failure class only: vendor/phone exception messages may contain personal data.
        onFailure(failure.javaClass.simpleName)
        null
    }
}
