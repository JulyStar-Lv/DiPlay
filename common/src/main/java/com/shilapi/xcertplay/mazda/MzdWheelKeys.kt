package com.shilapi.xcertplay.mazda

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.util.Log
import android.view.KeyEvent
import com.shilapi.xcertplay.CarPlayBackgroundSession
import com.shilapi.xcertplay.CarPlayMediaKeys
import com.shilapi.xcertplay.WheelKeyLog
import com.shilapi.xcertplay.inCall

/** Mazda's voice key never reaches the accessibility filter; subscribe while CarPlay owns keys. */
internal object MzdWheelKeys {
    private val handler = Handler(Looper.getMainLooper())
    private var listener: MzdWheelKeyListener? = null

    fun start(context: Context, session: Any) {
        if (!MzdIntegration.enabled(context)) return
        onMain {
            val current = listener ?: MzdWheelKeyListener(context.applicationContext).also { listener = it }
            current.start(session)
        }
    }

    fun stop() = onMain { listener?.stop() }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == handler.looper) action() else handler.post { action() }
    }
}

/**
 * WUTONG J90K Android 14's CarInput extension, as used by WT_TinnoveCoreService 2.1.1.3.
 * Keep its small Binder contract here instead of bundling the vendor's android.car SDK. These
 * transaction IDs are firmware-specific, not the public input-capture API. Binding/registration
 * may be denied to an ordinary APK; report that failure and leave the car's key handling alone.
 * All state belongs to the main looper; Binder callbacks only enqueue work there.
 */
internal class MzdWheelKeyListener(
    private val context: Context,
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val canRequest: () -> Boolean = {
        CarPlayBackgroundSession.active && !CarPlayBackgroundSession.isStopping() && !inCall(context)
    },
    private val requestSiri: (Any) -> Boolean = { CarPlayMediaKeys.onExternalSiriKey(it) },
) {
    private var owner: Any? = null
    private var connection: ServiceConnection? = null
    private var input: IBinder? = null
    private var callback: IBinder? = null
    private var held: Held? = null

    private class Held(val device: Int, val scan: Int, val downTime: Long) {
        var fired = false
        lateinit var trigger: Runnable
        fun matches(event: KeyEvent) = event.deviceId == device && event.scanCode == scan && event.downTime == downTime
    }

    fun start(session: Any) {
        if (owner === session && connection != null) return
        stop()
        owner = session
        bind()
    }

    fun stop() {
        owner = null
        handler.removeCallbacksAndMessages(null)
        disconnect()
    }

    private fun bind() {
        val next = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (connection !== this || owner == null) return
                try {
                    check(service?.interfaceDescriptor == CAR_DESCRIPTOR) { "Unexpected CarService interface" }
                    val manager = transact(service, CAR_DESCRIPTOR, GET_CAR_SERVICE, {
                        writeString("android.car.input")
                    }) { readStrongBinder() }
                    check(manager?.interfaceDescriptor == INPUT_DESCRIPTOR) { "CarInput extension unavailable" }
                    val listener = object : Binder() {
                        init { attachInterface(null, CALLBACK_DESCRIPTOR) }

                        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                            if (code != IBinder.FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags)
                            data.enforceInterface(CALLBACK_DESCRIPTOR)
                            val event = data.readTypedObject(KeyEvent.CREATOR) ?: return true
                            val display = data.readInt()
                            handler.post {
                                if (callback === this && owner != null) onKey(event, display)
                            }
                            return true
                        }
                    }
                    transact(manager, INPUT_DESCRIPTOR, REGISTER_KEY_LISTENER, { writeStrongBinder(listener) }) {}
                    input = manager
                    callback = listener
                    note("registered voice key 231; longPressMs=$LONG_PRESS_MILLIS")
                } catch (error: Exception) {
                    note("registration failed: ${error.javaClass.simpleName}: ${error.message}", error)
                    disconnect()
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                if (connection !== this) return
                cancelPress()
                input = null
                callback = null
                // Android retains this binding and reconnects it when CarService comes back.
                note("input service disconnected")
            }

            override fun onBindingDied(name: ComponentName?) {
                if (connection !== this) return
                val session = owner ?: return
                disconnect()
                handler.postDelayed({ if (owner === session && connection == null) bind() }, 3_000L)
            }

            override fun onNullBinding(name: ComponentName?) {
                if (connection !== this) return
                note("input service returned no Binder")
                disconnect()
            }
        }
        connection = next
        try {
            val intent = Intent("android.car.ICar").setComponent(ComponentName("com.android.car", "com.android.car.CarService"))
            if (!context.bindService(intent, next, Context.BIND_AUTO_CREATE)) {
                note("CarService binding unavailable")
                disconnect()
            }
        } catch (error: Exception) {
            note("binding failed: ${error.javaClass.simpleName}: ${error.message}", error)
            disconnect()
        }
    }

    private fun disconnect() {
        cancelPress()
        val oldInput = input
        val oldCallback = callback
        val oldConnection = connection
        input = null
        callback = null
        connection = null
        if (oldInput != null && oldCallback != null) runCatching {
            transact(oldInput, INPUT_DESCRIPTOR, UNREGISTER_KEY_LISTENER, { writeStrongBinder(oldCallback) }) {}
        }.onFailure { note("unregister failed: ${it.javaClass.simpleName}") }
        if (oldConnection != null) runCatching { context.unbindService(oldConnection) }
    }

    private fun onKey(event: KeyEvent, display: Int) {
        if (event.keyCode != KeyEvent.KEYCODE_VOICE_ASSIST) return
        if (event.isCanceled) {
            if (held?.matches(event) == true) cancelPress()
            return
        }
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount != 0 || held?.matches(event) == true) return
                cancelPress()
                if (!canRequest()) return
                val session = owner ?: return
                val press = Held(event.deviceId, event.scanCode, event.downTime)
                press.trigger = Runnable {
                    if (held !== press || press.fired) return@Runnable
                    press.fired = true
                    if (canRequest()) note("Siri long press sent=${requestSiri(session)}")
                }
                held = press
                // Count from receipt so a delayed batch containing a short DOWN/UP cannot fire Siri.
                handler.postDelayed(press.trigger, LONG_PRESS_MILLIS)
                note("voice DOWN scan=${event.scanCode} device=${event.deviceId} display=$display")
            }
            KeyEvent.ACTION_UP -> if (held?.matches(event) == true) {
                note("voice UP heldMs=${event.eventTime - event.downTime}")
                cancelPress()
            }
        }
    }

    private fun cancelPress() {
        held?.let { handler.removeCallbacks(it.trigger) }
        held = null
    }

    private inline fun <T> transact(service: IBinder, descriptor: String, code: Int,
        write: Parcel.() -> Unit, read: Parcel.() -> T): T {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(descriptor)
            data.write()
            check(service.transact(code, data, reply, 0)) { "Unsupported $descriptor transaction $code" }
            reply.readException()
            return reply.read()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun note(message: String, error: Throwable? = null) {
        if (error == null) Log.i("DiPlay-MazdaWheel", message) else Log.w("DiPlay-MazdaWheel", message, error)
        WheelKeyLog.note("Mazda wheel: $message")
    }

    private companion object {
        const val LONG_PRESS_MILLIS = 1_000L
        const val CAR_DESCRIPTOR = "android.car.ICar"
        const val INPUT_DESCRIPTOR = "android.car.input.ICarInput"
        const val CALLBACK_DESCRIPTOR = "android.car.input.ICarInputListener"
        const val GET_CAR_SERVICE = 12
        const val REGISTER_KEY_LISTENER = 5
        const val UNREGISTER_KEY_LISTENER = 6
    }
}
