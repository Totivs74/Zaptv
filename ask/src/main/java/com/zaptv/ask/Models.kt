package com.zaptv.ask

/** A step in a how-to / recipe answer. */
data class Step(val title: String, val detail: String)

/** One card: product, place, option. */
data class Card(
    val title: String,
    val desc: String,
    val price: String = "",
    val link: String = "",
    var imageData: ByteArray? = null,
    var imageQuery: String = ""
)

data class Source(val title: String, val url: String)

/** layout: "steps", "cards", or "text". Only cards carry images. */
data class Answer(
    val question: String,
    val heading: String,
    val summary: String,          // short, also spoken
    val layout: String = "text",
    val steps: List<Step> = emptyList(),
    val cards: List<Card> = emptyList(),
    val body: String = "",        // for plain text answers
    val sources: List<Source> = emptyList(),
    val generatedImage: ByteArray? = null,
    val error: String? = null
)
