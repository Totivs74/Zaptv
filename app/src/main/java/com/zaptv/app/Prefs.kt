package com.zaptv.app

import android.content.Context
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("zaptv", Context.MODE_PRIVATE)

    private inner class Str(val k: String, val d: String = "") : ReadWriteProperty<Any?, String> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): String = p.getString(k, d) ?: d
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) {
            p.edit().putString(k, value).apply()
        }
    }

    private inner class Bool(val k: String, val d: Boolean) : ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): Boolean = p.getBoolean(k, d)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) {
            p.edit().putBoolean(k, value).apply()
        }
    }

    private inner class IntP(val k: String, val d: Int) : ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): Int = p.getInt(k, d)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) {
            p.edit().putInt(k, value).apply()
        }
    }

    private inner class LongP(val k: String, val d: Long) : ReadWriteProperty<Any?, Long> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): Long = p.getLong(k, d)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Long) {
            p.edit().putLong(k, value).apply()
        }
    }

    // Source: "", "m3u", "xtream", "stalker"
    var sourceType by Str("src")
    var m3uUrl by Str("m3u_url")
    var epgUrl by Str("epg_url")
    var xtServer by Str("xt_server")
    var xtUser by Str("xt_user")
    var xtPass by Str("xt_pass")
    var portal by Str("portal")
    var mac by Str("mac")
    var lastRefresh by LongP("last_refresh", 0L)
    var lastEpgRefresh by LongP("last_epg_refresh", 0L)

    // Playback memory
    var lastChannelKey by Str("last_key")
    var prevChannelKey by Str("prev_key")
    var lastUrl by Str("last_url")
    var lastGroup by Str("last_group")

    // Startup
    var autoBoot by Bool("auto_boot", true)
    var autoWake by Bool("auto_wake", true)
    var openWithLast by Bool("open_last", true)

    // Voice
    var voiceEnabled by Bool("voice", true)

    // Player
    var buffer by IntP("buffer", 1)            // 0 fast, 1 balanced, 2 stable
    var autoReconnect by Bool("reconnect", true)
    var ffmpegFirst by Bool("ffmpeg_first", false)
    var audioLang by Str("audio_lang", "tr")
    var subtitles by Bool("subs", false)
    var bannerSec by IntP("banner_sec", 3)     // 0 = off
    var resizeMode by IntP("resize", 0)        // 0 fit, 1 fill, 2 zoom

    // Display
    var gridCols by IntP("grid_cols", 6)
    var showClock by Bool("clock", true)

    // System-wide key that opens the app (accessibility service)
    var globalLaunchKey by IntP("global_key", KeyMap.NONE)

    var favourites: Set<String>
        get() = p.getStringSet("favs", emptySet())?.toSet() ?: emptySet()
        set(v) = p.edit().putStringSet("favs", v).apply()

    /** Last watched channels, newest first (max 10). */
    var recent: List<String>
        get() = (p.getString("recent", "") ?: "").split('\n').filter { it.isNotEmpty() }
        set(v) = p.edit().putString("recent", v.take(10).joinToString("\n")).apply()

    fun addRecent(key: String) {
        recent = listOf(key) + recent.filter { it != key }
    }

    var hiddenGroups: Set<String>
        get() = p.getStringSet("hidden", emptySet())?.toSet() ?: emptySet()
        set(v) = p.edit().putStringSet("hidden", v).apply()

    fun keyFor(a: Action): Int = p.getInt("key_" + a.name, a.defaultKey)

    fun setKeyFor(a: Action, code: Int) {
        val e = p.edit()
        if (code != KeyMap.NONE) {
            for (other in Action.values()) if (other != a && keyFor(other) == code) e.putInt("key_" + other.name, KeyMap.NONE)
        }
        e.putInt("key_" + a.name, code).apply()
    }

    fun resetKeys() {
        val e = p.edit()
        for (a in Action.values()) e.remove("key_" + a.name)
        e.apply()
    }
}
