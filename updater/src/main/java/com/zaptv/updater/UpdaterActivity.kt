package com.zaptv.updater

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** One screen: check GitHub for a new ZapTV, install it, or receive any APK from a laptop. */
class UpdaterActivity : Activity() {
    private val BG = 0xFF030725.toInt()
    private val SURF = 0xFF0B1342.toInt()
    private val TEXT = 0xFFEEF3FF.toInt()
    private val MUTED = 0xFF93A0CF.toInt()
    private val CYAN = 0xFF19E3FF.toInt()
    private val CORAL = 0xFFFF6A4D.toInt()

    private val io = Executors.newSingleThreadExecutor()
    private lateinit var server: UploadServer
    private lateinit var installed: TextView
    private lateinit var latest: TextView
    private lateinit var status: TextView
    private lateinit var address: TextView
    private lateinit var installBtn: TextView
    private var latestCode = -1L

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); includeFontPadding = false
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private fun round(color: Int, r: Int, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
        cornerRadius = dp(r).toFloat(); setColor(color); if (stroke > 0) setStroke(dp(stroke), strokeColor)
    }

    private fun button(label: String, onClick: () -> Unit) = text(label, 17f, TEXT, true).apply {
        setPadding(dp(24), dp(14), dp(24), dp(14))
        gravity = Gravity.CENTER
        isFocusable = true
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), round(CYAN, 999))
            addState(intArrayOf(), round(SURF, 999, 1, 0xFF26326E.toInt()))
        }
        setOnFocusChangeListener { _, has -> setTextColor(if (has) BG else TEXT) }
        setOnClickListener { onClick() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        server = UploadServer(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(BG); setPadding(dp(56), dp(44), dp(56), dp(36)) }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(text("ZapTV Updater", 30f, TEXT, true))
        left.addView(text("Keep ZapTV up to date in one click.", 16f, MUTED), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        installed = text("", 18f, TEXT)
        latest = text("Checking GitHub…", 18f, TEXT)
        left.addView(installed, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(28) })
        left.addView(latest, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        installBtn = button("Install update") { installLatest() }
        row.addView(installBtn)
        row.addView(button("Check again") { check() }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12) })
        row.addView(button("Open ZapTV") { openZapTv() }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12) })
        left.addView(row, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(28) })
        status = text("", 16f, CYAN, true)
        left.addView(status, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(22) })
        root.addView(left, LinearLayout.LayoutParams(0, -1, 1f))

        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = round(SURF, 20, 1, 0xFF26326E.toInt())
            setPadding(dp(24), dp(22), dp(24), dp(22))
        }
        right.addView(text("Send any app from your laptop", 18f, TEXT, true))
        right.addView(text("On the same Wi-Fi, open:", 14f, MUTED), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
        address = text("", 24f, CYAN, true)
        right.addView(address, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        right.addView(text("Drop an APK on that page. Keep this screen open, then press Install when the TV asks.", 14f, MUTED).apply { setLineSpacing(0f, 1.2f) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        right.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        right.addView(text("Created by Bulent Cansever", 12f, 0xFF5D6A9E.toInt()))
        root.addView(right, LinearLayout.LayoutParams(dp(360), -1).apply { leftMargin = dp(36) })
        setContentView(root)
        installBtn.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        Installer.listener = { msg -> status.text = msg; refreshInstalled() }
        server.start()
        address.postDelayed({ address.text = server.address().removePrefix("http://").ifEmpty { "Connect the TV to Wi-Fi" } }, 600)
        refreshInstalled()
        check()
    }

    override fun onPause() {
        Installer.listener = null
        super.onPause()
    }

    override fun onDestroy() {
        server.stop()
        super.onDestroy()
    }

    private fun refreshInstalled() {
        val v = Installer.installedVersion(this)
        installed.text = if (v == null) "ZapTV on this TV: not installed" else "ZapTV on this TV: ${v.second}"
        updateButton()
    }

    private fun updateButton() {
        val have = Installer.installedVersion(this)?.first ?: -1L
        installBtn.text = when {
            have < 0 -> "Install ZapTV"
            latestCode > have -> "Install update"
            else -> "Reinstall"
        }
    }

    private fun check() {
        latest.text = "Checking GitHub (will retry if busy)…"
        io.execute {
            val r = runCatching {
                val js = JSONObject(Installer.open(Installer.VERSION_URL).inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
                Pair(js.optLong("versionCode"), js.optString("versionName"))
            }
            runOnUiThread {
                r.onSuccess { (code, name) ->
                    latestCode = code
                    val have = Installer.installedVersion(this)?.first ?: -1L
                    latest.text = "Latest on GitHub: $name" + if (code > have) "  (new)" else "  (you're up to date)"
                    latest.setTextColor(if (code > have) CORAL else TEXT)
                    updateButton()
                }.onFailure { latest.text = "GitHub is busy right now. Press Check again in a moment." }
            }
        }
    }

    private fun canInstall(): Boolean {
        if (Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls()) return true
        status.text = "Allow ZapTV Updater to install apps (one time), then come back."
        runCatching {
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
        }
        return false
    }

    private fun installLatest() {
        if (!canInstall()) return
        status.text = "Downloading…"
        io.execute {
            val f = File(cacheDir, "zaptv.apk")
            runCatching {
                Installer.download(Installer.APK_URL, f) { pct -> Installer.say("Downloading… $pct%") }
                Installer.say("Installing…")
                Installer.install(this, f)
            }.onFailure { e -> Installer.say("Couldn't update: ${e.message}") }
        }
    }

    private fun openZapTv() {
        val i = packageManager.getLeanbackLaunchIntentForPackage(Installer.ZAPTV_PACKAGE)
            ?: packageManager.getLaunchIntentForPackage(Installer.ZAPTV_PACKAGE)
        if (i == null) status.text = "ZapTV is not installed yet." else startActivity(i)
    }
}
