package com.pusula.browser

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

object Engines {
    class E(val id: String, val name: String, val search: String, val home: String)
    val all = listOf(
        E("google", "Google", "https://www.google.com/search?q=%s", "https://www.google.com/"),
        E("ddg", "DuckDuckGo", "https://duckduckgo.com/?q=%s", "https://duckduckgo.com/"),
        E("bing", "Bing", "https://www.bing.com/search?q=%s", "https://www.bing.com/")
    )
    fun get(id: String): E = all.firstOrNull { it.id == id } ?: all[0]
}

val HOST_LIKE = Regex("^([a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,}([:/?#].*)?$|^(\\d{1,3}\\.){3}\\d{1,3}([:/?#].*)?$|^localhost([:/?#].*)?$")

fun resolveInput(raw: String, engine: Engines.E): String {
    val t = raw.trim()
    if (t.isEmpty()) return engine.home
    if (t.startsWith("http://", true) || t.startsWith("https://", true)) return t
    if (!t.contains(' ') && HOST_LIKE.matches(t)) return "https://" + t
    return engine.search.replace("%s", Uri.encode(t))
}

class Prefs(context: Context) {
    class Entry(val title: String, val url: String)
    private val sp = context.getSharedPreferences("pusula", Context.MODE_PRIVATE)

    var engine: String get() = sp.getString("engine", "google") ?: "google"
      set(v) { sp.edit().putString("engine", v).apply() }
    var bgPlay: Boolean get() = sp.getBoolean("bgPlay", true)
      set(v) { sp.edit().putBoolean("bgPlay", v).apply() }
    var theme: String get() = sp.getString("theme", "lavanta") ?: "lavanta"
      set(v) { sp.edit().putString("theme", v).apply() }
    var autoHideBar: Boolean get() = sp.getBoolean("autoHideBar", true)
      set(v) { sp.edit().putBoolean("autoHideBar", v).apply() }
    var barBottom: Boolean get() = sp.getBoolean("barBottom", true)
      set(v) { sp.edit().putBoolean("barBottom", v).apply() }
    var pins: String get() = sp.getString("pins", "") ?: ""
      // commit: yeni sekme açılınca kayıt kaybolmasın
      set(v) { sp.edit().putString("pins", v).commit() }

    /** Android Auto: YT Music sayfasından çekilen listeler/şarkılar JSON */
    var autoCatalog: String
        get() = sp.getString("autoCatalog", "") ?: ""
        set(v) { sp.edit().putString("autoCatalog", v).apply() }
    var askedNotif: Boolean get() = sp.getBoolean("askedNotif", false)
      set(v) { sp.edit().putBoolean("askedNotif", v).apply() }
    var adBlock: Boolean get() = sp.getBoolean("adBlock", true)
      set(v) { sp.edit().putBoolean("adBlock", v).apply() }
    /** home | resume | blank */
    var startMode: String get() = sp.getString("startMode", "resume") ?: "resume"
      set(v) { sp.edit().putString("startMode", v).apply() }
    var clearOnExit: Boolean get() = sp.getBoolean("clearOnExit", false)
      set(v) { sp.edit().putBoolean("clearOnExit", v).apply() }
    var pipSeek: Boolean get() = sp.getBoolean("pipSeek", true)
      set(v) { sp.edit().putBoolean("pipSeek", v).apply() }

    private fun readList(key: String): MutableList<Entry> {
        val out = ArrayList<Entry>()
        try {
            val arr = JSONArray(sp.getString(key, "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Entry(o.optString("t"), o.optString("u")))
            }
        } catch (_: Exception) {}
        return out
    }
    private fun writeList(key: String, list: List<Entry>) {
        val arr = JSONArray()
        for (e in list) arr.put(JSONObject().put("t", e.title).put("u", e.url))
        sp.edit().putString(key, arr.toString()).apply()
    }
    fun bookmarks(): List<Entry> = readList("bookmarks")
    fun isBookmarked(url: String): Boolean = bookmarks().any { it.url == url }
    fun toggleBookmark(title: String, url: String): Boolean {
        val list = readList("bookmarks")
        val had = list.removeAll { it.url == url }
        if (!had) list.add(0, Entry(title, url))
        writeList("bookmarks", list)
        return !had
    }
    fun removeBookmark(url: String) { val l = readList("bookmarks"); l.removeAll { it.url == url }; writeList("bookmarks", l) }
    fun history(): List<Entry> = readList("history")
    fun addHistory(title: String, url: String) {
        val l = readList("history")
        if (l.isNotEmpty() && l[0].url == url) return
        l.add(0, Entry(title, url))
        while (l.size > 300) l.removeAt(l.size - 1)
        writeList("history", l)
    }
    fun removeHistory(url: String) { val l = readList("history"); l.removeAll { it.url == url }; writeList("history", l) }
    fun clearHistory() { sp.edit().putString("history", "[]").apply() }

    /** İndirilen dosya kaydı (uygulama içi liste için). */
    class DownloadEntry(val title: String, val url: String, val path: String, val mime: String, val time: Long)

    fun downloads(): List<DownloadEntry> {
        val out = ArrayList<DownloadEntry>()
        try {
            val arr = JSONArray(sp.getString("downloads", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(DownloadEntry(
                    o.optString("t"),
                    o.optString("u"),
                    o.optString("p"),
                    o.optString("m"),
                    o.optLong("ts", 0L)
                ))
            }
        } catch (_: Exception) {}
        return out
    }

    fun addDownload(title: String, url: String, path: String, mime: String) {
        val list = downloads().toMutableList()
        // Aynı dosya varsa üste taşı
        list.removeAll { it.path == path || (it.url == url && it.title == title) }
        list.add(0, DownloadEntry(title, url, path, mime, System.currentTimeMillis()))
        while (list.size > 200) list.removeAt(list.size - 1)
        val arr = JSONArray()
        for (e in list) {
            arr.put(JSONObject()
                .put("t", e.title)
                .put("u", e.url)
                .put("p", e.path)
                .put("m", e.mime)
                .put("ts", e.time))
        }
        sp.edit().putString("downloads", arr.toString()).apply()
    }

    fun removeDownload(path: String) {
        val list = downloads().toMutableList()
        list.removeAll { it.path == path }
        val arr = JSONArray()
        for (e in list) {
            arr.put(JSONObject()
                .put("t", e.title)
                .put("u", e.url)
                .put("p", e.path)
                .put("m", e.mime)
                .put("ts", e.time))
        }
        sp.edit().putString("downloads", arr.toString()).apply()
    }

    fun clearDownloads() { sp.edit().putString("downloads", "[]").apply() }

    fun saveTabs(urls: List<String>, current: Int) {
        val arr = JSONArray()
        for (u in urls) arr.put(u)
        sp.edit().putString("tabs", arr.toString()).putInt("tabCur", current).apply()
    }
    fun savedTabs(): List<String> {
        val out = ArrayList<String>()
        try {
            val arr = JSONArray(sp.getString("tabs", "[]") ?: "[]")
            for (i in 0 until arr.length()) out.add(arr.getString(i))
        } catch (_: Exception) {}
        return out
    }
    fun savedCurrent(): Int = sp.getInt("tabCur", 0)
}
