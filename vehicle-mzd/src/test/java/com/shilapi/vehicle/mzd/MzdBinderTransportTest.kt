package com.shilapi.vehicle.mzd

import android.os.Binder
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class MzdBinderTransportTest {
    private open class Stub(descriptor: String) : Binder(), IInterface {
        init { attachInterface(this, descriptor) }
        override fun asBinder(): IBinder = this
    }
    private class Service : Stub(MzdBinderTransport.DESCRIPTOR) {
        val transactions = mutableListOf<Int>()
        val focus = mutableMapOf<MzdDomain, IBinder>()
        var failRequest = false
        var grantRequests = true
        var navigationPair: Pair<Int, Int>? = null
        var mediaJson: org.json.JSONObject? = null
        var laneJson: org.json.JSONObject? = null
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            data.enforceInterface(MzdBinderTransport.DESCRIPTOR)
            transactions.add(code)
            MzdDomain.entries.firstOrNull { it.request == code }?.let { domain ->
                assertEquals("test.owner", data.readString())
                val callback = data.readStrongBinder()!!
                focus[domain] = callback
                if (failRequest) { reply!!.writeException(SecurityException("denied")); return true }
                if (grantRequests) changeFocus(callback, true)
            }
            if (code == 24) navigationPair = data.readInt() to data.readInt()
            if (code == 37) mediaJson = org.json.JSONObject(data.readString()!!)
            if (code == 25) laneJson = org.json.JSONObject(data.readString()!!)
            reply?.writeNoException()
            return true
        }
    }
    private val service = Service()
    private val registry = object : Stub(MzdBinderTransport.REGISTRY_DESCRIPTOR) {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            assertEquals(6, code)
            data.enforceInterface(MzdBinderTransport.REGISTRY_DESCRIPTOR)
            assertEquals(MzdBinderTransport.SERVICE, data.readString())
            reply!!.writeNoException(); reply.writeStrongBinder(service)
            return true
        }
    }
    private var owner = true
    private fun client() = MzdBinderTransport("test.owner", { owner }, {}, { name ->
        assertEquals(MzdBinderTransport.REGISTRY, name); registry
    })

    @Test fun usesRegistryOnceAndIndependentFocusCallbacksOnTheSameBinder() {
        val client = client()
        client.request(MzdDomain.NAVIGATION)
        client.request(MzdDomain.MEDIA)
        client.request(MzdDomain.PHONE)
        client.request(MzdDomain.NAVIGATION)
        assertEquals(listOf(29, 63, 40, 61, 45, 65), service.transactions)
        assertTrue(MzdDomain.entries.all { client.focused(it) })
        client.navigation(MzdNavigation(19, 150, 5000, 60))
        assertEquals(19 to 150, service.navigationPair)
        client.close()
        assertEquals(listOf(64, 30, 62, 41, 66, 46), service.transactions.takeLast(6))
    }

    @Test fun focusAndSessionLossForbidWritesAndOldCallbacksCannotGrantNewRegistration() {
        val client = client()
        client.request(MzdDomain.MEDIA)
        val old = service.focus.getValue(MzdDomain.MEDIA)
        changeFocus(old, false)
        assertThrows(MzdOwnershipLost::class.java) { client.media("{}") }
        client.release(MzdDomain.MEDIA)
        client.request(MzdDomain.MEDIA)
        changeFocus(old, false)
        assertTrue(client.focused(MzdDomain.MEDIA))
        owner = false
        assertThrows(MzdOwnershipLost::class.java) { client.media("{}") }
        client.close()
        assertFalse(service.transactions.contains(37))
    }

    @Test fun registrationIsReleasedEvenIfFocusRequestFails() {
        service.failRequest = true
        val client = client()
        assertThrows(SecurityException::class.java) { client.request(MzdDomain.PHONE) }
        client.close()
        assertEquals(listOf(45, 65, 66, 46), service.transactions)
    }

    @Test fun multiLaneJsonUsesNativeTransaction25AfterRemainingAndRequiresNavigationFocus() {
        val client = client()
        val value = MzdNavigation(19, 150, 5000, 60, listOf(16, 16, 0, 33))
        assertThrows(MzdOwnershipLost::class.java) { client.navigation(value) }
        assertFalse(service.transactions.contains(25))
        client.request(MzdDomain.NAVIGATION)
        client.navigation(value)
        assertEquals(listOf(24, 27, 25), service.transactions.takeLast(3))
        assertEquals(4, service.laneJson!!.getInt("laneNum"))
        assertEquals(16, service.laneJson!!.getInt("lane1"))
        assertEquals(33, service.laneJson!!.getInt("lane4"))
        client.navigation(MzdNavigation(0, 0, 0, 0))
        assertEquals(0, service.laneJson!!.getInt("laneNum"))
        changeFocus(service.focus.getValue(MzdDomain.NAVIGATION), false)
        val count = service.transactions.size
        assertThrows(MzdOwnershipLost::class.java) { client.navigation(value) }
        assertEquals(count, service.transactions.size)
        client.close()
    }

    @Test fun unexpectedDescriptorStopsBeforeAnyMutation() {
        assertThrows(IllegalStateException::class.java) {
            MzdBinderTransport("test.owner", { true }, {}, { Stub("wrong.registry") })
        }
        assertTrue(service.transactions.isEmpty())
    }

    @Test fun disconnectedBluetoothAllowsOneHandoverOnlyAndRequiresRealCallback() {
        var disconnected = false
        var closed = false
        val reader = object : MzdBluetoothMedia {
            override fun disconnected() = disconnected
            override fun close() { closed = true }
        }
        val client = MzdBinderTransport("test.owner", { owner }, {}, { registry }, reader)
        client.request(MzdDomain.MEDIA)
        disconnected = true
        assertFalse(client.handoverDisconnectedBluetooth()) // no revoked ownership
        changeFocus(service.focus.getValue(MzdDomain.MEDIA), false)
        disconnected = false
        assertFalse(client.handoverDisconnectedBluetooth())
        disconnected = true; service.grantRequests = false
        assertTrue(client.handoverDisconnectedBluetooth())
        assertFalse(client.focused(MzdDomain.MEDIA))
        assertThrows(MzdOwnershipLost::class.java) { client.media("{}") }
        assertFalse(service.transactions.contains(37))
        changeFocus(service.focus.getValue(MzdDomain.MEDIA), true)
        client.media("{}")
        changeFocus(service.focus.getValue(MzdDomain.MEDIA), false)
        assertFalse(client.handoverDisconnectedBluetooth())
        assertEquals(2, service.transactions.count { it == 61 })
        owner = false
        assertThrows(MzdOwnershipLost::class.java) { client.handoverDisconnectedBluetooth() }
        client.close(); assertTrue(closed)
    }

    companion object {
        private fun changeFocus(callback: IBinder, grant: Boolean) {
            val data = Parcel.obtain(); val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(callback.interfaceDescriptor!!)
                data.writeInt(if (grant) 1 else 0)
                assertTrue(callback.transact(3, data, reply, 0))
                reply.readException()
            } finally { reply.recycle(); data.recycle() }
        }
    }
}
