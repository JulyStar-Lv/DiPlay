package com.shilapi.xcertplay

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Looper
import java.time.Duration
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@LooperMode(LooperMode.Mode.PAUSED)
class PhoneBluetoothReceiverTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val selected = "12:34:56:78:9A:BC"

    @Before fun reset() {
        DiPlayPreferences.saveConnectOnPhoneBluetooth(app, false)
        app.getSharedPreferences("diplay_phone_wake", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("diplay_phone_wake_diagnostics", Context.MODE_PRIVATE).edit().clear().commit()
        CarPlayBackgroundSession.clear()
        // Saved in lowercase on purpose: the receiver must match the (always uppercase) device
        // address case-insensitively; Robolectric refuses to build a lowercase remote device.
        DiPlayPreferences.savePhone(app, selected.lowercase(), "iPhone")
    }

    private fun event(address: String): Intent {
        val device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address)
        return Intent(BluetoothDevice.ACTION_ACL_CONNECTED)
            .putExtra(BluetoothDevice.EXTRA_DEVICE, device)
    }

    @Test fun onlySelectedPhoneWithOptInLaunchesOnce() {
        val launches = mutableListOf<Intent>()
        val context = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent) { launches += intent }
        }
        val receiver = PhoneBluetoothReceiver()
        receiver.onReceive(context, event(selected))
        assertTrue(launches.isEmpty())
        DiPlayPreferences.saveConnectOnPhoneBluetooth(app, true)
        receiver.onReceive(context, event("AA:BB:CC:DD:EE:FF"))
        assertTrue(launches.isEmpty())
        receiver.onReceive(context, event(selected))
        receiver.onReceive(context, event(selected))
        assertTrue(launches.isEmpty())
        val service = startDelayedAutoStart(context)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        service.destroy()
        assertEquals(1, launches.size)
        assertEquals(DiPlayActivity::class.java.name, launches.single().component?.className)
        assertEquals(PhoneBluetoothReceiver.launchToken, launches.single().getStringExtra(PhoneBluetoothReceiver.EXTRA_AUTO_CONNECT))
        assertTrue(PhoneWakeDiagnostics.report(app).contains("result=startActivity-returned"))
    }
}
