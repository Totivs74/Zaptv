package com.zaptv.app

import android.app.Activity
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView

/** Channel info card shown after a switch, or while the controls are open. Touch version of the TV banner. */
class TBanner(act: Activity) : FrameLayout(act) {
    private val app = act.application as App
    private val card = act.hbox()
    private val logo = ImageView(act)
    private val initials = act.label("", 14f, Col.TEXT, true)
    private val title = act.label("", 19f, Col.TEXT, true)
    private val now = act.label("", 15f, Col.TEXT, true)
    private val nowTime = act.label("", 13f, Col.MUTED)
    private val next = act.label("", 13f, Col.MUTED)
    private val bar = act.progressBar(0f)
    private val hide = Runnable { visibility = View.GONE }

    init {
        card.background = rounded(Col.CARD, dp(16).toFloat(), dp(1), Col.LINE)
        card.setPadding(dp(14), dp(12), dp(16), dp(12))
        val logoBox = FrameLayout(act)
        logoBox.background = rounded(Col.SURF2, dp(10).toFloat())
        logo.scaleType = ImageView.ScaleType.FIT_CENTER
        logoBox.addView(logo, LayoutParams(MATCH, MATCH).apply { setMargins(dp(6), dp(6), dp(6), dp(6)) })
        initials.gravity = Gravity.CENTER
        logoBox.addView(initials, LayoutParams(MATCH, MATCH))
        card.addView(logoBox, lp(dp(84), dp(54)).apply { rightMargin = dp(14) })

        val info = act.vbox()
        title.maxLines = 1; title.ellipsize = TextUtils.TruncateAt.END
        info.addView(title)
        val nowRow = act.hbox()
        now.maxLines = 1; now.ellipsize = TextUtils.TruncateAt.END
        nowRow.addView(now, lp(0, WRAP, 1f))
        nowRow.addView(nowTime, lp(WRAP, WRAP).apply { leftMargin = dp(10) })
        info.addView(nowRow, lp(MATCH, WRAP).apply { topMargin = dp(6) })
        info.addView(bar, lp(MATCH, dp(4)).apply { topMargin = dp(6) })
        next.maxLines = 1; next.ellipsize = TextUtils.TruncateAt.END
        info.addView(next, lp(MATCH, WRAP).apply { topMargin = dp(6) })
        card.addView(info, lp(0, WRAP, 1f))

        addView(card, LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { setMargins(dp(16), 0, dp(16), dp(12)) })
        visibility = View.GONE
    }

    fun show(ch: Channel, sticky: Boolean = false) {
        title.text = "${ch.number}  ${ch.name}" + if (TrVpn.get(context).needs(ch.key)) "   · TR" else ""
        initials.text = ch.initials
        Logos.load(ch.logo, logo, initials)
        val t = System.currentTimeMillis()
        val (cur, nxt) = app.repo.epg.nowNext(ch, t)
        if (cur != null) {
            now.text = cur.title; nowTime.text = Fmt.range(cur)
            bar.visibility = View.VISIBLE; setProgress(bar, cur.progress(t))
        } else {
            now.text = ch.group; nowTime.text = ""; bar.visibility = View.GONE
        }
        next.text = nxt?.let { "Next: ${it.title}  ${Fmt.time(it.start)}" } ?: ""
        next.visibility = if (nxt == null) View.GONE else View.VISIBLE
        visibility = View.VISIBLE
        removeCallbacks(hide)
        val sec = app.prefs.bannerSec
        if (!sticky) postDelayed(hide, (if (sec <= 0) 3 else sec) * 1000L)
    }

    fun hideNow() { removeCallbacks(hide); visibility = View.GONE }
}
