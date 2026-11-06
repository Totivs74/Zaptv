package com.zaptv.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout

/** Press-to-talk channel switching. Interface in English, recognition in Turkish (tr-TR). */
class VoiceOverlay(private val act: MainActivity) : FrameLayout(act) {
    private val repo = (act.application as App).repo
    private val state = act.label("Listening in Turkish…", 16f, Col.MUTED)
    private val heard = act.label("", 28f, Col.TEXT, true)
    private val match = act.label("", 18f, Col.DARK, true)
    private val example = act.label("Say e.g. \"Kanal D aç\", \"TRT bir\", \"Show TV\"", 13f, Col.MUTED)
    private var recognizer: SpeechRecognizer? = null
    private val hideLater = Runnable { close() }

    init {
        setBackgroundColor(0x99000000.toInt())
        val card = act.vbox()
        card.gravity = Gravity.CENTER_HORIZONTAL
        card.background = rounded(Col.SURF, dp(22).toFloat(), dp(1), Col.LINE)
        card.setPadding(dp(32), dp(28), dp(32), dp(26))
        val mic = act.label("MIC", 14f, Col.DARK, true)
        mic.gravity = Gravity.CENTER
        mic.background = rounded(Col.ACCENT, dp(36).toFloat())
        card.addView(mic, lp(dp(72), dp(72)))
        card.addView(state, lp(WRAP, WRAP).apply { topMargin = dp(16) })
        heard.gravity = Gravity.CENTER
        card.addView(heard, lp(WRAP, WRAP).apply { topMargin = dp(10) })
        match.setPadding(dp(16), dp(10), dp(16), dp(10))
        match.background = rounded(Col.TEXT, dp(12).toFloat())
        match.visibility = View.GONE
        card.addView(match, lp(WRAP, WRAP).apply { topMargin = dp(16) })
        card.addView(example, lp(WRAP, WRAP).apply { topMargin = dp(16) })
        addView(card, LayoutParams(dp(480), WRAP, Gravity.CENTER))
        visibility = View.GONE
    }

    private fun intent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "tr-TR")
        putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, true)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        putExtra(RecognizerIntent.EXTRA_PROMPT, "Kanal adını söyleyin")
    }

    fun start() {
        removeCallbacks(hideLater)
        if (Build.VERSION.SDK_INT >= 23 && act.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            act.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MainActivity.REQ_MIC)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(act)) {
            // Fall back to the system voice screen.
            runCatching { act.startActivityForResult(intent(), MainActivity.REQ_VOICE) }
                .onFailure { act.status("Voice recognition is not available on this device") }
            return
        }
        visibility = View.VISIBLE
        state.text = "Listening in Turkish…"
        heard.text = ""
        match.visibility = View.GONE
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(act)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { state.text = "Finding the channel…" }
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { heard.text = "\"$it\"" }
            }
            override fun onResults(results: Bundle?) {
                handle(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf())
            }
            override fun onError(error: Int) {
                state.text = when (error) {
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "No internet for voice recognition"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is off"
                    else -> "Didn't catch that. Hold OK and try again."
                }
                postDelayed(hideLater, 1800)
            }
        })
        r.startListening(intent())
    }

    /** Also used for results coming back from the system voice screen. */
    fun handle(phrases: List<String>) {
        if (phrases.isEmpty()) {
            visibility = View.VISIBLE
            state.text = "Didn't catch that. Hold OK and try again."
            postDelayed(hideLater, 1800)
            return
        }
        visibility = View.VISIBLE
        heard.text = "\"${phrases.first()}\""
        val found = VoiceMatch.best(phrases, repo.channels)
        if (found == null) {
            state.text = "No matching channel"
            postDelayed(hideLater, 1800)
            return
        }
        val ch = found.first
        state.text = "Matched channel"
        match.text = "${ch.number}   ${ch.name}"
        match.visibility = View.VISIBLE
        postDelayed({
            close()
            act.playFromVoice(ch)
        }, 700)
    }

    fun close() {
        removeCallbacks(hideLater)
        recognizer?.let { runCatching { it.cancel() }; runCatching { it.destroy() } }
        recognizer = null
        visibility = View.GONE
    }
}
