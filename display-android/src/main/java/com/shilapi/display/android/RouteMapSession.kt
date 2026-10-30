package com.shilapi.display.android

import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.shilapi.display.api.RouteScene
import com.shilapi.display.api.SourceSessionKey
import java.util.concurrent.atomic.AtomicBoolean

/** Optional vector producer for the existing OEM pixel output. The composition root must grant
 * navigation ownership separately. Never attach this alongside a phone decoder to the same lease.
 * The surface is owned by VehicleMapOutput and is released only after detach acknowledges it. */
class RouteMapSession(
    output: VehicleMapOutput,
    session: SourceSessionKey,
    clock: () -> Long,
    allowSampleReplay: Boolean = false,
    presentation: RouteMapPresentation = RouteMapPresentation.DEFAULT,
    private val reportFailure: (String) -> Unit = {},
) : MapSurfaceConsumer, AutoCloseable {
    private val store = HudMapLayers(session, clock, allowSampleReplay)
    private val renderer = HudMapRenderer(presentation)
    private val frameIntervalMillis = 1000L / presentation.framesPerSecond.also { require(it in 1..30) }
    private val thread = HandlerThread("diplay-route-renderer").apply { start() }
    private val handler = Handler(thread.looper)
    private var surface: Surface? = null // renderer thread only
    private var closed = false
    private val retiring = AtomicBoolean()
    private val lease = output.attach(this)
    private val drawFrame = object : Runnable {
        override fun run() {
            if (closed) return
            draw()
            handler.postDelayed(this, frameIntervalMillis)
        }
    }

    init {
        lease.videoSize(presentation.renderWidth ?: output.request.width, presentation.renderHeight ?: output.request.height)
        lease.streamActive(true)
        handler.post(drawFrame)
    }

    fun submit(scene: RouteScene): Boolean = store.submit(scene)
    fun showJunction(event: JunctionImageEvent, image: RasterJunctionImage): Boolean = store.showJunction(event, image)
    fun hideJunction(event: JunctionImageEvent): Boolean = store.hideJunction(event)

    override fun attach(surface: Surface) {
        handler.post { if (!closed) { this.surface = surface; draw() } }
    }

    override fun detach(surface: Surface, released: () -> Unit) {
        if (!handler.post {
            if (this.surface === surface) this.surface = null
            try { released() } finally { if (closed && this.surface == null) thread.quitSafely() }
        }) released() // queue is closed only after the last surface was detached
    }

    private fun draw() {
        val target = surface?.takeIf { it.isValid } ?: return
        try {
            val canvas = target.lockCanvas(null)
            try { renderer.draw(canvas, store.frame()) } finally { target.unlockCanvasAndPost(canvas) }
        } catch (error: RuntimeException) {
            // Keep route coordinates and raw exception messages out of diagnostics.
            reportFailure(error.javaClass.simpleName)
        }
    }

    override fun close() {
        if (!retiring.compareAndSet(false, true)) return
        store.close()
        try { lease.close() } finally {
            handler.post {
                closed = true
                handler.removeCallbacks(drawFrame)
                draw() // black while the OEM window is being retired
                if (surface == null) thread.quitSafely()
            }
        }
    }
}
