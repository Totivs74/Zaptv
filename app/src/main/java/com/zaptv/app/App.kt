package com.zaptv.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings

class App : Application() {
    lateinit var prefs: Prefs
    lateinit var repo: Repo
    lateinit var web: WebServer
    val main = Handler(Looper.getMainLooper())

    /** Set by MainActivity; called when the phone page saves a new source. */
    var onSourceSaved: (() -> Unit)? = null

    /** Set by MainActivity; called when weather cities change. */
    var onWeatherChanged: (() -> Unit)? = null

    /** Set when settings changed something the grid shows (groups, favourites, list). */
    var gridDirty = false

    override fun onCreate() {
        super.onCreate()
        inst = this
        prefs = Prefs(this)
        repo = Repo(this)
        Logos.init(this)
        web = WebServer(this)
        web.start()
    }

    companion object {
        lateinit var inst: App

        fun canAutoStart(ctx: Context): Boolean =
            Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(ctx)

        /** Starts the small background service that reopens the app when the TV wakes up. */
        fun syncAutoStartService(ctx: Context) {
            val p = (ctx.applicationContext as App).prefs
            val i = Intent(ctx, AutoStartService::class.java)
            if (p.autoWake) {
                runCatching {
                    if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
                }
            } else {
                runCatching { ctx.stopService(i) }
            }
        }

        fun launchMain(ctx: Context) {
            val i = Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            runCatching { ctx.startActivity(i) }
        }
    }
}
