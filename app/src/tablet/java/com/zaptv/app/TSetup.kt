package com.zaptv.app

import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ScrollView

/** First-run screen on the tablet: add a playlist right here in the browser, or from a laptop. */
class TSetup(private val act: TabletActivity) : FrameLayout(act) {
    private val app = act.application as App
    private val laptop = act.label("", 22f, Col.ACCENT, true)
    private val status = act.label("", 15f, Col.TEXT)
    private val retry = Runnable { refreshAddress() }

    init {
        setBackgroundColor(Col.BG)
        isClickable = true
        val scroll = ScrollView(act)
        val box = act.vbox()
        box.setPadding(dp(32), dp(40), dp(32), dp(32))
        box.addView(act.label("ZapTV", 15f, Col.MUTED, true))
        box.addView(act.label("Add your playlist", 30f, Col.TEXT, true), lp(WRAP, WRAP).apply { topMargin = dp(8) })
        box.addView(act.label("Upload an M3U file, paste a link, or add Xtream Codes or a MAC portal.", 16f, Col.MUTED), lp(MATCH, WRAP).apply { topMargin = dp(14) })
        box.addView(button("Add it on this tablet", primary = true) { openLocalPage() }, lp(WRAP, WRAP).apply { topMargin = dp(24) })
        box.addView(act.label("Opens the ZapTV page in your browser. Save it there, then come back here.", 13f, Col.MUTED), lp(MATCH, WRAP).apply { topMargin = dp(8) })
        box.addView(act.label("Or from a laptop or phone on the same Wi-Fi, open:", 15f, Col.MUTED), lp(MATCH, WRAP).apply { topMargin = dp(28) })
        box.addView(laptop, lp(WRAP, WRAP).apply { topMargin = dp(6) })
        box.addView(status, lp(MATCH, WRAP).apply { topMargin = dp(24) })
        box.addView(button("Settings", primary = false) { act.openSettings() }, lp(WRAP, WRAP).apply { topMargin = dp(20) })
        scroll.addView(box)
        addView(scroll, LayoutParams(MATCH, MATCH, Gravity.CENTER))
        visibility = View.GONE
    }

    private fun button(text: String, primary: Boolean, onClick: () -> Unit) =
        act.label(text, 16f, if (primary) Col.DARK else Col.TEXT, true).apply {
            setPadding(dp(22), dp(13), dp(22), dp(13))
            background = rounded(if (primary) Col.ACCENT else Col.SURF2, dp(999).toFloat())
            setOnClickListener { onClick() }
        }

    private fun openLocalPage() {
        val port = if (app.web.port == 0) 8080 else app.web.port
        val ok = runCatching { act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:$port"))) }.isSuccess
        if (!ok) act.status("No browser found on this tablet")
    }

    private fun refreshAddress() {
        val a = app.web.address()
        laptop.text = if (a.isEmpty()) "Connect the tablet to Wi-Fi" else a.removePrefix("http://")
        if (a.isEmpty()) postDelayed(retry, 2000)
    }

    val isOpen get() = visibility == View.VISIBLE

    fun show(message: String?) {
        visibility = View.VISIBLE
        status.text = message ?: ""
        status.visibility = if (message.isNullOrEmpty()) View.GONE else View.VISIBLE
        removeCallbacks(retry)
        refreshAddress()
    }

    fun close() {
        removeCallbacks(retry)
        visibility = View.GONE
    }
}
