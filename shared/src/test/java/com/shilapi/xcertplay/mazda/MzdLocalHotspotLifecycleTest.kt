package com.shilapi.xcertplay.mazda

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class MzdLocalHotspotLifecycleTest {
    private val now = AtomicLong()
    private val lifecycle = MzdLocalHotspotLifecycle(now::get)

    @Test fun reuseRequiresAnUnstoppedReservationAndAnEnabledApWhenStateIsAvailable() {
        val ticket = lifecycle.acquire(15_000) { false }
        assertFalse(lifecycle.isActive(ticket))
        lifecycle.begin(ticket)
        lifecycle.stateChanged(13)
        assertFalse(lifecycle.isActive(ticket))
        lifecycle.resolved(ticket)
        assertTrue(lifecycle.isActive(ticket))
        lifecycle.stateChanged(10)
        assertFalse(lifecycle.isActive(ticket))
        lifecycle.stateChanged(13)
        lifecycle.stopped(ticket)
        assertFalse(lifecycle.isActive(ticket))
    }

    @Test fun restartWaitsForDisabledAndSettlingRatherThanReservationClose() {
        val old = lifecycle.acquire(15_000) { false }
        lifecycle.begin(old)
        lifecycle.stateChanged(13)
        lifecycle.resolved(old)
        lifecycle.close(old)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val next = worker.submit<MzdLocalHotspotLifecycle.Ticket> { lifecycle.acquire(15_000) { false } }
            assertThrows(TimeoutException::class.java) { next.get(100, TimeUnit.MILLISECONDS) }
            lifecycle.stateChanged(10)
            now.addAndGet(1_000)
            assertThrows(TimeoutException::class.java) { next.get(100, TimeUnit.MILLISECONDS) }
            lifecycle.stateChanged(11)
            assertThrows(TimeoutException::class.java) { next.get(100, TimeUnit.MILLISECONDS) }
            now.addAndGet(1_000)
            assertNotSame(old, next.get(2, TimeUnit.SECONDS))
        } finally { worker.shutdownNow() }
    }

    @Test fun initialDisabledSnapshotCannotConfirmShutdownOfALaterRequest() {
        lifecycle.stateChanged(11)
        val old = lifecycle.acquire(15_000) { false }
        lifecycle.begin(old)
        lifecycle.resolved(old)
        lifecycle.close(old)
        now.addAndGet(2_000)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val next = worker.submit<MzdLocalHotspotLifecycle.Ticket> { lifecycle.acquire(15_000) { false } }
            assertThrows(TimeoutException::class.java) { next.get(100, TimeUnit.MILLISECONDS) }
            lifecycle.stateChanged(11)
            now.addAndGet(1_000)
            assertNotSame(old, next.get(2, TimeUnit.SECONDS))
        } finally { worker.shutdownNow() }
    }

    @Test fun cancelledPendingRequestMustResolveBeforeAnotherCanStart() {
        val old = lifecycle.acquire(15_000) { false }
        lifecycle.begin(old)
        lifecycle.close(old)
        lifecycle.stateChanged(11)
        now.addAndGet(1_000)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val next = worker.submit<MzdLocalHotspotLifecycle.Ticket> { lifecycle.acquire(15_000) { false } }
            assertThrows(TimeoutException::class.java) { next.get(100, TimeUnit.MILLISECONDS) }
            lifecycle.resolved(old)
            assertNotSame(old, next.get(2, TimeUnit.SECONDS))
        } finally { worker.shutdownNow() }
    }

    @Test fun genericFailureStillWaitsForTheUnownedApToStop() {
        val old = lifecycle.acquire(15_000) { false }
        lifecycle.begin(old)
        lifecycle.stateChanged(11)
        lifecycle.resolved(old, 2)
        lifecycle.close(old)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val next = worker.submit<MzdLocalHotspotLifecycle.Ticket> { lifecycle.acquire(15_000) { false } }
            // The system can bring up a request-less AP after reporting error 2.
            now.addAndGet(500)
            lifecycle.stateChanged(12)
            now.addAndGet(1_000)
            assertThrows(TimeoutException::class.java) { next.get(100, TimeUnit.MILLISECONDS) }
            lifecycle.stateChanged(11)
            now.addAndGet(1_000)
            assertNotSame(old, next.get(2, TimeUnit.SECONDS))
        } finally { worker.shutdownNow() }
    }

    @Test fun absentShutdownConfirmationFailsInsteadOfIssuingANewRequest() {
        val realtime = MzdLocalHotspotLifecycle()
        val old = realtime.acquire(15_000) { false }
        realtime.begin(old)
        realtime.resolved(old)
        realtime.close(old)
        assertThrows(IOException::class.java) { realtime.acquire(1) { false } }
    }

    @Test fun cancellationWhileWaitingDoesNotConsumeTheNextLease() {
        val old = lifecycle.acquire(15_000) { false }
        lifecycle.begin(old)
        lifecycle.resolved(old)
        lifecycle.close(old)
        val cancelled = AtomicBoolean(true)
        assertThrows(IOException::class.java) { lifecycle.acquire(15_000, cancelled::get) }
        lifecycle.stateChanged(11)
        now.addAndGet(1_000)
        assertNotSame(old, lifecycle.acquire(15_000) { false })
    }

    @Test fun rejectedOrUnsubmittedRequestsDoNotWaitForAnApThatWasNeverStarted() {
        val old = lifecycle.acquire(15_000) { false }
        lifecycle.close(old)
        val denied = lifecycle.acquire(15_000) { false }
        lifecycle.begin(denied)
        lifecycle.resolved(denied, 3)
        lifecycle.close(denied)
        assertNotSame(denied, lifecycle.acquire(15_000) { false })
        assertThrows(IOException::class.java) { lifecycle.begin(old) }
    }
}
