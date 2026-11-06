package com.zaptv.app

object M3u {
    data class Result(val channels: List<Channel>, val epgUrl: String)

    private val attr = Regex("([A-Za-z0-9_-]+)=\"([^\"]*)\"")

    fun parse(text: String): Result {
        val out = ArrayList<Channel>(1024)
        var epg = ""
        var name = ""
        var attrs: Map<String, String> = emptyMap()
        var groupLine = ""
        var pending = false
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTM3U") -> {
                    val a = attr.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    epg = a["url-tvg"] ?: a["x-tvg-url"] ?: ""
                    epg = epg.split(',').firstOrNull()?.trim() ?: ""
                }
                line.startsWith("#EXTINF") -> {
                    attrs = attr.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    val lastQuote = line.lastIndexOf('"')
                    val comma = line.indexOf(',', if (lastQuote >= 0) lastQuote else 0)
                    name = if (comma >= 0) line.substring(comma + 1).trim() else ""
                    if (name.isEmpty()) name = attrs["tvg-name"] ?: "Channel"
                    groupLine = ""
                    pending = true
                }
                line.startsWith("#EXTGRP:") -> groupLine = line.substringAfter(':').trim()
                line.startsWith("#") -> Unit
                pending -> {
                    val group = (attrs["group-title"] ?: groupLine).ifBlank { "Other" }
                    val num = attrs["tvg-chno"]?.toIntOrNull() ?: 0
                    out.add(
                        Channel(
                            name = name,
                            group = group,
                            logo = attrs["tvg-logo"] ?: "",
                            url = line,
                            tvgId = attrs["tvg-id"] ?: "",
                            number = num
                        )
                    )
                    pending = false
                }
            }
        }
        return Result(out, epg)
    }
}
