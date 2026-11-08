package com.zaptv.updater

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

object Installer {
    const val ZAPTV_PACKAGE = "com.zaptv.app"
    const val REPO = "Totivs74/Zaptv"
    const val APK_URL = "https://github.com/$REPO/releases/latest/download/tv.apk"
    const val VERSION_URL = "https://github.com/$REPO/releases/latest/download/version.json"

    /** Set by the activity to show progress / results on screen. */
    @Volatile var listener: ((String) -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())
    fun say(msg: String) = main.post { listener?.invoke(msg) }

    /** Open with retries: GitHub's release CDN intermittently returns 500/502/503/504. */
    fun open(url: String): HttpURLConnection {
        var lastError: Exception? = null
        repeat(5) { attempt ->
            try {
                return openOnce(url)
            } catch (e: Exception) {
                lastError = e
                try { Thread.sleep(700L + attempt * 800L) } catch (_: InterruptedException) {}
            }
        }
        throw lastError ?: java.io.IOException("Couldn't reach GitHub")
    }

    private fun openOnce(url: String): HttpURLConnection {
        var current = url
        repeat(6) {
            val c = URL(current).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.setRequestProperty("User-Agent", "ZapTV-Updater/1.0")
            val code = c.responseCode
            if (code in 300..399) {
                current = URL(URL(current), c.getHeaderField("Location")).toString()
                c.disconnect()
            } else if (code >= 500) {
                c.disconnect(); throw java.io.IOException("GitHub busy ($code)")
            } else if (code >= 400) {
                c.disconnect(); throw IllegalStateException("Server answered $code")
            } else return c
        }
        throw IllegalStateException("Too many redirects")
    }

    fun download(url: String, target: File, progress: (Int) -> Unit) {
        val c = open(url)
        val total = c.contentLengthLong
        c.inputStream.use { input -> target.outputStream().use { out -> copy(input, out, total, progress) } }
    }

    fun copy(input: InputStream, out: OutputStream, total: Long, progress: (Int) -> Unit) {
        val buf = ByteArray(64 * 1024)
        var done = 0L
        var lastPct = -1
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            done += n
            if (total > 0) {
                val pct = (done * 100 / total).toInt()
                if (pct != lastPct) { lastPct = pct; progress(pct) }
            }
        }
    }

    /** Hands the APK to Android's installer. Android then asks the user to confirm on screen. */
    fun install(ctx: Context, apk: File) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            apk.inputStream().use { input ->
                s.openWrite("app.apk", 0, apk.length()).use { out ->
                    input.copyTo(out, 64 * 1024)
                    s.fsync(out)
                }
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val intent = Intent(ctx, InstallReceiver::class.java)
            val status = PendingIntent.getBroadcast(ctx, id, intent, flags)
            s.commit(status.intentSender)
        }
    }

    fun installedVersion(ctx: Context): Pair<Long, String>? = runCatching {
        val info = ctx.packageManager.getPackageInfo(ZAPTV_PACKAGE, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else {
            @Suppress("DEPRECATION") info.versionCode.toLong()
        }
        Pair(code, info.versionName ?: "")
    }.getOrNull()
}

/** Receives the installer's answer: ask the user to confirm, then report the result. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { ctx.startActivity(confirm) }
                    Installer.say("Press Install on the screen to finish.")
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Installer.say("Installed. All done.")
            PackageInstaller.STATUS_FAILURE_ABORTED -> Installer.say("Install cancelled.")
            else -> Installer.say("Install failed: " + (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown reason"))
        }
    }
}
