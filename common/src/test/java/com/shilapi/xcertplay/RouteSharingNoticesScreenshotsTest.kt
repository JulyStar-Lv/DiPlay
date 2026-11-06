package com.shilapi.xcertplay

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RouteSharingNoticesScreenshotsTest {
    @Test fun requiredAttributionWrapsInCompactAndFullAboutAtLargeFont() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        try {
            for ((name, widthDp, tag) in listOf(Triple("compact-zh", 320, "zh-CN"),
                Triple("full-en", 960, "en-US"), Triple("compact-ar", 320, "ar-SA"))) {
                val locale = Locale.forLanguageTag(tag)
                val config = Configuration(activity.resources.configuration).apply {
                    fontScale = 1.5f; setLocale(locale); setLayoutDirection(locale)
                }
                @Suppress("DEPRECATION")
                activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
                val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                DiPlayActivity::class.java.getDeclaredMethod("about", LinearLayout::class.java)
                    .apply { isAccessible = true }.invoke(activity, content)
                val width = (widthDp * activity.resources.displayMetrics.density).toInt()
                content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                content.layout(0, 0, width, content.measuredHeight)
                val notice = descendants(content).filterIsInstance<TextView>().single {
                    it.text.toString().startsWith("Based on LIVI by Lasse Heitgres")
                }
                assertTrue(notice.layout.getEllipsisCount(notice.lineCount - 1) == 0)
                val bitmap = Bitmap.createBitmap(width, content.measuredHeight, Bitmap.Config.ARGB_8888)
                try {
                    content.draw(Canvas(bitmap))
                    val folder = File("build/reports/route-sharing-notices").apply { mkdirs() }
                    File(folder, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                } finally { bitmap.recycle() }
            }
        } finally { activity.finish() }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
