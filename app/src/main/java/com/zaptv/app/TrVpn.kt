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
 * Turkey connection for channels marked TR. Uses the official WireGuard tunnel library with the
 * user's Unlocator file, and routes ONLY this app through it (IncludedApplications), so the rest
 * of the TV keeps its normal connection.
 */
class TrVpn(private val ctx: Context) {
    private val exec = Executors.newSingleThreadExecutor()
    private var backend: GoBackend? = null

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

    /** Android's one-time "allow VPN" dialog, or null when already allowed. */
    fun permissionIntent(): Intent? = VpnService.prepare(ctx)

    fun saveConfig(text: String): String? {
        if (!text.contains("[Interface]") || !text.contains("[Peer]")) return "That is not a WireGuard file."
        runCatching { Config.parse(withThisAppOnly(text).byteInputStream()) }.onFailure {
            return "The file could not be read: ${it.message}"
        }
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
                val b = backend ?: GoBackend(ctx.applicationContext).also { backend = it }
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
