package com.shilapi.xcertplay.mazda

import android.app.Activity
import android.content.Intent
import android.content.res.Resources
import android.os.Handler
import android.provider.Settings
import android.view.View
import com.shilapi.xcertplay.CarPlayBackgroundSession
import com.shilapi.xcertplay.CarPlayHostActivity
import com.shilapi.xcertplay.CarPlaySessionDisplay
import com.shilapi.xcertplay.DiPlayActivity
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.airplay.BplistCodec
import com.shilapi.xcertplay.airplay.AirPlayListenerIdentity
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.network.CarPlayVpnService
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import java.net.Socket
import java.net.InetAddress
import java.util.concurrent.ExecutorService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowResources
import org.robolectric.shadows.ShadowActivity
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-w5120dp-h1440dp-mdpi",
    shadows = [MzdOemReturnTest.MazdaResources::class, MzdOemReturnTest.MazdaTasks::class])
class MzdOemReturnTest {
    private lateinit var home: DiPlayActivity
    private lateinit var host: CarPlayHostActivity
    private val hosts = mutableListOf<CarPlayHostActivity>()
    private lateinit var session: AirPlaySession
    private lateinit var controller: CarPlayController
    private lateinit var sink: AndroidMediaSink
    private var stops = 0

    @Before fun setUp() {
        MazdaResources.enabled = true
        Settings.Global.putString(org.robolectric.RuntimeEnvironment.getApplication().contentResolver,
            "main_display_status", null)
        Settings.Global.putString(org.robolectric.RuntimeEnvironment.getApplication().contentResolver,
            "copilot_display_status", null)
        CarPlayBackgroundSession.clear()
        home = Robolectric.buildActivity(DiPlayActivity::class.java).get().apply {
            setTheme(R.style.Theme_Xcertplay)
            setContentView(MzdIntegration.wrap(this, View(this)))
        }
        host = newHost()
        session = mock(AirPlaySession::class.java)
        controller = mock(CarPlayController::class.java)
        sink = mock(AndroidMediaSink::class.java)
        ReflectionHelpers.setField(host, "activeAirPlaySession", session)
        store(host)
    }

    @After fun tearDown() {
        MzdIntegration.closeWindows()
        home.finish()
        hosts.forEach {
            it.finish()
            ReflectionHelpers.getField<Handler>(it, "mainHandler").removeCallbacksAndMessages(null)
            ReflectionHelpers.getField<ExecutorService>(it, "teardownExecutor").shutdownNow()
            ReflectionHelpers.getField<ExecutorService>(it, "airPlayCommandExecutor").shutdownNow()
        }
        CarPlayBackgroundSession.clear()
        MazdaResources.enabled = true
    }

    @Test fun oemRequestClosesBothWindowsIncludingHiddenHomeAndKeepsTheLiveSession() {
        home.window.decorView.visibility = View.GONE
        val listener = listener(host)
        repeat(2) { listener.onHostUiRequested(session) }
        assertClosedWithSessionRetained()
        assertNull("Duplicate callbacks must not launch HOME twice", shadowOf(host).nextStartedActivity)
        verifyNoInteractions(controller, sink)
    }

    @Test fun passengerOemExitRevealsItsExistingAppListWithoutGoingHome() {
        host.intent = Intent().putExtra("task_display_area", "area_right")
        assertTrue(listener(host).onHostUiRequested(session))
        assertClosedWithSessionRetained()
        assertNoOemRefresh()
    }

    @Test fun driverReturnRefreshesTheStalePassengerRecordThatBlocksItsAppListClick() {
        Settings.Global.putString(host.contentResolver, "copilot_display_status",
            "copilot_display_status 1 ${host.packageName} ${CarPlayHostActivity::class.java.name}")
        listener(host).onHostUiRequested(session)
        assertClosedWithSessionRetained()
        assertOemRefresh("area_right")
        assertNull(shadowOf(host).nextStartedActivity)
    }

    @Test fun passengerExitRefreshesAStaleDriverRecordWithoutNavigatingItsOwnArea() {
        host.intent = Intent().putExtra("task_display_area", "area_right")
        Settings.Global.putString(host.contentResolver, "main_display_status",
            "main_display_status 1 ${host.packageName} ${CarPlayHostActivity::class.java.name}")
        listener(host).onHostUiRequested(session)
        assertClosedWithSessionRetained()
        assertOemRefresh("area_left")
        assertNull(shadowOf(host).nextStartedActivity)
    }

    @Test fun oemExitLeavesAnotherPassengerAppAlone() {
        Settings.Global.putString(host.contentResolver, "copilot_display_status",
            "copilot_display_status 1 com.wt.scene com.wt.scene.MainActivity")
        listener(host).onHostUiRequested(session)
        assertClosedWithSessionRetained()
        assertNull(shadowOf(host).nextStartedActivity)
        assertTrue(shadowOf(org.robolectric.RuntimeEnvironment.getApplication()).broadcastIntents
            .none { it.action == "wt.intent.action.gohome" })
    }

    @Test fun closingTheHomePageRevealsAppListAndRefreshesOnlyTheOppositeRecord() {
        Settings.Global.putString(home.contentResolver, "main_display_status",
            "main_display_status 1 ${home.packageName} ${DiPlayActivity::class.java.name}")
        Settings.Global.putString(home.contentResolver, "copilot_display_status",
            "copilot_display_status 1 ${home.packageName} ${DiPlayActivity::class.java.name}")
        MzdIntegration.exitApp(home)
        assertClosedWithSessionRetained()
        assertOemRefresh("area_right")
        assertNull("Closing must reveal the existing AppList, without starting HOME", shadowOf(home).nextStartedActivity)
        assertNull(shadowOf(host).nextStartedActivity)
    }

    @Test fun closingThePassengerConnectionPageRevealsItsExistingLauncherPage() {
        host.intent = Intent().putExtra("task_display_area", "area_right")
        Settings.Global.putString(host.contentResolver, "copilot_display_status",
            "copilot_display_status 1 ${host.packageName} ${CarPlayHostActivity::class.java.name}")
        Settings.Global.putString(host.contentResolver, "main_display_status",
            "main_display_status 1 com.wt.scene com.wt.scene.MainActivity")
        MzdIntegration.exitApp(host)
        assertClosedWithSessionRetained()
        assertNull(shadowOf(home).nextStartedActivity)
        assertNull(shadowOf(host).nextStartedActivity)
        assertTrue(shadowOf(org.robolectric.RuntimeEnvironment.getApplication()).broadcastIntents
            .none { it.action == "wt.intent.action.gohome" })
    }

    @Test fun taskMovesUseTheCurrentWindowRatherThanTheOriginalLaunchArea() {
        val window = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = window.get()
            activity.intent = Intent().putExtra("task_display_area", "area_right")
            val decor = activity.window.decorView
            decor.layout(0, 0, 2560, 1440)
            // WindowManager changes this physical origin when reparenting an otherwise unchanged
            // task. Layout left alone is local to the decor and does not represent a task move.
            val attached = ReflectionHelpers.getField<Any>(decor, "mAttachInfo")
            ReflectionHelpers.setField(attached, "mWindowLeft", 0)
            assertEquals("area_left", MzdIntegration.homeArea(activity))
            ReflectionHelpers.setField(attached, "mWindowLeft", 2560)
            assertEquals("area_right", MzdIntegration.homeArea(activity))
            activity.intent = Intent().putExtra("task_display_area", "area_left")
            assertEquals("area_right", MzdIntegration.homeArea(activity))
            ReflectionHelpers.setField(attached, "mWindowLeft", 0)
            assertEquals("area_left", MzdIntegration.homeArea(activity))
        } finally { window.pause().stop().destroy() }
    }

    @Test fun oemExitWorksWithoutAnInstalledOemHomeActivity() {
        listener(host).onHostUiRequested(session)
        assertClosedWithSessionRetained()
        assertNoOemRefresh()
    }

    @Test fun adoptedSessionCanReturnBeforeAnotherSessionActiveCallback() {
        ReflectionHelpers.setField(host, "activeAirPlaySession", null)
        listener(host).onHostUiRequested(session)
        assertClosedWithSessionRetained()
    }

    @Test fun wirelessRtspOemRequestOnAnotherControlConnectionReturnsTheCurrentUi() {
        val config = AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(2496, 1080), port = 0)
        val identity = AirPlayIdentity(ByteArray(32), ByteArray(32), "test")
        val pairings = PairingStore()
        val live = CarPlayController(host, CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL,
            transport = CarPlayTransport.WIRELESS, identification = Iap2IdentificationConfig(
                name = "test", modelIdentifier = "test", manufacturer = "test", serialNumber = "test",
                firmwareVersion = "1", hardwareVersion = "1", carPlayUsbInterfaceNumber = 3)),
            config, identity, pairings, listener(host), object : AirPlayMediaHandler {}, {})
        controller = live
        store(host)
        ReflectionHelpers.setField(live, "activeSession", session)
        val phase = Class.forName("com.shilapi.xcertplay.orchestration.CarPlayController\$Phase")
            .enumConstants.single { it.toString() == "WIRELESS" }
        ReflectionHelpers.setField(live, "phase", phase)
        val watchdogType = Class.forName("com.shilapi.xcertplay.orchestration.FirstTcpWatchdog")
        val wireless = live.javaClass.getDeclaredMethod("wirelessSessionListener", Int::class.javaPrimitiveType,
            watchdogType).apply { isAccessible = true }.invoke(live, 0, mock(watchdogType)) as AirPlaySessionListener
        val service = Robolectric.buildService(CarPlayVpnService::class.java).create().get()
        val address = InetAddress.getByName("127.0.0.1")
        try {
            // The visible projection already has its active video session. A separate control
            // connection sends the OEM command through the real service's transport wrapper.
            assertEquals(CarPlayVpnService.AttachResult.Started, service.attachWireless(address,
                config, identity, pairings, null, wireless, object : AirPlayMediaHandler {},
                listenerIdentity = AirPlayListenerIdentity(0)))
            Socket(address, service.boundPort()!!).use { socket ->
                socket.soTimeout = 2_000
                val body = BplistCodec.encode(mapOf("type" to "requestUI",
                    "params" to emptyMap<String, Any?>()))
                val header = "POST /command RTSP/1.0\r\nCSeq: 1\r\nContent-Length: ${body.size}\r\n\r\n"
                socket.getOutputStream().apply {
                    write(header.toByteArray(Charsets.US_ASCII))
                    write(body)
                    flush()
                }
                assertEquals("RTSP/1.0 200 OK", socket.getInputStream().bufferedReader().readLine())
            }
            assertClosedWithSessionRetained()
            assertNoOemRefresh()
        } finally {
            service.detach()
            live.close()
            assertTrue(live.awaitClosed(2_000))
        }
    }

    @Test fun staleUiGenerationCannotCloseTheCurrentWindows() {
        val generation = ReflectionHelpers.getField<Int>(host, "restartGeneration")
        listener(host, generation - 1).onHostUiRequested(session)
        assertFalse(host.isFinishing)
        assertFalse(home.isFinishing)
        assertNull("Stale callbacks must not navigate HOME", shadowOf(host).nextStartedActivity)
        listener(host).onHostUiRequested(session)
        assertClosedWithSessionRetained()
    }

    @Test fun callbacksFromThePreviousOwnerCannotCloseAReopenedProjection() {
        val oldListener = listener(host)
        val reopened = newHost()
        store(reopened)
        oldListener.onHostUiRequested(session)
        assertFalse(host.isFinishing)
        assertFalse(home.isFinishing)
        assertFalse(reopened.isFinishing)
        assertNull(shadowOf(host).nextStartedActivity)
        assertNull(shadowOf(reopened).nextStartedActivity)
        listener(reopened).onHostUiRequested(session)
        assertTrue(host.isFinishing)
        assertTrue(home.isFinishing)
        assertTrue(reopened.isFinishing)
        assertEquals(0, stops)
        assertSame(controller, CarPlayBackgroundSession.snapshot()!!.controller)
    }

    @Test fun otherVariantsKeepTheirExistingHostUiBehavior() {
        MazdaResources.enabled = false
        assertFalse(listener(host).onHostUiRequested(session))
        assertFalse(host.isFinishing)
        assertFalse(home.isFinishing)
        assertEquals(0, stops)
    }

    @Test fun oemReturnFinishesAllPagesWithoutTheServiceStoppingTaskRemovalCallback() {
        val legacy = newHost()
        listener(host).onHostUiRequested(session)
        assertClosedWithSessionRetained()
        assertTrue(legacy.isFinishing)
        repeat(2) { MzdIntegration.closeWindows() }
        assertTrue(CarPlayBackgroundSession.hasSession())
        assertEquals(0, stops)
    }

    private fun newHost() = Robolectric.buildActivity(CarPlayHostActivity::class.java).get().apply {
        hosts.add(this)
        setTheme(R.style.Theme_Xcertplay)
        setContentView(MzdIntegration.wrap(this, View(this)))
    }

    private fun store(owner: CarPlayHostActivity) {
        CarPlayBackgroundSession.store(controller, sink, 2496, 1080, owner,
            CarPlaySessionDisplay(2496, 1080, 0, false, false, 2496, 1080)) {
            stops++
            it()
        }
        CarPlayBackgroundSession.active = true
    }

    private fun listener(activity: CarPlayHostActivity,
        generation: Int = ReflectionHelpers.getField(activity, "restartGeneration")): AirPlaySessionListener =
        ReflectionHelpers.callInstanceMethod(activity, "createSessionListener",
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, generation))

    private fun assertClosedWithSessionRetained() {
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(host.isFinishing)
        assertTrue(home.isFinishing)
        assertTrue(CarPlayBackgroundSession.hasSession())
        assertTrue(CarPlayBackgroundSession.active)
        assertSame(controller, CarPlayBackgroundSession.snapshot()!!.controller)
        assertSame(sink, CarPlayBackgroundSession.snapshot()!!.sink)
        assertEquals(0, stops)
        assertNull("OEM and close must reveal the existing Launcher page", shadowOf(host).nextStartedActivity)
        assertNull(shadowOf(home).nextStartedActivity)
    }

    private fun assertNoOemRefresh() {
        assertTrue(shadowOf(org.robolectric.RuntimeEnvironment.getApplication()).broadcastIntents
            .none { it.action == "wt.intent.action.gohome" })
    }

    private fun assertOemRefresh(area: String) {
        val refresh = shadowOf(org.robolectric.RuntimeEnvironment.getApplication()).broadcastIntents
            .single { it.action == "wt.intent.action.gohome" }
        assertEquals("com.tinnove.launcher", refresh.`package`)
        assertEquals(area, refresh.getStringExtra("launcher_display_area"))
    }

    @Implements(Activity::class)
    class MazdaTasks : ShadowActivity() {
        @RealObject private lateinit var activity: Activity
        @Implementation fun startActivity(intent: Intent) {
            if (intent.hasCategory(Intent.CATEGORY_HOME))
                throw AssertionError("Closing DiPlay must reveal AppList, not launch HOME")
            Shadow.directlyOn<Any?, Activity>(activity, Activity::class.java, "startActivity",
                ReflectionHelpers.ClassParameter.from(Intent::class.java, intent))
        }
        @Implementation override fun finishAndRemoveTask() {
            throw AssertionError("Removing a UI task invokes DiPlaySessionService.onTaskRemoved and stops CarPlay")
        }
    }

    @Implements(Resources::class)
    class MazdaResources : ShadowResources() {
        @RealObject private lateinit var resources: Resources
        @Implementation fun getBoolean(id: Int): Boolean = if (id == R.bool.config_mazda) enabled else
            Shadow.directlyOn(resources, Resources::class.java, "getBoolean",
                ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, id))
        companion object { var enabled = true }
    }
}
