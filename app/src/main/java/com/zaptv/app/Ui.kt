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
    // Navy + neon palette taken from the ZapTV logo
    val BG = 0xFF030725.toInt()
    val SURF = 0xFF0B1342.toInt()
    val SURF2 = 0xFF14205C.toInt()
    val LINE = 0xFF26326E.toInt()
    val TEXT = 0xFFEEF3FF.toInt()
    val MUTED = 0xFF93A0CF.toInt()
    val SUBTLE = 0xFF5D6A9E.toInt()
    val ACCENT = 0xFF19E3FF.toInt()      // cyan: focus
    val CORAL = 0xFFFF6A4D.toInt()       // coral: live / now
    val FOCUS_FILL = 0x2619E3FF          // cyan tint behind focused rows
    val DARK = 0xFF03081F.toInt()
    val DIM = 0xE6030725.toInt()
    val CARD = 0xF00A123E.toInt()
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

/** Rows and cards: focused = cyan frame on a cyan tint, activated = soft cyan tint. */
fun focusBg(ctx: Context, normal: Int, radiusDp: Int, activated: Int = 0x1F19E3FF): Drawable {
    val r = ctx.dp(radiusDp).toFloat()
    return StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), rounded(blend(normal), r, ctx.dp(2), Col.ACCENT))
        addState(intArrayOf(android.R.attr.state_activated), rounded(activated, r, ctx.dp(1), 0x7319E3FF))
        addState(intArrayOf(), rounded(normal, r))
    }
}

private fun blend(normal: Int): Int = if (normal == 0) Col.FOCUS_FILL else 0xFF102A66.toInt()

/** Pills and buttons: focused = solid cyan with dark text. */
fun pillBg(ctx: Context, normal: Int = 0x8014205C.toInt()): Drawable {
    val r = ctx.dp(999).toFloat()
    return StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), rounded(Col.ACCENT, r))
        addState(intArrayOf(android.R.attr.state_activated), rounded(0x1F19E3FF, r, ctx.dp(1), 0x7319E3FF))
        addState(intArrayOf(), rounded(normal, r))
    }
}

fun pillText(normal: Int = Col.MUTED): ColorStateList = ColorStateList(
    arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf(android.R.attr.state_activated), intArrayOf()),
    intArrayOf(Col.DARK, Col.TEXT, normal)
)

/** Rows keep light text when focused (the frame shows focus). */
fun focusText(normal: Int = Col.TEXT): ColorStateList = ColorStateList(
    arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf(android.R.attr.state_activated), intArrayOf()),
    intArrayOf(Col.TEXT, Col.TEXT, normal)
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
fun Context.progressBar(fraction: Float, color: Int = Col.CORAL, track: Int = Col.LINE): LinearLayout {
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
