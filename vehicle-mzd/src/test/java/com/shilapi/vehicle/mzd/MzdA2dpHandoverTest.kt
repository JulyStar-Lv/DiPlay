package com.shilapi.vehicle.mzd

import org.junit.Assert.*
import org.junit.Test

class MzdA2dpHandoverTest {
    private class Journal : MzdA2dpJournal { override var pending = false }
    private class Port : MzdA2dpPort {
        var bt = MzdBluetoothState(true, true, true, false)
        var native: MzdNativeMedia? = MzdNativeMedia(3, 3)
        var disconnects = 0
        var restores = 0
        var pauses = 0
        var onDisconnect: () -> Unit = {}
        var onRestore: () -> Unit = {}
        var pauseResult = true
        override fun bluetooth() = bt
        override fun nativeMedia() = native
        override fun disconnect() { disconnects++; onDisconnect() }
        override fun restore() { restores++; onRestore() }
        override fun pauseAutomaticFallback(): Boolean { pauses++; if (pauseResult) native = MzdNativeMedia(6, 2); return pauseResult }
    }
    private val port = Port()
    private val journal = Journal()
    private val handover = MzdA2dpHandover(port, journal)
    private fun tick(now: Long, playing: Boolean = true, noCalls: Boolean = true) = handover.prepare(playing, noCalls, now)
    private fun ready() {
        handover.begin(); assertFalse(tick(0))
        port.bt = port.bt.copy(a2dp = false); port.native = MzdNativeMedia(0, 0)
        assertFalse(tick(1_000)); assertTrue(tick(2_000))
    }

    @Test fun onlyRealPlayingWithIdlePhoneConnectedProfilesAndStableBtSourceCanSeparate() {
        handover.begin()
        assertFalse(tick(0, playing = false)); assertFalse(tick(250, noCalls = false))
        port.bt = port.bt.copy(calling = true); assertFalse(tick(500))
        port.bt = port.bt.copy(calling = false, hfp = false); assertFalse(tick(750))
        port.bt = port.bt.copy(hfp = true, radioOn = false); assertFalse(tick(1_000))
        port.bt = port.bt.copy(radioOn = true, a2dp = false); assertFalse(tick(1_250))
        port.bt = port.bt.copy(a2dp = true)
        port.native = null; assertFalse(tick(1_500))
        port.native = MzdNativeMedia(6, 3); assertFalse(tick(1_750))
        assertEquals(0, port.disconnects); assertFalse(journal.pending)
        port.native = MzdNativeMedia(3, 3)
        assertFalse(tick(2_000)); assertEquals(1, port.disconnects); assertTrue(journal.pending)
    }

    @Test fun waitsForPhysicalDisconnectAndSettledSourceBeforeFocusCanBeRequested() {
        handover.begin(); tick(0)
        assertFalse(tick(2_000))
        port.bt = port.bt.copy(a2dp = false); port.native = MzdNativeMedia(0, 0)
        assertFalse(tick(2_250)); assertFalse(tick(2_750)); assertTrue(tick(3_000))
        repeat(10) { assertTrue(tick(4_000 + it * 1_000L)) }
        assertEquals(1, port.disconnects); assertEquals(0, port.restores)
    }

    @Test fun knownEarlyQqFallbackIsPausedOnceAndMustThenActuallySettle() {
        handover.begin(); tick(0)
        port.bt = port.bt.copy(a2dp = false); port.native = MzdNativeMedia(6, 3)
        assertFalse(tick(500)); assertEquals(1, port.pauses)
        assertFalse(tick(750)); assertTrue(tick(2_000))
        port.native = MzdNativeMedia(6, 3)
        assertFalse(tick(3_000)); assertFalse(tick(4_000))
        assertEquals(1, port.pauses); assertEquals(1, port.disconnects)
        assertEquals(1, port.restores); assertFalse(journal.pending)
        assertFalse(tick(10_000)) // No retry in this source session.
    }

    @Test fun otherSourcesLateFallbackAndFailedRecheckAreNeverPaused() {
        handover.begin(); tick(0); port.bt = port.bt.copy(a2dp = false)
        port.native = MzdNativeMedia(5, 2)
        assertFalse(tick(500)); assertEquals(0, port.pauses)
        val late = MzdA2dpHandover(port, Journal())
        port.bt = port.bt.copy(a2dp = true); port.native = MzdNativeMedia(3, 3)
        late.begin(); late.prepare(true, true, 0)
        port.bt = port.bt.copy(a2dp = false); port.native = MzdNativeMedia(6, 3)
        assertFalse(late.prepare(true, true, 4_000)); assertEquals(0, port.pauses)
        val raced = MzdA2dpHandover(port, Journal())
        port.bt = port.bt.copy(a2dp = true); port.native = MzdNativeMedia(3, 3)
        raced.begin(); raced.prepare(true, true, 0)
        port.bt = port.bt.copy(a2dp = false); port.native = MzdNativeMedia(6, 3); port.pauseResult = false
        assertFalse(raced.prepare(true, true, 500)); assertFalse(raced.prepare(true, true, 750))
        assertEquals(1, port.pauses)
    }

    @Test fun pauseAndPhoneDoNotBounceA2dpAndEndRestoresEvenIfPhysicallyReconnected() {
        ready()
        assertTrue(tick(3_000, playing = false))
        port.bt = port.bt.copy(calling = true)
        assertTrue(tick(4_000, noCalls = false))
        handover.end(); handover.recover(5_000, false); handover.recover(6_000)
        assertEquals(0, port.restores); assertTrue(journal.pending)
        port.bt = port.bt.copy(calling = false, a2dp = true)
        handover.recover(11_000)
        assertEquals(1, port.restores); assertFalse(journal.pending)
    }

    @Test fun focusLossYieldsAndTransportRecreationCannotRestartSeparation() {
        ready(); handover.yield()
        assertFalse(tick(3_000)); assertFalse(tick(10_000))
        assertEquals(1, port.restores); assertEquals(1, port.disconnects)
        handover.end(); handover.begin()
        port.bt = port.bt.copy(a2dp = true); port.native = MzdNativeMedia(3, 3)
        assertFalse(tick(11_000)); assertEquals(2, port.disconnects)
    }

    @Test fun failedOrPartiallyDeliveredDisconnectLeavesDurableRecoveryAndNoSecondDisconnect() {
        port.onDisconnect = { check(journal.pending); throw IllegalStateException() }
        handover.begin(); assertFalse(tick(0)); assertTrue(journal.pending)
        assertFalse(tick(1_000)); assertFalse(journal.pending)
        assertFalse(tick(10_000)); assertEquals(1, port.disconnects); assertEquals(1, port.restores)
    }

    @Test fun restartRecoveryPrecedesNewLeaseAndRestorationFailuresBackOffWithoutClearingJournal() {
        journal.pending = true
        port.onRestore = { throw IllegalStateException() }
        handover.begin(); assertFalse(tick(0)); assertEquals(1, port.restores)
        repeat(10) { assertFalse(tick(1_000 + it * 100L)) }
        assertEquals(1, port.restores); assertTrue(journal.pending); assertEquals(0, port.disconnects)
        port.onRestore = {}
        assertFalse(tick(10_000)); assertFalse(journal.pending); assertEquals(2, port.restores)
        assertFalse(tick(10_250)); assertEquals(1, port.disconnects)
    }

    @Test fun journalFailurePreventsAnyDisconnect() {
        val failing = object : MzdA2dpJournal {
            override var pending: Boolean
                get() = false
                set(value) { throw IllegalStateException() }
        }
        val safe = MzdA2dpHandover(port, failing)
        safe.begin(); assertFalse(safe.prepare(true, true, 0)); assertEquals(0, port.disconnects)
    }

    @Test fun unexpectedProfileLossYieldsWithoutMutatingHfpOrRadio() {
        ready(); port.bt = port.bt.copy(hfp = false)
        assertFalse(tick(3_000)); assertFalse(tick(4_000))
        assertEquals(1, port.disconnects); assertEquals(1, port.restores)
        assertFalse(port.bt.hfp); assertTrue(port.bt.radioOn)
    }

    @Test fun disabledBuildStillRecoversJournalFromPreviousInstallationButNeverSeparates() {
        journal.pending = true
        val disabled = MzdA2dpHandover(port, journal, enabled = false)
        disabled.begin()
        assertFalse(disabled.prepare(true, true, 0))
        assertFalse(journal.pending); assertEquals(1, port.restores)
        assertTrue(disabled.prepare(true, true, 1_000))
        assertEquals(0, port.disconnects)
    }
}
