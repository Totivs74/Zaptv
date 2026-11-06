package com.zaptv.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext as App
        App.syncAutoStartService(ctx)
        if (app.prefs.autoBoot) {
            val pending = goAsync()
            // Give the Google TV home screen a moment to finish loading first.
            Handler(Looper.getMainLooper()).postDelayed({
                App.launchMain(ctx)
                pending.finish()
            }, 4000)
        }
    }
}
