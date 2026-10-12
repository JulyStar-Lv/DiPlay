package com.shilapi.xcertplay.mazda

import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RectShape
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.core.content.ContextCompat
import com.shilapi.xcertplay.host.R

/** Shared presentation only: connection state and actions remain owned by the existing Activities. */
internal open class MzdPageView(
    context: Context,
    night: Boolean,
    close: () -> Unit,
    settings: () -> Unit,
) : FrameLayout(context) {
    private val texts = mutableListOf<Pair<TextView, Boolean>>()
    private val buttons = mutableListOf<Pair<Button, Boolean>>()
    private val headerButtons = mutableListOf<Button>()
    private val pageBackground = ShapeDrawable(RectShape())
    private val illustrationBounds = Rect()
    private val exit = ImageButton(context)
    private val exitIconSize = resources.getDimensionPixelSize(R.dimen.mazda_header_close_size)
    private val exitSize = exitIconSize
    private val oemHeaderHeight = resources.getDimensionPixelSize(R.dimen.mazda_header_height)
    private val headerActionHeight = dp(56)
    private val headerHeight = maxOf(oemHeaderHeight, headerActionHeight)
    private val illustration = ConnectionIllustration(context)
    private val body = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(24), dp(16), dp(24), dp(16))
    }
    private val scroll = ScrollView(context).apply { isFillViewport = true }
    private val header = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        isBaselineAligned = false
        setPaddingRelative(resources.getDimensionPixelSize(R.dimen.mazda_header_start), 0, dp(24), 0)
    }
    val stage = text("", 22f, false)
    val instructions = text("", 17f, true)
    private val actions = LinearLayout(context).apply { gravity = Gravity.CENTER }
    val hint = text("", 14f, true)
    private var night = night

    init {
        isClickable = true
        background = pageBackground
        exit.apply {
            setImageResource(R.drawable.ic_mazda_close)
            contentDescription = context.getString(R.string.car_home)
            scaleType = ImageView.ScaleType.FIT_CENTER
            val padding = (exitSize - exitIconSize) / 2
            setPadding(padding, padding, padding, padding)
            setOnClickListener { close() }
        }
        header.addView(exit, LinearLayout.LayoutParams(exitSize, exitSize).apply {
            gravity = Gravity.TOP
            topMargin = (oemHeaderHeight - exitSize) / 2
        })
        val brandSize = resources.getDimensionPixelSize(R.dimen.mazda_header_brand_size)
        header.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_carplay)
            contentDescription = context.getString(R.string.carplay)
        }, LinearLayout.LayoutParams(brandSize, brandSize).apply {
            gravity = Gravity.TOP
            topMargin = (oemHeaderHeight - brandSize) / 2
            marginStart = resources.getDimensionPixelSize(R.dimen.mazda_header_title_gap)
            marginEnd = resources.getDimensionPixelSize(R.dimen.mazda_header_brand_gap)
        })
        header.addView(text(context.getString(R.string.diplay), 26f, false).apply {
            // Match FileManager's WTTextStyleHeadlineM40M, whose size is in physical pixels.
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.mazda_header_brand_size))
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, exitSize, 1f).apply {
            gravity = Gravity.TOP
            topMargin = (oemHeaderHeight - exitSize) / 2
        })
        header.addView(action(context.getString(R.string.settings), false, settings).also { headerButtons += it },
            headerActionLayout())
        body.addView(illustration, LinearLayout.LayoutParams(dp(440), dp(190)))
        body.addView(text(context.getString(R.string.carplay), 36f, false).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12); bottomMargin = dp(8) })
        body.addView(stage, LinearLayout.LayoutParams(-1, -2))
        body.addView(instructions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12); bottomMargin = dp(16) })
        body.addView(actions, LinearLayout.LayoutParams(-1, -2))
        body.addView(hint, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        scroll.addView(body)
        addView(scroll, LayoutParams(-1, -1).apply { topMargin = headerHeight; bottomMargin = dp(40) })
        addView(header, LayoutParams(-1, headerHeight))
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
        addView(text("v$version", 13f, true), LayoutParams(-2, dp(40), Gravity.BOTTOM or Gravity.END).apply {
            marginEnd = dp(24)
        })
        paint(night)
    }

    fun addAction(label: String, primary: Boolean = true, click: () -> Unit): Button =
        action(label, primary, click).also {
            actions.addView(it, LinearLayout.LayoutParams(0, dp(60), 1f).apply {
                marginStart = dp(8); marginEnd = dp(8)
            })
            paint(night)
        }

    fun addHeaderAction(label: String, click: () -> Unit): Button =
        action(label, false, click).also {
            headerButtons += it
            header.addView(it, header.childCount - 1, headerActionLayout())
            paint(night)
        }

    private fun headerActionLayout() = LinearLayout.LayoutParams(-2, headerActionHeight).apply {
        gravity = Gravity.TOP
        topMargin = ((oemHeaderHeight - headerActionHeight) / 2).coerceAtLeast(0)
    }

    fun addRecovery(button: Button) {
        (button.parent as? android.view.ViewGroup)?.removeView(button)
        button.textSize = 19f
        button.setPadding(dp(16), 0, dp(16), 0)
        buttons += button to false
        body.addView(button, body.indexOfChild(actions), LinearLayout.LayoutParams(-1, dp(60)).apply {
            bottomMargin = dp(12)
        })
        paint(night)
    }

    fun paint(night: Boolean) {
        this.night = night
        val foreground = if (night) Color.rgb(243, 246, 252) else Color.rgb(24, 42, 65)
        val muted = if (night) Color.rgb(168, 181, 203) else Color.rgb(99, 119, 146)
        pageBackground.paint.color = if (night) Color.rgb(2, 5, 11) else Color.rgb(253, 254, 255)
        updateBackground()
        texts.forEach { (view, secondary) -> view.setTextColor(if (secondary) muted else foreground) }
        buttons.forEach { (view, primary) ->
            view.setTextColor(if (primary) Color.WHITE else Color.rgb(15, 145, 250))
            val shape = GradientDrawable().apply {
                setColor(if (primary) Color.rgb(0, 132, 250) else if (night) Color.rgb(17, 34, 55) else Color.rgb(227, 239, 252))
                cornerRadius = dp(30).toFloat()
            }
            view.background = RippleDrawable(ColorStateList.valueOf(0x3377bfff), shape, null)
        }
        headerButtons.forEach {
            it.setTextColor(foreground)
            it.background = RippleDrawable(ColorStateList.valueOf(0x3377bfff), null,
                GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(28).toFloat() })
        }
        exit.imageTintList = ColorStateList.valueOf(foreground)
        exit.background = RippleDrawable(ColorStateList.valueOf(0x3377bfff), null,
            GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = exitSize / 2f })
        illustration.night = night
        illustration.invalidate()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        updateBackground()
    }

    private fun updateBackground() {
        if (width == 0 || height == 0 || illustration.width == 0) return
        illustration.getDrawingRect(illustrationBounds)
        offsetDescendantRectToMyCoords(illustration, illustrationBounds)
        val centreX = width / 2f
        val floorY = illustrationBounds.top + illustrationBounds.height() * 183f / 190f
        // The reference has white/black edges and an elliptical blue glow around the artwork.
        val colors = if (night) intArrayOf(Color.rgb(1, 33, 73), Color.rgb(1, 25, 54),
            Color.rgb(2, 13, 28), Color.rgb(2, 7, 14), Color.rgb(2, 5, 11)) else
            intArrayOf(Color.rgb(223, 241, 254), Color.rgb(231, 245, 254),
                Color.rgb(244, 249, 254), Color.rgb(251, 253, 254), Color.rgb(253, 254, 255))
        val halo = glow(centreX, floorY - height * .06f, width * .48f, height * .70f,
            colors, floatArrayOf(0f, .25f, .5f, .75f, 1f))
        val floor = glow(centreX, floorY, illustration.width * .9f, illustration.height * .06f,
            intArrayOf(if (night) 0x3d0064d4 else 0x4094ccff, Color.TRANSPARENT))
        val line = glow(centreX, floorY, illustration.width * .9f, maxOf(1f, illustration.height * .006f),
            intArrayOf(if (night) 0x3d1b85ff else 0x66ffffff, Color.TRANSPARENT))
        pageBackground.paint.shader = ComposeShader(ComposeShader(halo, floor, PorterDuff.Mode.SRC_OVER),
            line, PorterDuff.Mode.SRC_OVER)
        pageBackground.invalidateSelf()
    }

    private fun glow(x: Float, y: Float, radiusX: Float, radiusY: Float,
        colors: IntArray, stops: FloatArray? = null): Shader =
        RadialGradient(0f, 0f, 1f, colors, stops, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply { setScale(radiusX, radiusY); postTranslate(x, y) })
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val available = (width - paddingLeft - paddingRight - dp(48)).coerceAtLeast(0)
        hint.visibility = if (hint.text.isBlank()) View.GONE else View.VISIBLE
        val bodyHeightDp = (MeasureSpec.getSize(heightMeasureSpec) - paddingTop - paddingBottom - headerHeight - dp(40)) / resources.displayMetrics.density
        val artHeight = dp(minOf(150, (bodyHeightDp * .30f).toInt().coerceAtLeast(64)))
        illustration.layoutParams.width = minOf(artHeight * 440 / 190, available)
        illustration.layoutParams.height = illustration.layoutParams.width * 190 / 440
        actions.layoutParams.width = minOf(available, dp(if (actions.childCount == 1) 300 else 600))
        actions.orientation = if (available < dp(500) || resources.configuration.fontScale > 1.3f) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        for (i in 0 until actions.childCount) {
            actions.getChildAt(i).layoutParams = if (actions.orientation == LinearLayout.HORIZONTAL) {
                LinearLayout.LayoutParams(0, dp(60), 1f).apply { setMargins(dp(8), 0, dp(8), 0) }
            } else LinearLayout.LayoutParams(-1, dp(64)).apply { bottomMargin = dp(12) }
        }
        // Keep the reference's centred group, but cap the button/text column on the wide panel.
        body.setPadding(maxOf(dp(24), (width - dp(650)) / 2), dp(8), maxOf(dp(24), (width - dp(650)) / 2), dp(8))
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun action(label: String, primary: Boolean, click: () -> Unit) = Button(context).apply {
        text = label
        textSize = 19f
        isAllCaps = false
        setPadding(dp(16), 0, dp(16), 0)
        setOnClickListener { click() }
        buttons += this to primary
    }

    private fun text(label: String, size: Float, secondary: Boolean) = TextView(context).apply {
        text = label
        textSize = size
        gravity = Gravity.CENTER
        texts += this to secondary
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
}

/** Decorative vector drawing; all labels and controls are actual Android widgets. */
private class ConnectionIllustration(context: Context) : View(context) {
    var night = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val icon = ContextCompat.getDrawable(context, R.drawable.ic_carplay)
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val save = canvas.save()
        canvas.scale(width / 440f, height / 190f)
        paint.color = if (night) Color.rgb(125, 157, 202) else Color.rgb(112, 145, 190)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.5f
        canvas.drawRoundRect(45f, 10f, 143f, 182f, 17f, 17f, paint)
        canvas.drawLine(79f, 22f, 109f, 22f, paint)
        canvas.drawLine(80f, 169f, 108f, 169f, paint)
        canvas.drawRoundRect(244f, 30f, 435f, 157f, 14f, 14f, paint)
        canvas.drawLine(339f, 157f, 339f, 179f, paint)
        canvas.drawLine(310f, 180f, 368f, 180f, paint)
        paint.color = Color.rgb(25, 165, 255)
        paint.strokeWidth = 5f
        for (r in listOf(13f, 22f, 31f)) canvas.drawArc(94f-r, 111f-r, 94f+r, 111f+r, 225f, 90f, false, paint)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(94f, 109f, 3f, paint)
        for (x in listOf(176f, 198f, 220f)) canvas.drawCircle(x, 96f, 3f, paint)
        icon?.setBounds(309, 62, 370, 123)
        icon?.draw(canvas)
        canvas.restoreToCount(save)
    }
}
