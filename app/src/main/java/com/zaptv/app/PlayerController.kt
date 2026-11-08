package com.zaptv.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.source.preload.TargetPreloadStatusControl
import java.util.Locale

/** Wraps Media3 ExoPlayer: fast start, auto-reconnect, track switching. */
class PlayerController(
    private val ctx: Context,
    private val frame: VideoFrame,
    private val subs: TextView,
    private val prefs: Prefs
) {
    interface Listener {
        fun onBuffering(buffering: Boolean)
        fun onProblem(message: String?)
        /** Return true if the activity will fetch a fresh link and call play() itself (MAC portals). */
        fun onNeedFreshUrl(): Boolean = false
    }

    class TrackRef(val group: Tracks.Group, val index: Int, val label: String, val selected: Boolean)

    companion object {
        const val AUDIO = C.TRACK_TYPE_AUDIO
        const val TEXT = C.TRACK_TYPE_TEXT
        const val MSG_RECONNECT = "Reconnecting…"
        const val MSG_SLOW = "Channel not responding, trying again…"
        const val MSG_DEAD = "This channel isn't working right now"
        private const val START_TIMEOUT_MS = 6000L
        private const val FREEZE_MS = 6000L
        private const val STUCK_BUFFER_MS = 10000L
        private const val MAX_START_ATTEMPTS = 4
        /** Neighbours stay preloaded this long after the last channel change. */
        private const val PRELOAD_WINDOW_MS = 60_000L
        /** Live streams move on, so neighbours are re-fetched this often while the window is open. */
        private const val PRELOAD_REFRESH_MS = 10_000L
        /** Full mode: how much of each neighbour to download ahead. */
        private const val FULL_PRELOAD_MS = 3_000L
    }

    var listener: Listener? = null
    private var player: ExoPlayer? = null
    private var url: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private var retries = 0
    private val retry = Runnable { reconnect() }

    // Freeze / dead-channel detection
    private var everReady = false
    private var gaveUp = false
    private var lastPos = -1L
    private var lastMove = 0L
    private var bufferingSince = 0L
    private val startCheck = Runnable {
        if (!everReady && !gaveUp) {
            listener?.onProblem(MSG_SLOW)
            reconnect()
        }
    }
    private val watchdog = object : Runnable {
        override fun run() {
            checkFrozen()
            handler.postDelayed(this, 2000)
        }
    }

    val isActive: Boolean get() = player != null

    // ---------- Neighbour preloading (Settings > Playback > Preload next channels) ----------
    private var preloadManager: DefaultPreloadManager? = null
    private val preloaded = LinkedHashMap<String, MediaItem>()   // url -> item held by the preload manager
    private var inUseItem: MediaItem? = null                     // preloaded item the player is now playing
    /** Neighbour URLs with their position in the zap list (2 above, 2 below). */
    private var neighbours: List<Pair<String, Int>> = emptyList()
    private var neighbourIndex = 0
    private var preloadUntil = 0L
    private val preloadTick = object : Runnable {
        override fun run() {
            if (System.currentTimeMillis() >= preloadUntil || prefs.preload == 0) { clearPreload(); return }
            refreshNeighbours()
            handler.postDelayed(this, PRELOAD_REFRESH_MS)
        }
    }

    private fun build(): ExoPlayer {
        val (minMs, maxMs, playMs, rebufMs) = when (prefs.buffer) {
            0 -> listOf(2000, 20000, 500, 1000)
            2 -> listOf(15000, 50000, 2500, 5000)
            else -> listOf(5000, 30000, 1000, 2000)
        }
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(minMs, maxMs, playMs, rebufMs)
            .build()
        val renderers = DefaultRenderersFactory(ctx)
            .setExtensionRendererMode(
                if (prefs.ffmpegFirst) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
            )
            .setEnableDecoderFallback(true)
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(Http.UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10000)
            .setReadTimeoutMs(15000)
        val sources = DefaultMediaSourceFactory(ctx)
            .setDataSourceFactory(DefaultDataSource.Factory(ctx, http))
        // Light: neighbours' playlists are fetched and parsed. Full: their first seconds of video too.
        // Full mode downloads the first seconds only for the two closest neighbours; the outer two stay light.
        val target = TargetPreloadStatusControl<Int, DefaultPreloadManager.PreloadStatus> { rank ->
            if (prefs.preload >= 2 && kotlin.math.abs(rank - neighbourIndex) <= 1)
                DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(FULL_PRELOAD_MS)
            else DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_SOURCE_PREPARED
        }
        val pmBuilder = DefaultPreloadManager.Builder(ctx, target)
            .setMediaSourceFactory(sources)
            .setRenderersFactory(renderers)
            .setLoadControl(load)
        val p = pmBuilder.buildExoPlayer(ExoPlayer.Builder(ctx))
        preloadManager = pmBuilder.build()
        val b = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !prefs.subtitles)
        if (prefs.audioLang.isNotEmpty()) b.setPreferredAudioLanguage(prefs.audioLang)
        p.trackSelectionParameters = b.build()
        p.setVideoSurfaceView(frame.surface)
        p.playWhenReady = true
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                listener?.onBuffering(state == Player.STATE_BUFFERING)
                val now = System.currentTimeMillis()
                bufferingSince = if (state == Player.STATE_BUFFERING) (if (bufferingSince == 0L) now else bufferingSince) else 0L
                if (state == Player.STATE_READY) {
                    retries = 0
                    everReady = true
                    gaveUp = false
                    lastMove = now
                    handler.removeCallbacks(startCheck)
                    listener?.onProblem(null)
                } else if (state == Player.STATE_ENDED) {
                    scheduleRetry()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    p.seekToDefaultPosition()
                    p.prepare()
                    return
                }
                if (prefs.autoReconnect) scheduleRetry()
                else listener?.onProblem(MSG_DEAD)
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                frame.setVideoSize(videoSize.width, videoSize.height, videoSize.pixelWidthHeightRatio)
            }

            override fun onCues(cueGroup: CueGroup) {
                val t = cueGroup.cues.mapNotNull { it.text?.toString() }.joinToString("\n")
                subs.text = t
            }
        })
        frame.mode = prefs.resizeMode
        return p
    }

    private fun ensure(): ExoPlayer = player ?: build().also { player = it }

    fun play(u: String) {
        handler.removeCallbacks(retry)
        handler.removeCallbacks(startCheck)
        retries = 0
        everReady = false
        gaveUp = false
        lastPos = -1L
        lastMove = System.currentTimeMillis()
        bufferingSince = 0L
        url = u
        subs.text = ""
        val p = ensure()
        val item = itemFor(u)
        val previousInUse = inUseItem
        inUseItem = null
        // Reloading the same channel always starts a fresh source.
        val ready = if (prefs.preload > 0 && previousInUse?.mediaId != u) preloadManager?.getMediaSource(item) else null
        if (ready != null) {
            preloaded.remove(u)
            inUseItem = item
            p.setMediaSource(ready)
        } else {
            p.setMediaItem(item)
        }
        p.prepare()
        p.play()
        // A preloaded source is used once; drop the one we just left so it gets re-fetched fresh if needed.
        if (previousInUse != null) preloadManager?.remove(previousInUse)
        handler.postDelayed(startCheck, START_TIMEOUT_MS)
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, 2000)
    }

    private fun itemFor(u: String): MediaItem {
        val item = MediaItem.Builder().setUri(u).setMediaId(u)
        val lower = u.lowercase(Locale.ROOT)
        if (lower.contains("m3u8")) item.setMimeType(MimeTypes.APPLICATION_M3U8)
        return item.build()
    }

    /**
     * Called after each channel change with the previous and next channel in the zapping order
     * (null for channels that need a portal link first). Keeps them preloaded for one minute.
     */
    fun setNeighbours(list: List<Pair<String, Int>>, index: Int) {
        handler.removeCallbacks(preloadTick)
        if (prefs.preload == 0 || preloadManager == null || list.isEmpty()) { clearPreload(); return }
        neighbours = list
        neighbourIndex = index
        preloadUntil = System.currentTimeMillis() + PRELOAD_WINDOW_MS
        refreshNeighbours(fresh = false)
        handler.postDelayed(preloadTick, PRELOAD_REFRESH_MS)
    }

    /** Re-fetches the neighbours so a live channel starts close to live, not from an old preload. */
    private fun refreshNeighbours(fresh: Boolean = true) {
        val pm = preloadManager ?: return
        val want = LinkedHashMap<String, Int>()
        for ((u, rank) in neighbours) if (u != url && u !in want) want[u] = rank
        val iter = preloaded.entries.iterator()
        while (iter.hasNext()) {
            val e = iter.next()
            if (fresh || e.key !in want) { pm.remove(e.value); iter.remove() }
        }
        for ((u, rank) in want) {
            if (u in preloaded) continue
            val item = itemFor(u)
            pm.add(item, rank)
            preloaded[u] = item
        }
        pm.setCurrentPlayingIndex(neighbourIndex)
        pm.invalidate()
    }

    private fun clearPreload() {
        handler.removeCallbacks(preloadTick)
        val pm = preloadManager
        if (pm != null) for (item in preloaded.values) pm.remove(item)
        preloaded.clear()
    }

    private fun scheduleRetry() {
        if (url == null || gaveUp) return
        if (!everReady && retries >= MAX_START_ATTEMPTS) {
            giveUp()
            return
        }
        val delay = when (retries) { 0 -> 800L; 1 -> 1500L; 2 -> 3000L; else -> 5000L }
        retries++
        listener?.onProblem(if (everReady) MSG_RECONNECT else MSG_SLOW)
        handler.removeCallbacks(retry)
        handler.postDelayed(retry, delay)
    }

    private fun giveUp() {
        gaveUp = true
        handler.removeCallbacks(retry)
        handler.removeCallbacks(startCheck)
        player?.stop()
        listener?.onProblem(MSG_DEAD)
    }

    /** Picture not moving for 6 s, or stuck loading for 10 s: reload at the live point. */
    private fun checkFrozen() {
        val p = player ?: return
        if (gaveUp || url == null || !everReady || !p.playWhenReady) return
        val now = System.currentTimeMillis()
        if (p.playbackState == Player.STATE_READY) {
            val pos = p.currentPosition
            if (pos != lastPos) { lastPos = pos; lastMove = now; return }
            if (now - lastMove >= FREEZE_MS) {
                lastMove = now
                listener?.onProblem(MSG_RECONNECT)
                reconnect()
            }
        } else if (p.playbackState == Player.STATE_BUFFERING && bufferingSince > 0 && now - bufferingSince >= STUCK_BUFFER_MS) {
            bufferingSince = now
            listener?.onProblem(MSG_RECONNECT)
            reconnect()
        }
    }

    private fun reconnect() {
        val p = player ?: return
        val u = url ?: return
        // MAC portal links expire: let the activity fetch a fresh one after the first failed attempt.
        if (retries >= 2 && listener?.onNeedFreshUrl() == true) return
        lastPos = -1L
        lastMove = System.currentTimeMillis()
        p.stop()
        p.setMediaItem(p.currentMediaItem ?: MediaItem.fromUri(u))
        p.seekToDefaultPosition()
        p.prepare()
        p.play()
    }

    /** Re-applies settings (buffer, decoder, language) and resumes the current stream. */
    fun restart() {
        val u = url
        release()
        if (u != null) play(u)
    }

    fun stop() {
        handler.removeCallbacks(retry)
        handler.removeCallbacks(startCheck)
        handler.removeCallbacks(watchdog)
        player?.stop()
    }

    fun release() {
        handler.removeCallbacks(retry)
        handler.removeCallbacks(startCheck)
        handler.removeCallbacks(watchdog)
        handler.removeCallbacks(preloadTick)
        preloaded.clear()
        inUseItem = null
        player?.release()
        player = null
        preloadManager?.release()
        preloadManager = null
    }

    fun resume(): Boolean {
        val u = url ?: return false
        play(u)
        return true
    }

    // ---------- Tracks ----------

    private fun label(f: Format, n: Int): String {
        val lang = f.language?.let { l -> runCatching { Locale(l).getDisplayLanguage(Locale.UK) }.getOrNull()?.ifBlank { null } }
        val base = f.label ?: lang ?: "Track $n"
        val extra = when (f.sampleMimeType) {
            MimeTypes.AUDIO_AC3 -> " (Dolby)"
            MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> " (Dolby+)"
            MimeTypes.AUDIO_DTS -> " (DTS)"
            else -> ""
        }
        return base + extra
    }

    private fun tracks(type: Int): List<TrackRef> {
        val p = player ?: return emptyList()
        val out = ArrayList<TrackRef>()
        for (g in p.currentTracks.groups) {
            if (g.type != type) continue
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                out.add(TrackRef(g, i, label(g.getTrackFormat(i), out.size + 1), g.isTrackSelected(i)))
            }
        }
        return out
    }

    fun audioTracks() = tracks(C.TRACK_TYPE_AUDIO)
    fun textTracks() = tracks(C.TRACK_TYPE_TEXT)

    fun select(type: Int, ref: TrackRef?) {
        val p = player ?: return
        val b = p.trackSelectionParameters.buildUpon()
        if (ref == null) {
            b.setTrackTypeDisabled(type, true)
        } else {
            b.setTrackTypeDisabled(type, false)
            b.setOverrideForType(TrackSelectionOverride(ref.group.mediaTrackGroup, ref.index))
        }
        p.trackSelectionParameters = b.build()
    }

    fun setResize(mode: Int) {
        prefs.resizeMode = mode
        frame.mode = mode
    }
}
