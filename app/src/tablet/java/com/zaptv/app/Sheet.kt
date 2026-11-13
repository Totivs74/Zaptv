package com.zaptv.app

import android.content.Context
import android.graphics.Rect
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * A panel that slides over the playing video from one edge.
 * Closes on a tap outside it, a swipe toward its edge, or Back.
 */
class Sheet(ctx: Context, val edge: Int) : FrameLayout(ctx) {
    companion object {
        const val LEFT = 0
        const val RIGHT = 1
        const val BOTTOM = 2
    }

    private val scrim = View(ctx)
    val panel = FrameLayout(ctx)
    var onClosed: (() -> Unit)? = null
    /** Views inside the panel that scroll sideways themselves (a swipe there doesn't close the sheet). */
    val noSwipe = ArrayList<View>()
    /** Bottom sheet: only a downward swipe that starts in this view (the header) closes it. */
    var handle: View? = null
    private var downInNoSwipe = false
    private var downInHandle = false

    val isOpen: Boolean get() = visibility == View.VISIBLE

    private val swipe = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            val s = e1 ?: return false
            val dx = e2.x - s.x
            val dy = e2.y - s.y
            val min = dp(60)
            val toEdge = when (edge) {
                LEFT -> !downInNoSwipe && dx < -min && abs(dx) > 2 * abs(dy) && abs(vx) > 400
                RIGHT -> !downInNoSwipe && dx > min && abs(dx) > 2 * abs(dy) && abs(vx) > 400
                else -> downInHandle && dy > min && abs(dy) > 2 * abs(dx) && abs(vy) > 400
            }
            if (toEdge) close()
            return toEdge
        }
    })

    init {
        scrim.setBackgroundColor(0x88000000.toInt())
        scrim.setOnClickListener { close() }
        addView(scrim, LayoutParams(MATCH, MATCH))
        panel.background = rounded(Col.CARD, dp(20).toFloat(), dp(1), Col.LINE)
        panel.isClickable = true   // taps on the panel never fall through to the scrim
        addView(panel, LayoutParams(MATCH, MATCH))
        visibility = View.GONE
    }

    /** Sets the panel size: width for side sheets, height for the bottom sheet. */
    fun size(px: Int) {
        val lp = when (edge) {
            LEFT -> LayoutParams(px, MATCH, Gravity.START)
            RIGHT -> LayoutParams(px, MATCH, Gravity.END)
            else -> LayoutParams(MATCH, px, Gravity.BOTTOM)
        }
        panel.layoutParams = lp
    }

    private fun inside(v: View, ev: MotionEvent): Boolean {
        if (!v.isShown) return false
        val r = Rect()
        if (!v.getGlobalVisibleRect(r)) return false
        return r.contains(ev.rawX.toInt(), ev.rawY.toInt())
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            downInNoSwipe = noSwipe.any { inside(it, ev) }
            downInHandle = handle?.let { inside(it, ev) } ?: false
        }
        swipe.onTouchEvent(ev)
        if (!isOpen) return true
        return super.dispatchTouchEvent(ev)
    }

    private fun offscreen(): Pair<Float, Float> {
        val w = (panel.width.takeIf { it > 0 } ?: width).toFloat()
        val h = (panel.height.takeIf { it > 0 } ?: height).toFloat()
        return when (edge) {
            LEFT -> -w to 0f
            RIGHT -> w to 0f
            else -> 0f to h
        }
    }

    fun open() {
        if (isOpen) return
        visibility = View.VISIBLE
        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(180).start()
        panel.post {
            val (x, y) = offscreen()
            panel.translationX = x; panel.translationY = y
            panel.animate().translationX(0f).translationY(0f).setDuration(220).start()
        }
    }

    fun close() {
        if (!isOpen) return
        val (x, y) = offscreen()
        scrim.animate().alpha(0f).setDuration(180).start()
        panel.animate().translationX(x).translationY(y).setDuration(200).withEndAction {
            visibility = View.GONE
            panel.translationX = 0f; panel.translationY = 0f
            onClosed?.invoke()
        }.start()
    }

    /** Closes at once, no animation (used when the screen rotates). */
    fun closeNow() {
        if (!isOpen) return
        panel.animate().cancel(); scrim.animate().cancel()
        visibility = View.GONE
        panel.translationX = 0f; panel.translationY = 0f
        onClosed?.invoke()
    }
}
