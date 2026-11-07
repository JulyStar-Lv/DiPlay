package com.shilapi.vehicle.mzd

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.os.Binder
import android.os.IInterface
import android.os.Parcel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class MzdA2dpAndroidTest {
    private class Remote(val descriptor: String, val answer: (Int, Parcel) -> Unit) : Binder(), IInterface {
        init { attachInterface(this, descriptor) }
        override fun asBinder() = this
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            data.enforceInterface(descriptor)
            reply!!.writeNoException(); answer(code, reply)
            return true
        }
    }
    @Test fun verifiedAbiAcceptsEmptyListMinusOneAndOnlyMutatesA2dp() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        @Suppress("DEPRECATION")
        shadowOf(BluetoothAdapter.getDefaultAdapter()).setState(BluetoothAdapter.STATE_ON)
        var connected = true
        val controls = mutableListOf<Int>()
        val bt = Remote("com.incall.serversdk.bluetooth.IBluetoothManager") { code, _ -> controls.add(code) }
        val music = Remote("com.incall.serversdk.bluetooth.music.IBluetoothMusicManager") { code, reply ->
            reply.writeInt(when(code) { 1 -> if (connected) 2 else -1; 2 -> if (connected) 1 else 0; else -> error("Unexpected music mutation") })
        }
        val phone = Remote("com.incall.serversdk.bluetooth.phone.IPhoneManager") { code, reply ->
            reply.writeInt(when(code) { 27 -> 1; 26 -> 0; else -> error("Unexpected phone mutation") })
        }
        val registry = object : Binder(), IInterface {
            init { attachInterface(this, MzdBinderTransport.REGISTRY_DESCRIPTOR) }
            override fun asBinder() = this
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                assertEquals(6, code); data.enforceInterface(MzdBinderTransport.REGISTRY_DESCRIPTOR)
                val remote = when(data.readString()) {
                    "com.incall.bluetooth.server" -> bt
                    "com.incall.bluetooth.music" -> music
                    "com.incall.bluetooth.phone" -> phone
                    else -> error("Unexpected service")
                }
                reply!!.writeNoException(); reply.writeStrongBinder(remote)
                return true
            }
        }
        val port = MzdA2dpAndroid(app, {}) { registry }
        assertEquals(MzdBluetoothState(true, true, true, false), port.bluetooth())
        port.disconnect(); connected = false
        assertEquals(MzdBluetoothState(true, false, true, false), port.bluetooth())
        port.restore()
        assertEquals(listOf(54, 53), controls)
    }
}
