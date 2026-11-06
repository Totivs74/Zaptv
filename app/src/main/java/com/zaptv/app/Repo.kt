package com.zaptv.app

import java.io.File
import java.net.URLEncoder
import java.util.concurrent.Executors

class Repo(private val app: App) {
    private val prefs get() = app.prefs
    private val listFile = File(app.filesDir, "channels.tsv")
    val pastedFile = File(app.filesDir, "pasted.m3u")
    val epg = EpgStore(app)

    /** Background work: playlist / guide downloads, MAC portal links. */
    val io = Executors.newFixedThreadPool(2)

    @Volatile var channels: List<Channel> = emptyList()
        private set
    @Volatile var busy = false
        private set
    private var stalker: Stalker? = null
    private var playlistEpg = ""

    companion object {
        const val RECENT = "Recent"
        const val FAV = "Favourites"
        const val ALL = "All"
    }

    // ---------- Groups ----------

    fun groups(): List<String> {
        val hidden = prefs.hiddenGroups
        val seen = LinkedHashSet<String>()
        for (c in channels) if (c.group !in hidden) seen.add(c.group)
        return seen.toList()
    }

    fun allGroups(): List<String> = channels.map { it.group }.distinct()

    fun tabs(): List<String> = listOf(RECENT, FAV) + groups() + listOf(ALL)

    fun channelsFor(group: String): List<Channel> {
        val hidden = prefs.hiddenGroups
        return when (group) {
            RECENT -> {
                val byKey = channels.associateBy { it.key }
                prefs.recent.mapNotNull { byKey[it] }
            }
            FAV -> { val f = prefs.favourites; channels.filter { it.key in f } }
            ALL -> channels.filter { it.group !in hidden }
            else -> channels.filter { it.group == group }
        }
    }

    fun byKey(key: String): Channel? = if (key.isEmpty()) null else channels.firstOrNull { it.key == key }
    fun byNumber(n: Int): Channel? = channels.firstOrNull { it.number == n }

    fun isFav(c: Channel) = c.key in prefs.favourites
    fun toggleFav(c: Channel): Boolean {
        val f = prefs.favourites.toMutableSet()
        val added = if (c.key in f) { f.remove(c.key); false } else { f.add(c.key); true }
        prefs.favourites = f
        return added
    }

    // ---------- Cache ----------

    fun loadCached() {
        if (listFile.exists()) {
            val list = ArrayList<Channel>()
            listFile.forEachLine { line ->
                val f = line.split('\t')
                if (f.size >= 7) list.add(Channel(f[0], f[1], f[2], f[3], f[4], f[5], f[6].toIntOrNull() ?: 0))
            }
            channels = list
        }
        epg.loadCache()
    }

    private fun saveCache(list: List<Channel>) {
        fun c(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
        val tmp = File(app.filesDir, "channels.tmp")
        tmp.bufferedWriter().use { w ->
            for (ch in list) {
                w.write(listOf(c(ch.name), c(ch.group), c(ch.logo), c(ch.url), c(ch.tvgId), c(ch.cmd), ch.number.toString()).joinToString("\t"))
                w.write("\n")
            }
        }
        tmp.renameTo(listFile)
    }

    fun refreshDue(): Boolean = prefs.sourceType.isNotEmpty() &&
        System.currentTimeMillis() - prefs.lastRefresh > 24 * 3600_000L

    // ---------- Refresh ----------

    /** Reloads playlist then guide in the background. [onList] fires as soon as channels are ready. */
    fun refresh(onList: (String?) -> Unit, onEpg: () -> Unit = {}) {
        if (busy) return
        busy = true
        io.execute {
            var error: String? = null
            try {
                val list = fetchChannels()
                if (list.isEmpty()) throw IllegalStateException("The playlist has no channels")
                list.forEachIndexed { i, ch -> if (ch.number == 0) ch.number = i + 1 }
                channels = list
                saveCache(list)
                prefs.lastRefresh = System.currentTimeMillis()
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }
            app.main.post { onList(error) }
            if (error == null) {
                val url = epgUrl()
                if (url.isNotEmpty()) {
                    runCatching { epg.update(url, channels) }
                    prefs.lastEpgRefresh = System.currentTimeMillis()
                }
                app.main.post { onEpg() }
            }
            busy = false
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun xtBase(): String {
        var s = prefs.xtServer.trim().trimEnd('/')
        if (!s.startsWith("http")) s = "http://$s"
        return s
    }

    private fun fetchChannels(): List<Channel> = when (prefs.sourceType) {
        "m3u" -> {
            val text = if (prefs.m3uUrl.isNotBlank()) Http.text(prefs.m3uUrl.trim())
            else pastedFile.takeIf { it.exists() }?.readText() ?: ""
            val r = M3u.parse(text)
            playlistEpg = r.epgUrl
            r.channels
        }
        "xtream" -> {
            val url = "${xtBase()}/get.php?username=${enc(prefs.xtUser)}&password=${enc(prefs.xtPass)}&type=m3u_plus&output=ts"
            val r = M3u.parse(Http.text(url))
            playlistEpg = r.epgUrl
            r.channels
        }
        "stalker" -> {
            val s = Stalker(prefs.portal, prefs.mac)
            stalker = s
            s.channels()
        }
        else -> emptyList()
    }

    fun epgUrl(): String {
        if (prefs.epgUrl.isNotBlank()) return prefs.epgUrl.trim()
        if (playlistEpg.isNotBlank()) return playlistEpg
        if (prefs.sourceType == "xtream") return "${xtBase()}/xmltv.php?username=${enc(prefs.xtUser)}&password=${enc(prefs.xtPass)}"
        return ""
    }

    /** Returns a playable URL. For MAC portals this asks the portal for a fresh link (blocking). */
    fun resolve(ch: Channel): String {
        if (ch.cmd.isEmpty()) return ch.url
        val s = stalker ?: Stalker(prefs.portal, prefs.mac).also { stalker = it }
        return s.link(ch.cmd)
    }

    fun sourceLabel(): String = when (prefs.sourceType) {
        "m3u" -> if (prefs.m3uUrl.isNotBlank()) "M3U link" else "Pasted M3U"
        "xtream" -> "Xtream Codes"
        "stalker" -> "MAC portal"
        else -> "Not set"
    }
}
