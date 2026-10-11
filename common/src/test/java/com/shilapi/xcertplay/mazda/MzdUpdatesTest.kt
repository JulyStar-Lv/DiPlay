package com.shilapi.xcertplay.mazda

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shilapi.xcertplay.DiPlayActivity
import com.shilapi.xcertplay.SettingsCategory
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.update.UpdateAvailability
import com.shilapi.xcertplay.update.UpdateRelease
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowResources
import org.robolectric.util.ReflectionHelpers
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-w900dp-h720dp-mdpi", shadows = [MzdUpdatesTest.MazdaResources::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MzdUpdatesTest {
    @Test fun cachedUpdatesHaveNoMazdaActionEvenWhenDownloadOrInstallIsReady() {
        for ((locale, width) in listOf("zh-rCN" to 360, "zh-rCN" to 900, "ar" to 900)) {
            RuntimeEnvironment.setQualifiers("$locale-w${width}dp-h720dp-mdpi")
            val app = RuntimeEnvironment.getApplication()
            app.applicationInfo.flags = app.applicationInfo.flags or ApplicationInfo.FLAG_SUPPORTS_RTL
            app.resources.updateConfiguration(Configuration(app.resources.configuration).apply { fontScale = 1.5f },
                app.resources.displayMetrics)
            val release = UpdateRelease("v99.0.0", "DiPlay.apk", "https://example.com/DiPlay.apk", "https://example.com/SHA256SUMS.txt")
            UpdateAvailability.save(app, release)
            assertEquals(release, UpdateAvailability.available(app, "0.2.18"))
            val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
                Intent(app, DiPlayActivity::class.java).putExtra("page", "settings")).setup()
            val activity = controller.get()
            try {
                assertTrue(MzdIntegration.enabled(activity))
                ReflectionHelpers.setField(activity, "settingsCategory", SettingsCategory.ABOUT)
                val stageType = DiPlayActivity::class.java.getDeclaredField("updateStage").type
                for (stage in stageType.enumConstants!!.filter { it.toString() in listOf("IDLE", "AVAILABLE", "READY") }) {
                    ReflectionHelpers.setField(activity, "updateRelease", release)
                    ReflectionHelpers.setField(activity, "updateStage", stage)
                    ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
                    val texts = descendants(activity.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }.toList()
                    assertTrue(texts.contains(activity.getString(R.string.mazda_update_manual)))
                    for (text in listOf(activity.getString(R.string.update_check), activity.getString(R.string.update_download),
                        activity.getString(R.string.update_install, release.tagName), activity.getString(R.string.settings_background_update_checks))) {
                        assertFalse("Mazda must not offer $text at $stage", texts.contains(text))
                    }
                }
                val root = activity.findViewById<View>(android.R.id.content)
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, width, 720)
                val bitmap = Bitmap.createBitmap(width, 720, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(bitmap))
                val output = File("build/reports/mazda-ui/about-$width-$locale-font-1.5.png")
                output.parentFile!!.mkdirs()
                output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                bitmap.recycle()
                ReflectionHelpers.setField(activity, "page", "home")
                ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
                assertFalse(descendants(activity.window.decorView).filterIsInstance<TextView>().any {
                    it.text == activity.getString(R.string.update_available_home)
                })
            } finally {
                controller.pause().stop().destroy()
                UpdateAvailability.clearAllForTest(app)
            }
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    @Implements(Resources::class)
    class MazdaResources : ShadowResources() {
        @RealObject private lateinit var resources: Resources
        @Implementation fun getBoolean(id: Int): Boolean = if (id == R.bool.config_mazda) true else
            Shadow.directlyOn(resources, Resources::class.java, "getBoolean", ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, id))
    }
}
