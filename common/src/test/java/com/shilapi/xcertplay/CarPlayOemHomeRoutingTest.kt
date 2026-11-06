package com.shilapi.xcertplay

import android.content.Intent
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class CarPlayOemHomeRoutingTest {
    private var controller: CarPlayController? = null

    @After fun tearDown() {
        controller?.close()
        assertTrue(controller?.awaitClosed(2_000) != false)
    }

    @Test fun ordinaryUiUsesDefaultHomeAfterItsCallback() {
        val app = RuntimeEnvironment.getApplication()
        var notified = false
        val listener = start(object : AirPlaySessionListener {
            override fun onHostUiRequested(session: AirPlaySession): Boolean {
                assertNull("HOME must not precede the UI routing decision", shadowOf(app).nextStartedActivity)
                notified = true
                return false
            }
        })
        listener.onHostUiRequested(mock(AirPlaySession::class.java))
        assertTrue(notified)
        val intent = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_MAIN, intent.action)
        assertTrue(intent.hasCategory(Intent.CATEGORY_HOME))
        assertNull(intent.component)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, intent.flags)
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test fun handledOemRequestDoesNotLaunchAnUnqualifiedHome() {
        val session = mock(AirPlaySession::class.java)
        var requests = 0
        val listener = start(object : AirPlaySessionListener {
            override fun onHostUiRequested(actual: AirPlaySession): Boolean {
                assertSame(session, actual)
                requests++
                return true
            }
        })
        assertTrue(listener.onHostUiRequested(session))
        assertEquals(1, requests)
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
    }

    @Test fun reusedControllerRoutesThroughItsNewUiOwner() {
        var oldRequests = 0
        var newRequests = 0
        val listener = start(object : AirPlaySessionListener {
            override fun onHostUiRequested(session: AirPlaySession): Boolean {
                oldRequests++
                return false
            }
        })
        controller!!.attachUi(object : AirPlaySessionListener {
            override fun onHostUiRequested(session: AirPlaySession): Boolean {
                newRequests++
                return true
            }
        }, {})
        listener.onHostUiRequested(mock(AirPlaySession::class.java))
        assertEquals(0, oldRequests)
        assertEquals(1, newRequests)
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
    }

    @Test fun closedControllerCannotReturnTheNewProjectionToHome() {
        var requests = 0
        val listener = start(object : AirPlaySessionListener {
            override fun onHostUiRequested(session: AirPlaySession): Boolean {
                requests++
                return true
            }
        })
        controller!!.close()
        assertTrue(listener.onHostUiRequested(mock(AirPlaySession::class.java)))
        assertEquals(0, requests)
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
    }

    @Test fun wirelessOemUsesTheCurrentTransportRunInsteadOfTheVideoSessionIdentity() {
        var requests = 0
        start(object : AirPlaySessionListener {
            override fun onHostUiRequested(session: AirPlaySession): Boolean {
                requests++
                return true
            }
        })
        val phase = Class.forName("com.shilapi.xcertplay.orchestration.CarPlayController\$Phase")
            .enumConstants.single { it.toString() == "WIRELESS" }
        ReflectionHelpers.setField(controller, "phase", phase)
        ReflectionHelpers.getField<java.util.concurrent.atomic.AtomicInteger>(controller, "wirelessGeneration").set(1)
        assertTrue(wirelessListener(0).onHostUiRequested(mock(AirPlaySession::class.java)))
        assertEquals(0, requests)
        assertTrue(wirelessListener(1).onHostUiRequested(mock(AirPlaySession::class.java)))
        assertEquals(1, requests)
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
    }

    private fun wirelessListener(generation: Int): AirPlaySessionListener {
        val watchdogType = Class.forName("com.shilapi.xcertplay.orchestration.FirstTcpWatchdog")
        return controller!!.javaClass.getDeclaredMethod("wirelessSessionListener", Int::class.javaPrimitiveType,
            watchdogType).apply { isAccessible = true }
            .invoke(controller, generation, mock(watchdogType)) as AirPlaySessionListener
    }

    private fun start(ui: AirPlaySessionListener): AirPlaySessionListener {
        controller = CarPlayController(RuntimeEnvironment.getApplication(),
            CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL, identification = Iap2IdentificationConfig(
                name = "test", modelIdentifier = "test", manufacturer = "test", serialNumber = "test",
                firmwareVersion = "1", hardwareVersion = "1", carPlayUsbInterfaceNumber = 3)),
            AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480)),
            AirPlayIdentity(ByteArray(32), ByteArray(32), "test"), PairingStore(),
            ui, object : AirPlayMediaHandler {}, {})
        return ReflectionHelpers.getField(controller, "sessionListener")
    }
}
