package com.zaptv.app

import android.graphics.drawable.ColorDrawable
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/** Simple TV guide: channels down, two hours across. Left / Right moves through time. */
class GuideOverlay(private val act: MainActivity) : FrameLayout(act) {
    private val repo = (act.application as App).repo
    private val list = ListView(act)
    private val date = act.label("", 15f, Col.MUTED)
    private val slots = List(4) { act.label("", 13f, Col.MUTED) }
    private var channels: List<Channel> = emptyList()
    private var windowStart = 0L
    private val span = 2 * 3600_000L
    private val adapter = RowAdapter()

    init {
        setBackgroundColor(Col.BG)
        val root = act.vbox()
        root.setPadding(dp(28), dp(22), dp(28), dp(14))
        addView(root, LayoutParams(MATCH, MATCH))

        val head = act.hbox()
        head.addView(act.label("TV Guide", 22f, Col.TEXT, true), lp(0, WRAP, 1f))
        head.addView(date, lp(WRAP, WRAP))
        root.addView(head, lp(MATCH, WRAP))

        val times = act.hbox()
        times.addView(View(act), lp(dp(170), 1))
        for (s in slots) times.addView(s, lp(0, WRAP, 1f))
        root.addView(times, lp(MATCH, WRAP).apply { topMargin = dp(14); bottomMargin = dp(6) })

        list.divider = ColorDrawable(0)
        list.dividerHeight = dp(6)
        list.selector = rounded(0, dp(10).toFloat(), dp(3), Col.ACCENT)
        list.setDrawSelectorOnTop(true)
        list.isVerticalScrollBarEnabled = false
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> channels.getOrNull(pos)?.let { act.playFromGuide(it) } }
        root.addView(list, lp(MATCH, 0, 1f))

        root.addView(act.label("OK: open channel     Left / Right: time     Back: close", 12f, Col.MUTED),
            lp(WRAP, WRAP).apply { topMargin = dp(8) })
        visibility = View.GONE
    }

    private fun floor30(t: Long) = t - (t % (30 * 60_000L))

    fun open(chs: List<Channel>, current: Channel?) {
        channels = chs
        windowStart = floor30(System.currentTimeMillis())
        updateHeader()
        adapter.notifyDataSetChanged()
        visibility = View.VISIBLE
        list.requestFocus()
        val i = current?.let { c -> chs.indexOfFirst { it.key == c.key } } ?: -1
        list.setSelection(if (i >= 0) i else 0)
    }

    fun close() { visibility = View.GONE }

    fun shift(step: Int) {
        val nowFloor = floor30(System.currentTimeMillis())
        windowStart = (windowStart + step * 30 * 60_000L).coerceIn(nowFloor - 3600_000L, nowFloor + 22 * 3600_000L)
        updateHeader()
        adapter.notifyDataSetChanged()
    }

    private fun updateHeader() {
        date.text = Fmt.today()
        for ((i, s) in slots.withIndex()) s.text = Fmt.time(windowStart + i * 30 * 60_000L)
    }

    private inner class RowAdapter : BaseAdapter() {
        override fun getCount() = channels.size
        override fun getItem(position: Int) = channels[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = (convertView as? LinearLayout) ?: act.hbox().apply {
                layoutParams = AbsListView.LayoutParams(MATCH, dp(56))
                val name = act.label("", 14f, Col.TEXT, true).apply {
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                    setPadding(dp(12), 0, dp(8), 0)
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    background = rounded(Col.SURF, dp(10).toFloat())
                }
                addView(name, lp(dp(164), MATCH).apply { rightMargin = dp(6) })
                addView(act.hbox(), lp(0, MATCH, 1f))
            }
            val ch = channels[position]
            (row.getChildAt(0) as TextView).text = "${ch.number}  ${ch.name}"
            fill(row.getChildAt(1) as LinearLayout, ch)
            return row
        }

        private fun fill(box: LinearLayout, ch: Channel) {
            box.removeAllViews()
            val ws = windowStart
            val we = ws + span
            val now = System.currentTimeMillis()
            var cursor = ws
            val progs = repo.epg.forChannel(ch).filter { it.stop > ws && it.start < we }
            if (progs.isEmpty()) {
                box.addView(block("No guide info", "", false), lp(0, MATCH, 1f))
                return
            }
            for (p in progs) {
                val s = maxOf(p.start, ws)
                val e = minOf(p.stop, we)
                if (s > cursor) box.addView(View(act), lp(0, MATCH, (s - cursor).toFloat()))
                val live = p.start <= now && p.stop > now
                box.addView(block(p.title, Fmt.range(p), live), lp(0, MATCH, (e - s).toFloat()).apply { rightMargin = dp(3) })
                cursor = e
            }
            if (cursor < we) box.addView(View(act), lp(0, MATCH, (we - cursor).toFloat()))
        }

        private fun block(title: String, time: String, live: Boolean): View {
            val v = act.vbox()
            v.setPadding(dp(10), dp(6), dp(8), dp(6))
            v.background = rounded(if (live) Col.SURF2 else Col.SURF, dp(10).toFloat(), if (live) dp(1) else 0, Col.SUBTLE)
            v.gravity = android.view.Gravity.CENTER_VERTICAL
            v.addView(act.label(title, 14f, Col.TEXT, live).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            if (time.isNotEmpty()) v.addView(act.label(time, 11f, Col.MUTED).apply { maxLines = 1 })
            return v
        }
    }
}
