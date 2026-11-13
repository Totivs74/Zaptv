package com.zaptv.app

import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView

/**
 * Touch TV guide: channels on the left, the chosen channel's schedule on the right.
 * Tap a channel to see its programmes; tap a programme (or Watch) to switch to that channel.
 */
class TGuide(private val act: TabletActivity) : LinearLayout(act) {
    private val app = act.application as App
    private val repo get() = app.repo

    val header = act.hbox()
    private val dayLabel = act.label("", 14f, Col.MUTED)
    private val list = ListView(act)
    private val schedule = act.vbox()
    private val scroll = ScrollView(act)
    private val chTitle = act.label("", 19f, Col.TEXT, true)
    private val watch = act.label("Watch", 15f, Col.DARK, true)
    private var channels: List<Channel> = emptyList()
    private var selected: Channel? = null
    private val adapter = ChAdapter()

    init {
        orientation = VERTICAL
        setPadding(dp(16), dp(10), dp(16), dp(10))

        // Header doubles as the drag handle: swipe it down to close.
        val grip = View(act).apply { background = rounded(Col.LINE, dp(3).toFloat()) }
        addView(grip, lp(dp(44), dp(5)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(8) })
        header.addView(act.label("TV guide", 21f, Col.TEXT, true))
        header.addView(dayLabel, lp(0, WRAP, 1f).apply { leftMargin = dp(14) })
        val close = act.label("✕", 20f, Col.MUTED, true).apply {
            setPadding(dp(14), dp(6), dp(6), dp(6))
            setOnClickListener { act.closeGuide() }
        }
        header.addView(close)
        addView(header, lp(MATCH, WRAP).apply { bottomMargin = dp(10) })

        val body = act.hbox()
        body.gravity = Gravity.TOP
        list.adapter = adapter
        list.divider = null
        list.selector = rounded(0, 0f)
        body.addView(list, lp(0, MATCH, 0.42f))

        val right = act.vbox()
        right.setPadding(dp(14), 0, 0, 0)
        val titleRow = act.hbox()
        chTitle.maxLines = 1; chTitle.ellipsize = TextUtils.TruncateAt.END
        titleRow.addView(chTitle, lp(0, WRAP, 1f))
        watch.setPadding(dp(18), dp(8), dp(18), dp(8))
        watch.background = rounded(Col.ACCENT, dp(999).toFloat())
        watch.setOnClickListener { selected?.let { act.playFromGuide(it) } }
        titleRow.addView(watch, lp(WRAP, WRAP).apply { leftMargin = dp(10) })
        right.addView(titleRow, lp(MATCH, WRAP).apply { bottomMargin = dp(10) })
        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(schedule)
        right.addView(scroll, lp(MATCH, 0, 1f))
        body.addView(right, lp(0, MATCH, 0.58f))
        addView(body, lp(MATCH, 0, 1f))
    }

    fun open(chs: List<Channel>, current: Channel?) {
        channels = chs.filter { !it.isWeather && !it.isApp }
        dayLabel.text = Fmt.today()
        selected = current?.let { c -> channels.firstOrNull { it.key == c.key } } ?: channels.firstOrNull()
        adapter.notifyDataSetChanged()
        val i = channels.indexOfFirst { it.key == selected?.key }
        if (i >= 0) list.post { list.setSelectionFromTop(i, dp(60)) }
        showSchedule()
    }

    private fun select(ch: Channel) {
        selected = ch
        adapter.notifyDataSetChanged()
        showSchedule()
    }

    private fun showSchedule() {
        schedule.removeAllViews()
        val ch = selected
        if (ch == null) {
            chTitle.text = ""
            watch.visibility = View.GONE
            schedule.addView(act.label("No channels here.", 15f, Col.MUTED))
            return
        }
        watch.visibility = View.VISIBLE
        chTitle.text = "${ch.number}  ${ch.name}"
        val now = System.currentTimeMillis()
        val progs = repo.epg.forChannel(ch).filter { it.stop > now }.take(40)
        if (progs.isEmpty()) {
            schedule.addView(act.label("No guide information for this channel.\nTap Watch to switch to it.", 15f, Col.MUTED))
            scroll.scrollTo(0, 0)
            return
        }
        for (p in progs) schedule.addView(progRow(ch, p, now), lp(MATCH, WRAP).apply { bottomMargin = dp(6) })
        scroll.post { scroll.scrollTo(0, 0) }
    }

    private fun progRow(ch: Channel, p: Programme, now: Long): View {
        val live = p.start <= now && now < p.stop
        val box = act.vbox()
        box.setPadding(dp(14), dp(11), dp(14), dp(11))
        box.background = rounded(if (live) Col.SURF2 else Col.SURF, dp(12).toFloat(), if (live) dp(1) else 0, Col.CORAL)
        val top = act.hbox()
        top.addView(act.label(Fmt.range(p), 13f, if (live) Col.CORAL else Col.MUTED, true))
        if (live) top.addView(act.label("  NOW", 12f, Col.CORAL, true))
        box.addView(top)
        val t = act.label(p.title, 16f, Col.TEXT, true)
        t.maxLines = 2; t.ellipsize = TextUtils.TruncateAt.END
        box.addView(t, lp(MATCH, WRAP).apply { topMargin = dp(4) })
        if (live) box.addView(act.progressBar(p.progress(now)), lp(MATCH, dp(3)).apply { topMargin = dp(8) })
        if (p.desc.isNotBlank()) {
            val d = act.label(p.desc, 13f, Col.MUTED)
            d.maxLines = 2; d.ellipsize = TextUtils.TruncateAt.END
            box.addView(d, lp(MATCH, WRAP).apply { topMargin = dp(6) })
        }
        box.setOnClickListener { act.playFromGuide(ch) }
        return box
    }

    private inner class ChAdapter : BaseAdapter() {
        override fun getCount() = channels.size
        override fun getItem(position: Int) = channels[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val holder = (convertView?.tag as? Holder) ?: Holder()
            holder.bind(channels[position])
            return holder.root
        }
    }

    private inner class Holder {
        val root = act.hbox()
        val logoBox = FrameLayout(act)
        val logo = ImageView(act)
        val initials = act.label("", 11f, Col.TEXT, true)
        val name: TextView = act.label("", 14f, Col.TEXT, true)
        val now: TextView = act.label("", 12f, Col.MUTED)
        var ch: Channel? = null

        init {
            root.tag = this
            root.setPadding(dp(8), dp(8), dp(8), dp(8))
            logoBox.background = rounded(Col.SURF2, dp(8).toFloat())
            logo.scaleType = ImageView.ScaleType.FIT_CENTER
            logoBox.addView(logo, FrameLayout.LayoutParams(MATCH, MATCH).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
            initials.gravity = Gravity.CENTER
            logoBox.addView(initials, FrameLayout.LayoutParams(MATCH, MATCH))
            root.addView(logoBox, lp(dp(56), dp(36)).apply { rightMargin = dp(10) })
            val texts = act.vbox()
            name.maxLines = 1; name.ellipsize = TextUtils.TruncateAt.END
            now.maxLines = 1; now.ellipsize = TextUtils.TruncateAt.END
            texts.addView(name)
            texts.addView(now, lp(MATCH, WRAP).apply { topMargin = dp(3) })
            root.addView(texts, lp(0, WRAP, 1f))
            root.setOnClickListener { ch?.let { select(it) } }
        }

        fun bind(c: Channel) {
            ch = c
            name.text = "${c.number}  ${c.name}"
            initials.text = c.initials
            Logos.load(c.logo, logo, initials)
            now.text = repo.epg.nowNext(c).first?.title ?: c.group
            val on = c.key == selected?.key
            root.background = if (on) rounded(0x2619E3FF, dp(12).toFloat(), dp(1), 0x9919E3FF.toInt()) else rounded(0, 0f)
        }
    }
}
