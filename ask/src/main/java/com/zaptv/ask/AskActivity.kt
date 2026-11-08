package com.zaptv.ask

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
import java.util.concurrent.Executors

class AskActivity : Activity() {
    private val BG = 0xFF030725.toInt(); private val SURF = 0xFF0B1342.toInt(); private val SURF2 = 0xFF14205C.toInt()
    private val LINE = 0xFF26326E.toInt(); private val INK = 0xFFEEF3FF.toInt(); private val MUTED = 0xFF93A0CF.toInt()
    private val CYAN = 0xFF19E3FF.toInt(); private val VIOLET = 0xFF9B6CFF.toInt(); private val DARK = 0xFF03081F.toInt()

    private val io = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null
    private lateinit var keyServer: KeyServer

    private lateinit var feed: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var hintBig: TextView
    private lateinit var listenWrap: FrameLayout
    private lateinit var heard: TextView
    private lateinit var thinking: TextView
    private val cardViews = ArrayList<View>()
    private val sourceViews = ArrayList<View>()
    private var focusZone = 0  // 0 mic, 1 cards, 2 sources
    private var cardIdx = 0
    private var srcIdx = 0
    private var answer: Answer? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
    private fun tv(s: CharSequence, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); includeFontPadding = false
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private fun round(c: Int, r: Int, stroke: Int = 0, sc: Int = 0) = GradientDrawable().apply {
        cornerRadius = dp(r).toFloat(); setColor(c); if (stroke > 0) setStroke(dp(stroke), sc)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        keyServer = KeyServer(prefs).also { it.start(); it.onSaved = { main.post { if (feedShowingKeyHelp) showWelcome() } } }
        tts = TextToSpeech(this) { if (it == TextToSpeech.SUCCESS) tts?.language = Locale("tr", "TR") }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG); setPadding(dp(40), dp(26), dp(40), dp(24)) }

        // top bar
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val spark = View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(CYAN, VIOLET)).apply { cornerRadius = dp(12).toFloat() } }
        bar.addView(spark, LinearLayout.LayoutParams(dp(40), dp(40)))
        bar.addView(tv("Ask", 22f, INK, true), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12) })
        bar.addView(tv("  powered by Gemini", 14f, MUTED), LinearLayout.LayoutParams(-2, -2))
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        root.addView(bar, LinearLayout.LayoutParams(-1, -2))

        thinking = tv("Thinking…", 16f, CYAN, true).apply { visibility = View.GONE }
        root.addView(thinking, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })

        scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        feed = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(feed)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(12) })

        // composer
        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = round(SURF, 20, 1, LINE); setPadding(dp(20), dp(14), dp(20), dp(14))
        }
        mic = View(this).apply { background = micBg(false) }
        composer.addView(mic, LinearLayout.LayoutParams(dp(58), dp(58)))
        val hints = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        hintBig = tv("Hold OK and ask anything", 19f, INK, true)
        hints.addView(hintBig)
        hints.addView(tv("Turkish or English, spoken answers", 14f, MUTED), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(3) })
        composer.addView(hints, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(16) })
        root.addView(composer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        setContentView(root)

        // listening overlay
        listenWrap = FrameLayout(this).apply { setBackgroundColor(0xB8030725.toInt()); visibility = View.GONE }
        val lbox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        val pulse = View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(CYAN, VIOLET)).apply { shape = GradientDrawable.OVAL } }
        lbox.addView(pulse, LinearLayout.LayoutParams(dp(100), dp(100)))
        lbox.addView(tv("Listening…", 17f, MUTED), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) })
        heard = tv("", 28f, INK, true).apply { gravity = Gravity.CENTER }
        lbox.addView(heard, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
        listenWrap.addView(lbox, FrameLayout.LayoutParams(-1, -1).apply { })
        (lbox.layoutParams as FrameLayout.LayoutParams).gravity = Gravity.CENTER
        addContentView(listenWrap, FrameLayout.LayoutParams(-1, -1))

        showWelcome()
        if (prefs.geminiKey.isBlank()) showKeyHelp()
    }

    private lateinit var mic: View
    private fun micBg(focus: Boolean) = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(CYAN, VIOLET)).apply {
        shape = GradientDrawable.OVAL
        if (focus) setStroke(dp(3), CYAN)
    }

    override fun onResume() {
        super.onResume()
        updateFocus()
    }

    override fun onDestroy() {
        tts?.shutdown(); recognizer?.destroy(); super.onDestroy()
    }

    // ---------------- welcome / key help ----------------

    private fun showWelcome() {
        feedShowingKeyHelp = false
        feed.removeAllViews()
        feed.addView(tv("Ask me anything. Hold OK and speak, in Turkish or English.", 19f, MUTED, false).apply { setPadding(0, dp(30), 0, dp(16)) })
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (ex in listOf("TRT'de bugün ne var", "İskandinav oturma odası çiz", "En iyi espresso makineleri")) {
            chips.addView(tv(ex, 14f, MUTED).apply {
                background = round(SURF2, 999); setPadding(dp(14), dp(9), dp(14), dp(9))
            }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(10) })
        }
        feed.addView(chips)
    }

    private fun showKeyHelp() {
        feedShowingKeyHelp = true
        feed.removeAllViews()
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = round(SURF, 18, 1, LINE); setPadding(dp(24), dp(20), dp(24), dp(20)) }
        card.addView(tv("One-time setup: add your free Gemini key", 20f, INK, true))
        val steps = "1.  On your phone or laptop, open  aistudio.google.com/apikey\n" +
            "2.  Sign in with Google and press Create API key\n" +
            "3.  Copy it, then open this TV's Ask page on your phone to paste it:\n" +
            "     ${serverHint()}\n\n" +
            "It's free for personal use. Your key stays on this TV."
        card.addView(tv(steps, 16f, MUTED).apply { setLineSpacing(0f, 1.3f); setPadding(0, dp(12), 0, 0) })
        feed.addView(card)
    }

    private var feedShowingKeyHelp = false
    private fun serverHint(): String = keyServer.address().removePrefix("http://").ifEmpty { "connect the TV to Wi-Fi" }

    // ---------------- voice ----------------

    private fun startVoice() {
        if (prefs.geminiKey.isBlank()) { showKeyHelp(); return }
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 11); return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            runCatching { startActivityForResult(voiceIntent(), 12) }
            return
        }
        listenWrap.visibility = View.VISIBLE
        heard.text = ""
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(e: Int) { listenWrap.visibility = View.GONE }
            override fun onPartialResults(b: Bundle?) { b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { heard.text = "\"$it\"" } }
            override fun onEvent(t: Int, b: Bundle?) {}
            override fun onResults(b: Bundle?) {
                listenWrap.visibility = View.GONE
                b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { submit(it) }
            }
        })
        r.startListening(voiceIntent())
    }

    private fun voiceIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
    }

    override fun onRequestPermissionsResult(rc: Int, pm: Array<out String>, gr: IntArray) {
        super.onRequestPermissionsResult(rc, pm, gr)
        if (rc == 11 && gr.firstOrNull() == PackageManager.PERMISSION_GRANTED) startVoice()
    }

    override fun onActivityResult(rc: Int, res: Int, data: Intent?) {
        super.onActivityResult(rc, res, data)
        if (rc == 12 && res == RESULT_OK) data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { submit(it) }
    }

    // ---------------- ask + render ----------------

    private fun submit(question: String) {
        thinking.visibility = View.VISIBLE
        feed.removeAllViews()
        feed.addView(bubble(question))
        answer = null; cardViews.clear(); sourceViews.clear(); cardIdx = 0; srcIdx = 0
        val gem = Gemini(prefs.geminiKey)
        val images = Images(this, prefs)
        io.execute {
            val a = gem.ask(question)
            // fetch real images for cards in parallel-ish
            for (c in a.cards) if (c.imageData == null) c.imageData = runCatching { images.forCard(c) }.getOrNull()
            main.post {
                thinking.visibility = View.GONE
                answer = a
                render(a)
                if (prefs.speak && a.error == null) speak(a.summary.ifBlank { a.heading })
                else if (a.error != null) speak(a.error)
            }
        }
    }

    private fun bubble(q: String) = tv(q, 19f, INK, true).apply {
        background = round(SURF2, 16, 1, LINE); setPadding(dp(18), dp(12), dp(18), dp(12))
        val lp = LinearLayout.LayoutParams(-2, -2); lp.bottomMargin = dp(14); layoutParams = lp
    }

    private fun render(a: Answer) {
        feed.removeAllViews()
        feed.addView(bubble(a.question))
        if (a.error != null) {
            feed.addView(tv(a.error, 18f, 0xFFFF6A4D.toInt(), true).apply { setPadding(0, dp(8), 0, 0) })
            focusZone = 0; updateFocus(); return
        }
        feed.addView(tv(a.heading, 23f, INK, true).apply { setPadding(0, dp(4), 0, dp(6)) })
        if (a.summary.isNotBlank()) feed.addView(tv(a.summary, 18f, 0xFFD6DEFF.toInt()).apply { setLineSpacing(0f, 1.3f); setPadding(0, 0, 0, dp(14)) })

        a.generatedImage?.let { bytes ->
            val iv = ImageView(this).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_START }
            iv.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            feed.addView(iv, LinearLayout.LayoutParams(dp(520), -2).apply { bottomMargin = dp(12) })
        }

        if (a.cards.isNotEmpty()) {
            val grid = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            cardViews.clear()
            for ((i, c) in a.cards.take(6).withIndex()) {
                val v = cardView(c)
                cardViews.add(v)
                grid.addView(v, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = if (i < a.cards.size - 1) dp(12) else 0 })
            }
            feed.addView(grid, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
        }

        if (a.sources.isNotEmpty()) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            sourceViews.clear()
            for (s in a.sources.take(6)) {
                val v = sourceView(s)
                sourceViews.add(v)
                row.addView(v, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(10) })
            }
            val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }
            feed.addView(hs)
        }
        focusZone = if (cardViews.isNotEmpty()) 1 else if (sourceViews.isNotEmpty()) 2 else 0
        cardIdx = 0; srcIdx = 0
        updateFocus()
    }

    private fun cardView(c: Card): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = round(SURF, 16, 1, LINE) }
        val pic = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(SURF2) }
        c.imageData?.let { pic.setImageBitmap(BitmapFactory.decodeByteArray(it, 0, it.size)) }
        box.addView(pic, LinearLayout.LayoutParams(-1, dp(120)))
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12)) }
        body.addView(tv(c.title, 16f, INK, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        if (c.desc.isNotBlank()) body.addView(tv(c.desc, 13f, MUTED).apply { maxLines = 3; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(3), 0, 0) })
        if (c.price.isNotBlank() || c.link.isNotBlank()) {
            val f = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0); gravity = Gravity.CENTER_VERTICAL }
            f.addView(tv(c.price, 15f, INK, true), LinearLayout.LayoutParams(0, -2, 1f))
            if (c.link.isNotBlank()) f.addView(tv("Open ›", 12f, CYAN))
            body.addView(f)
        }
        box.addView(body)
        box.setOnClickListener { c.link.takeIf { it.isNotBlank() }?.let { openLink(it) } }
        return box
    }

    private fun sourceView(s: Source): View = tv(host(s.url), 13f, MUTED).apply {
        background = round(SURF, 999, 1, LINE); setPadding(dp(14), dp(8), dp(14), dp(8))
        setOnClickListener { openLink(s.url) }
    }

    private fun host(url: String) = runCatching { Uri.parse(url).host?.removePrefix("www.") ?: url }.getOrDefault(url)

    private fun openLink(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { toast("No browser on this TV to open the link") }
    }

    private fun speak(s: String) { tts?.speak(s, TextToSpeech.QUEUE_FLUSH, null, "ask") }
    private fun toast(m: String) = android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_SHORT).show()

    // ---------------- focus / keys ----------------

    private fun updateFocus() {
        mic.background = micBg(focusZone == 0)
        for ((i, v) in cardViews.withIndex()) v.background = round(if (focusZone == 1 && i == cardIdx) SURF2 else SURF, 16, if (focusZone == 1 && i == cardIdx) 3 else 1, if (focusZone == 1 && i == cardIdx) CYAN else LINE)
        for ((i, v) in sourceViews.withIndex()) {
            val on = focusZone == 2 && i == srcIdx
            v.background = round(SURF, 999, if (on) 2 else 1, if (on) CYAN else LINE)
            (v as TextView).setTextColor(if (on) CYAN else MUTED)
        }
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val code = e.keyCode
        val down = e.action == KeyEvent.ACTION_DOWN
        val up = e.action == KeyEvent.ACTION_UP
        val first = down && e.repeatCount == 0
        if (listenWrap.visibility == View.VISIBLE) {
            if (code == KeyEvent.KEYCODE_BACK && first) { listenWrap.visibility = View.GONE; recognizer?.cancel() }
            return true
        }
        when (code) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (down && e.repeatCount == 0) okFired = false
                else if (down && !okFired && e.eventTime - e.downTime >= 500) { okFired = true; startVoice() }
                else if (up) { if (!okFired) onOk(); okFired = false }
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (first) { if (answer != null) { showWelcome(); answer = null; focusZone = 0; cardViews.clear(); sourceViews.clear(); updateFocus() } else finish() }
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> { if (first) move(-1); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { if (first) move(1); return true }
            KeyEvent.KEYCODE_DPAD_UP -> { if (first) vertical(-1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { if (first) vertical(1); return true }
        }
        return super.dispatchKeyEvent(e)
    }

    private var okFired = false

    private fun onOk() {
        when (focusZone) {
            0 -> startVoice()
            1 -> answer?.cards?.getOrNull(cardIdx)?.link?.takeIf { it.isNotBlank() }?.let { openLink(it) }
            2 -> answer?.sources?.getOrNull(srcIdx)?.url?.let { openLink(it) }
        }
    }

    private fun move(step: Int) {
        when (focusZone) {
            1 -> cardIdx = (cardIdx + step).coerceIn(0, cardViews.size - 1)
            2 -> srcIdx = (srcIdx + step).coerceIn(0, sourceViews.size - 1)
        }
        updateFocus()
    }

    private fun vertical(step: Int) {
        if (step > 0) {
            focusZone = when (focusZone) { 1 -> if (sourceViews.isNotEmpty()) 2 else 0; 2 -> 0; else -> 0 }
        } else {
            focusZone = when (focusZone) { 0 -> if (cardViews.isNotEmpty()) 1 else if (sourceViews.isNotEmpty()) 2 else 0; 2 -> if (cardViews.isNotEmpty()) 1 else 2; else -> 1 }
        }
        updateFocus()
        if (focusZone == 0) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }
}
