package com.shilapi.xcertplay.mazda

import android.net.wifi.WifiManager
import android.os.Build
import android.os.Looper
import com.shilapi.xcertplay.network.LocalOnlyHotspotRadioInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.ClassName
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowWifiManager
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 35], manifest = Config.NONE, shadows = [MzdLocalHotspotStateObserverTest.Radio::class])
class MzdLocalHotspotStateObserverTest {
    @Test fun forwardsStatesFromTheLocalOnlyCallbackAndUnregistersIt() {
        val wifi = RuntimeEnvironment.getApplication().getSystemService(WifiManager::class.java)
        val radio = shadowOf(wifi) as Radio
        val states = mutableListOf<Int>()
        val observer = LocalOnlyHotspotRadioInfo(wifi, states::add)
        try {
            observer.start()
            assertNull(observer.unavailableReason)
            val callback = radio.callback!!
            val method = Class.forName("android.net.wifi.WifiManager\$SoftApCallback")
                .getMethod("onStateChanged", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            for (state in listOf(13, 10, 11)) {
                radio.executor!!.execute { method.invoke(callback, state, 0) }
            }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(13, 10, 11), states)
        } finally { observer.close() }
        assertEquals(1, radio.unregistered)
    }

    @Test fun shutdownCallbackAllowsReconnectionOnlyAfterDisabledAndSettling() {
        val wifi = RuntimeEnvironment.getApplication().getSystemService(WifiManager::class.java)
        val radio = shadowOf(wifi) as Radio
        val now = AtomicLong()
        val lifecycle = MzdLocalHotspotLifecycle(now::get)
        val observer = LocalOnlyHotspotRadioInfo(wifi, lifecycle::stateChanged)
        try {
            observer.start()
            assertNull(observer.unavailableReason)
            val type = Class.forName("android.net.wifi.WifiManager\$SoftApCallback")
            fun deliver(state: Int) {
                val value = if (Build.VERSION.SDK_INT >= 35) {
                    val stateType = Class.forName("android.net.wifi.SoftApState")
                    val requestType = Class.forName("android.net.TetheringManager\$TetheringRequest")
                    val snapshot = stateType.getConstructor(Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType, requestType, String::class.java)
                        .newInstance(state, 0, null, "ap0")
                    Runnable { type.getMethod("onStateChanged", stateType).invoke(radio.callback, snapshot) }
                } else Runnable {
                    type.getMethod("onStateChanged", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                        .invoke(radio.callback, state, 0)
                }
                radio.executor!!.execute(value)
                shadowOf(Looper.getMainLooper()).idle()
            }
            val previous = lifecycle.acquire(15_000) { false }
            lifecycle.begin(previous)
            deliver(13)
            lifecycle.resolved(previous)
            assertTrue(lifecycle.isActive(previous))
            lifecycle.close(previous)
            deliver(10)
            assertThrows(IOException::class.java) { lifecycle.acquire(0) { false } }
            deliver(11)
            assertThrows(IOException::class.java) { lifecycle.acquire(0) { false } }
            now.addAndGet(1_000)
            assertNotSame(previous, lifecycle.acquire(0) { false })
        } finally { observer.close() }
        assertEquals(1, radio.unregistered)
    }

    @Implements(WifiManager::class)
    class Radio : ShadowWifiManager() {
        var callback: Any? = null
        var executor: Executor? = null
        var unregistered = 0

        @Implementation fun registerLocalOnlyHotspotSoftApCallback(delivery: Executor,
            @ClassName("android.net.wifi.WifiManager\$SoftApCallback") value: Any,
        ) { executor = delivery; callback = value }

        @Implementation fun unregisterLocalOnlyHotspotSoftApCallback(
            @ClassName("android.net.wifi.WifiManager\$SoftApCallback") value: Any,
        ) { assertSame(callback, value); unregistered++ }
    }
}
