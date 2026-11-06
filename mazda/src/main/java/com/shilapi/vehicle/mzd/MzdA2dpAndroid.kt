package com.shilapi.vehicle.mzd

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.Parcel

/** Recovered, descriptor-checked ABI. No shell, hidden API exemption or radio/HFP mutation. */
internal class MzdA2dpAndroid(context: Context, report: (String) -> Unit,
    private val locator: (String) -> IBinder? = { name ->
        Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, name) as? IBinder
    },
) : MzdA2dpPort {
    private val app = context.applicationContext
    private val player = MzdBluetoothMediaConnection(app, report)
    private val services = mutableMapOf<String, IBinder>()

    override fun nativeMedia() = player.nativeMedia()
    override fun bluetooth(): MzdBluetoothState {
        val permission = if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.BLUETOOTH
        check(app.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) { "Bluetooth permission unavailable" }
        val radioOn = app.getSystemService(BluetoothManager::class.java)?.adapter?.state == BluetoothAdapter.STATE_ON
        val state = read(MUSIC, MUSIC_DESCRIPTOR, 1)
        // This OEM returns -1 for an empty connected-device list (also for an
        // unavailable profile). A prior connected state and settling guard are required.
        check(state == -1 || state == 2) { "A2DP state unknown" }
        val a2dp = boolean(MUSIC, MUSIC_DESCRIPTOR, 2)
        check(a2dp == (state == 2)) { "A2DP state changed during read" }
        return MzdBluetoothState(radioOn, a2dp, boolean(PHONE, PHONE_DESCRIPTOR, 27), boolean(PHONE, PHONE_DESCRIPTOR, 26))
    }

    override fun disconnect() = command(54)
    override fun restore() = command(53)
    override fun pauseAutomaticFallback(): Boolean {
        val bt = bluetooth()
        return bt.radioOn && bt.hfp && !bt.a2dp && !bt.calling && player.pauseQQFallback()
    }

    private fun command(code: Int) {
        rpc(BLUETOOTH, BLUETOOTH_DESCRIPTOR, code) { Unit }
    }
    private fun read(service: String, descriptor: String, code: Int): Int = rpc(service, descriptor, code) {
        check(dataAvail() == 4) { "Unexpected OEM reply" }
        readInt()
    }
    private fun boolean(service: String, descriptor: String, code: Int): Boolean = read(service, descriptor, code).let {
        check(it == 0 || it == 1) { "Unexpected OEM boolean" }; it == 1
    }
    private fun <T> rpc(service: String, descriptor: String, code: Int, reader: Parcel.() -> T): T {
        val remote = services[service]?.takeIf { it.isBinderAlive } ?: server(service, descriptor).also { services[service] = it }
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(descriptor)
            check(remote.transact(code, data, reply, 0)) { "Unsupported OEM transaction" }
            reply.readException()
            return reply.reader()
        } catch (error: Exception) {
            services.remove(service)
            throw error
        } finally { reply.recycle(); data.recycle() }
    }
    private fun server(service: String, descriptor: String): IBinder {
        val registry = locator(MzdBinderTransport.REGISTRY) ?: error("OEM registry unavailable")
        check(registry.interfaceDescriptor == MzdBinderTransport.REGISTRY_DESCRIPTOR)
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(MzdBinderTransport.REGISTRY_DESCRIPTOR); data.writeString(service)
            check(registry.transact(6, data, reply, 0))
            reply.readException()
            return requireNotNull(reply.readStrongBinder()).also { check(it.interfaceDescriptor == descriptor) }
        } finally { reply.recycle(); data.recycle() }
    }
    companion object {
        private const val BLUETOOTH = "com.incall.bluetooth.server"
        private const val BLUETOOTH_DESCRIPTOR = "com.incall.serversdk.bluetooth.IBluetoothManager"
        private const val MUSIC = "com.incall.bluetooth.music"
        private const val MUSIC_DESCRIPTOR = "com.incall.serversdk.bluetooth.music.IBluetoothMusicManager"
        private const val PHONE = "com.incall.bluetooth.phone"
        private const val PHONE_DESCRIPTOR = "com.incall.serversdk.bluetooth.phone.IPhoneManager"
    }
}

internal class MzdA2dpPreferences(context: Context) : MzdA2dpJournal {
    // Contains only a recovery bit, no phone identity or media metadata.
    private val preferences = context.applicationContext.getSharedPreferences("mzd-a2dp-handover", Context.MODE_PRIVATE)
    override var pending: Boolean
        get() = preferences.getBoolean("restore-pending", false)
        set(value) { check(preferences.edit().putBoolean("restore-pending", value).commit()) { "A2DP recovery journal failed" } }
}
