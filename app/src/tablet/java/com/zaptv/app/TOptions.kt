package com.zaptv.app

import android.app.AlertDialog
import android.widget.ScrollView
import android.widget.TextView

/** Options for the playing channel: audio, subtitles, picture size, favourite, reload, settings. */
class TOptions(private val act: TabletActivity) : ScrollView(act) {
    private val app = act.application as App
    private val box = act.vbox()
    private val audioVal = act.label("", 14f, Col.MUTED)
    private val subsVal = act.label("", 14f, Col.MUTED)
    private val picVal = act.label("", 14f, Col.MUTED)
    private val favVal = act.label("", 14f, Col.MUTED)

    init {
        isVerticalScrollBarEnabled = false
        box.setPadding(dp(16), dp(18), dp(16), dp(18))
        val head = act.hbox()
        head.addView(act.label("Options", 21f, Col.TEXT, true), lp(0, WRAP, 1f))
        head.addView(act.label("✕", 20f, Col.MUTED, true).apply {
            setPadding(dp(12), dp(4), dp(4), dp(4))
            setOnClickListener { act.closeOptions() }
        })
        box.addView(head, lp(MATCH, WRAP).apply { bottomMargin = dp(14) })
        row("Audio", audioVal) { chooseAudio() }
        row("Subtitles", subsVal) { chooseSubs() }
        row("Picture", picVal) { choosePicture() }
        row("Favourite", favVal) { act.toggleFavourite(); refresh() }
        row("Reload channel", null) { act.reloadChannel() }
        row("Settings", null) { act.openSettings() }
        addView(box)
    }

    private fun row(title: String, value: TextView?, onClick: () -> Unit) {
        val r = act.hbox()
        r.background = android.graphics.drawable.StateListDrawable().apply {
            val rad = dp(12).toFloat()
            addState(intArrayOf(android.R.attr.state_pressed), rounded(Col.SURF2, rad, dp(1), Col.ACCENT))
            addState(intArrayOf(), rounded(Col.SURF, rad))
        }
        r.setPadding(dp(16), dp(15), dp(16), dp(15))
        r.addView(act.label(title, 16f, Col.TEXT, true), lp(0, WRAP, 1f))
        if (value != null) r.addView(value, lp(WRAP, WRAP).apply { leftMargin = dp(10) })
        else r.addView(act.label("›", 20f, Col.SUBTLE, true))
        r.setOnClickListener { onClick() }
        box.addView(r, lp(MATCH, WRAP).apply { bottomMargin = dp(8) })
    }

    fun refresh() {
        val p = act.player
        val a = p.audioTracks()
        audioVal.text = (if (a.isEmpty()) "Default" else a.firstOrNull { it.selected }?.label ?: a.first().label) +
            if (a.size > 1) "  (${a.size})" else ""
        val s = p.textTracks()
        val sel = s.firstOrNull { it.selected }
        subsVal.text = when { s.isEmpty() -> "None available"; sel == null -> "Off"; else -> sel.label }
        picVal.text = PICTURE[app.prefs.resizeMode.coerceIn(0, 2)]
        favVal.text = act.currentChannel()?.let { if (app.repo.isFav(it)) "Yes ★" else "No" } ?: "-"
    }

    private fun dialog(title: String, names: List<String>, checked: Int, pick: (Int) -> Unit) {
        AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title)
            .setSingleChoiceItems(names.toTypedArray(), checked) { d, which -> d.dismiss(); pick(which); postDelayed({ refresh() }, 350) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun chooseAudio() {
        val a = act.player.audioTracks()
        if (a.size < 2) { act.status(if (a.isEmpty()) "No audio tracks yet" else "This channel has one audio track"); return }
        dialog("Audio", a.map { it.label }, a.indexOfFirst { it.selected }) { act.player.select(PlayerController.AUDIO, a[it]) }
    }

    private fun chooseSubs() {
        val s = act.player.textTracks()
        if (s.isEmpty()) { act.status("No subtitles on this channel"); return }
        val names = listOf("Off") + s.map { it.label }
        dialog("Subtitles", names, s.indexOfFirst { it.selected } + 1) { i ->
            act.player.select(PlayerController.TEXT, if (i == 0) null else s[i - 1])
        }
    }

    private fun choosePicture() {
        dialog("Picture", PICTURE, app.prefs.resizeMode.coerceIn(0, 2)) { act.player.setResize(it) }
    }

    companion object {
        val PICTURE = listOf("Fit", "Fill", "Zoom")
    }

}
