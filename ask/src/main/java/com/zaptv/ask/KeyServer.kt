package com.zaptv.ask

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors

/** Tiny page so the Gemini key (and optional image-search key) can be pasted from a phone. */
class KeyServer(private val prefs: Prefs) {
    @Volatile var port = 0; private set
    private var server: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(2)
    var onSaved: (() -> Unit)? = null

    fun start() {
        if (server != null) return
        Thread({
            for (p in 8070..8079) { try { server = ServerSocket(p); port = p; break } catch (_: Exception) {} }
            val s = server ?: return@Thread
            while (!s.isClosed) {
                try { val sock = s.accept(); pool.execute { runCatching { handle(sock) }; runCatching { sock.close() } } } catch (_: Exception) {}
            }
        }, "ask-web").apply { isDaemon = true }.start()
    }

    fun address(): String { val ip = localIp() ?: return ""; return "http://$ip:${if (port == 0) 8070 else port}" }

    private fun handle(sock: Socket) {
        sock.soTimeout = 15000
        val input = BufferedInputStream(sock.getInputStream())
        val head = ByteArrayOutputStream(); var last4 = 0
        while (true) { val b = input.read(); if (b < 0) return; head.write(b); last4 = (last4 shl 8) or b; if (last4 == 0x0D0A0D0A) break; if (head.size() > 32768) return }
        val lines = head.toString("UTF-8").split("\r\n")
        val parts = lines.first().split(" "); if (parts.size < 2) return
        var length = 0
        for (l in lines) if (l.lowercase().startsWith("content-length:")) length = l.substringAfter(':').trim().toIntOrNull() ?: 0
        val body = ByteArray(length); var r = 0; while (r < length) { val n = input.read(body, r, length - r); if (n < 0) break; r += n }
        if (parts[0] == "POST" && parts[1].startsWith("/save")) {
            val f = String(body, 0, r, Charsets.UTF_8).split('&').mapNotNull { val i = it.indexOf('='); if (i <= 0) null else URLDecoder.decode(it.substring(0, i), "UTF-8") to URLDecoder.decode(it.substring(i + 1), "UTF-8") }.toMap()
            f["gkey"]?.takeIf { it.isNotBlank() }?.let { prefs.geminiKey = it }
            f["imgkey"]?.let { prefs.imageKey = it }
            f["imgcx"]?.let { prefs.imageCx = it }
            onSaved?.invoke()
            respond(sock, page("Saved. You can go back to the TV and start asking."))
        } else respond(sock, page(null))
    }

    private fun respond(sock: Socket, html: String) {
        val b = html.toByteArray(Charsets.UTF_8)
        val o = sock.getOutputStream()
        o.write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray())
        o.write(b); o.flush()
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")

    private fun page(msg: String?): String {
        val note = if (msg == null) "" else "<div class=\"msg\">${esc(msg)}</div>"
        val hasKey = prefs.geminiKey.isNotBlank()
        return """<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Ask setup</title><style>
:root{color-scheme:dark}body{margin:0;background:#030725;color:#EEF3FF;font:16px/1.5 system-ui,-apple-system,Segoe UI,Roboto,sans-serif}
main{max-width:560px;margin:0 auto;padding:26px 18px 50px}h1{font-size:26px;margin:0 0 4px}p.s{color:#93A0CF}
label.f{display:block;font-size:14px;color:#93A0CF;margin:16px 0 6px}
input{width:100%;box-sizing:border-box;padding:13px;border-radius:10px;border:1px solid #26326E;background:#0B1342;color:#EEF3FF;font:inherit}
button{width:100%;padding:15px;border:0;border-radius:12px;background:#19E3FF;color:#03081F;font:inherit;font-weight:700;margin-top:20px;cursor:pointer}
.msg{background:#0B1342;border:1px solid #19E3FF;border-radius:12px;padding:12px;margin:14px 0}
.box{background:#0B1342;border:1px solid #26326E;border-radius:14px;padding:16px 18px;margin-top:14px}
a{color:#19E3FF}small{color:#93A0CF}
</style></head><body><main><h1>Ask setup</h1><p class="s">Paste your free Gemini key. It stays on your TV.</p>$note
<form method="post" action="/save">
<div class="box"><b>Gemini key ${if (hasKey) "(saved, leave blank to keep)" else "(required)"}</b>
<label class="f" for="gkey">From aistudio.google.com/apikey</label>
<input type="text" id="gkey" name="gkey" placeholder="AIza..." autocapitalize="off" autocomplete="off"></div>
<div class="box"><b>Real photos on cards (optional)</b>
<small>Without these, cards use each source's preview image. For sharper product photos, add a free Google Programmable Search key and engine ID.</small>
<label class="f" for="imgkey">Image search API key</label>
<input type="text" id="imgkey" name="imgkey" value="${esc(prefs.imageKey)}" placeholder="(optional)" autocapitalize="off">
<label class="f" for="imgcx">Search engine ID (cx)</label>
<input type="text" id="imgcx" name="imgcx" value="${esc(prefs.imageCx)}" placeholder="(optional)" autocapitalize="off"></div>
<button type="submit">Save to TV</button></form>
<p class="s" style="margin-top:20px">Ask, created by Bulent Cansever.</p></main></body></html>"""
    }

    companion object {
        fun localIp(): String? = runCatching {
            val c = ArrayList<Pair<String, String>>()
            for (ni in NetworkInterface.getNetworkInterfaces()) { if (!ni.isUp || ni.isLoopback) continue; for (a in ni.inetAddresses) if (a is Inet4Address && !a.isLoopbackAddress) c.add(ni.name to (a.hostAddress ?: "")) }
            (c.firstOrNull { it.first.startsWith("eth") } ?: c.firstOrNull { it.first.startsWith("wlan") } ?: c.firstOrNull())?.second
        }.getOrNull()
    }
}
