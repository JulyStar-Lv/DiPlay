package com.shilapi.xcertplay.mazda

import android.content.Context
import android.view.View
import android.widget.FrameLayout

/** The OEM panel clips a full-size video surface; narrowing never resizes its buffers. */
internal class MzdVideoCanvas(context: Context) : FrameLayout(context) {
    var isFixedCanvas = false
        private set

    init { clipChildren = false; clipToPadding = false }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        isFixedCanvas = MzdIntegration.cockpit(context) && MzdVideoLayout.isPanel(width, height)
        if (!isFixedCanvas) { super.onMeasure(widthMeasureSpec, heightMeasureSpec); return }
        setMeasuredDimension(width, height)
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility != View.GONE) child.measure(
                MeasureSpec.makeMeasureSpec(WIDTH, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(HEIGHT, MeasureSpec.EXACTLY))
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (!isFixedCanvas) { super.onLayout(changed, left, top, right, bottom); return }
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility != View.GONE) child.layout(width - WIDTH, 0, width, HEIGHT)
        }
    }

    companion object {
        const val WIDTH = 2496
        const val HEIGHT = 1080
    }
}
