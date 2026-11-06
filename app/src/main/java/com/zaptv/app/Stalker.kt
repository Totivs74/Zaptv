package com.zaptv.app

import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/** Minimal MAC portal (Stalker / Ministra) client: login, channel list, stream link. */
class Stalker(portalUrl: String, private val mac: String) {
    private val base: String
    private var endpoint: String? = null
    private var token = ""

    init {
        var b = portalUrl.trim().trimEnd('/')
        if (!b.startsWith("http")) b = "http://$b"
        b = b.removeSuffix("/c").removeSuffix("/portal.php").removeSuffix("/server/load.php")
        base = b
    }

    private fun headers(): Map<String, String> {
        val h = mutableMapOf(
            "User-Agent" to "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3",
            "X-User-Agent" to "Model: MAG250; Link: WiFi",
            "Cookie" to "mac=${mac.uppercase()}; stb_lang=en; timezone=Europe/Istanbul",
            "Referer" to "$base/c/"
        )
        if (token.isNotEmpty()) h["Authorization"] = "Bearer $token"
        return h
    }

    private fun call(ep: String, query: String): JSONObject {
        val body = Http.text("$ep?$query&JsHttpRequest=1-xml", headers())
        val start = body.indexOf('{')
        if (start < 0) throw IOException("Portal did not return data")
        return JSONObject(body.substring(start))
    }

    @Synchronized
    fun login() {
        val candidates = listOf("$base/portal.php", "$base/server/load.php", "$base/stalker_portal/server/load.php")
        var lastError: Exception? = null
        for (ep in candidates) {
            try {
                token = ""
                val js = call(ep, "type=stb&action=handshake&token=").optJSONObject("js")
                val t = js?.optString("token").orEmpty()
                if (t.isNotEmpty()) {
                    token = t
                    endpoint = ep
                    runCatching { call(ep, "type=stb&action=get_profile&hd=1&sn=&stb_type=MAG250&device_id=&device_id2=&signature=") }
                    return
                }
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw IOException("Could not log in to the portal" + (lastError?.message?.let { ": $it" } ?: ""))
    }

    fun channels(): List<Channel> {
        login()
        val ep = endpoint!!
        val genres = HashMap<String, String>()
        runCatching {
            val arr = call(ep, "type=itv&action=get_genres").optJSONArray("js")
            if (arr != null) for (i in 0 until arr.length()) {
                val g = arr.getJSONObject(i)
                genres[g.optString("id")] = g.optString("title")
            }
        }
        val data = call(ep, "type=itv&action=get_all_channels").optJSONObject("js")?.optJSONArray("data")
            ?: throw IOException("Portal returned no channels")
        val out = ArrayList<Channel>(data.length())
        for (i in 0 until data.length()) {
            val o = data.getJSONObject(i)
            val logo = o.optString("logo").let { if (it.startsWith("http")) it else "" }
            out.add(
                Channel(
                    name = o.optString("name"),
                    group = genres[o.optString("tv_genre_id")]?.ifBlank { null } ?: "Other",
                    logo = logo,
                    url = "",
                    tvgId = o.optString("xmltv_id"),
                    cmd = o.optString("cmd"),
                    number = o.optString("number").toIntOrNull() ?: 0
                )
            )
        }
        return out
    }

    fun link(cmd: String): String {
        if (endpoint == null) login()
        fun once(): String {
            val q = "type=itv&action=create_link&cmd=" + URLEncoder.encode(cmd, "UTF-8") +
                "&series=&forced_storage=undefined&disable_ad=0&download=0"
            val js = call(endpoint!!, q).optJSONObject("js")
            return cleanCmd(js?.optString("cmd").orEmpty())
        }
        val first = runCatching { once() }.getOrNull()
        if (!first.isNullOrEmpty()) return first
        login()
        return once().ifEmpty { cleanCmd(cmd) }
    }

    private fun cleanCmd(c: String): String =
        c.trim().removePrefix("ffmpeg ").removePrefix("ffrt ").removePrefix("auto ").trim()
}
