package com.zaptv.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One playlist source. Several can be on at the same time. */
data class Playlist(
    val id: String,
    var name: String,
    val type: String,            // "m3u", "xtream", "stalker"
    val url: String = "",        // M3U link (empty = uploaded / pasted file)
    val epgUrl: String = "",
    val xtServer: String = "",
    val xtUser: String = "",
    val xtPass: String = "",
    val portal: String = "",
    val mac: String = "",
    var on: Boolean = true,
    var lastRefresh: Long = 0
) {
    fun label(): String = when (type) {
        "m3u" -> if (url.isNotBlank()) "M3U link" else "M3U file"
        "xtream" -> "Xtream Codes"
        "stalker" -> "MAC portal"
        else -> type
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("type", type).put("url", url).put("epg", epgUrl)
        .put("xs", xtServer).put("xu", xtUser).put("xp", xtPass).put("portal", portal).put("mac", mac)
        .put("on", on).put("lr", lastRefresh)

    companion object {
        fun fromJson(o: JSONObject) = Playlist(
            id = o.optString("id"), name = o.optString("name"), type = o.optString("type"),
            url = o.optString("url"), epgUrl = o.optString("epg"),
            xtServer = o.optString("xs"), xtUser = o.optString("xu"), xtPass = o.optString("xp"),
            portal = o.optString("portal"), mac = o.optString("mac"),
            on = o.optBoolean("on", true), lastRefresh = o.optLong("lr", 0)
        )
    }
}

class PlaylistStore(private val ctx: Context, private val prefs: Prefs) {
    private val sp = ctx.getSharedPreferences("zaptv_pl", Context.MODE_PRIVATE)

    fun pastedFile(id: String) = File(ctx.filesDir, "pl_$id.m3u")
    fun cacheFile(id: String) = File(ctx.filesDir, "channels_$id.tsv")

    @Synchronized
    fun all(): MutableList<Playlist> {
        migrate()
        val arr = runCatching { JSONArray(sp.getString("list", "[]")) }.getOrDefault(JSONArray())
        return (0 until arr.length()).map { Playlist.fromJson(arr.getJSONObject(it)) }.toMutableList()
    }

    @Synchronized
    fun save(list: List<Playlist>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        sp.edit().putString("list", arr.toString()).apply()
    }

    fun active(): List<Playlist> = all().filter { it.on }

    fun add(p: Playlist) { val l = all(); l.add(p); save(l) }

    fun setOn(id: String, on: Boolean) {
        val l = all(); l.firstOrNull { it.id == id }?.on = on; save(l)
    }

    fun touch(id: String) {
        val l = all(); l.firstOrNull { it.id == id }?.lastRefresh = System.currentTimeMillis(); save(l)
    }

    fun delete(id: String) {
        save(all().filter { it.id != id })
        pastedFile(id).delete()
        cacheFile(id).delete()
    }

    fun newId(): String = java.lang.Long.toString(System.currentTimeMillis(), 36)

    /** Moves the single source from version 1.0 / 1.1 into the playlist list, once. */
    private fun migrate() {
        if (sp.getBoolean("migrated", false)) return
        val e = sp.edit().putBoolean("migrated", true)
        if (prefs.sourceType.isNotEmpty() && !sp.contains("list")) {
            val id = "p1"
            val p = Playlist(
                id = id, name = "My playlist", type = prefs.sourceType, url = prefs.m3uUrl, epgUrl = prefs.epgUrl,
                xtServer = prefs.xtServer, xtUser = prefs.xtUser, xtPass = prefs.xtPass, portal = prefs.portal, mac = prefs.mac
            )
            val old = File(ctx.filesDir, "pasted.m3u")
            if (old.exists()) old.renameTo(pastedFile(id))
            val oldCache = File(ctx.filesDir, "channels.tsv")
            if (oldCache.exists()) oldCache.renameTo(cacheFile(id))
            e.putString("list", JSONArray().put(p.toJson()).toString())
        }
        e.apply()
    }
}
