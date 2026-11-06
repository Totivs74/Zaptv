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
                val msg = save(form)
                respond(sock, 200, "text/html; charset=utf-8", page(msg))
            }
            else -> respond(sock, 200, "text/html; charset=utf-8", page(null))
        }
    }

    private fun parseForm(s: String): Map<String, String> = s.split('&').mapNotNull {
        val i = it.indexOf('=')
        if (i <= 0) null else URLDecoder.decode(it.substring(0, i), "UTF-8") to URLDecoder.decode(it.substring(i + 1), "UTF-8")
    }.toMap()

    private fun save(f: Map<String, String>): String {
        val p = app.prefs
        when (f["type"]) {
            "m3u" -> {
                val url = f["m3u_url"].orEmpty().trim()
                val text = f["m3u_text"].orEmpty()
                if (url.isEmpty() && text.isBlank()) return "Please paste an M3U link or the playlist text."
                p.m3uUrl = url
                if (url.isEmpty()) app.repo.pastedFile.writeText(text)
            }
            "xtream" -> {
                if (f["xt_server"].isNullOrBlank() || f["xt_user"].isNullOrBlank()) return "Please fill in server, username and password."
                p.xtServer = f["xt_server"]!!.trim()
                p.xtUser = f["xt_user"]!!.trim()
                if (!f["xt_pass"].isNullOrEmpty()) p.xtPass = f["xt_pass"]!!.trim()
            }
            "stalker" -> {
                val mac = f["mac"].orEmpty().trim().uppercase()
                if (f["portal"].isNullOrBlank() || !Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$").matches(mac))
                    return "Please enter the portal address and a MAC like 00:1A:79:12:34:56."
                p.portal = f["portal"]!!.trim()
                p.mac = mac
            }
            else -> return "Please choose a source type."
        }
        p.sourceType = f["type"]!!
        p.epgUrl = f["epg_url"].orEmpty().trim()
        p.lastRefresh = 0
        app.main.post { app.onSourceSaved?.invoke() }
        return "Saved. Your TV is loading the channels now."
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
        val p = app.prefs
        val t = p.sourceType.ifEmpty { "m3u" }
        fun chk(v: String) = if (t == v) "checked" else ""
        val note = if (msg == null) "" else "<div class=\"msg\">${esc(msg)}</div>"
        return """<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>ZapTV setup</title><style>
:root{color-scheme:dark}body{margin:0;background:#0B0D10;color:#F2F3F5;font:16px/1.45 system-ui,-apple-system,Segoe UI,Roboto,sans-serif}
main{max-width:560px;margin:0 auto;padding:24px 18px 48px}h1{font-size:24px;margin:0 0 4px}p.s{color:#A9AFB8;margin:0 0 20px}
.tabs{display:flex;gap:8px;margin-bottom:16px;flex-wrap:wrap}.tabs label{flex:1;min-width:120px;padding:12px;border:1px solid #2A2F37;border-radius:12px;text-align:center;cursor:pointer;font-weight:600}
.tabs input{display:none}.tabs input:checked+span{color:#F5A524}
fieldset{border:0;padding:0;margin:0 0 16px;display:none}fieldset.on{display:block}
label.f{display:block;font-size:14px;color:#A9AFB8;margin:12px 0 6px}
input[type=text],input[type=url],input[type=password],textarea{width:100%;box-sizing:border-box;padding:12px;border-radius:10px;border:1px solid #2A2F37;background:#15181D;color:#F2F3F5;font:inherit}
textarea{min-height:120px}button{width:100%;padding:14px;border:0;border-radius:12px;background:#F5A524;color:#111;font:inherit;font-weight:700;margin-top:18px}
.msg{background:#15181D;border:1px solid #F5A524;border-radius:12px;padding:12px;margin-bottom:16px}small{color:#A9AFB8}
</style></head><body><main><h1>ZapTV setup</h1><p class="s">Fill in one source. Your TV updates as soon as you save.</p>$note
<form method="post" action="/save">
<div class="tabs">
<label><input type="radio" name="type" value="m3u" ${chk("m3u")} onchange="sw()"><span>M3U link</span></label>
<label><input type="radio" name="type" value="xtream" ${chk("xtream")} onchange="sw()"><span>Xtream Codes</span></label>
<label><input type="radio" name="type" value="stalker" ${chk("stalker")} onchange="sw()"><span>MAC portal</span></label>
</div>
<fieldset id="m3u"><label class="f" for="m3u_url">M3U link</label><input type="url" id="m3u_url" name="m3u_url" value="${esc(p.m3uUrl)}" placeholder="http://...">
<label class="f" for="m3u_text">Or paste the playlist text</label><textarea id="m3u_text" name="m3u_text" placeholder="#EXTM3U ..."></textarea></fieldset>
<fieldset id="xtream"><label class="f" for="xt_server">Server</label><input type="text" id="xt_server" name="xt_server" value="${esc(p.xtServer)}" placeholder="http://host:port">
<label class="f" for="xt_user">Username</label><input type="text" id="xt_user" name="xt_user" value="${esc(p.xtUser)}" autocapitalize="off">
<label class="f" for="xt_pass">Password</label><input type="password" id="xt_pass" name="xt_pass" placeholder="${if (p.xtPass.isNotEmpty()) "(saved, leave empty to keep)" else ""}"></fieldset>
<fieldset id="stalker"><label class="f" for="portal">Portal address</label><input type="text" id="portal" name="portal" value="${esc(p.portal)}" placeholder="http://host:port/c/">
<label class="f" for="mac">MAC address (from your provider)</label><input type="text" id="mac" name="mac" value="${esc(p.mac)}" placeholder="00:1A:79:XX:XX:XX" autocapitalize="characters"></fieldset>
<label class="f" for="epg_url">EPG link (optional)</label><input type="url" id="epg_url" name="epg_url" value="${esc(p.epgUrl)}" placeholder="Leave empty to use the one in the playlist">
<small>Only needed if your playlist has no guide built in.</small>
<button type="submit">Save to TV</button></form></main>
<script>function sw(){var v=document.querySelector('input[name=type]:checked').value;['m3u','xtream','stalker'].forEach(function(id){document.getElementById(id).className=id==v?'on':''})}sw()</script>
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
