package com.zaptv.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/** Two-pane settings: sections on the left, options on the right. OK changes an option. */
class SettingsActivity : Activity() {
    private val app get() = application as App
    private val prefs get() = app.prefs
    private val repo get() = app.repo

    private lateinit var menu: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private var section = 0
    private var capture: ((Int) -> Unit)? = null
    private var captureGlobal = false

    private class Row(
        val title: String,
        val value: () -> String,
        val note: String = "",
        val toggle: Boolean = false,
        val onClick: (() -> Unit)? = null
    )

    private val sections = listOf("Playlists", "Channels", "Startup", "Remote keys", "Voice command", "Player", "Display", "Weather cities", "About")
    private val subs = listOf(
        "Every playlist that is on adds its channels. Turn them on or off any time.",
        "Fewer tiles, faster browsing.",
        "Start watching the moment the TV turns on.",
        "Assign any action to any key inside the app.",
        "Hold the key and say the channel name in Turkish.",
        "Speed and uninterrupted viewing.",
        "Kept simple.",
        "Each city becomes a channel in the Weather tab.",
        "ZapTV, a fast IPTV player designed by Bulent Cansever."
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = hbox()
        root.setBackgroundColor(Col.BG)
        root.gravity = Gravity.TOP
        root.setPadding(dp(28), dp(24), dp(28), dp(20))

        menu = vbox()
        menu.addView(label("Settings", 24f, Col.TEXT, true), lp(WRAP, WRAP).apply { bottomMargin = dp(14) })
        sections.forEachIndexed { i, name ->
            val b = label("${i + 1}   $name", 16f, Col.TEXT, true)
            b.setPadding(dp(16), dp(11), dp(16), dp(11))
            b.background = focusBg(this, 0, 10)
            b.setTextColor(focusText(0xFFC9CED6.toInt()))
            b.isFocusable = true
            b.setOnFocusChangeListener { _, has -> if (has && section != i) { section = i; render() } }
            b.setOnClickListener { content.getChildAt(0)?.requestFocus() }
            menu.addView(b, lp(MATCH, WRAP).apply { bottomMargin = dp(4) })
        }
        root.addView(menu, lp(dp(250), MATCH))

        val right = vbox()
        title = label("", 22f, Col.TEXT, true)
        subtitle = label("", 14f, Col.MUTED)
        right.addView(title)
        right.addView(subtitle, lp(WRAP, WRAP).apply { topMargin = dp(6); bottomMargin = dp(14) })
        val scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        content = vbox()
        scroll.addView(content)
        right.addView(scroll, lp(MATCH, 0, 1f))
        root.addView(right, lp(0, MATCH, 1f).apply { leftMargin = dp(28) })

        setContentView(root)
        render()
        menu.getChildAt(1)?.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    // ------------------------------------------------------------------ rows

    private fun rows(s: Int): List<Row> = when (s) {
        0 -> repo.playlists.all().map { pl ->
            Row(pl.name, { if (repo.playlists.all().firstOrNull { it.id == pl.id }?.on == true) "On" else "Off" }, pl.label(), toggle = true) {
                val now = repo.playlists.all().firstOrNull { it.id == pl.id } ?: return@Row
                repo.playlists.setOn(pl.id, !now.on)
                app.gridDirty = true
            }
        } + listOf(
            Row("Add a playlist", { "From laptop or phone" }, "Upload a file, paste a link, Xtream or MAC portal") { showPhoneDialog() },
            Row("Delete a playlist", { "Choose" }) { deletePlaylistDialog() },
            Row("Refresh playlists and guide now", { if (repo.busy) "Working…" else "Refresh" }, "Also happens by itself once a day") { refreshNow() }
        )
        1 -> listOf(
            Row("Hide groups", { "${prefs.hiddenGroups.size} hidden" }, "Hidden groups don't appear in the grid or when zapping") { hideGroupsDialog() },
            Row("Favourites", { "${prefs.favourites.size} channels" }, "Add while watching: press ▶, then Favourite. OK here clears them all.") { clearFavourites() },
            Row("Recently watched", { "${prefs.recent.size} channels" }, "Shown as the first tab in the channel list. OK here clears it.") {
                prefs.recent = emptyList(); app.gridDirty = true
            }
        )
        2 -> listOf(
            Row("Auto-start when TV turns on", { onOff(prefs.autoBoot) }, "Needs the \"Display over other apps\" permission below", toggle = true) {
                prefs.autoBoot = !prefs.autoBoot; askOverlayIfNeeded()
            },
            Row("Auto-start when waking from sleep", { onOff(prefs.autoWake) }, toggle = true) {
                prefs.autoWake = !prefs.autoWake; App.syncAutoStartService(this); askOverlayIfNeeded()
            },
            Row("Open with", { if (prefs.openWithLast) "Last channel" else "Channel list" }) { prefs.openWithLast = !prefs.openWithLast },
            Row("Display over other apps", { if (App.canAutoStart(this)) "Allowed" else "Not allowed" }, "Android requires this for auto-start") { openOverlaySettings() }
        )
        3 -> Action.values().map { a ->
            Row(a.label, { KeyMap.name(prefs.keyFor(a)) }, if (a == Action.VOICE) "Press OK to use \"Hold OK\"" else "") { captureKey(a) }
        } + listOf(
            Row("Reset to defaults", { "Reset" }) { prefs.resetKeys(); toast("Keys reset") }
        )
        4 -> listOf(
            Row("Voice command", { onOff(prefs.voiceEnabled) }, "Turkish recognition needs internet", toggle = true) { prefs.voiceEnabled = !prefs.voiceEnabled },
            Row("Recognition language", { "Turkish" }, "Interface stays in English"),
            Row("Trigger key", { KeyMap.name(prefs.keyFor(Action.VOICE)) }, "Change it under Remote keys")
        )
        5 -> listOf(
            Row("Buffer", { listOf("Fast zapping", "Balanced", "Stable")[prefs.buffer.coerceIn(0, 2)] }, "Smaller buffer switches faster; larger one survives weak connections") {
                prefs.buffer = (prefs.buffer + 1) % 3
            },
            Row("Auto-reconnect if stream drops", { onOff(prefs.autoReconnect) }, toggle = true) { prefs.autoReconnect = !prefs.autoReconnect },
            Row("Audio decoder", { if (prefs.ffmpegFirst) "FFmpeg first" else "Hardware first" }, "Use FFmpeg first if some channels play without sound") {
                prefs.ffmpegFirst = !prefs.ffmpegFirst
            },
            Row("Preferred audio language", { langName(prefs.audioLang) }) {
                val order = listOf("tr", "en", "")
                prefs.audioLang = order[(order.indexOf(prefs.audioLang) + 1).mod(order.size)]
            },
            Row("Subtitles", { onOff(prefs.subtitles) }, toggle = true) { prefs.subtitles = !prefs.subtitles },
            Row("Channel info banner", { if (prefs.bannerSec == 0) "Off" else "${prefs.bannerSec} sec" }) {
                val order = listOf(2, 3, 5, 0)
                prefs.bannerSec = order[(order.indexOf(prefs.bannerSec) + 1).mod(order.size)]
            }
        )
        6 -> listOf(
            Row("Show clock", { onOff(prefs.showClock) }, "Top right, in the channel list and while watching", toggle = true) { prefs.showClock = !prefs.showClock },
            Row("Grid size", { if (prefs.gridCols >= 6) "6 × 3" else "5 × 3" }, "5 × 3 shows bigger logos") {
                prefs.gridCols = if (prefs.gridCols >= 6) 5 else 6
            }
        )
        7 -> repo.weather.cities().map { c ->
            Row(c.name, { repo.weather.get(c)?.let { "${it.temp}°" } ?: "" }, c.country + ". OK removes it.") { removeCityDialog(c.name) }
        } + listOf(
            Row("Add a city", { "From laptop or phone" }, "Type the city name on the ZapTV page") { showPhoneDialog() }
        )
        else -> listOf(
            Row("Created by", { "Bulent Cansever" }),
            Row("Version", { versionName() }),
            Row("Laptop / phone page", { app.web.address().ifEmpty { "No network" } }),
            Row("Weather data", { "Open-Meteo.com" }, "Free weather service, data licensed CC BY 4.0"),
            Row("Clear cache", { "Clear" }, "Fixes most issues without reinstalling") { clearCache() }
        )
    }

    private fun deletePlaylistDialog() {
        val all = repo.playlists.all()
        if (all.isEmpty()) { toast("No playlists yet"); return }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Delete which playlist?")
            .setItems(all.map { it.name + "  (" + it.label() + ")" }.toTypedArray()) { _, which ->
                val p = all[which]
                AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Delete \"${p.name}\"?")
                    .setMessage("Its channels disappear from ZapTV. You can add it again later.")
                    .setPositiveButton("Delete") { _, _ -> repo.playlists.delete(p.id); app.gridDirty = true; render() }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun removeCityDialog(name: String) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Remove $name from Weather?")
            .setPositiveButton("Remove") { _, _ -> repo.weather.removeCity(name); render() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun render() {
        title.text = sections[section]
        subtitle.text = subs[section]
        for (i in 1 until menu.childCount) menu.getChildAt(i).isActivated = i - 1 == section
        content.removeAllViews()
        for (r in rows(section)) content.addView(rowView(r), lp(MATCH, WRAP).apply { bottomMargin = dp(8) })
    }

    private fun rowView(r: Row): View {
        val box = hbox()
        box.isFocusable = true
        box.background = focusBg(this, Col.SURF, 14, Col.SURF)
        box.setPadding(dp(18), dp(13), dp(18), dp(13))
        val texts = vbox()
        val t = label(r.title, 16f, Col.TEXT, true)
        texts.addView(t)
        val n = label(r.note, 12f, Col.MUTED)
        if (r.note.isNotEmpty()) texts.addView(n, lp(WRAP, WRAP).apply { topMargin = dp(4) })
        box.addView(texts, lp(0, WRAP, 1f))
        val v = label(r.value(), 15f, Col.TEXT)
        if (r.toggle) {
            v.text = ""
            v.background = toggleBg(r.value() == "On")
            box.addView(v, lp(dp(46), dp(26)))
        } else box.addView(v, lp(WRAP, WRAP).apply { leftMargin = dp(12) })
        if (!r.toggle) v.setTextColor(Col.MUTED)
        box.setOnFocusChangeListener { _, has -> if (!r.toggle) v.setTextColor(if (has) Col.ACCENT else Col.MUTED) }
        box.setOnClickListener {
            val action = r.onClick ?: return@setOnClickListener
            action()
            if (capture == null) {
                if (r.toggle) v.background = toggleBg(r.value() == "On") else v.text = r.value()
            }
        }
        box.tag = r
        return box
    }

    private fun toggleBg(on: Boolean) = rounded(if (on) Col.ACCENT else Col.SUBTLE, dp(13).toFloat())

    private fun onOff(b: Boolean) = if (b) "On" else "Off"
    private fun langName(l: String) = when (l) { "tr" -> "Turkish"; "en" -> "English"; else -> "Stream default" }
    private fun versionName(): String = runCatching { packageManager.getPackageInfo(packageName, 0).versionName ?: "" }.getOrDefault("")

    private fun toast(msg: String) = android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------------ actions

    private fun showPhoneDialog() {
        val addr = app.web.address()
        if (addr.isEmpty()) { toast("Connect the TV to the network first"); return }
        val box = vbox()
        box.gravity = Gravity.CENTER_HORIZONTAL
        box.setPadding(dp(24), dp(16), dp(24), dp(8))
        box.addView(label("Open this on your phone (same Wi-Fi):", 15f, Col.MUTED))
        box.addView(label(addr.removePrefix("http://"), 24f, Col.ACCENT, true), lp(WRAP, WRAP).apply { topMargin = dp(8) })
        val frame = FrameLayout(this)
        frame.background = rounded(0xFFFFFFFF.toInt(), dp(12).toFloat())
        frame.setPadding(dp(10), dp(10), dp(10), dp(10))
        val iv = ImageView(this)
        iv.setImageBitmap(Qr.bitmap(addr, 400))
        frame.addView(iv, FrameLayout.LayoutParams(dp(200), dp(200)))
        box.addView(frame, lp(WRAP, WRAP).apply { topMargin = dp(16) })
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setView(box)
            .setPositiveButton("Done", null)
            .show()
    }

    private fun refreshNow() {
        if (!repo.hasPlaylists()) { toast("Add a playlist first"); return }
        toast("Refreshing…")
        repo.refresh(onList = { err ->
            app.gridDirty = true
            toast(err?.let { "Couldn't refresh: $it" } ?: "Channels updated")
            render()
        }, onEpg = { toast("Guide updated") })
    }

    private fun hideGroupsDialog() {
        val groups = repo.allGroups()
        if (groups.isEmpty()) { toast("No channels loaded yet"); return }
        val hidden = prefs.hiddenGroups.toMutableSet()
        val checked = BooleanArray(groups.size) { groups[it] in hidden }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Hide groups")
            .setMultiChoiceItems(groups.toTypedArray(), checked) { _, which, isChecked ->
                if (isChecked) hidden.add(groups[which]) else hidden.remove(groups[which])
            }
            .setPositiveButton("Save") { _, _ -> prefs.hiddenGroups = hidden; app.gridDirty = true; render() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun clearFavourites() {
        if (prefs.favourites.isEmpty()) return
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Clear all favourites?")
            .setPositiveButton("Clear") { _, _ -> prefs.favourites = emptySet(); app.gridDirty = true; render() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun askOverlayIfNeeded() {
        if ((prefs.autoBoot || prefs.autoWake) && !App.canAutoStart(this)) openOverlaySettings()
    }

    private fun openOverlaySettings() {
        if (App.canAutoStart(this)) { toast("Already allowed"); return }
        val ok = Build.VERSION.SDK_INT >= 23 && runCatching {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }.isSuccess
        if (!ok) {
            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("One-time step from a computer")
                .setMessage("This TV hides the \"Display over other apps\" screen. Run this once from a computer with ADB:\n\nadb shell appops set $packageName SYSTEM_ALERT_WINDOW allow")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun clearCache() {
        Logos.clearDisk()
        cacheDir.listFiles()?.forEach { if (it.isFile) it.delete() }
        File(filesDir, "epg.bin").delete()
        prefs.lastRefresh = 0
        app.gridDirty = true
        toast("Cache cleared. Channels and guide reload on next start.")
    }

    // ------------------------------------------------------------------ key capture

    private fun captureKey(a: Action) {
        toast("Press the key for \"${a.label}\" (Back to cancel)")
        captureGlobal = false
        capture = { code ->
            when {
                code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER ->
                    if (a == Action.VOICE) prefs.setKeyFor(a, KeyMap.LONG_OK) else toast("OK is used for navigation")
                code in KeyMap.protected -> toast("${KeyMap.name(code)} can't be reassigned")
                else -> prefs.setKeyFor(a, code)
            }
        }
    }

    private fun captureGlobalKey() {
        toast("Press the key that should open ZapTV (Back to clear)")
        captureGlobal = true
        capture = { code ->
            if (code in KeyMap.protected) toast("${KeyMap.name(code)} can't be used") else prefs.globalLaunchKey = code
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val c = capture
        if (c != null) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                capture = null
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (captureGlobal) prefs.globalLaunchKey = KeyMap.NONE
                } else c(event.keyCode)
                render()
                content.getChildAt(0)?.post { focusRowTitled() }
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private var lastFocusTitle: String? = null
    private fun focusRowTitled() {
        val t = lastFocusTitle ?: return
        for (i in 0 until content.childCount) {
            val v = content.getChildAt(i)
            if ((v.tag as? Row)?.title == t) { v.requestFocus(); return }
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        (content.findFocus()?.tag as? Row)?.let { lastFocusTitle = it.title }
    }
}
