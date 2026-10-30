package com.shilapi.xcertplay.mazda.hudbench

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.shilapi.display.android.RouteSceneRenderer
import com.shilapi.display.android.HudMapLayers
import com.shilapi.display.api.*
import java.io.File
import java.security.MessageDigest
import java.time.Duration
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HudRouteReplayTest {
    @Test fun nativeReplayOnlyShowsTheMatchingRightJunctionAndThenRestoresTheUpdatingMap() {
        val controller = Robolectric.buildActivity(HudRouteReplayActivity::class.java).create().start().resume()
        try {
            val layers = ReflectionHelpers.getField<HudMapLayers>(controller.get(), "layers")
            val looper = shadowOf(android.os.Looper.getMainLooper())
            looper.idleFor(Duration.ofMillis(4000))
            assertEquals("sample-straight", layers.frame().map?.sourceRouteId)
            assertNull(layers.frame().junction)
            looper.idleFor(Duration.ofMillis(6000))
            val visible = layers.frame()
            assertEquals("sample-right", visible.map?.sourceRouteId)
            assertNotNull(visible.junction)
            looper.idleFor(Duration.ofMillis(1500))
            assertNull(layers.frame().junction)
            assertTrue(requireNotNull(layers.frame().map).revision > requireNotNull(visible.map).revision)
            assertNull(ReflectionHelpers.getField<Any?>(controller.get(), "output"))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun defaultReplayIsOnlyAPreviewAndLeavingThePageStopsIt() {
        val controller = Robolectric.buildActivity(HudRouteReplayActivity::class.java).create().start().resume()
        assertNull(ReflectionHelpers.getField<Any?>(controller.get(), "output"))
        controller.pause().stop()
        assertTrue(ReflectionHelpers.getField<Boolean>(controller.get(), "stopped"))
        assertNull(ReflectionHelpers.getField<Any?>(controller.get(), "endpoint"))
        controller.destroy()
    }

    @Test fun syntheticRouteReplayExportsNative178PixelFramesAndNeverClaimsCarPlayOrHudValidation() {
        val folder = File("build/reports/route-replay").apply { mkdirs() }
        val session = SourceSessionKey("offline-synthetic-replay", 1)
        val renderer = RouteSceneRenderer()
        val rows = JSONArray()
        val cards = StringBuilder()
        var now = 1000L
        var revision = 0L
        val store = RouteSceneStore(session, { now }, allowSampleReplay = true)
        fun save(name: String, scene: RouteScene?) {
            val image = Bitmap.createBitmap(178, 178, Bitmap.Config.ARGB_8888)
            renderer.draw(Canvas(image), scene)
            val pixels = IntArray(178 * 178)
            image.getPixels(pixels, 0, 178, 0, 0, 178, 178)
            val active = scene != null
            assertEquals(name, active, pixels.any { it == Color.rgb(0, 222, 255) })
            if (!active) assertTrue(name, pixels.all { it == Color.BLACK })
            val file = File(folder, "$name.png")
            file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
            rows.put(JSONObject().put("image", file.name).put("sha256", digest).put("width", 178).put("height", 178)
                .put("origin", "SAMPLE_REPLAY").put("hasRoutePixels", active))
            cards.append("<figure><figcaption>$name</figcaption><img src='${file.name}' width='178' height='178'><img src='${file.name}' width='356' height='356'></figure>")
            image.recycle()
        }
        for (case in HudRouteReplayFixtures.cases) for (progress in listOf(0.20, 0.55)) {
            now += 200
            val scene = case.scene(progress, session, ++revision, now)
            assertEquals(RouteSceneOrigin.SAMPLE_REPLAY, scene.origin)
            assertTrue(store.submit(scene))
            save("${case.id}-${(progress * 100).toInt()}", store.current())
        }
        now += 3001
        save("expired-clear", store.current())
        val last = HudRouteReplayFixtures.cases.first().scene(0.2, session, ++revision, now)
        val ended = RouteScene(session, ++revision, last.sourceRouteId, NavigationPhase.ENDED, last.route,
            emptyList(), last.position, last.headingDegrees, last.viewSpanMeters, now, last.origin)
        assertTrue(store.submit(ended)); save("ended-clear", store.current())
        store.close(); save("session-close", store.current())
        File(folder, "manifest.json").writeText(JSONObject().put("kind", "offline-synthetic-route-rendering")
            .put("carPlayGeometryVerified", false).put("hudDisplayVerified", false).put("frames", rows).toString(2))
        File(folder, "index.html").writeText("<!doctype html><meta charset='utf-8'><title>178×178 route replay</title>" +
            "<style>body{background:#111;color:#eee;font:16px system-ui}figure{display:inline-block;margin:12px}img{margin:8px;vertical-align:top}</style>" +
            "<h1>178×178 合成路线离线绘制</h1><p>SIM 样本，非 CarPlay 真实数据，非 HUD 实机截图。每组显示原尺寸与2倍预览。</p>" + cards)
    }
}
