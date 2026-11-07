package com.zaptv.app

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import java.util.concurrent.Executors

class MainActivity : Activity(), PlayerController.Listener {
    companion object {
        const val REQ_MIC = 7
        const val REQ_VOICE = 8
    }

    private enum class Mode { PLAYER, GRID, GUIDE, OPTIONS, SETUP, WEATHER }

    val app: App get() = application as App
    private val repo get() = app.repo
    private val prefs get() = app.prefs

    lateinit var player: PlayerController
    private lateinit var frame: VideoFrame
    private lateinit var grid: GridOverlay
    private lateinit var guide: GuideOverlay
    private lateinit var options: OptionsOverlay
    private lateinit var voice: VoiceOverlay
    private lateinit var setup: SetupOverlay
    private lateinit var banner: BannerView
    private lateinit var weather: WeatherOverlay
    private val clockView by lazy { label("", 20f, Col.TEXT, true) }
    private val clockTick = object : Runnable {
        override fun run() {
            clockView.text = Fmt.time(System.currentTimeMillis())
            clockView.postDelayed(this, 20_000)
        }
    }
    private val statusView by lazy { label("", 15f, Col.TEXT, true) }
    private val numberView by lazy { label("", 40f, Col.TEXT, true) }

    private var mode = Mode.PLAYER
        set(v) { field = v; syncClock() }
    private var guideReturn = Mode.PLAYER
    private var current: Channel? = null
    private var started = false
    private var okFired = false
    private var lastBack = 0L
    private var numBuf = ""
    private var zapTarget: Channel? = null
    private val links = Executors.newSingleThreadExecutor()

    private val hideStatus = Runnable { statusView.visibility = View.GONE }
    private val commitNumber = Runnable { goToNumber() }
    private val commitZap = Runnable { zapTarget?.let { playChannel(it) }; zapTarget = null }
    private val bufferingHint = Runnable { status("Loading…", sticky = true) }
    private val hourly = object : Runnable {
        override fun run() {
            if (repo.refreshDue() || repo.missingCache()) backgroundRefresh()
            statusView.postDelayed(this, 3600_000L)
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this)
        root.setBackgroundColor(0xFF000000.toInt())
        frame = VideoFrame(this)
        root.addView(frame, FrameLayout.LayoutParams(MATCH, MATCH))

        val subs = label("", 22f, Col.TEXT, true).apply {
            gravity = Gravity.CENTER
            setShadowLayer(6f, 0f, 2f, 0xFF000000.toInt())
        }
        root.addView(subs, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { setMargins(dp(80), 0, dp(80), dp(40)) })

        player = PlayerController(this, frame, subs, prefs)
        player.listener = this

        banner = BannerView(this); root.addView(banner, FrameLayout.LayoutParams(MATCH, MATCH))
        grid = GridOverlay(this); root.addView(grid, FrameLayout.LayoutParams(MATCH, MATCH))
        guide = GuideOverlay(this); root.addView(guide, FrameLayout.LayoutParams(MATCH, MATCH))
        options = OptionsOverlay(this); root.addView(options, FrameLayout.LayoutParams(MATCH, MATCH))
        setup = SetupOverlay(this); root.addView(setup, FrameLayout.LayoutParams(MATCH, MATCH))
        weather = WeatherOverlay(this); root.addView(weather, FrameLayout.LayoutParams(MATCH, MATCH))
        voice = VoiceOverlay(this); root.addView(voice, FrameLayout.LayoutParams(MATCH, MATCH))

        // Clock while watching (the channel list has its own)
        clockView.background = rounded(0x99030725.toInt(), dp(12).toFloat())
        clockView.setPadding(dp(14), dp(6), dp(14), dp(6))
        root.addView(clockView, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(22), dp(28), 0) })

        statusView.background = rounded(Col.CARD, dp(10).toFloat())
        statusView.setPadding(dp(14), dp(8), dp(14), dp(8))
        statusView.visibility = View.GONE
        root.addView(statusView, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START).apply { setMargins(dp(28), dp(22), 0, 0) })
        numberView.background = rounded(Col.CARD, dp(12).toFloat())
        numberView.setPadding(dp(18), dp(8), dp(18), dp(8))
        numberView.visibility = View.GONE
        root.addView(numberView, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(22), dp(28), 0) })

        setContentView(root)

        app.onSourceSaved = { onSourceSaved() }
        app.onWeatherChanged = { grid.refreshItems() }
        clockView.post(clockTick)
        App.syncAutoStartService(this)
        startup()
    }

    override fun onStart() {
        super.onStart()
        grid.applySettings()
        syncClock()
        if (app.gridDirty) {
            app.gridDirty = false
            repo.rebuild()
            if (repo.channels.isNotEmpty()) {
                if (mode == Mode.SETUP) hideSetup()
                grid.reload()
                current = current?.let { repo.byKey(it.key) } ?: current
            } else if (!repo.hasPlaylists()) showSetup(null)
            if (repo.missingCache()) backgroundRefresh()
        }
        if (started && !player.isActive) player.resume()
    }

    override fun onStop() {
        // Free the decoder and network while another app (or Settings) is in front.
        player.release()
        voice.close()
        super.onStop()
    }

    override fun onDestroy() {
        app.onSourceSaved = null
        app.onWeatherChanged = null
        player.release()
        statusView.removeCallbacks(hourly)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- startup & data

    private fun startup() {
        // Fastest path: start the last stream immediately, before the playlist is even read.
        if (prefs.openWithLast && prefs.lastUrl.isNotEmpty() && prefs.sourceType != "stalker") {
            player.play(prefs.lastUrl)
            started = true
        }
        repo.io.execute {
            repo.loadCached()
            app.main.post { afterCache() }
        }
    }

    private fun afterCache() {
        repo.weather.refreshAll { grid.refreshItems() }
        if (repo.channels.isEmpty()) {
            if (!repo.hasPlaylists()) {
                showSetup(null)
            } else {
                showSetup("Loading channels…")
                loadNow()
            }
            return
        }
        grid.reload()
        current = repo.byKey(prefs.lastChannelKey)
        val c = current
        if (prefs.openWithLast && c != null) {
            if (!started) playChannel(c) else if (prefs.bannerSec > 0) banner.show(c)
        } else {
            showGrid()
        }
        if (repo.refreshDue()) backgroundRefresh()
        statusView.removeCallbacks(hourly)
        statusView.postDelayed(hourly, 3600_000L)
    }

    /** Silent refresh: playback continues, the grid updates when done. */
    private fun backgroundRefresh() {
        repo.refresh(onList = { _ ->
            if (repo.channels.isNotEmpty()) {
                if (mode == Mode.SETUP) hideSetup()
                grid.reload()
                current = current?.let { repo.byKey(it.key) } ?: current
            }
        }, onEpg = { grid.refreshEpg() })
    }

    /** First load or a new source from the phone page. */
    private fun loadNow() {
        repo.refresh(onList = { err ->
            if (repo.channels.isEmpty()) {
                showSetup(if (repo.hasPlaylists()) "Couldn't load channels: ${err ?: "no channels"}" else null)
            } else {
                if (err != null) status("Some playlists failed: $err")
                hideSetup()
                grid.reload()
                val first = repo.byKey(prefs.lastChannelKey) ?: repo.channelsFor(grid.group).firstOrNull() ?: repo.channels.first()
                playChannel(first, showBanner = false)
                showGrid()
            }
        }, onEpg = { grid.refreshEpg() })
    }

    private fun onSourceSaved() {
        showSetup("Loading channels…")
        loadNow()
    }

    fun refreshFromSettings() = backgroundRefresh()

    // ---------------------------------------------------------------- playback

    fun playChannel(ch: Channel, showBanner: Boolean = true) {
        val cur = current
        if (cur != null && cur.key != ch.key) prefs.prevChannelKey = cur.key
        current = ch
        prefs.lastChannelKey = ch.key
        prefs.addRecent(ch.key)
        started = true
        if (ch.cmd.isNotEmpty()) {
            prefs.lastUrl = ""
            status("Connecting…", sticky = true)
            links.execute {
                val u = runCatching { repo.resolve(ch) }.getOrNull()
                app.main.post {
                    if (current === ch) {
                        if (u.isNullOrEmpty()) status("Couldn't open this channel") else { clearStatus(); player.play(u) }
                    }
                }
            }
        } else {
            prefs.lastUrl = ch.url
            player.play(ch.url)
        }
        if (showBanner && prefs.bannerSec > 0) banner.show(ch)
    }

    private fun zapList(): List<Channel> {
        val c = current
        // Zapping inside "Recent" would reshuffle as you go, so use the channel's own group there.
        if (c != null && grid.group == Repo.RECENT) return repo.channelsFor(c.group).ifEmpty { repo.channelsFor(Repo.ALL) }
        val g = grid.items
        if (c != null && g.any { it.key == c.key }) return g
        return repo.channelsFor(Repo.ALL)
    }

    fun currentChannel(): Channel? = current

    /** Up/Down zapping: the banner follows instantly, the stream starts when you stop pressing. */
    private fun zap(step: Int) {
        val list = zapList()
        if (list.isEmpty()) return
        val from = zapTarget ?: current
        val i = from?.let { f -> list.indexOfFirst { it.key == f.key } } ?: -1
        val next = list[((if (i < 0) 0 else i + step) % list.size + list.size) % list.size]
        zapTarget = next
        banner.show(next)
        statusView.removeCallbacks(commitZap)
        statusView.postDelayed(commitZap, 350)
    }

    private fun prevChannel() {
        val p = repo.byKey(prefs.prevChannelKey) ?: run { status("No previous channel yet"); return }
        closeOverlays()
        playChannel(p)
    }

    private fun goToNumber() {
        val n = numBuf.toIntOrNull()
        numBuf = ""
        numberView.visibility = View.GONE
        if (n == null) return
        val ch = repo.byNumber(n)
        if (ch == null) status("No channel $n") else { closeOverlays(); playChannel(ch) }
    }

    fun playFromGrid(ch: Channel) {
        grid.close(); mode = Mode.PLAYER
        // OK on the channel already playing reloads it (handy if the picture looks stuck).
        playChannel(ch)
    }

    fun playFromGuide(ch: Channel) {
        guide.close(); grid.close(); mode = Mode.PLAYER
        if (current?.key != ch.key || !player.isActive) playChannel(ch)
    }

    fun playFromVoice(ch: Channel) {
        closeOverlays()
        playChannel(ch)
    }

    // ---------------------------------------------------------------- overlays

    private fun closeOverlays() {
        grid.close(); guide.close(); options.close(); weather.close()
        if (mode != Mode.SETUP) mode = Mode.PLAYER
    }

    fun showGrid() {
        banner.hideNow()
        guide.close(); options.close()
        grid.open(current)
        mode = Mode.GRID
    }

    private fun closeGrid() {
        if (!started) { confirmExit(); return }
        grid.close()
        mode = Mode.PLAYER
    }

    fun showGuide() {
        if (repo.channels.isEmpty()) return
        guideReturn = if (mode == Mode.GRID) Mode.GRID else Mode.PLAYER
        banner.hideNow()
        val list = grid.items.ifEmpty { repo.channelsFor(Repo.ALL) }
        guide.open(list, current)
        mode = Mode.GUIDE
    }

    private fun closeGuide() {
        guide.close()
        if (guideReturn == Mode.GRID) { grid.open(current); mode = Mode.GRID } else mode = Mode.PLAYER
    }

    fun openWeather(index: Int) {
        grid.close()
        weather.open(index)
        mode = Mode.WEATHER
    }

    private fun closeWeather() {
        weather.close()
        showGrid()
    }

    private fun syncClock() {
        val show = prefs.showClock && (mode == Mode.PLAYER || mode == Mode.OPTIONS)
        clockView.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun showOptions() {
        banner.hideNow()
        options.open()
        mode = Mode.OPTIONS
    }

    private fun showSetup(msg: String?) {
        grid.close(); guide.close(); options.close(); weather.close()
        setup.show(msg)
        mode = Mode.SETUP
    }

    private fun hideSetup() {
        setup.close()
        mode = Mode.PLAYER
    }

    fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun confirmExit() {
        val now = System.currentTimeMillis()
        if (now - lastBack < 2000) finish()
        else { lastBack = now; status("Press Back again to exit") }
    }

    fun status(msg: String, sticky: Boolean = false) {
        statusView.text = msg
        statusView.visibility = View.VISIBLE
        statusView.removeCallbacks(hideStatus)
        if (!sticky) statusView.postDelayed(hideStatus, 2500)
    }

    private fun clearStatus() {
        statusView.removeCallbacks(hideStatus)
        statusView.visibility = View.GONE
    }

    // ---------------------------------------------------------------- actions

    fun doAction(a: Action) {
        when (a) {
            Action.GUIDE -> if (mode == Mode.GUIDE) closeGuide() else showGuide()
            Action.FAVOURITE -> {
                val target = (if (mode == Mode.GRID) grid.focusedChannel() else null) ?: current ?: return
                val added = repo.toggleFav(target)
                status(if (added) "Added to favourites: ${target.name}" else "Removed from favourites: ${target.name}")
                grid.onFavouritesChanged()
            }
            Action.VOICE -> if (prefs.voiceEnabled) voice.start() else status("Voice command is off in Settings")
            Action.PREV_CHANNEL -> prevChannel()
            Action.GRID -> if (mode == Mode.GRID) closeGrid() else showGrid()
            Action.SETTINGS -> openSettings()
            Action.OPTIONS -> showOptions()
        }
    }

    // ---------------------------------------------------------------- keys

    private fun isPassThrough(code: Int) = code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN ||
        code == KeyEvent.KEYCODE_VOLUME_MUTE || code == KeyEvent.KEYCODE_MUTE || code == KeyEvent.KEYCODE_POWER ||
        code == KeyEvent.KEYCODE_HOME || code == KeyEvent.KEYCODE_TV_POWER

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val code = e.keyCode
        if (isPassThrough(code)) return super.dispatchKeyEvent(e)
        val down = e.action == KeyEvent.ACTION_DOWN
        val first = down && e.repeatCount == 0

        if (voice.visibility == View.VISIBLE) {
            if (code == KeyEvent.KEYCODE_BACK && first) voice.close()
            return true
        }

        when (mode) {
            Mode.SETUP -> {
                if (code == KeyEvent.KEYCODE_BACK) {
                    if (first) { if (repo.channels.isNotEmpty() && started) hideSetup() else confirmExit() }
                    return true
                }
                if (code == KeyEvent.KEYCODE_MENU || KeyMap.actionFor(prefs, code) == Action.SETTINGS) {
                    if (first) openSettings(); return true
                }
                return super.dispatchKeyEvent(e)
            }
            Mode.GRID -> {
                if (code == KeyEvent.KEYCODE_BACK) { if (first) closeGrid(); return true }
                if (code == KeyEvent.KEYCODE_GUIDE) { if (first) showGuide(); return true }
                if (code == KeyEvent.KEYCODE_MENU) { if (first) openSettings(); return true }
                val a = KeyMap.actionFor(prefs, code)
                if (a != null) { if (first) doAction(a); return true }
                return super.dispatchKeyEvent(e)
            }
            Mode.WEATHER -> {
                when (code) {
                    KeyEvent.KEYCODE_BACK -> { if (first) closeWeather() }
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> { if (down) weather.changeCity(1) }
                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> { if (down) weather.changeCity(-1) }
                    KeyEvent.KEYCODE_DPAD_LEFT -> { if (down) weather.changeDay(-1) }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { if (down) weather.changeDay(1) }
                    else -> return super.dispatchKeyEvent(e)
                }
                return true
            }
            Mode.GUIDE -> {
                if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_GUIDE) { if (first) closeGuide(); return true }
                if (code == KeyEvent.KEYCODE_DPAD_LEFT) { if (down) guide.moveCell(-1); return true }
                if (code == KeyEvent.KEYCODE_DPAD_RIGHT) { if (down) guide.moveCell(1); return true }
                if (KeyMap.actionFor(prefs, code) == Action.GUIDE) { if (first) closeGuide(); return true }
                return super.dispatchKeyEvent(e)
            }
            Mode.OPTIONS -> {
                if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_DPAD_LEFT) {
                    if (first) { options.close(); mode = Mode.PLAYER }
                    return true
                }
                return super.dispatchKeyEvent(e)
            }
            Mode.PLAYER -> return playerKey(e)
        }
    }

    private fun playerKey(e: KeyEvent): Boolean {
        val code = e.keyCode
        val down = e.action == KeyEvent.ACTION_DOWN
        val up = e.action == KeyEvent.ACTION_UP
        val first = down && e.repeatCount == 0
        when (code) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                val voiceOnHold = prefs.voiceEnabled && prefs.keyFor(Action.VOICE) == KeyMap.LONG_OK
                if (down) {
                    if (e.repeatCount == 0) okFired = false
                    else if (!okFired && voiceOnHold && e.eventTime - e.downTime >= 600) {
                        okFired = true
                        doAction(Action.VOICE)
                    }
                } else if (up) {
                    if (!okFired) {
                        if (numBuf.isNotEmpty()) { statusView.removeCallbacks(commitNumber); goToNumber() } else showGrid()
                    }
                    okFired = false
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                if (down) zap(+1); return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                if (down) zap(-1); return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> { if (first) showGrid(); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { if (first) showOptions(); return true }
            KeyEvent.KEYCODE_INFO -> { if (first) current?.let { banner.show(it) }; return true }
            KeyEvent.KEYCODE_GUIDE -> { if (first) showGuide(); return true }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> { if (first) openSettings(); return true }
            KeyEvent.KEYCODE_BACK -> {
                if (first) {
                    if (banner.visibility == View.VISIBLE) banner.hideNow()
                    else if (numBuf.isNotEmpty()) { numBuf = ""; numberView.visibility = View.GONE }
                    else confirmExit()
                }
                return true
            }
        }
        if (code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            if (first) {
                if (code == KeyEvent.KEYCODE_0 && numBuf.isEmpty() && prefs.keyFor(Action.PREV_CHANNEL) == KeyEvent.KEYCODE_0) {
                    prevChannel()
                } else {
                    numBuf += (code - KeyEvent.KEYCODE_0).toString()
                    numberView.text = numBuf + "_"
                    numberView.visibility = View.VISIBLE
                    statusView.removeCallbacks(commitNumber)
                    if (numBuf.length >= 4) goToNumber() else statusView.postDelayed(commitNumber, 1500)
                }
            }
            return true
        }
        val a = KeyMap.actionFor(prefs, code)
        if (a != null) { if (first) doAction(a); return true }
        return super.dispatchKeyEvent(e)
    }

    // ---------------------------------------------------------------- player callbacks

    override fun onBuffering(buffering: Boolean) {
        statusView.removeCallbacks(bufferingHint)
        if (buffering) statusView.postDelayed(bufferingHint, 900)
        else if (statusView.text == "Loading…") clearStatus()
    }

    private val playerMessages = setOf(
        "Loading…", PlayerController.MSG_RECONNECT, PlayerController.MSG_SLOW, PlayerController.MSG_DEAD
    )

    override fun onProblem(message: String?) {
        if (message == null) {
            if (statusView.text.toString() in playerMessages) clearStatus()
        } else status(message, sticky = true)
    }

    override fun onNeedFreshUrl(): Boolean {
        val ch = current ?: return false
        if (ch.cmd.isEmpty()) return false
        links.execute {
            val u = runCatching { repo.resolve(ch) }.getOrNull()
            app.main.post { if (current === ch && !u.isNullOrEmpty()) player.play(u) }
        }
        return true
    }

    // ---------------------------------------------------------------- voice plumbing

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) voice.start()
            else status("Microphone permission is needed for voice command")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VOICE && resultCode == RESULT_OK) {
            voice.handle(data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS) ?: arrayListOf())
        }
    }
}
