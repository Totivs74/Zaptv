package com.zaptv.app

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Right-side panel: audio track, subtitles, picture size. OK cycles each option. */
class OptionsOverlay(private val act: MainActivity) : FrameLayout(act) {
    private val panel = act.vbox()
    private val audioVal = act.label("", 15f)
    private val subsVal = act.label("", 15f)
    private val picVal = act.label("", 15f)
    private val firstRow: View

    init {
        panel.background = rounded(Col.CARD, dp(18).toFloat(), dp(1), Col.LINE)
        panel.setPadding(dp(18), dp(18), dp(18), dp(18))
        panel.addView(act.label("Options", 20f, Col.TEXT, true), lp(MATCH, WRAP).apply { bottomMargin = dp(12) })
        firstRow = row("Audio", audioVal) { cycleAudio() }
        row("Subtitles", subsVal) { cycleSubs() }
        row("Picture", picVal) { cyclePicture() }
        panel.addView(act.label("OK: change     Back: close", 12f, Col.MUTED), lp(WRAP, WRAP).apply { topMargin = dp(10) })
        addView(panel, LayoutParams(dp(380), WRAP, Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = dp(28) })
        visibility = View.GONE
    }

    private fun row(title: String, value: TextView, onClick: () -> Unit): View {
        val r = act.hbox()
        r.isFocusable = true
        r.background = focusBg(act, Col.SURF, 12)
        r.setPadding(dp(14), dp(12), dp(14), dp(12))
        val t = act.label(title, 15f, Col.TEXT, true)
        r.addView(t, lp(0, WRAP, 1f))
        r.addView(value, lp(WRAP, WRAP))
        r.setOnFocusChangeListener { _, has ->
            val c = if (has) Col.DARK else Col.TEXT
            t.setTextColor(c); value.setTextColor(c)
        }
        r.setOnClickListener { onClick(); postDelayed({ refresh() }, 350) }
        panel.addView(r, lp(MATCH, WRAP).apply { bottomMargin = dp(8) })
        return r
    }

    fun open() {
        refresh()
        visibility = View.VISIBLE
        firstRow.requestFocus()
    }

    fun close() { visibility = View.GONE }

    private fun refresh() {
        val p = act.player
        val a = p.audioTracks()
        audioVal.text = when {
            a.isEmpty() -> "Default"
            else -> a.firstOrNull { it.selected }?.label ?: a.first().label
        } + if (a.size > 1) "  (${a.size})" else ""
        val s = p.textTracks()
        val sel = s.firstOrNull { it.selected }
        subsVal.text = when {
            s.isEmpty() -> "None available"
            sel == null -> "Off"
            else -> sel.label
        }
        picVal.text = listOf("Fit", "Fill", "Zoom")[act.app.prefs.resizeMode.coerceIn(0, 2)]
    }

    private fun cycleAudio() {
        val p = act.player
        val a = p.audioTracks()
        if (a.size < 2) return
        val i = a.indexOfFirst { it.selected }
        p.select(PlayerController.AUDIO, a[(i + 1) % a.size])
    }

    private fun cycleSubs() {
        val p = act.player
        val s = p.textTracks()
        if (s.isEmpty()) return
        val i = s.indexOfFirst { it.selected }
        when {
            i < 0 -> p.select(PlayerController.TEXT, s[0])
            i == s.size - 1 -> p.select(PlayerController.TEXT, null)
            else -> p.select(PlayerController.TEXT, s[i + 1])
        }
    }

    private fun cyclePicture() {
        val prefs = act.app.prefs
        act.player.setResize((prefs.resizeMode + 1) % 3)
    }
}
