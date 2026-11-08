package com.zaptv.ask

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * One grounded call per question. Gemini returns Markdown-ish text with light tags we parse into
 * steps / cards / plain text, so the TV shows the right layout without a slow second call.
 */
class Gemini(private val key: String) {
    private val base = "https://generativelanguage.googleapis.com/v1beta/models"
    private val textModel = "gemini-flash-latest"
    private val textFallbacks = listOf("gemini-3.5-flash", "gemini-3.1-flash-lite", "gemini-2.5-flash")
    private val imageModel = "gemini-3.1-flash-image"
    private val imageFallbacks = listOf("gemini-2.5-flash-image")

    private fun headers() = mapOf("Content-Type" to "application/json", "x-goog-api-key" to key)

    private fun generate(model: String, body: JSONObject): JSONObject {
        val chain = listOf(model) + when (model) { textModel -> textFallbacks; imageModel -> imageFallbacks; else -> emptyList() }
        var last: Exception? = null
        for (m in chain) {
            try { return JSONObject(Net.text("$base/$m:generateContent", headers(), "POST", body.toString().toByteArray())) }
            catch (e: Exception) {
                last = e; val msg = e.message ?: ""
                if (!(msg.contains("404") || msg.contains("no longer available") || msg.contains("not found", true))) throw e
            }
        }
        throw last ?: IllegalStateException("No model available")
    }

    private fun part(s: String) = JSONObject().put("text", s)
    private fun userContent(s: String) = JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(part(s))))
    private fun firstText(res: JSONObject): String {
        val parts = res.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until parts.length()) parts.optJSONObject(i)?.optString("text")?.let { sb.append(it) }
        return sb.toString()
    }

    private fun isDraw(q: String): Boolean {
        val w = q.lowercase()
        return listOf("çiz", "ciz", "resim yap", "görsel oluştur", "gorsel olustur", "tasarla", "hayal et",
            "draw ", "generate an image", "imagine ", "create an image", "paint ").any { w.contains(it) }
    }

    fun ask(question: String): Answer = try {
        if (isDraw(question)) draw(question) else answer(question)
    } catch (e: Exception) { Answer(question, "", "", error = friendly(e)) }

    private fun friendly(e: Exception): String {
        val m = e.message ?: "Something went wrong"
        return when {
            m.contains("401") || m.contains("403") || m.contains("API_KEY") -> "Your Gemini key was rejected. Check it in setup."
            m.contains("429") || m.contains("RESOURCE_EXHAUSTED") || m.contains("quota") -> "Daily free limit reached. Try again later, or add billing to your Google key."
            m.contains("Unable to resolve host") || m.contains("timeout") || m.contains("Failed to connect") -> "No internet connection."
            else -> m.take(140)
        }
    }

    private fun draw(q: String): Answer {
        val res = generate(imageModel, JSONObject().put("contents", userContent(q)))
        val parts = res.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
        var img: ByteArray? = null; var caption = ""
        if (parts != null) for (i in 0 until parts.length()) {
            val p = parts.getJSONObject(i)
            p.optJSONObject("inlineData")?.optString("data")?.let { img = Base64.decode(it, Base64.DEFAULT) }
            p.optString("text").takeIf { it.isNotBlank() }?.let { caption = it }
        }
        return Answer(q, "Here you go", caption.ifBlank { "Generated image" }, layout = "text", generatedImage = img,
            error = if (img == null) "Couldn't create the image. Try rewording it." else null)
    }

    private val sys = """
You are a voice TV assistant shown on a big screen. Reply in the user's language. Be concise.
Pick ONE layout and format EXACTLY as shown so the TV can lay it out:

For recipes, how-tos, instructions (ordered actions):
LAYOUT: STEPS
HEADING: short title
SUMMARY: one sentence, spoken aloud
1 | Step name | one short line of detail
2 | Step name | one short line of detail

For lists of things to compare or choose (products, places, films, options):
LAYOUT: CARDS
HEADING: short title
SUMMARY: one sentence, spoken aloud
- Name | one short line | price or empty | 2-4 word image search phrase
- Name | one short line | price or empty | 2-4 word image search phrase

For everything else (facts, explanations, short answers):
LAYOUT: TEXT
HEADING: short title
SUMMARY: one sentence, spoken aloud
BODY: a few short sentences.

Use at most 6 steps or 6 cards. Keep each line on ONE line.
""".trimIndent()

    private fun answer(q: String): Answer {
        val body = JSONObject()
            .put("contents", userContent(q))
            .put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(part(sys))))
        val res = generate(textModel, body)
        val text = firstText(res)
        val sources = sources(res)
        return parse(q, text, sources)
    }

    private fun parse(q: String, text: String, sources: List<Source>): Answer {
        val lines = text.lines()
        fun field(tag: String) = lines.firstOrNull { it.trim().startsWith(tag, true) }?.substringAfter(":")?.trim().orEmpty()
        val layout = field("LAYOUT").uppercase().let { if ("STEP" in it) "steps" else if ("CARD" in it) "cards" else "text" }
        val heading = field("HEADING").ifBlank { "Answer" }
        val summary = field("SUMMARY")
        when (layout) {
            "steps" -> {
                val steps = ArrayList<Step>()
                val re = Regex("^\\s*\\d+\\s*\\|")
                for (l in lines) if (re.containsMatchIn(l)) {
                    val parts = l.substringAfter("|").split("|")
                    val title = parts.getOrNull(0)?.trim().orEmpty()
                    val detail = parts.getOrNull(1)?.trim().orEmpty()
                    if (title.isNotEmpty()) steps.add(Step(title, detail))
                }
                if (steps.isNotEmpty()) return Answer(q, heading, summary, "steps", steps = steps, sources = sources)
            }
            "cards" -> {
                val cards = ArrayList<Card>()
                for (l in lines) if (l.trimStart().startsWith("-")) {
                    val parts = l.trimStart().removePrefix("-").split("|")
                    val name = parts.getOrNull(0)?.trim().orEmpty()
                    if (name.isEmpty()) continue
                    cards.add(Card(
                        title = name,
                        desc = parts.getOrNull(1)?.trim().orEmpty(),
                        price = parts.getOrNull(2)?.trim().orEmpty(),
                        imageQuery = parts.getOrNull(3)?.trim().orEmpty(),
                        link = sources.getOrNull(cards.size)?.url ?: ""
                    ))
                }
                if (cards.isNotEmpty()) return Answer(q, heading, summary, "cards", cards = cards, sources = sources)
            }
        }
        // text (or fallback): body is everything after BODY:, else the whole text minus tags
        val bodyText = run {
            val idx = lines.indexOfFirst { it.trim().startsWith("BODY", true) }
            if (idx >= 0) (listOf(lines[idx].substringAfter(":")) + lines.drop(idx + 1)).joinToString("\n").trim()
            else lines.filterNot { it.trim().startsWith("LAYOUT", true) || it.trim().startsWith("HEADING", true) || it.trim().startsWith("SUMMARY", true) }.joinToString("\n").trim()
        }
        return Answer(q, heading, summary.ifBlank { bodyText.take(120) }, "text", body = bodyText, sources = sources)
    }

    private fun sources(res: JSONObject): List<Source> {
        val out = LinkedHashMap<String, Source>()
        val gm = res.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("groundingMetadata") ?: return emptyList()
        val chunks = gm.optJSONArray("groundingChunks") ?: return emptyList()
        for (i in 0 until chunks.length()) {
            val web = chunks.optJSONObject(i)?.optJSONObject("web") ?: continue
            val uri = web.optString("uri"); if (uri.isBlank() || out.containsKey(uri)) continue
            out[uri] = Source(web.optString("title").ifBlank { uri }, uri)
        }
        return out.values.toList()
    }
}
