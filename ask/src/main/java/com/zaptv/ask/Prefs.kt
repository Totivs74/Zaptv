package com.zaptv.ask

import android.content.Context

class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("ask", Context.MODE_PRIVATE)
    var geminiKey: String
        get() = p.getString("gkey", "") ?: ""
        set(v) = p.edit().putString("gkey", v.trim()).apply()
    var imageKey: String
        get() = p.getString("imgkey", "") ?: ""
        set(v) = p.edit().putString("imgkey", v.trim()).apply()
    var imageCx: String
        get() = p.getString("imgcx", "") ?: ""
        set(v) = p.edit().putString("imgcx", v.trim()).apply()
    var speak: Boolean
        get() = p.getBoolean("speak", true)
        set(v) = p.edit().putBoolean("speak", v).apply()
}
