package com.shilapi.xcertplay.mazda

import com.shilapi.xcertplay.*

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.res.Resources
import android.os.Looper
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowResources
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w900dp-h720dp", shadows = [MzdHomeSessionButtonTest.HomeResources::class])
class MzdHomeSessionButtonTest {
    private var stopCompletion: (() -> Unit)? = null
    private var stops = 0

    @Test fun choosingAPhoneWithoutASessionKeepsTheSelectionAction() = forMazdaHome { activity ->
        val action = sessionButton(activity)
        assertEquals(activity.getString(R.string.choose_iphone), action.text.toString())
        choosePairedPhone(activity, action)
        assertFalse(CarPlayBackgroundSession.hasSession())
        assertEquals("Test iPhone", DiPlayPreferences.phoneName(activity))
        assertEquals(activity.getString(R.string.choose_iphone), sessionButton(activity).text.toString())
    }

    @Test fun connectingAndConnectedSessionsReuseTheSameButton() = forMazdaHome { activity ->
        val action = sessionButton(activity)
        val parent = action.parent
        val position = (parent as ViewGroup).indexOfChild(action)
        startSession()
        refresh(activity)
        assertEquals(activity.getString(R.string.disconnect), action.text.toString())
        assertTrue(action.isEnabled)
        CarPlayBackgroundSession.active = true
        refresh(activity)
        assertSame(action, sessionButton(activity))
        assertSame(parent, action.parent)
        assertEquals(position, parent.indexOfChild(action))
        assertEquals(activity.getString(R.string.open_carplay), connectButton(activity).text.toString())
        assertSingleSessionAction(activity)
        CarPlayBackgroundSession.clear()
        refresh(activity)
        assertSame(action, sessionButton(activity))
        assertEquals(activity.getString(R.string.choose_iphone), action.text.toString())
        assertEquals(activity.getString(R.string.connect_phone), connectButton(activity).text.toString())
    }

    @Test fun disconnectRemainsDisabledAcrossRefreshRebuildAndRepeatedClicks() = forMazdaHome { activity ->
        startSession()
        refresh(activity)
        var action = sessionButton(activity)
        action.performClick()
        assertEquals(1, stops)
        assertTrue(CarPlayBackgroundSession.hasSession())
        assertFalse(action.isEnabled)
        refresh(activity)
        assertFalse(action.isEnabled)
        // performClick also exercises the listener's guard without relying on touch dispatch.
        action.performClick()
        assertEquals(1, stops)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
        action = sessionButton(activity)
        assertFalse(action.isEnabled)
        assertEquals(activity.getString(R.string.disconnect), action.text.toString())
        CarPlayBackgroundSession.clear()
        refresh(activity)
        assertFalse(action.isEnabled)
        finishStop()
        assertTrue(action.isEnabled)
        assertEquals(activity.getString(R.string.choose_iphone), action.text.toString())
        assertSingleSessionAction(activity)
    }

    @Test fun aStopStartedElsewhereAlsoDisablesTheHomeAction() = forMazdaHome { activity ->
        startSession()
        CarPlayBackgroundSession.stop()
        refresh(activity)
        assertFalse(sessionButton(activity).isEnabled)
        CarPlayBackgroundSession.clear()
        finishStop()
        refresh(activity)
        assertTrue(sessionButton(activity).isEnabled)
        assertEquals(activity.getString(R.string.choose_iphone), sessionButton(activity).text.toString())
    }

    @Test fun clickUsesTheLiveSessionWhenTheSelectionLabelIsStale() = forMazdaHome { activity ->
        val action = sessionButton(activity)
        startSession()
        action.performClick()
        assertEquals(1, stops)
        assertFalse(action.isEnabled)
        assertEquals(activity.getString(R.string.disconnect), action.text.toString())
        CarPlayBackgroundSession.clear()
        finishStop()
    }

    @Test fun clickOpensSelectionWhenTheDisconnectLabelIsStale() = forMazdaHome { activity ->
        startSession()
        refresh(activity)
        val action = sessionButton(activity)
        CarPlayBackgroundSession.clear()
        choosePairedPhone(activity, action)
        assertEquals(0, stops)
    }

    @Test fun aCompletedStopThatLeavesTheSessionPresentAllowsRetry() = forMazdaHome { activity ->
        startSession()
        refresh(activity)
        val action = sessionButton(activity)
        action.performClick()
        // A completion is not proof the session ended: keep the actual session present.
        finishStop()
        assertTrue(CarPlayBackgroundSession.hasSession())
        assertTrue(action.isEnabled)
        assertEquals(activity.getString(R.string.disconnect), action.text.toString())
        action.performClick()
        assertEquals(2, stops)
        CarPlayBackgroundSession.clear()
        finishStop()
        assertEquals(activity.getString(R.string.choose_iphone), action.text.toString())
        assertTrue(action.isEnabled)
    }

    @Test fun ordinaryHomeKeepsSeparateSelectionAndDisconnectButtons() {
        HomeResources.mazda = false
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("diplay", 0).edit().clear().commit()
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(app, DiPlayActivity::class.java).putExtra("page", "home")).setup()
        try {
            val activity = controller.get()
            val choose = descendants(activity.window.decorView).filterIsInstance<Button>()
                .single { it.text == activity.getString(R.string.choose_iphone) }
            val disconnect = ReflectionHelpers.getField<Button>(activity, "disconnectButton")
            assertEquals(View.GONE, disconnect.visibility)
            startSession(); refresh(activity)
            assertEquals(View.VISIBLE, disconnect.visibility)
            assertEquals(activity.getString(R.string.choose_iphone), choose.text.toString())
            choosePairedPhone(activity, choose)
            assertEquals(0, stops)
        } finally {
            CarPlayBackgroundSession.clear()
            finishStop()
            controller.pause().stop().destroy()
        }
    }

    private fun forMazdaHome(check: (DiPlayActivity) -> Unit) {
        for (mazda in listOf(true)) {
            HomeResources.mazda = mazda
            stops = 0
            CarPlayBackgroundSession.clear()
            val app = RuntimeEnvironment.getApplication()
            app.getSharedPreferences("diplay", 0).edit().clear().commit()
            app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
            val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
                Intent(app, DiPlayActivity::class.java).putExtra("page", "home")).setup()
            try {
                check(controller.get())
            } finally {
                CarPlayBackgroundSession.clear()
                finishStop()
                controller.pause().stop().destroy()
                HomeResources.mazda = false
            }
        }
    }

    private fun startSession() {
        CarPlayBackgroundSession.store(mock(CarPlayController::class.java), mock(AndroidMediaSink::class.java),
            800, 480, Any(), CarPlaySessionDisplay(800, 480, Surface.ROTATION_0, false, false, 800, 480)) {
            stops++
            stopCompletion = it
        }
    }

    private fun finishStop() {
        val completion = stopCompletion
        stopCompletion = null
        completion?.invoke()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun choosePairedPhone(activity: DiPlayActivity, action: Button) {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        shadowOf(adapter).setEnabled(true)
        val device = adapter.getRemoteDevice("12:34:56:78:9A:BC")
        shadowOf(device).setName("Test iPhone")
        shadowOf(adapter).setBondedDevices(setOf(device))
        action.performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(dialog.isShowing)
        assertEquals(activity.getString(R.string.choose_your_iphone), shadowOf(dialog).title.toString())
        shadowOf(dialog).clickOnItem(0)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun refresh(activity: DiPlayActivity) = ReflectionHelpers.callInstanceMethod<Unit>(activity, "refreshStatus")
    private fun sessionButton(activity: DiPlayActivity): Button = ReflectionHelpers.getField<MzdHomeView>(activity, "mazdaHomeView").phoneSessionButton
    private fun connectButton(activity: DiPlayActivity): Button = ReflectionHelpers.getField(activity, "connectButton")
    private fun assertSingleSessionAction(activity: DiPlayActivity) {
        val labels = listOf(activity.getString(R.string.choose_iphone), activity.getString(R.string.disconnect))
        assertEquals(1, descendants(activity.window.decorView).filterIsInstance<Button>().count { it.text.toString() in labels })
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    @Implements(Resources::class)
    class HomeResources : ShadowResources() {
        @RealObject private lateinit var resources: Resources
        @Implementation fun getBoolean(id: Int): Boolean = if (id == R.bool.config_mazda) mazda else
            Shadow.directlyOn(resources, Resources::class.java, "getBoolean", ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, id))
        companion object { var mazda = false }
    }
}
