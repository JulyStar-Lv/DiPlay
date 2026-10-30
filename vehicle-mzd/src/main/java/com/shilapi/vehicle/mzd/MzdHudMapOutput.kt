package com.shilapi.vehicle.mzd

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import com.shilapi.display.android.*
import com.shilapi.display.api.NavigationJunction
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal interface MzdMapGate : VehicleMapOutput {
    fun navigationAllowed(allowed: Boolean)
    fun navigationAllowed(allowed: Boolean, junction: NavigationJunction?) = navigationAllowed(allowed)
}

/** Only the measured ordinary J90K layout. Pixel coordinates never escape the OEM module. */
internal object MzdHudMapProfile {
    const val DISPLAY_NAME = "MeterDisplay#0"
    const val WIDTH = 800
    const val HEIGHT = 480
    const val LEFT = 584
    const val TOP = 179
    const val SIZE = 178
    // Negotiate near the physical viewport resolution. Rendering 352px then shrinking to
    // 178px made phone-drawn labels and route strokes too small on the measured HUD.
    // The 192px bench trial enlarged camera icons/watermarks excessively. 256 keeps
    // more road context while reducing the original downsampling; validate per app.
    // The unobscured marker area is centred horizontally and biased downwards, leaving
    // more map ahead. This is a source layout request; phone apps may interpret it
    // differently. Never simulate marker placement by cropping the decoded route.
    val request = MapStreamRequest(
        width = 256, height = 256, framesPerSecond = 15,
        insetLeft = 64, insetTop = 128, insetRight = 64, insetBottom = 24,
    )
    fun request(content: MapStreamContent): MapStreamRequest = when (content) {
        MapStreamContent.MAP -> request
        // Phone-composed cards need the entire square, not the map's lower marker region.
        // Combined content is a probe: its card may contain only arrows/text, not a junction image.
        MapStreamContent.INSTRUCTION_CARD, MapStreamContent.MAP_WITH_INSTRUCTION_CARD ->
            MapStreamRequest(256, 256, 15, content = content)
    }
    fun accepts(name: String, width: Int, height: Int, rotation: Int, id: Int, valid: Boolean) =
        name == DISPLAY_NAME && width == WIDTH && height == HEIGHT && rotation == Surface.ROTATION_0 &&
            id != Display.DEFAULT_DISPLAY && valid

    /** Fit the entire source into the square; never stretch/crop an unexpected phone format. */
    fun fittedSize(width: Int, height: Int): Pair<Float, Float> {
        require(width > 0 && height > 0)
        val scale = SIZE.toFloat() / maxOf(width, height)
        return width * scale to height * scale
    }
}

internal class MzdHudMapOutput(context: Context, private val report: (String) -> Unit,
    content: MapStreamContent = MapStreamContent.MAP) : MzdMapGate {
    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val manager = this.context.getSystemService(DisplayManager::class.java)
    private data class Gate(val allowed: Boolean, val junction: NavigationJunction?)
    private val gate = AtomicReference(Gate(false, null))
    private var current: Lease? = null // main thread only
    private var presentation: MzdHudMapPresentation? = null
    private var unavailable: String? = null
    override val request = MzdHudMapProfile.request(content)

    override fun navigationAllowed(allowed: Boolean) = navigationAllowed(allowed, null)

    override fun navigationAllowed(allowed: Boolean, junction: NavigationJunction?) {
        val diagram = junction?.takeIf { allowed && JunctionDiagramRenderer.supports(it) }?.let {
            it.copy(branchAnglesDegrees = it.branchAnglesDegrees?.toList())
        }
        val next = Gate(allowed, diagram)
        val previous = gate.getAndSet(next)
        if (previous != next) {
            if (previous.allowed != allowed) report("hudmap action=gate allowed=$allowed")
            handler.post { reconcile() }
        }
    }

    override fun attach(consumer: MapSurfaceConsumer): VehicleMapLease = Lease(consumer).also { next ->
        handler.post {
            if (next.closed.get()) return@post
            current?.closed?.set(true)
            dismiss()
            if (current == null) manager?.registerDisplayListener(listener, handler)
            current = next
            report("hudmap action=attached request=${request.width}x${request.height} fps=${request.framesPerSecond} content=${request.content.name.lowercase(java.util.Locale.ROOT)}")
            reconcile()
        }
    }

    private inner class Lease(val consumer: MapSurfaceConsumer) : VehicleMapLease {
        val closed = AtomicBoolean(false)
        var active = false
        var width = request.width
        var height = request.height
        override fun streamActive(active: Boolean) {
            handler.post {
                if (current !== this || closed.get()) return@post
                if (this.active == active) return@post
                this.active = active
                report("hudmap action=stream active=$active")
                // Replace the texture on stream changes, so a previous stream's buffered frame
                // cannot be presented as the first frame of its successor.
                dismiss()
                reconcile()
            }
        }
        override fun videoSize(width: Int, height: Int) {
            if (width !in 1..8192 || height !in 1..8192) return
            handler.post {
                if (current !== this || closed.get()) return@post
                this.width = width; this.height = height
                presentation?.videoSize(width, height)
                report("hudmap action=format size=${width}x$height fit=contain")
            }
        }
        override fun close() {
            closed.set(true)
            handler.post {
                if (current !== this) return@post
                dismiss()
                current = null
                manager?.unregisterDisplayListener(listener)
                report("hudmap action=detached")
            }
        }
    }

    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = reconcile()
        override fun onDisplayRemoved(displayId: Int) = reconcile()
        override fun onDisplayChanged(displayId: Int) = reconcile()
    }

    @Suppress("DEPRECATION")
    private fun target(): Display? {
        val displays = manager?.displays?.filter { it.name == MzdHudMapProfile.DISPLAY_NAME }.orEmpty()
        if (displays.size != 1) return null
        return displays.single().takeIf {
            val metrics = DisplayMetrics().also(it::getRealMetrics)
            MzdHudMapProfile.accepts(it.name, metrics.widthPixels, metrics.heightPixels,
                it.rotation, it.displayId, it.isValid) && it.state != Display.STATE_OFF
        }
    }

    private fun reconcile() {
        val lease = current
        val value = gate.get()
        if (lease == null || lease.closed.get() || !value.allowed || (!lease.active && value.junction == null)) {
            dismiss()
            return
        }
        val display = target()
        if (display == null) {
            dismiss()
            if (unavailable != "display") report("hudmap action=waiting reason=display_profile")
            unavailable = "display"
            return
        }
        unavailable = null
        if (presentation?.display?.displayId == display.displayId) {
            presentation?.navigationJunction(value.junction)
            return
        }
        dismiss()
        val next = MzdHudMapPresentation(context, display, lease.consumer, request.content, value.junction, report)
        try {
            next.show()
            next.videoSize(lease.width, lease.height)
            presentation = next
            report("hudmap action=window display=${display.displayId} canvas=800x480 viewport=584,179,178,178")
        } catch (error: RuntimeException) {
            next.dismiss()
            report("hudmap action=unavailable error=${error.javaClass.simpleName}")
        }
    }

    private fun dismiss() {
        val old = presentation
        presentation = null
        if (old != null) { old.dismiss(); report("hudmap action=cleared") }
    }
}

private class MzdHudMapPresentation(
    context: Context,
    display: Display,
    private val consumer: MapSurfaceConsumer,
    private val content: MapStreamContent,
    private val initialJunction: NavigationJunction?,
    private val report: (String) -> Unit,
) : Presentation(context, display, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen) {
    private var texture: TextureView? = null
    private var cardTone: MzdHudCardToneController? = null
    private var diagram: JunctionDiagramView? = null
    private var diagramShowing = false
    private var frameShown = false
    private var sourceWidth = MzdHudMapProfile.request.width
    private var sourceHeight = MzdHudMapProfile.request.height

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_FULLSCREEN or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
            decorView.setPadding(0, 0, 0, 0)
            decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
        val root = FrameLayout(context).apply { setBackgroundColor(Color.TRANSPARENT) }
        val viewport = FrameLayout(context).apply { setBackgroundColor(Color.BLACK); clipChildren = true }
        // Transparent hardware layers keep both the raw texture and the black letterbox
        // outside the tone conversion. Two stages preserve the OEM secondary grey.
        val targetLayer = FrameLayout(context)
        val sourceLayer = FrameLayout(context)
        texture = TextureView(context).apply {
            alpha = 0f
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                private var surface: Surface? = null
                override fun onSurfaceTextureAvailable(value: SurfaceTexture, width: Int, height: Int) {
                    value.setDefaultBufferSize(sourceWidth, sourceHeight)
                    transform()
                    surface = Surface(value).also(consumer::attach)
                }
                override fun onSurfaceTextureSizeChanged(value: SurfaceTexture, width: Int, height: Int) = transform()
                override fun onSurfaceTextureUpdated(value: SurfaceTexture) {
                    val tone = cardTone
                    if (tone == null) alpha = 1f else tone.frameAvailable()
                    if (!frameShown) { frameShown = true; report("hudmap action=first_texture_frame") }
                }
                override fun onSurfaceTextureDestroyed(value: SurfaceTexture): Boolean {
                    val owned = surface
                    surface = null
                    if (owned == null) value.release() else consumer.detach(owned) {
                        owned.release()
                        value.release()
                    }
                    // Retain the texture until the decoder acknowledges detachment.
                    return false
                }
            }
        }
        if (MzdHudCardTone.enabled(content))
            cardTone = MzdHudCardToneController(requireNotNull(texture), viewport, sourceLayer, targetLayer, report)
        sourceLayer.addView(texture, FrameLayout.LayoutParams(-1, -1))
        targetLayer.addView(sourceLayer, FrameLayout.LayoutParams(-1, -1))
        viewport.addView(targetLayer, FrameLayout.LayoutParams(-1, -1))
        root.addView(viewport, FrameLayout.LayoutParams(MzdHudMapProfile.SIZE, MzdHudMapProfile.SIZE,
            Gravity.TOP or Gravity.LEFT).apply { leftMargin = MzdHudMapProfile.LEFT; topMargin = MzdHudMapProfile.TOP })
        // Keep the diagram outside the phone card's tone-filter parent. The green route must
        // retain its colour, and the video decoder keeps exclusive ownership of its Surface.
        diagram = JunctionDiagramView(context, MzdHudRoutePresentation.style).also {
            root.addView(it, FrameLayout.LayoutParams(MzdHudMapProfile.SIZE, MzdHudMapProfile.SIZE,
                Gravity.TOP or Gravity.LEFT).apply { leftMargin = MzdHudMapProfile.LEFT; topMargin = MzdHudMapProfile.TOP })
        }
        navigationJunction(initialJunction)
        setContentView(root, ViewGroup.LayoutParams(-1, -1))
        window?.apply { setLayout(-1, -1); setGravity(Gravity.TOP or Gravity.LEFT) }
    }

    override fun onStop() {
        diagram?.submit(null)
        diagram = null
        cardTone?.close()
        cardTone = null
        super.onStop()
    }

    fun videoSize(width: Int, height: Int) {
        sourceWidth = width; sourceHeight = height
        transform()
    }

    fun navigationJunction(value: NavigationJunction?) {
        diagram?.submit(value)
        val showing = JunctionDiagramRenderer.supports(value)
        if (diagramShowing != showing) {
            diagramShowing = showing
            report("hudmap action=junction_diagram showing=$showing branches=${value?.branchAnglesDegrees?.size ?: 0}")
        }
    }

    private fun transform() {
        val (width, height) = MzdHudMapProfile.fittedSize(sourceWidth, sourceHeight)
        val size = MzdHudMapProfile.SIZE.toFloat()
        texture?.setTransform(Matrix().apply {
            setScale(width / size, height / size)
            postTranslate((size - width) / 2f, (size - height) / 2f)
        })
    }
}
