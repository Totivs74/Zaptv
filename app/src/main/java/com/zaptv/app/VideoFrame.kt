package com.zaptv.app

import android.content.Context
import android.view.SurfaceView
import android.widget.FrameLayout

/** Holds the video surface and keeps the picture's aspect ratio (fit / fill / zoom). */
class VideoFrame(ctx: Context) : FrameLayout(ctx) {
    val surface = SurfaceView(ctx)
    private var videoRatio = 0f
    var mode = 0
        set(v) { field = v; requestLayout() }

    init {
        addView(surface, LayoutParams(MATCH, MATCH))
        setBackgroundColor(0xFF000000.toInt())
    }

    fun setVideoSize(w: Int, h: Int, par: Float) {
        videoRatio = if (w <= 0 || h <= 0) 0f else w * (if (par > 0f) par else 1f) / h
        requestLayout()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val W = r - l
        val H = b - t
        var w = W
        var h = H
        if (videoRatio > 0f && mode != 1) {
            val viewRatio = W.toFloat() / H
            val fitWidth = videoRatio > viewRatio
            if (mode == 0) {
                if (fitWidth) h = (W / videoRatio).toInt() else w = (H * videoRatio).toInt()
            } else {
                if (fitWidth) w = (H * videoRatio).toInt() else h = (W / videoRatio).toInt()
            }
        }
        val x = (W - w) / 2
        val y = (H - h) / 2
        surface.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        surface.layout(x, y, x + w, y + h)
    }
}
