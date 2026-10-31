package com.pusula.browser

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * PDF, Word, Excel, PowerPoint, RTF, metin ve görsel dosyaları uygulama içinde açar.
 * Yerel dosyalar (İndirilenler) internetsiz açılır; uzak adresler Google görüntüleyiciyle.
 */
class DocumentViewerActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var progress: ProgressBar
    private var fileUrl: String = ""
    private var fileName: String = ""
    private var mime: String? = null
    private var filePath: String? = null
    private var pdf: PdfRenderer? = null
    private var pfd: ParcelFileDescriptor? = null
    private val pdfLock = Any()
    private var bgHex = "#0B1020"
    private var fgHex = "#EAF0FF"
    private var subHex = "#4A5578"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fileUrl = intent.getStringExtra(EXTRA_URL) ?: ""
        filePath = intent.getStringExtra(EXTRA_PATH)
        if (fileUrl.isEmpty() && filePath == null) { finish(); return }
        fileName = intent.getStringExtra(EXTRA_NAME)
            ?: (filePath?.let { File(it).name } ?: guessName(fileUrl))
        mime = intent.getStringExtra(EXTRA_MIME)

        val prefs = Prefs(this)
        val pal = Themes.get(prefs.theme)
        val cBg = pal.bg
        val cSurface = pal.surface
        val cText = pal.text
        val cIcon = pal.icon
        val cAccent = pal.accent
        bgHex = String.format("#%06X", 0xFFFFFF and pal.bg)
        fgHex = String.format("#%06X", 0xFFFFFF and pal.text)
        subHex = String.format("#%06X", 0xFFFFFF and pal.field)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setBackgroundDrawable(ColorDrawable(cBg))
        val bars = WindowInsetsControllerCompat(window, window.decorView)
        bars.isAppearanceLightStatusBars = !pal.dark
        bars.isAppearanceLightNavigationBars = !pal.dark

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cBg)
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(cSurface)
            elevation = dp(6).toFloat()
        }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(top) { v, insets ->
            val sb = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars())
            v.setPadding(dp(6), sb.top + dp(8), dp(6), dp(10))
            insets
        }

        val back = ImageButton(this).apply {
            setImageResource(R.drawable.ic_back)
            setColorFilter(cIcon)
            background = null
            contentDescription = "Geri"
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { finish() }
        }
        top.addView(back, LinearLayout.LayoutParams(dp(52), dp(52)))

        val titleView = TextView(this).apply {
            text = fileName
            textSize = 16f
            setTextColor(cText)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
            leftMargin = dp(4); rightMargin = dp(8)
        })

        // Yerel HTML dosyası: ana ekrana kısayol ekle (zaten kısayoldan açıldıysa gösterme)
        val localPath = filePath
        val isHtmlLocal = localPath != null && localPath.lowercase().let { it.endsWith(".html") || it.endsWith(".htm") } &&
            !localPath.contains("/pinned_html/")
        if (isHtmlLocal) {
            val pinBtn = TextView(this).apply {
                text = "🏠 Ana ekran"
                textSize = 14f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(8), dp(14), dp(8))
                val bg = GradientDrawable()
                bg.setColor(cAccent)
                bg.cornerRadius = dp(22).toFloat()
                background = bg
                contentDescription = "Ana ekrana ekle"
                setOnClickListener { pinHtmlShortcut() }
            }
            top.addView(pinBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply {
                rightMargin = dp(6)
            })
        }

        // Yerel dosya zaten indirilmiş; indir düğmesi yalnızca uzak adreste görünür.
        if (filePath == null) {
            val downloadBtn = TextView(this).apply {
                text = "⬇️ İndir"
                textSize = 15f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(10), dp(16), dp(10))
                val bg = GradientDrawable()
                bg.setColor(cAccent)
                bg.cornerRadius = dp(22).toFloat()
                background = bg
                setOnClickListener { downloadFile(prefs) }
            }
            top.addView(downloadBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply {
                rightMargin = dp(6)
            })
        }
        root.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; this.progress = 0; visibility = View.VISIBLE
        }
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))

        val progressBar = progress
        web = WebView(this).apply {
            setBackgroundColor(cBg)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                builtInZoomControls = true
                displayZoomControls = false
                setSupportZoom(true)
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                defaultTextEncodingName = "utf-8"
                allowFileAccess = true
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val u = request?.url ?: return null
                    if (u.host == "pdf.local") return renderPdfPage(u)
                    return null
                }
                override fun onPageFinished(view: WebView?, url: String?) { progressBar.visibility = View.GONE }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    progressBar.progress = newProgress
                    progressBar.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
                }
            }
        }
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })
        loadDocument()
    }

    private fun loadDocument() {
        val path = filePath
        if (path != null) {
            val f = File(path)
            if (f.exists()) { openLocal(f); return }
            if (fileUrl.startsWith("http")) { openRemote(); return }
            toast("Dosya bulunamadı")
            finish()
            return
        }
        openRemote()
    }

    private fun openRemote() {
        val lowerMime = (mime ?: "").lowercase()
        val lowerName = fileName.lowercase()
        val isText = lowerMime.startsWith("text/") ||
            lowerName.endsWith(".txt") || lowerName.endsWith(".csv") ||
            lowerName.endsWith(".md") || lowerName.endsWith(".json") ||
            lowerName.endsWith(".log") || lowerName.endsWith(".xml")
        if (isText) web.loadUrl(fileUrl)
        else web.loadUrl("https://docs.google.com/gview?embedded=true&url=" + Uri.encode(fileUrl))
    }

    private fun showMessage(text: String) {
        progress.visibility = View.GONE
        web.loadDataWithBaseURL(null,
            DocConverter.wrap("<p>" + DocConverter.esc(text) + "</p>", bgHex, fgHex, subHex),
            "text/html", "utf-8", null)
    }

    private fun openLocal(f: File) {
        val n = f.name.lowercase()
        val m = (mime ?: "").lowercase()
        val imageExt = listOf(".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".svg")
        val textExt = listOf(".txt", ".csv", ".md", ".json", ".log", ".xml", ".ini", ".yml", ".yaml")
        when {
            n.endsWith(".pdf") || m.contains("pdf") -> openPdf(f)
            imageExt.any { n.endsWith(it) } || m.startsWith("image/") -> web.loadUrl(Uri.fromFile(f).toString())
            n.endsWith(".html") || n.endsWith(".htm") -> web.loadUrl(Uri.fromFile(f).toString())
            n.endsWith(".docx") || n.endsWith(".xlsx") || n.endsWith(".pptx") || n.endsWith(".rtf") -> convertAsync(f)
            textExt.any { n.endsWith(it) } || m.startsWith("text/") -> showText(f)
            else -> {
                if (fileUrl.startsWith("http")) openRemote()
                else showMessage("Bu biçim (" + f.extension + ") internet olmadan uygulama içinde gösterilemiyor. " +
                    "Eski .doc / .xls / .ppt dosyaları yerine .docx / .xlsx / .pptx kullanılabilir.")
            }
        }
    }

    private fun showText(f: File) {
        Thread {
            val html = try {
                val buf = ByteArray(5 * 1024 * 1024)
                var len = 0
                f.inputStream().use { ins ->
                    while (len < buf.size) {
                        val r = ins.read(buf, len, buf.size - len)
                        if (r < 0) break
                        len += r
                    }
                }
                DocConverter.wrap("<pre>" + DocConverter.esc(String(buf, 0, len, Charsets.UTF_8)) + "</pre>", bgHex, fgHex, subHex)
            } catch (_: Throwable) { null }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (html == null) showMessage("Dosya okunamadı")
                else web.loadDataWithBaseURL("https://doc.local/", html, "text/html", "utf-8", null)
            }
        }.start()
    }

    private fun convertAsync(f: File) {
        Thread {
            val html = try { DocConverter.convert(f, bgHex, fgHex, subHex) } catch (_: Throwable) { null }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (html == null) showMessage("Dosya açılamadı (bozuk veya desteklenmeyen olabilir)")
                else web.loadDataWithBaseURL("https://doc.local/", html, "text/html", "utf-8", null)
            }
        }.start()
    }

    private fun openPdf(f: File) {
        try {
            val p = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
            pfd = p
            val r = PdfRenderer(p)
            pdf = r
            val sb = StringBuilder()
            sb.append("<!doctype html><html><head><meta charset=\"utf-8\">")
            sb.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,maximum-scale=6\">")
            sb.append("<style>body{margin:0;padding:6px;background:#555}")
            sb.append("img{display:block;width:100%;height:auto;margin:0 0 8px;background:#fff}</style></head><body>")
            synchronized(pdfLock) {
                for (i in 0 until r.pageCount) {
                    val page = r.openPage(i)
                    val w = page.width
                    val h = page.height
                    page.close()
                    sb.append("<img src=\"https://pdf.local/p/").append(i).append(".jpg\" width=\"")
                        .append(w).append("\" height=\"").append(h).append("\" loading=\"lazy\">")
                }
            }
            sb.append("</body></html>")
            web.loadDataWithBaseURL("https://pdf.local/", sb.toString(), "text/html", "utf-8", null)
        } catch (_: Throwable) {
            showMessage("PDF açılamadı (şifreli veya bozuk olabilir)")
        }
    }

    private fun renderPdfPage(u: Uri): WebResourceResponse? {
        val idx = u.lastPathSegment?.substringBefore('.')?.toIntOrNull() ?: return null
        synchronized(pdfLock) {
            val r = pdf ?: return null
            if (idx < 0 || idx >= r.pageCount) return null
            val page = r.openPage(idx)
            try {
                val scale = (1600f / page.width).coerceIn(1f, 3f)
                val w = (page.width * scale).toInt()
                val h = (page.height * scale).toInt()
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                val out = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 88, out)
                bmp.recycle()
                return WebResourceResponse("image/jpeg", null, ByteArrayInputStream(out.toByteArray()))
            } catch (_: Throwable) {
                return null
            } finally {
                page.close()
            }
        }
    }

    /**
     * HTML dosyasını kalıcı klasöre kopyalar (önbellek temizlenebilir) ve ana ekrana, dosyayı bu görüntüleyiciyle
     * doğrudan açan bir kısayol ekler.
     */
    @SuppressLint("NewApi")
    private fun pinHtmlShortcut() {
        val src = filePath?.let { File(it) }
        if (src == null || !src.exists()) { toast("Dosya bulunamadı"); return }
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) { toast("Bu Android sürümünde desteklenmiyor"); return }
            val sm = getSystemService(ShortcutManager::class.java)
            if (sm == null || !sm.isRequestPinShortcutSupported) { toast("Bu cihazda kısayol desteklenmiyor"); return }
            val dir = File(filesDir, "pinned_html").apply { mkdirs() }
            val safe = src.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val dst = File(dir, Integer.toHexString(fileName.hashCode()) + "_" + safe)
            if (src.absolutePath != dst.absolutePath) src.copyTo(dst, overwrite = true)
            val title = fileName.substringBeforeLast('.').trim().ifBlank { "HTML" }.take(40)
            val launch = Intent(this, DocumentViewerActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra(EXTRA_PATH, dst.absolutePath)
                putExtra(EXTRA_NAME, fileName)
                putExtra(EXTRA_MIME, "text/html")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val info = ShortcutInfo.Builder(this, "html_" + Integer.toHexString(dst.absolutePath.hashCode()))
                .setShortLabel(title.take(25))
                .setLongLabel(title)
                .setIcon(htmlShortcutIcon(title))
                .setIntent(launch)
                .build()
            sm.requestPinShortcut(info, null)
            toast("Ana ekran isteği gönderildi")
        } catch (e: Exception) {
            toast("Ana ekrana eklenemedi")
        }
    }

    /** Dosya adının ilk harfiyle mavi zemin üzerine basit bir simge çizer. */
    @SuppressLint("NewApi")
    private fun htmlShortcutIcon(title: String): android.graphics.drawable.Icon {
        val size = 432
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        c.drawColor(Color.parseColor("#2F6BFF"))
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.color = Color.WHITE
        p.textSize = size * 0.42f
        p.typeface = android.graphics.Typeface.DEFAULT_BOLD
        p.textAlign = android.graphics.Paint.Align.CENTER
        val ch = title.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "H"
        c.drawText(ch, size / 2f, size / 2f - (p.descent() + p.ascent()) / 2f, p)
        return android.graphics.drawable.Icon.createWithAdaptiveBitmap(bmp)
    }

    private fun downloadFile(prefs: Prefs) {
        try {
            val req = android.app.DownloadManager.Request(Uri.parse(fileUrl))
            req.setTitle(fileName)
            req.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_HIDDEN)
            if (mime != null) req.setMimeType(mime)
            val cookie = CookieManager.getInstance().getCookie(fileUrl)
            if (cookie != null) req.addRequestHeader("Cookie", cookie)
            val destPath: String
            if (Build.VERSION.SDK_INT >= 29) {
                req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                destPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath + "/" + fileName
            } else {
                req.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, fileName)
                destPath = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.absolutePath + "/" + fileName
            }
            val dlId = (getSystemService(DOWNLOAD_SERVICE) as android.app.DownloadManager).enqueue(req)
            DownloadNotifier.track(this, dlId, fileName)
            prefs.addDownload(fileName, fileUrl, destPath, mime ?: "")
            toast("İndiriliyor: $fileName")
        } catch (e: Exception) {
            toast("İndirilemedi")
        }
    }

    private fun toast(s: String) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }

    private fun guessName(url: String): String {
        return try {
            val path = Uri.parse(url).lastPathSegment ?: "dosya"
            if (path.contains(".")) path else "dosya"
        } catch (_: Exception) { "dosya" }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    override fun onDestroy() {
        try { web.destroy() } catch (_: Exception) {}
        synchronized(pdfLock) {
            try { pdf?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
            pdf = null
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_NAME = "name"
        const val EXTRA_MIME = "mime"
        const val EXTRA_PATH = "path"
    }
}
