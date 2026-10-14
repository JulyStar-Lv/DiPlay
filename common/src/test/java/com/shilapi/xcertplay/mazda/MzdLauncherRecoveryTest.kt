package com.shilapi.xcertplay.mazda

import android.content.Intent
import android.provider.Settings
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, shadows = [MzdOemReturnTest.MazdaResources::class])
class MzdLauncherRecoveryTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val receiver = MzdPackageReplacedReceiver()

    @Before fun setUp() {
        MzdOemReturnTest.MazdaResources.enabled = true
        status("main_display_status", null)
        status("copilot_display_status", null)
    }

    @Test fun updateRefreshesTheStalePassengerTaskBeforeAnyActivityStarts() {
        status("copilot_display_status", "copilot_display_status 1 ${app.packageName} old.CarPlayHostActivity")
        receiver.onReceive(app, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertEquals(listOf("area_right"), homeAreas())
        assertNull("Update recovery must not start a DiPlay UI or session", shadowOf(app).nextStartedActivity)
        assertEquals("copilot_display_status 1 ${app.packageName} old.CarPlayHostActivity",
            Settings.Global.getString(app.contentResolver, "copilot_display_status"))
    }

    @Test fun updateRefreshesBothStaleRecordsButDoesNotTouchOtherApplications() {
        status("main_display_status", "main_display_status 1 ${app.packageName} old.DiPlayActivity")
        status("copilot_display_status", "copilot_display_status 1 ${app.packageName} old.CarPlayHostActivity")
        receiver.onReceive(app, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertEquals(listOf("area_left", "area_right"), homeAreas())
    }

    @Test fun anotherPassengerAppIsNotSentHome() {
        status("main_display_status", "main_display_status 1 ${app.packageName} old.DiPlayActivity")
        status("copilot_display_status", "copilot_display_status 1 com.wt.scene scene.Activity")
        receiver.onReceive(app, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertEquals(listOf("area_left"), homeAreas())
    }

    @Test fun hiddenAndMalformedRecordsDoNotChangeTheDesktop() {
        for (value in listOf(null, "copilot_display_status 0 ${app.packageName} old.Activity",
                "main_display_status 1 ${app.packageName} old.Activity", "copilot_display_status 1")) {
            status("copilot_display_status", value)
            receiver.onReceive(app, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        }
        assertTrue(homeAreas().isEmpty())
    }

    @Test fun unrelatedBroadcastsAndOtherVariantsDoNotRunRecovery() {
        status("copilot_display_status", "copilot_display_status 1 ${app.packageName} old.Activity")
        receiver.onReceive(app, Intent(Intent.ACTION_PACKAGE_REPLACED))
        assertTrue(homeAreas().isEmpty())
        MzdOemReturnTest.MazdaResources.enabled = false
        try {
            receiver.onReceive(app, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
            assertTrue(homeAreas().isEmpty())
        } finally { MzdOemReturnTest.MazdaResources.enabled = true }
    }

    private fun status(key: String, value: String?) = Settings.Global.putString(app.contentResolver, key, value)
    private fun homeAreas() = shadowOf(app).broadcastIntents.map {
        assertEquals("wt.intent.action.gohome", it.action)
        assertEquals("com.tinnove.launcher", it.`package`)
        it.getStringExtra("launcher_display_area")
    }
}
