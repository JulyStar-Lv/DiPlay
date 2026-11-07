package com.shilapi.xcertplay.mazda

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import android.view.KeyEvent
import com.shilapi.xcertplay.CarPlayBackgroundSession
import com.shilapi.xcertplay.CarPlayMediaKeys
import com.shilapi.xcertplay.WheelKeyLog
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.orchestration.CarPlayController
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

/** Exercise the vendor Binder stream as well as the hold timer; no accessibility event is needed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class MzdWheelKeysTest {
    private val app: Context get() = RuntimeEnvironment.getApplication()
    private val main get() = shadowOf(Looper.getMainLooper())
    private val input = InputService()
    private val car = object : Binder() {
        init { attachInterface(null, "android.car.ICar") }
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != 12) return super.onTransact(code, data, reply, flags)
            data.enforceInterface("android.car.ICar")
            assertEquals("android.car.input", data.readString())
            reply!!.writeNoException()
            reply.writeStrongBinder(input)
            return true
        }
    }
    private val context = object : ContextWrapper(app) {
        val connections = mutableListOf<ServiceConnection>()
        val unbound = mutableListOf<ServiceConnection>()
        var deny = false
        override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean {
            assertEquals(ComponentName("com.android.car", "com.android.car.CarService"), service.component)
            assertEquals("android.car.ICar", service.action)
            connections += conn
            if (deny) throw SecurityException("test binding denied")
            return true
        }
        override fun unbindService(conn: ServiceConnection) { unbound += conn }
    }
    private var ready = true
    private var requests = 0
    private val listener = MzdWheelKeyListener(context, canRequest = { ready }, requestSiri = { requests++; true })
    private var downTime = 0L

    @Before fun setUp() { WheelKeyLog.reset() }

    @After fun tearDown() {
        listener.stop()
        CarPlayBackgroundSession.clear()
        main.idle()
        WheelKeyLog.reset()
    }

    private fun connect(owner: Any = Any()): IBinder {
        listener.start(owner)
        context.connections.last().onServiceConnected(null, car)
        return input.callback.also { assertNotNull(it) }!!
    }

    private fun down(target: IBinder = input.callback!!, code: Int = 231, repeat: Int = 0) {
        if (repeat == 0) downTime = SystemClock.uptimeMillis()
        send(target, KeyEvent.ACTION_DOWN, code, repeat = repeat)
    }

    private fun send(target: IBinder = input.callback!!, action: Int, code: Int = 231,
        repeat: Int = 0, flags: Int = 0, device: Int = 2) {
        val event = KeyEvent(downTime, SystemClock.uptimeMillis(), action, code, repeat, 0, device, 47, flags)
        val parcel = Parcel.obtain()
        try {
            parcel.writeInterfaceToken("android.car.input.ICarInputListener")
            parcel.writeTypedObject(event, 0)
            parcel.writeInt(0)
            assertTrue(target.transact(1, parcel, null, IBinder.FLAG_ONEWAY))
        } finally {
            parcel.recycle()
        }
        main.idle()
    }

    private fun advance(millis: Long) = main.idleFor(Duration.ofMillis(millis))

    @Test fun shortPressLeavesSiriAloneAndHoldFiresAtOneSecondOnlyOnce() {
        connect()
        down()
        advance(210)
        send(action = KeyEvent.ACTION_UP)
        advance(2_000)
        assertEquals(0, requests)

        down()
        advance(999)
        assertEquals(0, requests)
        advance(1)
        assertEquals(1, requests)
        advance(4_670)
        send(action = KeyEvent.ACTION_UP)
        assertEquals(1, requests)
        assertEquals(1, input.registrations)
    }

    @Test fun repeatsAndDuplicateDownDoNotRestartTimerOrFireAgain() {
        connect()
        down()
        advance(400)
        send(action = KeyEvent.ACTION_DOWN) // Firmware may repeat without setting repeatCount.
        advance(400)
        send(action = KeyEvent.ACTION_DOWN, repeat = 1)
        advance(200)
        assertEquals(1, requests)
        send(action = KeyEvent.ACTION_DOWN)
        advance(2_000)
        assertEquals(1, requests)
        send(action = KeyEvent.ACTION_UP)
        down()
        advance(1_000)
        assertEquals(2, requests)
    }

    @Test fun canceledPressAndOtherKeysCannotOpenSiri() {
        connect()
        down(code = KeyEvent.KEYCODE_MEDIA_NEXT)
        advance(1_200)
        assertEquals(0, requests)
        down()
        advance(500)
        send(action = KeyEvent.ACTION_UP, flags = KeyEvent.FLAG_CANCELED)
        advance(1_000)
        assertEquals(0, requests)
    }

    @Test fun onlyMatchingReleaseCancelsAndSameSessionReattachKeepsHold() {
        val owner = Any()
        connect(owner)
        down()
        advance(500)
        listener.start(owner)
        send(action = KeyEvent.ACTION_UP, device = 9)
        advance(500)
        assertEquals(1, requests)
        assertEquals(1, context.connections.size)
    }

    @Test fun stoppingUnregistersAndIgnoresLateKeyCallbacks() {
        val old = connect()
        down()
        advance(500)
        listener.stop()
        assertEquals(1, input.unregistrations)
        assertEquals(1, context.unbound.size)
        down(old)
        advance(2_000)
        assertEquals(0, requests)
    }

    @Test fun replacementSessionCancelsOldHoldAndOldBinderCannotControlNewSession() {
        val old = connect()
        down()
        advance(600)
        connect()
        down(old)
        advance(1_500)
        assertEquals(0, requests)
        down()
        advance(1_000)
        assertEquals(1, requests)
        assertEquals(1, input.unregistrations)
    }

    @Test fun disconnectCancelsHoldAndReconnectRegistersFreshCallback() {
        val old = connect()
        down()
        advance(500)
        context.connections.last().onServiceDisconnected(null)
        advance(2_000)
        assertEquals(0, requests)
        context.connections.last().onServiceConnected(null, car)
        down(old)
        advance(1_000)
        assertEquals(0, requests)
        down()
        advance(1_000)
        assertEquals(1, requests)
    }

    @Test fun bindingDeathRebindsButStoppingCancelsRetry() {
        connect()
        context.connections.last().onBindingDied(null)
        advance(3_000)
        assertEquals(2, context.connections.size)
        context.connections.last().onServiceConnected(null, car)
        context.connections.last().onBindingDied(null)
        listener.stop()
        advance(4_000)
        assertEquals(2, context.connections.size)
    }

    @Test fun inactiveSessionOrCallSuppressesBothNewAndPendingHolds() {
        connect()
        ready = false
        down()
        advance(1_000)
        assertEquals(0, requests)
        ready = true
        down()
        advance(500)
        ready = false
        advance(1_000)
        assertEquals(0, requests)
    }

    @Test fun delayedShortPressBatchDoesNotBecomeALongPress() {
        connect()
        downTime = SystemClock.uptimeMillis()
        advance(2_000)
        send(action = KeyEvent.ACTION_DOWN)
        send(action = KeyEvent.ACTION_UP)
        advance(2_000)
        assertEquals(0, requests)
    }

    @Test fun permissionFailuresAreDiagnosticAndDoNotCrashOrRequestSiri() {
        context.deny = true
        listener.start(Any())
        assertTrue(WheelKeyLog.report(false).any { "binding failed: SecurityException" in it })
        listener.stop()
        context.deny = false
        input.deny = true
        listener.start(Any())
        context.connections.last().onServiceConnected(null, car)
        advance(2_000)
        assertEquals(0, requests)
        assertTrue(WheelKeyLog.report(false).any { "registration failed: SecurityException" in it })
    }

    @Test fun unsupportedFirmwareAndLateServiceConnectionDoNotRegister() {
        listener.start(Any())
        val old = context.connections.last()
        listener.start(Any())
        old.onServiceConnected(null, car)
        assertEquals(0, input.registrations)
        context.connections.last().onServiceConnected(null, Binder())
        assertEquals(0, input.registrations)
        assertTrue(WheelKeyLog.report(false).any { "Unexpected CarService interface" in it })
    }

    @Test fun holdUsesExistingExternalSiriEntryWithoutOpeningAnActivity() {
        val controller = mock(CarPlayController::class.java)
        `when`(controller.nowPlayingSnapshot()).thenReturn(CarPlayNowPlaying())
        `when`(controller.requestSiri()).thenReturn(true)
        CarPlayMediaKeys.attach(app, controller)
        CarPlayBackgroundSession.active = true
        val realEntry = MzdWheelKeyListener(context)
        try {
            realEntry.start(controller)
            context.connections.last().onServiceConnected(null, car)
            assertFalse(CarPlayMediaKeys.onExternalSiriKey(Any()))
            down()
            advance(1_000)
            verify(controller, times(1)).requestSiri()
        } finally {
            realEntry.stop()
            CarPlayMediaKeys.detach(controller)
        }
    }

    private class InputService : Binder() {
        var callback: IBinder? = null
        var registrations = 0
        var unregistrations = 0
        var deny = false
        init { attachInterface(null, "android.car.input.ICarInput") }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != 5 && code != 6) return super.onTransact(code, data, reply, flags)
            data.enforceInterface("android.car.input.ICarInput")
            val listener = data.readStrongBinder()
            if (code == 5) {
                if (deny) throw SecurityException("test registration denied")
                callback = listener
                registrations++
            } else {
                assertSame(callback, listener)
                callback = null
                unregistrations++
            }
            reply!!.writeNoException()
            return true
        }
    }
}
