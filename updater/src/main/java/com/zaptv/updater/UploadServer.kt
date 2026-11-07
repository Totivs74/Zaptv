package com.zaptv.updater

import android.content.Context
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/** Tiny page on port 8090: drop any APK from a laptop and it installs on the TV. */
class UploadServer(private val ctx: Context) {
    @Volatile var port = 0
        private set
    private var server: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(2)

    fun start() {
        if (server != null) return
        Thread({
            for (p in 8090..8099) {
                try { server = ServerSocket(p); port = p; break } catch (_: Exception) {}
            }
            val s = server ?: return@Thread
            while (!s.isClosed) {
                try {
                    val sock = s.accept()
                    pool.execute { runCatching { handle(sock) }; runCatching { sock.close() } }
                } catch (_: Exception) {}
            }
        }, "upd-web").apply { isDaemon = true }.start()
    }

    fun stop() { runCatching { server?.close() }; server = null }

    fun address(): String {
        val ip = localIp() ?: return ""
        return "http://$ip:${if (port == 0) 8090 else port}"
    }

    private fun handle(sock: Socket) {
        sock.soTimeout = 60000
        val input = BufferedInputStream(sock.getInputStream(), 64 * 1024)
        val head = ByteArrayOutputStream()
        var last4 = 0
        while (true) {
            val b = input.read()
            if (b < 0) return
            head.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break
            if (head.size() > 32 * 1024) return
        }
        val lines = head.toString("UTF-8").split("\r\n")
        val parts = lines.first().split(" ")
        if (parts.size < 2) return
        var length = 0L
        var name = "upload.apk"
        for (l in lines) {
            val low = l.lowercase()
            if (low.startsWith("content-length:")) length = l.substringAfter(':').trim().toLongOrNull() ?: 0
            if (low.startsWith("x-file-name:")) name = l.substringAfter(':').trim()
        }
        if (parts[0] == "POST" && parts[1].startsWith("/upload")) {
            if (length <= 0 || length > 300L * 1024 * 1024) { respond(sock, 400, "text/plain", "Choose an APK file (max 300 MB)."); return }
            val f = File(ctx.cacheDir, "upload.apk")
            Installer.say("Receiving $name…")
            f.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var left = length
                while (left > 0) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) break
                    out.write(buf, 0, n)
                    left -= n
                }
            }
            if (f.length() != length) { respond(sock, 400, "text/plain", "Upload interrupted. Try again."); return }
            respond(sock, 200, "text/plain", "Received. Look at the TV and press Install.")
            Installer.say("Installing $name…")
            runCatching { Installer.install(ctx, f) }.onFailure { Installer.say("Couldn't install: ${it.message}") }
            return
        }
        respond(sock, 200, "text/html; charset=utf-8", PAGE)
    }

    private fun respond(sock: Socket, code: Int, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val out = sock.getOutputStream()
        out.write("HTTP/1.1 $code ${if (code == 200) "OK" else "Error"}\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(bytes)
        out.flush()
    }

    companion object {
        fun localIp(): String? = runCatching {
            val c = ArrayList<Pair<String, String>>()
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in ni.inetAddresses) if (a is Inet4Address && !a.isLoopbackAddress) c.add(ni.name to (a.hostAddress ?: ""))
            }
            (c.firstOrNull { it.first.startsWith("eth") } ?: c.firstOrNull { it.first.startsWith("wlan") } ?: c.firstOrNull())?.second
        }.getOrNull()

        private const val PAGE = """<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Send an app to the TV</title><style>
:root{color-scheme:dark}body{margin:0;background:#030725;color:#EEF3FF;font:16px/1.45 system-ui,-apple-system,Segoe UI,Roboto,sans-serif}
main{max-width:560px;margin:0 auto;padding:28px 18px}h1{font-size:26px;margin:0 0 6px}p{color:#93A0CF}
#drop{margin-top:18px;border:2px dashed #19E3FF;border-radius:16px;padding:44px 18px;text-align:center;cursor:pointer;background:#0B1342}
#drop.over{background:#14205C}#msg{margin-top:16px;font-weight:600}progress{width:100%;height:12px;margin-top:14px}
input{display:none}
</style></head><body><main><h1>Send an app to the TV</h1>
<p>Drop an APK file here (or click to choose one). Keep ZapTV Updater open on the TV, then press Install there when it asks.</p>
<label id="drop" for="f">Drop APK here or click to choose</label><input type="file" id="f" accept=".apk,application/vnd.android.package-archive">
<progress id="pg" value="0" max="100" hidden></progress><div id="msg"></div></main>
<script>
var d=document.getElementById('drop'),f=document.getElementById('f'),m=document.getElementById('msg'),pg=document.getElementById('pg');
function send(file){if(!file)return;if(!/\.apk$/i.test(file.name)){m.textContent='That is not an APK file.';return}
 var x=new XMLHttpRequest();x.open('POST','/upload');x.setRequestHeader('Content-Type','application/octet-stream');x.setRequestHeader('X-File-Name',file.name);
 pg.hidden=false;pg.value=0;m.textContent='Sending '+file.name+'…';
 x.upload.onprogress=function(e){if(e.lengthComputable)pg.value=Math.round(e.loaded*100/e.total)};
 x.onload=function(){m.textContent=x.responseText};x.onerror=function(){m.textContent='Could not reach the TV. Is ZapTV Updater open?'};x.send(file)}
f.addEventListener('change',function(){send(f.files[0])});
['dragover','dragenter'].forEach(function(t){d.addEventListener(t,function(e){e.preventDefault();d.className='over'})});
['dragleave','drop'].forEach(function(t){d.addEventListener(t,function(e){e.preventDefault();d.className=''})});
d.addEventListener('drop',function(e){send(e.dataTransfer.files[0])});
</script></body></html>"""
    }
}
