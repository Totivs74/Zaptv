package com.zaptv.app

import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class Repo(private val app: App) {
    private val prefs get() = app.prefs
    val playlists = PlaylistStore(app, app.prefs)
    val epg = EpgStore(app)
    val weather = WeatherStore(app)

    /** Background work: playlist / guide downloads, MAC portal links. */
    val io = Executors.newFixedThreadPool(2)

    /** Channels of every playlist that is on, in playlist order. */
    @Volatile var channels: List<Channel> = emptyList()
        private set
    @Volatile var busy = false
        private set
    private val stalkers = ConcurrentHashMap<String, Stalker>()
    private val playlistEpg = ConcurrentHashMap<String, String>()

    companion object {
        const val RECENT = "Recent"
        const val FAV = "Favourites"
        const val WEATHER = "Weather"
    const val APPS = "Apps"
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

    fun tabs(): List<String> {
        val t = arrayListOf(RECENT, FAV)
        t.addAll(groups())
        if (weather.cities().isNotEmpty()) t.add(WEATHER)
        if (app.appsRow.isNotEmpty()) t.add(APPS)
        t.add(ALL)
        return t
    }

    fun channelsFor(group: String): List<Channel> {
        val hidden = prefs.hiddenGroups
        return when (group) {
            RECENT -> {
                val byKey = channels.associateBy { it.key }
                prefs.recent.mapNotNull { byKey[it] }
            }
            FAV -> { val f = prefs.favourites; channels.filter { it.key in f } }
            WEATHER -> weather.cityChannels()
            APPS -> app.appsRow.map { Channel(name = it.label, group = APPS, logo = "", url = "app:" + it.pkg, tvgId = "") }
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

    fun hasPlaylists(): Boolean = playlists.all().isNotEmpty()

    // ---------- Cache ----------

    private fun readCache(p: Playlist): List<Channel> {
        val f = playlists.cacheFile(p.id)
        if (!f.exists()) return emptyList()
        val list = ArrayList<Channel>()
        f.forEachLine { line ->
            val x = line.split('\t')
            if (x.size >= 7) list.add(Channel(x[0], x[1], x[2], x[3], x[4], x[5], x[6].toIntOrNull() ?: 0, pl = p.id))
        }
        return list
    }

    private fun writeCache(p: Playlist, list: List<Channel>) {
        fun c(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
        val f = playlists.cacheFile(p.id)
        val tmp = java.io.File(f.parentFile, f.name + ".tmp")
        tmp.bufferedWriter().use { w ->
            for (ch in list) {
                w.write(listOf(c(ch.name), c(ch.group), c(ch.logo), c(ch.url), c(ch.tvgId), c(ch.cmd), ch.number.toString()).joinToString("\t"))
                w.write("\n")
            }
        }
        tmp.renameTo(f)
    }

    /** Rebuilds the combined list from the caches of the playlists that are on. Fast, no network. */
    fun rebuild() {
        val out = ArrayList<Channel>()
        for (p in playlists.active()) out.addAll(readCache(p))
        out.forEachIndexed { i, ch -> ch.number = i + 1 }
        channels = out
    }

    fun loadCached() {
        rebuild()
        epg.loadCache()
        weather.loadCache()
    }

    fun refreshDue(): Boolean = playlists.active().any { System.currentTimeMillis() - it.lastRefresh > 24 * 3600_000L }

    /** Playlists that are on but have never been downloaded. */
    fun missingCache(): Boolean = playlists.active().any { !playlists.cacheFile(it.id).exists() }

    // ---------- Refresh ----------

    /**
     * Downloads every playlist that is on (or only [onlyId]), then the guides.
     * [onList] fires as soon as the channel list is ready; errors name the playlist that failed.
     */
    fun refresh(onList: (String?) -> Unit, onEpg: () -> Unit = {}, onlyId: String? = null) {
        if (busy) return
        busy = true
        io.execute {
            val errors = ArrayList<String>()
            for (p in playlists.active()) {
                if (onlyId != null && p.id != onlyId) continue
                try {
                    val list = fetch(p)
                    if (list.isEmpty()) throw IllegalStateException("no channels")
                    writeCache(p, list)
                    playlists.touch(p.id)
                } catch (e: Exception) {
                    errors.add("${p.name}: ${e.message ?: e.javaClass.simpleName}")
                }
            }
            rebuild()
            val err = if (errors.isEmpty()) null else errors.joinToString("; ")
            app.main.post { onList(if (channels.isEmpty()) (err ?: "No channels") else err) }
            val urls = playlists.active().mapNotNull { epgUrl(it).ifEmpty { null } }.distinct()
            if (urls.isNotEmpty()) {
                runCatching { epg.update(urls, channels) }
                prefs.lastEpgRefresh = System.currentTimeMillis()
            }
            app.main.post { onEpg() }
            busy = false
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun xtBase(p: Playlist): String {
        var s = p.xtServer.trim().trimEnd('/')
        if (!s.startsWith("http")) s = "http://$s"
        return s
    }

    private fun fetch(p: Playlist): List<Channel> {
        val raw: List<Channel> = when (p.type) {
            "m3u" -> {
                val text = if (p.url.isNotBlank()) Http.text(p.url.trim())
                else playlists.pastedFile(p.id).takeIf { it.exists() }?.readText() ?: ""
                val r = M3u.parse(text)
                playlistEpg[p.id] = r.epgUrl
                r.channels
            }
            "xtream" -> {
                val url = "${xtBase(p)}/get.php?username=${enc(p.xtUser)}&password=${enc(p.xtPass)}&type=m3u_plus&output=ts"
                val r = M3u.parse(Http.text(url))
                playlistEpg[p.id] = r.epgUrl
                r.channels
            }
            "stalker" -> {
                val s = Stalker(p.portal, p.mac)
                stalkers[p.id] = s
                s.channels()
            }
            else -> emptyList()
        }
        return raw.map { it.copy(pl = p.id) }
    }

    private fun epgUrl(p: Playlist): String {
        if (p.epgUrl.isNotBlank()) return p.epgUrl.trim()
        playlistEpg[p.id]?.let { if (it.isNotBlank()) return it }
        if (p.type == "xtream") return "${xtBase(p)}/xmltv.php?username=${enc(p.xtUser)}&password=${enc(p.xtPass)}"
        return ""
    }

    /** Returns a playable URL. For MAC portals this asks the portal for a fresh link (blocking). */
    fun resolve(ch: Channel): String {
        if (ch.cmd.isEmpty()) return ch.url
        val p = playlists.all().firstOrNull { it.id == ch.pl } ?: return ch.url
        val s = stalkers[p.id] ?: Stalker(p.portal, p.mac).also { stalkers[p.id] = it }
        return s.link(ch.cmd)
    }

    fun sourceLabel(): String {
        val all = playlists.all()
        if (all.isEmpty()) return "None yet"
        return "${all.count { it.on }} of ${all.size} on"
    }
}
