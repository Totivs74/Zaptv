package com.zaptv.ask

import android.Manifest
import android.app.Activity
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
    private lateinit var keyServer: KeyServer
    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null

    private lateinit var scroll: ScrollView
    private lateinit var feed: LinearLayout
    private lateinit var mic: View
    private lateinit var thinking: TextView
    private lateinit var listenWrap: FrameLayout
    private lateinit var heard: TextView

    // focusables: index 0 = mic, then rows (steps/cards), then sources
    private val focusables = ArrayList<View>()
    private val focusKind = ArrayList<String>()   // "mic","step","card","source"
    private var focus = 0
    private var answer: Answer? = null
    private var reader: FrameLayout? = null
    private var showingKeyHelp = false
    private var okFired = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
    private fun tvw(s: CharSequence, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); includeFontPadding = false
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private fun round(c: Int, r: Int, stroke: Int = 0, sc: Int = 0) = GradientDrawable().apply {
        cornerRadius = dp(r).toFloat(); setColor(c); if (stroke > 0) setStroke(dp(stroke), sc)
    }
    private fun micBg(focus: Boolean) = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(CYAN, VIOLET)).apply {
        shape = GradientDrawable.OVAL; if (focus) setStroke(dp(4), 0xFFFFFFFF.toInt())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        keyServer = KeyServer(prefs).also { it.start(); it.onSaved = { main.post { if (showingKeyHelp) welcome() } } }
        tts = TextToSpeech(this) { if (it == TextToSpeech.SUCCESS) tts?.language = Locale("tr", "TR") }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG); setPadding(dp(40), dp(26), dp(40), dp(24)) }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bar.addView(View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(CYAN, VIOLET)).apply { cornerRadius = dp(12).toFloat() } }, LinearLayout.LayoutParams(dp(40), dp(40)))
        bar.addView(tvw("Ask", 22f, INK, true), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12) })
        bar.addView(tvw("  powered by Gemini", 14f, MUTED), LinearLayout.LayoutParams(-2, -2))
        root.addView(bar, LinearLayout.LayoutParams(-1, -2))

        thinking = tvw("Thinking…", 16f, CYAN, true).apply { visibility = View.GONE }
        root.addView(thinking, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })

        scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; isSmoothScrollingEnabled = true }
        feed = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(feed)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(12) })

        val composer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = round(SURF, 20, 1, LINE); setPadding(dp(20), dp(14), dp(20), dp(14)) }
        mic = View(this).apply { background = micBg(true) }
        composer.addView(mic, LinearLayout.LayoutParams(dp(58), dp(58)))
        val hints = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        hints.addView(tvw("Hold OK and ask anything", 19f, INK, true))
        hints.addView(tvw("Turkish or English, spoken answers", 14f, MUTED), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(3) })
        composer.addView(hints, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(16) })
        root.addView(composer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        setContentView(root)

        listenWrap = FrameLayout(this).apply { setBackgroundColor(0xC2030725.toInt()); visibility = View.GONE }
        val lbox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        lbox.addView(View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(CYAN, VIOLET)).apply { shape = GradientDrawable.OVAL } }, LinearLayout.LayoutParams(dp(100), dp(100)))
        lbox.addView(tvw("Listening…", 17f, MUTED), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) })
        heard = tvw("", 28f, INK, true).apply { gravity = Gravity.CENTER }
        lbox.addView(heard, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
        listenWrap.addView(lbox, FrameLayout.LayoutParams(-1, -1).apply { gravity = Gravity.CENTER })
        addContentView(listenWrap, FrameLayout.LayoutParams(-1, -1))

        if (prefs.geminiKey.isBlank()) keyHelp() else welcome()
    }

    override fun onDestroy() { tts?.shutdown(); recognizer?.destroy(); super.onDestroy() }

    // ---------------- screens ----------------

    private fun welcome() {
        showingKeyHelp = false
        feed.removeAllViews(); focusables.clear(); focusKind.clear(); answer = null
        focusables.add(mic); focusKind.add("mic"); focus = 0
        feed.addView(tvw("Ask me anything. Hold OK and speak, in Turkish or English.", 19f, MUTED).apply { setPadding(0, dp(26), 0, dp(14)) })
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (ex in listOf("Muhallebi nasıl yapılır", "İskandinav oturma odası çiz", "En iyi espresso makineleri")) {
            chips.addView(tvw(ex, 14f, MUTED).apply { background = round(SURF2, 999); setPadding(dp(14), dp(9), dp(14), dp(9)) }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(10) })
        }
        feed.addView(chips)
        updateFocus()
    }

    private fun keyHelp() {
        showingKeyHelp = true
        feed.removeAllViews(); focusables.clear(); focusKind.clear()
        focusables.add(mic); focusKind.add("mic"); focus = 0
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = round(SURF, 18, 1, LINE); setPadding(dp(24), dp(20), dp(24), dp(20)) }
        card.addView(tvw("One-time setup: add your free Gemini key", 20f, INK, true))
        val steps = "1.  On your phone or laptop, open  aistudio.google.com/apikey\n" +
            "2.  Sign in with Google, press Create API key, copy it\n" +
            "3.  Open this TV's Ask page and paste it:\n     ${keyServer.address().removePrefix("http://").ifEmpty { "connect the TV to Wi-Fi" }}\n\n" +
            "Free for personal use. The key stays on this TV."
        card.addView(tvw(steps, 16f, MUTED).apply { setLineSpacing(0f, 1.3f); setPadding(0, dp(12), 0, 0) })
        feed.addView(card)
        updateFocus()
    }

    // ---------------- voice ----------------

    private fun startVoice() {
        if (prefs.geminiKey.isBlank()) { keyHelp(); return }
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 11); return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { runCatching { startActivityForResult(voiceIntent(), 12) }; return }
        listenWrap.visibility = View.VISIBLE; heard.text = ""
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(this); recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) {}; override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}; override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}; override fun onEvent(t: Int, b: Bundle?) {}
            override fun onError(e: Int) { listenWrap.visibility = View.GONE }
            override fun onPartialResults(b: Bundle?) { b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { heard.text = "\"$it\"" } }
            override fun onResults(b: Bundle?) { listenWrap.visibility = View.GONE; b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { submit(it) } }
        })
        r.startListening(voiceIntent())
    }
    private fun voiceIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR"); putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
    }
    override fun onRequestPermissionsResult(rc: Int, pm: Array<out String>, gr: IntArray) {
        super.onRequestPermissionsResult(rc, pm, gr)
        if (rc == 11 && gr.firstOrNull() == PackageManager.PERMISSION_GRANTED) startVoice()
    }
    override fun onActivityResult(rc: Int, res: Int, data: Intent?) {
        super.onActivityResult(rc, res, data)
        if (rc == 12 && res == RESULT_OK) data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { submit(it) }
    }

    // ---------------- ask ----------------

    private fun submit(question: String) {
        thinking.visibility = View.VISIBLE
        feed.removeAllViews(); focusables.clear(); focusKind.clear()
        focusables.add(mic); focusKind.add("mic"); focus = 0
        feed.addView(bubble(question)); updateFocus()
        val gem = Gemini(prefs.geminiKey); val images = Images(this, prefs)
        io.execute {
            val a = gem.ask(question)
            main.post {
                thinking.visibility = View.GONE
                answer = a; render(a)
                if (a.error == null && a.summary.isNotBlank()) speak(a.summary) else a.error?.let { speak(it) }
            }
            // images load in the background and fill in without blocking the answer
            if (a.layout == "cards") for ((i, c) in a.cards.withIndex()) {
                val bytes = runCatching { images.forCard(c) }.getOrNull() ?: continue
                c.imageData = bytes
                main.post { fillCardImage(i, bytes) }
            }
        }
    }

    private fun bubble(q: String) = tvw(q, 19f, INK, true).apply {
        background = round(SURF2, 16, 1, LINE); setPadding(dp(18), dp(12), dp(18), dp(12))
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(14) }
    }

    private val cardImageViews = HashMap<Int, ImageView>()

    private fun render(a: Answer) {
        feed.removeAllViews(); focusables.clear(); focusKind.clear(); cardImageViews.clear()
        focusables.add(mic); focusKind.add("mic")
        feed.addView(bubble(a.question))
        if (a.error != null) { feed.addView(tvw(a.error, 18f, 0xFFFF6A4D.toInt(), true).apply { setPadding(0, dp(8), 0, 0) }); focus = 0; updateFocus(); return }
        feed.addView(tvw(a.heading, 24f, INK, true).apply { setPadding(0, dp(4), 0, dp(6)) })
        if (a.summary.isNotBlank() && a.layout != "text") feed.addView(tvw(a.summary, 17f, 0xFFD6DEFF.toInt()).apply { setLineSpacing(0f, 1.3f); setPadding(0, 0, 0, dp(14)) })

        a.generatedImage?.let { bytes ->
            val iv = ImageView(this).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_START }
            iv.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            feed.addView(iv, LinearLayout.LayoutParams(dp(520), -2).apply { bottomMargin = dp(12) })
        }

        when (a.layout) {
            "steps" -> a.steps.forEachIndexed { i, st -> val v = stepRow(i + 1, st); focusables.add(v); focusKind.add("step"); feed.addView(v) }
            "cards" -> {
                val grid = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                a.cards.take(6).forEachIndexed { i, c ->
                    val v = cardView(i, c); focusables.add(v); focusKind.add("card")
                    grid.addView(v, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = if (i < a.cards.size - 1) dp(12) else 0 })
                }
                feed.addView(grid, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
            }
            else -> feed.addView(tvw(a.body.ifBlank { a.summary }, 19f, 0xFFD6DEFF.toInt()).apply { setLineSpacing(0f, 1.4f); setPadding(0, 0, 0, dp(12)) })
        }

        if (a.sources.isNotEmpty()) {
            feed.addView(tvw("Sources", 13f, MUTED, true).apply { setPadding(0, dp(4), 0, dp(6)) })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            a.sources.take(6).forEach { s -> val v = sourceView(s); focusables.add(v); focusKind.add("source"); row.addView(v, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(10) }) }
            feed.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) })
        }
        focus = if (focusables.size > 1) 1 else 0
        updateFocus()
    }

    private fun stepRow(n: Int, st: Step): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; background = round(SURF, 14, 1, LINE); setPadding(dp(16), dp(14), dp(16), dp(14)) }
        val num = tvw(n.toString(), 18f, DARK, true).apply { gravity = Gravity.CENTER; background = round(CYAN, 999) }
        box.addView(num, LinearLayout.LayoutParams(dp(38), dp(38)).apply { rightMargin = dp(16); gravity = Gravity.CENTER_VERTICAL })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(tvw(st.title, 18f, INK, true))
        if (st.detail.isNotBlank()) col.addView(tvw(st.detail, 15f, MUTED).apply { setLineSpacing(0f, 1.25f); setPadding(0, dp(3), 0, 0) })
        box.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        box.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
        box.setOnClickListener { openReader(st.title, st.detail) }
        return box
    }

    private fun cardView(i: Int, c: Card): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = round(SURF, 16, 1, LINE) }
        val pic = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(SURF2) }
        c.imageData?.let { pic.setImageBitmap(BitmapFactory.decodeByteArray(it, 0, it.size)) }
        cardImageViews[i] = pic
        box.addView(pic, LinearLayout.LayoutParams(-1, dp(120)))
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12)) }
        body.addView(tvw(c.title, 16f, INK, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        if (c.desc.isNotBlank()) body.addView(tvw(c.desc, 13f, MUTED).apply { maxLines = 3; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(3), 0, 0) })
        if (c.price.isNotBlank()) body.addView(tvw(c.price, 15f, INK, true).apply { setPadding(0, dp(8), 0, 0) })
        box.addView(body)
        box.setOnClickListener { openReader(c.title, listOfNotNull(c.desc, c.price.ifBlank { null }).joinToString("\n\n")) }
        return box
    }

    private fun fillCardImage(i: Int, bytes: ByteArray) {
        cardImageViews[i]?.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
    }

    private fun sourceView(s: Source): View = tvw(host(s.url), 13f, MUTED).apply {
        background = round(SURF, 999, 1, LINE); setPadding(dp(14), dp(8), dp(14), dp(8))
        setOnClickListener { openReader(host(s.url), s.title + "\n\n" + s.url) }
    }
    private fun host(url: String) = runCatching { Uri.parse(url).host?.removePrefix("www.") ?: url }.getOrDefault(url)

    // ---------------- reader (no browser needed) ----------------

    private fun openReader(title: String, text: String) {
        closeReader()
        val wrap = FrameLayout(this).apply { setBackgroundColor(0xE6030725.toInt()) }
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = round(SURF, 22, 1, LINE); setPadding(dp(40), dp(32), dp(40), dp(32)) }
        card.addView(tvw(title, 26f, INK, true))
        val sc = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        sc.addView(tvw(text.ifBlank { "No extra detail." }, 20f, 0xFFD6DEFF.toInt()).apply { setLineSpacing(0f, 1.4f); setPadding(0, dp(16), 0, 0) })
        card.addView(sc, LinearLayout.LayoutParams(-1, -2))
        card.addView(tvw("Back to close", 13f, MUTED).apply { setPadding(0, dp(18), 0, 0) })
        wrap.addView(card, FrameLayout.LayoutParams(dp(760), -2).apply { gravity = Gravity.CENTER })
        addContentView(wrap, FrameLayout.LayoutParams(-1, -1))
        reader = wrap
    }
    private fun closeReader() { reader?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }; reader = null }

    private fun speak(s: String) { tts?.speak(s, TextToSpeech.QUEUE_FLUSH, null, "ask") }

    // ---------------- focus / keys ----------------

    private fun updateFocus() {
        for (i in focusables.indices) {
            val v = focusables[i]; val on = i == focus
            when (focusKind[i]) {
                "mic" -> v.background = micBg(on)
                "step" -> v.background = round(if (on) SURF2 else SURF, 14, if (on) 3 else 1, if (on) CYAN else LINE)
                "card" -> v.background = round(if (on) SURF2 else SURF, 16, if (on) 3 else 1, if (on) CYAN else LINE)
                "source" -> { v.background = round(SURF, 999, if (on) 2 else 1, if (on) CYAN else LINE); (v as TextView).setTextColor(if (on) CYAN else MUTED) }
            }
            if (on && focusKind[i] != "mic") v.post { scroll.requestChildRectangleOnScreen(v, android.graphics.Rect(0, 0, v.width, v.height), false) }
        }
    }

    private fun kinds(k: String) = focusables.indices.filter { focusKind[it] == k }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val code = e.keyCode; val down = e.action == KeyEvent.ACTION_DOWN; val up = e.action == KeyEvent.ACTION_UP
        val first = down && e.repeatCount == 0
        if (listenWrap.visibility == View.VISIBLE) { if (code == KeyEvent.KEYCODE_BACK && first) { listenWrap.visibility = View.GONE; recognizer?.cancel() }; return true }
        if (reader != null) { if (code == KeyEvent.KEYCODE_BACK && first) closeReader(); return true }
        when (code) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (down && e.repeatCount == 0) okFired = false
                else if (down && !okFired && e.eventTime - e.downTime >= 500) { okFired = true; startVoice() }
                else if (up) { if (!okFired) onOk(); okFired = false }
                return true
            }
            KeyEvent.KEYCODE_BACK -> { if (first) { if (answer != null || showingKeyHelp) welcome() else finish() }; return true }
            KeyEvent.KEYCODE_DPAD_UP -> { if (first) moveUp(); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { if (first) moveDown(); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { if (first) moveSide(-1); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { if (first) moveSide(1); return true }
        }
        return super.dispatchKeyEvent(e)
    }

    private fun onOk() { focusables.getOrNull(focus)?.performClick() }

    // Up/down walks the vertical order: mic <-> steps/text, and cards row <-> sources row.
    private fun moveDown() {
        val k = focusKind.getOrNull(focus)
        when (k) {
            "mic" -> {} // mic is at the bottom; nothing below
            "step" -> { val s = kinds("step"); val at = s.indexOf(focus); if (at < s.size - 1) focus = s[at + 1] else goto("source") ?: run { focus = 0 } }
            "card" -> goto("source") ?: run { focus = 0 }
            "source" -> focus = 0
        }
        updateFocus()
    }
    private fun moveUp() {
        val k = focusKind.getOrNull(focus)
        when (k) {
            "mic" -> { if (!goto("step")) if (!goto("card")) goto("source") ?: run { focus = firstContent() } }
            "step" -> { val s = kinds("step"); val at = s.indexOf(focus); if (at > 0) focus = s[at - 1] }
            "card" -> {}
            "source" -> { if (!goto("card")) goto("step") }
        }
        updateFocus()
    }
    private fun moveSide(step: Int) {
        val k = focusKind.getOrNull(focus) ?: return
        if (k == "card" || k == "source") {
            val row = kinds(k); val at = row.indexOf(focus)
            val next = at + step
            if (next in row.indices) { focus = row[next]; updateFocus() }
        }
    }
    private fun goto(kind: String): Boolean { val l = kinds(kind); if (l.isEmpty()) return false; focus = l.first(); return true }
    private fun firstContent(): Int = if (focusables.size > 1) 1 else 0
}
