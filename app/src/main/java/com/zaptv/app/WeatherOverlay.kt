package com.zaptv.app

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout

/** Full-screen weather "channel": ▲▼ switches city, ◀▶ picks a day, Back returns to the list. */
class WeatherOverlay(private val act: MainActivity) : FrameLayout(act) {
    private val store = (act.application as App).repo.weather
    private var cityIdx = 0
    private var dayIdx = 0

    private val city = act.label("", 34f, Col.TEXT, true)
    private val icon = WeatherIcon(act)
    private val temp = act.label("", 64f, Col.TEXT, true)
    private val desc = act.label("", 17f, Col.MUTED)
    private val cityList = act.vbox()
    private val detail = act.hbox()
    private val days = act.hbox()
    private val status = act.label("", 15f, Col.MUTED)

    init {
        setBackgroundColor(Col.BG)
        val root = act.vbox()
        root.setPadding(dp(40), dp(28), dp(40), dp(20))
        addView(root, LayoutParams(MATCH, MATCH))

        val top = act.hbox()
        top.gravity = Gravity.TOP
        val left = act.vbox()
        left.addView(city)
        val now = act.hbox()
        now.addView(icon, lp(dp(84), dp(84)))
        now.addView(temp, lp(WRAP, WRAP).apply { leftMargin = dp(12) })
        now.addView(desc, lp(WRAP, WRAP).apply { leftMargin = dp(16) })
        left.addView(now, lp(WRAP, WRAP).apply { topMargin = dp(6) })
        top.addView(left, lp(0, WRAP, 1f))
        cityList.gravity = Gravity.END
        top.addView(cityList, lp(WRAP, WRAP))
        root.addView(top, lp(MATCH, WRAP))

        root.addView(detail, lp(MATCH, WRAP).apply { topMargin = dp(18) })
        root.addView(status, lp(WRAP, WRAP).apply { topMargin = dp(8) })
        root.addView(View(act), lp(MATCH, 0, 1f))
        root.addView(days, lp(MATCH, dp(150)))
        val foot = act.hbox()
        foot.addView(act.label("◀ ▶ choose a day     ▲ ▼ change city     Back: channel list", 12f, Col.SUBTLE), lp(0, WRAP, 1f))
        foot.addView(act.label("Weather data: Open-Meteo.com", 11f, Col.SUBTLE), lp(WRAP, WRAP))
        root.addView(foot, lp(MATCH, WRAP).apply { topMargin = dp(10) })
        visibility = View.GONE
    }

    fun open(index: Int) {
        cityIdx = index.coerceIn(0, maxOf(0, store.cities().size - 1))
        dayIdx = 0
        visibility = View.VISIBLE
        render()
        load()
    }

    fun close() { visibility = View.GONE }

    fun changeCity(step: Int) {
        val n = store.cities().size
        if (n == 0) return
        cityIdx = ((cityIdx + step) % n + n) % n
        dayIdx = 0
        render(); load()
    }

    fun changeDay(step: Int) {
        val f = store.cities().getOrNull(cityIdx)?.let { store.get(it) } ?: return
        dayIdx = (dayIdx + step).coerceIn(0, f.days.size - 1)
        render()
    }

    private fun load() {
        val c = store.cities().getOrNull(cityIdx) ?: return
        if (!store.stale(c)) return
        if (store.get(c) == null) status.text = "Loading the forecast…"
        val want = c.name
        (act.application as App).repo.io.execute {
            val ok = runCatching { store.fetch(c) }.isSuccess
            act.app.main.post {
                if (store.cities().getOrNull(cityIdx)?.name != want) return@post
                status.text = if (ok) "" else "Couldn't load the forecast. Check the internet connection."
                render()
            }
        }
    }

    private fun render() {
        val cities = store.cities()
        val c = cities.getOrNull(cityIdx)
        cityList.removeAllViews()
        cities.forEachIndexed { i, x ->
            cityList.addView(act.label(x.name, 14f, if (i == cityIdx) Col.ACCENT else Col.SUBTLE, i == cityIdx).apply { gravity = Gravity.END },
                lp(WRAP, WRAP).apply { bottomMargin = dp(4) })
        }
        if (c == null) { city.text = "No cities yet"; return }
        city.text = c.name
        val f = store.get(c)
        detail.removeAllViews(); days.removeAllViews()
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
                box.addView(act.label(k, 14f, Col.MUTED))
                box.addView(act.label(v, 20f, Col.TEXT, true), lp(WRAP, WRAP).apply { topMargin = dp(3) })
                detail.addView(box, lp(WRAP, WRAP).apply { rightMargin = dp(30) })
            }
            stat(d.day, WeatherStore.describe(d.code))
            stat("Chance of rain", "${d.rain}%")
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
            cell.addView(ic, lp(dp(40), dp(40)).apply { topMargin = dp(4) })
            cell.addView(act.label("${x.hi}°", 18f, Col.TEXT, true), lp(WRAP, WRAP).apply { topMargin = dp(4) })
            cell.addView(act.label("${x.lo}°", 13f, Col.MUTED))
            cell.addView(act.label(if (x.rain > 0) "${x.rain}% rain" else "Dry", 11f, Col.ACCENT), lp(WRAP, WRAP).apply { topMargin = dp(4) })
            days.addView(cell, lp(0, MATCH, 1f).apply { rightMargin = if (i < f.days.size - 1) dp(8) else 0 })
        }
    }
}
