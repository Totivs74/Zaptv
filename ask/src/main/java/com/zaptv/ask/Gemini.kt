package com.zaptv.ask

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * Talks to the Gemini API with a key the user pastes once.
 * Current-info questions use Google Search grounding; "draw me" questions use the image model.
 * Gemini can't combine grounding with strict JSON, so cards are formatted in a second quick call.
 */
class Gemini(private val key: String) {
    private val base = "https://generativelanguage.googleapis.com/v1beta/models"
    private val textModel = "gemini-2.5-flash"
    private val imageModel = "gemini-2.5-flash-image"

    private fun headers() = mapOf("Content-Type" to "application/json", "x-goog-api-key" to key)

    private fun generate(model: String, bodyJson: JSONObject): JSONObject =
        JSONObject(Net.text("$base/$model:generateContent", headers(), "POST", bodyJson.toString().toByteArray()))

    private fun textPart(s: String) = JSONObject().put("text", s)
    private fun userContent(s: String) = JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(textPart(s))))

    private fun firstText(res: JSONObject): String {
        val parts = res.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until parts.length()) parts.optJSONObject(i)?.optString("text")?.let { sb.append(it) }
        return sb.toString()
    }

    /** True if the question is asking to create/draw/show an imagined picture. */
    private fun isDrawRequest(q: String): Boolean {
        val w = q.lowercase()
        val tr = listOf("çiz", "ciz", "resim yap", "görsel oluştur", "gorsel olustur", "tasarla", "hayal et")
        val en = listOf("draw ", "generate an image", "imagine ", "create an image", "paint ")
        return (tr + en).any { w.contains(it) }
    }

    fun ask(question: String): Answer {
        return try {
            if (isDrawRequest(question)) drawAnswer(question) else infoAnswer(question)
        } catch (e: Exception) {
            Answer(question, "", "", error = friendly(e))
        }
    }

    private fun friendly(e: Exception): String {
        val m = e.message ?: "Something went wrong"
        return when {
            m.contains("401") || m.contains("403") || m.contains("API_KEY") -> "Your Gemini key was rejected. Check it in Settings."
            m.contains("429") -> "Too many requests for now. Try again in a moment."
            m.contains("Unable to resolve host") || m.contains("timeout") -> "No internet connection."
            else -> m.take(140)
        }
    }

    // ---- "Draw me" -> generated image ----
    private fun drawAnswer(q: String): Answer {
        val body = JSONObject().put("contents", userContent(q))
        val res = generate(imageModel, body)
        val parts = res.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
        var img: ByteArray? = null
        var caption = ""
        if (parts != null) for (i in 0 until parts.length()) {
            val p = parts.getJSONObject(i)
            p.optJSONObject("inlineData")?.optString("data")?.let { img = Base64.decode(it, Base64.DEFAULT) }
            p.optString("text").takeIf { it.isNotBlank() }?.let { caption = it }
        }
        return Answer(q, "Here you go", caption.ifBlank { "Generated image" }, generatedImage = img,
            error = if (img == null) "Couldn't create the image. Try rewording it." else null)
    }

    // ---- Everything else -> grounded facts, then formatted into cards ----
    private fun infoAnswer(q: String): Answer {
        // Step 1: grounded answer with Google Search (gives text + real source links)
        val groundBody = JSONObject()
            .put("contents", userContent(q))
            .put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(textPart(
                "You are a helpful TV assistant. Answer the user's language. Be concise and specific. " +
                "If the question is about products, places, films or similar, list 3 to 6 concrete named items with one short reason each."
            ))))
        val grounded = generate(textModel, groundBody)
        val groundedText = firstText(grounded)
        val sources = extractSources(grounded)

        // Step 2: turn that into a compact JSON of heading/summary/cards (no tools, so JSON is allowed)
        val schema = JSONObject()
            .put("type", "OBJECT")
            .put("properties", JSONObject()
                .put("heading", JSONObject().put("type", "STRING"))
                .put("summary", JSONObject().put("type", "STRING"))
                .put("cards", JSONObject().put("type", "ARRAY").put("items", JSONObject()
                    .put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("title", JSONObject().put("type", "STRING"))
                        .put("desc", JSONObject().put("type", "STRING"))
                        .put("price", JSONObject().put("type", "STRING"))
                        .put("image_query", JSONObject().put("type", "STRING")))
                    .put("required", JSONArray().put("title").put("desc").put("image_query")))))
            .put("required", JSONArray().put("heading").put("summary").put("cards"))
        val fmtPrompt = "From the text below, produce a short heading, a one or two sentence spoken summary, " +
            "and up to 6 cards. Each card: title, desc (one short line), price (empty if none), " +
            "and image_query (2 to 4 words to find a real photo of it). Keep the user's language.\n\nTEXT:\n$groundedText"
        val fmtBody = JSONObject()
            .put("contents", userContent(fmtPrompt))
            .put("generationConfig", JSONObject()
                .put("responseMimeType", "application/json")
                .put("responseSchema", schema))
        val parsed = runCatching { JSONObject(firstText(generate(textModel, fmtBody))) }.getOrNull()

        if (parsed == null) {
            // Fall back to plain text if formatting failed
            return Answer(q, "Answer", groundedText.take(600), sources = sources)
        }
        val cardsArr = parsed.optJSONArray("cards") ?: JSONArray()
        val cards = ArrayList<Card>()
        for (i in 0 until cardsArr.length()) {
            val o = cardsArr.getJSONObject(i)
            cards.add(Card(
                title = o.optString("title"),
                desc = o.optString("desc"),
                price = o.optString("price"),
                link = sources.getOrNull(i)?.url ?: "",
                imageUrl = ""
            ).also { it.imageQuery = o.optString("image_query") })
        }
        return Answer(q, parsed.optString("heading", "Answer"), parsed.optString("summary"), cards, sources)
    }

    private fun extractSources(res: JSONObject): List<Source> {
        val out = LinkedHashMap<String, Source>()
        val gm = res.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("groundingMetadata") ?: return emptyList()
        val chunks = gm.optJSONArray("groundingChunks") ?: return emptyList()
        for (i in 0 until chunks.length()) {
            val web = chunks.optJSONObject(i)?.optJSONObject("web") ?: continue
            val uri = web.optString("uri")
            val title = web.optString("title").ifBlank { uri }
            if (uri.isNotBlank() && !out.containsKey(uri)) out[uri] = Source(title, uri)
        }
        return out.values.toList()
    }
}
