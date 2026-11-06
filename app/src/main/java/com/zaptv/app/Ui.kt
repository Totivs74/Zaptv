package com.zaptv.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

object Col {
    val BG = 0xFF0B0D10.toInt()
    val SURF = 0xFF15181D.toInt()
    val SURF2 = 0xFF1C2026.toInt()
    val LINE = 0xFF2A2F37.toInt()
    val TEXT = 0xFFF2F3F5.toInt()
    val MUTED = 0xFFA9AFB8.toInt()
    val SUBTLE = 0xFF3A3F47.toInt()
    val ACCENT = 0xFFF5A524.toInt()
    val DARK = 0xFF0B0D10.toInt()
    val DIM = 0xE00B0D10.toInt()
    val CARD = 0xEB0B0D10.toInt()
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun View.dp(v: Int): Int = context.dp(v)

fun rounded(color: Int, radiusPx: Float, strokePx: Int = 0, strokeColor: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusPx
        setColor(color)
        if (strokePx > 0) setStroke(strokePx, strokeColor)
    }

/** Background that turns white when focused, accent when activated (e.g. the current tab). */
fun focusBg(ctx: Context, normal: Int, radiusDp: Int, activated: Int = Col.ACCENT): Drawable {
    val r = ctx.dp(radiusDp).toFloat()
    return StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), rounded(Col.TEXT, r))
        addState(intArrayOf(android.R.attr.state_activated), rounded(activated, r))
        addState(intArrayOf(), rounded(normal, r))
    }
}

fun focusText(normal: Int = Col.TEXT): ColorStateList = ColorStateList(
    arrayOf(
        intArrayOf(android.R.attr.state_focused),
        intArrayOf(android.R.attr.state_activated),
        intArrayOf()
    ),
    intArrayOf(Col.DARK, Col.DARK, normal)
)

fun Context.label(
    text: CharSequence = "",
    sizeSp: Float = 15f,
    color: Int = Col.TEXT,
    bold: Boolean = false
): TextView = TextView(this).apply {
    this.text = text
    textSize = sizeSp
    setTextColor(color)
    if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    includeFontPadding = false
}

fun Context.vbox(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
fun Context.hbox(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

fun lp(w: Int, h: Int, weight: Float = 0f): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(w, h, weight)

const val MATCH = -1
const val WRAP = -2

/** A thin progress bar made from two weighted views. */
fun Context.progressBar(fraction: Float, color: Int = Col.ACCENT, track: Int = Col.LINE): LinearLayout {
    val box = hbox()
    box.background = rounded(track, dp(2).toFloat())
    val f = fraction.coerceIn(0f, 1f)
    val fill = View(this).apply { background = rounded(color, dp(2).toFloat()) }
    box.addView(fill, lp(0, MATCH, f))
    box.addView(View(this), lp(0, MATCH, 1f - f))
    return box
}

fun setProgress(bar: LinearLayout, fraction: Float) {
    val f = fraction.coerceIn(0f, 1f)
    (bar.getChildAt(0).layoutParams as LinearLayout.LayoutParams).weight = f
    (bar.getChildAt(1).layoutParams as LinearLayout.LayoutParams).weight = 1f - f
    bar.requestLayout()
}
