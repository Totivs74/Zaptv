package com.zaptv.app

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView

/** First-run screen: open the shown address on a phone to add the playlist. */
class SetupOverlay(private val act: MainActivity) : FrameLayout(act) {
    private val app = act.application as App
    private val url = act.label("", 30f, Col.ACCENT, true)
    private val status = act.label("", 16f, Col.TEXT)
    private val qr = ImageView(act)
    private var lastUrl = ""

    init {
        setBackgroundColor(Col.BG)
        val row = act.hbox()
        val left = act.vbox()
        left.addView(act.label("ZapTV", 16f, Col.MUTED, true))
        left.addView(act.label("Add your playlist", 34f, Col.TEXT, true), lp(WRAP, WRAP).apply { topMargin = dp(8) })
        left.addView(act.label("On your phone or computer, on the same Wi-Fi, open:", 17f, Col.MUTED), lp(WRAP, WRAP).apply { topMargin = dp(20) })
        left.addView(url, lp(WRAP, WRAP).apply { topMargin = dp(10) })
        left.addView(act.label("or scan the QR code. Works with M3U links, Xtream Codes and MAC portals.", 15f, Col.MUTED), lp(WRAP, WRAP).apply { topMargin = dp(14) })
        left.addView(status, lp(WRAP, WRAP).apply { topMargin = dp(26) })
        val settings = act.label("Settings", 15f, Col.TEXT, true).apply {
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = focusBg(act, Col.SURF2, 999)
            setTextColor(focusText())
            isFocusable = true
            setOnClickListener { act.openSettings() }
        }
        left.addView(settings, lp(WRAP, WRAP).apply { topMargin = dp(22) })
        row.addView(left, lp(0, WRAP, 1f))
        val qrBox = FrameLayout(act)
        qrBox.background = rounded(0xFFFFFFFF.toInt(), dp(16).toFloat())
        qrBox.setPadding(dp(12), dp(12), dp(12), dp(12))
        qrBox.addView(qr, LayoutParams(dp(220), dp(220)))
        row.addView(qrBox, lp(WRAP, WRAP).apply { leftMargin = dp(40) })
        addView(row, LayoutParams(MATCH, WRAP, Gravity.CENTER_VERTICAL).apply { setMargins(dp(64), 0, dp(64), 0) })
        visibility = View.GONE
    }

    private val retry = Runnable { refreshAddress() }

    private fun refreshAddress() {
        val a = app.web.address()
        if (a.isEmpty()) {
            url.text = "Connect the TV to the internet"
            postDelayed(retry, 2000)
            return
        }
        url.text = a.removePrefix("http://")
        if (a != lastUrl) {
            lastUrl = a
            qr.setImageBitmap(Qr.bitmap(a, 440))
        }
    }

    fun show(message: String?) {
        visibility = View.VISIBLE
        status.text = message ?: ""
        status.visibility = if (message.isNullOrEmpty()) View.GONE else View.VISIBLE
        removeCallbacks(retry)
        refreshAddress()
        postDelayed(retry, 1500)
    }

    fun close() {
        removeCallbacks(retry)
        visibility = View.GONE
    }
}
