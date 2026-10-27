package com.pusula.browser

import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject

/** Yeni sekme sayfasının okuduğu bilgiler. Yalnızca kendi sayfamız yüklüyken cevap verir. */
class PusulaBridge(private val prefs: Prefs) {
    @Volatile var trusted = false

    private fun hex(v: Int): String = "#%06X".format(0xFFFFFF and v)

    private fun pinsJson(): Any {
        val raw = prefs.pins.trim()
        // Hiç kayıt yok → null (newtab varsayılan gösterir)
        // "[]" → boş dizi (kullanıcı hepsini silmiş, varsayılan geri gelmesin)
        if (raw.isEmpty()) return JSONObject.NULL
        return try { JSONArray(raw) } catch (e: Exception) { JSONObject.NULL }
    }

    @JavascriptInterface
    fun savePins(json: String) {
        // Kayıt her zaman yazılsın (yeni sekme silinmesin); config hâlâ trusted ister
        try {
            prefs.pins = JSONArray(json).toString()
        } catch (e: Exception) { }
    }

    @JavascriptInterface
    fun config(): String {
        if (!trusted) return "{}"
        val e = Engines.get(prefs.engine)
        val th = Themes.get(prefs.theme)
        val bm = JSONArray()
        for (b in prefs.bookmarks().take(8)) bm.put(JSONObject().put("t", b.title).put("u", b.url))
        val hs = JSONArray()
        for (h in prefs.history().take(6)) hs.put(JSONObject().put("t", h.title).put("u", h.url))
        return JSONObject()
            .put("engine", e.name)
            .put("search", e.search)
            .put("bookmarks", bm)
            .put("history", hs)
            .put("pins", pinsJson())
            .put("dark", th.dark)
            .put("bg", hex(th.bg))
            .put("surface", hex(th.surface))
            .put("field", hex(th.field))
            .put("text", hex(th.text))
            .put("sub", hex(th.sub))
            .put("accent", hex(th.accent))
            .put("accent2", hex(th.accent2))
            .put("accent3", hex(th.accent3))
            .put("wall", th.wall)
            .toString()
    }
}
