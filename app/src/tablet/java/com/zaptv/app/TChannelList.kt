package com.zaptv.app

import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView

/** Group tabs on top, channel rows below. Tap plays, ☆ (or a long press) adds to Favourites. */
class TChannelList(private val act: TabletActivity) : LinearLayout(act) {
    private val app = act.application as App
    private val repo get() = app.repo
    private val prefs get() = app.prefs

    var group = ""
        private set
    var items: List<Channel> = emptyList()
        private set
    private var playingKey = ""

    val tabsScroll = HorizontalScrollView(act)
    private val tabs = act.hbox()
    private val list = ListView(act)
    private val empty = act.label("", 15f, Col.MUTED)
    private val adapter = RowAdapter()

    init {
        orientation = VERTICAL
        setBackgroundColor(Col.BG)
        tabsScroll.isHorizontalScrollBarEnabled = false
        tabs.setPadding(dp(10), dp(10), dp(10), dp(10))
        tabsScroll.addView(tabs)
        addView(tabsScroll, lp(MATCH, WRAP))
        val body = FrameLayout(act)
        list.adapter = adapter
        list.divider = null
        list.dividerHeight = 0
        list.selector = rounded(0, 0f)
        list.isVerticalScrollBarEnabled = true
        list.setPadding(dp(8), 0, dp(8), dp(12))
        list.clipToPadding = false
        body.addView(list, FrameLayout.LayoutParams(MATCH, MATCH))
        empty.gravity = Gravity.CENTER
        empty.setPadding(dp(24), dp(24), dp(24), dp(24))
        empty.visibility = View.GONE
        body.addView(empty, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER))
        addView(body, lp(MATCH, 0, 1f))
    }

    /** Tabs without the TV-only Apps tab. */
    private fun tabNames(): List<String> = repo.tabs().filter { it != Repo.APPS }

    /** Rebuilds tabs and rows after the channel list changed. */
    fun reload() {
        val names = tabNames()
        val wanted = prefs.lastGroup
        val g = when {
            group.isNotEmpty() && group in names -> group
            wanted.isNotEmpty() && wanted in names -> wanted
            prefs.favourites.isNotEmpty() -> Repo.FAV
            else -> names.firstOrNull { it != Repo.RECENT && it != Repo.FAV } ?: Repo.ALL
        }
        buildTabs(names)
        setGroup(g, force = true)
    }

    private fun buildTabs(names: List<String>) {
        tabs.removeAllViews()
        for (n in names) {
            val t = act.label(if (n == Repo.FAV) "★ $n" else n, 15f, Col.MUTED, true)
            t.setPadding(dp(16), dp(9), dp(16), dp(9))
            t.background = tabBg()
            t.setTextColor(tabText())
            t.tag = n
            t.setOnClickListener { setGroup(n) }
            tabs.addView(t, lp(WRAP, WRAP).apply { rightMargin = dp(8) })
        }
    }

    private fun tabBg(): StateListDrawable {
        val r = dp(999).toFloat()
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_activated), rounded(Col.ACCENT, r))
            addState(intArrayOf(android.R.attr.state_pressed), rounded(Col.SURF2, r, dp(1), Col.ACCENT))
            addState(intArrayOf(), rounded(0x8014205C.toInt(), r))
        }
    }

    private fun tabText() = android.content.res.ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_activated), intArrayOf()),
        intArrayOf(Col.DARK, Col.TEXT)
    )

    fun setGroup(name: String, force: Boolean = false) {
        if (!force && name == group) return
        group = name
        prefs.lastGroup = name
        items = repo.channelsFor(name)
        for (i in 0 until tabs.childCount) {
            val t = tabs.getChildAt(i)
            val on = t.tag == name
            t.isActivated = on
            if (on) tabsScroll.post { tabsScroll.smoothScrollTo(maxOf(0, t.left - dp(40)), 0) }
        }
        adapter.notifyDataSetChanged()
        list.setSelection(0)
        updateEmpty()
    }

    private fun updateEmpty() {
        empty.text = when {
            items.isNotEmpty() -> ""
            group == Repo.FAV -> "No favourites yet.\nTap ☆ next to a channel, or long-press it."
            group == Repo.RECENT -> "Channels you watch will appear here."
            else -> "No channels in this group."
        }
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    /** Marks the playing channel and scrolls to it. */
    fun markPlaying(ch: Channel?, scroll: Boolean = true) {
        playingKey = ch?.key ?: ""
        adapter.notifyDataSetChanged()
        if (scroll && ch != null) {
            val i = items.indexOfFirst { it.key == ch.key }
            // Only scroll when the playing channel is off screen, so a tap never makes the list jump.
            if (i >= 0) list.post {
                val visible = list.childCount > 0 && i > list.firstVisiblePosition && i < list.lastVisiblePosition
                if (!visible) list.setSelectionFromTop(i, dp(80))
            }
        }
    }

    /** Favourites, weather temperatures or the guide changed: redraw without moving. */
    fun refreshItems() {
        if (group == Repo.FAV || group == Repo.RECENT || group == Repo.WEATHER) items = repo.channelsFor(group)
        adapter.notifyDataSetChanged()
        updateEmpty()
    }

    fun refreshEpg() = adapter.notifyDataSetChanged()

    private fun toggleFav(ch: Channel) {
        if (ch.isWeather) return
        val added = repo.toggleFav(ch)
        act.status(if (added) "Added to favourites: ${ch.name}" else "Removed from favourites: ${ch.name}")
        refreshItems()
    }

    // ------------------------------------------------------------------ rows

    private inner class Row(val root: LinearLayout) {
        val logoBox = FrameLayout(act)
        val logo = ImageView(act)
        val initials = act.label("", 12f, Col.TEXT, true)
        val name = act.label("", 16f, Col.TEXT, true)
        val now = act.label("", 13f, Col.MUTED)
        val bar = act.progressBar(0f)
        val star = act.label("", 22f, Col.MUTED)
        var ch: Channel? = null

        init {
            root.setPadding(dp(10), dp(9), dp(6), dp(9))
            root.background = StateListDrawable().apply {
                val r = dp(14).toFloat()
                addState(intArrayOf(android.R.attr.state_pressed), rounded(Col.SURF2, r))
                addState(intArrayOf(android.R.attr.state_activated), rounded(0x2619E3FF, r, dp(1), 0x9919E3FF.toInt()))
                addState(intArrayOf(), rounded(0, r))
            }
            logoBox.background = rounded(Col.SURF2, dp(10).toFloat())
            logo.scaleType = ImageView.ScaleType.FIT_CENTER
            logoBox.addView(logo, FrameLayout.LayoutParams(MATCH, MATCH).apply { setMargins(dp(5), dp(5), dp(5), dp(5)) })
            initials.gravity = Gravity.CENTER
            initials.maxLines = 2
            logoBox.addView(initials, FrameLayout.LayoutParams(MATCH, MATCH))
            root.addView(logoBox, lp(dp(72), dp(46)).apply { rightMargin = dp(12) })
            val texts = act.vbox()
            name.maxLines = 1; name.ellipsize = TextUtils.TruncateAt.END
            now.maxLines = 1; now.ellipsize = TextUtils.TruncateAt.END
            texts.addView(name)
            texts.addView(now, lp(MATCH, WRAP).apply { topMargin = dp(4) })
            texts.addView(bar, lp(MATCH, dp(3)).apply { topMargin = dp(6) })
            root.addView(texts, lp(0, WRAP, 1f))
            star.gravity = Gravity.CENTER
            star.setPadding(dp(14), dp(8), dp(14), dp(8))
            root.addView(star, lp(WRAP, WRAP))
            root.setOnClickListener { ch?.let { act.playFromList(it) } }
            root.setOnLongClickListener { ch?.let { toggleFav(it) }; true }
            star.setOnClickListener { ch?.let { toggleFav(it) } }
        }

        fun bind(c: Channel) {
            ch = c
            val num = if (c.isWeather) c.badge else c.number.toString()
            name.text = if (num.isEmpty()) c.name else "$num   ${c.name}"
            initials.text = c.initials
            Logos.load(c.logo, logo, initials)
            val t = System.currentTimeMillis()
            val cur = if (c.isWeather) null else repo.epg.nowNext(c, t).first
            if (cur != null) {
                now.text = "${cur.title}  ·  ${Fmt.range(cur)}"
                bar.visibility = View.VISIBLE
                setProgress(bar, cur.progress(t))
            } else {
                now.text = if (c.isWeather) "Weather forecast" else c.group
                bar.visibility = View.GONE
            }
            if (c.isWeather) {
                star.visibility = View.INVISIBLE
            } else {
                star.visibility = View.VISIBLE
                val fav = repo.isFav(c)
                star.text = if (fav) "★" else "☆"
                star.setTextColor(if (fav) 0xFFFFC53D.toInt() else Col.SUBTLE)
            }
            root.isActivated = c.key == playingKey
        }
    }

    private inner class RowAdapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row: Row = (convertView?.tag as? Row) ?: Row(act.hbox()).also { it.root.tag = it }
            row.bind(items[position])
            return row.root
        }
    }
}
