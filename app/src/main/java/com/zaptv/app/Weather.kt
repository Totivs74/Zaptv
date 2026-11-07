package com.zaptv.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class City(val name: String, val country: String, val lat: Double, val lon: Double)
data class WxDay(val day: String, val code: Int, val hi: Int, val lo: Int, val rain: Int, val wind: Int)
data class Forecast(val temp: Int, val code: Int, val humidity: Int, val wind: Int, val days: List<WxDay>, val fetched: Long)

/** Weather "channels": cities you add, forecast from Open-Meteo (free, no key; data CC BY 4.0). */
class WeatherStore(private val app: App) {
    private val sp = app.getSharedPreferences("zaptv_wx", Context.MODE_PRIVATE)
    private val cacheFile = File(app.filesDir, "weather.json")
    private val forecasts = ConcurrentHashMap<String, Forecast>()

    fun cities(): List<City> {
        if (!sp.contains("cities")) {
            saveCities(listOf(City("Istanbul", "Türkiye", 41.0138, 28.9497), City("London", "United Kingdom", 51.5085, -0.1257)))
        }
        val arr = runCatching { JSONArray(sp.getString("cities", "[]")) }.getOrDefault(JSONArray())
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            City(o.optString("n"), o.optString("c"), o.optDouble("lat"), o.optDouble("lon"))
        }
    }

    private fun saveCities(l: List<City>) {
        val arr = JSONArray()
        l.forEach { arr.put(JSONObject().put("n", it.name).put("c", it.country).put("lat", it.lat).put("lon", it.lon)) }
        sp.edit().putString("cities", arr.toString()).apply()
    }

    /** Looks the city up by name (blocking) and adds it. Returns the added city. */
    fun addCity(query: String): City {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val js = JSONObject(Http.text("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=1&language=en&format=json"))
        val r = js.optJSONArray("results")?.optJSONObject(0) ?: throw IllegalArgumentException("City not found: $query")
        val c = City(r.optString("name"), r.optString("country"), r.optDouble("latitude"), r.optDouble("longitude"))
        val l = cities().filter { it.name != c.name } + c
        saveCities(l)
        return c
    }

    fun removeCity(name: String) {
        saveCities(cities().filter { it.name != name })
        forecasts.remove(name)
    }

    fun get(c: City): Forecast? = forecasts[c.name]

    fun stale(c: City): Boolean = (forecasts[c.name]?.fetched ?: 0L) < System.currentTimeMillis() - 30 * 60_000L

    /** Downloads the 10-day forecast (blocking). */
    fun fetch(c: City): Forecast {
        val url = String.format(
            Locale.US,
            "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f" +
                "&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,wind_speed_10m_max" +
                "&forecast_days=10&timezone=auto",
            c.lat, c.lon
        )
        val js = JSONObject(Http.text(url))
        val cur = js.getJSONObject("current")
        val d = js.getJSONObject("daily")
        val time = d.getJSONArray("time")
        val inFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val outFmt = SimpleDateFormat("EEE", Locale.UK)
        val days = ArrayList<WxDay>()
        for (i in 0 until time.length()) {
            val name = if (i == 0) "Today" else runCatching { outFmt.format(inFmt.parse(time.getString(i))!!) }.getOrDefault("")
            days.add(
                WxDay(
                    name,
                    d.getJSONArray("weather_code").optInt(i),
                    Math.round(d.getJSONArray("temperature_2m_max").optDouble(i)).toInt(),
                    Math.round(d.getJSONArray("temperature_2m_min").optDouble(i)).toInt(),
                    d.optJSONArray("precipitation_probability_max")?.optInt(i) ?: 0,
                    Math.round(d.optJSONArray("wind_speed_10m_max")?.optDouble(i) ?: 0.0).toInt()
                )
            )
        }
        val f = Forecast(
            Math.round(cur.optDouble("temperature_2m")).toInt(), cur.optInt("weather_code"),
            cur.optInt("relative_humidity_2m"), Math.round(cur.optDouble("wind_speed_10m")).toInt(),
            days, System.currentTimeMillis()
        )
        forecasts[c.name] = f
        saveCache()
        return f
    }

    /** Refreshes every city in the background; [done] runs on the main thread. */
    fun refreshAll(done: () -> Unit = {}) {
        app.repo.io.execute {
            for (c in cities()) if (stale(c)) runCatching { fetch(c) }
            app.main.post(done)
        }
    }

    fun cityChannels(): List<Channel> = cities().mapIndexed { i, c ->
        Channel(
            name = c.name, group = Repo.WEATHER, logo = "", url = "weather:$i", tvgId = "",
            badge = get(c)?.let { "${it.temp}°" } ?: ""
        )
    }

    private fun saveCache() {
        val o = JSONObject()
        for ((k, f) in forecasts) {
            val days = JSONArray()
            f.days.forEach { days.put(JSONArray().put(it.day).put(it.code).put(it.hi).put(it.lo).put(it.rain).put(it.wind)) }
            o.put(k, JSONObject().put("t", f.temp).put("c", f.code).put("h", f.humidity).put("w", f.wind).put("f", f.fetched).put("d", days))
        }
        runCatching { cacheFile.writeText(o.toString()) }
    }

    fun loadCache() {
        runCatching {
            if (!cacheFile.exists()) return
            val o = JSONObject(cacheFile.readText())
            for (k in o.keys()) {
                val f = o.getJSONObject(k)
                val arr = f.getJSONArray("d")
                val days = (0 until arr.length()).map {
                    val a = arr.getJSONArray(it)
                    WxDay(a.getString(0), a.getInt(1), a.getInt(2), a.getInt(3), a.getInt(4), a.getInt(5))
                }
                forecasts[k] = Forecast(f.getInt("t"), f.getInt("c"), f.getInt("h"), f.getInt("w"), days, f.getLong("f"))
            }
        }
    }

    companion object {
        /** WMO weather codes to plain words. */
        fun describe(code: Int): String = when (code) {
            0 -> "Clear"
            1 -> "Mostly clear"
            2 -> "Partly cloudy"
            3 -> "Cloudy"
            45, 48 -> "Fog"
            51, 53, 55, 56, 57 -> "Drizzle"
            61, 63, 65, 66, 67 -> "Rain"
            71, 73, 75, 77 -> "Snow"
            80, 81, 82 -> "Showers"
            85, 86 -> "Snow showers"
            95, 96, 99 -> "Thunderstorm"
            else -> "Weather"
        }

        fun kind(code: Int): Int = when (code) {
            0, 1 -> 0                 // sun
            2 -> 1                    // sun + cloud
            3, 45, 48 -> 2            // cloud
            in 51..67, 80, 81, 82 -> 3 // rain
            in 71..77, 85, 86 -> 4    // snow
            in 95..99 -> 5            // storm
            else -> 2
        }
    }
}

/** Simple drawn weather icon, no image files needed. */
class WeatherIcon(ctx: Context) : View(ctx) {
    var kind = 0
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val s = minOf(w, h)
        val ox = (w - s) / 2
        val oy = (h - s) / 2
        fun sun(cx: Float, cy: Float, r: Float) { p.style = Paint.Style.FILL; p.color = 0xFFFFC53D.toInt(); c.drawCircle(ox + cx * s, oy + cy * s, r * s, p) }
        fun cloud() {
            p.style = Paint.Style.FILL; p.color = 0xFFC9D6FF.toInt()
            c.drawCircle(ox + 0.40f * s, oy + 0.50f * s, 0.18f * s, p)
            c.drawCircle(ox + 0.60f * s, oy + 0.44f * s, 0.22f * s, p)
            c.drawRoundRect(RectF(ox + 0.20f * s, oy + 0.50f * s, ox + 0.84f * s, oy + 0.70f * s), 0.10f * s, 0.10f * s, p)
        }
        fun drops(color: Int, dots: Boolean) {
            p.color = color
            p.strokeWidth = 0.05f * s
            p.strokeCap = Paint.Cap.ROUND
            for (x in listOf(0.34f, 0.52f, 0.70f)) {
                if (dots) { p.style = Paint.Style.FILL; c.drawCircle(ox + x * s, oy + 0.84f * s, 0.035f * s, p) }
                else { p.style = Paint.Style.STROKE; c.drawLine(ox + x * s, oy + 0.78f * s, ox + (x - 0.05f) * s, oy + 0.92f * s, p) }
            }
        }
        when (kind) {
            0 -> sun(0.5f, 0.5f, 0.24f)
            1 -> { sun(0.36f, 0.36f, 0.17f); cloud() }
            2 -> cloud()
            3 -> { cloud(); drops(Col.ACCENT, false) }
            4 -> { cloud(); drops(0xFFFFFFFF.toInt(), true) }
            else -> {
                cloud()
                p.style = Paint.Style.FILL; p.color = 0xFFFFC53D.toInt()
                val path = Path()
                path.moveTo(ox + 0.55f * s, oy + 0.66f * s); path.lineTo(ox + 0.42f * s, oy + 0.84f * s)
                path.lineTo(ox + 0.52f * s, oy + 0.84f * s); path.lineTo(ox + 0.46f * s, oy + 0.98f * s)
                path.lineTo(ox + 0.64f * s, oy + 0.78f * s); path.lineTo(ox + 0.54f * s, oy + 0.78f * s); path.close()
                c.drawPath(path, p)
            }
        }
    }
}
