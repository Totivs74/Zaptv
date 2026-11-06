package com.zaptv.app

import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout

/** Channel info band shown for a few seconds after switching. */
class BannerView(private val act: MainActivity) : FrameLayout(act) {
    private val prefs = (act.application as App).prefs
    private val repo = (act.application as App).repo
    private val card = act.hbox()
    private val logo = ImageView(act)
    private val initials = act.label("", 16f, Col.TEXT, true)
    private val title = act.label("", 22f, Col.TEXT, true)
    private val now = act.label("", 16f, Col.TEXT, true)
    private val nowTime = act.label("", 14f, Col.MUTED)
    private val next = act.label("", 13f, Col.MUTED)
    private val bar = act.progressBar(0f)
    private val hide = Runnable { visibility = View.GONE }

    init {
        card.background = rounded(Col.CARD, dp(18).toFloat(), dp(1), Col.LINE)
        card.setPadding(dp(18), dp(16), dp(22), dp(16))
        val logoBox = FrameLayout(act)
        logoBox.background = rounded(Col.SURF2, dp(12).toFloat())
        logo.scaleType = ImageView.ScaleType.FIT_CENTER
        logoBox.addView(logo, LayoutParams(MATCH, MATCH).apply { setMargins(dp(8), dp(8), dp(8), dp(8)) })
        initials.gravity = Gravity.CENTER
        logoBox.addView(initials, LayoutParams(MATCH, MATCH))
        card.addView(logoBox, lp(dp(104), dp(68)).apply { rightMargin = dp(18) })

        val info = act.vbox()
        info.addView(title)
        val nowRow = act.hbox()
        now.maxLines = 1; now.ellipsize = TextUtils.TruncateAt.END
        nowRow.addView(now, lp(0, WRAP, 1f))
        nowRow.addView(nowTime, lp(WRAP, WRAP).apply { leftMargin = dp(12) })
        info.addView(nowRow, lp(MATCH, WRAP).apply { topMargin = dp(8) })
        info.addView(bar, lp(MATCH, dp(4)).apply { topMargin = dp(8) })
        next.maxLines = 1; next.ellipsize = TextUtils.TruncateAt.END
        info.addView(next, lp(MATCH, WRAP).apply { topMargin = dp(8) })
        card.addView(info, lp(0, WRAP, 1f))

        addView(card, LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { setMargins(dp(28), 0, dp(28), dp(24)) })
        visibility = View.GONE
    }

    fun show(ch: Channel, sticky: Boolean = false) {
        title.text = "${ch.number}  ${ch.name}"
        initials.text = ch.initials
        Logos.load(ch.logo, logo, initials)
        val t = System.currentTimeMillis()
        val (cur, nxt) = repo.epg.nowNext(ch, t)
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
        val sec = prefs.bannerSec
        if (!sticky) postDelayed(hide, (if (sec <= 0) 3 else sec) * 1000L)
    }

    fun hideNow() { removeCallbacks(hide); visibility = View.GONE }
}
