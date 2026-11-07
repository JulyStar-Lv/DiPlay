package com.shilapi.xcertplay.mazda.hudbench

import com.shilapi.display.api.*
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.media.CarPlayPlaybackStatus
import com.shilapi.xcertplay.projection.carplay.CarPlayDisplaySource

internal interface HudBenchInput : AutoCloseable {
    fun apply(step: HudBenchStep, origin: Long)
    fun reconnect()
}

/** Observe the exact complete snapshots actually submitted to the real endpoint. */
internal fun observedFactory(factory: VehicleDisplayFactory, record: (DisplaySnapshot) -> Unit) =
    VehicleDisplayFactory { key ->
        val endpoint = factory.open(key)
        object : VehicleDisplayEndpoint by endpoint {
            override fun submit(snapshot: DisplaySnapshot) { record(snapshot); endpoint.submit(snapshot) }
        }
    }

internal class SnapshotBenchInput(
    private val factory: VehicleDisplayFactory,
    private val clock: () -> Long,
    private val instance: String,
) : HudBenchInput {
    private var epoch = 0L
    private var revision = 0L
    private var endpoint: VehicleDisplayEndpoint? = null
    private var key = SourceSessionKey(instance, epoch)
    init { reconnect() }
    override fun apply(step: HudBenchStep, origin: Long) {
        val value = step.expected
        endpoint!!.submit(DisplaySnapshot(key, ++revision,
            value.navigation.copy(receivedAtMillis = clock(),
                laneGuidance = value.navigation.laneGuidance?.copy(receivedAtMillis = clock())), value.media,
            value.calls.map { it.copy(connectedAtMillis = it.connectedAtMillis?.plus(origin)) }))
    }
    override fun reconnect() {
        val old = endpoint
        val staleKey = key
        key = SourceSessionKey(instance, ++epoch)
        revision = 0
        endpoint = factory.open(key)
        // Exercise a delayed close/write from the replaced session, against the real driver's guard.
        old?.close()
        old?.submit(DisplaySnapshot(staleKey, Long.MAX_VALUE, media = MediaState(title = "STALE-OLD", playback = PlaybackState.PLAYING)))
    }
    override fun close() { endpoint?.close(); endpoint = null }
}

internal class ReplayBenchInput(
    private val factory: VehicleDisplayFactory,
    private val clock: () -> Long,
    private val recordFrame: (Iap2Frame) -> Unit,
    private val failure: (String) -> Unit,
) : HudBenchInput {
    private var playback = CarPlayPlaybackStatus()
    private var source = source()
    private fun source() = CarPlayDisplaySource(factory, clock, failure)
    init { source.start() }
    override fun apply(step: HudBenchStep, origin: Long) {
        step.frames.forEach { frame ->
            recordFrame(frame)
            // Same ordering and retained production media parser as CarPlayController.onRouteFrame.
            source.onFrame(frame)
            playback.acceptUpdate(frame)?.let(source::onMedia)
        }
    }
    override fun reconnect() {
        val old = source
        old.close()
        playback = CarPlayPlaybackStatus()
        source = source().also { it.start() }
        HudBenchFixtures.music("STALE-OLD", "Old", true).forEach(old::onFrame)
    }
    override fun close() = source.close()
}
