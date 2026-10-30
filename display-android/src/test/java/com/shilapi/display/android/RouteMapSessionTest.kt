package com.shilapi.display.android

import android.graphics.SurfaceTexture
import android.os.Handler
import android.view.Surface
import com.shilapi.display.api.SourceSessionKey
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class RouteMapSessionTest {
    @Test fun detachAcknowledgesOldSurfaceWithoutClearingItsSuccessorAndCloseRetiresTheLease() {
        lateinit var source: MapSurfaceConsumer
        var retired = false
        var videoSize: Pair<Int, Int>? = null
        val output = object : VehicleMapOutput {
            override val request = MapStreamRequest(256, 256, 15)
            override fun attach(consumer: MapSurfaceConsumer): VehicleMapLease {
                source = consumer
                return object : VehicleMapLease {
                    override fun streamActive(active: Boolean) = Unit
                    override fun videoSize(width: Int, height: Int) { videoSize = width to height }
                    override fun close() { retired = true }
                }
            }
        }
        val session = RouteMapSession(output, SourceSessionKey("test", 1), { 1000 }, presentation = object : RouteMapPresentation {
            override val renderWidth = 178
            override val renderHeight = 178
            override val framesPerSecond = 10
        })
        assertEquals(178 to 178, videoSize)
        assertEquals(100L, ReflectionHelpers.getField<Long>(session, "frameIntervalMillis").toLong())
        val handler = ReflectionHelpers.getField<Handler>(session, "handler")
        val firstTexture = SurfaceTexture(0)
        val secondTexture = SurfaceTexture(0)
        val first = Surface(firstTexture)
        val second = Surface(secondTexture)
        try {
            source.attach(first); source.attach(second)
            var acknowledged = false
            source.detach(first) { acknowledged = true }
            shadowOf(handler.looper).idle()
            assertTrue(acknowledged)
            assertSame(second, ReflectionHelpers.getField<Surface>(session, "surface"))
            session.close()
            assertTrue(retired)
            source.detach(second) { second.release() }
            shadowOf(handler.looper).idle()
        } finally {
            session.close(); first.release(); second.release()
            firstTexture.release(); secondTexture.release()
        }
    }
}
