package com.zaptv.ask

/** One answer card: a title, a line or two, optional price, a web link, and an image to show. */
data class Card(
    val title: String,
    val desc: String,
    val price: String = "",
    val link: String = "",
    var imageUrl: String = "",     // real web image (products, places, news)
    var imageData: ByteArray? = null, // bytes once fetched
    var imageQuery: String = ""    // 2-4 words to find a real photo
)

data class Source(val title: String, val url: String)

data class Answer(
    val question: String,
    val heading: String,
    val summary: String,          // short, also spoken
    val cards: List<Card> = emptyList(),
    val sources: List<Source> = emptyList(),
    val generatedImage: ByteArray? = null,  // for "draw me / imagine"
    val error: String? = null
)
