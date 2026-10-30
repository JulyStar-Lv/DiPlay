package com.shilapi.vehicle.mzd

import android.app.Presentation
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.os.Looper
import android.view.Display
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import com.shilapi.display.android.MapSurfaceConsumer
import com.shilapi.display.android.MapStreamContent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class MzdHudMapWindowTest {
    @Test fun cardToneIsScopedToTheViewportAndDisposedWithTheWindow() {
        val context = RuntimeEnvironment.getApplication()
        val id = ShadowDisplayManager.addDisplay("w800dp-h480dp-mdpi", 5)
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(id)
        shadowOf(display).apply { setName("MeterDisplay#0"); setFlags(Display.FLAG_PRESENTATION); setRotation(0) }
        try {
            for (content in MapStreamContent.entries) {
                val output = MzdHudMapOutput(context, {}, content)
                val lease = output.attach(object : MapSurfaceConsumer {
                    override fun attach(surface: Surface) = Unit
                    override fun detach(surface: Surface, released: () -> Unit) = released()
                })
                lease.streamActive(true)
                output.navigationAllowed(true)
                shadowOf(Looper.getMainLooper()).idle()
                val window: Presentation = ReflectionHelpers.getField(output, "presentation")
                val tone: MzdHudCardToneController? = ReflectionHelpers.getField(window, "cardTone")
                assertEquals(content == MapStreamContent.INSTRUCTION_CARD, tone != null)
                if (tone != null) {
                    val viewport: android.view.View = ReflectionHelpers.getField(tone, "viewport")
                    val params = viewport.layoutParams as FrameLayout.LayoutParams
                    assertEquals(listOf(584, 179, 178, 178), listOf(params.leftMargin, params.topMargin, params.width, params.height))
                }
                lease.close()
                shadowOf(Looper.getMainLooper()).idle()
                assertNull(ReflectionHelpers.getField<Any?>(window, "cardTone"))
                if (tone != null) assertTrue(ReflectionHelpers.getField(tone, "closed"))
            }
        } finally { ShadowDisplayManager.removeDisplay(id) }
    }

    @Test fun windowIsGatedConfinedAndRetainsTextureUntilDecoderDetach() {
        val context = RuntimeEnvironment.getApplication()
        val id = ShadowDisplayManager.addDisplay("w800dp-h480dp-mdpi", 5)
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(id)
        shadowOf(display).apply { setName("MeterDisplay#0"); setFlags(Display.FLAG_PRESENTATION); setRotation(Surface.ROTATION_0) }
        val events = mutableListOf<String>()
        var attached: Surface? = null
        var release: (() -> Unit)? = null
        val output = MzdHudMapOutput(context, events::add)
        val lease = output.attach(object : MapSurfaceConsumer {
            override fun attach(surface: Surface) { attached = surface }
            override fun detach(surface: Surface, released: () -> Unit) {
                assertSame(attached, surface)
                release = released
            }
        })
        fun idle() = shadowOf(Looper.getMainLooper()).idle()
        fun window(): Presentation? = ReflectionHelpers.getField(output, "presentation")
        try {
            lease.streamActive(true)
            idle()
            assertNull(window())
            output.navigationAllowed(true)
            idle()
            assertNotNull("$events displays=${context.getSystemService(DisplayManager::class.java).displays.toList()}", window())
            val presentation = requireNotNull(window())
            assertEquals(id, presentation.display.displayId)
            val root = presentation.findViewById<FrameLayout>(android.R.id.content).getChildAt(0) as FrameLayout
            val viewport = root.getChildAt(0) as FrameLayout
            val params = viewport.layoutParams as FrameLayout.LayoutParams
            assertEquals(listOf(584, 179, 178, 178), listOf(params.leftMargin, params.topMargin, params.width, params.height))
            val targetLayer = viewport.getChildAt(0) as FrameLayout
            val sourceLayer = targetLayer.getChildAt(0) as FrameLayout
            val texture = sourceLayer.getChildAt(0) as TextureView
            val nativeTexture = SurfaceTexture(0)
            val listener = requireNotNull(texture.surfaceTextureListener)
            listener.onSurfaceTextureAvailable(nativeTexture, 178, 178)
            assertNotNull(attached)
            assertEquals(0f, texture.alpha, 0f)
            listener.onSurfaceTextureUpdated(nativeTexture)
            assertEquals(1f, texture.alpha, 0f)
            assertFalse(listener.onSurfaceTextureDestroyed(nativeTexture))
            assertNotNull(release)
            release!!.invoke()
            lease.streamActive(false)
            idle()
            assertNull(window())
            lease.streamActive(true)
            idle()
            assertNotSame(presentation, window())
            ShadowDisplayManager.removeDisplay(id)
            idle()
            assertNull(window())
        } finally {
            lease.close()
            idle()
            if (context.getSystemService(DisplayManager::class.java).getDisplay(id) != null)
                ShadowDisplayManager.removeDisplay(id)
        }
    }
}
