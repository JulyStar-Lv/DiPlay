package com.shilapi.vehicle.mzd

import android.os.Binder
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import java.util.EnumMap
import java.util.concurrent.atomic.AtomicBoolean

internal enum class MzdDomain(val register: Int, val unregister: Int, val request: Int, val abandon: Int,
    val callback: String, val focusCallback: String) {
    NAVIGATION(29, 30, 63, 64, "INavigateCallback", "INaviFocusCallback"),
    MEDIA(40, 41, 61, 62, "IMediaCallback", "IMediaFocusCallback"),
    PHONE(45, 46, 65, 66, "IPhoneCallback", "IPhoneFocusCallback"),
}

internal interface MzdTransport : AutoCloseable {
    val alive: Boolean
    fun request(domain: MzdDomain)
    fun focused(domain: MzdDomain): Boolean
    fun release(domain: MzdDomain)
    fun navigationMode(active: Boolean)
    fun navigation(value: MzdNavigation)
    fun media(json: String)
    fun handoverDisconnectedBluetooth(): Boolean = false
    fun phone(json: String)
    fun callTime(seconds: Int)
}

internal class MzdOwnershipLost : IllegalStateException("Display ownership/focus lost")

/** Recovered Parcel ABI only. No OEM jar, hidden API exemptions, property writes or shell fallback. */
internal class MzdBinderTransport(
    private val ownerPackage: String,
    private val stillOwner: () -> Boolean,
    private val report: (String) -> Unit,
    locator: (String) -> IBinder? = ::locate,
    private val bluetoothMedia: MzdBluetoothMedia? = null,
) : MzdTransport {
    private class Registration(val events: IBinder, val focus: IBinder) {
        var registerAttempted = false
        var requestAttempted = false
        val granted = AtomicBoolean()
        val revoked = AtomicBoolean()
        @Volatile var accepting = false
    }
    private val registrations = EnumMap<MzdDomain, Registration>(MzdDomain::class.java)
    private val dead = AtomicBoolean()
    private var bluetoothHandoverAttempted = false
    private val remote: IBinder
    private val death = IBinder.DeathRecipient {
        dead.set(true)
        synchronized(registrations) { registrations.values.forEach { it.granted.set(false) } }
        report("binder died; display unverified")
    }

    init {
        val registry = requireNotNull(locator(REGISTRY)) { "OEM registry unavailable" }
        check(registry.interfaceDescriptor == REGISTRY_DESCRIPTOR) { "Unexpected registry descriptor" }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(REGISTRY_DESCRIPTOR)
            data.writeString(SERVICE)
            check(registry.transact(6, data, reply, 0)) { "OEM getServer unsupported" }
            reply.readException()
            remote = requireNotNull(reply.readStrongBinder()) { "OEM interactive service unavailable" }
        } finally { reply.recycle(); data.recycle() }
        check(remote.interfaceDescriptor == DESCRIPTOR) { "Unexpected interactive descriptor" }
        remote.linkToDeath(death, 0)
        report("profile=J90K-20261027 connected; ABI/display unverified")
    }

    override val alive: Boolean get() = !dead.get() && remote.isBinderAlive

    override fun request(domain: MzdDomain) {
        if (!stillOwner()) throw MzdOwnershipLost()
        val registration = synchronized(registrations) {
            registrations.getOrPut(domain) {
                Registration(Callback(domain, false), Callback(domain, true))
            }
        }
        if (registration.requestAttempted) return // never loop to steal lost focus
        if (!registration.registerAttempted) {
            registration.registerAttempted = true
            call(domain.register) { writeStrongBinder(registration.events) }
        }
        registration.requestAttempted = true
        registration.accepting = true
        if (!stillOwner()) throw MzdOwnershipLost()
        call(domain.request) { writeString(ownerPackage); writeStrongBinder(registration.focus) }
    }

    override fun focused(domain: MzdDomain): Boolean = alive && synchronized(registrations) {
        registrations[domain]?.granted?.get() == true
    }

    override fun release(domain: MzdDomain) {
        val value = synchronized(registrations) { registrations.remove(domain) } ?: return
        value.accepting = false
        value.granted.set(false)
        var failure: Exception? = null
        if (alive && value.requestAttempted) try {
            call(domain.abandon) { writeString(ownerPackage); writeStrongBinder(value.focus) }
        } catch (error: Exception) { failure = error }
        if (alive && value.registerAttempted) try {
            call(domain.unregister) { writeStrongBinder(value.events) }
        } catch (error: Exception) { if (failure == null) failure = error else failure.addSuppressed(error) }
        failure?.let { throw it }
    }

    override fun navigationMode(active: Boolean) = owned(MzdDomain.NAVIGATION, 22) { writeInt(if (active) 2 else 0) }
    override fun navigation(value: MzdNavigation) {
        owned(MzdDomain.NAVIGATION, 24) { writeInt(value.icon); writeInt(value.distance) }
        owned(MzdDomain.NAVIGATION, 27) { writeInt(value.remaining); writeInt(value.seconds) }
        // Native left strip; send after remaining info, which shares its row on this firmware.
        owned(MzdDomain.NAVIGATION, 25) { writeString(MzdLaneMapping.json(value.lanes)) }
    }
    override fun media(json: String) = owned(MzdDomain.MEDIA, 37) { writeString(json) }
    override fun handoverDisconnectedBluetooth(): Boolean {
        if (!stillOwner()) throw MzdOwnershipLost()
        if (bluetoothHandoverAttempted || focused(MzdDomain.MEDIA) || synchronized(registrations) {
            registrations[MzdDomain.MEDIA]?.revoked?.get() != true
        }) return false
        if (bluetoothMedia?.disconnected() != true) return false
        if (!stillOwner()) throw MzdOwnershipLost()
        bluetoothHandoverAttempted = true
        release(MzdDomain.MEDIA)
        request(MzdDomain.MEDIA)
        report("media disconnected-BT handover requested; awaiting actual focus callback")
        return true
    }
    override fun phone(json: String) = owned(MzdDomain.PHONE, 42) { writeString(json) }
    override fun callTime(seconds: Int) = owned(MzdDomain.PHONE, 43) { writeInt(seconds) }

    private fun owned(domain: MzdDomain, code: Int, writer: Parcel.() -> Unit) {
        if (!stillOwner() || !focused(domain)) throw MzdOwnershipLost()
        call(code, writer)
        if (!stillOwner()) throw MzdOwnershipLost()
    }

    private fun call(code: Int, writer: Parcel.() -> Unit) {
        check(alive) { "OEM Binder unavailable" }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writer()
            check(remote.transact(code, data, reply, 0)) { "Unsupported OEM transaction $code" }
            reply.readException()
            // Never log the JSON/contact/lyric payload.
            report("transaction=$code accepted; display unverified")
        } finally { reply.recycle(); data.recycle() }
    }

    private inner class Callback(private val domain: MzdDomain, private val focus: Boolean) : Binder(), IInterface {
        private val descriptor = CALLBACK_PREFIX + if (focus) domain.focusCallback else domain.callback
        init { attachInterface(this, descriptor) }
        override fun asBinder(): IBinder = this
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) { reply?.writeString(descriptor); return true }
            if (code !in 1..(if (focus) 3 else 2)) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(descriptor)
            if (domain == MzdDomain.MEDIA && code == 2) data.readString() else {
                val value = data.readInt()
                if (focus && code == 3) synchronized(registrations) {
                    registrations[domain]?.takeIf { it.focus === this && it.accepting }?.let {
                        if (it.granted.getAndSet(value == 1) && value != 1) it.revoked.set(true)
                        if (domain == MzdDomain.NAVIGATION) report("hudnav stage=focus granted=${value == 1}")
                    }
                }
                report("callback domain=$domain code=$code value=$value")
            }
            // LVDS requests and phone/media operations are observed only; no control is advertised.
            reply?.writeNoException()
            return true
        }
    }

    override fun close() {
        var failure: Exception? = null
        MzdDomain.entries.forEach { domain -> try { release(domain) } catch (error: Exception) {
            if (failure == null) failure = error else failure?.addSuppressed(error)
        } }
        runCatching { remote.unlinkToDeath(death, 0) }
        bluetoothMedia?.close()
        dead.set(true)
        failure?.let { throw it }
    }

    companion object {
        internal const val REGISTRY = "com.incall.SVR_MNG_SERVICE"
        internal const val REGISTRY_DESCRIPTOR = "com.incall.serversdk.server.ISvrManager"
        internal const val SERVICE = "com.incall.double.INTERACTIVE_SERVICE"
        internal const val DESCRIPTOR = "com.incall.serversdk.interactive.IDouInteractiveManager"
        private const val CALLBACK_PREFIX = "com.incall.serversdk.interactive.callback."

        private fun locate(name: String): IBinder? = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java).invoke(null, name) as? IBinder
    }
}
