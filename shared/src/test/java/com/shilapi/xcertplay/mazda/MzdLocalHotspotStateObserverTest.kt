package com.shilapi.xcertplay.mazda

import android.net.wifi.WifiManager
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, shadows = [MzdLocalHotspotStateObserverTest.Radio::class])
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
