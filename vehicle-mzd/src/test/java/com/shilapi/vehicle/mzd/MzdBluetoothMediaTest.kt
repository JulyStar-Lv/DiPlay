package com.shilapi.vehicle.mzd

import android.os.Binder
import android.os.IInterface
import android.os.Parcel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class MzdBluetoothMediaTest {
    private class Player : Binder(), IInterface {
        var source = 3
        var state = 7
        var afterRead: () -> Unit = {}
        var denied = false
        val codes = mutableListOf<Int>()
        init { attachInterface(this, MzdBluetoothMediaReader.DESCRIPTOR) }
        override fun asBinder() = this
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            data.enforceInterface(MzdBluetoothMediaReader.DESCRIPTOR)
            codes.add(code)
            if (denied) { reply!!.writeException(SecurityException("denied")); return true }
            reply!!.writeNoException()
            reply.writeInt(when(code) { 14 -> source; 15 -> state; else -> error("Control method $code") })
            afterRead()
            return true
        }
    }
    private val player = Player()
    private var off = true
    private val reader = MzdBluetoothMediaReader({ player }, { off })

    @Test fun requiresRadioOffAndStoppedNativeBluetoothUsingReadOnlyMethods() {
        off = false
        assertFalse(reader.disconnected())
        assertTrue(player.codes.isEmpty())
        off = true
        assertTrue(reader.disconnected())
        assertEquals(listOf(14, 15, 14, 15), player.codes)
        for (state in listOf(0, 1, 2, 7)) { player.state = state; assertTrue(reader.disconnected()) }
        for (state in listOf(3, 4, 5, 6, 8, -1)) { player.state = state; assertFalse(reader.disconnected()) }
        player.state = 7
        for (source in listOf(0, 1, 2, 4, 5, 6, 7, 8)) { player.source = source; assertFalse(reader.disconnected()) }
        player.source = 0; player.state = 0
        assertTrue(reader.disconnected()) // Actual firmware resets disconnected BT to Unknown/NONE.
        for (state in listOf(1, 2, 3, 7, -1)) { player.state = state; assertFalse(reader.disconnected()) }
    }

    @Test fun radioSourceAndStateChangesDuringReadForbidHandover() {
        player.afterRead = { off = false }
        assertFalse(reader.disconnected())
        off = true; player.afterRead = { player.source = 5 }
        assertFalse(reader.disconnected())
        player.source = 3; player.afterRead = { player.state = 3 }
        assertFalse(reader.disconnected())
    }

    @Test fun missingUnsupportedDeniedOrPermissionFailureFailsClosed() {
        assertFalse(MzdBluetoothMediaReader({ null }, { true }).disconnected())
        assertFalse(MzdBluetoothMediaReader({ Binder() }, { true }).disconnected())
        assertFalse(MzdBluetoothMediaReader({ player }, { throw SecurityException() }).disconnected())
        player.denied = true
        assertFalse(reader.disconnected())
    }
}
