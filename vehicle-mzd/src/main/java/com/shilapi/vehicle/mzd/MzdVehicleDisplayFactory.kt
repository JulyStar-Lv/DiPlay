package com.shilapi.vehicle.mzd

import android.content.Context
import com.shilapi.display.api.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicReference

/** One worker owns the physical HUD across source replacements. open/submit/close never call Binder. */
class MzdVehicleDisplayFactory internal constructor(
    private val report: (String) -> Unit,
    private val clock: () -> Long,
    private val transportFactory: (() -> Boolean) -> MzdTransport,
    private val automatic: Boolean,
    private val mediaSource: MzdMediaSource = MzdMediaSource.TEXT_COMPAT_27,
    private val mediaHandover: MzdMediaHandover? = null,
    private val mapOutput: MzdMapGate? = null,
) : VehicleDisplayFactory {
    constructor(ownerPackage: String, report: (String) -> Unit = {}) : this(
        report, { System.nanoTime() / 1_000_000 },
        { owned -> MzdBinderTransport(ownerPackage, owned, report) }, true)

    constructor(context: Context, report: (String) -> Unit = {}) : this(context, MzdMediaSource.TEXT_COMPAT_27, report)

    constructor(context: Context, mediaSource: MzdMediaSource, report: (String) -> Unit = {}) : this(
        report, { System.nanoTime() / 1_000_000 }, { owned ->
            val reader = MzdBluetoothMediaConnection(context, report)
            try { MzdBinderTransport(context.packageName, owned, report, bluetoothMedia = reader) }
            catch (error: Exception) { reader.close(); throw error }
        }, true, mediaSource)

    /** Opt-in for the verified J90K profile; ordinary builds keep the existing behavior. */
    constructor(context: Context, mediaSource: MzdMediaSource, automaticA2dpHandover: Boolean,
        auxiliaryMap: Boolean = false,
        mapContent: com.shilapi.display.android.MapStreamContent = com.shilapi.display.android.MapStreamContent.MAP,
        report: (String) -> Unit = {}) : this(context, mediaSource,
        createHandover(context, automaticA2dpHandover, report),
        if (auxiliaryMap) MzdHudMapOutput(context, report, mapContent) else null, report)

    private constructor(context: Context, mediaSource: MzdMediaSource, handover: MzdMediaHandover?,
        mapOutput: MzdMapGate?,
        report: (String) -> Unit) : this(report, { System.nanoTime() / 1_000_000 }, { owned ->
        val reader = if (handover == null) MzdBluetoothMediaConnection(context, report) else null
        try { MzdBinderTransport(context.packageName, owned, report, bluetoothMedia = reader) }
        catch (error: Exception) { reader?.close(); throw error }
    }, true, mediaSource, handover, mapOutput)

    val auxiliaryMapOutput: com.shilapi.display.android.VehicleMapOutput? get() = mapOutput

    private inner class Endpoint(val session: SourceSessionKey) : VehicleDisplayEndpoint {
        @Volatile var closed = false
        val latest = AtomicReference<DisplaySnapshot?>()
        override val capabilities = DisplayCapabilities(navigation = true, mediaText = true, calls = true,
            auxiliaryMap = mapOutput != null, lanes = true)
        @Synchronized override fun submit(snapshot: DisplaySnapshot) {
            if (closed || requested.get() !== this || snapshot.session != session) return
            if (snapshot.revision <= (latest.get()?.revision ?: -1)) return
            latest.set(snapshot.copy(calls = snapshot.calls.toList(), navigation = snapshot.navigation.copy(
                junction = snapshot.navigation.junction?.let { it.copy(
                    branchAnglesDegrees = it.branchAnglesDegrees?.toList()) },
                laneGuidance = snapshot.navigation.laneGuidance?.let { guidance -> guidance.copy(
                    lanes = guidance.lanes.map { it.copy(anglesDegrees = it.anglesDegrees.toList()) }) })))
        }
        @Synchronized override fun close() {
            closed = true
            synchronized(this@MzdVehicleDisplayFactory) {
                if (requested.compareAndSet(this, null)) mapOutput?.navigationAllowed(false)
            }
        }
    }

    private val requested = AtomicReference<Endpoint?>()
    private var installed: Endpoint? = null // worker only
    private var transport: MzdTransport? = null
    private var clearing = false
    private var failures = 0
    private var retryAt = 0L
    private var navMode = false
    private var navReadyAt = 0L
    private var navLastSentAt = Long.MIN_VALUE
    private var navigation: MzdNavigation? = null
    private var media: String? = null
    private var mediaRestoreAt: Long? = null
    private var mediaLastSentAt = 0L
    private var bluetoothCheckAt = 0L
    private var phone: MzdPhone? = null
    private val focusSeen = mutableSetOf<MzdDomain>()
    private val disabledDomains = mutableSetOf<MzdDomain>()
    private var schedulerStarted = false
    private var worker: ScheduledExecutorService? = null
    private var reportedRevision = -1L
    private var reportedNavBlock: String? = null
    private var automaticMediaFocusSeen = false
    private var automaticMediaRequestedAt: Long? = null

    init { report("media sourceType=${mediaSource.sourceType} automaticA2dpHandover=${mediaHandover != null}") }

    /** Also recover a journal left by process death before a new phone session exists. */
    @Synchronized fun recoverInterruptedMediaHandover() {
        if (mediaHandover != null && automatic) startWorker()
    }

    private fun startWorker() {
        if (schedulerStarted) return
        schedulerStarted = true
        worker = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "diplay-mzd-hud").apply { isDaemon = true } }
        worker!!.scheduleWithFixedDelay({ pump() }, 0, 250, TimeUnit.MILLISECONDS)
    }

    @Synchronized override fun open(session: SourceSessionKey): VehicleDisplayEndpoint {
        val next = Endpoint(session)
        requested.getAndSet(next)?.closed = true
        mapOutput?.navigationAllowed(false)
        if (automatic) startWorker()
        return next
    }

    /** Worker barrier for diagnostics. Completion means calls returned, never that pixels were drawn.
     * Callers may wait on their own diagnostic thread; never wait on the main/protocol thread. */
    @Synchronized fun flush(): CompletableFuture<Unit> {
        val completion = CompletableFuture<Unit>()
        val executor = worker
        if (executor == null) completion.complete(Unit) else executor.execute {
            try { pump(); completion.complete(Unit) }
            catch (error: Exception) { completion.completeExceptionally(error) }
        }
        return completion
    }

    /** Also used by deterministic ownership/focus tests, without wall-clock sleeps. */
    internal fun pump() {
        val wanted = requested.get()?.takeUnless { it.closed }
        if (wanted !== installed) {
            retire()
            if (wanted != null) mediaHandover?.begin()
            installed = wanted
            failures = 0
            retryAt = 0
            disabledDomains.clear()
            reportedRevision = -1
            reportedNavBlock = null
        }
        mediaHandover?.recover(clock(), wanted?.latest?.get()?.calls?.isEmpty() != false)
        val owner = installed ?: return
        val snapshot = owner.latest.get() ?: return
        if (!current(owner)) return
        if (transport == null && (failures >= 3 || clock() < retryAt)) return
        try {
            val client = transport ?: transportFactory {
                installed === owner && (clearing || current(owner))
            }.also { transport = it }
            check(client.alive) { "Binder died" }
            val now = clock()
            val nav = MzdHudMapping.navigation(snapshot.navigation, now)
            if (reportedRevision != snapshot.revision) {
                report("source=${snapshot.session.sourceInstanceId} epoch=${snapshot.session.epoch} revision=${snapshot.revision} " +
                    "navigation=${snapshot.navigation.phase} media=${snapshot.media.playback} calls=${snapshot.calls.size}")
                reportedRevision = snapshot.revision
            }
            val block = MzdHudMapping.navigationBlockReason(snapshot.navigation, now)
            if (block != reportedNavBlock) report("hudnav stage=gate reason=${block ?: "ready"}")
            reportedNavBlock = block
            val song = snapshot.media.takeIf { it.playback != PlaybackState.UNKNOWN &&
                (!it.title.isNullOrBlank() || !it.artist.isNullOrBlank() || !it.currentLyric.isNullOrBlank()) }
            val call = MzdHudMapping.selectedCall(snapshot.calls)?.let { MzdHudMapping.phone(it, now) }
            domain(client, MzdDomain.NAVIGATION) { applyNavigation(client, owner, nav, now) }
            synchronized(this) {
                if (current(owner)) {
                    val allowed = nav != null && navMode && now >= navReadyAt &&
                        MzdDomain.NAVIGATION !in disabledDomains && client.focused(MzdDomain.NAVIGATION)
                    mapOutput?.navigationAllowed(allowed, snapshot.navigation.junction.takeIf { allowed })
                }
            }
            if (!current(owner)) return
            domain(client, MzdDomain.PHONE) { applyPhone(client, owner, call, now) }
            if (!current(owner)) return
            domain(client, MzdDomain.MEDIA) { applyMedia(client, owner, song, now, snapshot.calls.isEmpty()) }
        } catch (error: Exception) {
            report("endpoint unavailable: ${error.javaClass.simpleName}; display unverified")
            retire()
            failures++
            retryAt = clock() + (1000L shl failures.coerceAtMost(4))
        }
    }

    private fun current(owner: Endpoint) = requested.get() === owner && !owner.closed

    private inline fun domain(client: MzdTransport, domain: MzdDomain, action: () -> Unit) {
        if (domain in disabledDomains) return
        try { action() } catch (_: MzdOwnershipLost) {
            // A concurrent focus/session change is not permission to re-request or clear a new owner.
        } catch (error: Exception) {
            if (!client.alive) throw error
            disabledDomains.add(domain)
            if (domain == MzdDomain.MEDIA) mediaHandover?.yield()
            report("domain=$domain disabled: ${error.javaClass.simpleName}")
            try { clearDomain(client, domain) } catch (cleanup: Exception) {
                report("domain=$domain clear failed: ${cleanup.javaClass.simpleName}")
            }
            try { client.release(domain) } catch (cleanup: Exception) {
                report("domain=$domain release failed: ${cleanup.javaClass.simpleName}")
            }
        }
    }

    private fun clearDomain(client: MzdTransport, domain: MzdDomain) {
        if (!client.focused(domain)) return
        when (domain) {
            MzdDomain.NAVIGATION -> if (navMode) {
                client.navigationMode(false)
                // The receiver retains its last TBT value across mode changes. Clear it
                // while leaving navigation so an identical next route is delivered again.
                client.navigation(MzdNavigation(0, 0, 0, 0))
                report("hudnav stage=output action=clear")
            }
            MzdDomain.MEDIA -> if (media != null) client.media(MzdHudMapping.mediaJson(null, mediaSource))
            MzdDomain.PHONE -> if (phone != null) { client.phone(MzdHudMapping.phoneJson(null)); client.callTime(0) }
        }
    }

    private fun ready(client: MzdTransport, domain: MzdDomain): Boolean {
        client.request(domain)
        if (!client.focused(domain)) {
            focusSeen.remove(domain)
            when (domain) {
                MzdDomain.NAVIGATION -> { navMode = false; navigation = null }
                MzdDomain.MEDIA -> media = null
                MzdDomain.PHONE -> phone = null
            }
            return false
        }
        if (focusSeen.add(domain)) when (domain) {
            MzdDomain.NAVIGATION -> { navMode = false; navigation = null }
            MzdDomain.MEDIA -> media = null
            MzdDomain.PHONE -> phone = null
        }
        return true
    }

    private fun applyNavigation(client: MzdTransport, owner: Endpoint, value: MzdNavigation?, now: Long) {
        if (value == null) {
            if (navMode && client.focused(MzdDomain.NAVIGATION) && current(owner)) clearDomain(client, MzdDomain.NAVIGATION)
            client.release(MzdDomain.NAVIGATION)
            navMode = false; navigation = null; focusSeen.remove(MzdDomain.NAVIGATION)
            return
        }
        if (!ready(client, MzdDomain.NAVIGATION) || !current(owner)) return
        if (!navMode) {
            navMode = true // cleanup even if the mode transaction fails after partial delivery
            // Also invalidate a value retained by a previous process/owner before
            // activating the display. Mode changes alone do not reset that cache.
            client.navigation(MzdNavigation(0, 0, 0, 0))
            client.navigationMode(true)
            report("hudnav stage=output action=mode_on")
            navReadyAt = now + 350 // a startup delay alone is not a readiness guarantee
            navLastSentAt = Long.MIN_VALUE
            return
        }
        // Periodic bounded refresh recovers content reset by the asynchronous OEM mode transition.
        if (now >= navReadyAt && (navigation != value || now - navLastSentAt >= 1000)) {
            client.navigation(value)
            report("hudnav stage=lane_output count=${value.lanes.size} icons=${value.lanes.joinToString(",").ifEmpty { "none" }}")
            report("hudnav stage=output action=sent icon=${value.icon} distance=${value.distance} remaining=${value.remaining} seconds=${value.seconds}")
            navigation = value
            navLastSentAt = now
        }
    }

    private fun applyMedia(client: MzdTransport, owner: Endpoint, value: MediaState?, now: Long, noCalls: Boolean) {
        val prepared = mediaHandover?.prepare(value?.playback == PlaybackState.PLAYING, noCalls, now) ?: true
        if (value == null) {
            if (media != null && client.focused(MzdDomain.MEDIA) && current(owner)) client.media(MzdHudMapping.mediaJson(null, mediaSource))
            client.release(MzdDomain.MEDIA)
            media = null; mediaRestoreAt = null; focusSeen.remove(MzdDomain.MEDIA)
            automaticMediaRequestedAt = null; automaticMediaFocusSeen = false
            return
        }
        if (!prepared) {
            if (media != null && client.focused(MzdDomain.MEDIA) && current(owner)) client.media(MzdHudMapping.mediaJson(null, mediaSource))
            client.release(MzdDomain.MEDIA)
            media = null; focusSeen.remove(MzdDomain.MEDIA)
            automaticMediaRequestedAt = null
            return
        }
        mediaRestoreAt?.let { at ->
            // Call removal is asynchronous in the OEM display. Invalidate its lyric
            // cache first, then restore the latest snapshot after the removal settles.
            if (media != null && client.focused(MzdDomain.MEDIA) && current(owner)) client.media(MzdHudMapping.mediaJson(null, mediaSource))
            media = null
            if (now < at) return
            mediaRestoreAt = null
        }
        if (automaticMediaRequestedAt == null) automaticMediaRequestedAt = now
        val focused = ready(client, MzdDomain.MEDIA)
        if (!current(owner)) return
        if (!focused) {
            if (mediaHandover != null && noCalls &&
                (automaticMediaFocusSeen || now - (automaticMediaRequestedAt ?: now) >= 5_000)) mediaHandover.yield()
            if (mediaHandover == null && noCalls && value.playback == PlaybackState.PLAYING && now >= bluetoothCheckAt) {
                bluetoothCheckAt = now + 2_000
                client.handoverDisconnectedBluetooth()
            }
            return // Even an accepted request is not permission to write before its callback.
        }
        automaticMediaFocusSeen = true
        val json = MzdHudMapping.mediaJson(value, mediaSource)
        if (media != json || value.playback == PlaybackState.PLAYING && now - mediaLastSentAt >= 2_000) {
            // Mark attempted before sending: a failed Binder reply may follow a successful mutation.
            media = json
            client.media(json)
            // The receiver updates its online-source timestamp before payload dedup.
            // An identical heartbeat keeps it alive without resetting the marquee.
            mediaLastSentAt = now
        }
    }

    private fun applyPhone(client: MzdTransport, owner: Endpoint, value: MzdPhone?, now: Long) {
        if (value == null) {
            if (phone != null && client.focused(MzdDomain.PHONE) && current(owner)) {
                client.phone(MzdHudMapping.phoneJson(null)); client.callTime(0)
                mediaRestoreAt = now + 500
            }
            client.release(MzdDomain.PHONE)
            phone = null; focusSeen.remove(MzdDomain.PHONE)
            return
        }
        if (!ready(client, MzdDomain.PHONE) || !current(owner)) return
        val before = phone
        phone = value
        if (before?.copy(seconds = value.seconds) != value) client.phone(MzdHudMapping.phoneJson(value))
        if (value.state == 3 && before?.seconds != value.seconds) client.callTime(value.seconds)
    }

    private fun retire() {
        mapOutput?.navigationAllowed(false)
        mediaHandover?.end()
        automaticMediaFocusSeen = false
        automaticMediaRequestedAt = null
        val client = transport ?: return
        clearing = true
        fun cleanup(action: () -> Unit) { try { action() } catch (error: Exception) {
            report("cleanup failed: ${error.javaClass.simpleName}")
        } }
        try {
            MzdDomain.entries.forEach { domain -> cleanup { clearDomain(client, domain) } }
            cleanup { client.close() }
        } finally {
            transport = null; clearing = false; navMode = false; navigation = null
            media = null; mediaRestoreAt = null; mediaLastSentAt = 0
            bluetoothCheckAt = 0
            phone = null; focusSeen.clear()
        }
    }

    companion object {
        private fun createHandover(context: Context, enabled: Boolean, report: (String) -> Unit): MzdMediaHandover? {
            val journal = MzdA2dpPreferences(context)
            return if (enabled || journal.pending) MzdA2dpHandover(MzdA2dpAndroid(context, report), journal, report, enabled) else null
        }
    }
}
