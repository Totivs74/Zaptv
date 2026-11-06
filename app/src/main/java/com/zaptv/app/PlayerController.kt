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
    }

    class TrackRef(val group: Tracks.Group, val index: Int, val label: String, val selected: Boolean)

    companion object {
        const val AUDIO = C.TRACK_TYPE_AUDIO
        const val TEXT = C.TRACK_TYPE_TEXT
    }

    var listener: Listener? = null
    private var player: ExoPlayer? = null
    private var url: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private var retries = 0
    private val retry = Runnable { reconnect() }

    val isActive: Boolean get() = player != null

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
        val p = ExoPlayer.Builder(ctx, renderers)
            .setLoadControl(load)
            .setMediaSourceFactory(sources)
            .build()
        val b = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !prefs.subtitles)
        if (prefs.audioLang.isNotEmpty()) b.setPreferredAudioLanguage(prefs.audioLang)
        p.trackSelectionParameters = b.build()
        p.setVideoSurfaceView(frame.surface)
        p.playWhenReady = true
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                listener?.onBuffering(state == Player.STATE_BUFFERING)
                if (state == Player.STATE_READY) {
                    retries = 0
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
                else listener?.onProblem("Can't play this channel")
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
        retries = 0
        url = u
        subs.text = ""
        val p = ensure()
        val item = MediaItem.Builder().setUri(u)
        val lower = u.lowercase(Locale.ROOT)
        if (lower.contains(".m3u8") || lower.contains("m3u8")) item.setMimeType(MimeTypes.APPLICATION_M3U8)
        p.setMediaItem(item.build())
        p.prepare()
        p.play()
    }

    private fun scheduleRetry() {
        if (url == null) return
        val delay = when (retries) { 0 -> 800L; 1 -> 1500L; 2 -> 3000L; else -> 5000L }
        retries++
        listener?.onProblem("Reconnecting…")
        handler.removeCallbacks(retry)
        handler.postDelayed(retry, delay)
    }

    private fun reconnect() {
        val p = player ?: return
        val u = url ?: return
        if (p.currentMediaItem == null) p.setMediaItem(MediaItem.fromUri(u))
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
        player?.stop()
    }

    fun release() {
        handler.removeCallbacks(retry)
        player?.release()
        player = null
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
