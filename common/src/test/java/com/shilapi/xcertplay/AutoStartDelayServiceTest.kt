package com.shilapi.xcertplay

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Looper
import com.shilapi.xcertplay.mazda.MzdOemReturnTest
import java.time.Duration
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

/** Deliver the receiver's service request without advancing the main looper. */
internal fun startDelayedAutoStart(context: Context): ServiceController<AutoStartDelayService> {
    val app = RuntimeEnvironment.getApplication()
    val request = generateSequence { shadowOf(app).nextStartedService }
        .first { it.component?.className == AutoStartDelayService::class.java.name }
    val controller = Robolectric.buildService(AutoStartDelayService::class.java, request).create()
    ReflectionHelpers.setField(controller.get(), "mBase", context)
    controller.get().onStartCommand(request, 0, 1)
    return controller
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@LooperMode(LooperMode.Mode.PAUSED)
class AutoStartDelayServiceTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val launches = mutableListOf<Intent>()
    private val context get() = object : ContextWrapper(app) {
        override fun startActivity(intent: Intent) { launches += intent }
    }
    private val phone = "12:34:56:78:9A:BC"

    @Before fun reset() {
        app.getSharedPreferences("diplay", 0).edit().clear().commit()
        app.getSharedPreferences("diplay_phone_wake", 0).edit().clear().commit()
        AirPlayPersistence.saveAutoStartOnBoot(app, true)
        DiPlayPreferences.saveConnectOnPhoneBluetooth(app, true)
        DiPlayPreferences.savePhone(app, phone, "iPhone")
        CarPlayBackgroundSession.clear()
    }

    private fun boot() = BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

    private fun bluetooth() {
        val device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(phone)
        PhoneBluetoothReceiver().onReceive(context,
            Intent(BluetoothDevice.ACTION_ACL_CONNECTED).putExtra(BluetoothDevice.EXTRA_DEVICE, device))
    }

    private fun deliverNext(service: AutoStartDelayService) {
        service.onStartCommand(shadowOf(app).nextStartedService, 0, 2)
    }

    @Test fun everyBootLaunchWaitsTenSecondsIncludingLaterStarts() {
        repeat(2) { index ->
            boot()
            val service = startDelayedAutoStart(context)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(9_999))
            assertEquals(index, launches.size)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
            assertEquals(index + 1, launches.size)
            assertEquals(Intent.ACTION_MAIN, launches.last().action)
            assertTrue(launches.last().hasCategory(Intent.CATEGORY_LAUNCHER))
            assertFalse(launches.last().hasExtra("task_display_area"))
            service.destroy()
        }
    }

    @Test fun everyBluetoothLaunchWaitsTenSecondsIncludingLaterReconnects() {
        repeat(2) { index ->
            // Allow a separate reconnect after the receiver's duplicate-event throttle.
            if (index > 0) shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(15))
            bluetooth()
            val service = startDelayedAutoStart(context)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(9_999))
            assertEquals(index, launches.size)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
            assertEquals(index + 1, launches.size)
            assertEquals(PhoneBluetoothReceiver.launchToken,
                launches.last().getStringExtra(PhoneBluetoothReceiver.EXTRA_AUTO_CONNECT))
            service.destroy()
        }
    }

    @Test @Config(shadows = [MzdOemReturnTest.MazdaResources::class])
    fun mazdaBootAndBluetoothLaunchInTheDriverAreaAfterTenSeconds() {
        MzdOemReturnTest.MazdaResources.enabled = true
        for (phoneWake in listOf(false, true)) {
            if (phoneWake) bluetooth() else boot()
            val service = startDelayedAutoStart(context)
            try {
                val previous = launches.size
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(9_999))
                assertEquals(previous, launches.size)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
                assertEquals(previous + 1, launches.size)
                assertEquals("area_left", launches.last().getStringExtra("task_display_area"))
            } finally { service.destroy() }
        }
    }

    private fun assertCombinedLaunch(phoneFirst: Boolean) {
        if (phoneFirst) bluetooth() else boot()
        val service = startDelayedAutoStart(context)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        if (phoneFirst) boot() else bluetooth()
        deliverNext(service.get())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(7_999))
        assertTrue(launches.isEmpty())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertEquals(1, launches.size)
        assertEquals(Intent.ACTION_MAIN, launches.single().action)
        assertEquals(PhoneBluetoothReceiver.launchToken,
            launches.single().getStringExtra(PhoneBluetoothReceiver.EXTRA_AUTO_CONNECT))
        assertTrue(StartupDiagnosticSnapshot.report(app).contains("launchResult=startActivity-returned"))
        assertTrue(PhoneWakeDiagnostics.report(app).contains("result=startActivity-returned"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        assertEquals(1, launches.size)
        service.destroy()
    }

    @Test fun bluetoothMergesIntoPendingBootWithoutExtendingTheDeadline() = assertCombinedLaunch(false)

    @Test fun bootMergesIntoPendingBluetoothWithoutExtendingTheDeadline() = assertCombinedLaunch(true)

    @Test fun disablingAutomaticLaunchBeforeDispatchCancelsIt() {
        boot()
        val service = startDelayedAutoStart(context)
        AirPlayPersistence.saveAutoStartOnBoot(app, false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        assertTrue(launches.isEmpty())
        assertTrue(StartupDiagnosticSnapshot.report(app).contains("launchResult=cancelled"))
        service.destroy()
    }

    @Test fun changingSelectedPhoneBeforeDispatchCancelsBluetoothLaunch() {
        bluetooth()
        val service = startDelayedAutoStart(context)
        DiPlayPreferences.savePhone(app, "AA:BB:CC:DD:EE:FF", "Other iPhone")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        assertTrue(launches.isEmpty())
        assertTrue(PhoneWakeDiagnostics.report(app).contains("result=cancelled"))
        service.destroy()
    }

    @Test fun stoppingTheDelayServiceCancelsThePendingLaunch() {
        boot()
        val service = startDelayedAutoStart(context)
        service.destroy()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        assertTrue(launches.isEmpty())
    }
}
