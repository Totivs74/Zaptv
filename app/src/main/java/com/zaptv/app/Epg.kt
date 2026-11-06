package com.zaptv.app

import android.content.Context
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class EpgStore(ctx: Context) {
    private val file = File(ctx.filesDir, "epg.bin")
    @Volatile private var progs: Map<String, List<Programme>> = emptyMap()
    @Volatile private var names: Map<String, String> = emptyMap()

    val isEmpty: Boolean get() = progs.isEmpty()

    fun loadCache() {
        if (!file.exists()) return
        runCatching {
            DataInputStream(file.inputStream().buffered()).use { inp ->
                val n = inp.readInt()
                val nm = HashMap<String, String>(n)
                repeat(n) { nm[inp.readUTF()] = inp.readUTF() }
                val c = inp.readInt()
                val pm = HashMap<String, List<Programme>>(c)
                repeat(c) {
                    val id = inp.readUTF()
                    val k = inp.readInt()
                    val list = ArrayList<Programme>(k)
                    repeat(k) { list.add(Programme(inp.readLong(), inp.readLong(), inp.readUTF(), inp.readUTF())) }
                    pm[id] = list
                }
                names = nm
                progs = pm
            }
        }
    }

    private fun save() {
        val tmp = File(file.parentFile, "epg.tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { out ->
            val nm = names
            out.writeInt(nm.size)
            for ((k, v) in nm) { out.writeUTF(k); out.writeUTF(v) }
            val pm = progs
            out.writeInt(pm.size)
            for ((id, list) in pm) {
                out.writeUTF(id)
                out.writeInt(list.size)
                for (p in list) {
                    out.writeLong(p.start); out.writeLong(p.stop)
                    out.writeUTF(p.title.take(300)); out.writeUTF(p.desc.take(600))
                }
            }
        }
        tmp.renameTo(file)
    }

    private fun idFor(ch: Channel): String? {
        val pm = progs
        if (ch.tvgId.isNotEmpty() && pm.containsKey(ch.tvgId)) return ch.tvgId
        return names[Norm.key(ch.name)]
    }

    fun forChannel(ch: Channel): List<Programme> = idFor(ch)?.let { progs[it] } ?: emptyList()

    fun nowNext(ch: Channel, now: Long = System.currentTimeMillis()): Pair<Programme?, Programme?> {
        val list = forChannel(ch)
        val i = list.indexOfFirst { it.start <= now && it.stop > now }
        if (i < 0) return Pair(null, list.firstOrNull { it.start > now })
        return Pair(list[i], list.getOrNull(i + 1))
    }

    /** Downloads and parses an XMLTV guide, keeping only what the playlist needs (now - 2h .. now + 26h). */
    fun update(url: String, channels: List<Channel>) {
        val wantedIds = channels.mapNotNull { it.tvgId.ifEmpty { null } }.toHashSet()
        val wantedNames = channels.map { Norm.key(it.name) }.toHashSet()
        Http.stream(url).use { parse(it, wantedIds, wantedNames) }
        save()
    }

    private fun parse(input: InputStream, wantedIds: Set<String>, wantedNames: Set<String>) {
        val now = System.currentTimeMillis()
        val from = now - 2 * 3600_000L
        val to = now + 26 * 3600_000L
        val fmt = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
        val fmtUtc = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        fun time(s: String?): Long {
            if (s.isNullOrBlank()) return 0
            val t = s.trim()
            return runCatching { fmt.parse(t)!!.time }.getOrElse {
                runCatching { fmtUtc.parse(t.take(14))!!.time }.getOrDefault(0)
            }
        }

        val nameToId = HashMap<String, String>()
        val keep = HashSet<String>(wantedIds)
        val result = HashMap<String, ArrayList<Programme>>()

        val xp = Xml.newPullParser()
        xp.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        xp.setInput(input, null)

        var chanId: String? = null
        var pChan: String? = null
        var pStart = 0L
        var pStop = 0L
        var title = ""
        var desc = ""
        var ev = xp.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (xp.name) {
                    "channel" -> chanId = xp.getAttributeValue(null, "id")
                    "display-name" -> if (chanId != null) {
                        val key = Norm.key(xp.nextText())
                        if (key.isNotEmpty()) {
                            nameToId.putIfAbsent(key, chanId!!)
                            if (key in wantedNames) keep.add(chanId!!)
                        }
                    }
                    "programme" -> {
                        pChan = xp.getAttributeValue(null, "channel")
                        pStart = time(xp.getAttributeValue(null, "start"))
                        pStop = time(xp.getAttributeValue(null, "stop"))
                        title = ""; desc = ""
                    }
                    "title" -> if (pChan != null && title.isEmpty()) title = xp.nextText().trim()
                    "desc" -> if (pChan != null && desc.isEmpty()) desc = xp.nextText().trim()
                }
            } else if (ev == XmlPullParser.END_TAG) {
                when (xp.name) {
                    "channel" -> chanId = null
                    "programme" -> {
                        val c = pChan
                        if (c != null && c in keep && pStop > from && pStart < to && pStop > pStart) {
                            result.getOrPut(c) { ArrayList() }.add(Programme(pStart, pStop, title, desc))
                        }
                        pChan = null
                    }
                }
            }
            ev = xp.next()
        }
        for (l in result.values) l.sortBy { it.start }
        names = nameToId.filterValues { it in result.keys }
        progs = result
    }
}
