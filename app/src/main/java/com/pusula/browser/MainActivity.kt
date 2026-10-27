package com.pusula.browser

import android.annotation.SuppressLint
import android.app.Dialog
import android.app.PendingIntent
import android.app.DownloadManager
import android.graphics.Matrix
import android.view.Window
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.view.MotionEvent
import android.text.Html
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JsResult
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.widget.doAfterTextChanged
import androidx.appcompat.widget.SwitchCompat
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream

class MainActivity : AppCompatActivity() {

    private class BTab(val web: WebView, val bridge: PusulaBridge, val incognito: Boolean = false) {
        var title: String = "Yeni Sekme"
        var url: String = ""
        var pendingUrl: String? = null
        var pendingState: Bundle? = null
        var desktop: Boolean = false
        var mobileUa: String = ""
        var spoofUa: Boolean = false
        var appMode: Boolean = false
        var silent: Boolean = false
        var progress: Int = 100
        var preview: Bitmap? = null
        var adScript: ScriptHandler? = null
        var keepScript: ScriptHandler? = null
        var hookScript: ScriptHandler? = null
        var capScript: ScriptHandler? = null
        var openScript: ScriptHandler? = null
        var errorUrl: String? = null
        var errorLoading: Boolean = false
        var networkRetryUsed: Boolean = false
        var fromPopup: Boolean = false
    }

    private class SheetItem(val icon: String, val label: String, val on: Boolean, val act: () -> Unit)

    /** Arka plana atılınca WebView'a "hâlâ görünüyorum" dedirtir; YouTube sesi kesilmez. */
    private class BgWebView(c: Context) : WebView(c) {
        override fun onWindowVisibilityChanged(visibility: Int) {
            if (visibility != View.GONE) super.onWindowVisibilityChanged(View.VISIBLE)
        }
    }

    private val tabs = ArrayList<BTab>()
    private var cur = -1
    /** Ayarlar/indirilenler vb. diyalog açıkken otomatik PiP yapma */
    private var uiOverlayCount = 0
    private var shown: WebView? = null
    private var miniTab: BTab? = null
    private var miniOverlay: FrameLayout? = null
    private var miniPlayBtn: TextView? = null
    private var mdOld: BTab? = null
    private var mdPeek: BTab? = null
    private var mdClipH = 0
    private var mdTop = 0
    private var miniH = 0
    private var miniS = 1f
    private var miniTx = 0f
    private var miniTxR = 0f
    private var miniTxL = 0f
    private var miniTy = 1f
    private var mdLastDy = 0f
    private var mdLastT = 0L
    private var mdVel = 0f
    private var pipFromMini = false
    private var miniCy = 0f
    private var miniTyT = 0f
    private var mdBlur: android.widget.ImageView? = null
    private var mdBack = "https://m.youtube.com/"

    private lateinit var prefs: Prefs
    private lateinit var loader: WebViewAssetLoader
    private val handler = Handler(Looper.getMainLooper())
    private var appInBackground = false

    private var hasMedia = false
    private var lastPlaying = false
    private var mediaDismissedUntil = 0L
    private var lastTitle = ""
    private var lastArtist = ""
    private var lastArtUrl = ""
    private var sentPos = 0L
    private var sentAt = 0L
    private var sentPlaying = false
    private var lastDur = -1L
    /** Bildirim / PiP düğmelerinin bağlı olduğu gerçek medya sekmesi (YouTube veya YouTube Music). */
    private var mediaSrc: BTab? = null
    private fun mediaTarget(): BTab? =
        miniTab ?: mediaSrc?.takeIf { s -> tabs.any { it === s } } ?: tabs.getOrNull(cur)
    private lateinit var dockBar: LinearLayout
    private val roleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { afterRole() }
    private val notifLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val mediaStatePoll = object : Runnable {
        override fun run() {
            pollMedia()
            // Auto kaydırma çubuğu için konum daha sık güncellenir
            handler.postDelayed(this, 1000L)
        }
    }

    /** Başlık ve sanatçıdan "YouTube Music" yazısını temizler. */
    private fun cleanMeta(s: String): String {
        var r = s
        r = Regex("\\s*[-–—|•·]\\s*YouTube Music\\b", RegexOption.IGNORE_CASE).replace(r, "")
        r = Regex("\\bYouTube Music\\s*[-–—|•·]\\s*", RegexOption.IGNORE_CASE).replace(r, "")
        r = Regex("\\bYouTube Music\\b", RegexOption.IGNORE_CASE).replace(r, "")
        return r.trim()
    }

    private var catalogPollTick = 0

    private fun pollMedia() {
        // Tüm medya sekmeleri sorgulanır; çalan sekme kaynak seçilir (YouTube / YT Music çakışması olmasın)
        fun isMediaUrl(u: String): Boolean {
            val x = u.lowercase()
            return x.contains("youtube.com") || x.contains("youtu.be") || x.contains("music.youtube")
        }
        val cands = ArrayList<BTab>()
        miniTab?.let { cands.add(it) }
        tabs.getOrNull(cur)?.let { c0 -> if (cands.none { c -> c === c0 }) cands.add(c0) }
        for (t in tabs) if (isMediaUrl(t.url) && cands.none { c -> c === t }) cands.add(t)
        if (cands.isEmpty()) return
        val results = arrayOfNulls<String>(cands.size)
        var left = cands.size
        for ((i, t) in cands.withIndex()) {
            try {
                t.web.evaluateJavascript(MEDIA_STATE_JS) { raw ->
                    results[i] = raw
                    left--
                    if (left == 0) pickMediaSource(cands, results)
                }
            } catch (_: Exception) {
                left--
                if (left == 0) pickMediaSource(cands, results)
            }
        }
        catalogPollTick++
        if (catalogPollTick % 8 == 0) {
            try {
                val musicTab = tabs.firstOrNull { it.url.contains("music.youtube.com") }
                if (musicTab != null) {
                    val u = musicTab.web.url ?: ""
                    if (u.contains("music.youtube.com")) {
                        musicTab.web.evaluateJavascript(YT_MUSIC_CATALOG_JS) { raw -> onMusicCatalog(raw) }
                    }
                }
            } catch (_: Exception) { }
        }
    }

    /** Sonuçlardan gerçek medya kaynağını seçer: önce bakılan sekme çalıyorsa o, sonra önceki kaynak, sonra çalan herhangi biri. */
    private fun pickMediaSource(cands: List<BTab>, res: Array<String?>) {
        fun has(r: String?): Boolean = r != null && r != "null" && r != "\"\"" && r.length > 2
        fun playing(r: String?): Boolean = has(r) && r!!.contains("\\\"p\\\":true")
        val cur0 = tabs.getOrNull(cur)
        val idxs = cands.indices
        val pick: Int? =
            idxs.firstOrNull { playing(res[it]) && cands[it] === cur0 }
            ?: idxs.firstOrNull { playing(res[it]) && cands[it] === mediaSrc }
            ?: idxs.firstOrNull { playing(res[it]) }
            ?: idxs.firstOrNull { has(res[it]) && cands[it] === mediaSrc }
            ?: idxs.firstOrNull { has(res[it]) }
        if (pick == null) { onMediaState(""); return }
        mediaSrc = cands[pick]
        onMediaState(res[pick])
    }

    private fun onMediaState(raw: String?) {
        var has = false
        var playing = false
        var title = ""
        var artist = ""
        var artUrl = ""
        var dur = -1L
        var pos = 0L
        try {
            if (raw != null && raw != "null" && raw != "\"\"") {
                val inner = JSONArray("[" + raw + "]").getString(0)
                if (inner.isNotEmpty()) {
                    val o = JSONObject(inner)
                    has = true
                    playing = o.optBoolean("p")
                    title = o.optString("t")
                    artist = o.optString("a")
                    dur = o.optLong("d", -1L)
                    pos = o.optLong("c", 0L)
                    artUrl = o.optString("art")
                }
            }
        } catch (_: Exception) { return }
        title = cleanMeta(title); artist = cleanMeta(artist)
        if (android.os.SystemClock.elapsedRealtime() < mediaDismissedUntil) return
        if (!has) {
            if (hasMedia) { hasMedia = false; lastPlaying = false; PlaybackService.stop(this) }
            return
        }
        val now = android.os.SystemClock.elapsedRealtime()
        val expected = if (sentPlaying) sentPos + (now - sentAt) else sentPos
        val jumped = hasMedia && Math.abs(pos - expected) > 2500L
        val playChanged = playing != lastPlaying
        val changed = !hasMedia || playChanged || title != lastTitle || artist != lastArtist || artUrl != lastArtUrl || dur != lastDur || jumped
        hasMedia = true; lastPlaying = playing; lastTitle = title; lastArtist = artist; lastArtUrl = artUrl; lastDur = dur
        // Çalarken konumu da gönder (Android Auto / bildirim kaydırma çubuğu)
        if ((changed || playing) && (playing || PlaybackService.running)) {
            sentPos = pos; sentAt = now; sentPlaying = playing
            PlaybackService.update(this, playing, title, artist, isInPictureInPictureMode, pos, dur, artUrl)
        }
        // PiP düğme simgesi oynat/duraklat durumuna göre güncellensin
        if (playChanged && isInPictureInPictureMode) updatePipActions()
    }

fun onMusicCatalog(raw: String?) {
        try {
            if (raw.isNullOrBlank() || raw == "null") return
            val inner = try { JSONArray("[$raw]").getString(0) } catch (_: Exception) { raw.trim().trim('"') }
            if (inner.isBlank() || inner == "{}") return
            val o = JSONObject(inner)
            val pl = o.optJSONArray("playlists") ?: JSONArray()
            val sg = o.optJSONArray("songs") ?: JSONArray()
            if (pl.length() == 0 && sg.length() == 0) return
            // Öncekinden daha zenginse veya eşit değilse kaydet
            val prev = prefs.autoCatalog
            if (inner == prev) return
            var prevPl = 0; var prevSg = 0
            try {
                val po = JSONObject(prev)
                prevPl = po.optJSONArray("playlists")?.length() ?: 0
                prevSg = po.optJSONArray("songs")?.length() ?: 0
            } catch (_: Exception) { }
            // Daha az sonuçla ezme (geçici boş tarama)
            if (prev.isNotBlank() && pl.length() + sg.length() < prevPl + prevSg && pl.length() + sg.length() < 3) return
            prefs.autoCatalog = inner
            RotaMediaBrowserService.notifyCatalogChanged()
        } catch (_: Exception) { }
    }


    private fun seekRelJs(d: Int): String =
        "(function(){try{var l=Array.from(document.querySelectorAll('video,audio'));" +
        "var v=l.find(function(m){return !m.paused&&!m.ended;})||l[0];" +
        "if(v){var t=v.currentTime+(" + d + ");var mx=isFinite(v.duration)?v.duration:1e9;v.currentTime=Math.max(0,Math.min(mx,t));}" +
        "if(window.__pipFlash)window.__pipFlash();}catch(e){}})();"

    private fun pipAction(code: Int, icon: Int, label: String, action: String): android.app.RemoteAction {
        val pi = PendingIntent.getBroadcast(
            this, code,
            Intent(action).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return android.app.RemoteAction(android.graphics.drawable.Icon.createWithResource(this, icon), label, label, pi)
    }

    /** YouTube küçült (PiP) — yalnızca menüden. */
    private fun enterPip() {
        if (Build.VERSION.SDK_INT < 26) { toast("Bu Android sürümü küçük pencereyi desteklemiyor"); return }
        if (miniTab != null) {
            expandMiniNow()
            pipFromMini = true
            // Mini ölçeği/kayması PiP'e taşınmasın
            currentWeb()?.let { resetWebLayout(it) }
        }
        // PiP'e geçerken onPause, PiP bayrağı henüz açılmadan çağrılabilir: bu sırada medya durdurulmasın
        pipEntering = true
        handler.postDelayed({ pipEntering = false }, 2500L)
        enterPipWith(android.util.Rational(16, 9))
    }

    private var pipEntering = false

    /** Mini/PiP geçişlerinden sonra WebView kayma-ölçek kalmasın. */
    private fun resetWebLayout(w: WebView) {
        try {
            w.animate().cancel()
            w.scaleX = 1f
            w.scaleY = 1f
            w.translationX = 0f
            w.translationY = 0f
            w.rotation = 0f
            if (w.width > 0) w.pivotX = w.width / 2f
            if (w.height > 0) w.pivotY = w.height / 2f
            w.clipBounds = null
            w.clipToOutline = false
            w.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            w.requestLayout()
        } catch (_: Exception) { }
    }

    private fun enterPipWith(ratio: android.util.Rational) {
        try {
            enterPictureInPictureMode(buildPipParams(ratio))
        } catch (_: Exception) { toast("Küçük pencere açılamadı") }
    }

    private fun buildPipParams(ratio: android.util.Rational = android.util.Rational(16, 9)): android.app.PictureInPictureParams {
        val b = android.app.PictureInPictureParams.Builder().setAspectRatio(ratio)
        val acts = ArrayList<android.app.RemoteAction>()
        acts.add(pipAction(21, R.drawable.ic_pip_prev, "Önceki", PlaybackService.ACTION_PREVIOUS))
        // Oynuyorsa duraklat ikonu, duruyorsa oynat ikonu
        val midIcon = if (lastPlaying) R.drawable.ic_pip_pause else R.drawable.ic_pip_play
        val midLabel = if (lastPlaying) "Duraklat" else "Oynat"
        acts.add(pipAction(22, midIcon, midLabel, if (lastPlaying) PlaybackService.ACTION_PAUSE else PlaybackService.ACTION_PLAY))
        acts.add(pipAction(23, R.drawable.ic_pip_next, "Sonraki", PlaybackService.ACTION_NEXT))
        b.setActions(acts)
        return b.build()
    }

    private fun updatePipActions() {
        if (Build.VERSION.SDK_INT < 26 || !isInPictureInPictureMode) return
        try { setPictureInPictureParams(buildPipParams()) } catch (_: Exception) { }
    }

    private fun onPipChanged(pip: Boolean) {
        topBar.visibility = if (pip || barHidden) View.GONE else View.VISIBLE
        root.setBackgroundColor(if (pip) Color.BLACK else cBg)
        content.setBackgroundColor(if (pip) Color.BLACK else cBg)
        dockBar.visibility = if (pip) View.GONE else View.VISIBLE
        if (pip) {
            closeFind()
            appInBackground = false
            handler.removeCallbacks(backgroundMediaPump)
            try { shown?.onResume() } catch (_: Exception) { }
            if (customView == null) {
                try { currentWeb()?.evaluateJavascript(PIP_ON_JS, null) } catch (_: Exception) { }
            }
            updatePipActions()
            if (hasMedia) PlaybackService.update(this, lastPlaying, lastTitle, lastArtist, true, -1L, -2L, lastArtUrl)
        } else {
            // PiP ✕ ile kapandıysa (uygulama ekranda değil) hiçbir şeye dokunma; büyütüldüyse temizle
            pipExitPending = true
            handler.postDelayed({
                if (pipExitPending && isResumedNow && !isInPictureInPictureMode) {
                    pipExitPending = false
                    runPipExit()
                }
            }, 250)
        }
    }

    private var isResumedNow = false
    private var pipExitPending = false

    private fun runPipExit() {
        run {
            // PiP kapandı (büyütme): tek temiz yol
            val playing = lastPlaying
            val wasMini = pipFromMini
            pipFromMini = false

            // 1) WebView kayma/ölçek sıfır
            currentWeb()?.let { resetWebLayout(it) }

            // 2) PiP + mini stilleri bir kez kaldır
            for (t in tabs) {
                try { t.web.evaluateJavascript(PIP_OFF_JS, null) } catch (_: Exception) { }
                try { t.web.evaluateJavascript(YT_MINI_CSS_OFF_JS, null) } catch (_: Exception) { }
            }

            // 3) Layout oturunca bir kez daha sıfırla + recover (oynatmayı bozmadan)
            handler.postDelayed({
                if (isInPictureInPictureMode) return@postDelayed
                currentWeb()?.let { resetWebLayout(it) }
                normalizeView(tabs.getOrNull(cur))
                try { currentWeb()?.evaluateJavascript(PIP_RECOVER_JS, null) } catch (_: Exception) { }
                reapplyInsets()
            }, 250)
            handler.postDelayed({
                if (isInPictureInPictureMode) return@postDelayed
                currentWeb()?.let { resetWebLayout(it) }
                normalizeView(tabs.getOrNull(cur))
            }, 700)

            // 4) PiP ✕ ile kapanınca medya durumuna dokunma: duraklatılmışsa öyle kalır, çalıyorsa çalmaya devam eder
            if (playing && prefs.bgPlay) {
                // Çalıyorsa işareti koru (✕ ile arka plan / büyütünce devam)
                handler.postDelayed({
                    try {
                        currentWeb()?.evaluateJavascript(
                            "(function(){try{document.querySelectorAll('video,audio').forEach(function(m){" +
                            "m.dataset.pusulaWasPlaying='1';});}catch(e){}})();", null)
                    } catch (_: Exception) { }
                }, 300)
            }

            if (hasMedia) {
                PlaybackService.update(this, lastPlaying, lastTitle, lastArtist, false, -1L, -2L, lastArtUrl)
            }
            if (wasMini) {
                handler.postDelayed({ reopenAfterMiniPip(false) }, 200)
            }
            handler.postDelayed({ checkAfterPip(0) }, 1000)
            reapplyInsetsLater()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (isInPictureInPictureMode) return
        val land = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        try {
            if (land) currentWeb()?.evaluateJavascript(LANDSCAPE_VIDEO_JS, null)
            else currentWeb()?.evaluateJavascript(PORTRAIT_VIDEO_JS, null)
        } catch (_: Exception) { }
    }

    private val mediaControlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val act = intent?.action ?: return
            if (act == PlaybackService.ACTION_CLOSE) {
                // Bildirim ✕: tüm medya hemen durur, bildirim geri gelmez
                mediaDismissedUntil = android.os.SystemClock.elapsedRealtime() + 4000L
                hasMedia = false
                lastPlaying = false
                val stopJs = "(function(){try{window.__userPause=true;document.querySelectorAll('video,audio').forEach(function(m){m.dataset.pusulaWasPlaying='';if(!m.paused&&!m.ended)m.pause();});}catch(e){}})();"
                for (t in (tabs.toList() + listOfNotNull(miniTab))) {
                    try { t.web.evaluateJavascript(stopJs, null) } catch (_: Exception) { }
                }
                handler.removeCallbacks(backgroundMediaPump)
                // Bildirimden kapatınca PiP penceresi de kapansın (tersi geçerli değil: PiP ✕ bildirime dokunmaz)
                if (isInPictureInPictureMode) {
                    // PiP ✕ gibi: önce pencereyi doğrudan gizle; olmazsa büyütüp arka plana at
                    try { moveTaskToBack(true) } catch (_: Exception) { }
                    handler.postDelayed({
                        if (isInPictureInPictureMode) {
                            try { (getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).moveTaskToFront(taskId, 0) } catch (_: Exception) { }
                            handler.postDelayed({
                                try { if (!isInPictureInPictureMode) moveTaskToBack(true) } catch (_: Exception) { }
                            }, 600)
                        }
                    }, 450)
                }
                return
            }
            if (act == PlaybackService.ACTION_BACK10 || act == PlaybackService.ACTION_FWD10) {
                val d = if (act == PlaybackService.ACTION_BACK10) -10 else 10
                try { mediaTarget()?.web?.evaluateJavascript(seekRelJs(d), null) } catch (_: Exception) { }
                return
            }
            if (act == PlaybackService.ACTION_SEEK) {
                val sec = intent.getLongExtra("pos", 0L) / 1000.0
                try {
                    mediaTarget()?.web?.evaluateJavascript(
                        "(function(){try{var l=Array.from(document.querySelectorAll('video,audio'));" +
                        "var v=l.find(function(m){return !m.paused&&!m.ended;})||l[0];if(v)v.currentTime=" + sec + ";}catch(e){}})();", null)
                } catch (_: Exception) { }
                return
            }
            if (act == PlaybackService.ACTION_LIKE) {
                val tab = tabs.getOrNull(cur) ?: return
                val url = tab.url
                if (url.startsWith("http")) {
                    val title = tab.title.ifBlank { shownUrl(url) }
                    val added = prefs.toggleBookmark(title, url)
                    toast(if (added) "Favorilere eklendi" else "Favorilerden çıkarıldı")
                }
                return
            }
            if (act == PlaybackService.ACTION_HOME) {
                openTab("https://music.youtube.com/", appMode = true)
                return
            }
            if (act == PlaybackService.ACTION_SEARCH) {
                val q = intent.getStringExtra("q")?.trim().orEmpty()
                if (q.isNotEmpty()) openTab("https://music.youtube.com/search?q=" + Uri.encode(q), appMode = true)
                return
            }
            if (act == PlaybackService.ACTION_OPEN) {
                val u = intent.getStringExtra("url")?.trim().orEmpty()
                if (u.startsWith("http")) openTab(u, appMode = true)
                return
            }
            val js = when (act) {
                // PLAY = zorla oynat (toggle değil — focus/bildirim play deyince stop oluyordu)
                PlaybackService.ACTION_PLAY -> MEDIA_FORCE_PLAY_JS
                PlaybackService.ACTION_TOGGLE -> if (isInPictureInPictureMode) MEDIA_TOGGLE_FAST_JS else MEDIA_TOGGLE_JS
                PlaybackService.ACTION_PAUSE -> if (isInPictureInPictureMode) MEDIA_PAUSE_FAST_JS else MEDIA_PAUSE_JS
                PlaybackService.ACTION_PREVIOUS -> MEDIA_PREVIOUS_JS
                PlaybackService.ACTION_NEXT -> MEDIA_NEXT_JS
                else -> return
            }
            val targets = if (act == PlaybackService.ACTION_PAUSE) (tabs.toList() + listOfNotNull(miniTab))
                          else listOfNotNull(mediaTarget())
            targets.forEach { t ->
                try { t.web.evaluateJavascript(js, null) } catch (_: Exception) { }
            }
            if (act == PlaybackService.ACTION_PLAY || act == PlaybackService.ACTION_TOGGLE || act == PlaybackService.ACTION_PAUSE) {
                lastPlaying = when (act) {
                    PlaybackService.ACTION_PAUSE -> false
                    PlaybackService.ACTION_PLAY -> true
                    else -> !lastPlaying
                }
                if (isInPictureInPictureMode) updatePipActions()
                handler.postDelayed({ pollMedia() }, 250L)
            }
            if (isInPictureInPictureMode) {
                try { tabs.getOrNull(cur)?.web?.evaluateJavascript("if(window.__pipFlash)window.__pipFlash();", null) } catch (_: Exception) { }
            }
        }
    }

    private val backgroundMediaPump = object : Runnable {
        override fun run() {
            if (!appInBackground || !prefs.bgPlay) return
            val t = tabs.getOrNull(cur)
            try { t?.web?.evaluateJavascript(BACKGROUND_MEDIA_JS, null) } catch (_: Exception) { }
            handler.postDelayed(this, 1200L)
        }
    }

    private lateinit var root: LinearLayout
    private lateinit var content: FrameLayout
    private lateinit var fullscreen: FrameLayout
    private lateinit var urlField: EditText
    private lateinit var progress: ProgressBar
    private lateinit var backBtn: ImageButton
    private lateinit var tabCount: TextView
    private lateinit var privateBadge: TextView
    private lateinit var findBar: LinearLayout
    private lateinit var findInput: EditText
    private lateinit var findCount: TextView

    private var dark = false
    private var cBg = 0
    private var cSurface = 0
    private var cField = 0
    private var cText = 0
    private var cSub = 0
    private var cIcon = 0
    private var cAccent = 0
    private var cAccent2 = 0
    private lateinit var topBar: LinearLayout
    private var barHidden = false
    private var barAccum = 0
    private var barIgnoreUntil = 0L

    private fun setBarShown(show: Boolean) {
        // Ana ekran kısayolundan açılan sekmede çubuk hiç görünmez (uygulama gibi tam ekran)
        val hide = !show || tabs.getOrNull(cur)?.appMode == true
        if (barHidden == hide) return
        barHidden = hide
        barAccum = 0
        barIgnoreUntil = android.os.SystemClock.uptimeMillis() + 450L
        topBar.visibility = if (hide) View.GONE else View.VISIBLE
    }

    /** Çubuk, sayfanın bir parçası gibi davranır: kendi yönüne kayınca gizlenir, ters yöne kayınca geri gelir. */
    private fun onPageScroll(tab: BTab, y: Int, oldY: Int) {
        if (tabs.getOrNull(cur) !== tab) return
        if (!prefs.autoHideBar) { setBarShown(true); return }
        if (urlField.hasFocus() || customView != null || isInPictureInPictureMode) return
        if (android.os.SystemClock.uptimeMillis() < barIgnoreUntil) return
        if (!prefs.barBottom && y <= dp(8)) { setBarShown(true); return }
        // Üstteki çubuk sayfayla birlikte yukarı kayar; alttaki çubuk sayfayla birlikte aşağı kayar.
        val dy = if (prefs.barBottom) oldY - y else y - oldY
        if (dy == 0) return
        barAccum = if (barAccum == 0 || (dy > 0) == (barAccum > 0)) barAccum + dy else dy
        if (barAccum > dp(48)) setBarShown(false)
        else if (barAccum < -dp(48)) setBarShown(true)
    }

    private var customView: View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null
    private var fileCb: ValueCallback<Array<Uri>>? = null
    private var startUrl: String? = null
    private var startAppMode = false

    private val fileLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            fileCb?.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(res.resultCode, res.data)
            )
            fileCb = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        loadPalette()

        WindowCompat.setDecorFitsSystemWindows(window, false)
        buildUi()
        val bars = WindowInsetsControllerCompat(window, window.decorView)
        bars.isAppearanceLightStatusBars = !dark
        bars.isAppearanceLightNavigationBars = !dark

        startUrl = intent?.dataString
        startAppMode = intent?.getBooleanExtra("pusula_app", false) == true

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { handleBack() }
        })

        val mediaFilter = IntentFilter().apply {
            addAction(PlaybackService.ACTION_CLOSE)
            addAction(PlaybackService.ACTION_PLAY)
            addAction(PlaybackService.ACTION_TOGGLE)
            addAction(PlaybackService.ACTION_PAUSE)
            addAction(PlaybackService.ACTION_PREVIOUS)
            addAction(PlaybackService.ACTION_NEXT)
            addAction(PlaybackService.ACTION_SEEK)
            addAction(PlaybackService.ACTION_BACK10)
            addAction(PlaybackService.ACTION_FWD10)
            addAction(PlaybackService.ACTION_LIKE)
            addAction(PlaybackService.ACTION_HOME)
            addAction(PlaybackService.ACTION_SEARCH)
            addAction(PlaybackService.ACTION_OPEN)
        }
        ContextCompat.registerReceiver(this, mediaControlReceiver, mediaFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        startBrowsing()
        addOnPictureInPictureModeChangedListener { info -> onPipChanged(info.isInPictureInPictureMode) }
        handler.removeCallbacks(mediaStatePoll)
        handler.postDelayed(mediaStatePoll, 1500L)
        if (Build.VERSION.SDK_INT >= 33 && !prefs.askedNotif &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            prefs.askedNotif = true
            notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !isInPictureInPictureMode) reapplyInsetsLater()
        if (isInPictureInPictureMode) {
            try { currentWeb()?.evaluateJavascript("if(window.__pipFlash)window.__pipFlash();", null) } catch (_: Exception) { }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val u = intent.dataString ?: return
        openTab(u, appMode = intent.getBooleanExtra("pusula_app", false))
    }

    override fun onPause() {
        super.onPause()
        isResumedNow = false
        saveTabs()
        CookieManager.getInstance().flush()
        if (isInPictureInPictureMode || pipEntering) {
            try { shown?.onResume() } catch (_: Exception) { }
            return
        }
        // Ana ekran / PiP ✕: WebView'ı durdurma, arka planda çalsın
        if (prefs.bgPlay) {
            appInBackground = true
            try { shown?.onResume() } catch (_: Exception) { }
            handler.removeCallbacks(backgroundMediaPump)
            handler.post(backgroundMediaPump)
            if (hasMedia && lastPlaying) {
                try {
                    currentWeb()?.evaluateJavascript(
                        "(function(){try{window.__userPause=false;document.querySelectorAll('video,audio').forEach(function(m){" +
                        "m.dataset.pusulaWasPlaying='1';});}catch(e){}})();", null)
                } catch (_: Exception) { }
                PlaybackService.update(this, true, lastTitle, lastArtist, false, -1L, -2L, lastArtUrl)
            } else {
                // Kullanıcı durdurmuşsa arka planda tekrar başlatma
                try {
                    currentWeb()?.evaluateJavascript(
                        "(function(){try{window.__userPause=true;document.querySelectorAll('video,audio').forEach(function(m){" +
                        "m.dataset.pusulaWasPlaying='';});}catch(e){}})();", null)
                } catch (_: Exception) { }
            }
        } else {
            appInBackground = true
            // Arka planda çalma kapalı: YouTube sayfayı gizlenince kendi durur, YT Music durmaz — o yüzden tüm medyayı açıkça duraklat
            val stopJs = "(function(){try{window.__userPause=true;document.querySelectorAll('video,audio').forEach(function(m){m.dataset.pusulaWasPlaying='';if(!m.paused&&!m.ended)m.pause();});}catch(e){}})();"
            for (t in (tabs.toList() + listOfNotNull(miniTab))) {
                try { t.web.evaluateJavascript(stopJs, null) } catch (_: Exception) { }
            }
            lastPlaying = false
            if (hasMedia) PlaybackService.update(this, false, lastTitle, lastArtist, false, -1L, -2L, lastArtUrl)
            // Betiğin çalışması için WebView'ı kısa bir gecikmeyle durdur
            handler.postDelayed({
                if (appInBackground && !isResumedNow && !isInPictureInPictureMode && !pipEntering) {
                    try { shown?.onPause() } catch (_: Exception) { }
                }
            }, 350L)
        }
    }

    override fun onStop() {
        super.onStop()
        // PiP penceresi ✕ ile kapanınca da buraya gelinir (PiP bayrağı hâlâ true olabilir): arka planda çalma yolu çalışsın
        if (prefs.bgPlay) {
            appInBackground = true
            try { shown?.onResume() } catch (_: Exception) { }
            handler.removeCallbacks(backgroundMediaPump)
            handler.post(backgroundMediaPump)
            if (hasMedia && lastPlaying) {
                try {
                    currentWeb()?.evaluateJavascript(
                        "(function(){try{window.__userPause=false;document.querySelectorAll('video,audio').forEach(function(m){" +
                        "m.dataset.pusulaWasPlaying='1';});}catch(e){}})();", null)
                } catch (_: Exception) { }
                PlaybackService.update(this, true, lastTitle, lastArtist, false, -1L, -2L, lastArtUrl)
            }
        }
    }

    private fun pushUiOverlay() { uiOverlayCount++ }
    private fun popUiOverlay() { if (uiOverlayCount > 0) uiOverlayCount-- }
    private fun hasUiOverlay(): Boolean = uiOverlayCount > 0

    /** Ana ekrana basınca YouTube videosu çalıyorsa kendiliğinden küçük pencereye geçer. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // İndirilenler / ayarlar / sekmeler açıkken PiP yapma
        if (hasUiOverlay()) return
        // "Arka planda çalma" kapalıysa küçük pencereye geçme: medya normal yoldan (onPause) durur
        if (!prefs.bgPlay) return
        if (Build.VERSION.SDK_INT >= 26 && !isInPictureInPictureMode && hasMedia && lastPlaying && (miniTab != null || youtubeVideoTab())) {
            enterPip()
        }
    }

    private fun youtubeVideoTab(): Boolean {
        val u = tabs.getOrNull(cur)?.url ?: return false
        val yt = (u.contains("youtube.com") || u.contains("youtu.be")) && !u.contains("music.youtube.com")
        return yt && (u.contains("/watch") || u.contains("youtu.be/") || u.contains("/shorts"))
    }


    override fun onResume() {
        super.onResume()
        isResumedNow = true
        if (pipExitPending && !isInPictureInPictureMode) {
            pipExitPending = false
            handler.postDelayed({ if (!isInPictureInPictureMode) runPipExit() }, 120)
        }
        appInBackground = false
        handler.removeCallbacks(backgroundMediaPump)
        shown?.onResume()
    }

    override fun onDestroy() {
        try { unregisterReceiver(mediaControlReceiver) } catch (_: Exception) { }
        handler.removeCallbacks(backgroundMediaPump)
        handler.removeCallbacks(mediaStatePoll)
        PlaybackService.stop(this)
        if (prefs.clearOnExit) {
            try {
                clearBrowsingData()
                prefs.clearHistory()
                prefs.saveTabs(emptyList(), 0)
                clearTabStates()
            } catch (_: Exception) { }
        }
        try { miniTab?.web?.destroy() } catch (_: Exception) { }
        for (t in tabs) { try { t.web.destroy() } catch (_: Exception) { } }
        super.onDestroy()
    }

    private fun loadPalette() {
        val pal = Themes.get(prefs.theme)
        dark = pal.dark
        cBg = pal.bg; cSurface = pal.surface; cField = pal.field
        cText = pal.text; cSub = pal.sub; cIcon = pal.icon
        cAccent = pal.accent; cAccent2 = pal.accent2
        window.setBackgroundDrawable(ColorDrawable(cBg))
    }

    /** Temayı uygulamadan çıkmadan, sekmeleri koruyarak uygular. */
    private fun applyTheme() {
        if (customView != null) hideCustom()
        val web = shown
        if (web != null) content.removeView(web)
        loadPalette()
        buildUi()
        if (web != null) {
            content.addView(web, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        val bars = WindowInsetsControllerCompat(window, window.decorView)
        bars.isAppearanceLightStatusBars = !dark
        bars.isAppearanceLightNavigationBars = !dark
        val t = tabs.getOrNull(cur)
        if (t != null) {
            privateBadge.visibility = if (t.incognito) View.VISIBLE else View.GONE
            topBar.setBackgroundColor(if (t.incognito) 0xFF1B0F38.toInt() else cBg)
        }
        pushThemeToNewTabs()
        pushThemeToYoutubeTabs()
        syncUi()
    }

    /**
     * YouTube'un KENDİ koyu/açık temasını kullanır (renk ezmesi yok): PREF çerezindeki f6 koyu bitini (0x400)
     * uygulama temasına göre ekler/kaldırır, diğer PREF ayarlarına dokunmaz. Değiştiyse true döner.
     */
    private fun syncYoutubeCookie(): Boolean {
        return try {
            val cm = CookieManager.getInstance()
            val raw = cm.getCookie("https://m.youtube.com") ?: cm.getCookie("https://www.youtube.com") ?: ""
            val pref = raw.split(";").map { it.trim() }.firstOrNull { it.startsWith("PREF=") }?.removePrefix("PREF=") ?: ""
            val map = LinkedHashMap<String, String>()
            for (p in pref.split("&")) {
                if (p.isEmpty()) continue
                val i = p.indexOf('=')
                if (i > 0) map[p.substring(0, i)] = p.substring(i + 1)
            }
            val old = map["f6"]?.toLongOrNull(16) ?: 0L
            val nw = if (dark) (old or 0x400L) else (old and 0x400L.inv())
            if (nw == old && (map.containsKey("f6") || !dark)) return false
            if (nw == 0L) map.remove("f6") else map["f6"] = java.lang.Long.toHexString(nw)
            val v = map.entries.joinToString("&") { it.key + "=" + it.value }
            val exp = "; Max-Age=31536000; Path=/; Secure"
            for (host in listOf(".youtube.com", "m.youtube.com", "www.youtube.com")) {
                val dom = if (host.startsWith(".")) "; Domain=$host" else ""
                cm.setCookie("https://$host", "PREF=$v$dom$exp")
            }
            cm.flush()
            true
        } catch (_: Exception) { false }
    }

    private fun isYt(u: String) = (u.contains("youtube.com") || u.contains("youtu.be")) && !u.contains("music.youtube.com")

    /** Tema değişince çerezi eşitler, açık YouTube sekmelerini yeniler. */
    private fun pushThemeToYoutubeTabs() {
        if (!syncYoutubeCookie()) return
        for (t in tabs.toList()) {
            if (!isYt(t.url)) continue
            try { t.web.reload() } catch (_: Exception) { }
        }
    }

    /** Açık tüm karşılama (yeni sekme) sayfalarına yeni temayı anında uygular; uygulayamazsa sayfayı yeniler. */
    private fun pushThemeToNewTabs() {
        for (t in tabs.toList()) {
            if (!t.url.startsWith(ASSET_PREFIX)) continue
            try {
                t.web.evaluateJavascript("(function(){try{if(window.__rotaTheme){window.__rotaTheme();return true;}}catch(e){}return false;})();") { r ->
                    if (r != "true") { try { t.web.reload() } catch (_: Exception) { } }
                }
            } catch (_: Exception) { try { t.web.reload() } catch (_: Exception) { } }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
    private fun shape(color: Int, radiusDp: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(radiusDp).toFloat()
        return g
    }
    private fun ripple(content: Drawable?): RippleDrawable =
        RippleDrawable(ColorStateList.valueOf(0x33888888), content, ColorDrawable(Color.WHITE))

    private fun iconBtn(res: Int, desc: String, onClick: (View) -> Unit): ImageButton {
        val b = ImageButton(this)
        b.setImageResource(res)
        b.setColorFilter(cIcon)
        b.background = ripple(null)
        b.contentDescription = desc
        b.scaleType = ImageView.ScaleType.CENTER
        b.setOnClickListener { v -> onClick(v) }
        return b
    }

    private fun textBtn(label: String, onClick: () -> Unit): TextView {
        val t = TextView(this)
        t.text = label
        t.textSize = 16f
        t.setTextColor(cIcon)
        t.gravity = Gravity.CENTER
        t.background = ripple(null)
        t.setOnClickListener { onClick() }
        return t
    }

    private fun buildUi() {
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(cBg)
        root.isFocusable = true
        root.isFocusableInTouchMode = true

        barHidden = false
        topBar = object : LinearLayout(this@MainActivity) {
            private var sy = 0f
            private var sx = 0f
            private var fired = false
            override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { sy = e.rawY; sx = e.rawX; fired = false }
                    MotionEvent.ACTION_MOVE -> if (!fired) {
                        val dy = e.rawY - sy
                        val dx = e.rawX - sx
                        // Alttaysa yukarı çek = aç, üstteyse aşağı çek = aç
                        val openDir = if (prefs.barBottom) dy < 0 else dy > 0
                        if (openDir && kotlin.math.abs(dy) > dp(36) && kotlin.math.abs(dy) > kotlin.math.abs(dx) * 1.5f) {
                            fired = true
                            if (menuDialog?.isShowing != true) showMenu(this)
                            return true
                        }
                    }
                }
                return false
            }
            override fun onTouchEvent(e: MotionEvent): Boolean = true
        }
        topBar.orientation = LinearLayout.HORIZONTAL
        topBar.gravity = Gravity.CENTER_VERTICAL
        topBar.setPadding(dp(10), dp(8), dp(10), dp(8))
        topBar.setBackgroundColor(cBg)

        privateBadge = TextView(this)
        privateBadge.text = "🕶 GİZLİ"
        privateBadge.textSize = 11f
        privateBadge.setTypeface(null, Typeface.BOLD)
        privateBadge.setTextColor(Color.WHITE)
        privateBadge.gravity = Gravity.CENTER
        privateBadge.setPadding(dp(10), 0, dp(10), 0)
        privateBadge.background = shape(0xFF8B3DFF.toInt(), 16)
        privateBadge.visibility = View.GONE
        val blp = LinearLayout.LayoutParams(wrap, dp(32))
        blp.rightMargin = dp(8)
        topBar.addView(privateBadge, blp)

        val pill = LinearLayout(this)
        pill.orientation = LinearLayout.HORIZONTAL
        pill.gravity = Gravity.CENTER_VERTICAL
        pill.background = shape(cSurface, 26)
        pill.elevation = dp(4).toFloat()

        urlField = EditText(this)
        urlField.hint = "Ara veya adres yaz"
        urlField.setHintTextColor(cSub)
        urlField.setTextColor(cText)
        urlField.textSize = 15f
        urlField.isSingleLine = true
        urlField.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        urlField.imeOptions = EditorInfo.IME_ACTION_GO
        urlField.setSelectAllOnFocus(true)
        urlField.background = null
        urlField.setPadding(dp(18), 0, 0, 0)
        urlField.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO || (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)) {
                submitUrl(); true
            } else false
        }
        urlField.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) setBarShown(true) else syncUrlText()
        }
        pill.addView(urlField, LinearLayout.LayoutParams(0, dp(50), 1f))
        pill.addView(iconBtn(R.drawable.ic_reload, "Yenile") { reloadCurrent() }, LinearLayout.LayoutParams(dp(38), dp(50)))

        backBtn = iconBtn(R.drawable.ic_back, "Geri") { goBack() }
        val menuBtn = iconBtn(R.drawable.ic_menu, "Menü") { v -> showMenu(v) }

        val tabsWrap = FrameLayout(this)
        tabsWrap.background = ripple(null)
        tabsWrap.contentDescription = "Sekmeler"
        tabsWrap.setOnClickListener { showTabs() }
        tabCount = TextView(this)
        tabCount.gravity = Gravity.CENTER; tabCount.textSize = 12f
        tabCount.setTypeface(null, Typeface.BOLD)
        tabCount.setTextColor(cIcon)
        val tcBg = GradientDrawable()
        tcBg.setColor(0)
        tcBg.cornerRadius = dp(7).toFloat()
        tcBg.setStroke(dp(2), cIcon)
        tabCount.background = tcBg
        tabsWrap.addView(tabCount, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))

        pill.addView(backBtn, LinearLayout.LayoutParams(dp(40), dp(50)))
        pill.addView(tabsWrap, LinearLayout.LayoutParams(dp(40), dp(50)))
        pill.addView(menuBtn, LinearLayout.LayoutParams(dp(40), dp(50)))
        topBar.addView(pill, LinearLayout.LayoutParams(0, dp(50), 1f))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progress.max = 100
        progress.progressTintList = ColorStateList.valueOf(cAccent)
        progress.progressBackgroundTintList = ColorStateList.valueOf(cBg)
        progress.visibility = View.INVISIBLE

        content = FrameLayout(this)
        content.setBackgroundColor(cBg)
        if (!prefs.barBottom) {
            root.addView(topBar, LinearLayout.LayoutParams(match, wrap))
            root.addView(progress, LinearLayout.LayoutParams(match, dp(3)))
        }
        root.addView(content, LinearLayout.LayoutParams(match, 0, 1f))

        findBar = LinearLayout(this)
        findBar.orientation = LinearLayout.HORIZONTAL
        findBar.gravity = Gravity.CENTER_VERTICAL
        findBar.setBackgroundColor(cSurface)
        findBar.setPadding(dp(8), dp(4), dp(4), dp(4))
        findBar.visibility = View.GONE
        findInput = EditText(this)
        findInput.hint = "Sayfada bul"
        findInput.setHintTextColor(cSub)
        findInput.setTextColor(cText)
        findInput.textSize = 15f
        findInput.isSingleLine = true
        findInput.background = shape(cField, 18)
        findInput.setPadding(dp(14), 0, dp(14), 0)
        findInput.doAfterTextChanged { text ->
            val t = text?.toString() ?: ""
            val web = currentWeb()
            if (web != null) {
                if (t.isEmpty()) { web.clearMatches(); findCount.text = "" }
                else { web.findAllAsync(t) }
            }
        }
        findBar.addView(findInput, LinearLayout.LayoutParams(0, dp(36), 1f))
        findCount = TextView(this)
        findCount.setTextColor(cSub); findCount.textSize = 13f; findCount.setPadding(dp(8), 0, dp(8), 0)
        findBar.addView(findCount, LinearLayout.LayoutParams(wrap, wrap))
        findBar.addView(textBtn("▲") { currentWeb()?.findNext(false) }, LinearLayout.LayoutParams(dp(40), dp(40)))
        findBar.addView(textBtn("▼") { currentWeb()?.findNext(true) }, LinearLayout.LayoutParams(dp(40), dp(40)))
        findBar.addView(textBtn("✕") { closeFind() }, LinearLayout.LayoutParams(dp(40), dp(40)))
        root.addView(findBar, LinearLayout.LayoutParams(match, wrap))
        if (prefs.barBottom) {
            root.addView(progress, LinearLayout.LayoutParams(match, dp(3)))
            root.addView(topBar, LinearLayout.LayoutParams(match, wrap))
        }

        dockBar = LinearLayout(this)

        fullscreen = FrameLayout(this)
        fullscreen.setBackgroundColor(Color.BLACK)
        fullscreen.visibility = View.GONE

        val frame = FrameLayout(this)
        frame.addView(root, FrameLayout.LayoutParams(match, match))
        frame.addView(fullscreen, FrameLayout.LayoutParams(match, match))
        setContentView(frame)

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        root.requestFocus()
        updateTabCount()
    }

    private fun startBrowsing() {
        if (tabs.isEmpty()) {
            when (prefs.startMode) {
                "home" -> openTab(Engines.get(prefs.engine).home)
                "blank" -> openTab(null)
                else -> {
                    val saved = prefs.savedTabs()
                    if (saved.isEmpty()) {
                        openTab(null)
                    } else {
                        val want = prefs.savedCurrent()
                        for ((i, u) in saved.take(12).withIndex()) {
                            val t = createTab()
                            if (u.startsWith("http")) { t.pendingUrl = u; t.url = u; t.title = shownUrl(u) }
                            else { t.pendingUrl = NEWTAB_URL }
                            val f = stateFile(i)
                            if (f.exists()) {
                                try { t.pendingState = bytesToBundle(f.readBytes()) } catch (_: Exception) { }
                            }
                        }
                        switchTo(if (want in tabs.indices) want else 0)
                    }
                }
            }
        }
        val s = startUrl; startUrl = null
        val sa = startAppMode; startAppMode = false
        if (s != null) openTab(s, appMode = sa)
    }

    private fun currentWeb(): WebView? = if (cur in tabs.indices) tabs[cur].web else null

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private fun createTab(incognito: Boolean = false): BTab {
        val web = BgWebView(this)
        val bridge = PusulaBridge(prefs)
        val tab = BTab(web, bridge, incognito)
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        try { s.databaseEnabled = true } catch (_: Exception) { }
        s.mediaPlaybackRequiresUserGesture = false
        s.setSupportMultipleWindows(true)
        s.javaScriptCanOpenWindowsAutomatically = false
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.setGeolocationEnabled(false)
        s.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
        try { if (Build.VERSION.SDK_INT >= 26) s.safeBrowsingEnabled = true } catch (_: Exception) { }
        try {
            val cm = android.webkit.CookieManager.getInstance()
            cm.setAcceptCookie(true)
            cm.setAcceptThirdPartyCookies(web, true)
        } catch (_: Exception) { }

        val ua = s.userAgentString.replace("; wv", "").replace(Regex("Version/\\d+\\.\\d+ "), "")
        s.userAgentString = ua
        tab.mobileUa = ua
        web.addJavascriptInterface(bridge, "Pusula")

        syncScripts(tab)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try { WebViewCompat.addDocumentStartJavaScript(web, MEDIA_HOOK_JS, setOf("*")) } catch (_: Exception) { }
        }

        web.setFindListener { active, total, _ ->
            if (tabs.getOrNull(cur) === tab) {
                findCount.text = if (total == 0) "0" else "${active + 1}/$total"
            }
        }
        web.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY -> onPageScroll(tab, scrollY, oldScrollY) }
        web.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            if (isViewableDocument(url, mimeType, contentDisposition)) {
                openDocumentViewer(url, mimeType, contentDisposition)
            } else {
                promptDownload(url, userAgent, contentDisposition, mimeType)
            }
        }
        // Görsel / belge: uzun basınca indir veya aç
        // Video: uzun basınca yalnızca "İndir" düğmesi göster (sardırma ile çakışmasın)
        web.setOnLongClickListener {
            val hr = web.hitTestResult
            when (hr.type) {
                WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE,
                WebView.HitTestResult.IMAGE_TYPE -> {
                    val img = hr.extra
                    if (!img.isNullOrBlank() && img.startsWith("http")) {
                        promptDownload(img, null, null, "image/*")
                        true
                    } else false
                }
                WebView.HitTestResult.SRC_ANCHOR_TYPE -> {
                    val link = hr.extra
                    if (!link.isNullOrBlank() && isViewableDocument(link, null, null)) {
                        openDocumentViewer(link, null, null)
                        true
                    } else false
                }
                else -> {
                    val u = tab.url
                    if (u.contains("youtube.com") || u.contains("youtu.be") || u.contains("music.youtube.com") ||
                        u.contains("video") || u.contains("watch")) {
                        showVideoDownloadChip(web)
                        true
                    } else false
                }
            }
        }
        // JS köprüsü: "İndir" chip'ine basılınca native indirmeyi başlat
        web.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun startVideoDownload() {
                handler.post { downloadCurrentMedia() }
            }
        }, "RotaDl")
        web.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun start(bottom: Double, top: Double) { handler.post { if (tabs.getOrNull(cur) === tab) miniDragStart(bottom, top) } }
            @android.webkit.JavascriptInterface
            fun move(dy: Double, x: Double) { handler.post { miniDragMove(dy) } }
            @android.webkit.JavascriptInterface
            fun end(dy: Double, x: Double, cancel: Boolean) { handler.post { miniDragEnd(dy, cancel) } }
        }, "RotaMini")
        web.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun tap(fx: Double, fy: Double) {
                handler.post {
                    try {
                        val px = (fx * web.width).toFloat()
                        val py = (fy * web.height).toFloat()
                        val t0 = android.os.SystemClock.uptimeMillis()
                        val dn = android.view.MotionEvent.obtain(t0, t0, android.view.MotionEvent.ACTION_DOWN, px, py, 0)
                        web.dispatchTouchEvent(dn)
                        dn.recycle()
                        handler.postDelayed({
                            try {
                                val t1 = android.os.SystemClock.uptimeMillis()
                                val upE = android.view.MotionEvent.obtain(t0, t1, android.view.MotionEvent.ACTION_UP, px, py, 0)
                                web.dispatchTouchEvent(upE)
                                upE.recycle()
                            } catch (_: Exception) { }
                        }, 60)
                    } catch (_: Exception) { }
                }
            }
        }, "RotaTap")
        web.webViewClient = makeClient(tab)
        web.webChromeClient = makeChrome(tab)
        tabs.add(tab)
        updateTabCount()
        return tab
    }

    private fun syncScripts(t: BTab) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        try {
            if (t.openScript == null) t.openScript = WebViewCompat.addDocumentStartJavaScript(t.web, OPEN_APP_HIDE_JS, setOf("*"))
            if (t.capScript == null) t.capScript = WebViewCompat.addDocumentStartJavaScript(t.web, CAPTIONS_OFF_JS, setOf("*"))
            if (t.hookScript == null) t.hookScript = WebViewCompat.addDocumentStartJavaScript(t.web, MEDIA_HOOK_JS, setOf("*"))
            if (prefs.adBlock && t.adScript == null) t.adScript = WebViewCompat.addDocumentStartJavaScript(t.web, STABLE_AD_BLOCKER_JS, setOf("*"))
            else if (!prefs.adBlock && t.adScript != null) { t.adScript?.remove(); t.adScript = null }
            if (prefs.bgPlay && t.keepScript == null) t.keepScript = WebViewCompat.addDocumentStartJavaScript(t.web, KEEP_PLAYING_JS, setOf("*"))
            else if (!prefs.bgPlay && t.keepScript != null) { t.keepScript?.remove(); t.keepScript = null }
        } catch (_: Exception) { }
    }

    /** Ayar değişince betikleri günceller ve açık sayfayı yeniler. */
    private var settingsDirty = false

    /** Ayar değişince betikler hemen güncellenir; açık sayfanın yenilenmesi ayarlar kapanınca (Kaydet) yapılır. */
    private fun settingsChanged() {
        for (t in tabs) syncScripts(t)
        settingsDirty = true
    }

    private fun applySettingsReload() {
        if (!settingsDirty) return
        settingsDirty = false
        val c = tabs.getOrNull(cur) ?: return
        if (c.url.startsWith("http")) {
            // Ayar yenilemesinde "Sayfadan ayrılınsın mı?" sorusu çıkmasın
            skipBeforeUnloadUntil = android.os.SystemClock.uptimeMillis() + 5000L
            c.web.reload()
        }
    }

    private var skipBeforeUnloadUntil = 0L

    private fun openTab(url: String?, load: Boolean = true, incognito: Boolean = false, appMode: Boolean = false): BTab? {
        if (tabs.size >= MAX_TABS) { toast("En fazla $MAX_TABS sekme açılabilir"); return null }
        val t = createTab(incognito)
        t.appMode = appMode
        switchTo(tabs.size - 1)
        applyHostUa(t, url)
        if (appMode) applyAppTaskDescription(url)
        if (load) t.web.loadUrl(url ?: (if (incognito) NEWTAB_URL + "?private=1" else NEWTAB_URL))
        return t
    }

    /** Ana ekran kısayolu: son uygulamalarda site adı ile ayrı görev gibi görünsün. */
    private fun applyAppTaskDescription(url: String?) {
        try {
            val host = try { Uri.parse(url ?: "").host ?: "Web" } catch (_: Exception) { "Web" }
            val label = host.removePrefix("www.").removePrefix("m.").replaceFirstChar { it.uppercase() }
            if (Build.VERSION.SDK_INT >= 28) {
                setTaskDescription(android.app.ActivityManager.TaskDescription.Builder()
                    .setLabel(label)
                    .setIcon(R.drawable.ic_launcher)
                    .build())
            } else {
                @Suppress("DEPRECATION")
                setTaskDescription(android.app.ActivityManager.TaskDescription(label))
            }
        } catch (_: Exception) { }
    }

    private fun switchTo(i: Int) {
        if (i !in tabs.indices) return
        tabs.getOrNull(cur)?.let { capturePreview(it) }
        // WebView hiyerarşide kalsın (GONE) — medya diğer sekmede susmasın
        cur = i
        val t = tabs[i]
        for (tab in tabs) {
            val w = tab.web
            if (w.parent == null) {
                content.addView(w, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            w.visibility = if (tab === t) View.VISIBLE else View.GONE
        }
        shown = t.web
        try { t.web.onResume() } catch (_: Exception) { }

        try { t.web.evaluateJavascript(BACKGROUND_MEDIA_JS, null) } catch (_: Exception) {}

        val ps = t.pendingState
        val p = t.pendingUrl
        if (ps != null) {
            t.pendingState = null
            t.pendingUrl = null
            val ok = try { t.web.restoreState(ps) != null } catch (_: Exception) { false }
            if (!ok && p != null) t.web.loadUrl(p)
        } else if (p != null) {
            t.pendingUrl = null
            t.web.loadUrl(p)
        }
        closeFind()
        setBarShown(true)
        privateBadge.visibility = if (t.incognito) View.VISIBLE else View.GONE
        topBar.setBackgroundColor(if (t.incognito) 0xFF1B0F38.toInt() else cBg)
        syncUi()
        if (t.url.startsWith(ASSET_PREFIX)) {
            try { t.web.evaluateJavascript("if(window.__rotaTheme)window.__rotaTheme();", null) } catch (_: Exception) { }
        }
    }

    private fun closeTab(index: Int) {
        if (index !in tabs.indices) return
        val t = tabs[index]
        val wasCurrent = index == cur
        if (wasCurrent) { content.removeView(t.web); shown = null }
        t.web.stopLoading(); try { t.web.clearHistory() } catch (_: Exception) {}; try { t.web.destroy() } catch (_: Exception) {}
        tabs.removeAt(index)
        if (t.incognito) {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
        }
        if (tabs.isEmpty()) { cur = -1; openTab(null); return }
        if (wasCurrent) switchTo(minOf(index, tabs.size - 1))
        else if (index < cur) cur--
        updateTabCount()
    }

    private fun miniPx(cssPx: Double): Float = (cssPx * resources.displayMetrics.density).toFloat()

    /** p=0: tam ekran konumu, p=1: seçili köşedeki küçük konum. */
    private fun applyMini(p: Float) {
        val t = (miniTab ?: mdOld) ?: return
        val q = p.coerceIn(0f, 1f)
        val sc = 1f + (miniS - 1f) * q
        t.web.scaleX = sc
        t.web.scaleY = sc
        t.web.translationX = miniTx * q
        t.web.translationY = (miniCy - mdTop) * q
    }

    /** Görünmez yüklenen arka sayfayı açar ve flu görüntüyü kaldırır. */
    private fun revealSilent(t: BTab) {
        if (!t.silent) return
        t.silent = false
        try { t.web.visibility = View.VISIBLE } catch (_: Exception) { }
        removeMiniBlur(380)
        if (tabs.getOrNull(cur) === t) syncUi()
    }

    private fun resumeMini(t: BTab) {
        val js = "(function(){try{var v=document.querySelector('video');if(v&&v.paused&&window.__rotaWas)v.play();}catch(e){}})();"
        for (d in longArrayOf(0L, 350L, 900L, 1800L)) {
            handler.postDelayed({ try { t.web.evaluateJavascript(js, null) } catch (_: Exception) { } }, d)
        }
    }

    private fun roundMini(t: BTab, on: Boolean) {
        if (on) {
            t.web.outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) {
                    o.setRoundRect(0, mdTop, v.width, mdTop + mdClipH.coerceAtLeast(1), dp(18).toFloat())
                }
            }
            t.web.clipToOutline = true
        } else {
            t.web.clipToOutline = false
            t.web.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        }
    }

    private fun animMini(from: Float, to: Float, ms: Long, end: () -> Unit) {
        val a = android.animation.ValueAnimator.ofFloat(from, to)
        a.duration = ms
        a.interpolator = android.view.animation.DecelerateInterpolator(1.4f)
        a.addUpdateListener { applyMini(it.animatedValue as Float) }
        a.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) { end() }
        })
        a.start()
    }

    private fun removeMiniBlur(fadeMs: Long) {
        val b = mdBlur ?: return
        mdBlur = null
        if (fadeMs <= 0L) { try { content.removeView(b) } catch (_: Exception) { }; return }
        b.animate().alpha(0f).setDuration(fadeMs).withEndAction { try { content.removeView(b) } catch (_: Exception) { } }.start()
    }

    /** Video aşağı çekilmeye başladı: video yalnızca oynatıcı kısmı olarak ayrılır, arka sayfa flulaştırılmış görüntüye döner (kasmasın). */
    private fun miniDragStart(bottomCss: Double, topCss: Double) {
        if (mdOld != null) return
        val old = tabs.getOrNull(cur) ?: return
        if (old.incognito || !old.url.contains("youtube.com/watch")) return
        val w = content.width
        val h = content.height
        if (w <= 0 || h <= 0) return
        if (miniTab != null) dismissMini()
        var back = "https://m.youtube.com/"
        try {
            val l = old.web.copyBackForwardList()
            var i = l.currentIndex - 1
            while (i >= 0) {
                val u = l.getItemAtIndex(i).url
                if (u != null && u.startsWith("http") && !u.contains("youtube.com/watch")) { back = u; break }
                i--
            }
        } catch (_: Exception) { }
        mdBack = back
        val topPx = miniPx(topCss).toInt().coerceIn(0, (h - dp(120)).coerceAtLeast(0))
        val botPx = miniPx(bottomCss).toInt().coerceIn(topPx + dp(120), h.coerceAtLeast(topPx + dp(120)))
        val clipH = botPx - topPx
        mdTop = topPx
        // Arka plan: küçültülüp büyütülmüş (flu) anlık görüntü; sayfa çizilmediği için sürükleme akıcı kalır
        val blur = android.widget.ImageView(this)
        try {
            val sc = 48f / w
            val bmp = Bitmap.createBitmap(48, (h * sc).toInt().coerceAtLeast(1), Bitmap.Config.RGB_565)
            val cv = Canvas(bmp)
            cv.scale(sc, sc)
            old.web.draw(cv)
            blur.setImageBitmap(bmp)
            blur.scaleType = android.widget.ImageView.ScaleType.FIT_XY
            blur.setColorFilter(0xFF8C8C8C.toInt(), android.graphics.PorterDuff.Mode.MULTIPLY)
        } catch (_: Exception) {
            blur.setBackgroundColor(0xFF202020.toInt())
        }
        content.addView(blur, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        mdBlur = blur
        mdOld = old
        mdClipH = clipH
        val mw = dp(210)
        miniS = mw.toFloat() / w
        miniH = (clipH * miniS).toInt()
        miniTxR = (w - dp(12) - mw).toFloat()
        miniTxL = dp(12).toFloat()
        miniTx = miniTxR
        miniTy = (h - dp(16) - miniH).toFloat().coerceAtLeast(1f)
        miniTyT = dp(12).toFloat()
        miniCy = miniTy
        old.web.pivotX = 0f
        old.web.pivotY = topPx.toFloat()
        old.web.clipBounds = android.graphics.Rect(0, topPx, w, botPx)
        roundMini(old, true)
        mdLastDy = 0f
        mdLastT = android.os.SystemClock.uptimeMillis()
        mdVel = 0f
    }

    private fun miniDragMove(dyCss: Double) {
        if (mdOld == null) return
        val dy = miniPx(dyCss)
        applyMini(dy / (miniTy - mdTop).coerceAtLeast(1f))
        val now = android.os.SystemClock.uptimeMillis()
        if (now > mdLastT) mdVel = (dy - mdLastDy) / (now - mdLastT) * 1000f
        mdLastDy = dy
        mdLastT = now
    }

    private fun miniDragEnd(dyCss: Double, cancel: Boolean) {
        val old = mdOld ?: return
        val dy = miniPx(dyCss)
        val p = (dy / (miniTy - mdTop).coerceAtLeast(1f)).coerceIn(0f, 1f)
        val commit = !cancel && (p > 0.3f || (mdVel > 900f && dy > dp(40)))
        if (commit) animMini(p, 1f, 200) { commitMini() }
        else animMini(p, 0f, 180) { abortMini(old) }
    }

    private fun abortMini(old: BTab) {
        mdOld = null
        old.web.clipBounds = null
        roundMini(old, false)
        removeMiniBlur(120)
        resumeMini(old)
        normalizeLater(old)
    }

    private fun commitMini() {
        val old = mdOld ?: return
        mdOld = null
        val nt = createTab(false)
        tabs.remove(nt)
        applyHostUa(nt, mdBack)
        content.addView(nt.web, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // Arka sayfa görünmeden yüklenir: yükleme çubuğu ve titreme olmaz, hazır olunca flu görüntü yumuşakça kalkar
        nt.silent = true
        nt.web.visibility = View.INVISIBLE
        nt.web.loadUrl(mdBack)
        handler.postDelayed({ revealSilent(nt) }, 7000)
        var idx = tabs.indexOf(old)
        if (idx < 0) idx = cur.coerceAtLeast(0)
        tabs.remove(old)
        tabs.add(idx.coerceIn(0, tabs.size), nt)
        cur = tabs.indexOf(nt)
        shown = nt.web
        miniTab = old
        updateTabCount()
        for (t in tabs) {
            if (t !== nt) { try { t.web.evaluateJavascript(MEDIA_PAUSE_JS, null) } catch (_: Exception) { } }
        }
        nt.web.onResume()
        closeFind()
        setBarShown(true)
        privateBadge.visibility = View.GONE
        topBar.setBackgroundColor(cBg)
        syncUi()
        // Küçük pencerede YouTube yazıları / kontroller gizlenir, video pencereyi doldurur
        try { old.web.evaluateJavascript(YT_MINI_CSS_ON_JS, null) } catch (_: Exception) { }

        val mw = dp(210)
        val ov = FrameLayout(this)
        val tap = View(this)
        var downX = 0f
        var downY = 0f
        var baseTx = 0f
        var baseTy = 0f
        var moved = false
        val slop = dp(8).toFloat()
        tap.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; baseTx = miniTx; baseTy = miniCy; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop)) moved = true
                    if (moved) {
                        val tx = (baseTx + dx).coerceIn(0f, (content.width - mw).toFloat())
                        val ty = (baseTy + dy).coerceIn(0f, (content.height - miniH).toFloat())
                        miniTab?.web?.translationX = tx
                        miniTab?.web?.translationY = ty - mdTop
                        ov.translationX = tx - miniTxR
                        ov.translationY = ty - miniTy
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!moved) {
                        if (e.actionMasked == MotionEvent.ACTION_UP) expandMini(1f)
                    } else {
                        val web = miniTab?.web
                        if (web != null) {
                            val cx = web.translationX + mw / 2f
                            val cy = web.translationY + mdTop + miniH / 2f
                            val toX = if (cx < content.width / 2f) miniTxL else miniTxR
                            val toY = if (cy < content.height / 2f) miniTyT else miniTy
                            val fx = web.translationX
                            val fy = web.translationY + mdTop
                            val a = android.animation.ValueAnimator.ofFloat(0f, 1f)
                            a.duration = 200
                            a.interpolator = android.view.animation.DecelerateInterpolator(1.4f)
                            a.addUpdateListener {
                                val f = it.animatedValue as Float
                                val vx = fx + (toX - fx) * f
                                val vy = fy + (toY - fy) * f
                                miniTab?.web?.translationX = vx
                                miniTab?.web?.translationY = vy - mdTop
                                ov.translationX = vx - miniTxR
                                ov.translationY = vy - miniTy
                            }
                            a.addListener(object : android.animation.AnimatorListenerAdapter() {
                                override fun onAnimationEnd(animation: android.animation.Animator) { miniTx = toX; miniCy = toY }
                            })
                            a.start()
                        }
                    }
                    moved = false
                }
            }
            true
        }
        ov.addView(tap, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        fun pill(txt: String, g: Int, act: () -> Unit): TextView {
            val b2 = TextView(this)
            b2.text = txt
            b2.textSize = 15f
            b2.setTextColor(0xCCDADADA.toInt())
            b2.gravity = Gravity.CENTER
            b2.background = shape(0x99000000.toInt(), 16)
            b2.setOnClickListener { act() }
            ov.addView(b2, FrameLayout.LayoutParams(dp(34), dp(34), g).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
            return b2
        }
        miniPlayBtn = pill("❚❚", Gravity.TOP or Gravity.START) {
            val mt = miniTab
            if (mt != null) {
                // Bildirimdeki oynat/durdur ile aynı komut: sayfanın duraklatma engeline takılmaz
                try { mt.web.evaluateJavascript("window.__rotaWas=false;", null) } catch (_: Exception) { }
                try { mt.web.evaluateJavascript(MEDIA_TOGGLE_FAST_JS, null) } catch (_: Exception) { }
                miniPlayBtn?.text = if (miniPlayBtn?.text?.toString() == "❚❚") "▶\uFE0E" else "❚❚"
                handler.postDelayed({ miniSyncBtn() }, 400)
            }
        }
        pill("✕", Gravity.TOP or Gravity.END) { dismissMini() }
        miniOverlay = ov
        content.addView(ov, FrameLayout.LayoutParams(mw, miniH, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(12), dp(16)) })
        // Arkadaki gezinme sayfasında video ön izlemeleri kapalı: iki video aynı anda oynayıp kasmasın
        try { nt.web.evaluateJavascript(YT_NO_PREVIEW_JS, null) } catch (_: Exception) { }
        handler.removeCallbacks(miniPoll)
        handler.postDelayed(miniPoll, 500)
        resumeMini(old)
    }

    /** Küçük pencereden PiP'e girilmişse, PiP'ten dönünce video büyük ekranda temiz bir görünümle, kaldığı saniyeden açılır. */
    private fun reopenAfterMiniPip(reload: Boolean) {
        val t = tabs.getOrNull(cur) ?: return
        val w = t.web
        w.scaleX = 1f
        w.scaleY = 1f
        w.translationX = 0f
        w.translationY = 0f
        w.clipBounds = null
        w.clipToOutline = false
        w.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        w.requestLayout()
        val url = t.url
        if (!reload || !url.contains("youtube.com/watch")) return
        try {
            w.evaluateJavascript("(function(){var v=document.querySelector('video');return v&&isFinite(v.currentTime)?Math.floor(v.currentTime):-1;})();") { r ->
                val sec = r?.trim()?.toIntOrNull() ?: -1
                if (sec >= 0 && tabs.getOrNull(cur) === t) {
                    val base = url.replace(Regex("([?&])t=[^&]*"), "").trimEnd('?', '&')
                    val sep = if (base.contains("?")) "&" else "?"
                    w.loadUrl(base + sep + "t=" + sec + "s")
                }
            }
        } catch (_: Exception) { }
    }

    /** Geçişlerden (küçük pencere, PiP) sonra artık ölçek/kayma/yatay kaydırma kalmasın: sayfa sola kaymasın. */
    /** PiP/geçişlerden sonra sistem çubuğu boşlukları (üst/sol) bazen sıfırda kalıyor: ekran sol-üste kayar. Yeniden uygulanır. */
    private fun reapplyInsets() {
        try {
            ViewCompat.requestApplyInsets(root)
            if (isInPictureInPictureMode) return
            val wi = ViewCompat.getRootWindowInsets(root) ?: return
            val b = wi.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            if (root.paddingLeft != b.left || root.paddingTop != b.top || root.paddingRight != b.right || root.paddingBottom != b.bottom) {
                root.setPadding(b.left, b.top, b.right, b.bottom)
            }
        } catch (_: Exception) { }
    }

    private fun reapplyInsetsLater() {
        for (d in longArrayOf(100L, 400L, 900L, 1800L)) handler.postDelayed({ reapplyInsets() }, d)
    }

    private fun normalizeView(t: BTab?) {
        if (t == null || t === miniTab || t === mdOld) return
        val w = t.web
        if (w.scaleX != 1f || w.scaleY != 1f || w.translationX != 0f || w.translationY != 0f) {
            w.scaleX = 1f
            w.scaleY = 1f
            w.translationX = 0f
            w.translationY = 0f
        }
        if (w.clipBounds != null) w.clipBounds = null
        if (w.scrollX != 0) w.scrollTo(0, w.scrollY)
        if (w.scrollY in 1..dp(160) && t.url.contains("youtube.com/watch")) w.scrollTo(0, 0)
        try { w.evaluateJavascript(FIX_HSCROLL_JS, null) } catch (_: Exception) { }
    }

    private fun normalizeLater(t: BTab?) {
        for (d in longArrayOf(0L, 300L, 1000L)) {
            handler.postDelayed({ if (tabs.getOrNull(cur) === t) normalizeView(t) }, d)
        }
    }

    private fun checkAfterPip(attempt: Int) {
        val t = tabs.getOrNull(cur) ?: return
        if (isInPictureInPictureMode || miniTab != null) return
        normalizeView(t)
        try {
            t.web.evaluateJavascript(PIP_LAYOUT_CHECK_JS) { r ->
                if (r != null && r.contains("bad") && tabs.getOrNull(cur) === t && !isInPictureInPictureMode) {
                    if (attempt == 0) {
                        try { t.web.evaluateJavascript(PIP_RELAYOUT_JS, null) } catch (_: Exception) { }
                        handler.postDelayed({ checkAfterPip(1) }, 900)
                    } else if (lastPlaying) {
                        reopenAfterMiniPip(true)
                    }
                }
            }
        } catch (_: Exception) { }
    }

    /** Küçük pencerede video oynuyorsa arkadaki ön izlemeler durur; video duraklıysa ön izlemeler serbest. */
    private val miniPoll = object : Runnable {
        override fun run() {
            val mt = miniTab ?: return
            try {
                mt.web.evaluateJavascript("(function(){var v=document.querySelector('video');return !!(v&&!v.paused&&!v.ended);})();") { r ->
                    if (miniTab === mt) {
                        miniPlayBtn?.text = if (r == "true") "❚❚" else "▶\uFE0E"
                        val fg = tabs.getOrNull(cur)
                        if (fg != null && fg !== mt) {
                            try { fg.web.evaluateJavascript("window.__rotaNoPreview=" + (r == "true") + ";", null) } catch (_: Exception) { }
                        }
                    }
                }
            } catch (_: Exception) { }
            handler.postDelayed(this, 700)
        }
    }

    /** Küçük penceredeki oynat/durdur düğmesinin simgesini videonun gerçek durumuna eşitler. */
    private fun miniSyncBtn() {
        val mt = miniTab ?: return
        try {
            mt.web.evaluateJavascript("(function(){var v=document.querySelector('video');return !!(v&&!v.paused&&!v.ended);})();") { r ->
                if (miniTab === mt) miniPlayBtn?.text = if (r == "true") "❚❚" else "▶\uFE0E"
            }
        } catch (_: Exception) { }
    }

    private fun previewsOff() {
        handler.removeCallbacks(miniPoll)
        for (t in tabs) { try { t.web.evaluateJavascript("window.__rotaNoPreview=false;", null) } catch (_: Exception) { } }
    }

    /** Küçük oynatıcıya dokununca video tam ekran olur (yeni sekme olarak). */
    private fun expandMini(fromP: Float) {
        val mt = miniTab ?: return
        prepExpand(mt)
        animMini(fromP, 0f, 200) { finishExpand(mt) }
    }

    /** Animasyonsuz: küçük pencereyi hemen tam ekran video sekmesine çevirir (PiP'e girmeden önce kullanılır). */
    private fun expandMiniNow() {
        val mt = miniTab ?: return
        prepExpand(mt)
        applyMini(0f)
        finishExpand(mt)
        resetWebLayout(mt.web)
    }

    private fun prepExpand(mt: BTab) {
        val ov = miniOverlay
        miniOverlay = null
        try { content.removeView(ov) } catch (_: Exception) { }
        try { mt.web.evaluateJavascript(YT_MINI_CSS_OFF_JS, null) } catch (_: Exception) { }
    }

    private fun finishExpand(mt: BTab) {
        miniTab = null
        mt.web.clipBounds = null
        roundMini(mt, false)
        try { tabs.getOrNull(cur)?.let { capturePreview(it) } } catch (_: Exception) { }
        val fg = tabs.getOrNull(cur)
        shown?.let { try { content.removeView(it) } catch (_: Exception) { } }
        removeMiniBlur(0L)
        var at = (cur + 1).coerceIn(0, tabs.size)
        // Arkada duran YouTube gezinme sekmesi videonun yerine geçer: sekme sayısı artmaz, hep tek video sekmesi olur
        if (fg != null && fg !== mt && !fg.incognito && (fg.url.isEmpty() || fg.url.contains("youtube.com") || fg.url.contains("youtu.be"))) {
            at = cur.coerceAtLeast(0)
            tabs.remove(fg)
            try { fg.web.stopLoading(); fg.web.destroy() } catch (_: Exception) { }
        }
        tabs.add(at.coerceIn(0, tabs.size), mt)
        cur = tabs.indexOf(mt)
        shown = mt.web
        updateTabCount()
        for (t in tabs) {
            if (t !== mt) { try { t.web.evaluateJavascript(MEDIA_PAUSE_JS, null) } catch (_: Exception) { } }
        }
        previewsOff()
        mt.web.onResume()
        closeFind()
        setBarShown(true)
        privateBadge.visibility = View.GONE
        topBar.setBackgroundColor(cBg)
        syncUi()
        resumeMini(mt)
        normalizeLater(mt)
        reapplyInsetsLater()
    }

    /** Küçük oynatıcıyı kapatır (✕ ya da ön planda başka video açılınca). */
    private fun dismissMini() {
        val mt = miniTab ?: return
        val ov = miniOverlay
        miniTab = null
        miniOverlay = null
        previewsOff()
        try { mt.web.evaluateJavascript(MEDIA_PAUSE_JS, null) } catch (_: Exception) { }
        val done = {
            try { content.removeView(ov) } catch (_: Exception) { }
            try { content.removeView(mt.web) } catch (_: Exception) { }
            try { mt.web.stopLoading(); mt.web.destroy() } catch (_: Exception) { }
        }
        mt.web.animate().alpha(0f).setDuration(150).withEndAction { done() }.start()
        ov?.animate()?.alpha(0f)?.setDuration(150)?.start()
    }

    
    private fun updateTabCount() { tabCount.text = tabs.size.toString() }

    private fun stateFile(i: Int) = java.io.File(filesDir, "tabstate_$i.bin")

    private fun bundleToBytes(b: Bundle): ByteArray {
        val p = android.os.Parcel.obtain()
        try {
            p.writeBundle(b)
            return p.marshall()
        } finally {
            p.recycle()
        }
    }

    private fun bytesToBundle(data: ByteArray): Bundle? {
        val p = android.os.Parcel.obtain()
        return try {
            p.unmarshall(data, 0, data.size)
            p.setDataPosition(0)
            p.readBundle(MainActivity::class.java.classLoader)
        } catch (_: Exception) {
            null
        } finally {
            p.recycle()
        }
    }

    private fun clearTabStates() {
        filesDir.listFiles()?.forEach { if (it.name.startsWith("tabstate_")) it.delete() }
    }

    /** Sekme adreslerini ve her sekmenin geri/ileri geçmişini kaydeder. */
    private fun saveTabs() {
        if (tabs.isEmpty()) return
        val urls = ArrayList<String>()
        val keep = HashSet<String>()
        var curSaved = 0
        for ((idx, t) in tabs.withIndex()) {
            if (t.incognito) continue
            if (idx == cur) curSaved = urls.size
            val i = urls.size
            val u = t.pendingUrl ?: t.url
            urls.add(if (u.startsWith("http")) u else NEWTAB_URL)
            try {
                val st = t.pendingState ?: Bundle().also { b ->
                    if (t.web.saveState(b) == null) b.clear()
                }
                if (!st.isEmpty) {
                    stateFile(i).writeBytes(bundleToBytes(st))
                    keep.add(stateFile(i).name)
                }
            } catch (_: Exception) { }
        }
        filesDir.listFiles()?.forEach {
            if (it.name.startsWith("tabstate_") && it.name !in keep) it.delete()
        }
        prefs.saveTabs(urls, curSaved)
    }

    private fun shownUrl(u: String): String =
        if (u.isEmpty() || u.startsWith(ASSET_PREFIX)) ""
        else u.removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')

    private fun syncUrlText() {
        val t = tabs.getOrNull(cur)
        urlField.setText(if (t == null) "" else shownUrl(t.pendingUrl ?: t.url))
    }

    private fun syncUi() {
        val t = tabs.getOrNull(cur) ?: return
        if (!urlField.hasFocus()) syncUrlText()
        setLoadProgress(t.progress)
        refreshNav()
        updateTabCount()
    }

    private fun setLoadProgress(p: Int) {
        progress.progress = p
        val ct = tabs.getOrNull(cur)
        progress.visibility = if (ct?.appMode == true) View.GONE
                              else if (ct?.silent == true) View.INVISIBLE
                              else if (p in 1..99) View.VISIBLE else View.INVISIBLE
    }

    private fun refreshNav() {
        val web = currentWeb()
        backBtn.alpha = if (web != null && (web.canGoBack() || tabs.size > 1)) 1f else 0.35f
    }

    private fun loadInCurrent(url: String) {
        val t = tabs.getOrNull(cur) ?: return
        t.pendingUrl = null
        t.pendingState = null
        applyHostUa(t, url)
        t.web.loadUrl(url)
    }

    private fun submitUrl() {
        val text = urlField.text.toString()
        loadInCurrent(resolveInput(text, Engines.get(prefs.engine)))
        hideKeyboard(); urlField.clearFocus(); root.requestFocus()
    }

    private fun goBack() {
        val web = currentWeb() ?: return
        if (web.canGoBack()) web.goBack() else if (tabs.size > 1) closeTab(cur)
    }

    private fun goForward() { val web = currentWeb() ?: return; if (web.canGoForward()) web.goForward() }
    private fun goHome() { loadInCurrent(Engines.get(prefs.engine).home) }
    private fun reloadCurrent() {
        val t = tabs.getOrNull(cur) ?: return
        if (t.progress in 1..99) { t.web.stopLoading(); return }
        val eu = t.errorUrl
        if (eu != null) { t.errorUrl = null; t.web.loadUrl(eu) } else t.web.reload()
    }

    private fun handleBack() {
        val web = currentWeb()
        when {
            customView != null -> hideCustom()
            findBar.visibility == View.VISIBLE -> closeFind()
            urlField.hasFocus() -> { hideKeyboard(); urlField.clearFocus(); root.requestFocus() }
            web != null && web.canGoBack() -> web.goBack()
            // Sekmeyi kapatma / değiştirmeme — sadece çıkış uyarısı
            else -> themedConfirm("Uygulamadan çıkılsın mı?", onYes = { finish() }, onNo = {})
        }
    }

    private fun shouldBlockAd(uri: Uri): Boolean {
        val host = (uri.host ?: "").lowercase()
        val path = (uri.encodedPath ?: "").lowercase()
        val blockedHosts = listOf(
            "doubleclick.net", "googlesyndication.com", "googleadservices.com",
            "adservice.google.com", "adnxs.com", "adsrvr.org", "adform.net",
            "adcolony.com", "taboola.com", "outbrain.com", "scorecardresearch.com",
            "amazon-adsystem.com", "criteo.com", "hotjar.com", "pagead2.googlesyndication.com"
        )
        if (blockedHosts.any { host == it || host.endsWith("." + it) }) return true
        return path.contains("/ads/") || path.contains("/adserver/") ||
            path.contains("/pagead/") || path.contains("/advertisement/") ||
            path.contains("/api/stats/ads")
    }

    private fun makeClient(tab: BTab): WebViewClient = object : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            val uri = request?.url ?: return null
            if (prefs.adBlock && request?.isForMainFrame != true && !tab.url.contains("youtube.com") && !tab.url.contains("youtu.be") && shouldBlockAd(uri)) {
                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            }
            return loader.shouldInterceptRequest(uri)
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val u = request?.url ?: return false
            val scheme = u.scheme ?: return false
            val gesture = request?.hasGesture() == true
            val urlStr = u.toString()
            if (request?.isForMainFrame == true && (scheme == "http" || scheme == "https")) applyHostUa(tab, urlStr)
            return when (scheme) {
                "http", "https" -> {
                    if (prefs.adBlock && tab.fromPopup && shouldBlockAd(u)) {
                        toast("Reklam penceresi engellendi")
                        closePopupIfEmpty(tab)
                        true
                    } else if (isViewableDocument(urlStr, null, null) && gesture) {
                        openDocumentViewer(urlStr, null, null)
                        true
                    } else false
                }
                "about", "blob", "data" -> false
                "file", "content", "javascript" -> true
                // YouTube / YT Music native uygulamaya yönlendirme engeli
                "intent", "market", "vnd.youtube", "youtube", "youtubemusic" -> true
                else -> {
                    if (urlStr.contains("play.google.com/store") &&
                        (urlStr.contains("com.google.android.youtube") || urlStr.contains("com.google.android.apps.youtube.music"))) {
                        true
                    } else askExternal(urlStr, tab, gesture)
                }
            }
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            val u = url ?: ""
            tab.bridge.trusted = u.startsWith(ASSET_PREFIX)
            tab.url = u
            if (isYt(u)) syncYoutubeCookie()
            tab.progress = 5
            if (tab.errorLoading) tab.errorLoading = false else tab.errorUrl = null
            if (tabs.getOrNull(cur) === tab) { setBarShown(true); syncUi() }
        }

        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
            if (url != null && !url.startsWith("data:")) tab.url = url
            if (miniTab != null && tab !== miniTab && url != null && url.contains("youtube.com/watch")) dismissMini()
            if (tabs.getOrNull(cur) === tab) syncUi()
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            tab.progress = 100
            tab.networkRetryUsed = false
            if (url != null && url.startsWith(ASSET_PREFIX)) {
                try { view?.evaluateJavascript("if(window.__rotaTheme)window.__rotaTheme();", null) } catch (_: Exception) { }
            }
            if (tab.silent) handler.postDelayed({ revealSilent(tab) }, 350)
            val u = url ?: return
            if (u.startsWith("http") && !tab.incognito) {
                val title = view?.title
                prefs.addHistory(if (title.isNullOrBlank()) u else title, u)
            }
            // YouTube kendi koyu/açık temasıyla açılsın: çerez uygulama temasına uymuyorsa düzeltip bir kez yenile
            if (isYt(u)) {
                if (syncYoutubeCookie()) { try { view?.reload() } catch (_: Exception) { } }
            }
            // YouTube ortadaki logo / büyük oynat katmanı + sayfa içi mini player
            if (u.contains("youtube.com") || u.contains("youtu.be") || u.contains("music.youtube.com")) {
                try { view?.evaluateJavascript(YT_HIDE_LOGO_JS, null) } catch (_: Exception) { }
                try { view?.evaluateJavascript(YT_PROMO_JS, null) } catch (_: Exception) { }
                try { view?.evaluateJavascript(YT_MINI_PLAYER_JS, null) } catch (_: Exception) { }
                if (u.contains("music.youtube.com")) {
                    try { view?.evaluateJavascript(YT_MUSIC_UX_JS, null) } catch (_: Exception) { }
                }
                if (miniTab != null && tab !== miniTab) { try { view?.evaluateJavascript(YT_NO_PREVIEW_JS, null) } catch (_: Exception) { } }
            }
            if (tabs.getOrNull(cur) === tab) syncUi()
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            if (view == null || request == null || error == null) return
            if (!request.isForMainFrame) return
            val desc = error.description?.toString() ?: ""
            if (desc.contains("ERR_ABORTED") || desc.contains("ERR_BLOCKED_BY_ORB")) return
            val failing = request.url.toString()
            val code = try { error.errorCode } catch (_: Exception) { 0 }
            val transient = code == android.webkit.WebViewClient.ERROR_HOST_LOOKUP ||
                code == android.webkit.WebViewClient.ERROR_CONNECT ||
                code == android.webkit.WebViewClient.ERROR_TIMEOUT ||
                code == android.webkit.WebViewClient.ERROR_IO ||
                desc.contains("ERR_CONNECTION") || desc.contains("ERR_NAME_NOT_RESOLVED") ||
                desc.contains("ERR_TIMED_OUT") || desc.contains("ERR_NETWORK_CHANGED")
            if (transient && !tab.networkRetryUsed) {
                tab.networkRetryUsed = true
                handler.postDelayed({
                    try { if (tabs.any { it === tab }) view.loadUrl(failing) } catch (_: Exception) { }
                }, 1200L)
                return
            }
            val msg = when {
                code == android.webkit.WebViewClient.ERROR_HOST_LOOKUP -> "Site bulunamadı (DNS). VPN veya ağı kontrol et."
                code == android.webkit.WebViewClient.ERROR_CONNECT -> "Bağlantı kurulamadı. VPN/ağ kopmuş olabilir."
                code == android.webkit.WebViewClient.ERROR_TIMEOUT -> "Bağlantı zaman aşımı. Tekrar dene."
                else -> if (desc.isNotBlank()) desc else "Sayfa yüklenemedi"
            }
            showError(view, tab, failing, msg)
        }

        override fun onReceivedSslError(view: WebView?, h: android.webkit.SslErrorHandler?, error: android.net.http.SslError?) {
            h?.cancel()
            if (view == null || error == null) return
            val failing = error.url ?: return
            if (failing.substringBefore('#').trimEnd('/') == tab.url.substringBefore('#').trimEnd('/')) {
                showError(view, tab, failing, "Güvenli bağlantı kurulamadı (sertifika hatası)")
            }
        }

        override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
            if (tabs.none { it.web === view }) return false
            val u = tab.url
            handler.post {
                val idx = tabs.indexOf(tab)
                if (idx >= 0) {
                    val only = tabs.size == 1
                    closeTab(idx)
                    if (u.startsWith("http")) { if (only) loadInCurrent(u) else openTab(u) }
                    toast("Sayfa yeniden başlatıldı")
                }
            }
            return true
        }
    }

    private fun makeChrome(tab: BTab): WebChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            tab.progress = newProgress
            if (tabs.getOrNull(cur) === tab) setLoadProgress(newProgress)
        }
        override fun onReceivedTitle(view: WebView?, title: String?) { if (!title.isNullOrBlank()) tab.title = title }
        override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
            if (!isUserGesture || resultMsg == null) return false
            if (prefs.adBlock) {
                val ht = view?.hitTestResult?.type ?: 0
                val real = ht == WebView.HitTestResult.SRC_ANCHOR_TYPE || ht == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
                if (!real) { toast("Açılır pencere engellendi"); return false }
            }
            val nt = openTab(null, false) ?: return false
            nt.fromPopup = true
            val transport = resultMsg.obj as WebView.WebViewTransport
            transport.webView = nt.web
            resultMsg.sendToTarget()
            return true
        }
        override fun onCloseWindow(window: WebView?) {
            handler.post { val i = tabs.indexOfFirst { it.web === window }; if (i >= 0) closeTab(i) }
        }
        override fun onShowFileChooser(webView: WebView?, filePathCallback: ValueCallback<Array<Uri>>?, fileChooserParams: FileChooserParams?): Boolean {
            fileCb?.onReceiveValue(null); fileCb = filePathCallback
            return try {
                fileLauncher.launch(fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).setType("*/*"))
                true
            } catch (_: Exception) { fileCb?.onReceiveValue(null); fileCb = null; false }
        }
        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
            if (view == null || customView != null) { callback?.onCustomViewHidden(); return }
            customView = view; customCb = callback
            fullscreen.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            fullscreen.visibility = View.VISIBLE
            setBars(false)
        }
        override fun onHideCustomView() { hideCustom() }
        override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
            themedMessage(message ?: "", onDismiss = { result?.confirm() })
            return true
        }
        override fun onJsBeforeUnload(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
            if (android.os.SystemClock.uptimeMillis() < skipBeforeUnloadUntil) {
                result?.confirm()
                return true
            }
            themedChoice(
                "Sayfadan ayrılınsın mı?",
                "Bu sayfada yaptığın değişiklikler kaydedilmemiş olabilir. Şimdi ayrılırsan kaybolabilirler.",
                listOf(
                    Triple("Bu sayfada kal", true) { result?.cancel() },
                    Triple("Kaydetmeden ayrıl", false) { result?.confirm() }
                ),
                onCancel = { result?.cancel() }
            )
            return true
        }
        override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
            themedConfirm(message ?: "", onYes = { result?.confirm() }, onNo = { result?.cancel() })
            return true
        }
    }

    private fun hideCustom() {
        val v = customView ?: return
        fullscreen.removeView(v); fullscreen.visibility = View.GONE
        customView = null; customCb?.onCustomViewHidden(); customCb = null
        setBars(true)
    }

    private fun setBars(show: Boolean) {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        if (show) { c.show(WindowInsetsCompat.Type.systemBars()) }
        else {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    /** Dokunuşsuz (reklam) yönlendirmeler uygulama açamaz; dokunuşla gelenler önce sorulur. */
    private fun askExternal(url: String, tab: BTab, gesture: Boolean): Boolean {
        if (prefs.adBlock && !gesture) {
            toast("Otomatik uygulama geçişi engellendi")
            closePopupIfEmpty(tab)
            return true
        }
        val i: Intent = try {
            if (url.startsWith("intent:")) Intent.parseUri(url, Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, Uri.parse(url))
        } catch (_: Exception) { toast("Bağlantı açılamadı"); closePopupIfEmpty(tab); return true }
        i.addCategory(Intent.CATEGORY_BROWSABLE); i.component = null; i.selector = null
        val label = try { packageManager.resolveActivity(i, 0)?.loadLabel(packageManager)?.toString() } catch (_: Exception) { null }
        val what = label ?: (i.scheme ?: "başka bir uygulama")
        themedConfirm("Başka bir uygulamaya geçilecek: " + what + "\nDevam edilsin mi?",
            onYes = { try { startActivity(i) } catch (_: Exception) { toast("Bağlantı açılamadı") }; closePopupIfEmpty(tab) },
            onNo = { closePopupIfEmpty(tab) })
        return true
    }

    /** Boş kalan açılır pencere sekmesini kapatır. */
    private fun closePopupIfEmpty(tab: BTab) {
        handler.post {
            val idx = tabs.indexOf(tab)
            if (idx >= 0 && tabs.size > 1 && tab.fromPopup && tab.url.isEmpty()) closeTab(idx)
        }
    }

    private fun showError(view: WebView, tab: BTab, failing: String, desc: String) {
        tab.errorUrl = failing
        tab.errorLoading = true
        view.loadDataWithBaseURL(failing, errorHtml(failing, desc), "text/html", "utf-8", failing)
    }

    private fun errorHtml(url: String, desc: String): String {
        val bg = String.format("#%06X", 0xFFFFFF and cBg)
        val fg = String.format("#%06X", 0xFFFFFF and cText)
        val js = Html.escapeHtml("location.href=" + JSONObject.quote(url))
        return "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<style>body{font-family:sans-serif;margin:0;padding:32px 24px;background:$bg;color:$fg}h1{font-size:22px}p{opacity:.75}" +
            "button{margin-top:12px;padding:12px 20px;border:0;border-radius:22px;background:#2f6bff;color:#fff;font-size:16px}" +
            "</style></head><body><h1>Yüklenemedi</h1><p>${Html.escapeHtml(url)}</p><p>${Html.escapeHtml(desc)}</p><button onclick=\"$js\">Tekrar Dene</button></body></html>"
    }

    /** PDF, Word, Excel, metin vb. uygulama içinde açılabilecek dosya mı? */
    private fun isViewableDocument(url: String, mime: String?, cd: String?): Boolean {
        val m = (mime ?: "").lowercase()
        val name = URLUtil.guessFileName(url, cd, mime).lowercase()
        val path = try { Uri.parse(url).path?.lowercase() ?: "" } catch (_: Exception) { "" }
        return m.contains("pdf") || name.endsWith(".pdf") || path.endsWith(".pdf") ||
            m.contains("msword") || m.contains("wordprocessingml") || name.endsWith(".doc") || name.endsWith(".docx") ||
            m.contains("ms-excel") || m.contains("spreadsheetml") || name.endsWith(".xls") || name.endsWith(".xlsx") ||
            m.contains("ms-powerpoint") || m.contains("presentationml") || name.endsWith(".ppt") || name.endsWith(".pptx") ||
            m.contains("rtf") || name.endsWith(".rtf") ||
            m.startsWith("text/") || name.endsWith(".txt") || name.endsWith(".csv") ||
            name.endsWith(".md") || name.endsWith(".json") || name.endsWith(".log") || name.endsWith(".xml")
    }

    private fun openDocumentViewer(url: String, mime: String?, cd: String?) {
        val name = URLUtil.guessFileName(url, cd, mime)
        val i = Intent(this, DocumentViewerActivity::class.java)
            .putExtra(DocumentViewerActivity.EXTRA_URL, url)
            .putExtra(DocumentViewerActivity.EXTRA_NAME, name)
            .putExtra(DocumentViewerActivity.EXTRA_MIME, mime)
        try { startActivity(i) } catch (_: Exception) { startDownload(url, null, cd, mime) }
    }

    private fun mediaKind(url: String, mime: String?, cd: String?): String {
        val m = (mime ?: "").lowercase()
        val name = URLUtil.guessFileName(url, cd, mime).lowercase()
        return when {
            m.startsWith("video/") || name.endsWith(".mp4") || name.endsWith(".webm") ||
            name.endsWith(".mkv") || name.endsWith(".mov") || name.endsWith(".m4v") ||
            name.endsWith(".avi") || name.endsWith(".3gp") -> "video"
            m.startsWith("audio/") || name.endsWith(".mp3") || name.endsWith(".m4a") ||
            name.endsWith(".aac") || name.endsWith(".ogg") || name.endsWith(".wav") ||
            name.endsWith(".flac") || name.endsWith(".opus") -> "audio"
            m.startsWith("image/") || name.endsWith(".jpg") || name.endsWith(".jpeg") ||
            name.endsWith(".png") || name.endsWith(".gif") || name.endsWith(".webp") ||
            name.endsWith(".bmp") || name.endsWith(".svg") -> "image"
            else -> "file"
        }
    }

    /** Medya ve diğer dosyalar için indirme seçenekleri gösterir. */
    private fun promptDownload(url: String, ua: String?, cd: String?, mime: String?) {
        if (!url.startsWith("http")) return
        val kind = mediaKind(url, mime, cd)
        val name = URLUtil.guessFileName(url, cd, mime)
        val d = Dialog(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(18), dp(18), dp(18), dp(22))
        val r = dp(28).toFloat()
        val bgd = GradientDrawable()
        bgd.setColor(cSurface)
        bgd.cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        col.background = bgd

        val title = TextView(this)
        title.text = when (kind) {
            "video" -> "Videoyu indir"
            "audio" -> "Sesi indir"
            "image" -> "Görseli indir"
            else -> "Dosyayı indir"
        }
        title.textSize = 18f
        title.setTypeface(null, Typeface.BOLD)
        title.setTextColor(cText)
        title.setPadding(0, 0, 0, dp(6))
        col.addView(title)

        val sub = TextView(this)
        sub.text = name
        sub.textSize = 13f
        sub.setTextColor(cSub)
        sub.setPadding(0, 0, 0, dp(14))
        col.addView(sub)

        fun addOpt(label: String, forceMime: String? = null, forceExt: String? = null) {
            val btn = textBtn(label) {
                d.dismiss()
                var finalName = name
                var finalMime = forceMime ?: mime
                if (forceExt != null && !finalName.lowercase().endsWith(forceExt)) {
                    finalName = finalName.substringBeforeLast('.') + forceExt
                }
                startDownload(url, ua, cd, finalMime, finalName)
            }
            btn.setTextColor(cText)
            btn.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            btn.setPadding(dp(16), 0, dp(16), 0)
            btn.background = shape(cField, 14)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50))
            lp.bottomMargin = dp(8)
            col.addView(btn, lp)
        }

        when (kind) {
            "video" -> {
                addOpt("En yüksek kalite (orijinal)", mime)
                addOpt("1080p olarak dene", "video/mp4", ".mp4")
                addOpt("720p olarak dene", "video/mp4", ".mp4")
                addOpt("480p olarak dene", "video/mp4", ".mp4")
            }
            "audio" -> {
                addOpt("MP3 / Ses olarak indir", "audio/mpeg", ".mp3")
                addOpt("Orijinal formatta indir", mime)
            }
            "image" -> {
                addOpt("Görseli indir", mime)
            }
            else -> {
                addOpt("İndir", mime)
            }
        }

        val cancel = textBtn("İptal") { d.dismiss() }
        cancel.setTextColor(cSub)
        cancel.gravity = Gravity.CENTER
        col.addView(cancel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))

        sheetWindow(d, col, false)
        d.show()
    }

    /** Sekme başlığından temiz dosya adı (videoplayback yazmasın). */
    private fun mediaFileBase(): String {
        val t = tabs.getOrNull(cur)
        var title = t?.title?.trim().orEmpty()
        if (title.isBlank() || title.equals("YouTube", true) || title.equals("YouTube Music", true)) {
            title = lastTitle.ifBlank { "medya" }
        }
        title = title
            .replace(Regex("\\s*[-|]\\s*YouTube(\\s*Music)?\\s*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[\\\\/:*?\"<>|]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(80)
        if (title.isBlank()) title = "medya"
        return title
    }

    private fun startDownload(url: String, ua: String?, cd: String?, mime: String?, forcedName: String? = null) {
        if (!url.startsWith("http")) return
        try {
            var name = forcedName
            if (name.isNullOrBlank()) {
                val guessed = URLUtil.guessFileName(url, cd, mime)
                // videoplayback / generik isimleri başlıkla değiştir
                val base = mediaFileBase()
                val ext = when {
                    mime?.contains("audio") == true || guessed.endsWith(".mp3", true) || guessed.endsWith(".m4a", true) ->
                        if (guessed.contains(".")) guessed.substringAfterLast('.') else "mp3"
                    mime?.contains("video") == true || guessed.endsWith(".mp4", true) || guessed.endsWith(".webm", true) ->
                        if (guessed.contains(".")) guessed.substringAfterLast('.') else "mp4"
                    guessed.contains(".") -> guessed.substringAfterLast('.')
                    else -> "bin"
                }
                name = if (guessed.startsWith("videoplayback", true) || guessed.startsWith("audio", true) ||
                    guessed.length < 8 || guessed.matches(Regex("(?i)download|file|media.*"))) {
                    "$base.$ext"
                } else {
                    // Yine de başlığı tercih et
                    "$base.$ext"
                }
            }
            val req = DownloadManager.Request(Uri.parse(url))
            if (mime != null) req.setMimeType(mime)
            val cookie = CookieManager.getInstance().getCookie(url)
                ?: CookieManager.getInstance().getCookie("https://www.youtube.com")
            if (!cookie.isNullOrBlank()) req.addRequestHeader("Cookie", cookie)
            req.addRequestHeader("User-Agent", ua ?: "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
            if (url.contains("googlevideo.com") || url.contains("youtube.com") || url.contains("youtu.be")) {
                req.addRequestHeader("Referer", "https://www.youtube.com/")
                req.addRequestHeader("Origin", "https://www.youtube.com")
            }
            req.setTitle(name)
            // Sistem bildirimi kapalı; ilerleme ve bitiş bildirimini DownloadNotifier kendisi gösterir
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN)
            req.setAllowedOverMetered(true)
            req.setAllowedOverRoaming(true)
            val destPath: String
            if (Build.VERSION.SDK_INT >= 29) {
                req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                destPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath + "/" + name
            } else {
                req.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, name)
                destPath = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.absolutePath + "/" + name
            }
            val dlId = (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            DownloadNotifier.track(this, dlId, name.orEmpty())
            prefs.addDownload(name, url, destPath, mime ?: "")
            val kind = when {
                mime?.contains("audio") == true || name.endsWith(".mp3", true) -> "MP3 indiriliyor"
                else -> "MP4 indiriliyor"
            }
            toast("$kind: $name")
        } catch (_: Exception) { toast("İndirilemedi") }
    }

    private fun toast(msg: String) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(root.windowToken, 0)
    }

    private fun sheetWindow(d: Dialog, view: View, full: Boolean, top: Boolean = false) {
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        d.setContentView(view)
        d.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(if (full) cBg else Color.TRANSPARENT))
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, if (full) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT)
            if (!full) w.setGravity(if (top) Gravity.TOP else Gravity.BOTTOM)
            if (!full && top) w.addFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        }
    }

    /** Adres çubuğu: altta yukarı=aç aşağı=kapa; üstte aşağı=aç yukarı=kapa. */
    private fun attachBarDrawerGesture(target: View) {
        var startY = 0f
        var active = false
        var handled = false
        val threshold = dp(40).toFloat()
        target.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startY = e.rawY
                    active = true
                    handled = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!active || handled) return@setOnTouchListener handled
                    val dy = e.rawY - startY
                    if (kotlin.math.abs(dy) < threshold) return@setOnTouchListener false
                    val bottom = prefs.barBottom
                    val open = if (bottom) dy < 0 else dy > 0
                    val close = if (bottom) dy > 0 else dy < 0
                    when {
                        open && menuDialog?.isShowing != true -> {
                            handled = true
                            active = false
                            showMenu(v)
                            true
                        }
                        close && menuDialog?.isShowing == true -> {
                            handled = true
                            active = false
                            try { menuDialog?.dismiss() } catch (_: Exception) {}
                            true
                        }
                        else -> false
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    active = false
                    handled
                }
                else -> false
            }
        }
    }

    private fun addCurrentToHomeScreen() {
        val t = tabs.getOrNull(cur) ?: return
        val url = t.url
        if (!url.startsWith("http")) {
            toast("Önce bir sayfa aç")
            return
        }
        val title = (if (t.title.isBlank()) shownUrl(url) else t.title)
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { "Rota" }
            .take(40)
        val launch = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(url)
            putExtra("pusula_app", true)
            // Son uygulamalarda Rota'tan ayrı bir "uygulama" gibi görünsün
            flags = Intent.FLAG_ACTIVITY_NEW_DOCUMENT or
                Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val iconJs = """(function(){var best='',bs=-1;var ls=document.querySelectorAll('link[rel~="apple-touch-icon"],link[rel~="apple-touch-icon-precomposed"],link[rel~="icon"],link[rel="shortcut icon"]');for(var i=0;i<ls.length;i++){var l=ls[i];var sz=parseInt((l.getAttribute('sizes')||'0').split('x')[0],10)||0;if(/apple/.test(l.rel||''))sz+=1000;if(l.href&&sz>=bs){bs=sz;best=l.href;}}return best;})();"""
        try {
            val web = t.web
            web.evaluateJavascript(iconJs) { raw ->
                val jsIcon = try { JSONArray("[" + raw + "]").optString(0, "") } catch (_: Exception) { "" }
                val wvFav = try { web.favicon } catch (_: Exception) { null }
                Thread {
                    val bmp = try { fetchSiteIcon(url, jsIcon, wvFav) } catch (_: Exception) { null }
                    runOnUiThread { pinShortcut(url, title, launch, bmp) }
                }.start()
            }
        } catch (_: Exception) {
            pinShortcut(url, title, launch, null)
        }
    }

    /** Sitenin kendi simgesini indirir: sayfadaki link, /apple-touch-icon.png, /favicon.ico, sonra WebView simgesi. */
    private fun fetchSiteIcon(pageUrl: String, jsIcon: String, wvFav: Bitmap?): Bitmap? {
        val host = try { Uri.parse(pageUrl).host ?: "" } catch (_: Exception) { "" }
        val cands = ArrayList<String>()
        if (jsIcon.startsWith("http")) cands.add(jsIcon)
        if (host.isNotEmpty()) {
            cands.add("https://" + host + "/apple-touch-icon.png")
            cands.add("https://" + host + "/favicon.ico")
            cands.add("https://t2.gstatic.com/faviconV2?client=SOCIAL&type=FAVICON&url=https://" + host + "&size=128")
        }
        var best: Bitmap? = null
        for (u in cands) {
            try {
                val c = java.net.URL(u).openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 4000
                c.readTimeout = 4000
                c.instanceFollowRedirects = true
                c.setRequestProperty("User-Agent", "Mozilla/5.0")
                c.connect()
                if (c.responseCode != 200) { c.disconnect(); continue }
                val bmp = android.graphics.BitmapFactory.decodeStream(c.inputStream)
                c.disconnect()
                if (bmp != null && bmp.width >= 16) {
                    val prevBest = best
                    if (prevBest == null || bmp.width > prevBest.width) best = bmp
                    if (bmp.width >= 96) break
                }
            } catch (_: Exception) { }
        }
        val b = best
        if (wvFav != null && (b == null || b.width < 32) && wvFav.width > (b?.width ?: 0)) best = wvFav
        return best
    }

    /** Simgeyi launcher'ın maskesine uyacak şekilde ortalayıp kenarlara kendi arka plan rengini verir. */
    private fun toShortcutBitmap(src: Bitmap): Bitmap {
        val size = 432
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(out)
        val corner = src.getPixel(0, 0)
        cv.drawColor(if (android.graphics.Color.alpha(corner) == 255) corner else android.graphics.Color.WHITE)
        val target = size * 0.62f
        val scale = target / Math.max(src.width, src.height).toFloat()
        val w = Math.max(1, (src.width * scale).toInt())
        val h = Math.max(1, (src.height * scale).toInt())
        val scaled = Bitmap.createScaledBitmap(src, w, h, true)
        cv.drawBitmap(scaled, (size - w) / 2f, (size - h) / 2f, null)
        return out
    }

    private fun pinShortcut(url: String, title: String, launch: Intent, icon: Bitmap?) {
        try {
            val shaped = icon?.let { try { toShortcutBitmap(it) } catch (_: Exception) { null } }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val sm = getSystemService(ShortcutManager::class.java)
                if (sm != null && sm.isRequestPinShortcutSupported) {
                    val id = "rota_" + Integer.toHexString(url.hashCode())
                    val ic = if (shaped != null) Icon.createWithAdaptiveBitmap(shaped) else Icon.createWithResource(this, R.drawable.ic_launcher)
                    val info = ShortcutInfo.Builder(this, id)
                        .setShortLabel(title.take(25))
                        .setLongLabel(title)
                        .setIcon(ic)
                        .setIntent(launch)
                        .build()
                    sm.requestPinShortcut(info, null)
                    toast("Ana ekran isteği gönderildi")
                } else {
                    toast("Bu cihazda kısayol desteklenmiyor")
                }
            } else {
                @Suppress("DEPRECATION")
                val add = Intent("com.android.launcher.action.INSTALL_SHORTCUT")
                add.putExtra(Intent.EXTRA_SHORTCUT_INTENT, launch)
                add.putExtra(Intent.EXTRA_SHORTCUT_NAME, title.take(25))
                if (shaped != null) {
                    add.putExtra(Intent.EXTRA_SHORTCUT_ICON, Bitmap.createScaledBitmap(shaped, 192, 192, true))
                } else {
                    add.putExtra(
                        Intent.EXTRA_SHORTCUT_ICON_RESOURCE,
                        Intent.ShortcutIconResource.fromContext(this, R.drawable.ic_launcher)
                    )
                }
                add.putExtra("duplicate", false)
                sendBroadcast(add)
                toast("Kısayol eklendi")
            }
        } catch (_: Exception) {
            toast("Ana ekrana eklenemedi")
        }
    }

    private fun showMenu(@Suppress("UNUSED_PARAMETER") anchor: View) {
        val t = tabs.getOrNull(cur) ?: return
        val marked = t.url.startsWith("http") && prefs.isBookmarked(t.url)
        val onYt = (t.url.contains("youtube.com") || t.url.contains("youtu.be")) && !t.url.contains("music.youtube.com")
        val canHome = t.url.startsWith("http")
        // Zaten açıksa kapat (gesture tekrar)
        if (menuDialog?.isShowing == true) {
            try { menuDialog?.dismiss() } catch (_: Exception) {}
            menuDialog = null
            return
        }
        val sheetAtTop = !prefs.barBottom
        var sheetView: View? = null
        val d = object : Dialog(this@MainActivity) {
            private var closing = false
            fun finishNow() { try { super.dismiss() } catch (_: Exception) { } }
            override fun dismiss() {
                if (closing) return
                val v = sheetView
                if (v == null || !isShowing || this@MainActivity.isFinishing || this@MainActivity.isDestroyed || v.height <= 0) {
                    finishNow(); return
                }
                closing = true
                val h = v.height.toFloat()
                val from = v.translationY
                v.animate().translationY(if (sheetAtTop) -h else h)
                    .setDuration(220)
                    .setInterpolator(android.view.animation.AccelerateInterpolator(1.2f))
                    .setUpdateListener { a -> try { window?.setDimAmount(0.5f * (1f - a.animatedFraction)) } catch (_: Exception) { } }
                    .withEndAction { finishNow() }
                    .start()
            }
        }
        menuDialog = d
        d.setOnDismissListener { if (menuDialog === d) menuDialog = null }

        fun row(icon: String, label: String, trailing: String? = null, act: () -> Unit): View {
            val r = LinearLayout(this)
            r.orientation = LinearLayout.HORIZONTAL
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(dp(16), dp(15), dp(16), dp(15))
            r.background = ripple(null)
            val ic = TextView(this)
            ic.text = icon
            ic.textSize = 20f
            ic.gravity = Gravity.CENTER
            r.addView(ic, LinearLayout.LayoutParams(dp(32), dp(32)).apply { rightMargin = dp(14) })
            val lb = TextView(this)
            lb.text = label
            lb.textSize = 16f
            lb.setTextColor(cText)
            r.addView(lb, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (trailing != null) {
                val tr = TextView(this)
                tr.text = trailing
                tr.textSize = 13f
                tr.setTextColor(cSub)
                tr.setPadding(dp(10), dp(4), dp(10), dp(4))
                tr.background = shape(cField, 12)
                r.addView(tr)
            }
            r.setOnClickListener { d.dismiss(); act() }
            return r
        }

        fun sep(): View {
            val v = View(this)
            v.setBackgroundColor(cField)
            return v
        }

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val statusH = run {
            val rid = resources.getIdentifier("status_bar_height", "dimen", "android")
            if (rid > 0) resources.getDimensionPixelSize(rid) else dp(28)
        }
        if (sheetAtTop) col.setPadding(dp(10), dp(8) + statusH, dp(10), dp(8))
        else col.setPadding(dp(10), dp(8), dp(10), dp(16))
        val rad = dp(28).toFloat()
        val bgd = GradientDrawable()
        bgd.setColor(cSurface)
        bgd.cornerRadii = if (sheetAtTop) floatArrayOf(0f, 0f, 0f, 0f, rad, rad, rad, rad)
                          else floatArrayOf(rad, rad, rad, rad, 0f, 0f, 0f, 0f)
        col.background = bgd

        val handle = View(this)
        handle.background = shape(cField, 3)
        if (!sheetAtTop) col.addView(handle, LinearLayout.LayoutParams(dp(40), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(6); bottomMargin = dp(10)
        })

        // Üst aksiyonlar
        col.addView(row(if (marked) "⭐" else "☆", if (marked) "Yer iminden kaldır" else "Yer imlerine ekle") {
            prefs.toggleBookmark(if (t.title.isBlank()) t.url else t.title, t.url); toast("Yer imleri güncellendi")
        })
        if (canHome) {
            col.addView(row("🏠", "Ana ekrana ekle") { addCurrentToHomeScreen() })
        }
        col.addView(row("🔍", "Sayfada bul") { openFind() })
        col.addView(row("➕", "Yeni sekme") { openTab(null) })
        col.addView(row("🕶️", "Gizli sekme") { openTab(null, true, true) })

        col.addView(sep(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(6); bottomMargin = dp(10); leftMargin = dp(12); rightMargin = dp(12)
        })

        // Hızlı erişim kutuları (Geçmiş / Yer imleri / İndirilenler / Paylaş)
        val quick = LinearLayout(this)
        quick.orientation = LinearLayout.HORIZONTAL
        quick.setPadding(dp(6), 0, dp(6), dp(8))
        fun qBtn(icon: String, label: String, act: () -> Unit): View {
            val cell = LinearLayout(this)
            cell.orientation = LinearLayout.VERTICAL
            cell.gravity = Gravity.CENTER
            cell.setPadding(dp(6), dp(12), dp(6), dp(12))
            cell.background = ripple(shape(cField, 16))
            val ic = TextView(this)
            ic.text = icon
            ic.textSize = 22f
            ic.gravity = Gravity.CENTER
            cell.addView(ic)
            val lb = TextView(this)
            lb.text = label
            lb.textSize = 11f
            lb.setTextColor(cSub)
            lb.gravity = Gravity.CENTER
            lb.setPadding(0, dp(4), 0, 0)
            cell.addView(lb)
            cell.setOnClickListener { d.dismiss(); act() }
            return cell
        }
        quick.addView(qBtn("🕘", "Geçmiş") {
            showEntries("Geçmiş", prefs.history(), { prefs.removeHistory(it.url) }, Pair("Temizle") { prefs.clearHistory() })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        quick.addView(qBtn("⭐", "Yer imleri") {
            showEntries("Yer İmleri", prefs.bookmarks(), { prefs.removeBookmark(it.url) }, null)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        quick.addView(qBtn("⬇️", "İndirilenler") { showDownloads() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        quick.addView(qBtn("📤", "Paylaş") { shareCurrent() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(quick)

        col.addView(sep(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(4); bottomMargin = dp(6); leftMargin = dp(12); rightMargin = dp(12)
        })

        col.addView(row("🎨", "Tema") { chooseTheme { } })
        col.addView(row("⚙️", "Ayarlar") { showSettings() })

        if (sheetAtTop) col.addView(handle, LinearLayout.LayoutParams(dp(40), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(10); bottomMargin = dp(6)
        })

        // Çekmece parmağı takip eder: adres çubuğu altaysa aşağı, üstteyse yukarı sürükleyince kapanır
        val swipeWrap = object : FrameLayout(this@MainActivity) {
            private var sy = 0f
            private var sx = 0f
            private var dragging = false
            private var vt: android.view.VelocityTracker? = null
            private val slop = dp(10).toFloat()
            private fun sg(): Float = if (sheetAtTop) -1f else 1f
            private fun dimTo(progress: Float) {
                try { d.window?.setDimAmount(0.5f * (1f - progress.coerceIn(0f, 1f))) } catch (_: Exception) { }
            }
            override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        sy = e.rawY; sx = e.rawX; dragging = false
                        vt?.recycle(); vt = android.view.VelocityTracker.obtain(); vt?.addMovement(e)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        vt?.addMovement(e)
                        if (!dragging) {
                            val dy = (e.rawY - sy) * sg()
                            val dx = kotlin.math.abs(e.rawX - sx)
                            if (dy > slop && dy > dx * 1.2f) { dragging = true; sy = e.rawY; return true }
                        }
                    }
                }
                return dragging
            }
            override fun onTouchEvent(e: MotionEvent): Boolean {
                vt?.addMovement(e)
                when (e.actionMasked) {
                    MotionEvent.ACTION_MOVE -> if (dragging) {
                        val p = Math.max(0f, (e.rawY - sy) * sg())
                        col.translationY = p * sg()
                        dimTo(p / Math.max(1, col.height).toFloat())
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (dragging) {
                            vt?.computeCurrentVelocity(1000)
                            val v = (vt?.yVelocity ?: 0f) * sg()
                            val p = Math.abs(col.translationY)
                            if (p > col.height * 0.3f || v > 1200f) d.dismiss()
                            else {
                                col.animate().translationY(0f).setDuration(200)
                                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                                    .setUpdateListener { a -> dimTo((p / Math.max(1, col.height).toFloat()) * (1f - a.animatedFraction)) }
                                    .start()
                            }
                        }
                        dragging = false
                        vt?.recycle(); vt = null
                    }
                }
                return true
            }
        }
        swipeWrap.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        sheetView = col
        sheetWindow(d, swipeWrap, false, sheetAtTop)
        d.window?.setWindowAnimations(0)
        d.window?.setDimAmount(0f)
        // Açılış: çekmece adres çubuğunun olduğu yönden kayarak gelir
        swipeWrap.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                swipeWrap.viewTreeObserver.removeOnPreDrawListener(this)
                val h = col.height.toFloat()
                col.translationY = if (sheetAtTop) -h else h
                col.animate().translationY(0f).setDuration(280)
                    .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f))
                    .setUpdateListener { a -> try { d.window?.setDimAmount(0.5f * a.animatedFraction) } catch (_: Exception) { } }
                    .start()
                return true
            }
        })
        pushUiOverlay()
        d.setOnDismissListener { popUiOverlay(); menuDialog = null }
        menuDialog = d
        d.show()
    }

    /** Videoya uzun basınca sağda/solda kısa ömürlü "İndir" chip'i gösterir. */
    private fun showVideoDownloadChip(web: WebView) {
        try {
            web.evaluateJavascript(
                """
                (function(){
                  try{
                    var old=document.getElementById('__rota_dl_chip');
                    if(old)old.remove();
                    var b=document.createElement('div');
                    b.id='__rota_dl_chip';
                    b.textContent='⬇️ İndir';
                    b.style.cssText='position:fixed;right:12px;top:42%;z-index:2147483647;padding:14px 18px;border-radius:28px;background:linear-gradient(135deg,#4c8dff,#8a5cff);color:#fff;font:700 15px/1 sans-serif;box-shadow:0 8px 24px rgba(0,0,0,.4);cursor:pointer;-webkit-user-select:none;user-select:none;';
                    b.onclick=function(e){e.preventDefault();e.stopPropagation();b.remove();try{if(window.RotaDl)RotaDl.startVideoDownload();}catch(x){}};
                    document.documentElement.appendChild(b);
                    setTimeout(function(){try{b.remove();}catch(z){}},5000);
                  }catch(e){}
                })();
                """.trimIndent(), null)
        } catch (_: Exception) {
            downloadCurrentMedia()
        }
    }

    /**
     * YouTube: SaveFrom arayüzüne yönlendirmeden arka planda link çeker,
     * kendi kalite menümüzde "İndir" sunar.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private var dlSearchDialog: Dialog? = null
    private var menuDialog: Dialog? = null
    private var playlistQueue: ArrayList<Pair<String, String>> = ArrayList() // id to title
    private var playlistIndex: Int = 0
    private var playlistRunning: Boolean = false
    private var playlistAudioOnly: Boolean = false

    private fun showDlSearching() {
        if (dlSearchDialog?.isShowing == true) return
        val d = Dialog(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(22), dp(20), dp(22), dp(20))
        val bg = GradientDrawable()
        bg.setColor(cSurface)
        bg.cornerRadius = dp(18).toFloat()
        col.background = bg
        val tv = TextView(this)
        tv.text = "İndirme seçenekleri aranıyor…"
        tv.textSize = 15f
        tv.setTextColor(cText)
        tv.gravity = Gravity.CENTER
        col.addView(tv)
        d.setCancelable(true)
        d.setOnCancelListener { dlSearchDialog = null }
        sheetWindow(d, col, false)
        dlSearchDialog = d
        d.show()
    }

    private fun hideDlSearching() {
        try { dlSearchDialog?.dismiss() } catch (_: Exception) {}
        dlSearchDialog = null
    }

    private fun downloadCurrentMedia() {
        val t = tabs.getOrNull(cur) ?: return
        val page = t.url
        val isYt = page.contains("youtube.com") || page.contains("youtu.be") || page.contains("music.youtube.com")
        if (!isYt) {
            try {
                t.web.evaluateJavascript(
                    "(function(){try{" +
                    "var v=document.querySelector('video');" +
                    "if(v&&(v.currentSrc||v.src)&&String(v.currentSrc||v.src).indexOf('http')===0)" +
                    "return JSON.stringify({u:v.currentSrc||v.src,m:'video/mp4'});" +
                    "var a=document.querySelector('audio');" +
                    "if(a&&(a.currentSrc||a.src)&&String(a.currentSrc||a.src).indexOf('http')===0)" +
                    "return JSON.stringify({u:a.currentSrc||a.src,m:'audio/mpeg'});" +
                    "return '';}catch(e){return '';}})();"
                ) { raw ->
                    try {
                        if (raw == null || raw == "null" || raw == "\"\"") {
                            toast("İndirilebilir medya bulunamadı"); return@evaluateJavascript
                        }
                        val inner = org.json.JSONArray("[$raw]").getString(0)
                        if (inner.isBlank()) { toast("İndirilebilir medya bulunamadı"); return@evaluateJavascript }
                        val o = org.json.JSONObject(inner)
                        val u = o.optString("u")
                        if (u.startsWith("http")) promptDownload(u, null, null, o.optString("m", "video/mp4"))
                        else toast("İndirilebilir medya bulunamadı")
                    } catch (_: Exception) { toast("Medya adresi okunamadı") }
                }
            } catch (_: Exception) { toast("İndirme başlatılamadı") }
            return
        }

        val listId = try { Uri.parse(page).getQueryParameter("list") } catch (_: Exception) { null }
        offerYtDownloadMenu(page, listId)
    }

    private fun offerYtDownloadMenu(page: String, listId: String?) {
        val d = Dialog(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(18), dp(18), dp(18), dp(22))
        val rr = dp(28).toFloat()
        val bgd = GradientDrawable()
        bgd.setColor(cSurface)
        bgd.cornerRadii = floatArrayOf(rr, rr, rr, rr, 0f, 0f, 0f, 0f)
        col.background = bgd

        val title = TextView(this)
        title.text = "Ne indirilsin?"
        title.textSize = 18f
        title.setTypeface(null, Typeface.BOLD)
        title.setTextColor(cText)
        title.setPadding(0, 0, 0, dp(12))
        col.addView(title)

        fun addBtn(label: String, act: () -> Unit) {
            val btn = textBtn(label) { d.dismiss(); act() }
            btn.setTextColor(cText)
            btn.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            btn.setPadding(dp(16), 0, dp(16), 0)
            btn.background = shape(cField, 14)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50))
            lp.bottomMargin = dp(8)
            col.addView(btn, lp)
        }

        addBtn("Bu video MP4") { downloadSingleDirect(page, audioOnly = false) }
        addBtn("Bu video MP3") { downloadSingleDirect(page, audioOnly = true) }
        if (!listId.isNullOrBlank()) {
            addBtn("Listeden seç (MP4)") { loadPlaylistPicker(page, listId, audioOnly = false) }
            addBtn("Listeden seç (MP3)") { loadPlaylistPicker(page, listId, audioOnly = true) }
        }

        val cancel = textBtn("İptal") { d.dismiss() }
        cancel.setTextColor(cSub)
        cancel.gravity = Gravity.CENTER
        col.addView(cancel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        sheetWindow(d, col, false)
        d.show()
    }

    /** Liste kuyruğu ile aynı yol — seçenek menüsü / bekleme yok, direkt indir. */
    private fun downloadSingleDirect(page: String, audioOnly: Boolean) {
        val id = videoIdOf(page) ?: run {
            toast("Video bulunamadı")
            return
        }
        fetchVideoTitle(id) { t -> downloadSingleTitled(page, audioOnly, id, t) }
    }

    /** Videonun gerçek başlığını YouTube'dan alır; olmazsa oynatıcıdan dener. */
    private fun fetchVideoTitle(id: String, onDone: (String?) -> Unit) {
        Thread {
            var title: String? = null
            try {
                val u = java.net.URL("https://www.youtube.com/oembed?format=json&url=" + Uri.encode("https://www.youtube.com/watch?v=" + id))
                val c = u.openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 5000
                c.readTimeout = 5000
                c.setRequestProperty("User-Agent", "Mozilla/5.0")
                if (c.responseCode in 200..299) {
                    val t = JSONObject(c.inputStream.bufferedReader().readText()).optString("title").trim()
                    if (t.isNotBlank()) title = t
                }
                c.disconnect()
            } catch (_: Exception) { }
            val found = title
            handler.post {
                if (found != null) { onDone(found); return@post }
                val js = "(function(){try{var p=document.getElementById('movie_player');" +
                    "if(p&&p.getVideoData){var d=p.getVideoData();if(d&&d.title)return d.title;}" +
                    "var md=navigator.mediaSession&&navigator.mediaSession.metadata;if(md&&md.title)return md.title;" +
                    "return (document.title||'').replace(/\\s*[-|]\\s*YouTube( Music)?\\s*$/i,'');}catch(e){return '';}})();"
                val w = tabs.getOrNull(cur)?.web
                if (w == null) { onDone(null); return@post }
                try {
                    w.evaluateJavascript(js) { raw ->
                        val r = try { JSONArray("[" + raw + "]").getString(0) } catch (_: Exception) { "" }
                        onDone(if (r.isBlank() || r == "null") null else r)
                    }
                } catch (_: Exception) { onDone(null) }
            }
        }.start()
    }

    private fun downloadSingleTitled(page: String, audioOnly: Boolean, id: String, ytTitle: String?) {
        val title = ytTitle?.takeIf {
            it.isNotBlank() && !it.equals("YouTube", true) && !it.equals("YouTube Music", true)
        } ?: "video_$id"
        val base = safeFileName(title, id)
        val watch = "https://www.youtube.com/watch?v=$id"

        fun isAudio(label: String, url: String): Boolean {
            val t = "$label $url".lowercase()
            return t.contains("mp3") || t.contains("audio") || t.contains("m4a") ||
                t.contains("mime=audio") || t.contains("itag=140") || t.contains("itag=251") ||
                t.contains("itag=139") || t.contains("itag=249") || t.contains("itag=250")
        }
        fun looksProgressive(url: String): Boolean {
            val u = url.lowercase()
            return u.contains("ratebypass") || u.contains("itag=18") || u.contains("itag=22") ||
                u.contains("itag=37") ||
                (u.contains("mime=video") && u.contains("audio")) ||
                (u.contains("videoplayback") && !u.contains("mime=video") && !u.contains("mime=audio"))
        }
        fun pickAndStart(links: List<Pair<String, String>>) {
            val audio = links.firstOrNull { isAudio(it.first, it.second) }?.second
            val prog = links.firstOrNull { !isAudio(it.first, it.second) && looksProgressive(it.second) }?.second
            when {
                audioOnly && audio != null ->
                    startDownload(audio, null, null, "audio/mpeg", "$base.mp3")
                !audioOnly && prog != null ->
                    startDownload(prog, null, null, "video/mp4", "$base.mp4")
                audio != null ->
                    startDownload(audio, null, null, "audio/mpeg", "$base.mp3")
                else -> toast("İndirme linki alınamadı")
            }
        }

        // 1) Açık sayfadan hızlı
        val t = tabs.getOrNull(cur)
        try {
            t?.web?.evaluateJavascript(YT_EXTRACT_FORMATS_JS) { raw ->
                val links = parseYtFormatLinks(raw)
                if (links.isNotEmpty()) {
                    pickAndStart(links)
                    return@evaluateJavascript
                }
                // 2) InnerTube → 3) SaveFrom (sessiz)
                fetchViaInnertube(watch) { itLinks ->
                    if (itLinks.isNotEmpty()) pickAndStart(itLinks)
                    else fetchLinksViaSavefromSilent(watch) { pickAndStart(it) }
                }
            } ?: fetchViaInnertube(watch) { itLinks ->
                if (itLinks.isNotEmpty()) pickAndStart(itLinks)
                else fetchLinksViaSavefromSilent(watch) { pickAndStart(it) }
            }
        } catch (_: Exception) {
            fetchViaInnertube(watch) { itLinks ->
                if (itLinks.isNotEmpty()) pickAndStart(itLinks)
                else fetchLinksViaSavefromSilent(watch) { pickAndStart(it) }
            }
        }
    }

    /** Liste videolarını getir → tikli seçim ekranı (henüz indirmez). */
    private fun loadPlaylistPicker(page: String, listId: String, audioOnly: Boolean) {
        if (playlistRunning) {
            toast("Liste indirmesi sürüyor")
            return
        }
        showDlSearching()
        val t = tabs.getOrNull(cur)
        val extractPl = """
            (function(){try{
              var items=[],seen={};
              function isDuration(t){return /^\d{1,2}:\d{2}(:\d{2})?$/.test((t||'').trim());}
              function badTitle(t){
                t=(t||'').replace(/\s+/g,' ').trim().toLowerCase();
                if(!t)return true;
                if(isDuration(t))return true;
                if(t.length<2)return true;
                if(/^(uygulamada\s*a[cç]|open\s*(in\s*)?(the\s*)?app|share|payla[sş]|izle|watch|menu|menü|more|daha fazla|download|indir)/i.test(t))return true;
                if(/^[a-z0-9_-]{6,14}$/i.test(t) && !/\s/.test(t))return true; // çıplak id benzeri
                return false;
              }
              function cleanTitle(t){
                t=(t||'').replace(/\s+/g,' ').trim();
                if(badTitle(t))return '';
                if(t.length>90)t=t.slice(0,90);
                return t;
              }
              function textOf(node){
                if(!node)return '';
                if(typeof node==='string')return node;
                if(node.simpleText)return node.simpleText;
                if(node.runs&&node.runs.length)return node.runs.map(function(r){return r.text||'';}).join('');
                return '';
              }
              function thumbOf(r,id){
                try{
                  var th=r.thumbnail||r.thumbnails;
                  if(th&&th.thumbnails&&th.thumbnails.length){
                    var arr=th.thumbnails;return arr[arr.length-1].url||arr[0].url;
                  }
                }catch(e){}
                return 'https://i.ytimg.com/vi/'+id+'/hqdefault.jpg';
              }
              function durationOf(r){
                var d=textOf(r.lengthText)||textOf(r.length)||textOf(r.thumbnailOverlays&&r.thumbnailOverlays[0]&&r.thumbnailOverlays[0].thumbnailOverlayTimeStatusRenderer&&r.thumbnailOverlays[0].thumbnailOverlayTimeStatusRenderer.text)||'';
                d=(d||'').trim();
                return isDuration(d)?d:'';
              }
              function add(id,title,thumb,dur){
                if(!id||id.length<8||seen[id])return;
                title=cleanTitle(title);
                if(!title)title='Video';
                seen[id]=1;
                items.push({id:id,t:title,th:thumb||('https://i.ytimg.com/vi/'+id+'/hqdefault.jpg'),d:dur||''});
              }
              // Sadece playlist satır bileşenleri — rastgele videoId yürüme yok
              function walk(o,depth){
                if(!o||depth>16)return;
                if(Array.isArray(o)){for(var i=0;i<o.length;i++)walk(o[i],depth+1);return;}
                if(typeof o!=='object')return;
                var r=o.playlistVideoRenderer||o.playlistPanelVideoRenderer||null;
                if(r&&r.videoId){
                  add(r.videoId,textOf(r.title),thumbOf(r,r.videoId),durationOf(r));
                }
                // compact video in playlist panels
                if(o.compactVideoRenderer&&o.compactVideoRenderer.videoId){
                  var c=o.compactVideoRenderer;
                  add(c.videoId,textOf(c.title),thumbOf(c,c.videoId),durationOf(c));
                }
                for(var k in o){
                  if(k==='playlistVideoRenderer'||k==='playlistPanelVideoRenderer'||k==='compactVideoRenderer')continue;
                  try{walk(o[k],depth+1);}catch(e){}
                }
              }
              walk(window.ytInitialData,0);
              // DOM yedek: playlist satırları
              if(items.length<2){
                document.querySelectorAll('ytm-playlist-video-renderer,ytd-playlist-video-renderer,ytm-playlist-panel-video-renderer').forEach(function(el){
                  try{
                    var a=el.querySelector('a[href*="watch"]');
                    if(!a)return;
                    var u=new URL(a.href,location.href);var id=u.searchParams.get('v');
                    if(!id)return;
                    var title='';
                    var tEl=el.querySelector('#video-title,h4,.title,.playlist-video-title');
                    if(tEl)title=tEl.getAttribute('title')||tEl.textContent||'';
                    if(!title)title=a.getAttribute('title')||'';
                    var dur='';
                    var dEl=el.querySelector('.badge-shape-wiz__text,.yt-core-attributed-string,span.ytm-badge-and-byline-item-badge,.thumbnail-overlay-time-status-renderer');
                    // süre genelde küçük badge
                    var badges=el.querySelectorAll('span');
                    for(var i=0;i<badges.length;i++){
                      var bt=(badges[i].textContent||'').trim();
                      if(isDuration(bt)){dur=bt;break;}
                    }
                    var img=el.querySelector('img');
                    add(id,title,img&&(img.src||img.getAttribute('data-thumb')),dur);
                  }catch(e){}
                });
              }
              return JSON.stringify(items.slice(0,50));
            }catch(e){return '[]';}})();
        """.trimIndent()
        fun onItems(items: List<PlItem>) {
            hideDlSearching()
            if (items.isEmpty()) {
                toast("Liste videoları bulunamadı")
                return
            }
            showPlaylistPicker(items, audioOnly)
        }
        try {
            t?.web?.evaluateJavascript(extractPl) { raw ->
                val items = parsePlaylistItems(raw)
                if (items.isNotEmpty()) onItems(items)
                else fetchPlaylistItemsInnertube(listId) { onItems(it) }
            } ?: fetchPlaylistItemsInnertube(listId) { onItems(it) }
        } catch (_: Exception) {
            fetchPlaylistItemsInnertube(listId) { onItems(it) }
        }
    }

    data class PlItem(val id: String, val title: String, val thumb: String, val duration: String = "")

    private fun parsePlaylistItems(raw: String?): List<PlItem> {
        if (raw.isNullOrBlank() || raw == "null") return emptyList()
        return try {
            val inner = org.json.JSONArray("[$raw]").getString(0)
            val arr = org.json.JSONArray(inner)
            val out = ArrayList<PlItem>()
            val seen = HashSet<String>()
            val durRe = Regex("^\\d{1,2}:\\d{2}(:\\d{2})?$")
            val bad = Regex("^(uygulamada\\s*a[cç]|open\\s*(in\\s*)?(the\\s*)?app|share|payla[sş]|izle|watch)$", RegexOption.IGNORE_CASE)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (id.length < 8 || !seen.add(id)) continue
                var title = o.optString("t").trim()
                if (title.isBlank() || durRe.matches(title) || bad.containsMatchIn(title) || title == id) {
                    title = "Video"
                }
                val thumb = o.optString("th").ifBlank { "https://i.ytimg.com/vi/$id/hqdefault.jpg" }
                val dur = o.optString("d").trim().let { if (durRe.matches(it)) it else "" }
                out.add(PlItem(id, title, thumb, dur))
            }
            out
        } catch (_: Exception) { emptyList() }
    }

    private fun fetchPlaylistItemsInnertube(listId: String, onDone: (List<PlItem>) -> Unit) {
        Thread {
            val items = ArrayList<PlItem>()
            val seen = HashSet<String>()
            try {
                val cookie = try {
                    CookieManager.getInstance().getCookie("https://www.youtube.com") ?: ""
                } catch (_: Exception) { "" }
                val browseId = if (listId.startsWith("VL")) listId else "VL$listId"
                val body = JSONObject()
                    .put("context", JSONObject().put("client", JSONObject()
                        .put("clientName", "ANDROID")
                        .put("clientVersion", "19.28.35")
                        .put("androidSdkVersion", 33)
                        .put("hl", "tr")
                        .put("gl", "TR")
                    ))
                    .put("browseId", browseId)
                    .toString()
                val url = java.net.URL("https://www.youtube.com/youtubei/v1/browse?prettyPrint=false")
                val c = url.openConnection() as java.net.HttpURLConnection
                c.requestMethod = "POST"
                c.connectTimeout = 10000
                c.readTimeout = 10000
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.setRequestProperty("User-Agent", "com.google.android.youtube/19.28.35 (Linux; U; Android 13) gzip")
                c.setRequestProperty("X-YouTube-Client-Name", "3")
                c.setRequestProperty("X-YouTube-Client-Version", "19.28.35")
                if (cookie.isNotEmpty()) c.setRequestProperty("Cookie", cookie)
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = c.responseCode
                val stream = if (code in 200..299) c.inputStream else c.errorStream
                val resp = stream?.bufferedReader()?.readText() ?: ""
                c.disconnect()
                if (resp.isNotEmpty()) {
                    fun titleOf(node: JSONObject): String {
                        val t = node.opt("title")
                        when (t) {
                            is String -> if (t.isNotBlank()) return t
                            is JSONObject -> {
                                t.optString("simpleText").takeIf { it.isNotBlank() }?.let { return it }
                                val runs = t.optJSONArray("runs")
                                if (runs != null) {
                                    val sb = StringBuilder()
                                    for (i in 0 until runs.length()) {
                                        sb.append(runs.optJSONObject(i)?.optString("text") ?: "")
                                    }
                                    if (sb.isNotEmpty()) return sb.toString()
                                }
                            }
                        }
                        return ""
                    }
                    fun walk(node: Any?, depth: Int) {
                        if (depth > 14) return
                        when (node) {
                            is JSONObject -> {
                                val vid = node.optString("videoId")
                                if (vid.length >= 8 && seen.add(vid)) {
                                    var title = titleOf(node).ifBlank { "Video" }
                                    if (title.matches(Regex("^\\d{1,2}:\\d{2}(:\\d{2})?$"))) title = "Video"
                                    if (title.contains("Uygulamada", true) || title.contains("Open app", true)) title = "Video"
                                    val thumb = "https://i.ytimg.com/vi/$vid/hqdefault.jpg"
                                    items.add(PlItem(vid, title, thumb, ""))
                                }
                                val keys = node.keys()
                                while (keys.hasNext()) walk(node.opt(keys.next()), depth + 1)
                            }
                            is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i), depth + 1)
                        }
                    }
                    walk(JSONObject(resp), 0)
                }
            } catch (_: Exception) { }
            handler.post { onDone(items.take(50)) }
        }.start()
    }

    /** Tikli liste: tümünü seç / tek tek / indirmeyi başlat. */
    private fun showPlaylistPicker(items: List<PlItem>, audioOnly: Boolean) {
        val selected = BooleanArray(items.size) { true }
        val d = Dialog(this)
        val rootV = LinearLayout(this)
        rootV.orientation = LinearLayout.VERTICAL
        rootV.setBackgroundColor(cBg)
        rootV.setPadding(dp(14), dp(34), dp(14), dp(14))

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val tt = TextView(this)
        tt.text = if (audioOnly) "MP3 seç" else "MP4 seç"
        tt.textSize = 20f
        tt.setTypeface(null, Typeface.BOLD)
        tt.setTextColor(cText)
        head.addView(tt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val close = textBtn("Kapat") { d.dismiss() }
        close.setTextColor(cSub)
        head.addView(close)
        rootV.addView(head)

        val selectAll = textBtn("Tümünü seç / kaldır") { }
        selectAll.setTextColor(cAccent)
        selectAll.setTypeface(null, Typeface.BOLD)
        rootV.addView(selectAll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))

        val scroll = ScrollView(this)
        val listCol = LinearLayout(this)
        listCol.orientation = LinearLayout.VERTICAL
        listCol.setPadding(0, dp(8), 0, dp(12))

        val checks = ArrayList<android.widget.CheckBox>()
        for (i in items.indices) {
            val item = items[i]
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(8), dp(8), dp(10), dp(8))
            row.background = shape(cSurface, 14)

            val cb = android.widget.CheckBox(this)
            cb.isChecked = true
            try { cb.buttonTintList = ColorStateList.valueOf(cAccent) } catch (_: Exception) {}
            cb.setOnCheckedChangeListener { _, v -> selected[i] = v }
            checks.add(cb)
            row.addView(cb, LinearLayout.LayoutParams(dp(36), dp(36)))

            val thumb = ImageView(this)
            thumb.scaleType = ImageView.ScaleType.CENTER_CROP
            thumb.clipToOutline = true
            val tbg = GradientDrawable()
            tbg.setColor(cField)
            tbg.cornerRadius = dp(8).toFloat()
            thumb.background = tbg
            row.addView(thumb, LinearLayout.LayoutParams(dp(72), dp(40)).apply { marginEnd = dp(10) })
            // YouTube kapak
            Thread {
                try {
                    val url = item.thumb.ifBlank { "https://i.ytimg.com/vi/${item.id}/hqdefault.jpg" }
                    val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 4000
                    conn.readTimeout = 4000
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                    conn.connect()
                    val bmp = android.graphics.BitmapFactory.decodeStream(conn.inputStream)
                    conn.disconnect()
                    if (bmp != null) handler.post { thumb.setImageBitmap(bmp) }
                } catch (_: Exception) { }
            }.start()

            val mid = LinearLayout(this)
            mid.orientation = LinearLayout.VERTICAL
            mid.setPadding(0, 0, dp(6), 0)
            val name = TextView(this)
            name.text = item.title
            name.textSize = 14f
            name.setTextColor(cText)
            name.maxLines = 2
            name.ellipsize = android.text.TextUtils.TruncateAt.END
            mid.addView(name)
            row.addView(mid, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            row.setOnClickListener { cb.isChecked = !cb.isChecked }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(8)
            listCol.addView(row, lp)
        }
        scroll.addView(listCol)
        rootV.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        var allOn = true
        selectAll.setOnClickListener {
            allOn = !allOn
            for (i in checks.indices) {
                checks[i].isChecked = allOn
                selected[i] = allOn
            }
        }

        val startBtn = textBtn("İndirmeyi başlat") {
            val picked = ArrayList<Pair<String, String>>()
            for (i in items.indices) if (selected[i]) picked.add(items[i].id to items[i].title)
            if (picked.isEmpty()) {
                toast("En az bir video seç")
                return@textBtn
            }
            d.dismiss()
            playlistQueue = picked
            playlistIndex = 0
            playlistRunning = true
            playlistAudioOnly = audioOnly
            toast("${picked.size} video kuyruğa alındı")
            processPlaylistQueue()
        }
        startBtn.setTextColor(Color.WHITE)
        startBtn.gravity = Gravity.CENTER
        startBtn.background = shape(cAccent, 22)
        rootV.addView(startBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))

        sheetWindow(d, rootV, true)
        d.show()

    }

    private fun safeFileName(title: String, id: String): String {
        var t = title.trim()
            .replace(Regex("\\s*[-|]\\s*YouTube(\\s*Music)?\\s*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[\\\\/:*?\"<>|]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(80)
        if (t.isBlank() || t == id) t = "video_$id"
        return t
    }

    private fun processPlaylistQueue() {
        if (!playlistRunning) return
        if (playlistIndex >= playlistQueue.size) {
            playlistRunning = false
            toast("Liste indirmesi bitti")
            return
        }
        val (id, title) = playlistQueue[playlistIndex]
        val n = playlistIndex + 1
        val total = playlistQueue.size
        val watch = "https://www.youtube.com/watch?v=$id"
        val base = safeFileName(title, id)

        fun advance() {
            playlistIndex++
            handler.postDelayed({ processPlaylistQueue() }, 3000L)
        }

        fun tryDownload(links: List<Pair<String, String>>) {
            fun isAudio(label: String, url: String): Boolean {
                val t = "$label $url".lowercase()
                return t.contains("mp3") || t.contains("audio") || t.contains("m4a") ||
                    t.contains("mime=audio") || t.contains("itag=140") || t.contains("itag=251") ||
                    t.contains("itag=139") || t.contains("itag=249") || t.contains("itag=250")
            }
            fun looksProgressive(url: String): Boolean {
                val u = url.lowercase()
                return u.contains("ratebypass") || u.contains("itag=18") || u.contains("itag=22") ||
                    u.contains("itag=37") ||
                    (u.contains("mime=video") && u.contains("audio")) ||
                    (u.contains("videoplayback") && !u.contains("mime=video") && !u.contains("mime=audio"))
            }
            val audio = links.firstOrNull { isAudio(it.first, it.second) }?.second
            val prog = links.firstOrNull { !isAudio(it.first, it.second) && looksProgressive(it.second) }?.second
            when {
                playlistAudioOnly && audio != null -> {
                    startDownload(audio, null, null, "audio/mpeg", "$base.mp3")
                    advance()
                }
                !playlistAudioOnly && prog != null -> {
                    startDownload(prog, null, null, "video/mp4", "$base.mp4")
                    advance()
                }
                audio != null -> {
                    // MP4 istendi ama sadece ses bulundu
                    startDownload(audio, null, null, "audio/mpeg", "$base.mp3")
                    advance()
                }
                else -> {
                    // SaveFrom yedek (sessiz)
                    fetchLinksViaSavefromSilent(watch) { sfLinks ->
                        val sfAudio = sfLinks.firstOrNull { isAudio(it.first, it.second) }?.second
                        val sfProg = sfLinks.firstOrNull { !isAudio(it.first, it.second) && looksProgressive(it.second) }?.second
                        when {
                            playlistAudioOnly && sfAudio != null ->
                                startDownload(sfAudio, null, null, "audio/mpeg", "$base.mp3")
                            sfProg != null ->
                                startDownload(sfProg, null, null, "video/mp4", "$base.mp4")
                            sfAudio != null ->
                                startDownload(sfAudio, null, null, "audio/mpeg", "$base.mp3")
                            else -> { /* atla */ }
                        }
                        advance()
                    }
                }
            }
        }

        fetchViaInnertube(watch) { links ->
            if (links.isNotEmpty()) tryDownload(links)
            else fetchLinksViaSavefromSilent(watch) { tryDownload(it) }
        }
    }

    /** Liste kuyruğu için SaveFrom — UI toast yok. */
    private fun fetchLinksViaSavefromSilent(page: String, onDone: (List<Pair<String, String>>) -> Unit) {
        val loader = WebView(this)
        loader.settings.javaScriptEnabled = true
        loader.settings.domStorageEnabled = true
        loader.settings.userAgentString =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        var finished = false
        fun finish(links: List<Pair<String, String>>) {
            if (finished) return
            finished = true
            try { loader.stopLoading(); loader.destroy() } catch (_: Exception) {}
            try { (loader.parent as? ViewGroup)?.removeView(loader) } catch (_: Exception) {}
            onDone(links)
        }
        val extractJs =
            "(function(){try{" +
            "var out=[];" +
            "function ok(u){if(!u||u.indexOf('http')!==0)return false;" +
            "return /googlevideo\\.com|videoplayback|sf-tools\\.com|mime=video|mime=audio|itag=/i.test(u);}" +
            "function add(u,l){if(!ok(u))return;for(var i=0;i<out.length;i++)if(out[i].u===u)return;" +
            "out.push({u:u,l:(l||'İndir').slice(0,40)});}" +
            "document.querySelectorAll('a[href],[data-link],[data-url]').forEach(function(el){" +
            "add(el.href||el.getAttribute('data-link')||el.getAttribute('data-url')||'',el.textContent||'');});" +
            "return JSON.stringify(out.slice(0,20));" +
            "}catch(e){return '[]';}})();"
        var tries = 0
        fun poll() {
            if (finished) return
            tries++
            try {
                if (tries <= 3) {
                    loader.evaluateJavascript(
                        "(function(){try{var b=document.querySelector('button[type=submit],.def-btn,#sf_submit');if(b)b.click();" +
                        "var inp=document.querySelector('input[type=text],#sf_url');if(inp&&!inp.value)inp.value=" +
                        JSONObject.quote(page) + ";}catch(e){}})();", null)
                }
                loader.evaluateJavascript(extractJs) { raw ->
                    try {
                        val inner = org.json.JSONArray("[$raw]").getString(0)
                        val arr = org.json.JSONArray(inner)
                        val list = ArrayList<Pair<String, String>>()
                        for (i in 0 until arr.length()) {
                            val o = arr.getJSONObject(i)
                            val u = o.optString("u")
                            if (u.startsWith("http")) list.add(o.optString("l", "İndir") to u)
                        }
                        if (list.isNotEmpty()) finish(list)
                        else if (tries < 10) handler.postDelayed({ poll() }, 900)
                        else finish(emptyList())
                    } catch (_: Exception) {
                        if (tries < 10) handler.postDelayed({ poll() }, 900) else finish(emptyList())
                    }
                }
            } catch (_: Exception) {
                if (tries < 10) handler.postDelayed({ poll() }, 900) else finish(emptyList())
            }
        }
        loader.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                handler.postDelayed({ poll() }, 1600)
            }
        }
        loader.layoutParams = ViewGroup.LayoutParams(1, 1)
        try { (window.decorView as? ViewGroup)?.addView(loader) } catch (_: Exception) {}
        loader.loadUrl("https://en.savefrom.net/" + page)
        handler.postDelayed({ if (!finished) finish(emptyList()) }, 14000)
    }

    private fun videoIdOf(page: String): String? {
        return try {
            val u = Uri.parse(page)
            val v = u.getQueryParameter("v")
            if (!v.isNullOrBlank()) return v
            val path = u.path ?: return null
            val parts = path.trim('/').split('/')
            when {
                page.contains("youtu.be") && parts.isNotEmpty() -> parts[0].takeWhile { it.isLetterOrDigit() || it == '_' || it == '-' }
                parts.size >= 2 && (parts[0] == "shorts" || parts[0] == "embed" || parts[0] == "live") -> parts[1]
                else -> null
            }?.takeIf { it.length >= 8 }
        } catch (_: Exception) { null }
    }

    private fun parseYtFormatLinks(raw: String?): List<Pair<String, String>> {
        if (raw.isNullOrBlank() || raw == "null" || raw == "\"\"") return emptyList()
        return try {
            val inner = org.json.JSONArray("[$raw]").getString(0)
            if (inner.isBlank() || inner == "null") return emptyList()
            val arr = org.json.JSONArray(inner)
            val out = ArrayList<Pair<String, String>>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val u = o.optString("u")
                if (!u.startsWith("http")) continue
                val kind = o.optString("k")
                val q = o.optString("q")
                val itag = o.optInt("itag", 0)
                val label = when {
                    kind == "a" || o.optBoolean("audio") -> "MP3"
                    q.isNotBlank() -> "MP4 $q"
                    itag == 18 -> "MP4 360p"
                    itag == 22 -> "MP4 720p"
                    else -> "MP4"
                }
                out.add(label to u)
            }
            out
        } catch (_: Exception) { emptyList() }
    }

    private fun fetchViaInnertube(page: String, onDone: (List<Pair<String, String>>) -> Unit) {
        val id = videoIdOf(page) ?: page.substringAfter("v=", "").take(11).takeIf { it.length >= 8 }
        if (id == null) { onDone(emptyList()); return }
        Thread {
            val links = ArrayList<Pair<String, String>>()
            try {
                val cookie = try {
                    CookieManager.getInstance().getCookie("https://www.youtube.com") ?: ""
                } catch (_: Exception) { "" }
                val body = JSONObject()
                    .put("context", JSONObject().put("client", JSONObject()
                        .put("clientName", "ANDROID")
                        .put("clientVersion", "19.28.35")
                        .put("androidSdkVersion", 33)
                        .put("hl", "tr")
                        .put("gl", "TR")
                        .put("userAgent", "com.google.android.youtube/19.28.35 (Linux; U; Android 13) gzip")
                    ))
                    .put("videoId", id)
                    .put("contentCheckOk", true)
                    .put("racyCheckOk", true)
                    .toString()
                val url = java.net.URL("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
                val c = url.openConnection() as java.net.HttpURLConnection
                c.requestMethod = "POST"
                c.connectTimeout = 8000
                c.readTimeout = 8000
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.setRequestProperty("User-Agent", "com.google.android.youtube/19.28.35 (Linux; U; Android 13) gzip")
                c.setRequestProperty("X-YouTube-Client-Name", "3")
                c.setRequestProperty("X-YouTube-Client-Version", "19.28.35")
                if (cookie.isNotEmpty()) c.setRequestProperty("Cookie", cookie)
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = c.responseCode
                val stream = if (code in 200..299) c.inputStream else c.errorStream
                val respText = stream?.bufferedReader()?.readText() ?: ""
                c.disconnect()
                if (respText.isNotEmpty()) {
                    val root = JSONObject(respText)
                    val sd = root.optJSONObject("streamingData") ?: JSONObject()
                    fun eat(arr: JSONArray?, audioOnly: Boolean) {
                        if (arr == null) return
                        for (i in 0 until arr.length()) {
                            val f = arr.optJSONObject(i) ?: continue
                            val u = f.optString("url")
                            if (!u.startsWith("http")) continue
                            val mime = f.optString("mimeType").lowercase()
                            val itag = f.optInt("itag")
                            val q = f.optString("qualityLabel")
                            val isAudio = audioOnly || mime.contains("audio") ||
                                itag in listOf(139, 140, 141, 249, 250, 251)
                            if (isAudio) links.add("MP3" to u)
                            else {
                                val progressive = itag in listOf(18, 22, 37) || u.contains("ratebypass") ||
                                    (mime.contains("video") && mime.contains("audio"))
                                if (progressive) {
                                    val lab = when {
                                        q.isNotBlank() -> "MP4 $q"
                                        itag == 18 -> "MP4 360p"
                                        itag == 22 -> "MP4 720p"
                                        else -> "MP4"
                                    }
                                    links.add(lab to u)
                                }
                            }
                        }
                    }
                    eat(sd.optJSONArray("formats"), false)
                    val ad = sd.optJSONArray("adaptiveFormats")
                    if (ad != null) {
                        for (i in 0 until ad.length()) {
                            val f = ad.optJSONObject(i) ?: continue
                            val u = f.optString("url")
                            if (!u.startsWith("http")) continue
                            val mime = f.optString("mimeType").lowercase()
                            val itag = f.optInt("itag")
                            if (mime.contains("audio") || itag in listOf(139, 140, 141, 249, 250, 251)) {
                                links.add("MP3" to u)
                            }
                        }
                    }
                }
            } catch (_: Exception) { }
            handler.post { onDone(links) }
        }.start()
    }

    private fun fetchViaSavefrom(page: String) {
        val loader = WebView(this)
        loader.settings.javaScriptEnabled = true
        loader.settings.domStorageEnabled = true
        loader.settings.userAgentString =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        var finished = false
        fun finishWith(links: List<Pair<String, String>>) {
            if (finished) return
            finished = true
            try { loader.stopLoading(); loader.destroy() } catch (_: Exception) {}
            try { (loader.parent as? ViewGroup)?.removeView(loader) } catch (_: Exception) {}
            if (links.isEmpty()) {
                hideDlSearching()
                toast("İndirme linki alınamadı")
            } else showFetchedDownloadOptions(links)
        }
        val extractJs =
            "(function(){try{" +
            "var out=[];" +
            "function ok(u){" +
            "if(!u||u.indexOf('http')!==0)return false;" +
            "if(/savefrom\\.net|ssyoutube|facebook|instagram|tiktok|reddit|pinterest|dailymotion|vimeo|vk\\.com|threads|play\\.google/i.test(u)&&!/googlevideo|videoplayback|sf-tools/i.test(u))return false;" +
            "return /googlevideo\\.com|videoplayback|sf-tools\\.com|mime=video|mime=audio|itag=/i.test(u);" +
            "}" +
            "function add(u,l){if(!ok(u))return;for(var i=0;i<out.length;i++)if(out[i].u===u)return;" +
            "out.push({u:u,l:(l||'İndir').replace(/\\s+/g,' ').trim().slice(0,40)});" +
            "}" +
            "document.querySelectorAll('a[href],[data-link],[data-url],[data-download],[data-href]').forEach(function(el){" +
            "add(el.href||el.getAttribute('data-link')||el.getAttribute('data-url')||el.getAttribute('data-download')||el.getAttribute('data-href')||'',el.textContent||'');" +
            "});" +
            "return JSON.stringify(out.slice(0,25));" +
            "}catch(e){return '[]';}})();"
        var tries = 0
        fun poll() {
            if (finished) return
            tries++
            try {
                if (tries == 1 || tries == 3) {
                    loader.evaluateJavascript(
                        "(function(){try{" +
                        "var b=document.querySelector('button[type=submit],.def-btn,.btn-green,#sf_submit,.convert-btn,input[type=submit]');" +
                        "if(b)b.click();" +
                        "var inp=document.querySelector('input[type=text],input[name=sf_url],#sf_url');" +
                        "if(inp&&!inp.value){inp.value=" + JSONObject.quote(page) + ";}" +
                        "}catch(e){}})();", null)
                }
                loader.evaluateJavascript(extractJs) { raw ->
                    try {
                        val inner = org.json.JSONArray("[$raw]").getString(0)
                        val arr = org.json.JSONArray(inner)
                        val list = ArrayList<Pair<String, String>>()
                        for (i in 0 until arr.length()) {
                            val o = arr.getJSONObject(i)
                            val u = o.optString("u")
                            val l = o.optString("l", "İndir")
                            if (u.contains("googlevideo") || u.contains("videoplayback") ||
                                u.contains("sf-tools") || u.contains("itag=") ||
                                u.contains("mime=video") || u.contains("mime=audio")) {
                                list.add(l to u)
                            }
                        }
                        if (list.isNotEmpty()) finishWith(list)
                        else if (tries < 14) handler.postDelayed({ poll() }, 900)
                        else finishWith(emptyList())
                    } catch (_: Exception) {
                        if (tries < 14) handler.postDelayed({ poll() }, 900) else finishWith(emptyList())
                    }
                }
            } catch (_: Exception) {
                if (tries < 14) handler.postDelayed({ poll() }, 900) else finishWith(emptyList())
            }
        }
        loader.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                handler.postDelayed({ poll() }, 1800)
            }
        }
        loader.layoutParams = ViewGroup.LayoutParams(1, 1)
        try { (window.decorView as? ViewGroup)?.addView(loader) } catch (_: Exception) {}
        loader.loadUrl("https://en.savefrom.net/" + page)
        handler.postDelayed({ if (!finished) finishWith(emptyList()) }, 18000)
    }

    private fun showFetchedDownloadOptions(links: List<Pair<String, String>>) {
        fun heightOf(s: String): Int {
            val m = Regex("(\\d{3,4})\\s*p", RegexOption.IGNORE_CASE).find(s)
            return m?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        }
        fun isAudio(label: String, url: String): Boolean {
            val t = "$label $url".lowercase()
            return t.contains("mp3") || t.contains("audio") || t.contains("m4a") ||
                t.contains("mime=audio") || t.contains("itag=140") || t.contains("itag=251") ||
                t.contains("itag=139") || t.contains("itag=249") || t.contains("itag=250")
        }
        fun looksProgressive(url: String): Boolean {
            val u = url.lowercase()
            return u.contains("ratebypass") ||
                u.contains("itag=18") || u.contains("itag=22") || u.contains("itag=37") ||
                (u.contains("mime=video") && u.contains("audio")) ||
                (u.contains("videoplayback") && !u.contains("mime=video") && !u.contains("mime=audio"))
        }

        val audioLinks = links.filter { isAudio(it.first, it.second) }
        val bestAudio = audioLinks.maxByOrNull { it.second.length }?.second
        val progressive = links.filter { !isAudio(it.first, it.second) && looksProgressive(it.second) }

        data class Opt(val label: String, val url: String, val audioOnly: Boolean)
        val opts = ArrayList<Opt>()
        val seenH = HashSet<Int>()
        for (v in progressive.sortedBy {
            heightOf(it.first + " " + it.second).let { h -> if (h > 0) h else 9999 }
        }) {
            val h = heightOf(v.first + " " + v.second)
            if (h > 0 && !seenH.add(h)) continue
            val lab = if (h > 0) "MP4 ${h}p" else v.first.ifBlank { "MP4" }
            opts.add(Opt(lab, v.second, false))
            if (opts.size >= 4) break
        }
        if (bestAudio != null) opts.add(Opt("MP3", bestAudio, true))

        hideDlSearching()
        if (opts.isEmpty()) {
            toast("İndirme linki alınamadı")
            return
        }

        val d = Dialog(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(18), dp(18), dp(18), dp(22))
        val rr = dp(28).toFloat()
        val bgd = GradientDrawable()
        bgd.setColor(cSurface)
        bgd.cornerRadii = floatArrayOf(rr, rr, rr, rr, 0f, 0f, 0f, 0f)
        col.background = bgd
        val title = TextView(this)
        title.text = "İndirme seçenekleri"
        title.textSize = 18f
        title.setTypeface(null, Typeface.BOLD)
        title.setTextColor(cText)
        title.setPadding(0, 0, 0, dp(12))
        col.addView(title)
        for (opt in opts) {
            val btn = textBtn(opt.label) {
                d.dismiss()
                val base = mediaFileBase()
                if (opt.audioOnly) startDownload(opt.url, null, null, "audio/mpeg", "$base.mp3")
                else startDownload(opt.url, null, null, "video/mp4", "$base.mp4")
            }
            btn.setTextColor(cText)
            btn.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            btn.setPadding(dp(16), 0, dp(16), 0)
            btn.background = shape(cField, 14)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50))
            lp.bottomMargin = dp(8)
            col.addView(btn, lp)
        }
        val cancel = textBtn("İptal") { d.dismiss() }
        cancel.setTextColor(cSub)
        cancel.gravity = Gravity.CENTER
        col.addView(cancel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        sheetWindow(d, col, false)
        d.show()
    }

    private fun downloadAndMerge(videoUrl: String, audioUrl: String, outName: String) {
        toast("MP4 indiriliyor: $outName")
        Thread {
            try {
                val dir = cacheDir
                val vFile = java.io.File(dir, "v_${System.currentTimeMillis()}.mp4")
                val aFile = java.io.File(dir, "a_${System.currentTimeMillis()}.m4a")
                val oFile = java.io.File(dir, outName)
                if (!httpToFile(videoUrl, vFile) || !httpToFile(audioUrl, aFile)) {
                    handler.post { toast("İndirme başarısız") }
                    return@Thread
                }
                val ok = muxAv(vFile.absolutePath, aFile.absolutePath, oFile.absolutePath)
                vFile.delete(); aFile.delete()
                if (!ok || !oFile.exists()) {
                    handler.post {
                        startDownload(videoUrl, null, null, "video/mp4", outName)
                    }
                    return@Thread
                }
                // Public Downloads'a kopyala
                val resolver = contentResolver
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, outName)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, "video/mp4")
                    if (Build.VERSION.SDK_INT >= 29) {
                        put(android.provider.MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                    }
                }
                val uri = if (Build.VERSION.SDK_INT >= 29) {
                    resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                } else {
                    val dest = java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), outName)
                    oFile.copyTo(dest, true)
                    android.net.Uri.fromFile(dest)
                }
                if (uri != null && Build.VERSION.SDK_INT >= 29) {
                    resolver.openOutputStream(uri)?.use { out ->
                        oFile.inputStream().use { it.copyTo(out) }
                    }
                    values.clear()
                    values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
                oFile.delete()
                val path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath + "/" + outName
                handler.post {
                    prefs.addDownload(outName, videoUrl, path, "video/mp4")
                    toast("MP4 indirildi: $outName")
                }
            } catch (_: Exception) {
                handler.post {
                    startDownload(videoUrl, null, null, "video/mp4", outName)
                }
            }
        }.start()
    }

    private fun httpToFile(url: String, dest: java.io.File): Boolean {
        return try {
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 20000
            conn.readTimeout = 120000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36")
            conn.setRequestProperty("Referer", "https://www.youtube.com/")
            val cookie = CookieManager.getInstance().getCookie(url)
                ?: CookieManager.getInstance().getCookie("https://www.youtube.com/")
            if (cookie != null) conn.setRequestProperty("Cookie", cookie)
            conn.inputStream.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            dest.length() > 0
        } catch (_: Exception) { false }
    }

    private fun muxAv(videoPath: String, audioPath: String, outPath: String): Boolean {
        return try {
            val vExt = android.media.MediaExtractor()
            vExt.setDataSource(videoPath)
            val aExt = android.media.MediaExtractor()
            aExt.setDataSource(audioPath)
            val muxer = android.media.MediaMuxer(outPath, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var vTrack = -1
            var aTrack = -1
            var vIdx = -1
            var aIdx = -1
            for (i in 0 until vExt.trackCount) {
                val fmt = vExt.getTrackFormat(i)
                val mime = fmt.getString(android.media.MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) { vIdx = i; vTrack = muxer.addTrack(fmt); break }
            }
            for (i in 0 until aExt.trackCount) {
                val fmt = aExt.getTrackFormat(i)
                val mime = fmt.getString(android.media.MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) { aIdx = i; aTrack = muxer.addTrack(fmt); break }
            }
            if (vTrack < 0 || aTrack < 0) {
                vExt.release(); aExt.release(); muxer.release(); return false
            }
            vExt.selectTrack(vIdx)
            aExt.selectTrack(aIdx)
            muxer.start()
            val buf = java.nio.ByteBuffer.allocate(1024 * 1024)
            val info = android.media.MediaCodec.BufferInfo()
            while (true) {
                val size = vExt.readSampleData(buf, 0)
                if (size < 0) break
                info.offset = 0; info.size = size
                info.presentationTimeUs = vExt.sampleTime
                info.flags = vExt.sampleFlags
                muxer.writeSampleData(vTrack, buf, info)
                vExt.advance()
            }
            while (true) {
                val size = aExt.readSampleData(buf, 0)
                if (size < 0) break
                info.offset = 0; info.size = size
                info.presentationTimeUs = aExt.sampleTime
                info.flags = aExt.sampleFlags
                muxer.writeSampleData(aTrack, buf, info)
                aExt.advance()
            }
            muxer.stop(); muxer.release()
            vExt.release(); aExt.release()
            true
        } catch (_: Exception) { false }
    }

    private fun mediaKindOf(name: String, mime: String): String? {
        val n = name.lowercase()
        return when {
            mime.startsWith("video") || n.endsWith(".mp4") || n.endsWith(".webm") || n.endsWith(".mkv") ||
                n.endsWith(".3gp") || n.endsWith(".mov") || n.endsWith(".m4v") -> "video"
            mime.startsWith("audio") || n.endsWith(".mp3") || n.endsWith(".m4a") || n.endsWith(".aac") ||
                n.endsWith(".ogg") || n.endsWith(".opus") || n.endsWith(".wav") || n.endsWith(".flac") -> "audio"
            else -> null
        }
    }

    /** İndirilen video / müziği yeni ExoPlayer oynatıcısında açar (aynı türdeki indirilenler liste olur). */
    private fun openMediaPlayer(file: java.io.File, title: String, audio: Boolean) {
        for (t in tabs) { try { t.web.evaluateJavascript(MEDIA_PAUSE_JS, null) } catch (_: Exception) { } }
        val want = if (audio) "audio" else "video"
        val same = prefs.downloads().filter {
            mediaKindOf(it.title, it.mime) == want && java.io.File(it.path).exists()
        }
        val paths = ArrayList<String>()
        val titles = ArrayList<String>()
        for (e in same) { paths.add(e.path); titles.add(e.title) }
        var idx = paths.indexOf(file.absolutePath)
        if (idx < 0) { paths.add(0, file.absolutePath); titles.add(0, title); idx = 0 }
        val i = Intent(this, MediaPlayerActivity::class.java)
            .putStringArrayListExtra(MediaPlayerActivity.EXTRA_PATHS, paths)
            .putStringArrayListExtra(MediaPlayerActivity.EXTRA_TITLES, titles)
            .putExtra(MediaPlayerActivity.EXTRA_INDEX, idx)
        try { startActivity(i) } catch (_: Exception) { toast("Oynatıcı açılamadı") }
    }

    private fun isLocalViewable(name: String, mime: String): Boolean {
        val n = name.lowercase()
        val m = mime.lowercase()
        val exts = listOf(".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".rtf", ".txt", ".csv", ".md",
            ".json", ".log", ".xml", ".html", ".htm", ".yml", ".yaml", ".ini",
            ".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".svg")
        return exts.any { n.endsWith(it) } || m.contains("pdf") || m.startsWith("text/") || m.startsWith("image/") ||
            m.contains("wordprocessingml") || m.contains("spreadsheetml") || m.contains("presentationml") ||
            m.contains("msword") || m.contains("ms-excel") || m.contains("ms-powerpoint") || m.contains("rtf")
    }

    /** İndirilen dosya başka bir paket (DownloadManager) adına kaydedildiği için yoldan okunamayabilir; gerekirse kopyasını alır. */
    private fun resolveReadable(f: java.io.File, e: Prefs.DownloadEntry): java.io.File? {
        try {
            if (f.length() > 0) { f.inputStream().use { it.read() }; return f }
        } catch (_: Exception) { }
        try {
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            var id = -1L
            dm.query(DownloadManager.Query().setFilterByStatus(DownloadManager.STATUS_SUCCESSFUL))?.use { c ->
                val iT = c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)
                val iId = c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
                while (c.moveToNext()) {
                    if (c.getString(iT) == e.title) { id = c.getLong(iId); break }
                }
            }
            if (id >= 0) {
                val uri = dm.getUriForDownloadedFile(id)
                if (uri != null) {
                    val dir = java.io.File(cacheDir, "docs")
                    dir.mkdirs()
                    val out = java.io.File(dir, e.title.replace(Regex("[\\\\/:*?\"<>|]+"), "_"))
                    contentResolver.openInputStream(uri)?.use { ins -> out.outputStream().use { o -> ins.copyTo(o) } }
                    if (out.length() > 0) return out
                }
            }
        } catch (_: Exception) { }
        return null
    }

    private fun openLocalDocument(f: java.io.File, e: Prefs.DownloadEntry) {
        Thread {
            val rf = resolveReadable(f, e)
            handler.post {
                if (rf == null) { toast("Dosya okunamadı"); return@post }
                val i = Intent(this, DocumentViewerActivity::class.java)
                    .putExtra(DocumentViewerActivity.EXTRA_PATH, rf.absolutePath)
                    .putExtra(DocumentViewerActivity.EXTRA_NAME, e.title)
                    .putExtra(DocumentViewerActivity.EXTRA_MIME, e.mime)
                    .putExtra(DocumentViewerActivity.EXTRA_URL, e.url)
                try { startActivity(i) } catch (_: Exception) { toast("Dosya açılamadı") }
            }
        }.start()
    }

    /** Uygulama içi İndirilenler listesi. */
    private fun showDownloads() {
        val items = ArrayList(prefs.downloads())
        val d = Dialog(this)
        pushUiOverlay()
        d.setOnDismissListener { popUiOverlay() }
        val rootV = LinearLayout(this)
        rootV.orientation = LinearLayout.VERTICAL
        rootV.setBackgroundColor(cBg)
        rootV.setPadding(dp(14), dp(34), dp(14), dp(14))

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val tt = TextView(this)
        tt.text = "İndirilenler"
        tt.textSize = 20f
        tt.setTypeface(null, Typeface.BOLD)
        tt.setTextColor(cText)
        head.addView(tt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (items.isNotEmpty()) {
            val clear = textBtn("Temizle") {
                // Seçmeli silme: önce neyin silineceği seçilir, sonra listeden mi telefondan mı silineceği sorulur
                showDownloadsSelect { d.dismiss(); showDownloads() }
            }
            clear.setTextColor(cAccent)
            head.addView(clear)
        }
        rootV.addView(head)

        if (items.isEmpty()) {
            val empty = TextView(this)
            empty.text = "Henüz indirilen dosya yok.\nİndirdiğin dosyalar burada görünür."
            empty.textSize = 15f
            empty.setTextColor(cSub)
            empty.gravity = Gravity.CENTER
            empty.setPadding(0, dp(40), 0, 0)
            rootV.addView(empty)
        } else {
            val scroll = ScrollView(this)
            val listCol = LinearLayout(this)
            listCol.orientation = LinearLayout.VERTICAL
            listCol.setPadding(0, dp(12), 0, dp(20))
            for (e in items) {
                val row = LinearLayout(this)
                row.orientation = LinearLayout.HORIZONTAL
                row.gravity = Gravity.CENTER_VERTICAL
                row.setPadding(dp(12), dp(12), dp(12), dp(12))
                row.background = shape(cSurface, 14)
                val icon = TextView(this)
                icon.text = when {
                    e.mime.startsWith("video") || e.title.lowercase().endsWith(".mp4") -> "🎬"
                    e.mime.startsWith("audio") || e.title.lowercase().endsWith(".mp3") -> "🎵"
                    e.mime.startsWith("image") -> "🖼️"
                    e.mime.contains("pdf") || e.title.lowercase().endsWith(".pdf") -> "📄"
                    else -> "📁"
                }
                icon.textSize = 22f
                row.addView(icon, LinearLayout.LayoutParams(dp(36), dp(36)).apply { rightMargin = dp(10) })
                val info = LinearLayout(this)
                info.orientation = LinearLayout.VERTICAL
                val nameTv = TextView(this)
                nameTv.text = e.title
                nameTv.textSize = 15f
                nameTv.setTextColor(cText)
                nameTv.maxLines = 1
                nameTv.ellipsize = android.text.TextUtils.TruncateAt.END
                val subTv = TextView(this)
                subTv.text = try {
                    java.text.SimpleDateFormat("dd MMM yyyy HH:mm", java.util.Locale("tr")).format(java.util.Date(e.time))
                } catch (_: Exception) { "" }
                subTv.textSize = 12f
                subTv.setTextColor(cSub)
                info.addView(nameTv)
                info.addView(subTv)
                row.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.setOnClickListener {
                    try {
                        val f = java.io.File(e.path)
                        if (f.exists()) {
                            val kind = mediaKindOf(e.title, e.mime)
                                      if (kind != null) {
                                          openMediaPlayer(f, e.title, kind == "audio")
                                      } else if (isLocalViewable(e.title, e.mime)) {
                                          openLocalDocument(f, e)
                                      } else {
                                          val uri = androidx.core.content.FileProvider.getUriForFile(this, "com.pusula.browser.files", f)
                                          val open = Intent(Intent.ACTION_VIEW).setDataAndType(uri, e.mime.ifBlank { "*/*" })
                                          open.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                                          try {
                                              startActivity(Intent.createChooser(open, "Aç"))
                                          } catch (_: Exception) {
                                              toast("Bu dosyayı açacak uygulama yok: " + e.title)
                                          }
                                      }
                                  } else if (e.url.startsWith("http")) {
                            openTab(e.url)
                            d.dismiss()
                        } else {
                            toast("Dosya bulunamadı")
                        }
                    } catch (_: Exception) {
                        toast("Dosya açılamadı")
                    }
                }
                row.setOnLongClickListener {
                    themedDeleteDownload(e.title, e.path) {
                        d.dismiss()
                        showDownloads()
                    }
                    true
                }
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.bottomMargin = dp(8)
                listCol.addView(row, lp)
            }
            scroll.addView(listCol)
            rootV.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }

        val close = textBtn("Kapat") { d.dismiss() }
        close.setTextColor(cSub)
        close.gravity = Gravity.CENTER
        rootV.addView(close, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))

        sheetWindow(d, rootV, true)
        d.show()
    }

    /** Dosyayı telefondan siler (önce doğrudan, olmazsa MediaStore üzerinden). */
    private fun deleteDownloadFile(path: String): Boolean {
        var ok = false
        try {
            val f = java.io.File(path)
            if (f.exists()) ok = f.delete()
            if (!ok && Build.VERSION.SDK_INT >= 29) {
                try {
                    contentResolver.query(
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        arrayOf(android.provider.MediaStore.MediaColumns._ID),
                        android.provider.MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                        arrayOf(f.name),
                        null
                    )?.use { c ->
                        if (c.moveToFirst()) {
                            val id = c.getLong(0)
                            val u = android.content.ContentUris.withAppendedId(
                                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, id
                            )
                            ok = contentResolver.delete(u, null, null) > 0
                        }
                    }
                } catch (_: Exception) { }
            }
        } catch (_: Exception) { }
        return ok
    }

    /** Seçilen indirmeler için silme sorusu: tek dosyada uzun basınca çıkan pencereyle aynı görünüm ve sıra. */
    private fun themedDeleteMany(picked: List<Prefs.DownloadEntry>, after: () -> Unit) {
        val n = picked.size
        val d = Dialog(this)
        try { d.window?.setBackgroundDrawableResource(android.R.color.transparent) } catch (_: Exception) { }
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(22), dp(20), dp(22), dp(18))
        val bg = GradientDrawable()
        bg.setColor(cSurface)
        bg.cornerRadius = dp(24).toFloat()
        col.background = bg
        val msg = TextView(this)
        msg.text = if (n == 1) "«" + picked[0].title + "» silinsin mi?" else "Seçilen $n dosya silinsin mi?"
        msg.textSize = 16f
        msg.setTypeface(null, Typeface.BOLD)
        msg.setTextColor(cText)
        msg.setPadding(0, 0, 0, dp(16))
        col.addView(msg)

        fun ovalBtn(label: String, fill: Int, textColor: Int, runAfter: Boolean, action: () -> Unit) {
            val b2 = TextView(this)
            b2.text = label
            b2.textSize = 15f
            b2.setTypeface(null, Typeface.BOLD)
            b2.setTextColor(textColor)
            b2.gravity = Gravity.CENTER
            b2.setPadding(dp(16), dp(14), dp(16), dp(14))
            val sh = GradientDrawable()
            sh.setColor(fill)
            sh.cornerRadius = dp(28).toFloat()
            b2.background = sh
            b2.isClickable = true
            b2.setOnClickListener {
                d.dismiss()
                action()
                if (runAfter) after()
            }
            col.addView(b2, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
            })
        }
        ovalBtn("Sadece listeden kaldır", cField, cText, true) {
            for (e in picked) prefs.removeDownload(e.path)
            toast("$n kayıt listeden kaldırıldı")
        }
        ovalBtn("Telefondan da sil", cAccent, Color.WHITE, true) {
            var okCount = 0
            for (e in picked) {
                if (deleteDownloadFile(e.path)) okCount++
                prefs.removeDownload(e.path)
            }
            toast(if (okCount == n) "$n dosya telefondan silindi" else "$okCount dosya silindi, kalanlar için sadece kayıt kaldırıldı")
        }
        ovalBtn("Vazgeç", cField, cSub, false) { }
        d.setContentView(col)
        d.show()
    }

    /** Silinecek indirmeleri çentikle seçme ekranı (tümünü seç / tek tek). */
    private fun showDownloadsSelect(onDone: () -> Unit) {
        val items = ArrayList(prefs.downloads())
        if (items.isEmpty()) return
        val selected = BooleanArray(items.size)
        val d = Dialog(this)
        pushUiOverlay()
        d.setOnDismissListener { popUiOverlay() }
        val rootV = LinearLayout(this)
        rootV.orientation = LinearLayout.VERTICAL
        rootV.setBackgroundColor(cBg)
        rootV.setPadding(dp(14), dp(34), dp(14), dp(14))

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val tt = TextView(this)
        tt.text = "Silinecekleri seç"
        tt.textSize = 20f
        tt.setTypeface(null, Typeface.BOLD)
        tt.setTextColor(cText)
        head.addView(tt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val cancel = textBtn("Vazgeç") { d.dismiss() }
        cancel.setTextColor(cSub)
        head.addView(cancel, LinearLayout.LayoutParams(dp(84), dp(40)))
        rootV.addView(head)

        val selectAll = textBtn("Tümünü seç / kaldır") { }
        selectAll.setTextColor(cAccent)
        selectAll.setTypeface(null, Typeface.BOLD)
        rootV.addView(selectAll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))

        val delBtn = TextView(this)
        fun updateCount() {
            val n = selected.count { it }
            delBtn.text = if (n == 0) "Silmek için seç" else "Seçilenleri sil ($n)"
            delBtn.alpha = if (n == 0) 0.45f else 1f
        }

        val checks = ArrayList<android.widget.CheckBox>()
        val scroll = ScrollView(this)
        val listCol = LinearLayout(this)
        listCol.orientation = LinearLayout.VERTICAL
        listCol.setPadding(0, dp(8), 0, dp(12))
        for (i in items.indices) {
            val e = items[i]
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(8), dp(10), dp(12), dp(10))
            row.background = shape(cSurface, 14)
            val cb = android.widget.CheckBox(this)
            try { cb.buttonTintList = ColorStateList.valueOf(cAccent) } catch (_: Exception) { }
            cb.setOnCheckedChangeListener { _, v -> selected[i] = v; updateCount() }
            checks.add(cb)
            row.addView(cb, LinearLayout.LayoutParams(dp(40), dp(40)))
            val icon = TextView(this)
            icon.text = when {
                e.mime.startsWith("video") || e.title.lowercase().endsWith(".mp4") -> "🎬"
                e.mime.startsWith("audio") || e.title.lowercase().endsWith(".mp3") -> "🎵"
                e.mime.startsWith("image") -> "🖼️"
                e.mime.contains("pdf") || e.title.lowercase().endsWith(".pdf") -> "📄"
                else -> "📁"
            }
            icon.textSize = 20f
            row.addView(icon, LinearLayout.LayoutParams(dp(32), dp(32)).apply { rightMargin = dp(8) })
            val nameTv = TextView(this)
            nameTv.text = e.title
            nameTv.textSize = 15f
            nameTv.setTextColor(cText)
            nameTv.maxLines = 2
            nameTv.ellipsize = android.text.TextUtils.TruncateAt.END
            row.addView(nameTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.setOnClickListener { cb.isChecked = !cb.isChecked }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(8)
            listCol.addView(row, lp)
        }
        scroll.addView(listCol)
        rootV.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        var allOn = false
        selectAll.setOnClickListener {
            allOn = !allOn
            for (c in checks) c.isChecked = allOn
        }

        delBtn.textSize = 15f
        delBtn.setTypeface(null, Typeface.BOLD)
        delBtn.setTextColor(Color.WHITE)
        delBtn.gravity = Gravity.CENTER
        val dg = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFFFF5A5F.toInt(), 0xFFD6245A.toInt()))
        dg.cornerRadius = dp(26).toFloat()
        delBtn.background = dg
        delBtn.setOnClickListener {
            val picked = ArrayList<Prefs.DownloadEntry>()
            for (i in items.indices) if (selected[i]) picked.add(items[i])
            if (picked.isEmpty()) { toast("Önce silinecekleri seç"); return@setOnClickListener }
            themedDeleteMany(picked) { d.dismiss(); onDone() }
        }
        updateCount()
        rootV.addView(delBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))

        sheetWindow(d, rootV, true)
        d.show()
    }

    private fun showEntries(title: String, list: List<Prefs.Entry>, onDelete: (Prefs.Entry) -> Unit, neutral: Pair<String, () -> Unit>?) {
        val items = ArrayList(list)
        val d = Dialog(this)
        val rootV = LinearLayout(this)
        rootV.orientation = LinearLayout.VERTICAL
        rootV.setBackgroundColor(cBg)
        rootV.setPadding(dp(14), dp(34), dp(14), dp(14))

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.setPadding(dp(6), 0, dp(6), dp(10))
        val tv = TextView(this)
        tv.text = title; tv.textSize = 24f; tv.setTypeface(null, Typeface.BOLD); tv.setTextColor(cText)
        head.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val listCol = LinearLayout(this)
        listCol.orientation = LinearLayout.VERTICAL
        var query = ""

        fun render() {
            listCol.removeAllViews()
            val q = query.trim().lowercase()
            val shownList = items.filter { q.isEmpty() || it.title.lowercase().contains(q) || it.url.lowercase().contains(q) }
            if (shownList.isEmpty()) {
                val e = TextView(this)
                e.text = if (items.isEmpty()) "🗂️\n\nBurada henüz bir şey yok" else "🔍\n\nSonuç bulunamadı"
                e.textSize = 15f; e.setTextColor(cSub); e.gravity = Gravity.CENTER
                e.setPadding(0, dp(60), 0, 0)
                listCol.addView(e, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                return
            }
            for (en in shownList) {
                val row = LinearLayout(this)
                row.orientation = LinearLayout.HORIZONTAL
                row.gravity = Gravity.CENTER_VERTICAL
                row.setPadding(dp(12), dp(10), dp(4), dp(10))
                row.background = ripple(shape(cSurface, 18))
                row.setOnClickListener { d.dismiss(); loadInCurrent(en.url) }
                val host = shownUrl(en.url).substringBefore("/")
                val av = TextView(this)
                av.text = (host.firstOrNull() ?: '?').uppercaseChar().toString()
                av.textSize = 17f; av.setTypeface(null, Typeface.BOLD); av.setTextColor(Color.WHITE); av.gravity = Gravity.CENTER
                val g = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(cAccent, cAccent2))
                g.cornerRadius = dp(14).toFloat()
                av.background = g
                row.addView(av, LinearLayout.LayoutParams(dp(42), dp(42)))
                val col = LinearLayout(this)
                col.orientation = LinearLayout.VERTICAL
                col.setPadding(dp(12), 0, dp(6), 0)
                val t1 = TextView(this)
                t1.text = if (en.title.isBlank()) host else en.title
                t1.textSize = 15f; t1.setTextColor(cText); t1.maxLines = 1
                t1.ellipsize = android.text.TextUtils.TruncateAt.END
                col.addView(t1)
                val t2 = TextView(this)
                t2.text = shownUrl(en.url); t2.textSize = 12.5f; t2.setTextColor(cSub); t2.maxLines = 1
                t2.ellipsize = android.text.TextUtils.TruncateAt.END
                col.addView(t2)
                row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val x = textBtn("✕") { onDelete(en); items.remove(en); render() }
                x.setTextColor(cSub)
                row.addView(x, LinearLayout.LayoutParams(dp(44), dp(44)))
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.bottomMargin = dp(8)
                listCol.addView(row, lp)
            }
        }

        if (neutral != null) {
            val nb = textBtn(neutral.first) { neutral.second(); items.clear(); render() }
            nb.setTextColor(cAccent); nb.setTypeface(null, Typeface.BOLD)
            head.addView(nb, LinearLayout.LayoutParams(dp(84), dp(40)))
        }
        val done = textBtn("Bitti") { d.dismiss() }
        done.setTextColor(cAccent); done.setTypeface(null, Typeface.BOLD)
        head.addView(done, LinearLayout.LayoutParams(dp(64), dp(40)))
        rootV.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val search = EditText(this)
        search.hint = "🔎  Ara"
        search.setHintTextColor(cSub); search.setTextColor(cText); search.textSize = 15f
        search.isSingleLine = true
        search.background = shape(cSurface, 22)
        search.setPadding(dp(16), 0, dp(16), 0)
        search.doAfterTextChanged { t -> query = t?.toString() ?: ""; render() }
        val slp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44))
        slp.bottomMargin = dp(12)
        rootV.addView(search, slp)

        val scroll = ScrollView(this)
        scroll.addView(listCol)
        rootV.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        render()
        sheetWindow(d, rootV, true)
        d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        d.show()
    }

    private fun openFind() {
        findBar.visibility = View.VISIBLE; findInput.setText(""); findCount.text = ""
        findInput.requestFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(findInput, 0)
    }

    private fun closeFind() {
        if (findBar.visibility != View.VISIBLE) return
        findBar.visibility = View.GONE; currentWeb()?.clearMatches()
        hideKeyboard(); root.requestFocus()
    }

    /** TikTok'a masaüstü tarayıcı gibi görünür (sayfa mobil kalır); başka sitelerde mobil kimlik geri gelir. */
    private fun applyHostUa(t: BTab, url: String?) {
        if (t.desktop || url == null || t.mobileUa.isEmpty()) return
        val host = try { Uri.parse(url).host ?: "" } catch (_: Exception) { "" }
        val want = host == "tiktok.com" || host.endsWith(".tiktok.com")
        if (want == t.spoofUa) return
        t.spoofUa = want
        try {
            val s = t.web.settings
            if (want) {
                val chrome = Regex("Chrome/[\\d.]+").find(t.mobileUa)?.value ?: "Chrome/120.0.0.0"
                s.userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) $chrome Safari/537.36"
            } else {
                s.userAgentString = t.mobileUa
            }
        } catch (_: Exception) { }
    }

    private fun toggleDesktop() {
        val t = tabs.getOrNull(cur) ?: return
        t.desktop = !t.desktop
        val s = t.web.settings
        if (t.desktop) {
            val chrome = Regex("Chrome/[\\d.]+").find(t.mobileUa)?.value ?: "Chrome/120.0.0.0"
            s.userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) $chrome Safari/537.36"
        } else { s.userAgentString = t.mobileUa }
        t.web.reload()
    }

    private fun shareCurrent() {
        val t = tabs.getOrNull(cur) ?: return
        if (!t.url.startsWith("http")) return
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t.url), "Paylaş"))
    }

    private fun capturePreview(tab: BTab) {
        try {
            val w = tab.web.width; val h = tab.web.height
            if (w <= 0 || h <= 0) return
            val sc = 360f / w
            val bh = (h * sc).toInt().coerceIn(1, 700)
            val bmp = Bitmap.createBitmap(360, bh, Bitmap.Config.RGB_565)
            val canvas = Canvas(bmp); canvas.scale(sc, sc)
            tab.web.draw(canvas); tab.preview = bmp
        } catch (_: Exception) {}
    }

    private fun pillBtn(label: String, colors: IntArray, onClick: () -> Unit): TextView {
        val t = TextView(this)
        t.text = label; t.textSize = 14f; t.setTypeface(null, Typeface.BOLD)
        t.setTextColor(Color.WHITE); t.gravity = Gravity.CENTER
        val g = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, colors)
        g.cornerRadius = dp(24).toFloat()
        t.background = g
        t.setOnClickListener { onClick() }
        return t
    }

    private fun showTabs() {
        tabs.getOrNull(cur)?.let { capturePreview(it) }
        val d = Dialog(this)
        pushUiOverlay()
        d.setOnDismissListener { popUiOverlay() }
        val rootV = LinearLayout(this)
        rootV.orientation = LinearLayout.VERTICAL
        rootV.setBackgroundColor(cBg)
        rootV.setPadding(dp(12), dp(34), dp(12), dp(16))

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.setPadding(dp(6), 0, dp(6), dp(8))
        val title = TextView(this)
        title.textSize = 24f; title.setTypeface(null, Typeface.BOLD); title.setTextColor(cText)
        head.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val done = textBtn("Bitti") { d.dismiss() }
        done.setTextColor(cAccent); done.setTypeface(null, Typeface.BOLD)
        head.addView(done, LinearLayout.LayoutParams(dp(72), dp(40)))
        rootV.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val grid = LinearLayout(this)
        grid.orientation = LinearLayout.VERTICAL
        val scroll = ScrollView(this)
        scroll.addView(grid)
        rootV.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val actions = LinearLayout(this)
        actions.orientation = LinearLayout.HORIZONTAL
        actions.setPadding(0, dp(10), 0, 0)
        val b1 = pillBtn("＋  Yeni sekme", intArrayOf(cAccent, cAccent2)) { d.dismiss(); openTab(null) }
        val b2 = pillBtn("🕶  Gizli sekme", intArrayOf(0xFF6D2BD9.toInt(), 0xFFC026D3.toInt())) { d.dismiss(); openTab(null, true, true) }
        val l1 = LinearLayout.LayoutParams(0, dp(48), 1f); l1.rightMargin = dp(6)
        val l2 = LinearLayout.LayoutParams(0, dp(48), 1f); l2.leftMargin = dp(6)
        actions.addView(b1, l1); actions.addView(b2, l2)
        rootV.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        fillTabs(grid, title, d)
        sheetWindow(d, rootV, true)
        d.show()
    }

    private fun fillTabs(grid: LinearLayout, title: TextView, d: Dialog) {
        grid.removeAllViews()
        title.text = "Sekmeler · " + tabs.size
        for (rowStart in 0 until tabs.size step 2) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            for (i in rowStart until minOf(rowStart + 2, tabs.size)) {
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                lp.setMargins(dp(5), dp(5), dp(5), dp(5))
                row.addView(tabCard(i, grid, title, d), lp)
            }
            if (rowStart + 1 >= tabs.size) row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            grid.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun tabCard(i: Int, grid: LinearLayout, title: TextView, d: Dialog): View {
        val t = tabs[i]
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(8), dp(4), dp(8), dp(8))
        val bg = GradientDrawable()
        bg.setColor(if (t.incognito) 0xFF2B1A55.toInt() else cSurface)
        bg.cornerRadius = dp(20).toFloat()
        if (i == cur) bg.setStroke(dp(2), if (t.incognito) 0xFFA46BFF.toInt() else cAccent)
        card.background = bg
        card.setOnClickListener { d.dismiss(); switchTo(i) }

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val label = TextView(this)
        val name = if (t.title.isBlank() || t.title == "Yeni Sekme") shownUrl(t.pendingUrl ?: t.url).ifEmpty { "Yeni sekme" } else t.title
        label.text = (if (t.incognito) "🕶 " else "") + name
        label.textSize = 12.5f; label.maxLines = 1
        label.ellipsize = android.text.TextUtils.TruncateAt.END
        label.setTextColor(if (t.incognito) 0xFFF3ECFF.toInt() else cText)
        if (i == cur) label.setTypeface(null, Typeface.BOLD)
        label.setPadding(dp(4), 0, 0, 0)
        head.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(textBtn("✕") { closeTab(i); fillTabs(grid, title, d) }, LinearLayout.LayoutParams(dp(34), dp(34)))
        card.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val img = ImageView(this)
        img.scaleType = ImageView.ScaleType.MATRIX
        img.background = shape(if (t.incognito) 0xFF3A2470.toInt() else cField, 14)
        img.clipToOutline = true
        val bmp = t.preview
        if (bmp != null) {
            img.setImageBitmap(bmp)
            img.post {
                val sc = img.width.toFloat() / bmp.width
                val m = Matrix(); m.setScale(sc, sc)
                img.imageMatrix = m
            }
        }
        card.addView(img, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(170)))
        return card
    }

    private fun settingsRow(icon: String, title: String, sub: String?, trailing: View?, onClick: () -> Unit): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(12), dp(10), dp(14), dp(10))
        row.background = ripple(shape(cSurface, 18))
        row.setOnClickListener { onClick() }
        val ic = TextView(this)
        ic.text = icon; ic.textSize = 19f; ic.gravity = Gravity.CENTER
        val g = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(cAccent, cAccent2))
        g.cornerRadius = dp(13).toFloat()
        ic.background = g
        row.addView(ic, LinearLayout.LayoutParams(dp(42), dp(42)))
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(12), 0, dp(8), 0)
        val tt = TextView(this)
        tt.text = title; tt.textSize = 15.5f; tt.setTextColor(cText); tt.setTypeface(null, Typeface.BOLD)
        col.addView(tt)
        if (sub != null) {
            val st = TextView(this)
            st.text = sub; st.textSize = 12.5f; st.setTextColor(cSub)
            col.addView(st)
        }
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (trailing != null) row.addView(trailing, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return row
    }

    private fun chevron(): TextView {
        val c = TextView(this)
        c.text = "›"; c.textSize = 24f; c.setTextColor(cSub)
        return c
    }

    private fun switchRow(icon: String, title: String, sub: String, value: Boolean, onChange: (Boolean) -> Unit): View {
        val sw = SwitchCompat(this)
        sw.isChecked = value
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        sw.thumbTintList = ColorStateList(states, intArrayOf(Color.WHITE, cSub))
        sw.trackTintList = ColorStateList(states, intArrayOf(cAccent, cField))
        sw.setOnCheckedChangeListener { _, v -> onChange(v) }
        return settingsRow(icon, title, sub, sw) { sw.toggle() }
    }


    /** İndirme: sadece listeden veya telefon dosyasıyla sil. */
    private fun themedDeleteDownload(title: String, path: String, after: () -> Unit) {
        val d = Dialog(this)
        try { d.window?.setBackgroundDrawableResource(android.R.color.transparent) } catch (_: Exception) { }
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(22), dp(20), dp(22), dp(18))
        val bg = GradientDrawable()
        bg.setColor(cSurface)
        bg.cornerRadius = dp(24).toFloat()
        col.background = bg
        val msg = TextView(this)
        msg.text = "«" + title + "» silinsin mi?"
        msg.textSize = 16f
        msg.setTypeface(null, Typeface.BOLD)
        msg.setTextColor(cText)
        msg.setPadding(0, 0, 0, dp(16))
        col.addView(msg)

        fun ovalBtn(label: String, fill: Int, textColor: Int, runAfter: Boolean, action: () -> Unit) {
            val b = TextView(this)
            b.text = label
            b.textSize = 15f
            b.setTypeface(null, Typeface.BOLD)
            b.setTextColor(textColor)
            b.gravity = Gravity.CENTER
            b.setPadding(dp(16), dp(14), dp(16), dp(14))
            val shape = GradientDrawable()
            shape.setColor(fill)
            shape.cornerRadius = dp(28).toFloat()
            b.background = shape
            b.isClickable = true
            b.setOnClickListener {
                d.dismiss()
                action()
                if (runAfter) after()
            }
            col.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
            })
        }
        ovalBtn("Sadece listeden kaldır", cField, cText, true) {
            prefs.removeDownload(path)
            toast("Kayıt silindi")
        }
        ovalBtn("Telefondan da sil", cAccent, Color.WHITE, true) {
            var ok = false
            try {
                val f = java.io.File(path)
                if (f.exists()) ok = f.delete()
                if (!ok && Build.VERSION.SDK_INT >= 29) {
                    try {
                        contentResolver.query(
                            android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            arrayOf(android.provider.MediaStore.MediaColumns._ID),
                            android.provider.MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                            arrayOf(f.name),
                            null
                        )?.use { c ->
                            if (c.moveToFirst()) {
                                val id = c.getLong(0)
                                val u = android.content.ContentUris.withAppendedId(
                                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, id
                                )
                                ok = contentResolver.delete(u, null, null) > 0
                            }
                        }
                    } catch (_: Exception) { }
                }
            } catch (_: Exception) { }
            prefs.removeDownload(path)
            toast(if (ok) "Dosya telefonda silindi" else "Kayıt silindi (dosyaya erişilemedi)")
        }
        ovalBtn("Vazgeç", cField, cSub, false) { }
        d.setContentView(col)
        d.show()
    }



fun themedMessage(message: String, onDismiss: () -> Unit = {}) {
        val d = Dialog(this)
        try {
            d.window?.setBackgroundDrawableResource(android.R.color.transparent)
            d.window?.setGravity(Gravity.CENTER)
            d.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        } catch (_: Exception) { }
        try { d.window?.setBackgroundDrawableResource(android.R.color.transparent) } catch (_: Exception) { }
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(20), dp(18), dp(20), dp(14))
        val bg = GradientDrawable()
        bg.setColor(cSurface)
        bg.cornerRadius = dp(22).toFloat()
        col.background = bg
        val msg = TextView(this)
        msg.text = message.trim().ifEmpty { "Tamam" }
        msg.textSize = 15f
        msg.setTextColor(cText)
        msg.setPadding(0, 0, 0, dp(14))
        col.addView(msg, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val ok = textBtn("Tamam") { d.dismiss(); onDismiss() }
        ok.setTextColor(cAccent)
        ok.setTypeface(null, Typeface.BOLD)
        col.addView(ok, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        d.setOnCancelListener { onDismiss() }
        sheetWindow(d, col, false)
        d.show()
    }

    private fun themedConfirm(message: String, onYes: () -> Unit, onNo: () -> Unit) {
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val wrap = FrameLayout(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(22), dp(20), dp(22), dp(16))
        val bg = GradientDrawable()
        bg.setColor(cSurface)
        bg.cornerRadius = dp(22).toFloat()
        col.background = bg
        val msg = TextView(this)
        msg.text = message.trim().ifEmpty { "Emin misin?" }
        msg.textSize = 16f
        msg.setTextColor(cText)
        msg.setPadding(0, 0, 0, dp(16))
        col.addView(msg)
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val no = textBtn("Vazgeç") { d.dismiss(); onNo() }
        no.setTextColor(cSub)
        val yes = textBtn("Tamam") { d.dismiss(); onYes() }
        yes.setTextColor(cAccent)
        yes.setTypeface(null, Typeface.BOLD)
        row.addView(no, LinearLayout.LayoutParams(0, dp(44), 1f))
        row.addView(yes, LinearLayout.LayoutParams(0, dp(44), 1f))
        col.addView(row)
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.gravity = Gravity.CENTER
        lp.leftMargin = dp(28)
        lp.rightMargin = dp(28)
        wrap.addView(col, lp)
        d.setContentView(wrap)
        d.setOnCancelListener { onNo() }
        d.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            w.setGravity(Gravity.CENTER)
            w.setDimAmount(0.45f)
        }
        d.show()
    }

    /** Başlıklı, oval tuşlu ve temaya uygun seçim kartı. buttons: (yazı, ana tuş mu, eylem). İptal edilirse onCancel çalışır. */
    private fun themedChoice(title: String, message: String, buttons: List<Triple<String, Boolean, () -> Unit>>, onCancel: () -> Unit) {
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val wrap = FrameLayout(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(22), dp(22), dp(22), dp(18))
        val bg = GradientDrawable()
        bg.setColor(cSurface)
        bg.cornerRadius = dp(26).toFloat()
        bg.setStroke(dp(1), cField)
        col.background = bg
        val tt = TextView(this)
        tt.text = title
        tt.textSize = 19f
        tt.setTypeface(null, Typeface.BOLD)
        tt.setTextColor(cText)
        tt.setPadding(0, 0, 0, dp(8))
        col.addView(tt)
        val msg = TextView(this)
        msg.text = message
        msg.textSize = 14.5f
        msg.setTextColor(cSub)
        msg.setLineSpacing(0f, 1.15f)
        msg.setPadding(0, 0, 0, dp(18))
        col.addView(msg)
        var done = false
        for ((label, primary, act) in buttons) {
            val b = TextView(this)
            b.text = label
            b.textSize = 15f
            b.setTypeface(null, Typeface.BOLD)
            b.gravity = Gravity.CENTER
            if (primary) {
                b.setTextColor(Color.WHITE)
                val g = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(cAccent, cAccent2))
                g.cornerRadius = dp(24).toFloat()
                b.background = ripple(g)
            } else {
                b.setTextColor(cText)
                b.background = ripple(shape(cField, 24))
            }
            b.setOnClickListener { if (!done) { done = true; d.dismiss(); act() } }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
            lp.topMargin = dp(8)
            col.addView(b, lp)
        }
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.gravity = Gravity.CENTER
        lp.leftMargin = dp(26)
        lp.rightMargin = dp(26)
        wrap.addView(col, lp)
        d.setContentView(wrap)
        d.setOnCancelListener { if (!done) { done = true; onCancel() } }
        d.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            w.setGravity(Gravity.CENTER)
            w.setDimAmount(0.5f)
        }
        d.show()
    }

    private fun startModeLabel(): String = when (prefs.startMode) {
        "home" -> "Ana sayfa"
        "blank" -> "Yeni sekme"
        else -> "Kaldığım yerden"
    }

    private fun chooseStartMode(onDone: () -> Unit) {
        val d = Dialog(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(14), dp(16), dp(14), dp(18))
        val r = dp(28).toFloat()
        val bgd = GradientDrawable()
        bgd.setColor(cSurface)
        bgd.cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        col.background = bgd
        val tt = TextView(this)
        tt.text = "Açılış"
        tt.textSize = 18f
        tt.setTypeface(null, Typeface.BOLD)
        tt.setTextColor(cText)
        tt.setPadding(dp(6), 0, 0, dp(10))
        col.addView(tt)
        data class Opt(val id: String, val title: String, val sub: String)
        val opts = listOf(
            Opt("resume", "Kaldığım yerden", "Son sekmelerle devam et"),
            Opt("home", "Ana sayfa", Engines.get(prefs.engine).name + " açılır"),
            Opt("blank", "Yeni sekme", "Boş sekmeden başla")
        )
        for (o in opts) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.VERTICAL
            row.setPadding(dp(14), dp(12), dp(14), dp(12))
            val rb = GradientDrawable()
            rb.setColor(if (prefs.startMode == o.id) cField else cSurface)
            rb.cornerRadius = dp(16).toFloat()
            if (prefs.startMode == o.id) rb.setStroke(dp(2), cAccent) else rb.setStroke(dp(1), cField)
            row.background = rb
            val t1 = TextView(this)
            t1.text = o.title
            t1.textSize = 15.5f
            t1.setTextColor(cText)
            t1.setTypeface(null, Typeface.BOLD)
            val t2 = TextView(this)
            t2.text = o.sub
            t2.textSize = 12.5f
            t2.setTextColor(cSub)
            row.addView(t1)
            row.addView(t2)
            row.setOnClickListener {
                prefs.startMode = o.id
                d.dismiss()
                onDone()
            }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(8)
            col.addView(row, lp)
        }
        sheetWindow(d, col, false)
        d.show()
    }


    private fun showSettings() {
        val d = Dialog(this)
        pushUiOverlay()
        d.setOnDismissListener { popUiOverlay(); handler.postDelayed({ applySettingsReload() }, 120L) }
        val rootV = LinearLayout(this)
        rootV.orientation = LinearLayout.VERTICAL
        rootV.setBackgroundColor(cBg)
        rootV.setPadding(dp(14), dp(34), dp(14), dp(14))
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.setPadding(dp(6), 0, dp(6), dp(10))
        val title = TextView(this)
        title.text = "Ayarlar"; title.textSize = 24f; title.setTypeface(null, Typeface.BOLD); title.setTextColor(cText)
        head.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val done = textBtn("Bitti") { d.dismiss() }
        done.setTextColor(cAccent); done.setTypeface(null, Typeface.BOLD)
        head.addView(done, LinearLayout.LayoutParams(dp(84), dp(40)))
        rootV.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        fun add(v: View) {
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(8)
            list.addView(v, lp)
        }
        fun label(t: String) {
            val l = TextView(this)
            l.text = t.uppercase(); l.textSize = 11.5f; l.setTextColor(cSub); l.setTypeface(null, Typeface.BOLD)
            l.letterSpacing = 0.1f
            l.setPadding(dp(6), dp(10), 0, dp(6))
            list.addView(l)
        }
        label("Gezinme")
        add(settingsRow("🚀", "Açılış", startModeLabel(), chevron()) {
            chooseStartMode { d.dismiss(); showSettings() }
        })
        add(switchRow("🛡️", "Reklam engelleyici", "YouTube reklamlarını atlar", prefs.adBlock) { v ->
            prefs.adBlock = v; settingsChanged()
        })
        add(switchRow("🎧", "Arka planda çalma", "Uygulamadan çıkınca müzik sürer", prefs.bgPlay) { v ->
            prefs.bgPlay = v; settingsChanged()
        })
        add(switchRow("⬇️", "Adres çubuğu altta", "Açıksa adres çubuğu ekranın altında olur", prefs.barBottom) { v ->
            prefs.barBottom = v; applyTheme()
        })
        add(switchRow("↕️", "Kaydırınca çubuğu gizle", "Çubuk sayfayla birlikte kayıp gizlenir, ters yöne kaydırınca geri gelir", prefs.autoHideBar) { v ->
            prefs.autoHideBar = v
            if (!v) setBarShown(true)
        })
        add(settingsRow("🌐", "Varsayılan tarayıcı yap", if (isDefaultBrowser()) "Şu an varsayılan ✓" else "Linkler Rota ile açılsın", chevron()) { d.dismiss(); askDefaultBrowser() })
        label("Görünüm ve arama")
        add(settingsRow("🔎", "Arama motoru", Engines.get(prefs.engine).name, chevron()) { chooseEngine { d.dismiss(); showSettings() } })
        label("Gizlilik")
        add(switchRow("🧹", "Çıkışta temizle", "Kapanınca çerez ve veriler silinir", prefs.clearOnExit) { v ->
            prefs.clearOnExit = v
        })
        add(settingsRow("🕘", "Geçmişi temizle", null, null) {
            themedConfirm("Geçmiş silinsin mi?", onYes = { prefs.clearHistory(); toast("Silindi") }, onNo = {})
        })
        add(settingsRow("🧽", "Önbellek ve çerezler", null, null) {
            themedConfirm("Önbellek ve çerezler silinsin mi?", onYes = { clearBrowsingData(); toast("Temizlendi") }, onNo = {})
        })

        val scroll = ScrollView(this)
        scroll.addView(list)
        rootV.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        sheetWindow(d, rootV, true)
        d.show()
    }

    private fun isDefaultBrowser(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val rm = getSystemService(android.app.role.RoleManager::class.java)
                rm != null && rm.isRoleHeld(android.app.role.RoleManager.ROLE_BROWSER)
            } else {
                val i = Intent(Intent.ACTION_VIEW, Uri.parse("http://www.example.com"))
                val ri = packageManager.resolveActivity(i, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                ri?.activityInfo?.packageName == packageName
            }
        } catch (_: Exception) { false }
    }

    private fun openDefaultAppsSettings() {
        try {
            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            toast("\"Tarayıcı uygulaması\"nı seç, sonra Rota'yı işaretle")
        } catch (_: Exception) {
            try { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) } catch (_: Exception) { toast("Ayarlar açılamadı") }
        }
    }

    private fun afterRole() {
        if (isDefaultBrowser()) toast("Rota artık varsayılan tarayıcı ✓") else openDefaultAppsSettings()
    }

    private fun askDefaultBrowser() {
        if (isDefaultBrowser()) { toast("Rota zaten varsayılan tarayıcı ✓"); return }
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val rm = getSystemService(android.app.role.RoleManager::class.java)
                if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_BROWSER)) {
                    roleLauncher.launch(rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_BROWSER))
                    return
                }
            } catch (_: Exception) { }
        }
        openDefaultAppsSettings()
    }

    private fun chooseTheme(onDone: () -> Unit) {
        val d = Dialog(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(14), dp(14), dp(14), dp(22))
        val r = dp(28).toFloat()
        val bgd = GradientDrawable()
        bgd.setColor(cSurface)
        bgd.cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        col.background = bgd
        val tt = TextView(this)
        tt.text = "Tema seç"; tt.textSize = 18f; tt.setTypeface(null, Typeface.BOLD); tt.setTextColor(cText)
        tt.setPadding(dp(6), 0, 0, dp(10))
        col.addView(tt)

        fun groupLabel(txt: String) {
            val l = TextView(this)
            l.text = txt.uppercase(); l.textSize = 11.5f; l.setTextColor(cSub); l.setTypeface(null, Typeface.BOLD)
            l.letterSpacing = 0.1f
            l.setPadding(dp(6), dp(8), 0, dp(8))
            col.addView(l)
        }

        fun card(p: Palette): View {
            val sel = p.id == prefs.theme
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(12), dp(12), dp(14), dp(12))
            // Kartın arka planı temanın kendi renk geçişi: önizleme gibi görünür
            val rb = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(p.bg, p.surface))
            rb.cornerRadius = dp(20).toFloat()
            if (sel) rb.setStroke(dp(2), p.accent) else rb.setStroke(dp(1), p.field)
            row.background = ripple(rb)

            // Üç renkli yuvarlak: vurgu renkleri
            val sw = FrameLayout(this)
            fun dotOf(color: Int, size: Int, gx: Int, gy: Int) {
                val v = View(this)
                val g = GradientDrawable()
                g.shape = GradientDrawable.OVAL
                g.setColor(color)
                g.setStroke(dp(2), p.surface)
                v.background = g
                sw.addView(v, FrameLayout.LayoutParams(dp(size), dp(size)).apply { leftMargin = dp(gx); topMargin = dp(gy) })
            }
            dotOf(p.accent, 26, 0, 4)
            dotOf(p.accent2, 26, 16, 4)
            dotOf(p.accent3, 26, 32, 4)
            row.addView(sw, LinearLayout.LayoutParams(dp(62), dp(34)))

            val nm = TextView(this)
            nm.text = p.name; nm.textSize = 15.5f; nm.setTextColor(p.text); nm.setTypeface(null, Typeface.BOLD)
            nm.setPadding(dp(12), 0, 0, 0)
            row.addView(nm, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (sel) {
                val ck = TextView(this)
                ck.text = "✓"; ck.textSize = 18f; ck.setTextColor(p.accent); ck.setTypeface(null, Typeface.BOLD)
                row.addView(ck)
            }
            row.setOnClickListener {
                d.dismiss()
                prefs.theme = p.id
                applyTheme()
                onDone()
            }
            return row
        }

        fun addCard(p: Palette) {
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(8)
            col.addView(card(p), lp)
        }

        groupLabel("Koyu")
        for (p in Themes.all) if (p.dark) addCard(p)
        groupLabel("Açık")
        for (p in Themes.all) if (!p.dark) addCard(p)

        val sc = ScrollView(this)
        sc.addView(col)
        sheetWindow(d, sc, false)
        d.show()
    }

    private fun chooseEngine(onDone: () -> Unit) {
        val d = Dialog(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(14), dp(16), dp(14), dp(18))
        val r = dp(28).toFloat()
        val bgd = GradientDrawable()
        bgd.setColor(cSurface)
        bgd.cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        col.background = bgd
        val tt = TextView(this)
        tt.text = "Arama motoru"
        tt.textSize = 18f
        tt.setTypeface(null, Typeface.BOLD)
        tt.setTextColor(cText)
        tt.setPadding(dp(6), 0, 0, dp(10))
        col.addView(tt)
        for (e in Engines.all) {
            val row = textBtn(e.name) {
                prefs.engine = e.id
                d.dismiss()
                onDone()
            }
            row.setTextColor(if (e.id == prefs.engine) cAccent else cText)
            row.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            row.setPadding(dp(14), 0, dp(14), 0)
            row.background = shape(cField, 14)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
            lp.bottomMargin = dp(8)
            col.addView(row, lp)
        }
        sheetWindow(d, col, false)
        d.show()
    }

    private fun clearBrowsingData() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        for (t in tabs) t.web.clearCache(true)
    }

    private companion object {
        const val ASSET_PREFIX = "https://appassets.androidplatform.net/"
        const val NEWTAB_URL = "https://appassets.androidplatform.net/assets/newtab.html"
        const val MAX_TABS = 20


        // YouTube sayfasından progressive + ses URL'lerini çıkar (cipher'sız olanlar)
        const val YT_EXTRACT_FORMATS_JS =
            "(function(){try{" +
            "function collect(p){if(!p||!p.streamingData)return[];var out=[];" +
            "function push(f,k){if(!f)return;var u=f.url||'';if(!u&&f.signatureCipher){var m=/url=([^&]+)/.exec(f.signatureCipher);if(m)u=decodeURIComponent(m[1]);}" +
            "if(!u||u.indexOf('http')!==0)return;" +
            // cipher imzası yoksa URL işe yaramaz; sadece düz url alanları al
            "if(f.signatureCipher&&!f.url)return;" +
            "out.push({u:u,itag:f.itag||0,q:f.qualityLabel||'',k:k,audio:!!((f.mimeType||'').indexOf('audio')>=0)});} " +
            "(p.streamingData.formats||[]).forEach(function(f){push(f,'p');});" +
            "(p.streamingData.adaptiveFormats||[]).forEach(function(f){" +
            "var m=(f.mimeType||'');if(m.indexOf('audio')>=0)push(f,'a');" +
            "});return out;}" +
            "var p=window.ytInitialPlayerResponse||window.playerResponse||null;" +
            "if(!p){try{var s=document.getElementById('player-response')||document.getElementById('ytInitialPlayerResponse');" +
            "if(s&&s.textContent)p=JSON.parse(s.textContent);}catch(e){}}" +
            "if(!p){try{for(var k in window){if(k&&k.indexOf('ytInitialPlayerResponse')>=0&&window[k]&&window[k].streamingData){p=window[k];break;}}}catch(e){}}" +
            "var out=collect(p);" +
            "if(!out.length){var v=document.querySelector('video');if(v&&v.currentSrc&&v.currentSrc.indexOf('http')===0)" +
            "out.push({u:v.currentSrc,itag:0,q:'playing',k:'p',audio:false});}" +
            "return JSON.stringify(out.slice(0,20));" +
            "}catch(e){return '[]';}})();"


                  // YouTube / YouTube Music: "uygulamada aç / izle / en iyi deneyim" pencerelerini kapatır
                  const val YT_PROMO_JS = """
                  (function(){try{
                  if(window.__rotaPromo)return;window.__rotaPromo=1;
                  var re=/uygulama(y[ıi]|n[ıi]|s[ıi]nda)?\s*(dene|a[cç]|y[üu]kle|indir|edin|al\b|ile|izle|dinle)|uygulamada\s*(a[cç]|dinle|izle|devam)|en iyi deneyim|t[üu]m [öo]zellik|get\s*the\s*app|open\s*(in\s*)?(the\s*)?app|try\s*the\s*app|use\s*the\s*app|install\s*(the\s*)?app|download\s*the\s*app|listen\s*in\s*the\s*app|watch\s*in\s*(the\s*)?(youtube\s*)?app|continue\s*in\s*(the\s*)?app|better\s*on\s*the\s*app|youtube music uygulamas[ıi]nda/i;
                  var no=/^(hay[ıi]r|şimdi de[gğ]il|simdi degil|belki sonra|not now|no,? thanks|continue in browser|taray[ıi]c[ıi]da devam|kapat|close|dismiss|vazge[cç]|×|✕)/i;
                  function hide(e){if(!e||!e.style)return;try{e.style.setProperty('display','none','important');e.style.setProperty('visibility','hidden','important');e.style.setProperty('pointer-events','none','important');e.setAttribute('aria-hidden','true');}catch(x){}}
                  function killBackdrop(){
                  document.querySelectorAll('iron-overlay-backdrop,tp-yt-iron-overlay-backdrop,.dialog-backdrop,[class*="overlay-backdrop"]').forEach(hide);
                  try{document.body.style.setProperty('overflow','auto','important');document.documentElement.style.setProperty('overflow','auto','important');}catch(x){}
                  }
                  function scan(){
                  document.querySelectorAll('ytmusic-mealbar-promo-renderer,ytmusic-app-promo,ytm-promo-banner,ytm-app-promo,ytmusic-premium-upsell,ytm-open-app-button-renderer,ytm-open-app-banner-renderer,ytd-open-app-button-renderer,ytmusic-open-app-button,[class*="app-promo"],[class*="open-app"],[class*="get-app"],[id*="app-promo"],[id*="open-app"]').forEach(hide);
                  document.querySelectorAll('a,button,[role=button],yt-button-renderer,ytmusic-button-renderer,ytm-button-renderer').forEach(function(e){
                  var t=((e.getAttribute('aria-label')||'')+' '+(e.textContent||'')).replace(/\s+/g,' ').trim();
                  if(t&&t.length<120&&re.test(t)){var c=e.closest('ytmusic-mealbar-promo-renderer,ytm-promo-banner,tp-yt-paper-dialog,yt-confirm-dialog-renderer,[role=dialog],ytm-bottom-sheet-renderer,ytmusic-popup-container')||e;hide(c);killBackdrop();}
                  });
                  document.querySelectorAll('tp-yt-paper-dialog,yt-confirm-dialog-renderer,[role=dialog],ytmusic-popup-container,ytm-bottom-sheet-renderer,yt-upsell-dialog-renderer').forEach(function(d){
                  var t=(d.textContent||'').replace(/\s+/g,' ');
                  if(t.length<900&&re.test(t)){
                  d.querySelectorAll('a,button,[role=button]').forEach(function(b){var bt=(b.textContent||b.getAttribute('aria-label')||'').trim();if(no.test(bt)){try{b.click();}catch(x){}}});
                  hide(d);killBackdrop();
                  }
                  });
                  }
                  setInterval(scan,400);scan();
                  var mo=new MutationObserver(function(){scan();});
                  try{mo.observe(document.documentElement,{childList:true,subtree:true});}catch(x){}
                  }catch(e){}})();
                  """

        // YouTube Music: play/pause = gri şeffaf daire geri bildirimi; ekranın her yerinden esnek aşağı çek = küçült
        const val YT_MUSIC_UX_JS =
            "(function(){try{\n" +
            "if(location.hostname.indexOf('music.youtube.com')<0)return;\n" +
            "if(window.__rotaMusicUx5)return;window.__rotaMusicUx5=1;\n" +
            "var HS='ytmusic-play-button-renderer,#play-pause-button,.play-pause-button';\n" +
            "var FX=['paper-ripple','tp-yt-paper-ripple','yt-touch-feedback-shape','.yt-spec-touch-feedback-shape','#ink'];\n" +
            "var css='*{-webkit-tap-highlight-color:rgba(0,0,0,0)!important}html,body{overscroll-behavior-y:contain!important}*,*::before,*::after{-webkit-backdrop-filter:none!important;backdrop-filter:none!important}*:not(ytmusic-player-page):not(ytmusic-player-page *){filter:none!important}';\n" +
            "HS.split(',').forEach(function(h){\n" +
            "css+=h+'{outline:none!important;--paper-ripple-color:transparent;--paper-ripple-opacity:0;--yt-spec-touch-response:transparent;--yt-spec-touch-response-v2:transparent;--yt-focus-color:transparent}';\n" +
            "FX.forEach(function(f){css+=h+' '+f+'{display:none!important}';});\n" +
            "});\n" +
            "var st=document.createElement('style');st.id='__rota_ytm_ux5';st.textContent=css;\n" +
            "document.documentElement.appendChild(st);\n" +
            "var ov=null;\n" +
            "function getOv(){\n" +
            "if(ov&&ov.isConnected)return ov;\n" +
            "ov=document.createElement('div');\n" +
            "ov.style.cssText='position:fixed;left:0;top:0;border-radius:50%;background:rgba(150,150,150,.4);pointer-events:none;z-index:2147483646;opacity:0;display:none;';\n" +
            "document.documentElement.appendChild(ov);\n" +
            "return ov;\n" +
            "}\n" +
            "function findHost(e){\n" +
            "var path=(e.composedPath&&e.composedPath())||[];\n" +
            "for(var i=0;i<path.length;i++){\n" +
            "var n=path[i];\n" +
            "if(n&&n.matches&&n.matches(HS))return n;\n" +
            "}\n" +
            "for(var j=0;j<path.length;j++){\n" +
            "var m=path[j];\n" +
            "if(m&&m.getAttribute&&m.matches&&m.matches('button,[role=button],tp-yt-paper-icon-button,yt-icon-button')){\n" +
            "var al=m.getAttribute('aria-label')||'';\n" +
            "if(/^(oynat|duraklat|play|pause)\\b/i.test(al))return m;\n" +
            "}\n" +
            "}\n" +
            "return null;\n" +
            "}\n" +
            "function inkDown(e){\n" +
            "try{\n" +
            "var h=findHost(e);if(!h)return;\n" +
            "var r=h.getBoundingClientRect();\n" +
            "if(r.width<8||r.width>150||Math.abs(r.width-r.height)>r.width*0.3)return;\n" +
            "var o=getOv(),s=Math.max(r.width,r.height);\n" +
            "o.style.transition='none';\n" +
            "o.style.width=s+'px';o.style.height=s+'px';\n" +
            "o.style.left=(r.left+(r.width-s)/2)+'px';o.style.top=(r.top+(r.height-s)/2)+'px';\n" +
            "o.style.display='block';o.style.opacity='0';\n" +
            "void o.offsetWidth;\n" +
            "o.style.transition='opacity .07s ease-out';o.style.opacity='1';\n" +
            "}catch(z){}\n" +
            "}\n" +
            "function inkUp(){\n" +
            "try{\n" +
            "if(!ov||ov.style.display==='none')return;\n" +
            "ov.style.transition='opacity .32s ease-out';ov.style.opacity='0';\n" +
            "var o=ov;setTimeout(function(){if(o.style.opacity==='0')o.style.display='none';},360);\n" +
            "}catch(z){}\n" +
            "}\n" +
            "document.addEventListener('pointerdown',inkDown,true);\n" +
            "window.addEventListener('pointerup',inkUp,true);\n" +
            "window.addEventListener('pointercancel',inkUp,true);\n" +
            "window.addEventListener('touchend',inkUp,true);\n" +
            "window.addEventListener('touchcancel',inkUp,true);\n" +
            "function clickable(e){\n" +
            "var n=e;\n" +
            "for(var k=0;k<12&&n;k++){\n" +
            "if(n.nodeType===1&&n.matches&&n.matches('button,[role=button],tp-yt-paper-icon-button,yt-icon-button,a,[class*=minimize],[class*=collapse],[class*=toggle-player]'))return n;\n" +
            "n=n.parentElement||(n.getRootNode&&n.getRootNode().host)||null;\n" +
            "}\n" +
            "return null;\n" +
            "}\n" +
            "function findChevron(){\n" +
            "var pg=pageEl();if(!pg)return null;\n" +
            "var sels=['.player-minimize-button','.toggle-player-page-button','tp-yt-paper-icon-button.toggle-player','.toggle-player','[class*=minimize]','[class*=collapse-button]','[class*=dismiss]'];\n" +
            "for(var i=0;i<sels.length;i++){var el=pg.querySelector(sels[i]);if(el)return el;}\n" +
            "var all=pg.querySelectorAll('[aria-label]');\n" +
            "for(var j=0;j<all.length;j++){\n" +
            "var al=all[j].getAttribute('aria-label')||'';\n" +
            "if(/(asagi|aşağı|collapse|minimi|kucult|küçült|down|player)/i.test(al))return all[j];\n" +
            "}\n" +
            "for(var y=16;y<=110;y+=12){\n" +
            "for(var x=16;x<=96;x+=12){\n" +
            "var c=clickable(document.elementFromPoint(x,y));\n" +
            "if(c&&c.getBoundingClientRect().width<120)return c;\n" +
            "}\n" +
            "}\n" +
            "return null;\n" +
            "}\n" +
            "function fullTap(el){\n" +
            "var r=el.getBoundingClientRect(),x=r.left+r.width/2,y=r.top+r.height/2;\n" +
            "try{\n" +
            "var tc=new Touch({identifier:77,target:el,clientX:x,clientY:y});\n" +
            "el.dispatchEvent(new TouchEvent('touchstart',{touches:[tc],targetTouches:[tc],changedTouches:[tc],bubbles:true,cancelable:true,composed:true}));\n" +
            "el.dispatchEvent(new TouchEvent('touchend',{touches:[],targetTouches:[],changedTouches:[tc],bubbles:true,cancelable:true,composed:true}));\n" +
            "}catch(z){}\n" +
            "['pointerdown','mousedown','pointerup','mouseup','click'].forEach(function(t){\n" +
            "var C=t.indexOf('pointer')===0?PointerEvent:MouseEvent;\n" +
            "try{el.dispatchEvent(new C(t,{bubbles:true,cancelable:true,composed:true,clientX:x,clientY:y,view:window,button:0,pointerType:'touch',isPrimary:true}));}catch(z){}\n" +
            "});\n" +
            "}\n" +
            "function tryNative(){\n" +
            "if(!window.RotaTap)return false;\n" +
            "var x=45,y=32,el=findChevron();\n" +
            "if(el){var r=el.getBoundingClientRect();if(r.width>0&&r.top<200&&r.left<200){x=r.left+r.width/2;y=r.top+r.height/2;}}\n" +
            "window.RotaTap.tap(x/window.innerWidth,y/window.innerHeight);\n" +
            "return true;\n" +
            "}\n" +
            "function tryClick(){var el=findChevron();if(!el)return false;el.click();return true;}\n" +
            "function tryTap(){var el=findChevron();if(!el)return false;fullTap(el);return true;}\n" +
            "function tryLayout(){\n" +
            "var layout=document.querySelector('ytmusic-app-layout,#layout');\n" +
            "if(!layout)return false;\n" +
            "try{if('playerPageOpen_' in layout)layout.playerPageOpen_=false;}catch(z){}\n" +
            "try{layout.removeAttribute('player-page-open_');}catch(z){}\n" +
            "try{layout.setAttribute('player-ui-state_','PLAYER_BAR_ONLY');}catch(z){}\n" +
            "return true;\n" +
            "}\n" +
            "function layoutSig(){\n" +
            "var l=document.querySelector('ytmusic-app-layout,#layout');\n" +
            "if(!l)return '';\n" +
            "return l.getAttributeNames().map(function(k){return k+'='+l.getAttribute(k);}).join('|');\n" +
            "}\n" +
            "function openNow(){\n" +
            "var p=pageEl();if(!p)return false;\n" +
            "var cs=getComputedStyle(p);\n" +
            "if(cs.display==='none'||cs.visibility==='hidden')return false;\n" +
            "var r=p.getBoundingClientRect(),H=window.innerHeight;\n" +
            "return r.width>0&&r.height>H*0.5&&r.top<H*0.25&&r.bottom>H*0.6;\n" +
            "}\n" +
            "function showDebug(){\n" +
            "try{\n" +
            "var seen=[];\n" +
            "for(var y=16;y<=110;y+=22){for(var x=16;x<=96;x+=26){\n" +
            "var e=document.elementFromPoint(x,y),n=0;\n" +
            "while(e&&n<3){\n" +
            "var d=e.tagName.toLowerCase()+(e.className&&typeof e.className==='string'?'.'+e.className.trim().split(/\\s+/).slice(0,2).join('.'):'')+(e.getAttribute('aria-label')?'['+e.getAttribute('aria-label')+']':'');\n" +
            "if(seen.indexOf(d)<0)seen.push(d);\n" +
            "e=e.parentElement;n++;\n" +
            "}\n" +
            "}}\n" +
            "var b=document.createElement('div');\n" +
            "b.style.cssText='position:fixed;left:8px;right:8px;bottom:70px;z-index:2147483647;background:rgba(0,0,0,.85);color:#fff;font:11px/1.3 monospace;padding:8px;border-radius:8px;word-break:break-all;';\n" +
            "b.textContent='Rota: küçült tuşu çalışmadı. '+seen.slice(0,14).join(' ; ');\n" +
            "document.documentElement.appendChild(b);\n" +
            "setTimeout(function(){try{b.remove();}catch(z){}},12000);\n" +
            "}catch(z){}\n" +
            "}\n" +
            "function pageEl(){return document.querySelector('ytmusic-player-page#player-page,ytmusic-player-page');}\n" +
            "function pageOpen(){\n" +
            "var p=pageEl();if(!p)return false;\n" +
            "var r=p.getBoundingClientRect(),H=window.innerHeight;\n" +
            "return r.width>0&&r.height>H*0.5&&r.top<H*0.25&&r.bottom>H*0.6;\n" +
            "}\n" +
            "var busyT=0,tracking=false,locked=false,busy=false,sx=0,sy=0,off=0,smp=[],pg=null;\n" +
            "function rubber(d){\n" +
            "if(d<=0)return 0;\n" +
            "var R=window.innerHeight*1.1;\n" +
            "return R*(1-Math.exp(-d/R));\n" +
            "}\n" +
            "var peekEl=[];\n" +
            "function peekOn(){\n" +
            "try{\n" +
            "if(!document.getElementById('__rota_peek')){\n" +
            "var s=document.createElement('style');s.id='__rota_peek';\n" +
            "s.textContent='html.rota-peek ytmusic-app-layout #content,html.rota-peek ytmusic-app-layout #main-panel,html.rota-peek ytmusic-app-layout ytmusic-browse-response,html.rota-peek ytmusic-app-layout #nav-bar-background,html.rota-peek ytmusic-app-layout ytmusic-nav-bar{visibility:visible!important;opacity:1!important}html.rota-peek ytmusic-app-layout #content{display:block!important}html.rota-noanim ytmusic-app-layout,html.rota-noanim ytmusic-app-layout *{transition:none!important;animation:none!important}html.rota-peek ytmusic-player-page,html.rota-peek ytmusic-player-page #player-page{background-color:transparent!important;-webkit-backdrop-filter:none!important;backdrop-filter:none!important}';\n" +
            "document.documentElement.appendChild(s);\n" +
            "}\n" +
            "document.documentElement.classList.add('rota-peek');\n" +
            "var l=document.querySelector('ytmusic-app-layout,#layout');\n" +
            "if(l){\n" +
            "var q=[l],n=0;\n" +
            "while(q.length&&n<120){\n" +
            "var e=q.shift();n++;\n" +
            "if(e.nodeType===1&&e!==pg&&!(pg&&pg.contains(e))){\n" +
            "var cs=getComputedStyle(e);\n" +
            "var bf=cs.backdropFilter||cs.webkitBackdropFilter||'none';\n" +
            "var fl=cs.filter||'none';\n" +
            "if(bf!=='none'||fl.indexOf('blur')>=0){\n" +
            "peekEl.push([e,e.style.cssText]);\n" +
            "e.style.setProperty('backdrop-filter','none','important');\n" +
            "e.style.setProperty('-webkit-backdrop-filter','none','important');\n" +
            "if(fl.indexOf('blur')>=0)e.style.setProperty('filter','none','important');\n" +
            "}\n" +
            "}\n" +
            "for(var c=e.firstElementChild;c;c=c.nextElementSibling)q.push(c);\n" +
            "}\n" +
            "}\n" +
            "}catch(z){}\n" +
            "}\n" +
            "function peekOff(){\n" +
            "try{\n" +
            "document.documentElement.classList.remove('rota-peek');document.documentElement.classList.remove('rota-noanim');\n" +
            "peekEl.forEach(function(a){a[0].style.cssText=a[1];});\n" +
            "peekEl=[];\n" +
            "}catch(z){}\n" +
            "}\n" +
            "function setOff(v){\n" +
            "if(!pg)return;\n" +
            "pg.style.setProperty('transform','translate3d(0,'+v+'px,0)','important');\n" +
            "pg.style.setProperty('border-radius',Math.min(v,28)+'px '+Math.min(v,28)+'px 0 0','important');\n" +
            "}\n" +
            "function clean(){\n" +
            "if(!pg)return;\n" +
            "peekOff();\n" +
            "['transform','transition','border-radius','will-change','overflow','opacity'].forEach(function(k){pg.style.removeProperty(k);});\n" +
            "busy=false;\n" +
            "}\n" +
            "function springBack(){\n" +
            "if(!pg)return;\n" +
            "busy=true;busyT=Date.now();\n" +
            "pg.style.setProperty('transition','transform .55s cubic-bezier(.3,1.35,.5,1)','important');\n" +
            "setOff(0);\n" +
            "setTimeout(clean,600);\n" +
            "}\n" +
            "function commit(v){\n" +
            "busy=true;busyT=Date.now();\n" +
            "var H=window.innerHeight,left=Math.max(H-off,1);\n" +
            "var d=Math.max(170,Math.min(340,left/Math.max(v,0.9)));\n" +
            "pg.style.setProperty('transition','transform '+d+'ms cubic-bezier(.2,.7,.3,1)','important');\n" +
            "setOff(H);\n" +
            "setTimeout(afterDown,d);\n" +
            "}\n" +
            "function afterDown(){\n" +
            "pg.style.setProperty('transition','none','important');\n" +
            "pg.style.setProperty('opacity','0','important');\n" +
            "pg.style.removeProperty('transform');\n" +
            "pg.style.removeProperty('border-radius');\n" +
            "document.documentElement.classList.add('rota-noanim');\n" +
            "pg.style.setProperty('opacity','0','important');\n" +
            "pg.style.removeProperty('transform');\n" +
            "pg.style.removeProperty('border-radius');\n" +
            "var tries=[tryNative,tryClick,tryTap,tryLayout],i=0;\n" +
            "function success(){\n" +
            "pg.style.removeProperty('opacity');\n" +
            "clean();\n" +
            "}\n" +
            "function fail(){\n" +
            "pg.style.setProperty('transform','translate3d(0,'+window.innerHeight+'px,0)','important');\n" +
            "pg.style.removeProperty('opacity');\n" +
            "void pg.offsetWidth;\n" +
            "showDebug();\n" +
            "springBack();\n" +
            "}\n" +
            "function next(){\n" +
            "if(i>=tries.length){fail();return;}\n" +
            "var ok=false;\n" +
            "try{ok=tries[i++]();}catch(z){}\n" +
            "if(!ok){next();return;}\n" +
            "var sig0=layoutSig(),t0=Date.now();\n" +
            "(function poll(){\n" +
            "var el=Date.now()-t0;\n" +
            "if(!openNow()){success();return;}\n" +
            "if(layoutSig()!==sig0){if(el>=1800){success();return;}}\n" +
            "else if(el>=1000){next();return;}\n" +
            "setTimeout(poll,60);\n" +
            "})();\n" +
            "}\n" +
            "next();\n" +
            "}\n" +
            "var armed=true,sawClosed=false,busy=false,busyT=0,locked=false,tr=false,sx=0,sy=0,off=0,smp=[],pg=null;\n" +
            "(function(){var q=document.createElement('style');q.id='__rota_noanim';q.textContent='html.rota-noanim ytmusic-app-layout,html.rota-noanim ytmusic-app-layout *{transition:none!important;animation:none!important}';document.documentElement.appendChild(q);})();\n" +
            "function rubber(d){if(d<=0)return 0;var R=window.innerHeight*1.1;return R*(1-Math.exp(-d/R));}\n" +
            "function setOff(v){if(!pg)return;pg.style.setProperty('transform','translate3d(0,'+v+'px,0)','important');}\n" +
            "var pk2=[];\n" +
            "function peek2On(){\n" +
            "try{\n" +
            "if(!pg)return;\n" +
            "document.documentElement.classList.add('rota-peek2');\n" +
            "if(!document.getElementById('__rota_peek2')){var rs=document.createElement('style');rs.id='__rota_peek2';rs.textContent='html.rota-peek2 ytmusic-app-layout #content,html.rota-peek2 ytmusic-app-layout #main-panel,html.rota-peek2 ytmusic-app-layout ytmusic-pages,html.rota-peek2 ytmusic-app-layout ytmusic-browse-response,html.rota-peek2 ytmusic-app-layout #nav-bar-background,html.rota-peek2 ytmusic-app-layout ytmusic-nav-bar{visibility:visible!important;opacity:1!important}html.rota-peek2 ytmusic-app-layout #content,html.rota-peek2 ytmusic-app-layout ytmusic-browse-response{display:block!important}';document.documentElement.appendChild(rs);}\n" +
            "var root=document.querySelector('ytmusic-app-layout,#layout')||document.body;\n" +
            "var q=[root],n=0;\n" +
            "while(q.length&&n<800){\n" +
            "var e=q.shift();n++;\n" +
            "if(e.nodeType!==1)continue;\n" +
            "var inPg=(e===pg);\n" +
            "var cs=getComputedStyle(e);\n" +
            "var f=cs.filter||'none',b=cs.backdropFilter||cs.webkitBackdropFilter||'none';\n" +
            "var fb=(f!=='none'&&!inPg&&f.indexOf('blur')>=0),bb=(b!=='none');\n" +
            "if(fb||bb){\n" +
            "pk2.push([e,e.style.getPropertyValue('filter'),e.style.getPropertyPriority('filter'),e.style.getPropertyValue('backdrop-filter'),e.style.getPropertyPriority('backdrop-filter')]);\n" +
            "if(fb)e.style.setProperty('filter','none','important');\n" +
            "if(bb){e.style.setProperty('backdrop-filter','none','important');e.style.setProperty('-webkit-backdrop-filter','none','important');}\n" +
            "}\n" +
            "if(inPg)continue;\n" +
            "for(var c=e.firstElementChild;c;c=c.nextElementSibling)q.push(c);\n" +
            "}\n" +
            "}catch(z){}\n" +
            "}\n" +
            "function peek2Off(){\n" +
            "try{\n" +
            "document.documentElement.classList.remove('rota-peek2');\n" +
            "pk2.forEach(function(a){\n" +
            "var e=a[0];\n" +
            "try{\n" +
            "if(a[1])e.style.setProperty('filter',a[1],a[2]);else e.style.removeProperty('filter');\n" +
            "if(a[3])e.style.setProperty('backdrop-filter',a[3],a[4]);else e.style.removeProperty('backdrop-filter');\n" +
            "e.style.removeProperty('-webkit-backdrop-filter');\n" +
            "}catch(z){}\n" +
            "});\n" +
            "pk2=[];\n" +
            "}catch(z){}\n" +
            "}\n" +
            "function cleanAll(){\n" +
            "peek2Off();\n" +
            "if(pg){['transform','transition','will-change','opacity'].forEach(function(k){pg.style.removeProperty(k);});}\n" +
            "document.documentElement.classList.remove('rota-noanim');\n" +
            "busy=false;\n" +
            "}\n" +
            "function springBack(){\n" +
            "if(!pg)return;\n" +
            "busy=true;busyT=Date.now();\n" +
            "pg.style.setProperty('transition','transform .5s cubic-bezier(.3,1.3,.5,1)','important');\n" +
            "setOff(0);\n" +
            "setTimeout(cleanAll,560);\n" +
            "}\n" +
            "function closeDown(v){\n" +
            "busy=true;busyT=Date.now();armed=false;sawClosed=false;\n" +
            "var H=window.innerHeight,left=Math.max(H-off,1);\n" +
            "var d=Math.max(380,Math.min(600,left/Math.max(v,0.5)));\n" +
            "pg.style.setProperty('transition','transform '+d+'ms cubic-bezier(.3,.1,.25,1)','important');\n" +
            "setOff(H);\n" +
            "setTimeout(function(){\n" +
            "pg.style.setProperty('transition','none','important');\n" +
            "pg.style.setProperty('opacity','0','important');\n" +
            "pg.style.removeProperty('transform');\n" +
            "document.documentElement.classList.add('rota-noanim');\n" +
            "var ok=false;\n" +
            "try{ok=tryNative();}catch(z){}\n" +
            "if(!ok){try{ok=tryClick();}catch(z){}}\n" +
            "var t0=Date.now();\n" +
            "(function poll(){\n" +
            "if(!pageOpen()){setTimeout(cleanAll,650);return;}\n" +
            "if(Date.now()-t0>1500){\n" +
            "document.documentElement.classList.remove('rota-noanim');\n" +
            "pg.style.setProperty('transform','translate3d(0,'+window.innerHeight+'px,0)','important');\n" +
            "pg.style.removeProperty('opacity');\n" +
            "void pg.offsetWidth;\n" +
            "armed=true;\n" +
            "springBack();\n" +
            "return;\n" +
            "}\n" +
            "setTimeout(poll,50);\n" +
            "})();\n" +
            "},d);\n" +
            "}\n" +
            "setInterval(function(){\n" +
            "try{\n" +
            "if(!pageOpen())sawClosed=true;\n" +
            "else if(sawClosed&&!armed&&!busy)armed=true;\n" +
            "if(busy&&Date.now()-busyT>4000){cleanAll();}\n" +
            "}catch(z){}\n" +
            "},300);\n" +
            "document.addEventListener('touchstart',function(e){\n" +
            "tr=false;\n" +
            "if(!e.touches||e.touches.length!==1)return;\n" +
            "if(!pageOpen())return;\n" +
            "var t=e.touches[0];\n" +
            "sx=t.clientX;sy=t.clientY;tr=true;\n" +
            "},{passive:true,capture:true});\n" +
            "document.addEventListener('touchend',function(e){\n" +
            "if(!tr)return;tr=false;\n" +
            "if(!pageOpen())return;\n" +
            "var t=e.changedTouches&&e.changedTouches[0];if(!t)return;\n" +
            "var dy=t.clientY-sy,dx=t.clientX-sx;\n" +
            "if(dy<36||Math.abs(dx)>dy*1.2)return;\n" +
            "try{tryNative();}catch(z){}\n" +
            "try{tryClick();}catch(z){}\n" +
            "},{passive:true,capture:true});\n" +
            "document.addEventListener('touchcancel',function(){tr=false;},{passive:true,capture:true});\n" +
            "try{var __pk=document.getElementById('__rota_peek');if(__pk)__pk.remove();document.documentElement.classList.remove('rota-peek');document.documentElement.classList.remove('rota-noanim');}catch(z){}\n" +
            "}catch(e){}})();\n"

        const val KEEP_PLAYING_JS =
            "(function(){try{" +
                "Object.defineProperty(document,'hidden',{get:function(){return false},configurable:true});" +
                "Object.defineProperty(document,'visibilityState',{get:function(){return 'visible'},configurable:true});" +
                "document.addEventListener('visibilitychange',function(e){" +
                "document.querySelectorAll('video,audio').forEach(function(m){if(!m.paused)m.dataset.pusulaWasPlaying='1';});" +
                "e.stopImmediatePropagation();},true);" +
                // Kullanıcı durdurduysa arka planda tekrar başlatma
                "document.addEventListener('pause',function(e){try{var m=e.target;if(!m||!m.tagName)return;" +
                "if(m.tagName!=='VIDEO'&&m.tagName!=='AUDIO')return;" +
                "if(window.__pipBlock&&!window.__userPause)return;" +
                "m.dataset.pusulaWasPlaying='';}catch(z){}},true);" +
                "document.addEventListener('play',function(e){try{var m=e.target;if(!m||!m.tagName)return;" +
                "if(m.tagName!=='VIDEO'&&m.tagName!=='AUDIO')return;" +
                "m.dataset.pusulaWasPlaying='1';window.__userPause=false;}catch(z){}},true);" +
                "}catch(e){}})();"

        // Reklam sırasında: siyah bekleme ekranı örtülür, ses tamamen kısılır, altyazı ve reklam arayüzü gizlenir,
        // reklam 16 kat hızlandırılır ve "Atla" düğmesine basılır. Reklam bitince ekran varsayılan haline döner.
        const val STABLE_AD_BLOCKER_JS =
            "(function(){" +
            "if(location.hostname.indexOf('youtube.com')<0)return;" +
            "var fast=false,gap=0,cover=null,obs=null,obsPl=null,tick=0;" +
            "var sels=['.ytp-ad-player-overlay','.ytp-ad-player-overlay-layout','.ytp-ad-player-overlay-instream-info','.ytp-ad-text','.ytp-ad-text-overlay','.ytp-ad-preview-container','.ytp-ad-preview-text','.ytp-ad-message-container','.ytp-ad-info-dialog-container','.ytp-ad-overlay-container','.ytp-ad-overlay-slot','.ytp-ad-image-overlay','.ytp-ad-progress','.ytp-ad-progress-list','.ytp-ad-feedback-dialog-container','.ytp-ad-skip-button-container','.caption-window','.ytp-caption-window-container','.ytp-caption-segment','.ytp-ad-module','.video-ads','.ytp-ad-action-interstitial','ytd-ad-slot-renderer','ytd-banner-promo-renderer','ytd-player-legacy-desktop-watch-ads-renderer','.ytp-paid-content-overlay','.ytp-ad-simple-ad-badge','.ytp-ad-button-container'];" +
            "function hideAdUi(on){" +
            "for(var i=0;i<sels.length;i++){" +
            "document.querySelectorAll(sels[i]).forEach(function(el){" +
            "try{" +
            "if(on){el.style.setProperty('display','none','important');el.style.setProperty('visibility','hidden','important');el.style.setProperty('opacity','0','important');}" +
            "else{el.style.removeProperty('display');el.style.removeProperty('visibility');el.style.removeProperty('opacity');}" +
            "}catch(x){}" +
            "});" +
            "}" +
            "}" +
            "function ensureCover(pl){" +
            "if(cover&&cover.parentNode===pl)return cover;" +
            "cover=document.createElement('div');" +
            "cover.id='__adcover';" +
            "cover.style.cssText='position:absolute;left:0;top:0;right:0;bottom:0;background:#000;z-index:2147483000;pointer-events:none;display:none';" +
            "if(!document.getElementById('__adcss')){" +
            "var st=document.createElement('style');" +
            "st.id='__adcss';" +
            "st.textContent='@keyframes __adsp{to{transform:rotate(360deg)}}';" +
            "document.documentElement.appendChild(st);" +
            "}" +
            "var sp=document.createElement('div');" +
            "sp.style.cssText='position:absolute;left:50%;top:50%;width:44px;height:44px;margin:-22px 0 0 -22px;box-sizing:border-box;border:4px solid rgba(255,255,255,.22);border-top-color:rgba(255,255,255,.9);border-radius:50%;animation:__adsp .9s linear infinite';" +
            "cover.appendChild(sp);" +
            "pl.appendChild(cover);" +
            "return cover;" +
            "}" +
            "function endAd(vd){" +
            "fast=false;" +
            "hideAdUi(false);" +
            "if(!vd)return;" +
            "vd.style.visibility='';" +
            "vd.playbackRate=1;" +
            // Reklam bitince asla muted bırakma — ses her zaman geri açılır
            "vd.muted=false;" +
            "var tv=parseFloat(vd.dataset.pv||'1')||1;" +
            "vd.volume=0;" +
            "var k=0;" +
            "var iv=setInterval(function(){k++;vd.muted=false;vd.volume=Math.min(tv,tv*(k/12));if(k>=12){clearInterval(iv);vd.volume=tv;vd.muted=false;}},30);" +
            "}" +
            "function check(){" +
            "try{" +
            "var pl=document.querySelector('.html5-video-player');" +
            "if(!pl)return;" +
            "var vd=pl.querySelector('video');" +
            "var ad=pl.classList.contains('ad-showing')||pl.classList.contains('ad-interrupting');" +
            "var cv=ensureCover(pl);" +
            "if(ad){" +
            "gap=0;" +
            "cv.style.display='block';" +
            "tick++;" +
            "if(tick%5===1)hideAdUi(true);" +
            "if(vd){" +
            "if(!fast){fast=true;vd.dataset.pv=String(vd.volume>0?vd.volume:1);}" +
            // muted kullanma — sadece volume=0 (reklam sesi); içerik muted kalmasın
            "try{vd.volume=0;}catch(x){}" +
            "vd.style.visibility='hidden';" +
            "if(vd.playbackRate<8)vd.playbackRate=16;" +
            "}" +
            "var b=pl.querySelector('.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-skip-ad-button,.ytp-ad-skip-button-container button');" +
            "if(b)try{b.click();}catch(x){}" +
            "}else if(fast||cv.style.display!=='none'){" +
            "if(vd&&vd.playbackRate>1)vd.playbackRate=1;" +
            "gap++;" +
            "if(gap>=6){" +
            "gap=0;" +
            "cv.style.display='none';" +
            "endAd(vd);" +
            "}" +
            "}" +
            "}catch(e){}" +
            "}" +
            "function watch(){" +
            "var pl=document.querySelector('.html5-video-player');" +
            "if(pl&&pl!==obsPl){" +
            "obsPl=pl;" +
            "if(obs)obs.disconnect();" +
            "obs=new MutationObserver(check);" +
            "obs.observe(pl,{attributes:true,attributeFilter:['class']});" +
            "}" +
            "}" +
            "setInterval(function(){watch();check();},60);" +
            "})();"

        // YouTube üst çubuğundaki "Uygulamada aç" düğmesini gizler; başka hiçbir şeye dokunmaz.
        const val OPEN_APP_HIDE_JS =
            "(function(){" +
            "if(location.hostname.indexOf('youtube.com')<0)return;" +
            "var reApp=/uygulamada\\s*a[cç]|uygulamay[ıi]\\s*a[cç]|uygulamay[ıi]\\s*(edin|indir)|open\\s*(in\\s*)?(the\\s*)?app|get\\s*the\\s*app|use\\s*the\\s*app/i;" +
            "var rePrem=/premium|youtube\\s*premium|üye\\s*ol|abone\\s*ol|try\\s*premium|get\\s*premium|go\\s*premium|premium.?a\\s*geç|premium.?üye/i;" +
            "function hideEl(e){try{e.style.setProperty('display','none','important');e.style.setProperty('visibility','hidden','important');e.style.setProperty('height','0','important');e.style.setProperty('max-height','0','important');e.style.setProperty('overflow','hidden','important');e.style.setProperty('opacity','0','important');}catch(x){}}" +
            "function hide(){" +
            "try{" +
            // Uygulamada aç / uygulamayı yükle bannerları
            "document.querySelectorAll('ytm-open-app-button-renderer,ytm-open-app-banner-renderer,ytd-open-app-button-renderer,ytmusic-open-app-button,ytm-promo-banner,ytmusic-pivot-bar-renderer ytm-promo-banner,ytm-app-promo,ytd-app-promo,ytmusic-mealbar-promo-renderer').forEach(hideEl);" +
            "var reApp=/uygulama(y[ıi]\\s*)?(a[cç]|yükle|indir)|open\\s*(the\\s*)?app|install\\s*(the\\s*)?app|get\\s*(the\\s*)?app|open\\s*in\\s*app|try\\s*the\\s*app/i;" +
            "document.querySelectorAll('button,a,ytm-button-renderer,yt-button-renderer,ytmusic-button-renderer,[role=button]').forEach(function(e){" +
            "var t=((e.getAttribute('aria-label')||'')+' '+(e.textContent||'')).replace(/\\s+/g,' ').trim();" +
            "if(t.length>0&&t.length<80&&reApp.test(t))hideEl(e);" +
            "});" +
            // Premium upsell / mealtime / offer banners
            "document.querySelectorAll('ytm-mealbar-promo-renderer,ytd-mealbar-promo-renderer,ytm-premium-upsell,ytd-premium-upsell,ytmusic-premium-upsell,ytm-banner-promo-renderer,ytd-banner-promo-renderer,ytm-statement-banner-renderer,.ytp-pause-overlay-container,.premium-upsell,.ytmusic-toast-renderer').forEach(hideEl);" +
            "document.querySelectorAll('[class*=\"premium\"],[id*=\"premium\"]').forEach(function(e){var t=(e.textContent||'');if(t.length<200&&rePrem.test(t))hideEl(e);});" +
            "var head=document.querySelector('ytm-mobile-topbar-renderer')||document.querySelector('header')||document.querySelector('ytmusic-nav-bar');" +
            "if(head){" +
            "head.querySelectorAll('a,button,ytm-button-renderer,yt-button-renderer').forEach(function(e){" +
            "var t=((e.getAttribute('aria-label')||'')+' '+(e.textContent||'')).trim();" +
            "if(t.length<80&&(reApp.test(t)||rePrem.test(t))){var c=e.closest('ytm-button-renderer,yt-button-renderer')||e;hideEl(c);}" +
            "});" +
            "}" +
            // Premium diyalogları kapat
            "document.querySelectorAll('tp-yt-paper-dialog,yt-confirm-dialog-renderer,.yt-upsell-dialog').forEach(function(d){" +
            "var t=(d.textContent||'');if(rePrem.test(t)){hideEl(d);var b=d.querySelector('#cancel-button,button');if(b)try{b.click();}catch(z){}}" +
            "});" +
            "}catch(x){}" +
            "}" +
            "setInterval(hide,600);" +
            "})();"

        // YouTube sayfasında ortadaki büyük logo / oynat katmanı ve watermark
        const val YT_HIDE_LOGO_JS =
            "(function(){try{" +
            "if(location.hostname.indexOf('youtube.com')<0&&location.hostname.indexOf('youtu.be')<0)return;" +
            "if(document.getElementById('__rota_nologo'))return;" +
            "var s=document.createElement('style');s.id='__rota_nologo';" +
            "s.textContent='" +
            ".ytp-large-play-button,.ytp-cued-thumbnail-overlay,.ytp-pause-overlay," +
            ".ytp-watermark,.ytp-youtube-button,.annotation," +
            ".ytp-cued-thumbnail-overlay-image{display:none!important;opacity:0!important;visibility:hidden!important;pointer-events:none!important}" +
            "';" +
            "document.documentElement.appendChild(s);" +
            "}catch(e){}})();"

        // YouTube: videoyu aşağı çek → video parmakla birlikte sağ alta küçülür (hareketi native yönetir)
        const val YT_MINI_PLAYER_JS = """
        (function(){try{
        if(location.hostname.indexOf('youtube.com')<0||location.hostname.indexOf('music.')===0)return;
        if(window.__rotaMini3)return;window.__rotaMini3=1;
        window.addEventListener('scroll',function(){if(window.scrollX>0)window.scrollTo(0,window.scrollY);},{passive:true});
        var st=document.createElement('style');
        st.textContent='#movie_player,.html5-video-player{touch-action:none!important}';
        document.documentElement.appendChild(st);
        var sx=0,sy=0,pl=null,drag=false,locked=0,started=false,dy=0,lx=0;
        function watch(){return location.pathname.indexOf('/watch')===0&&window.innerHeight>400&&!document.fullscreenElement&&!!window.RotaMini;}
        function player(){return document.querySelector('#movie_player')||document.querySelector('.html5-video-player');}
        document.addEventListener('touchstart',function(e){
        drag=false;locked=0;started=false;dy=0;
        if(!watch()||e.touches.length>1)return;
        var t=e.touches[0];if(!t)return;
        pl=player();if(!pl)return;
        var r=pl.getBoundingClientRect();
        if(t.clientY<r.top||t.clientY>r.bottom||t.clientX<r.left||t.clientX>r.right)return;
        sx=t.clientX;sy=t.clientY;drag=true;
        },{passive:true,capture:true});
        document.addEventListener('touchmove',function(e){
        if(!drag)return;
        var t=e.touches[0];if(!t)return;
        var mx=t.clientX-sx,my=t.clientY-sy;
        if(!locked){if(Math.abs(mx)+Math.abs(my)<12)return;locked=(my>0&&Math.abs(my)>Math.abs(mx)*1.2)?1:2;}
        if(locked!==1){drag=false;return;}
        try{e.preventDefault();}catch(z){}
        dy=my;lx=t.clientX;
        if(!started){
        started=true;
        var v=document.querySelector('video');window.__rotaWas=!!(v&&!v.paused);
        try{window.scrollTo(0,0);}catch(z){}
        try{var rr=pl.getBoundingClientRect();window.RotaMini.start(rr.bottom,rr.top);}catch(z){}
        }
        try{window.RotaMini.move(dy,lx);}catch(z){}
        },{passive:false,capture:true});
        document.addEventListener('touchend',function(){
        if(!drag)return;drag=false;
        if(started){started=false;try{window.RotaMini.end(dy,lx,false);}catch(z){}}
        },{passive:true,capture:true});
        document.addEventListener('touchcancel',function(){
        if(!drag)return;drag=false;
        if(started){started=false;try{window.RotaMini.end(dy,lx,true);}catch(z){}}
        },{passive:true,capture:true});
        }catch(e){}})();
        """


        // Küçük pencerede: oynatıcının üstündeki YouTube yazıları/kontroller gizlenir, video pencereyi doldurur
        const val YT_MINI_CSS_ON_JS = """
        (function(){try{
        var s=document.getElementById('__rota_mini_css');
        if(!s){s=document.createElement('style');s.id='__rota_mini_css';document.documentElement.appendChild(s);}
        s.textContent='html,body{background:#000!important;overflow:hidden!important}'+
        'body *{visibility:hidden!important}'+
        'video{visibility:visible!important;object-fit:cover!important}'+
        '.html5-video-player{background:#000!important}';
        }catch(e){}})();
        """

        const val YT_MINI_CSS_OFF_JS = """
        (function(){try{var s=document.getElementById('__rota_mini_css');if(s)s.remove();}catch(e){}})();
        """

        // Küçük pencere açıkken gezinme sayfasında video ön izlemeleri oynamaz
        const val YT_NO_PREVIEW_JS = """
        (function(){try{
        if(location.hostname.indexOf('youtube.com')<0)return;
        if(window.__rotaNoPreview===undefined)window.__rotaNoPreview=false;
        if(!window.__rotaPlayHooked){
        window.__rotaPlayHooked=true;
        var op=HTMLMediaElement.prototype.play;
        HTMLMediaElement.prototype.play=function(){
        if(window.__rotaNoPreview&&location.pathname.indexOf('/watch')!==0){try{this.pause();}catch(e){}return Promise.resolve();}
        return op.apply(this,arguments);
        };
        }
        function stop(){
        if(!window.__rotaNoPreview||location.pathname.indexOf('/watch')===0)return;
        document.querySelectorAll('video,audio').forEach(function(v){try{if(!v.paused)v.pause();}catch(e){}});
        }
        if(!window.__rotaNpTimer)window.__rotaNpTimer=setInterval(stop,600);
        stop();
        }catch(e){}})();
        """

        // Normal videolar altyazısız başlar (reklam dışında). Kullanıcı sonradan açarsa karışılmaz.
        const val CAPTIONS_OFF_JS =
            "(function(){" +
            "if(location.hostname.indexOf('youtube.com')<0)return;" +
            "var vid='',t0=0,tries=0,clicked=false;" +
            "function curId(){" +
            "try{var p=document.getElementById('movie_player');if(p&&p.getVideoData){var d=p.getVideoData();if(d&&d.video_id)return d.video_id;}}catch(e){}" +
            "return location.href;" +
            "}" +
            "function capsOff(){" +
            "try{" +
            "var pl=document.getElementById('movie_player')||document.querySelector('.html5-video-player');" +
            "if(!pl)return;" +
            "if(pl.classList.contains('ad-showing')||pl.classList.contains('ad-interrupting'))return;" +
            "var id=curId();" +
            "if(id!==vid){vid=id;t0=Date.now();tries=0;clicked=false;}" +
            "if(Date.now()-t0>8000)return;" +
            "var btn=pl.querySelector('.ytp-subtitles-button');" +
            "var on=false;" +
            "if(btn&&btn.getAttribute('aria-pressed')==='true')on=true;" +
            "if(!on&&pl.getOption){try{var tr=pl.getOption('captions','track');if(tr&&tr.languageCode)on=true;}catch(x){}}" +
            "if(!on)return;" +
            "tries++;" +
            "if(tries<=3&&pl.setOption){" +
            "try{pl.setOption('captions','track',{});}catch(x){}" +
            "}else if(!clicked&&btn&&btn.getAttribute('aria-pressed')==='true'){" +
            "clicked=true;" +
            "try{btn.click();}catch(x){}" +
            "}" +
            "}catch(e){}" +
            "}" +
            "setInterval(capsOff,400);" +
            "})();"

        const val BACKGROUND_MEDIA_JS =
            "(function(){try{" +
                "if(window.__userPause)return;" +
                "document.querySelectorAll('video,audio').forEach(function(m){" +
                "if(m.dataset.pusulaWasPlaying==='1' && m.paused){var p=m.play();if(p&&p.catch)p.catch(function(){});}" +
                "});" +
                "}catch(e){}})();"

        const val MEDIA_TOGGLE_JS =
            "(function(){try{" +
            "function mediaList(){return Array.from(document.querySelectorAll('video,audio'));}" +
            "function fadeTo(m,from,to,ms,done){var steps=Math.max(8,Math.round(ms/25)),i=0;m.volume=from;var iv=setInterval(function(){i++;var t=i/steps;var e=0.5-0.5*Math.cos(Math.PI*t);m.volume=Math.max(0,Math.min(1,from+(to-from)*e));if(i>=steps){clearInterval(iv);m.volume=to;if(done)done();}},25);}" +
            "var v=mediaList().find(function(m){return !m.paused&&!m.ended;})||document.querySelector('video,audio');" +
            "if(v){" +
            "if(!v.paused){window.__userPause=true;" +
            "var from=v.volume;v.dataset._fv=String(from);v.dataset.pusulaWasPlaying='';" +
            "fadeTo(v,from,0,350,function(){v.pause();try{v.volume=from;}catch(z){}});" +
            "}else{" +
            "window.__userPause=false;v.dataset.pusulaWasPlaying='1';var to=parseFloat(v.dataset._fv||'1');if(!(to>0))to=1;v.volume=0;if(v.muted)v.muted=false;" +
            "var p=v.play();if(p&&p.catch)p.catch(function(){});" +
            "fadeTo(v,0,to,450,function(){try{delete v.dataset._fv;}catch(z){}});" +
            "}" +
            "return;}" +
            "var b=document.querySelector('.ytp-play-button,#play-pause-button,ytmusic-player-bar #play-pause-button,ytmusic-play-button-renderer');" +
            "if(b)b.click();" +
            "}catch(e){}})();"

        // PiP / küçük pencere düğmeleri için gecikmesiz oynat-duraklat (ses geçişi yok)
        const val MEDIA_TOGGLE_FAST_JS =
            "(function(){try{" +
            "var l=Array.from(document.querySelectorAll('video,audio'));" +
            "var v=l.find(function(m){return !m.paused&&!m.ended;})||document.querySelector('video,audio');" +
            "if(v){" +
            "if(!v.paused){window.__userPause=true;v.dataset.pusulaWasPlaying='';v.pause();}" +
            "else{window.__userPause=false;v.dataset.pusulaWasPlaying='1';if(v.muted)v.muted=false;var p=v.play();if(p&&p.catch)p.catch(function(){});}" +
            "if(window.__pipFlash)window.__pipFlash();return;}" +
            "var b=document.querySelector('.ytp-play-button,#play-pause-button,ytmusic-player-bar #play-pause-button,ytmusic-play-button-renderer');" +
            "if(b)b.click();" +
            "}catch(e){}})();"

        // Zorla oynat (toggle yok)
        const val MEDIA_FORCE_PLAY_JS =
            "(function(){try{" +
            "window.__userPause=false;" +
            "document.querySelectorAll('video,audio').forEach(function(m){" +
            "m.dataset.pusulaWasPlaying='1';try{m.muted=false;}catch(z){}" +
            "if(m.paused){var p=m.play();if(p&&p.catch)p.catch(function(){});}" +
            "});" +
            "var v=document.querySelector('video,audio');" +
            "if(!v||(v.paused&&v.readyState<2)){" +
            "var b=document.querySelector('.ytp-play-button,#play-pause-button,ytmusic-player-bar #play-pause-button,ytmusic-play-button-renderer');" +
            "if(b)b.click();}" +
            "}catch(e){}})();"

        // PiP'ten dönünce video düzeni bozuk mu? (oynatıcı sayfa genişliğinden dar ya da PiP stili kalmış)
        const val PIP_LAYOUT_CHECK_JS =
            "(function(){try{" +
            "if(location.pathname.indexOf('/watch')!==0)return 'ok';" +
            "var p=document.querySelector('#movie_player,.html5-video-player');if(!p)return 'ok';" +
            "var r=p.getBoundingClientRect();" +
            "var bad=window.scrollX>0||(window.scrollY>0&&window.scrollY<160)||r.width<window.innerWidth*0.9||r.height<60||document.getElementById('__pip')!==null||document.querySelector('.__pipv')!==null;" +
            "return bad?'bad':'ok';" +
            "}catch(e){return 'ok';}})();"

        const val FIX_HSCROLL_JS =
            "(function(){try{var e=document.scrollingElement||document.documentElement;" +
            "if(e&&e.scrollLeft)e.scrollLeft=0;if(window.scrollX)window.scrollTo(0,window.scrollY);" +
            "if(location.pathname.indexOf('/watch')===0&&window.scrollY>0&&window.scrollY<160)window.scrollTo(0,0);" +
            "if(document.body&&document.body.scrollLeft)document.body.scrollLeft=0;" +
            "}catch(x){}})();"

        const val PIP_RELAYOUT_JS =
            "(function(){try{" +
            "var e0=document.scrollingElement||document.documentElement;if(e0&&e0.scrollLeft)e0.scrollLeft=0;if(window.scrollX)window.scrollTo(0,window.scrollY);" +
            "if(location.pathname.indexOf('/watch')===0&&window.scrollY>0&&window.scrollY<160)window.scrollTo(0,0);" +
            "var s=document.getElementById('__pip');if(s)s.remove();" +
            "var pb=document.getElementById('__pipbar');if(pb)pb.remove();" +
            "var mc=document.getElementById('__rota_mini_css');if(mc)mc.remove();" +
            "document.querySelectorAll('.__pipv').forEach(function(x){x.classList.remove('__pipv');});" +
            "var p=document.querySelector('.html5-video-player');if(p)p.classList.remove('ytp-pause-overlay');" +
            "var v=document.querySelector('video.html5-main-video')||document.querySelector('video');" +
            "if(v){try{v.style.removeProperty('transform');v.style.removeProperty('object-fit');v.style.removeProperty('object-position');}catch(z){}}" +
            "window.dispatchEvent(new Event('resize'));" +
            "}catch(e){}})();"

        const val MEDIA_PAUSE_FAST_JS =
            "(function(){try{" +
            "window.__userPause=true;" +
            "document.querySelectorAll('video,audio').forEach(function(m){try{m.dataset.pusulaWasPlaying='';if(!m.paused)m.pause();}catch(z){}});" +
            "if(window.__pipFlash)window.__pipFlash();" +
            "}catch(e){}})();"

        const val MEDIA_PAUSE_JS =
            "(function(){try{" +
            "function fadeTo(m,from,to,ms,done){var steps=Math.max(8,Math.round(ms/25)),i=0;m.volume=from;var iv=setInterval(function(){i++;var t=i/steps;var e=0.5-0.5*Math.cos(Math.PI*t);m.volume=Math.max(0,Math.min(1,from+(to-from)*e));if(i>=steps){clearInterval(iv);m.volume=to;if(done)done();}},25);}" +
            "document.querySelectorAll('video,audio').forEach(function(m){" +
            "window.__userPause=true;if(!m.paused){var from=m.volume;m.dataset._fv=String(from);fadeTo(m,from,0,300,function(){m.pause();try{m.volume=from;}catch(z){}m.dataset.pusulaWasPlaying='';});}" +
            "});" +
            "}catch(e){}})();"

        const val MEDIA_PREVIOUS_JS =
            "(function(){try{" +
            "function mediaList(){return Array.from(document.querySelectorAll('video,audio'));}" +
            "function fadeTo(m,from,to,ms,done){var steps=Math.max(8,Math.round(ms/25)),i=0;m.volume=from;var iv=setInterval(function(){i++;var t=i/steps;var e=0.5-0.5*Math.cos(Math.PI*t);m.volume=Math.max(0,Math.min(1,from+(to-from)*e));if(i>=steps){clearInterval(iv);m.volume=to;if(done)done();}},25);}" +
            "function fadeOutAll(ms,done){var list=mediaList(),n=list.length,left=n;if(!n){if(done)done();return;}list.forEach(function(m){try{var from=m.volume;m.dataset._fv=String(from);fadeTo(m,from,0,ms,function(){left--;if(left<=0&&done)done();});}catch(x){left--;if(left<=0&&done)done();}});}" +
            "function fadeInAll(ms){setTimeout(function(){mediaList().forEach(function(m){try{var to=parseFloat(m.dataset._fv||'1');if(!(to>0))to=1;m.volume=0;if(m.muted)m.muted=false;fadeTo(m,0,to,ms,function(){try{delete m.dataset._fv;}catch(z){}});}catch(x){}});},80);}" +
            "function clickEl(el){if(!el)return false;var btn=(el.tagName==='BUTTON'||el.getAttribute('role')==='button')?el:(el.querySelector&&(el.querySelector('button')||el.querySelector('[role=button]')))||el;try{btn.click();return true;}catch(x){try{el.click();return true;}catch(y){return false;}}}" +
            "function sig(){var a='',b='';try{var mp=document.getElementById('movie_player');if(mp&&mp.getVideoData)a=String(mp.getVideoData().video_id||'');}catch(x){}try{var t=document.querySelector('ytmusic-player-bar .title,.title.ytmusic-player-bar,ytm-slim-video-metadata-section-renderer h2,h1.title');b=t?t.textContent.trim():'';}catch(x){}return a+'|'+b;}" +
            "function waitChange(s0,ms,yes,no){var t0=Date.now();(function w(){if(sig()!==s0){yes();return;}if(Date.now()-t0>ms){no();return;}setTimeout(w,100);})();}" +
            "function doPrev(){var s0=sig(),mp=document.getElementById('movie_player');" +
            "if(mp&&typeof mp.previousVideo==='function'){try{mp.previousVideo();}catch(x){}waitChange(s0,1500,function(){fadeInAll(500);},function(){doPrev3(s0);});}else{doPrev3(s0);}}" +
            "function doPrev3(s0){doPrev2();waitChange(s0,1200,function(){fadeInAll(500);},function(){doPrev2();});}" +
            "function doPrev2(){var h=window.__msh&&window.__msh.previoustrack;if(h){try{h({action:'previoustrack'});fadeInAll(500);return;}catch(x){}}" +
            "var sels=['.ytp-prev-button','.ytp-chrome-bottom .ytp-prev-button','ytmusic-player-bar .previous-button','ytmusic-player-controls .previous-button','ytmusic-player-bar yt-icon-button.previous-button','ytmusic-player-controls yt-icon-button.previous-button','#left-controls .previous-button','.previous-button','[aria-label=\\\"Previous\\\"]','[aria-label=\\\"Önceki\\\"]','[aria-label*=\\\"Previous track\\\"]','[aria-label*=\\\"Önceki parça\\\"]','[aria-label*=\\\"previous\\\"]'];" +
            "for(var i=0;i<sels.length;i++){if(clickEl(document.querySelector(sels[i]))){fadeInAll(500);return;}}" +
            "var v=document.querySelector('video.html5-main-video')||document.querySelector('video');if(v){v.currentTime=0;}fadeInAll(500);}" +
            "fadeOutAll(400,doPrev);" +
            "}catch(e){}})();"

        const val MEDIA_NEXT_JS =
            "(function(){try{" +
            "function mediaList(){return Array.from(document.querySelectorAll('video,audio'));}" +
            "function fadeTo(m,from,to,ms,done){var steps=Math.max(8,Math.round(ms/25)),i=0;m.volume=from;var iv=setInterval(function(){i++;var t=i/steps;var e=0.5-0.5*Math.cos(Math.PI*t);m.volume=Math.max(0,Math.min(1,from+(to-from)*e));if(i>=steps){clearInterval(iv);m.volume=to;if(done)done();}},25);}" +
            "function fadeOutAll(ms,done){var list=mediaList(),n=list.length,left=n;if(!n){if(done)done();return;}list.forEach(function(m){try{var from=m.volume;m.dataset._fv=String(from);fadeTo(m,from,0,ms,function(){left--;if(left<=0&&done)done();});}catch(x){left--;if(left<=0&&done)done();}});}" +
            "function fadeInAll(ms){setTimeout(function(){mediaList().forEach(function(m){try{var to=parseFloat(m.dataset._fv||'1');if(!(to>0))to=1;m.volume=0;if(m.muted)m.muted=false;fadeTo(m,0,to,ms,function(){try{delete m.dataset._fv;}catch(z){}});}catch(x){}});},80);}" +
            "function clickEl(el){if(!el)return false;var btn=(el.tagName==='BUTTON'||el.getAttribute('role')==='button')?el:(el.querySelector&&(el.querySelector('button')||el.querySelector('[role=button]')))||el;try{btn.click();return true;}catch(x){try{el.click();return true;}catch(y){return false;}}}" +
            "function doNext(){var h=window.__msh&&window.__msh.nexttrack;if(h){try{h({action:'nexttrack'});fadeInAll(500);return;}catch(x){}}" +
            "var sels=['.ytp-next-button','.ytp-chrome-bottom .ytp-next-button','ytmusic-player-bar .next-button','ytmusic-player-controls .next-button','ytmusic-player-bar yt-icon-button.next-button','ytmusic-player-controls yt-icon-button.next-button','#left-controls .next-button','.next-button','[aria-label=\\\"Next\\\"]','[aria-label=\\\"Sonraki\\\"]','[aria-label*=\\\"Next track\\\"]','[aria-label*=\\\"Sonraki parça\\\"]','[aria-label*=\\\"next\\\"]'];" +
            "for(var i=0;i<sels.length;i++){if(clickEl(document.querySelector(sels[i]))){fadeInAll(500);return;}}" +
            "var v=document.querySelector('video.html5-main-video')||document.querySelector('video');if(v&&isFinite(v.duration)){v.currentTime=v.duration;}fadeInAll(500);}" +
            "fadeOutAll(400,doNext);" +
            "}catch(e){}})();"

        const val MEDIA_HOOK_JS =
            "(function(){try{var ms=navigator.mediaSession;if(!ms||!ms.setActionHandler)return;var o=ms.setActionHandler.bind(ms);window.__msh={};ms.setActionHandler=function(a,f){window.__msh[a]=f;return o(a,f);};}catch(e){}})();"

        const val YT_MUSIC_CATALOG_JS =
        "(function(){try{" +
        "if(!/music\\.youtube\\.com/i.test(location.hostname||''))return JSON.stringify({playlists:[],songs:[],page:location.href});" +
        "var playlists=[],songs=[],seen={};" +
        "function abs(u){if(!u)return '';u=String(u);if(u.indexOf('http')===0)return u.split('?')[0].indexOf('google')>=0?u:u;if(u.indexOf('//')===0)return location.protocol+u;return u;}" +
        "function txt(el){if(!el)return '';return (el.getAttribute('title')||el.getAttribute('aria-label')||el.textContent||'').replace(/\\s+/g,' ').trim();}" +
        "function pushP(title,sub,url,art){title=(title||'').replace(/\\s+/g,' ').trim();url=(url||'').trim();if(!title||title.length<2)return;if(!url||url.indexOf('http')!==0)return;" +
        "if(/signin|accounts\\.google|language|feedback/i.test(url+title))return;if(seen['p'+url])return;seen['p'+url]=1;" +
        "playlists.push({title:title.slice(0,90),artist:(sub||'').replace(/\\s+/g,' ').trim().slice(0,90),url:url,art:abs(art||'')});}" +
        "function pushS(title,artist,url,art){title=(title||'').replace(/\\s+/g,' ').trim();if(!title||title.length<1)return;" +
        "if(/^(oynat|play|next|previous|karıştır)/i.test(title))return;var id=url||(title+'|'+artist);if(seen['s'+id])return;seen['s'+id]=1;" +
        "songs.push({title:title.slice(0,90),artist:(artist||'').replace(/\\s+/g,' ').trim().slice(0,90),url:(url||'').trim(),art:abs(art||'')});}" +
        // two-row cards (listeler / albümler / çalma listeleri)
        "document.querySelectorAll('ytmusic-two-row-item-renderer,ytmusic-responsive-list-item-renderer a[href*=\"browse\"],ytmusic-carousel-item-renderer').forEach(function(el){" +
        "var root=el.tagName&&el.tagName.toLowerCase().indexOf('a')>=0?el.closest('ytmusic-two-row-item-renderer,ytmusic-carousel-item-renderer,ytmusic-responsive-list-item-renderer')||el:el;" +
        "var a=root.querySelector?root.querySelector('a[href*=\"browse\"],a[href*=\"playlist\"],a[href*=\"channel\"],a[href]'): (root.href?root:null);" +
        "if(!a)a=root.querySelector&&root.querySelector('a[href]');" +
        "var href=a?(a.href||a.getAttribute('href')||''):'';" +
        "if(href&&href.indexOf('http')!==0){try{href=new URL(href,location.href).href;}catch(z){}}" +
        "var title=txt(root.querySelector&&root.querySelector('#title,yt-formatted-string#title,.title,a[title],yt-formatted-string[title]'));" +
        "if(!title&&a)title=txt(a);" +
        "var sub=txt(root.querySelector&&root.querySelector('#subtitle,.subtitle,yt-formatted-string.subtitle'));" +
        "var img=root.querySelector&&root.querySelector('img');var art=img?(img.getAttribute('src')||img.src||''):'';" +
        "if(href&&(/browse|playlist|channel/.test(href)||(root.tagName||'').toLowerCase().indexOf('two-row')>=0))pushP(title,sub,href,art);" +
        "else if(href&&/watch/.test(href))pushS(title,sub,href,art);" +
        "});" +
        // şarkı satırları
        "document.querySelectorAll('ytmusic-responsive-list-item-renderer,ytmusic-player-queue-item,ytmusic-list-item-renderer').forEach(function(el){" +
        "var a=el.querySelector('a[href*=\"watch\"],a[href*=\"browse\"],a[href]');" +
        "var href=a?(a.href||''):'';" +
        "var title=txt(el.querySelector('.title,#title,yt-formatted-string.title,yt-formatted-string[class*=\"title\"]'));" +
        "if(!title)title=txt(el.querySelector('yt-formatted-string'));" +
        "var secs=el.querySelectorAll('.secondary-flex-columns yt-formatted-string,yt-formatted-string.flex-column,.subtitle yt-formatted-string,yt-formatted-string[class*=\"byline\"]');" +
        "var artist=secs.length?txt(secs[0]):'';" +
        "var img=el.querySelector('img');var art=img?(img.getAttribute('src')||img.src||''):'';" +
        "if(href&&/browse|playlist/.test(href)&&!/watch/.test(href))pushP(title,artist,href,art);" +
        "else pushS(title,artist,href,art);" +
        "});" +
        // genel link tarama (yedek)
        "if(playlists.length<3){document.querySelectorAll('a[href*=\"/browse/\"],a[href*=\"list=\"]').forEach(function(a){" +
        "var href=a.href||'';var title=txt(a);if(title.length<3){var p=a.closest('[id],[class]');title=txt(p);}pushP(title,'',href,'');});}" +
        "if(songs.length<3){document.querySelectorAll('a[href*=\"watch?v=\"]').forEach(function(a){" +
        "var title=txt(a);if(title.length<2)return;pushS(title,'',a.href,'');});}" +
        "return JSON.stringify({playlists:playlists.slice(0,50),songs:songs.slice(0,60),page:location.href,ts:Date.now()});" +
        "}catch(e){return JSON.stringify({playlists:[],songs:[],error:String(e),page:location.href});}})();"



    const val MEDIA_STATE_JS =
            "(function(){try{" +
            "var l=Array.from(document.querySelectorAll('video,audio'));" +
            "var v=l.find(function(m){return !m.paused&&!m.ended;})||l[0];" +
            "if(!v||!(v.currentSrc||v.src))return '';" +
            "var md=(navigator.mediaSession&&navigator.mediaSession.metadata)||null;" +
            "function tx(q){try{var e=document.querySelector(q);var t=e&&(e.getAttribute('title')||e.textContent);return t?t.trim():'';}catch(x){return '';}}" +
            "function site(t){return !t||/^YouTube( Music)?$/i.test(t.trim());}" +
            "var t=(md&&md.title)||'';" +
            "if(site(t))t=tx('ytmusic-player-bar .title')||tx('.player-bar-title')||tx('ytm-slim-video-metadata-section-renderer h2')||tx('.slim-video-information-title')||tx('ytd-watch-metadata h1')||tx('h1.title')||'';" +
            "if(site(t)){var ms=document.getElementsByTagName('meta');for(var i=0;i<ms.length;i++){if(ms[i].getAttribute('property')==='og:title'){t=ms[i].getAttribute('content')||'';break;}}}" +
            "if(site(t))t=(document.title||'').replace(/\\s*[-|]\\s*YouTube( Music)?\\s*$/i,'');" +
            "var a=(md&&md.artist)||tx('ytmusic-player-bar .byline')||tx('.slim-owner-channel-name')||tx('ytm-slim-owner-renderer .slim-owner-channel-name')||'';" +
            "var art='';" +
            "var art='';var H=location.hostname;var yt=/youtube\\.com|youtu\\.be/i.test(H);var ytm=/music\\.youtube\\.com/i.test(H);" +
            "function ok(u){return !!u&&/^(https?:)?\\/\\//.test(u)&&!/(favicon|logo|yt_1200)/i.test(u);}" +
            "function fx(u){try{u=new URL(u,location.href).href;}catch(x){return '';}var m=u.match(/\\/\\/(?:i|img)\\.ytimg\\.com\\/vi(?:_webp)?\\/([\\w-]{11})\\//);if(m)return 'https://i.ytimg.com/vi/'+m[1]+'/mqdefault.jpg';return u.replace(/=w\\d+-h\\d+/,'=w512-h512');}" +
            "try{if(md&&md.artwork&&md.artwork.length){var bs=-1;for(var k=0;k<md.artwork.length;k++){var w=parseInt(String(md.artwork[k].sizes||'0').split('x')[0],10)||0;var sr=md.artwork[k].src;if(ok(sr)&&w>=bs){bs=w;art=sr;}}}}catch(x){}" +
            "if(!art&&ytm){try{var im=document.querySelector('ytmusic-player-bar img.image,ytmusic-player-bar img,#song-image img');var s2=im&&(im.currentSrc||im.src);if(ok(s2))art=s2;}catch(x){}}" +
            "if(!art&&yt){try{var vid=new URLSearchParams(location.search).get('v');if(!vid){try{var pl=document.getElementById('movie_player');var vd=pl&&pl.getVideoData&&pl.getVideoData();if(vd&&vd.video_id)vid=vd.video_id;}catch(x){}}if(!vid){try{var cu=document.querySelector('link[rel=canonical],meta[property=\"og:url\"]');var cs=cu?(cu.href||cu.getAttribute('content')||''):'';var m3=cs.match(/[?&]v=([\\w-]{11})/);if(m3)vid=m3[1];}catch(x){}}if(!vid){var mm=location.pathname.match(/\\/(?:shorts|embed|live)\\/([\\w-]{11})/);if(mm)vid=mm[1];}if(vid&&/^[\\w-]{11}$/.test(vid))art='https://i.ytimg.com/vi/'+vid+'/mqdefault.jpg';}catch(x){}}" +
            "if(!art&&!yt){try{var mt=document.getElementsByTagName('meta');for(var q=0;q<mt.length;q++){var pp=mt[q].getAttribute('property')||mt[q].getAttribute('name')||'';if(pp==='og:image'||pp==='twitter:image'){var cc=mt[q].getAttribute('content')||'';if(ok(cc)){art=cc;break;}}}}catch(x){}}" +
            "if(art)art=fx(art);" +
            "try{if(art){window.__rart={t:t,a:art};}else if(window.__rart&&window.__rart.t===t){art=window.__rart.a;}}catch(x){}" +
            "return JSON.stringify({p:!v.paused&&!v.ended&&v.readyState>1,t:t,a:a,d:(isFinite(v.duration)?Math.round(v.duration*1000):-1),c:Math.round((v.currentTime||0)*1000),art:art||''});" +
            "}catch(e){return '';}})();"
        const val KEEP_MEDIA_PLAYING_JS =
            "(function(){try{" +
            "document.querySelectorAll('video,audio').forEach(function(m){" +
            "try{" +
            "if(m.ended)return;" +
            "if(m.paused){var p=m.play();if(p&&p.catch)p.catch(function(){});}" +
            "}catch(x){}" +
            "});" +
            "}catch(e){}})();"

        // Küçük pencere: video eski düzenle (tam kare) gösterilir. Sayfa, videoyu kendiliğinden
        // duraklatmasın diye video.pause() küçük pencere süresince engellenir; yalnızca
        // bizim oynat/duraklat düğmelerimiz (window.__userPause) duraklatabilir.
        // Eski çalışan PiP (referans: sohbet başı / pasted-text-2)
        const val PIP_ON_JS =
            "(function(){try{" +
            "if(document.getElementById('__pip'))return;" +
            "var v=document.querySelector('video.html5-main-video')||document.querySelector('.html5-video-player video')||document.querySelector('video');" +
            "if(!v)return;" +
            "try{window.__pipVol=v.volume;}catch(z){}" +
            "window.__userPause=false;window.__pipBlock=true;" +
            "if(!window.__op){window.__op=HTMLMediaElement.prototype.pause;HTMLMediaElement.prototype.pause=function(){if(window.__pipBlock&&!window.__userPause)return;return window.__op.apply(this,arguments);};}" +
            "var s=document.createElement('style');s.id='__pip';" +
            "s.textContent='" +
            "html,body{margin:0!important;padding:0!important;overflow:hidden!important;background:#000!important;width:100%!important;height:100%!important}" +
            "body>*{visibility:hidden!important}" +
            "video.__pipv,.html5-video-player,ytmusic-player-page,ytmusic-player{visibility:visible!important}" +
            ".html5-video-player{position:fixed!important;inset:0!important;width:100vw!important;height:100vh!important;z-index:2147483646!important;background:#000!important}" +
            "video.__pipv{position:fixed!important;top:0!important;left:0!important;width:100vw!important;height:100vh!important;max-width:100vw!important;max-height:100vh!important;z-index:2147483647!important;background:#000!important;object-fit:contain!important;margin:0!important;padding:0!important;transform:none!important}" +
            ".ytp-large-play-button,.ytp-cued-thumbnail-overlay,.ytp-pause-overlay,.ytp-watermark," +
            ".ytp-youtube-button,.ytp-title-channel,.ytp-chrome-top,.ytp-chrome-bottom," +
            ".ytp-gradient-top,.ytp-gradient-bottom,.annotation{display:none!important;opacity:0!important;visibility:hidden!important}" +
            "';" +
            "document.documentElement.appendChild(s);" +
            "v.classList.add('__pipv');" +
            "var e=v.parentElement;while(e&&e!==document.documentElement){e.style.setProperty('visibility','visible','important');e.style.setProperty('transform','none','important');e.style.setProperty('overflow','visible','important');e=e.parentElement;}" +
            "(function(){var bar=document.createElement('div');bar.id='__pipbar';" +
            "bar.style.cssText='position:fixed;left:0;right:0;bottom:0;height:4px;z-index:2147483647;background:rgba(255,255,255,.4);pointer-events:none;visibility:visible';" +
            "var fill=document.createElement('div');fill.style.cssText='height:100%;width:0;background:rgba(255,255,255,.95)';" +
            "bar.appendChild(fill);document.documentElement.appendChild(bar);" +
            "function tick(){var d=v.duration;if(!isFinite(d)||d<=0){bar.style.display='none';return;}bar.style.display='block';fill.style.width=(v.currentTime/d*100)+'%';}" +
            "tick();window.__pipIv=setInterval(tick,500);})();" +
            "}catch(e){}})();"

        const val PIP_OFF_JS =
            "(function(){try{" +
            "var pv=window.__pipVol;window.__pipVol=null;" +
            "if(pv>0){var n=0;var iv2=setInterval(function(){n++;try{if(!document.querySelector('.html5-video-player.ad-showing,.html5-video-player.ad-interrupting')){document.querySelectorAll('video,audio').forEach(function(m){if(!m.dataset._fv&&Math.abs(m.volume-pv)>0.001)m.volume=pv;});}}catch(z){}if(n>=8)clearInterval(iv2);},200);}" +
            "window.__pipBlock=false;window.__userPause=false;" +
            "if(window.__op){HTMLMediaElement.prototype.pause=window.__op;window.__op=null;}" +
            "var s=document.getElementById('__pip');if(s)s.remove();" +
            "clearInterval(window.__pipIv);document.removeEventListener('pointerdown',window.__pipDoc,true);window.__pipFlash=null;var pb=document.getElementById('__pipbar');if(pb)pb.remove();" +
            "document.querySelectorAll('video.__pipv,.__pipv').forEach(function(x){x.classList.remove('__pipv');});" +
            "document.querySelectorAll('*').forEach(function(x){try{if(!x.style)return;" +
            "if(x.style.getPropertyPriority('visibility')==='important')x.style.removeProperty('visibility');" +
            "if(x.style.getPropertyPriority('transform')==='important')x.style.removeProperty('transform');" +
            "if(x.style.getPropertyPriority('overflow')==='important')x.style.removeProperty('overflow');" +
            "}catch(z){}});" +
            "try{window.dispatchEvent(new Event('resize'));}catch(z){}" +
            "}catch(e){}})();"

        // PiP sonrası: sadece pip sınıfları/scale; YouTube layout'una dokunma
        const val PIP_RECOVER_JS =
            "(function(){try{" +
            "window.__pipBlock=false;window.__userPause=false;" +
            "if(window.__op){try{HTMLMediaElement.prototype.pause=window.__op;}catch(z){}window.__op=null;}" +
            "var s=document.getElementById('__pip');if(s)s.remove();" +
            "document.querySelectorAll('.ytp-spinner,.ytp-cued-thumbnail-overlay').forEach(function(e){" +
            "try{e.style.removeProperty('display');e.style.removeProperty('visibility');}catch(z){}});" +
            "var p=document.querySelector('.html5-video-player');" +
            "if(p){p.classList.remove('ytp-pause-overlay');}" +
            "var v=document.querySelector('video.html5-main-video')||document.querySelector('video');" +
            "if(v){" +
            "v.classList.remove('__pipv');" +
            "try{v.style.removeProperty('transform');v.style.removeProperty('transform-origin');" +
            "v.style.removeProperty('object-fit');v.style.removeProperty('object-position');}catch(z){}" +
            "try{var t=v.currentTime;v.currentTime=Math.max(0,t-0.001);v.currentTime=t;}catch(z){}" +
            "try{if(!v.paused)v.dispatchEvent(new Event('playing'));}catch(z){}" +
            "}" +
            "try{window.dispatchEvent(new Event('resize'));}catch(z){}" +
            "}catch(e){}})();"

        const val LANDSCAPE_VIDEO_JS =
            "(function(){try{" +
            "var v=document.querySelector('video.html5-main-video')||document.querySelector('.html5-video-player video')||document.querySelector('video');" +
            "if(!v||v.paused)return;" +
            "var fs=document.querySelector('.ytp-fullscreen-button,.ytp-size-button');" +
            "if(fs&&!document.fullscreenElement){try{fs.click();}catch(x){}}" +
            "if(!document.fullscreenElement&&v.requestFullscreen){try{v.requestFullscreen();}catch(x){}}" +
            "if(!document.getElementById('__land')){" +
            "var s=document.createElement('style');s.id='__land';" +
            "s.textContent='.html5-video-player.ytp-fullscreen,video.html5-main-video:fullscreen{width:100vw!important;height:100vh!important}" +
            "body.__landv video{position:fixed!important;inset:0!important;width:100vw!important;height:100vh!important;object-fit:contain!important;z-index:2147483647!important;background:#000!important}';" +
            "document.documentElement.appendChild(s);" +
            "}" +
            "document.body.classList.add('__landv');" +
            "}catch(e){}})();"

        const val PORTRAIT_VIDEO_JS =
            "(function(){try{" +
            "document.body.classList.remove('__landv');" +
            "var s=document.getElementById('__land');if(s)s.remove();" +
            "if(document.fullscreenElement){try{document.exitFullscreen();}catch(x){}}" +
            "}catch(e){}})();"


    }
}
