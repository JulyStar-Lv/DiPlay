package com.shilapi.vehicle.mzd

/** OEM-only lease. All methods run on the display worker, never the projection callback. */
internal interface MzdMediaHandover {
    fun begin()
    fun prepare(playing: Boolean, noCalls: Boolean, now: Long): Boolean
    fun yield()
    fun end()
    fun recover(now: Long, noCalls: Boolean = true)
}

internal data class MzdNativeMedia(val source: Int, val state: Int)
internal data class MzdBluetoothState(val radioOn: Boolean, val a2dp: Boolean, val hfp: Boolean, val calling: Boolean)

internal interface MzdA2dpPort {
    fun bluetooth(): MzdBluetoothState
    /** Null includes an unavailable service or a source/state change during the read. */
    fun nativeMedia(): MzdNativeMedia?
    fun disconnect()
    fun restore()
    /** Must recheck disconnected A2DP and the expected source immediately before pause. */
    fun pauseAutomaticFallback(): Boolean
}

internal interface MzdA2dpJournal {
    var pending: Boolean
}

/**
 * One separation per source session. A new Binder transport cannot start another attempt.
 * Only the known BT -> Unknown -> QQMusic fallback within the initial settling window may
 * be paused once. User source changes after readiness cause surrender, never focus stealing.
 */
internal class MzdA2dpHandover(
    private val port: MzdA2dpPort,
    private val journal: MzdA2dpJournal,
    private val report: (String) -> Unit = {},
    private val enabled: Boolean = true,
) : MzdMediaHandover {
    private var attempted = false
    private var blocked = false
    private var leased = false
    private var ready = false
    private var startedAt = 0L
    private var stableAt: Long? = null
    private var stable: MzdNativeMedia? = null
    private var pausedFallback = false
    private var startedFromBluetooth = false
    private var waitingReason: String? = null
    private var nextCheckAt = 0L
    private var restoreAt = 0L
    private var restoreFailures = 0

    override fun begin() {
        check(!leased)
        attempted = false; blocked = false; ready = false
        stable = null; stableAt = null; pausedFallback = false; nextCheckAt = 0
        waitingReason = null
    }

    override fun prepare(playing: Boolean, noCalls: Boolean, now: Long): Boolean {
        if (!leased && journal.pending) { recover(now, noCalls); return false }
        if (!enabled) return true
        if (blocked || now < nextCheckAt) return ready
        nextCheckAt = now + if (ready) 1_000 else 250
        try {
            if (!noCalls) return ready // No protocol changes during a call.
            val bt = port.bluetooth()
            if (bt.calling) return ready
            if (!attempted) {
                if (!playing || !bt.radioOn || !bt.hfp || !bt.a2dp) return waiting("playback/profile prerequisites")
                // Do not interrupt another native source, or act on unavailable state.
                val native = port.nativeMedia() ?: return waiting("native reader unavailable")
                if (!(native.source == 3 && native.state in setOf(0, 1, 2, 3, 7) || native == MzdNativeMedia(0, 0)))
                    return waiting("native source=${native.source} state=${native.state}")
                startedFromBluetooth = native.source == 3
                journal.pending = true // Durable BEFORE a possibly partially delivered mutation.
                attempted = true; leased = true; startedAt = now
                port.disconnect()
                report("a2dp separation requested; awaiting actual disconnected state")
                return false
            }
            if (!bt.radioOn || !bt.hfp) { surrender("bluetooth/HFP changed"); return false }
            if (bt.a2dp) {
                if (ready || now - startedAt >= 6_000) surrender("A2DP connected or separation timed out")
                return false
            }
            val native = port.nativeMedia()
            if (native == null) {
                if (ready || now - startedAt >= 6_000) surrender("native state unavailable")
                return false
            }
            val acceptable = native.source == 0 && native.state == 0 ||
                native.source == 3 && native.state in setOf(0, 1, 2, 7) ||
                native.source == 6 && native.state in setOf(0, 1, 2)
            if (ready) {
                if (!acceptable) surrender("native source resumed")
                return ready
            }
            // MusicService forwards Android PlaybackState: 3=playing, 6=buffering,
            // 2=paused. HUD playStatus and internal PlayState use different values.
            if (startedFromBluetooth && native.source == 6 && native.state in setOf(3, 6) && !pausedFallback && now - startedAt <= 3_000) {
                pausedFallback = true
                if (!port.pauseAutomaticFallback()) { surrender("fallback changed before pause"); return false }
                report("a2dp automatic QQMusic fallback paused once")
                stable = null; stableAt = null
                return false
            }
            if (!acceptable) {
                stable = null; stableAt = null
                if (now - startedAt >= 6_000 || native.source !in setOf(0, 3, 6)) surrender("unexpected native source/state")
                return false
            }
            if (stable != native) { stable = native; stableAt = now }
            // Let the native source transition settle BEFORE the single focus request.
            if (now - startedAt >= 2_000 && now - (stableAt ?: now) >= 750) {
                ready = true
                report("a2dp separated; HFP retained; native source=${native.source} state=${native.state}; HUD handover ready")
            }
            return ready
        } catch (error: Exception) {
            surrender("${error.javaClass.simpleName}")
            return false
        }
    }

    override fun yield() = surrender("display focus lost or domain unavailable")

    private fun waiting(reason: String): Boolean {
        if (waitingReason != reason) report("a2dp handover waiting: $reason")
        waitingReason = reason
        return false
    }

    private fun surrender(reason: String) {
        if (!blocked) report("a2dp handover yielded: $reason")
        blocked = true; ready = false; leased = false
        // The next worker tick restores once phone state is known to be idle.
    }

    override fun end() { ready = false; leased = false }

    override fun recover(now: Long, noCalls: Boolean) {
        if (!noCalls || leased || !journal.pending || now < restoreAt) return
        restoreAt = now + 5_000
        try {
            if (port.bluetooth().calling) return
            // Always call connectA2dp: physical reconnection alone does not clear
            // the OEM automatic-reject flag installed by disconnectA2dp.
            port.restore()
            journal.pending = false
            restoreFailures = 0
            report("a2dp restore accepted; automatic connection enabled; physical connection unverified")
        } catch (error: Exception) {
            restoreFailures++
            restoreAt = now + (5_000L shl restoreFailures.coerceAtMost(5))
            report("a2dp restore pending: ${error.javaClass.simpleName}")
        }
    }
}
