package com.shilapi.xcertplay.mazda.hudbench

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.shilapi.display.android.*
import com.shilapi.display.api.*
import com.shilapi.vehicle.mzd.MzdHudRoutePresentation
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HudNativeMapReplayTest {
    @Test fun nativePolicyAndJunctionLifecycleExportAt178PixelsWithExplicitSyntheticProvenance() {
        val folder = File("build/reports/hud-native-map").apply { mkdirs() }
        val session = SourceSessionKey("offline-native-style-sample", 1)
        var now = 1000L
        var revision = 0L
        val layers = HudMapLayers(session, { now }, allowSampleReplay = true)
        val renderer = HudMapRenderer(MzdHudRoutePresentation)
        val rows = JSONArray()
        val cards = StringBuilder()
        fun render(frame: HudMapFrame): Bitmap = Bitmap.createBitmap(178, 178, Bitmap.Config.ARGB_8888).also {
            renderer.draw(Canvas(it), frame)
        }
        fun pixels(bitmap: Bitmap) = IntArray(178 * 178).also { bitmap.getPixels(it, 0, 178, 0, 0, 178, 178) }
        fun save(name: String): IntArray {
            val frame = layers.frame()
            val bitmap = render(frame)
            val pixels = pixels(bitmap)
            val active = frame.map != null
            assertEquals(name, active, pixels.any { it == 0xff00ffa4.toInt() })
            if (!active) assertTrue(name, pixels.all { it == Color.BLACK })
            val file = File(folder, "$name.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            rows.put(JSONObject().put("image", file.name).put("sha256", digest).put("width", 178).put("height", 178)
                .put("origin", "SAMPLE_REPLAY").put("active", active).put("junctionVisible", frame.junction != null)
                .put("nativeZoomPolicyLevel", frame.map?.let { MzdHudRoutePresentation.zoomLevel(it.distanceToManeuverMeters) } ?: JSONObject.NULL))
            cards.append("<figure><figcaption>$name</figcaption><img src='${file.name}' width='178' height='178'>" +
                "<img src='${file.name}' width='356' height='356'></figure>")
            return pixels
        }
        for (case in HudRouteReplayFixtures.cases) {
            now += 100
            assertTrue(layers.submit(case.scene(0.20, session, ++revision, now, 4.0, 150)))
            save("near-${case.id}")
        }
        val right = HudRouteReplayFixtures.cases.first { it.id == "right" }
        for (distance in listOf(900L, 450L, 150L)) {
            now += 100
            layers.submit(right.scene(0.20, session, ++revision, now, 4.0, distance))
            save("distance-${distance}m")
        }
        val before = save("junction-before")
        val event = JunctionImageEvent(session, "sample-right", "sample-junction", 1, now, RouteSceneOrigin.SAMPLE_REPLAY)
        assertTrue(layers.showJunction(event, HudJunctionReplaySample.create()))
        val overlay = save("junction-show")
        now += 100
        val latest = right.scene(0.45, session, ++revision, now, 4.0, 150)
        assertTrue(layers.submit(latest))
        assertArrayEquals(overlay, save("junction-map-updated")) // opaque overlay keeps the same pixels
        assertTrue(layers.hideJunction(event.copy(revision = 2, receivedAtMillis = now)))
        val restored = save("junction-hide-latest")
        val expected = render(HudMapFrame(latest, null, null))
        assertArrayEquals(pixels(expected), restored)
        expected.recycle()
        assertFalse(before.contentEquals(restored)) // not the frozen map from before the show event
        now += 3001
        save("expired-clear")
        val ended = RouteScene(session, ++revision, latest.sourceRouteId, NavigationPhase.ENDED, latest.route,
            emptyList(), latest.position, latest.headingDegrees, latest.viewSpanMeters, now, latest.origin)
        assertTrue(layers.submit(ended)); save("ended-clear")
        assertTrue(layers.submit(right.scene(0.20, session, ++revision, now, 4.0, 150)))
        assertTrue(layers.showJunction(event.copy(revision = 3, receivedAtMillis = now), HudJunctionReplaySample.create()))
        layers.close(); save("closed-clear")
        File(folder, "manifest.json").writeText(JSONObject().put("kind", "offline-native-policy-and-raster-layers")
            .put("carPlayGeometryVerified", false).put("carPlayJunctionImageVerified", false)
            .put("hudDisplayVerified", false).put("frames", rows).toString(2))
        File(folder, "index.html").writeText("<!doctype html><meta charset='utf-8'><title>HUD native policy replay</title>" +
            "<style>body{background:#111;color:#eee;font:16px system-ui}figure{display:inline-block;margin:12px}img{margin:8px;vertical-align:top}</style>" +
            "<h1>原厂分层方案：178×178 离线回放</h1><p>SIM 合成路线及栅格路口图；不是 CarPlay 图片，也不是台架截图。" +
            "每组为原尺寸及2倍预览。17/18/19为距离策略级别，本地相对缩放尚未校准原厂SDK绝对视野。</p>" + cards)
    }
}
