package com.zaptv.app

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors

/** Tiny local web page so the playlist can be entered from a phone on the same Wi-Fi. */
class WebServer(private val app: App) {
    @Volatile var port = 0
        private set
    private val pool = Executors.newFixedThreadPool(3)
    private var server: ServerSocket? = null

    fun start() {
        if (server != null) return
        Thread({
            for (p in 8080..8090) {
                try {
                    server = ServerSocket(p)
                    port = p
                    break
                } catch (_: Exception) { }
            }
            val s = server ?: return@Thread
            while (!s.isClosed) {
                try {
                    val sock = s.accept()
                    pool.execute { runCatching { handle(sock) }; runCatching { sock.close() } }
                } catch (_: Exception) { }
            }
        }, "zaptv-web").apply { isDaemon = true }.start()
    }

    fun address(): String {
        val ip = localIp() ?: return ""
        return "http://$ip:${if (port == 0) 8080 else port}"
    }

    private fun handle(sock: Socket) {
        sock.soTimeout = 20000
        val input = BufferedInputStream(sock.getInputStream())
        val head = ByteArrayOutputStream()
        var last4 = 0
        while (true) {
            val b = input.read()
            if (b < 0) return
            head.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break
            if (head.size() > 64 * 1024) return
        }
        val lines = head.toString("UTF-8").split("\r\n")
        val parts = lines.first().split(" ")
        if (parts.size < 2) return
        val method = parts[0]
        val path = parts[1].substringBefore('?')
        var length = 0
        for (l in lines) if (l.lowercase().startsWith("content-length:")) length = l.substringAfter(':').trim().toIntOrNull() ?: 0
        if (length > 30 * 1024 * 1024) { respond(sock, 413, "text/plain", "Too large"); return }
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) break
            read += n
        }
        when {
            method == "POST" && path == "/save" -> {
                val form = parseForm(String(body, 0, read, Charsets.UTF_8))
                respond(sock, 200, "text/html; charset=utf-8", page(save(form)))
            }
            method == "POST" && path in setOf("/toggle", "/delete", "/city", "/city_delete") -> {
                val form = parseForm(String(body, 0, read, Charsets.UTF_8))
                respond(sock, 200, "text/html; charset=utf-8", page(action(path, form)))
            }
            else -> respond(sock, 200, "text/html; charset=utf-8", page(null))
        }
    }

    private fun parseForm(s: String): Map<String, String> = s.split('&').mapNotNull {
        val i = it.indexOf('=')
        if (i <= 0) null else URLDecoder.decode(it.substring(0, i), "UTF-8") to URLDecoder.decode(it.substring(i + 1), "UTF-8")
    }.toMap()

    private fun changed() { app.main.post { app.onSourceSaved?.invoke() } }

    private fun save(f: Map<String, String>): String {
        val store = app.repo.playlists
        val type = f["type"].orEmpty()
        val id = store.newId()
        val name = f["name"].orEmpty().trim().ifEmpty { "Playlist ${store.all().size + 1}" }
        val epg = f["epg_url"].orEmpty().trim()
        val p: Playlist = when (type) {
            "m3u" -> {
                val url = f["m3u_url"].orEmpty().trim()
                val text = f["m3u_text"].orEmpty()
                if (url.isEmpty() && text.isBlank()) return "Please upload a file, paste a link, or paste the playlist text."
                if (url.isEmpty()) store.pastedFile(id).writeText(text)
                Playlist(id, name, "m3u", url = url, epgUrl = epg)
            }
            "xtream" -> {
                if (f["xt_server"].isNullOrBlank() || f["xt_user"].isNullOrBlank() || f["xt_pass"].isNullOrBlank())
                    return "Please fill in server, username and password."
                Playlist(id, name, "xtream", epgUrl = epg, xtServer = f["xt_server"]!!.trim(), xtUser = f["xt_user"]!!.trim(), xtPass = f["xt_pass"]!!.trim())
            }
            "stalker" -> {
                val mac = f["mac"].orEmpty().trim().uppercase()
                if (f["portal"].isNullOrBlank() || !Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$").matches(mac))
                    return "Please enter the portal address and a MAC like 00:1A:79:12:34:56."
                Playlist(id, name, "stalker", epgUrl = epg, portal = f["portal"]!!.trim(), mac = mac)
            }
            else -> return "Please choose a source type."
        }
        store.add(p)
        changed()
        return "Added \"$name\". Your TV is loading its channels now."
    }

    private fun action(path: String, f: Map<String, String>): String {
        val store = app.repo.playlists
        val id = f["id"].orEmpty()
        return when (path) {
            "/toggle" -> {
                val p = store.all().firstOrNull { it.id == id } ?: return "That playlist no longer exists."
                store.setOn(id, !p.on); changed()
                "\"${p.name}\" is now ${if (!p.on) "on" else "off"}."
            }
            "/delete" -> {
                val p = store.all().firstOrNull { it.id == id } ?: return "That playlist no longer exists."
                store.delete(id); changed()
                "Deleted \"${p.name}\"."
            }
            "/city" -> {
                val q = f["city"].orEmpty().trim()
                if (q.isEmpty()) return "Type a city name first."
                runCatching { app.repo.weather.addCity(q) }.fold(
                    { c -> app.repo.weather.refreshAll { app.onWeatherChanged?.invoke() }; "Added ${c.name}, ${c.country} to Weather." },
                    { e -> e.message ?: "Couldn't find that city." }
                )
            }
            "/city_delete" -> {
                app.repo.weather.removeCity(f["city"].orEmpty())
                app.main.post { app.onWeatherChanged?.invoke() }
                "Removed ${f["city"]} from Weather."
            }
            else -> "Unknown action."
        }
    }

    private fun respond(sock: Socket, code: Int, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val status = if (code == 200) "OK" else "Error"
        val head = "HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n"
        val out = sock.getOutputStream()
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun page(msg: String?): String {
        val lists = app.repo.playlists.all()
        val cities = app.repo.weather.cities()
        val note = if (msg == null) "" else "<div class=\"msg\">${esc(msg)}</div>"
        val rows = if (lists.isEmpty()) "<p class=\"s\">No playlists yet. Add your first one below.</p>" else lists.joinToString("") { p ->
            """<div class="pl"><div><b>${esc(p.name)}</b><small>${esc(p.label())}</small></div>
<form method="post" action="/toggle"><input type="hidden" name="id" value="${esc(p.id)}"><button class="tg ${if (p.on) "on" else ""}" type="submit">${if (p.on) "On" else "Off"}</button></form>
<form method="post" action="/delete" onsubmit="return confirm('Delete ${esc(p.name).replace("'", "")}?')"><input type="hidden" name="id" value="${esc(p.id)}"><button class="del" type="submit">Delete</button></form></div>"""
        }
        val cityRows = cities.joinToString("") { c ->
            """<div class="pl"><div><b>${esc(c.name)}</b><small>${esc(c.country)}</small></div><form method="post" action="/city_delete"><input type="hidden" name="city" value="${esc(c.name)}"><button class="del" type="submit">Remove</button></form></div>"""
        }
        return """<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>ZapTV</title><style>
:root{color-scheme:dark}body{margin:0;background:#030725;color:#EEF3FF;font:16px/1.45 system-ui,-apple-system,Segoe UI,Roboto,sans-serif}
main{max-width:620px;margin:0 auto;padding:24px 18px 56px}h1{font-size:26px;margin:0 0 4px}h2{font-size:19px;margin:30px 0 10px}p.s{color:#93A0CF;margin:0 0 16px}
.pl{display:flex;align-items:center;gap:10px;padding:12px 14px;border-radius:14px;background:#0B1342;border:1px solid #26326E;margin-bottom:8px}
.pl>div{flex:1;min-width:0}.pl b{display:block}.pl small{color:#93A0CF}.pl form{margin:0}
.tg,.del{padding:9px 14px;border-radius:10px;border:1px solid #26326E;background:#14205C;color:#93A0CF;font:inherit;font-weight:700;width:auto;margin:0}
.tg.on{background:#19E3FF;color:#03081F;border-color:#19E3FF}.del{color:#FF6A4D}
.tabs{display:flex;gap:8px;margin-bottom:12px;flex-wrap:wrap}.tabs label{flex:1;min-width:120px;padding:12px;border:1px solid #26326E;border-radius:12px;text-align:center;cursor:pointer;font-weight:600}
.tabs input{display:none}.tabs input:checked+span{color:#19E3FF}
fieldset{border:0;padding:0;margin:0 0 12px;display:none}fieldset.on{display:block}
label.f{display:block;font-size:14px;color:#93A0CF;margin:12px 0 6px}
input[type=text],input[type=url],input[type=password],textarea{width:100%;box-sizing:border-box;padding:12px;border-radius:10px;border:1px solid #26326E;background:#0B1342;color:#EEF3FF;font:inherit}
input[type=file]{width:100%;box-sizing:border-box;padding:12px;border-radius:10px;border:1px dashed #19E3FF;background:#0B1342;color:#EEF3FF;font:inherit}
textarea{min-height:110px}button{width:100%;padding:14px;border:0;border-radius:12px;background:#19E3FF;color:#03081F;font:inherit;font-weight:700;margin-top:16px;cursor:pointer}
.msg{background:#0B1342;border:1px solid #19E3FF;border-radius:12px;padding:12px;margin:12px 0}small{color:#93A0CF}
.row{display:flex;gap:8px}.row input{flex:1}.row button{width:auto;margin:0;padding:12px 18px}
</style></head><body><main><h1>ZapTV</h1><p class="s">Manage your playlists and weather cities. Changes show up on the TV straight away.</p>$note
<h2>Playlists</h2>$rows
<h2>Add a playlist</h2>
<form method="post" action="/save">
<label class="f" for="name">Name</label><input type="text" id="name" name="name" placeholder="e.g. Turkish News">
<div class="tabs" style="margin-top:14px">
<label><input type="radio" name="type" value="m3u" checked onchange="sw()"><span>M3U</span></label>
<label><input type="radio" name="type" value="xtream" onchange="sw()"><span>Xtream Codes</span></label>
<label><input type="radio" name="type" value="stalker" onchange="sw()"><span>MAC portal</span></label>
</div>
<fieldset id="m3u"><label class="f" for="m3u_file">Upload an M3U file</label><input type="file" id="m3u_file" accept=".m3u,.m3u8,.txt,audio/x-mpegurl,application/x-mpegurl,audio/mpegurl">
<small id="file_note"></small>
<label class="f" for="m3u_url">Or an M3U link</label><input type="url" id="m3u_url" name="m3u_url" placeholder="http://...">
<label class="f" for="m3u_text">Or paste the playlist text</label><textarea id="m3u_text" name="m3u_text" placeholder="#EXTM3U ..."></textarea></fieldset>
<fieldset id="xtream"><label class="f" for="xt_server">Server</label><input type="text" id="xt_server" name="xt_server" placeholder="http://host:port">
<label class="f" for="xt_user">Username</label><input type="text" id="xt_user" name="xt_user" autocapitalize="off">
<label class="f" for="xt_pass">Password</label><input type="password" id="xt_pass" name="xt_pass"></fieldset>
<fieldset id="stalker"><label class="f" for="portal">Portal address</label><input type="text" id="portal" name="portal" placeholder="http://host:port/c/">
<label class="f" for="mac">MAC address (from your provider)</label><input type="text" id="mac" name="mac" placeholder="00:1A:79:XX:XX:XX" autocapitalize="characters"></fieldset>
<label class="f" for="epg_url">EPG link (optional)</label><input type="url" id="epg_url" name="epg_url" placeholder="Leave empty to use the one in the playlist">
<button type="submit">Add playlist</button></form>
<h2>Weather cities</h2>$cityRows
<form method="post" action="/city" class="row"><input type="text" name="city" placeholder="Add a city, e.g. Antalya" aria-label="City name"><button type="submit">Add</button></form>
<p class="s" style="margin-top:22px">ZapTV, created by Bulent Cansever.</p></main>
<script>document.getElementById('m3u_file').addEventListener('change',function(e){var f=e.target.files[0];if(!f)return;var r=new FileReader();r.onload=function(){var t=String(r.result);document.getElementById('m3u_text').value=t;document.getElementById('m3u_url').value='';var n=(t.match(/#EXTINF/g)||[]).length;var l=(t.match(/tvg-logo="[^"]+"/g)||[]).length;document.getElementById('file_note').textContent='Loaded '+f.name+': '+n+' channels, '+l+' with a logo.';var nm=document.getElementById('name');if(!nm.value)nm.value=f.name.replace(/\.[^.]+$/,'')};r.readAsText(f)});
function sw(){var v=document.querySelector('input[name=type]:checked').value;['m3u','xtream','stalker'].forEach(function(id){document.getElementById(id).className=id==v?'on':''})}sw()</script>
</body></html>"""
    }

    companion object {
        fun localIp(): String? = runCatching {
            val cands = ArrayList<Pair<String, String>>()
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in ni.inetAddresses) if (a is Inet4Address && !a.isLoopbackAddress) cands.add(ni.name to (a.hostAddress ?: ""))
            }
            (cands.firstOrNull { it.first.startsWith("eth") } ?: cands.firstOrNull { it.first.startsWith("wlan") } ?: cands.firstOrNull())?.second
        }.getOrNull()
    }
}
