package com.shilapi.xcertplay.mazda

import android.content.res.Resources
import android.os.Handler
import android.view.View
import com.shilapi.xcertplay.CarPlayBackgroundSession
import com.shilapi.xcertplay.CarPlayHostActivity
import com.shilapi.xcertplay.CarPlaySessionDisplay
import com.shilapi.xcertplay.DiPlayActivity
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
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
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-w5120dp-h1440dp-mdpi", shadows = [MzdOemReturnTest.MazdaResources::class])
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
        verifyNoInteractions(controller, sink)
    }

    @Test fun adoptedSessionCanReturnBeforeAnotherSessionActiveCallback() {
        ReflectionHelpers.setField(host, "activeAirPlaySession", null)
        listener(host).onHostUiRequested(session)
        assertClosedWithSessionRetained()
    }

    @Test fun staleGenerationAndForeignSessionCannotCloseTheCurrentWindows() {
        val generation = ReflectionHelpers.getField<Int>(host, "restartGeneration")
        listener(host, generation - 1).onHostUiRequested(session)
        listener(host).onHostUiRequested(mock(AirPlaySession::class.java))
        assertFalse(host.isFinishing)
        assertFalse(home.isFinishing)
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
        listener(reopened).onHostUiRequested(session)
        assertTrue(host.isFinishing)
        assertTrue(home.isFinishing)
        assertTrue(reopened.isFinishing)
        assertEquals(0, stops)
        assertSame(controller, CarPlayBackgroundSession.snapshot()!!.controller)
    }

    @Test fun otherVariantsKeepTheirExistingHostUiBehavior() {
        MazdaResources.enabled = false
        listener(host).onHostUiRequested(session)
        assertFalse(host.isFinishing)
        assertFalse(home.isFinishing)
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
