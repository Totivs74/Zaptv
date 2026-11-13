package com.zaptv.app

import android.graphics.Rect
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import kotlin.math.abs

/** Weather "channel" for touch: tap a city or a day, swipe up/down to change city, ✕ or Back to close. */
class TWeather(private val act: TabletActivity) : FrameLayout(act) {
    private val app = act.application as App
    private val store get() = app.repo.weather
    private var cityIdx = 0
    private var dayIdx = 0

    private val cityChips = act.hbox()
    private val chipScroll = HorizontalScrollView(act)
    private val city = act.label("", 30f, Col.TEXT, true)
    private val icon = WeatherIcon(act)
    private val temp = act.label("", 56f, Col.TEXT, true)
    private val desc = act.label("", 16f, Col.MUTED)
    private val detail = act.hbox()
    private val days = act.hbox()
    private val dayScroll = HorizontalScrollView(act)
    private val status = act.label("", 14f, Col.MUTED)

    private val swipe = GestureDetector(act, object : GestureDetector.SimpleOnGestureListener() {
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            val s = e1 ?: return false
            if (inside(chipScroll, s) || inside(dayScroll, s)) return false
            val dx = e2.x - s.x
            val dy = e2.y - s.y
            if (abs(dy) > dp(60) && abs(dy) > 2 * abs(dx) && abs(vy) > 400) { changeCity(if (dy < 0) 1 else -1); return true }
            if (abs(dx) > dp(60) && abs(dx) > 2 * abs(dy) && abs(vx) > 400) { changeDay(if (dx < 0) 1 else -1); return true }
            return false
        }
    })

    init {
        setBackgroundColor(Col.BG)
        isClickable = true
        val scroll = ScrollView(act)
        val root = act.vbox()
        root.setPadding(dp(20), dp(16), dp(20), dp(16))

        val top = act.hbox()
        top.addView(act.label("Weather", 15f, Col.MUTED, true), lp(0, WRAP, 1f))
        top.addView(act.label("✕", 22f, Col.MUTED, true).apply {
            setPadding(dp(14), dp(6), dp(6), dp(6))
            setOnClickListener { act.closeWeather() }
        })
        root.addView(top, lp(MATCH, WRAP))

        chipScroll.isHorizontalScrollBarEnabled = false
        chipScroll.addView(cityChips)
        root.addView(chipScroll, lp(MATCH, WRAP).apply { topMargin = dp(8) })

        root.addView(city, lp(WRAP, WRAP).apply { topMargin = dp(16) })
        val now = act.hbox()
        now.addView(icon, lp(dp(76), dp(76)))
        now.addView(temp, lp(WRAP, WRAP).apply { leftMargin = dp(10) })
        now.addView(desc, lp(WRAP, WRAP).apply { leftMargin = dp(16) })
        root.addView(now, lp(WRAP, WRAP).apply { topMargin = dp(6) })
        root.addView(detail, lp(MATCH, WRAP).apply { topMargin = dp(16) })
        root.addView(status, lp(WRAP, WRAP).apply { topMargin = dp(8) })

        dayScroll.isHorizontalScrollBarEnabled = false
        dayScroll.addView(days)
        root.addView(dayScroll, lp(MATCH, WRAP).apply { topMargin = dp(20) })
        root.addView(act.label("Swipe up or down to change city, sideways to change day.", 12f, Col.SUBTLE), lp(WRAP, WRAP).apply { topMargin = dp(14) })
        root.addView(act.label("Weather data: Open-Meteo.com", 11f, Col.SUBTLE), lp(WRAP, WRAP).apply { topMargin = dp(4) })
        scroll.addView(root)
        addView(scroll, LayoutParams(MATCH, MATCH))
        visibility = View.GONE
    }

    private fun inside(v: View, ev: MotionEvent): Boolean {
        val r = Rect()
        if (!v.isShown || !v.getGlobalVisibleRect(r)) return false
        return r.contains(ev.rawX.toInt(), ev.rawY.toInt())
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        swipe.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    val isOpen get() = visibility == View.VISIBLE

    fun open(index: Int) {
        cityIdx = index.coerceIn(0, maxOf(0, store.cities().size - 1))
        dayIdx = 0
        visibility = View.VISIBLE
        render(); load()
    }

    fun close() { visibility = View.GONE }

    private fun changeCity(step: Int) {
        val n = store.cities().size
        if (n == 0) return
        cityIdx = ((cityIdx + step) % n + n) % n
        dayIdx = 0
        render(); load()
    }

    private fun changeDay(step: Int) {
        val f = store.cities().getOrNull(cityIdx)?.let { store.get(it) } ?: return
        dayIdx = (dayIdx + step).coerceIn(0, f.days.size - 1)
        render()
    }

    private fun load() {
        val c = store.cities().getOrNull(cityIdx) ?: return
        if (!store.stale(c)) return
        if (store.get(c) == null) status.text = "Loading the forecast…"
        val want = c.name
        app.repo.io.execute {
            val ok = runCatching { store.fetch(c) }.isSuccess
            app.main.post {
                if (store.cities().getOrNull(cityIdx)?.name != want) return@post
                status.text = if (ok) "" else "Couldn't load the forecast. Check the internet connection."
                render()
            }
        }
    }

    private fun render() {
        val cities = store.cities()
        cityChips.removeAllViews()
        cities.forEachIndexed { i, x ->
            val on = i == cityIdx
            val chip = act.label(x.name, 14f, if (on) Col.DARK else Col.TEXT, true)
            chip.setPadding(dp(14), dp(8), dp(14), dp(8))
            chip.background = rounded(if (on) Col.ACCENT else 0x8014205C.toInt(), dp(999).toFloat())
            chip.setOnClickListener { if (cityIdx != i) { cityIdx = i; dayIdx = 0; render(); load() } }
            cityChips.addView(chip, lp(WRAP, WRAP).apply { rightMargin = dp(8) })
        }
        val c = cities.getOrNull(cityIdx)
        detail.removeAllViews(); days.removeAllViews()
        if (c == null) { city.text = "No cities yet. Add them in Settings."; temp.text = ""; desc.text = ""; icon.visibility = View.INVISIBLE; return }
        city.text = c.name
        val f = store.get(c)
        if (f == null) { temp.text = ""; desc.text = c.country; icon.visibility = View.INVISIBLE; return }
        icon.visibility = View.VISIBLE
        val d = f.days.getOrNull(dayIdx)
        if (dayIdx == 0 || d == null) {
            icon.kind = WeatherStore.kind(f.code)
            temp.text = "${f.temp}°"
            desc.text = WeatherStore.describe(f.code) + (d?.let { "\nHigh ${it.hi}°  Low ${it.lo}°" } ?: "")
        } else {
            icon.kind = WeatherStore.kind(d.code)
            temp.text = "${d.hi}°"
            desc.text = WeatherStore.describe(d.code) + "\nLow ${d.lo}°"
        }
        if (d != null) {
            fun stat(k: String, v: String) {
                val box = act.vbox()
                box.addView(act.label(k, 13f, Col.MUTED))
                box.addView(act.label(v, 18f, Col.TEXT, true), lp(WRAP, WRAP).apply { topMargin = dp(3) })
                detail.addView(box, lp(WRAP, WRAP).apply { rightMargin = dp(24) })
            }
            stat(d.day, WeatherStore.describe(d.code))
            stat("Rain", "${d.rain}%")
            stat("Wind", "${d.wind} km/h")
            if (dayIdx == 0) stat("Humidity", "${f.humidity}%")
        }
        f.days.forEachIndexed { i, x ->
            val cell = act.vbox()
            cell.gravity = Gravity.CENTER_HORIZONTAL
            cell.setPadding(dp(4), dp(10), dp(4), dp(10))
            val on = i == dayIdx
            cell.background = rounded(if (on) Col.SURF2 else Col.SURF, dp(14).toFloat(), dp(if (on) 2 else 1), if (on) Col.ACCENT else Col.LINE)
            cell.addView(act.label(x.day, 13f, Col.TEXT, true))
            val ic = WeatherIcon(act); ic.kind = WeatherStore.kind(x.code)
            cell.addView(ic, lp(dp(38), dp(38)).apply { topMargin = dp(4) })
            cell.addView(act.label("${x.hi}°", 17f, Col.TEXT, true), lp(WRAP, WRAP).apply { topMargin = dp(4) })
            cell.addView(act.label("${x.lo}°", 13f, Col.MUTED))
            cell.addView(act.label(if (x.rain > 0) "${x.rain}% rain" else "Dry", 11f, Col.ACCENT), lp(WRAP, WRAP).apply { topMargin = dp(4) })
            cell.setOnClickListener { dayIdx = i; render() }
            days.addView(cell, lp(dp(84), WRAP).apply { rightMargin = dp(8) })
        }
    }
}
