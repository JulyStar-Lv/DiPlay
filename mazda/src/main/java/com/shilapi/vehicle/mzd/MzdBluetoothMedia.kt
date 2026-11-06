package com.shilapi.vehicle.mzd

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.Parcel

/** Read-only permission for one display handover; never pauses a player or changes a radio. */
internal interface MzdBluetoothMedia : AutoCloseable {
    fun disconnected(): Boolean
}

internal class MzdBluetoothMediaReader(
    private val binder: () -> IBinder?,
    private val radioOff: () -> Boolean,
    private val report: (String) -> Unit = {},
) : MzdBluetoothMedia {
    private var lastDecision: String? = null
    override fun disconnected(): Boolean = try {
        if (!radioOff()) decision("radio not off", false) else {
            val remote = binder()?.takeIf { it.isBinderAlive && it.interfaceDescriptor == DESCRIPTOR }
            if (remote == null) decision("reader unavailable", false) else {
                val source = read(remote, 14)
                val state = read(remote, 15)
                // This firmware resets disconnected BT to Unknown/NONE (0/0). No other
                // unknown-state pair, USB, radio or online source permits handover.
                val inactive = source == 0 && state == 0 || source == 3 && state in setOf(0, 1, 2, 7)
                val stable = inactive && read(remote, 14) == source && read(remote, 15) == state && radioOff()
                decision("source=$source state=$state stable=$stable", stable)
            }
        }
    } catch (error: Exception) { decision("reader ${error.javaClass.simpleName}", false) }

    private fun decision(message: String, allowed: Boolean): Boolean {
        if (lastDecision != message) report("bt media check=$message")
        lastDecision = message
        return allowed
    }
    private fun read(remote: IBinder, code: Int): Int {
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            check(remote.transact(code, data, reply, 0))
            check(reply.dataSize() == 8)
            reply.readException()
            return reply.readInt()
        } finally { reply.recycle(); data.recycle() }
    }
    override fun close() = Unit
    companion object { const val DESCRIPTOR = "com.wt.musicsdk.IMusicOnLineControlInterface" }
}

internal class MzdBluetoothMediaConnection(context: Context, report: (String) -> Unit) : MzdBluetoothMedia {
    private val app = context.applicationContext
    @Volatile private var remote: IBinder? = null
    @Volatile private var closed = false
    private val reader = MzdBluetoothMediaReader({ remote.takeUnless { closed } }, ::radioOff, report)
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) { if (!closed) remote = service }
        override fun onServiceDisconnected(name: ComponentName) { remote = null }
        override fun onBindingDied(name: ComponentName) { remote = null }
        override fun onNullBinding(name: ComponentName) { remote = null }
    }
    private val bound = runCatching {
        app.bindService(Intent().setComponent(ComponentName("com.tinnove.mediacenter",
            "com.tinnove.mediacenter.service.MusicService")), connection, 0)
    }.getOrDefault(false) // Do not start an inactive OEM service.

    private fun radioOff(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.BLUETOOTH
        if (closed || app.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) return false
        return runCatching {
            app.getSystemService(BluetoothManager::class.java)?.adapter?.state == BluetoothAdapter.STATE_OFF
        }.getOrDefault(false)
    }
    override fun disconnected() = reader.disconnected()
    /** Stable OEM wire state for the opt-in A2DP lease; no player is started by this binding. */
    fun nativeMedia(): MzdNativeMedia? {
        val player = remote?.takeIf { !closed && it.isBinderAlive && it.interfaceDescriptor == MzdBluetoothMediaReader.DESCRIPTOR }
            ?: return null
        fun read(code: Int): Int {
            val data = Parcel.obtain(); val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(MzdBluetoothMediaReader.DESCRIPTOR)
                check(player.transact(code, data, reply, 0))
                check(reply.dataSize() == 8)
                reply.readException()
                return reply.readInt()
            } finally { reply.recycle(); data.recycle() }
        }
        val value = MzdNativeMedia(read(14), read(15))
        return value.takeIf { it == MzdNativeMedia(read(14), read(15)) }
    }

    fun pauseQQFallback(): Boolean {
        val state = nativeMedia() ?: return false
        if (state.source != 6 || state.state !in setOf(3, 6)) return false
        val player = remote ?: return false
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(MzdBluetoothMediaReader.DESCRIPTOR)
            check(player.transact(6, data, reply, 0))
            reply.readException()
            return true
        } finally { reply.recycle(); data.recycle() }
    }
    override fun close() {
        closed = true; remote = null
        if (bound) runCatching { app.unbindService(connection) }
    }
}
