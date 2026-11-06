package com.shilapi.xcertplay.mazda.hudbench

import android.content.pm.PackageManager
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HudBenchDisabledTest {
    @Test fun explicitlyDisabledHudExportsFailureAndUnexecutedPlanWithoutOpeningDriver() {
        val app = RuntimeEnvironment.getApplication() as HudBenchApplication
        @Suppress("DEPRECATION")
        val packageInfo = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_META_DATA)
        packageInfo.applicationInfo!!.metaData.putBoolean("com.shilapi.vehicle.mzd.HUD_ENABLED", false)
        shadowOf(app.packageManager).installPackage(packageInfo)
        assertFalse(app.hudEnabled)
        val id = UUID.randomUUID().toString()
        var status = ""
        HudBenchRunner(app) { status = it }.run(id, "both", "music", false)
        val root = File(app.getExternalFilesDir(null), "hud-bench/$id")
        val report = JSONObject(File(root, "report.json").readText())
        assertEquals(status, "failed", report.getString("status"))
        assertEquals(16, report.getJSONArray("plannedCases").length())
        assertEquals(0, report.getJSONArray("cases").length())
        assertEquals(0, report.getJSONArray("captures").length())
        assertTrue(File(root, "finished.json").isFile)
        assertTrue(File(root, "report.zip").isFile)
    }
}
