package com.zaptv.app

import android.graphics.drawable.ColorDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Big-logo channel grid shown on top of the playing video. Focus a tile to see what's on. */
class GridOverlay(private val act: MainActivity) : FrameLayout(act) {
    private val app = act.application as App
    private val repo = app.repo
    private val prefs = app.prefs

    private val tabRow = act.hbox()
    private val tabScroll = HorizontalScrollView(act)
    private val guideBtn = button("Guide")
    private val settingsBtn = button("Settings")
    private val clock = act.label("", 16f, Col.MUTED)
    val grid = GridView(act)
    private val adapter = TileAdapter()

    private val epgName = act.label("", 20f, Col.TEXT, true)
    private val epgHint = act.label("", 12f, Col.MUTED)
    private val nowTitle = act.label("", 16f, Col.TEXT, true)
    private val nowTime = act.label("", 14f, Col.MUTED)
    private val nextTitle = act.label("", 14f, 0xFFC9CED6.toInt())
    private val nextTime = act.label("", 14f, Col.MUTED)
    private val bar = act.progressBar(0f)
    private val nowRow = act.hbox()
    private val nextRow = act.hbox()

    var group: String = Repo.ALL
        private set
    var items: List<Channel> = emptyList()
        private set
    private var tabNames: List<String> = emptyList()
    private val clockTick = object : Runnable {
        override fun run() {
            clock.text = Fmt.time(System.currentTimeMillis())
            postDelayed(this, 20_000)
        }
    }

    init {
        setBackgroundColor(Col.DIM)
        val root = act.vbox()
        root.setPadding(dp(28), dp(22), dp(28), dp(16))
        addView(root, LayoutParams(MATCH, MATCH))

        // Top bar: group tabs | Guide | Settings | clock
        val top = act.hbox()
        tabScroll.isHorizontalScrollBarEnabled = false
        tabScroll.isFillViewport = false
        tabScroll.addView(tabRow)
        top.addView(tabScroll, lp(0, WRAP, 1f))
        top.addView(guideBtn, lp(WRAP, WRAP).apply { leftMargin = dp(10) })
        top.addView(settingsBtn, lp(WRAP, WRAP).apply { leftMargin = dp(8) })
        top.addView(clock, lp(WRAP, WRAP).apply { leftMargin = dp(16) })
        root.addView(top, lp(MATCH, WRAP))
        guideBtn.setOnClickListener { act.showGuide() }
        settingsBtn.setOnClickListener { act.openSettings() }

        // Grid
        grid.id = View.generateViewId()
        grid.numColumns = prefs.gridCols
        grid.stretchMode = GridView.STRETCH_COLUMN_WIDTH
        grid.horizontalSpacing = dp(12)
        grid.verticalSpacing = dp(12)
        grid.selector = ColorDrawable(0)
        grid.clipToPadding = false
        grid.clipChildren = false
        grid.setPadding(dp(6), dp(10), dp(6), dp(10))
        grid.isFocusable = true
        grid.isVerticalScrollBarEnabled = false
        grid.adapter = adapter
        grid.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                refreshHighlight(); showEpg(items.getOrNull(pos))
            }
            override fun onNothingSelected(p: AdapterView<*>?) { refreshHighlight() }
        }
        grid.setOnFocusChangeListener { _, has ->
            refreshHighlight()
            if (has) showEpg(items.getOrNull(grid.selectedItemPosition))
        }
        grid.setOnItemClickListener { _, _, pos, _ ->
            val c = items.getOrNull(pos) ?: return@setOnItemClickListener
            if (c.isWeather) act.openWeather(c.url.removePrefix("weather:").toIntOrNull() ?: 0) else act.playFromGrid(c)
        }
        grid.setOnItemLongClickListener { _, _, _, _ ->
            if (prefs.keyFor(Action.VOICE) == KeyMap.LONG_OK) { act.doAction(Action.VOICE); true } else false
        }
        grid.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView?, state: Int) {}
            override fun onScroll(view: AbsListView?, first: Int, visible: Int, total: Int) { refreshHighlight() }
        })
        root.clipChildren = false
        root.addView(grid, lp(MATCH, 0, 1f))

        // EPG panel for the focused tile
        val panel = act.vbox()
        panel.background = rounded(Col.CARD, dp(20).toFloat(), dp(1), Col.LINE)
        panel.setPadding(dp(20), dp(14), dp(20), dp(14))
        val head = act.hbox()
        head.addView(epgName, lp(0, WRAP, 1f))
        head.addView(epgHint, lp(WRAP, WRAP))
        panel.addView(head, lp(MATCH, WRAP))
        val nowLbl = act.label("Now", 13f, Col.CORAL, true)
        nowRow.addView(nowLbl, lp(dp(56), WRAP))
        nowTitle.maxLines = 1; nowTitle.ellipsize = TextUtils.TruncateAt.END
        nowRow.addView(nowTitle, lp(0, WRAP, 1f))
        nowRow.addView(nowTime, lp(WRAP, WRAP))
        panel.addView(nowRow, lp(MATCH, WRAP).apply { topMargin = dp(10) })
        panel.addView(bar, lp(MATCH, dp(4)).apply { topMargin = dp(8) })
        val nextLbl = act.label("Next", 13f, Col.MUTED, true)
        nextRow.addView(nextLbl, lp(dp(56), WRAP))
        nextTitle.maxLines = 1; nextTitle.ellipsize = TextUtils.TruncateAt.END
        nextRow.addView(nextTitle, lp(0, WRAP, 1f))
        nextRow.addView(nextTime, lp(WRAP, WRAP))
        panel.addView(nextRow, lp(MATCH, WRAP).apply { topMargin = dp(8) })
        root.addView(panel, lp(MATCH, WRAP).apply { topMargin = dp(8) })

        refreshHints()
        visibility = View.GONE
    }

    private fun button(text: String): TextView = act.label(text, 15f, Col.MUTED, true).apply {
        setPadding(dp(18), dp(9), dp(18), dp(9))
        background = pillBg(act)
        setTextColor(pillText())
        isFocusable = true
    }

    /** Only buttons the Thomson remote really has: no colour keys, no numbers. */
    fun refreshHints() {
        val parts = ArrayList<String>()
        parts.add("OK: open")
        val v = prefs.keyFor(Action.VOICE)
        if (prefs.voiceEnabled && v == KeyMap.LONG_OK) parts.add("Hold OK: voice")
        parts.add("Back: close")
        epgHint.text = parts.joinToString("    ")
    }

    fun applySettings() {
        if (grid.numColumns != prefs.gridCols) {
            grid.numColumns = prefs.gridCols
            adapter.notifyDataSetChanged()
        }
        clock.visibility = if (prefs.showClock) View.VISIBLE else View.GONE
        refreshHints()
    }

    /** Rebuilds the tabs and the tile list (after a playlist refresh or settings change). */
    fun reload() {
        tabNames = repo.tabs()
        tabRow.removeAllViews()
        for (name in tabNames) {
            val t = button(name)
            t.id = View.generateViewId()
            t.nextFocusDownId = grid.id
            t.setOnFocusChangeListener { _, has -> if (has && name != group) setGroup(name) }
            t.setOnClickListener { grid.requestFocus() }
            tabRow.addView(t, lp(WRAP, WRAP).apply { rightMargin = dp(8) })
        }
        val wanted = prefs.lastGroup
        val g = when {
            wanted.isNotEmpty() && wanted in tabNames -> wanted
            prefs.favourites.isNotEmpty() -> Repo.FAV
            else -> Repo.ALL
        }
        setGroup(g, force = true)
    }

    private fun setGroup(name: String, force: Boolean = false) {
        if (!force && name == group) return
        group = name
        prefs.lastGroup = name
        items = repo.channelsFor(name)
        adapter.notifyDataSetChanged()
        grid.setSelection(0)
        for (i in 0 until tabRow.childCount) {
            val tab = tabRow.getChildAt(i)
            tab.isActivated = tabNames.getOrNull(i) == name
            // Moving up from the grid lands on the current group's tab, not a random one.
            if (tab.isActivated) grid.nextFocusUpId = tab.id
        }
        if (!grid.hasFocus()) {
            epgName.text = when {
                items.isEmpty() && name == Repo.FAV -> "No favourites yet. Add them from the ▶ menu while watching."
                items.isEmpty() && name == Repo.RECENT -> "Nothing watched yet"
            name == Repo.WEATHER -> "${items.size} cities. Add more in Settings, Weather cities."
                else -> "${items.size} channels"
            }
            nowRow.visibility = View.GONE; nextRow.visibility = View.GONE; bar.visibility = View.GONE
        }
    }

    fun open(current: Channel?) {
        visibility = View.VISIBLE
        applySettings()
        // Recent and Favourites change while watching: refresh them each time the grid opens.
        if (group == Repo.RECENT || group == Repo.FAV || group == Repo.WEATHER) {
            items = repo.channelsFor(group)
            adapter.notifyDataSetChanged()
        }
        removeCallbacks(clockTick); post(clockTick)
        if (current != null && items.none { it.key == current.key }) {
            if (group != Repo.ALL && tabNames.contains(current.group)) setGroup(current.group)
            else if (group != Repo.ALL) setGroup(Repo.ALL)
        }
        val idx = current?.let { c -> items.indexOfFirst { it.key == c.key } } ?: -1
        grid.requestFocus()
        if (idx >= 0) grid.setSelection(idx)
        post { refreshHighlight(); showEpg(items.getOrNull(grid.selectedItemPosition)) }
        // Bring the active tab into view.
        val ti = tabNames.indexOf(group)
        if (ti >= 0) tabScroll.post { tabRow.getChildAt(ti)?.let { tabScroll.smoothScrollTo(it.left - dp(40), 0) } }
    }

    fun close() {
        visibility = View.GONE
        removeCallbacks(clockTick)
    }

    fun focusedChannel(): Channel? = if (grid.hasFocus()) items.getOrNull(grid.selectedItemPosition) else null

    fun onFavouritesChanged() {
        if (group == Repo.FAV) {
            val pos = grid.selectedItemPosition
            items = repo.channelsFor(Repo.FAV)
            adapter.notifyDataSetChanged()
            if (items.isNotEmpty()) grid.setSelection(pos.coerceAtMost(items.size - 1))
        } else adapter.notifyDataSetChanged()
    }

    /** Re-reads the current tab (weather temperatures, recent, favourites). */
    fun refreshItems() {
        if (visibility != View.VISIBLE) return
        val pos = grid.selectedItemPosition
        items = repo.channelsFor(group)
        adapter.notifyDataSetChanged()
        if (items.isNotEmpty() && pos >= 0) grid.setSelection(pos.coerceAtMost(items.size - 1))
        refreshEpg()
    }

    fun refreshEpg() {
        if (visibility == View.VISIBLE && grid.hasFocus()) showEpg(items.getOrNull(grid.selectedItemPosition))
    }

    private fun showEpg(ch: Channel?) {
        if (ch == null) return
        if (ch.isWeather) {
            epgName.text = "${ch.name} weather"
            val city = repo.weather.cities().getOrNull(ch.url.removePrefix("weather:").toIntOrNull() ?: -1)
            val f = city?.let { repo.weather.get(it) }
            nowRow.visibility = View.VISIBLE; bar.visibility = View.GONE; nextRow.visibility = View.GONE
            if (f != null) {
                val d = f.days.firstOrNull()
                nowTitle.text = WeatherStore.describe(f.code) + ", ${f.temp}°" + (d?.let { "  (${it.hi}° / ${it.lo}°)" } ?: "")
                nowTime.text = d?.let { "Rain ${it.rain}%   Wind ${it.wind} km/h" } ?: ""
            } else { nowTitle.text = "OK: open the 10-day forecast"; nowTime.text = "" }
            return
        }
        epgName.text = "${ch.number}  ${ch.name}" + if (repo.isFav(ch)) "  ★" else ""
        val now = System.currentTimeMillis()
        val (cur, next) = repo.epg.nowNext(ch, now)
        nowRow.visibility = View.VISIBLE
        if (cur != null) {
            nowTitle.text = cur.title
            nowTime.text = Fmt.range(cur)
            bar.visibility = View.VISIBLE
            setProgress(bar, cur.progress(now))
        } else {
            nowTitle.text = "No guide info for this channel"
            nowTime.text = ""
            bar.visibility = View.GONE
        }
        if (next != null) {
            nextRow.visibility = View.VISIBLE
            nextTitle.text = next.title
            nextTime.text = Fmt.range(next)
        } else nextRow.visibility = View.GONE
    }

    private fun refreshHighlight() {
        val focused = grid.hasFocus()
        val sel = grid.selectedItemPosition
        val first = grid.firstVisiblePosition
        for (i in 0 until grid.childCount) {
            val v = grid.getChildAt(i)
            (v.tag as? Tile)?.style(focused && first + i == sel)
        }
    }

    private inner class Tile(val root: FrameLayout) {
        val logo = ImageView(act)
        val initials = act.label("", 16f, Col.TEXT, true)
        val num = act.label("", 11f, Col.MUTED, true)
        val star = act.label("★", 12f, Col.ACCENT)

        init {
            logo.scaleType = ImageView.ScaleType.FIT_CENTER
            root.addView(logo, LayoutParams(MATCH, MATCH).apply { setMargins(dp(14), dp(12), dp(14), dp(10)) })
            initials.gravity = Gravity.CENTER
            initials.maxLines = 2
            initials.setTextColor(Col.TEXT)
            root.addView(initials, LayoutParams(MATCH, MATCH).apply { setMargins(dp(6), dp(6), dp(6), dp(6)) })
            root.addView(num, LayoutParams(WRAP, WRAP).apply { setMargins(dp(8), dp(6), 0, 0) })
            root.addView(star, LayoutParams(WRAP, WRAP, Gravity.END or Gravity.TOP).apply { setMargins(0, dp(4), dp(8), 0) })
            root.tag = this
            style(false)
        }

        fun bind(c: Channel) {
            num.text = if (c.isWeather) c.badge else c.number.toString()
            initials.text = c.initials
            star.visibility = if (repo.isFav(c)) View.VISIBLE else View.GONE
            Logos.load(c.logo, logo, initials)
        }

        /** Neon focus: the tile lifts with a cyan frame; the logo itself is never covered. */
        fun style(on: Boolean) {
            val r = dp(16).toFloat()
            root.background = if (on) rounded(Col.SURF2, r, dp(3), Col.ACCENT) else rounded(Col.SURF, r, dp(1), Col.LINE)
            num.setTextColor(if (on) Col.ACCENT else Col.SUBTLE)
            val s = if (on) 1.07f else 1f
            root.scaleX = s; root.scaleY = s
            root.translationY = if (on) -dp(5).toFloat() else 0f
            root.translationZ = if (on) dp(10).toFloat() else 0f
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                root.outlineSpotShadowColor = Col.ACCENT
                root.outlineAmbientShadowColor = Col.ACCENT
            }
        }
    }

    private inner class TileAdapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val tile = (convertView?.tag as? Tile) ?: Tile(FrameLayout(act)).also {
                it.root.clipChildren = false
            }
            val h = if (prefs.gridCols >= 6) dp(80) else dp(96)
            tile.root.layoutParams = AbsListView.LayoutParams(MATCH, h)
            tile.bind(items[position])
            tile.style(grid.hasFocus() && position == grid.selectedItemPosition)
            return tile.root
        }
    }
}
