package com.zaptv.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/** Listens for the TV waking from standby and brings ZapTV to the front. */
class AutoStartService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var registered = false

    private val screenOn = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val app = applicationContext as App
            if (app.prefs.autoWake) handler.postDelayed({ App.launchMain(applicationContext) }, 1500)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        if (!registered) {
            registerReceiver(screenOn, IntentFilter(Intent.ACTION_SCREEN_ON))
            registered = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        if (!(applicationContext as App).prefs.autoWake) stopSelf()
        return START_STICKY
    }

    private fun startAsForeground() {
        val n: Notification = if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("auto", "Auto start", NotificationManager.IMPORTANCE_MIN))
            Notification.Builder(this, "auto")
                .setContentTitle("ZapTV")
                .setContentText("Ready to start when the TV turns on")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("ZapTV")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .build()
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(1, n)
        }
    }

    override fun onDestroy() {
        if (registered) runCatching { unregisterReceiver(screenOn) }
        registered = false
        super.onDestroy()
    }
}
