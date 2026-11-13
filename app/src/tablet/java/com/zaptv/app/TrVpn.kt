package com.zaptv.app

import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.io.File
import java.util.concurrent.Executors

/**
 * Turkey connection for channels marked TR (same logic as the ZapTV TR test app on the TV).
 * Uses the official WireGuard tunnel library with the user's Unlocator file and routes ONLY this app
 * through it (IncludedApplications), so the rest of the tablet keeps its normal connection.
 */
class TrVpn private constructor(private val ctx: Context) {
    companion object {
        @Volatile private var inst: TrVpn? = null
        fun get(ctx: Context): TrVpn = inst ?: synchronized(this) {
            inst ?: TrVpn(ctx.applicationContext).also { inst = it }
        }
    }

    private val exec = Executors.newSingleThreadExecutor()
    private var backend: GoBackend? = null
    private val sp = ctx.getSharedPreferences("zaptv", Context.MODE_PRIVATE)

    /** True once the tunnel is up. */
    @Volatile var isUp = false
        private set

    /** Latest requested state; queued requests always apply this, so fast zapping can't leave it wrong. */
    @Volatile var desired = false
        private set

    private val tunnel = object : Tunnel {
        override fun getName() = "zaptv-tr"
        override fun onStateChange(newState: Tunnel.State) { isUp = newState == Tunnel.State.UP }
    }

    val file: File get() = File(ctx.filesDir, "turkey.conf")
    val configured: Boolean get() = file.isFile && file.length() > 0

    // ---------- channels marked TR ----------

    var channels: Set<String>
        get() = sp.getStringSet("tr_channels", emptySet())?.toSet() ?: emptySet()
        set(v) { sp.edit().putStringSet("tr_channels", v).apply() }

    fun isTr(key: String) = key in channels

    fun toggle(key: String) {
        val s = channels.toMutableSet()
        if (!s.add(key)) s.remove(key)
        channels = s
    }

    /** True when this channel should play through Turkey right now. */
    fun needs(key: String?): Boolean = key != null && configured && isTr(key)

    // ---------- file ----------

    /** Android's one-time "allow VPN" dialog, or null when already allowed. */
    fun permissionIntent(): Intent? = VpnService.prepare(ctx)

    /** Returns an error message, or null when the file was saved. */
    fun saveConfig(text: String): String? {
        if (!text.contains("[Interface]") || !text.contains("[Peer]")) return "That is not a WireGuard file."
        runCatching { Config.parse(withThisAppOnly(text).byteInputStream()) }.onFailure {
            return "The file could not be read: ${it.message}"
        }
        // A new file applies from the next TR channel.
        ensure(false) { _, _ -> }
        file.writeText(text)
        return null
    }

    fun removeConfig() {
        ensure(false) { _, _ -> }
        file.delete()
    }

    private fun withThisAppOnly(text: String): String {
        val cleaned = text.lines().filterNot {
            val k = it.substringBefore('=').trim().lowercase()
            k == "includedapplications" || k == "excludedapplications"
        }.joinToString("\n")
        return cleaned.replaceFirst(Regex("(?im)^\\s*\\[Interface\\]\\s*$"), "[Interface]\nIncludedApplications = ${ctx.packageName}")
    }

    /** Brings the tunnel up or down off the main thread; [done] runs on a background thread. */
    fun ensure(up: Boolean, done: (ok: Boolean, error: String?) -> Unit) {
        desired = up
        exec.execute {
            val target = desired
            val result = runCatching {
                val b = backend ?: GoBackend(ctx).also { backend = it }
                if (target && !isUp) {
                    val cfg = Config.parse(withThisAppOnly(file.readText()).byteInputStream())
                    b.setState(tunnel, Tunnel.State.UP, cfg)
                } else if (!target && isUp) {
                    b.setState(tunnel, Tunnel.State.DOWN, null)
                }
            }
            if (result.isFailure && target) isUp = false
            done(result.isSuccess && target == up, result.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName })
        }
    }
}
