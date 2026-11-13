package com.zaptv.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * ZapTV for tablets. Same player, playlists, guide, favourites and weather as the TV build,
 * with touch screens: swipe the picture to zap, tap for controls, double-tap for the previous channel.
 * Portrait: video on top, channel list below. Landscape: full-screen video, panels slide over it.
 */
class TabletActivity : Activity(), PlayerController.Listener {
    val app: App get() = application as App
    private val repo get() = app.repo
    private val prefs get() = app.prefs
    private val tr get() = TrVpn.get(this)
    companion object { const val REQ_VPN = 9 }

    lateinit var player: PlayerController
    private lateinit var body: LinearLayout
    private lateinit var videoBox: VideoBox
    private lateinit var frame: VideoFrame
    private lateinit var dock: FrameLayout
    private lateinit var list: TChannelList
    private lateinit var banner: TBanner
    private lateinit var controls: LinearLayout
    private lateinit var channelsBtn: TextView
    private lateinit var favBtn: TextView
    private lateinit var channelsSheet: Sheet
    private lateinit var guideSheet: Sheet
    private lateinit var optionsSheet: Sheet
    private lateinit var guide: TGuide
    private lateinit var options: TOptions
    private lateinit var weather: TWeather
    private lateinit var setup: TSetup
    private val clockView by lazy { label("", 16f, Col.TEXT, true) }
    private val statusView by lazy { label("", 14f, Col.TEXT, true) }

    private var landscape = false
    /** True between onStart and onStop. */
    private var visible = false
    /** A playlist was saved on the ZapTV page while we were in the background (e.g. in the browser). */
    private var sourcePending = false
    private var current: Channel? = null
    private var started = false
    private var lastBack = 0L
    private var zapTarget: Channel? = null
    /** +1 when zapping up the list, -1 when zapping down. Preloading leans this way. */
    private var zapDir = 1
    private val links = Executors.newSingleThreadExecutor()

    private val hideStatus = Runnable { statusView.visibility = View.GONE }
    private val hideControls = Runnable { setControls(false) }
    private val commitZap = Runnable { zapTarget?.let { playChannel(it) }; zapTarget = null }
    private val bufferingHint = Runnable { status("Loading…", sticky = true) }
    private val clockTick = object : Runnable {
        override fun run() {
            clockView.text = Fmt.time(System.currentTimeMillis())
            clockView.postDelayed(this, 20_000)
        }
    }
    private val hourly = object : Runnable {
        override fun run() {
            if (repo.refreshDue() || repo.missingCache()) backgroundRefresh()
            statusView.postDelayed(this, 3600_000L)
        }
    }

    /** Video area: in portrait it keeps a 16:9 height, in landscape it fills the screen. */
    class VideoBox(ctx: Context) : FrameLayout(ctx) {
        var ratio = false
            set(v) { field = v; requestLayout() }
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            if (ratio) {
                val w = MeasureSpec.getSize(widthMeasureSpec)
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(w * 9 / 16, MeasureSpec.EXACTLY))
            } else super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this)
        root.setBackgroundColor(Col.BG)

        body = vbox()
        videoBox = VideoBox(this)
        videoBox.setBackgroundColor(0xFF000000.toInt())
        frame = VideoFrame(this)
        videoBox.addView(frame, FrameLayout.LayoutParams(MATCH, MATCH))

        val subs = label("", 18f, Col.TEXT, true).apply {
            gravity = Gravity.CENTER
            setShadowLayer(6f, 0f, 2f, 0xFF000000.toInt())
        }
        videoBox.addView(subs, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { setMargins(dp(24), 0, dp(24), dp(24)) })

        player = PlayerController(this, frame, subs, prefs)
        player.listener = this

        // Touch layer over the picture: swipe to zap, tap for controls, double-tap for previous channel.
        val touch = View(this)
        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { toggleControls(); return true }
            override fun onDoubleTap(e: MotionEvent): Boolean { prevChannel(); return true }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val s = e1 ?: return false
                val dx = e2.x - s.x
                val dy = e2.y - s.y
                if (abs(dy) > dp(50) && abs(dy) > 1.5f * abs(dx) && abs(vy) > 300) {
                    zap(if (dy < 0) +1 else -1)   // swipe up = next channel
                    return true
                }
                return false
            }
        })
        touch.setOnTouchListener { _, e -> gestures.onTouchEvent(e) }
        videoBox.addView(touch, FrameLayout.LayoutParams(MATCH, MATCH))

        // Banner sits just above the control bar.
        banner = TBanner(this)
        videoBox.addView(banner, FrameLayout.LayoutParams(MATCH, MATCH).apply { bottomMargin = dp(64) })
        controls = buildControls()
        videoBox.addView(controls, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))

        clockView.background = rounded(0x99030725.toInt(), dp(10).toFloat())
        clockView.setPadding(dp(12), dp(5), dp(12), dp(5))
        videoBox.addView(clockView, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(14), dp(16), 0) })
        statusView.background = rounded(Col.CARD, dp(10).toFloat())
        statusView.setPadding(dp(12), dp(7), dp(12), dp(7))
        statusView.visibility = View.GONE
        videoBox.addView(statusView, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START).apply { setMargins(dp(16), dp(14), dp(90), 0) })

        body.addView(videoBox, lp(MATCH, WRAP))
        dock = FrameLayout(this)
        body.addView(dock, lp(MATCH, 0, 1f))
        root.addView(body, FrameLayout.LayoutParams(MATCH, MATCH))

        list = TChannelList(this)

        channelsSheet = Sheet(this, Sheet.LEFT)
        channelsSheet.noSwipe.add(list.tabsScroll)
        root.addView(channelsSheet, FrameLayout.LayoutParams(MATCH, MATCH))

        guide = TGuide(this)
        guideSheet = Sheet(this, Sheet.BOTTOM)
        guideSheet.panel.addView(guide, FrameLayout.LayoutParams(MATCH, MATCH))
        guideSheet.handle = guide.header
        root.addView(guideSheet, FrameLayout.LayoutParams(MATCH, MATCH))

        options = TOptions(this)
        optionsSheet = Sheet(this, Sheet.RIGHT)
        optionsSheet.panel.addView(options, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(optionsSheet, FrameLayout.LayoutParams(MATCH, MATCH))

        weather = TWeather(this)
        root.addView(weather, FrameLayout.LayoutParams(MATCH, MATCH))
        setup = TSetup(this)
        root.addView(setup, FrameLayout.LayoutParams(MATCH, MATCH))

        setContentView(root)
        applyLayout()

        app.onSourceSaved = { onSourceSaved() }
        app.onWeatherChanged = { list.refreshItems() }
        clockView.post(clockTick)
        syncClock()
        startup()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyLayout()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applySystemBars()
    }

    override fun onStart() {
        super.onStart()
        visible = true
        syncClock()
        if (sourcePending) {
            sourcePending = false
            app.gridDirty = false
            onSourceSaved()
            return
        }
        if (app.gridDirty) {
            app.gridDirty = false
            repo.rebuild()
            if (repo.channels.isNotEmpty()) {
                if (setup.isOpen) setup.close()
                list.reload()
                current = current?.let { repo.byKey(it.key) } ?: current
                list.markPlaying(current, scroll = false)
            } else if (!repo.hasPlaylists()) showSetup(null)
            if (repo.missingCache()) backgroundRefresh()
        }
        if (started && !player.isActive) routeFor(current) { player.resume() }
    }

    override fun onStop() {
        // Free the decoder and network while another app (or Settings) is in front.
        visible = false
        player.release()
        // Close the Turkey connection while ZapTV is not on screen; it reopens on the next TR channel.
        if (tr.isUp || tr.desired) tr.ensure(false) { _, _ -> }
        super.onStop()
    }

    override fun onDestroy() {
        app.onSourceSaved = null
        app.onWeatherChanged = null
        player.release()
        statusView.removeCallbacks(hourly)
        clockView.removeCallbacks(clockTick)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- layout

    /** Portrait: video on top, list docked below. Landscape: full-screen video, list in a slide-over panel. */
    private fun applyLayout() {
        landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        (list.parent as? android.view.ViewGroup)?.removeView(list)
        if (landscape) {
            videoBox.ratio = false
            videoBox.layoutParams = lp(MATCH, 0, 1f)
            dock.visibility = View.GONE
            channelsSheet.panel.addView(list, FrameLayout.LayoutParams(MATCH, MATCH))
            channelsBtn.visibility = View.VISIBLE
        } else {
            channelsSheet.closeNow()
            videoBox.ratio = true
            videoBox.layoutParams = lp(MATCH, WRAP)
            dock.visibility = View.VISIBLE
            dock.addView(list, FrameLayout.LayoutParams(MATCH, MATCH))
            channelsBtn.visibility = View.GONE
        }
        val dm = resources.displayMetrics
        channelsSheet.size(minOf(dp(440), (dm.widthPixels * 0.62f).toInt()))
        optionsSheet.size(minOf(dp(380), (dm.widthPixels * 0.8f).toInt()))
        guideSheet.size((dm.heightPixels * (if (landscape) 0.9f else 0.8f)).toInt())
        list.markPlaying(current)
        applySystemBars()
    }

    @Suppress("DEPRECATION")
    private fun applySystemBars() {
        val d = window.decorView
        d.systemUiVisibility = if (landscape) {
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        } else 0
    }

    private fun buildControls(): LinearLayout {
        val bar = hbox()
        bar.background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xE6030725.toInt(), 0x00030725))
        bar.setPadding(dp(10), dp(18), dp(10), dp(10))
        bar.gravity = Gravity.CENTER
        fun btn(text: String, onClick: () -> Unit): TextView {
            val b = label(text, 15f, Col.TEXT, true)
            b.setPadding(dp(16), dp(10), dp(16), dp(10))
            b.background = rounded(0xB314205C.toInt(), dp(999).toFloat())
            b.setOnClickListener { onClick() }
            bar.addView(b, lp(WRAP, WRAP).apply { leftMargin = dp(5); rightMargin = dp(5) })
            return b
        }
        channelsBtn = btn("☰  Channels") { showChannels() }
        btn("Guide") { showGuide() }
        btn("Options") { showOptions() }
        favBtn = btn("☆") { toggleFavourite() }
        btn("⚙") { openSettings() }
        bar.visibility = View.GONE
        return bar
    }

    // ---------------------------------------------------------------- startup & data

    private fun startup() {
        // Fastest path: start the last stream immediately, before the playlist is even read.
        if (prefs.openWithLast && prefs.lastUrl.isNotEmpty() && prefs.sourceType != "stalker" && !tr.needs(prefs.lastChannelKey)) {
            player.play(prefs.lastUrl)
            started = true
        }
        repo.io.execute {
            repo.loadCached()
            app.main.post { afterCache() }
        }
    }

    private fun afterCache() {
        repo.weather.refreshAll { list.refreshItems() }
        if (repo.channels.isEmpty()) {
            if (!repo.hasPlaylists()) showSetup(null)
            else { showSetup("Loading channels…"); loadNow() }
            return
        }
        list.reload()
        current = repo.byKey(prefs.lastChannelKey)
        list.markPlaying(current)
        val c = current
        if (prefs.openWithLast && c != null) {
            if (!started) playChannel(c) else if (prefs.bannerSec > 0) banner.show(c)
        } else if (landscape) {
            showChannels()
        }
        if (repo.refreshDue()) backgroundRefresh()
        statusView.removeCallbacks(hourly)
        statusView.postDelayed(hourly, 3600_000L)
    }

    /** Silent refresh: playback continues, the list updates when done. */
    private fun backgroundRefresh() {
        repo.refresh(onList = { _ ->
            if (repo.channels.isNotEmpty()) {
                if (setup.isOpen) setup.close()
                list.reload()
                current = current?.let { repo.byKey(it.key) } ?: current
                list.markPlaying(current, scroll = false)
            }
        }, onEpg = { list.refreshEpg() })
    }

    /** First load, or a new playlist saved on the ZapTV page. */
    private fun loadNow() {
        repo.refresh(onList = { err ->
            if (repo.channels.isEmpty()) {
                showSetup(if (repo.hasPlaylists()) "Couldn't load channels: ${err ?: "no channels"}" else null)
            } else {
                if (err != null) status("Some playlists failed: $err")
                setup.close()
                list.reload()
                val first = repo.byKey(prefs.lastChannelKey) ?: list.items.firstOrNull { !it.isWeather } ?: repo.channels.first()
                playChannel(first, showBanner = false)
                if (landscape) showChannels()
            }
        }, onEpg = { list.refreshEpg() })
    }

    private fun onSourceSaved() {
        // Saved from the browser on this tablet: load when ZapTV is back in front, so nothing plays in the background.
        if (!visible) { sourcePending = true; return }
        showSetup("Loading channels…")
        loadNow()
    }

    // ---------------------------------------------------------------- playback

    fun playChannel(ch: Channel, showBanner: Boolean = true) {
        if (ch.isApp) return
        if (ch.isWeather) { openWeather(ch.url.removePrefix("weather:").toIntOrNull() ?: 0); return }
        val cur = current
        if (cur != null && cur.key != ch.key) prefs.prevChannelKey = cur.key
        current = ch
        prefs.lastChannelKey = ch.key
        prefs.addRecent(ch.key)
        started = true
        routeFor(ch) { startStream(ch) }
        if (showBanner && prefs.bannerSec > 0) banner.show(ch, sticky = controls.visibility == View.VISIBLE)
        list.markPlaying(ch, scroll = !landscape || !channelsSheet.isOpen)
        if (list.group == Repo.RECENT) list.refreshItems()
        updateFavButton()
        updatePreload(ch)
    }

    private fun startStream(ch: Channel) {
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
    }

    // ---------------------------------------------------------------- Turkey connection

    private var pendingTr: Channel? = null

    /**
     * Opens the Turkey connection for TR channels and closes it for all others, then runs [then].
     * Channels without TR never wait: when the connection is already closed nothing happens.
     */
    private fun routeFor(ch: Channel?, then: () -> Unit) {
        val want = ch != null && tr.needs(ch.key)
        if (!want && !tr.isUp && !tr.desired) { then(); return }
        if (want && tr.isUp && tr.desired) { then(); return }
        if (want) {
            val ask = tr.permissionIntent()
            if (ask != null) {
                pendingTr = ch
                status("Allow the Turkey connection on the next screen", sticky = true)
                runCatching { startActivityForResult(ask, REQ_VPN) }.onFailure { status("This tablet can't open the VPN permission screen") }
                return
            }
            status("Connecting via Turkey…", sticky = true)
        }
        player.stop()
        tr.ensure(want) { ok, err ->
            app.main.post {
                if (current !== ch) return@post
                if (want && !ok) status("Turkey connection failed${err?.let { ": $it" } ?: ""}") else clearStatus()
                then()
            }
        }
    }

    /** Options > Turkey connection: marks or unmarks the current channel and replays it. */
    fun toggleTrForCurrent() {
        val ch = current ?: return
        if (!tr.configured) { openTurkeyFile(); return }
        tr.toggle(ch.key)
        status(if (tr.isTr(ch.key)) "${ch.name}: Turkey connection on" else "${ch.name}: Turkey connection off")
        list.refreshItems()
        playChannel(ch, showBanner = false)
    }

    fun openTurkeyFile() {
        closeSheets()
        startActivity(Intent(this, TurkeyFileActivity::class.java))
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN) {
            val ch = pendingTr
            pendingTr = null
            if (resultCode == RESULT_OK && ch != null && current === ch) playChannel(ch, showBanner = false)
            else if (resultCode != RESULT_OK) status("Turkey connection was not allowed")
        }
    }

    /** Preloads neighbours (Settings > Player > Channels to preload); leans toward the zapping direction. */
    private fun updatePreload(ch: Channel) {
        val l = zapList()
        val i = l.indexOfFirst { it.key == ch.key }
        if (i < 0 || l.size < 2 || tr.needs(ch.key)) { player.setNeighbours(emptyList(), 0); return }
        val out = ArrayList<Pair<String, Int>>()
        val z = zapDir
        val offsets = when (prefs.preloadCount) {
            0 -> listOf(z, -z)
            2 -> listOf(z, -z, 2 * z, 3 * z)
            3 -> listOf(z, -z, 2 * z, 3 * z, 4 * z)
            else -> listOf(z, -z, 2 * z, -2 * z)
        }
        for (d in offsets) {
            val n = l[((i + d) % l.size + l.size) % l.size]
            if (n.key != ch.key && n.cmd.isEmpty() && n.url.isNotEmpty() && !n.isWeather && !tr.needs(n.key) && out.none { it.first == n.url }) out.add(n.url to i + d)
        }
        player.setNeighbours(out, i)
    }

    /** Channels you zap through: the tab you're on (without weather), or all channels. */
    private fun zapList(): List<Channel> {
        val c = current
        // Zapping inside "Recent" would reshuffle as you go, so use the channel's own group there.
        if (c != null && list.group == Repo.RECENT) return repo.channelsFor(c.group).ifEmpty { repo.channelsFor(Repo.ALL) }
        val g = list.items.filter { !it.isWeather && !it.isApp }
        if (c != null && g.any { it.key == c.key }) return g
        return repo.channelsFor(Repo.ALL)
    }

    fun currentChannel(): Channel? = current

    /** Swipe zapping: the banner follows at once, the stream starts when you stop swiping. */
    private fun zap(step: Int) {
        zapDir = if (step >= 0) 1 else -1
        val l = zapList()
        if (l.isEmpty()) return
        val from = zapTarget ?: current
        val i = from?.let { f -> l.indexOfFirst { it.key == f.key } } ?: -1
        val next = l[((if (i < 0) 0 else i + step) % l.size + l.size) % l.size]
        zapTarget = next
        banner.show(next, sticky = controls.visibility == View.VISIBLE)
        statusView.removeCallbacks(commitZap)
        statusView.postDelayed(commitZap, 350)
    }

    private fun prevChannel() {
        val p = repo.byKey(prefs.prevChannelKey) ?: run { status("No previous channel yet"); return }
        playChannel(p)
    }

    fun playFromList(ch: Channel) {
        if (landscape && !ch.isWeather) channelsSheet.close()
        // Tapping the channel already playing reloads it (handy if the picture looks stuck).
        playChannel(ch)
    }

    fun playFromGuide(ch: Channel) {
        guideSheet.close()
        if (current?.key != ch.key || !player.isActive) playChannel(ch)
    }

    fun reloadChannel() {
        optionsSheet.close()
        current?.let { playChannel(it) }
    }

    fun toggleFavourite() {
        val c = current ?: return
        val added = repo.toggleFav(c)
        status(if (added) "Added to favourites: ${c.name}" else "Removed from favourites: ${c.name}")
        list.refreshItems()
        updateFavButton()
        bumpControls()
    }

    private fun updateFavButton() {
        val c = current
        val fav = c != null && repo.isFav(c)
        favBtn.text = if (fav) "★" else "☆"
        favBtn.setTextColor(if (fav) 0xFFFFC53D.toInt() else Col.TEXT)
    }

    // ---------------------------------------------------------------- controls & panels

    private fun toggleControls() = setControls(controls.visibility != View.VISIBLE)

    private fun setControls(show: Boolean) {
        controls.removeCallbacks(hideControls)
        if (show) {
            updateFavButton()
            controls.visibility = View.VISIBLE
            current?.let { banner.show(it, sticky = true) }
            controls.postDelayed(hideControls, 5000)
        } else {
            controls.visibility = View.GONE
            banner.hideNow()
        }
    }

    /** Keeps the controls up a little longer after a tap on them. */
    private fun bumpControls() {
        if (controls.visibility != View.VISIBLE) return
        controls.removeCallbacks(hideControls)
        controls.postDelayed(hideControls, 5000)
    }

    private fun anySheetOpen() = channelsSheet.isOpen || guideSheet.isOpen || optionsSheet.isOpen

    private fun closeSheets() {
        channelsSheet.close(); guideSheet.close(); optionsSheet.close()
    }

    fun showChannels() {
        if (!landscape) return
        setControls(false)
        guideSheet.close(); optionsSheet.close()
        list.markPlaying(current)
        channelsSheet.open()
    }

    fun showGuide() {
        if (repo.channels.isEmpty()) return
        setControls(false)
        channelsSheet.close(); optionsSheet.close()
        val l = list.items.filter { !it.isWeather && !it.isApp }.ifEmpty { repo.channelsFor(Repo.ALL) }
        guide.open(l, current)
        guideSheet.open()
    }

    fun closeGuide() = guideSheet.close()

    fun showOptions() {
        setControls(false)
        channelsSheet.close(); guideSheet.close()
        options.refresh()
        optionsSheet.open()
    }

    fun closeOptions() = optionsSheet.close()

    fun openWeather(index: Int) {
        closeSheets()
        setControls(false)
        weather.open(index)
    }

    fun closeWeather() = weather.close()

    private fun showSetup(msg: String?) {
        closeSheets()
        weather.close()
        setup.show(msg)
    }

    fun openSettings() {
        closeSheets()
        setControls(false)
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun syncClock() {
        clockView.visibility = if (prefs.showClock) View.VISIBLE else View.GONE
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

    // ---------------------------------------------------------------- back

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            weather.isOpen -> weather.close()
            anySheetOpen() -> closeSheets()
            setup.isOpen && repo.channels.isNotEmpty() && started -> setup.close()
            controls.visibility == View.VISIBLE -> setControls(false)
            else -> {
                val now = System.currentTimeMillis()
                if (now - lastBack < 2000) finish()
                else { lastBack = now; status("Press Back again to exit") }
            }
        }
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
}
