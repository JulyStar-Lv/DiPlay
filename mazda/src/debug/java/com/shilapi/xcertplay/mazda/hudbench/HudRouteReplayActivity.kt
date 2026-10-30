package com.shilapi.xcertplay.mazda.hudbench

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.display.android.*
import com.shilapi.display.api.*
import com.shilapi.vehicle.mzd.MzdHudRoutePresentation
import java.util.UUID

/** Finite synthetic replay. Preview by default; physical HUD requires an explicit debug intent. */
class HudRouteReplayActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val session = SourceSessionKey("sample-route-${UUID.randomUUID()}", 1)
    private var output: RouteMapSession? = null
    private var endpoint: VehicleDisplayEndpoint? = null
    private var ownsBench = false
    private var stopped = false
    private var revision = 0L
    private var started = 0L
    private val layers = HudMapLayers(session, { System.nanoTime() / 1_000_000 }, allowSampleReplay = true)
    private val junction by lazy { HudJunctionReplaySample.create() }
    private var visibleJunction: String? = null
    private var junctionRevision = 0L
    private lateinit var preview: Preview
    private lateinit var status: TextView
    private val app get() = application as HudBenchApplication
    private val tick = object : Runnable {
        override fun run() {
            if (stopped) return
            val elapsed = SystemClock.elapsedRealtime() - started
            val index = (elapsed / 6000).toInt()
            if (index >= HudRouteReplayFixtures.cases.size) { stopReplay(); return }
            val case = HudRouteReplayFixtures.cases[index]
            val now = System.nanoTime() / 1_000_000
            val progress = (elapsed % 6000) / 6000.0
            val distance = when { progress < 0.25 -> 900L; progress < 0.5 -> 450L; else -> 150L }
            val scene = case.scene(progress, session, ++revision, now, farViewSpanScale = 4.0,
                distanceToManeuverMeters = distance)
            layers.submit(scene)
            output?.submit(scene)
            val junctionId = "sample-${case.id}"
            val show = case.id == "right" && progress in 0.6..0.85
            if (show && visibleJunction != junctionId) {
                val event = JunctionImageEvent(session, scene.sourceRouteId, junctionId, ++junctionRevision, now, scene.origin)
                layers.showJunction(event, junction); output?.showJunction(event, junction)
                visibleJunction = junctionId
            } else if (!show && visibleJunction != null) {
                val event = JunctionImageEvent(session, scene.sourceRouteId, junctionId, ++junctionRevision, now, scene.origin)
                layers.hideJunction(event); output?.hideJunction(event)
                visibleJunction = null
            }
            preview.frame = layers.frame(); preview.invalidate()
            // All TBT values below are synthetic, exclusively inside the explicit debug replay.
            endpoint?.submit(DisplaySnapshot(session, revision, NavigationState(NavigationPhase.GUIDING,
                distanceMeters = distance, remainingMeters = 2000, remainingMillis = 120000, receivedAtMillis = now)))
            status.text = "SIM 合成路线：${case.id} / ${distance}m / ${if (show) "合成栅格路口" else "小地图"}\n" +
                "${if (output == null) "仅本机预览" else "台架HUD模拟"}；不代表CarPlay实图通过"
            handler.postDelayed(this, 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        status = TextView(this).apply { text = "SIM 合成路线回放"; textSize = 18f }
        preview = Preview(this)
        body.addView(status)
        val size = (178 * resources.displayMetrics.density).toInt()
        body.addView(preview, LinearLayout.LayoutParams(size, size))
        body.addView(Button(this).apply { text = "停止并清屏"; setOnClickListener { stopReplay() } })
        setContentView(body)
        if (intent.getBooleanExtra("project_hud", false)) {
            try {
                check(app.hudEnabled) { "构建未启用HUD" }
                val factory = app.ownership.acquire { handler.post { stopReplay() } }
                ownsBench = true
                endpoint = factory.open(session)
                output = RouteMapSession(requireNotNull(app.driver.auxiliaryMapOutput), session,
                    { System.nanoTime() / 1_000_000 }, allowSampleReplay = true, presentation = MzdHudRoutePresentation)
            } catch (error: RuntimeException) {
                stopReplay(); status.text = "未开始HUD模拟：${error.javaClass.simpleName}"
                return
            }
        }
        started = SystemClock.elapsedRealtime()
        handler.post(tick)
    }

    private fun stopReplay() {
        if (stopped) return
        stopped = true; handler.removeCallbacks(tick)
        output?.close(); output = null
        endpoint?.close(); endpoint = null
        if (ownsBench) { app.ownership.release(); ownsBench = false }
        layers.close()
        preview.frame = layers.frame(); preview.invalidate()
        status.text = "SIM 回放已停止；实车出图尚未验收"
    }
    override fun onStop() { stopReplay(); super.onStop() }

    private class Preview(context: Context) : View(context) {
        var frame = HudMapFrame(null, null, null)
        private val renderer = HudMapRenderer(MzdHudRoutePresentation)
        override fun onDraw(canvas: Canvas) = renderer.draw(canvas, frame, width, height)
    }
}
