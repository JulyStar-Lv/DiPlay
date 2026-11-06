package com.shilapi.xcertplay.mazda

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import com.shilapi.xcertplay.CarPlayBackgroundSession
import com.shilapi.xcertplay.CarPlayHostActivity
import com.shilapi.xcertplay.CarPlaySessionDisplay
import com.shilapi.xcertplay.DiPlayActivity
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.util.concurrent.ExecutorService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 34], shadows = [MzdOemReturnTest.MazdaResources::class])
class MzdDisconnectTest {
    @Test fun disconnectClosesOnlyTheHostWithoutAnimatingAndCompletesAfterTeardown() = checkStop(true)
    @Test fun otherVariantsKeepTheirWindowTransitions() = checkStop(false)

    @Suppress("DEPRECATION")
    private fun checkStop(mazda: Boolean) {
        MzdOemReturnTest.MazdaResources.enabled = mazda
        val home = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        val controller = mock(CarPlayController::class.java)
        val sink = mock(AndroidMediaSink::class.java)
        val executor = mock(ExecutorService::class.java)
        val tasks = mutableListOf<Runnable>()
        doAnswer { tasks += it.getArgument<Runnable>(0); null }.`when`(executor).execute(any(Runnable::class.java))
        ReflectionHelpers.getField<ExecutorService>(host, "teardownExecutor").shutdownNow()
        ReflectionHelpers.setField(host, "teardownExecutor", executor)
        ReflectionHelpers.setField(host, "controller", controller)
        ReflectionHelpers.setField(host, "sink", sink)
        home.setContentView(View(home))
        val originalHome = home.window.decorView
        host.window.setWindowAnimations(android.R.style.Animation_Dialog)
        host.overridePendingTransition(101, 202)
        var completions = 0
        CarPlayBackgroundSession.clear()
        CarPlayBackgroundSession.store(controller, sink, 800, 480, host,
            CarPlaySessionDisplay(800, 480, 0, false, false, 800, 480)) { completion ->
            ReflectionHelpers.callInstanceMethod<Unit>(host, "stopBackgroundSession",
                ReflectionHelpers.ClassParameter.from(Function0::class.java, completion))
        }
        try {
            CarPlayBackgroundSession.stop { completions++ }
            CarPlayBackgroundSession.stop { completions++ }
            assertTrue(host.isFinishing)
            assertFalse(home.isFinishing)
            assertSame(originalHome, home.window.decorView)
            assertEquals(View.VISIBLE, originalHome.visibility)
            assertEquals(if (mazda) View.GONE else View.VISIBLE, host.window.decorView.visibility)
            assertNull(shadowOf(host).nextStartedActivity)
            assertEquals(0, completions)
            assertTrue(CarPlayBackgroundSession.isStopping())
            assertTrue(CarPlayBackgroundSession.hasSession())
            assertNull(CarPlayBackgroundSession.snapshot())
            val shadow = shadowOf(host)
            assertEquals(if (mazda) 0 else android.R.style.Animation_Dialog, host.window.attributes.windowAnimations)
            assertEquals(if (mazda) 0 else 101, shadow.pendingTransitionEnterAnimationResourceId)
            assertEquals(if (mazda) 0 else 202, shadow.pendingTransitionExitAnimationResourceId)
            if (Build.VERSION.SDK_INT >= 34) {
                val transition = shadow.getOverriddenActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE)
                if (mazda) {
                    assertNotNull(transition)
                    assertEquals(0, transition!!.enterAnim)
                    assertEquals(0, transition.exitAnim)
                } else assertNull(transition)
            }
            tasks.single().run()
            shadowOf(Looper.getMainLooper()).idle()
            verify(controller).close()
            verify(sink).close()
            assertEquals(2, completions)
            assertFalse(CarPlayBackgroundSession.isStopping())
            assertFalse(CarPlayBackgroundSession.hasSession())
        } finally {
            ReflectionHelpers.getField<Handler>(host, "mainHandler").removeCallbacksAndMessages(null)
            ReflectionHelpers.getField<ExecutorService>(host, "airPlayCommandExecutor").shutdownNow()
            CarPlayBackgroundSession.clear()
            home.finish()
            host.finish()
            MzdOemReturnTest.MazdaResources.enabled = true
        }
    }
}
