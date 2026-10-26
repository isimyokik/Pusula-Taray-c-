package com.pusula.browser

import android.util.Base64
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/** DOCX / XLSX / PPTX / RTF dosyalarını internetsiz, ek kütüphanesiz HTML'e çevirir. */
object DocConverter {

    fun esc(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    fun wrap(body: String, bg: String, fg: String, sub: String): String =
        "<!doctype html><html><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,maximum-scale=5\">" +
            "<style>body{margin:0;padding:16px;background:" + bg + ";color:" + fg +
            ";font:16px/1.55 sans-serif;word-wrap:break-word}" +
            "table{border-collapse:collapse;margin:10px 0}" +
            "td{border:1px solid " + sub + ";padding:4px 8px;vertical-align:top;font-size:14px}" +
            "img{max-width:100%;height:auto}pre{white-space:pre-wrap;font:14px/1.5 monospace}" +
            ".sc{overflow-x:auto}h1,h2,h3{margin:18px 0 6px}p{margin:0 0 8px}</style></head><body>" +
            body + "</body></html>"

    /** Desteklenmeyen uzantıda null döner. */
    fun convert(f: File, bg: String, fg: String, sub: String): String? {
        val n = f.name.lowercase()
        val body = when {
            n.endsWith(".docx") -> docx(f)
            n.endsWith(".xlsx") -> xlsx(f)
            n.endsWith(".pptx") -> pptx(f)
            n.endsWith(".rtf") -> rtf(f)
            else -> return null
        }
        return wrap(body, bg, fg, sub)
    }

    private fun newParser(ins: InputStream): XmlPullParser {
        val p = Xml.newPullParser()
        p.setInput(ins, "UTF-8")
        return p
    }

    private fun rels(z: ZipFile, path: String): Map<String, String> {
        val m = HashMap<String, String>()
        val e = z.getEntry(path) ?: return m
        z.getInputStream(e).use { ins ->
            val p = newParser(ins)
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && p.name == "Relationship") {
                    val id = p.getAttributeValue(null, "Id")
                    val t = p.getAttributeValue(null, "Target")
                    if (id != null && t != null) m[id] = t
                }
                ev = p.next()
            }
        }
        return m
    }

    private fun mimeOf(name: String): String {
        val n = name.lowercase()
        return when {
            n.endsWith(".png") -> "image/png"
            n.endsWith(".gif") -> "image/gif"
            n.endsWith(".webp") -> "image/webp"
            n.endsWith(".bmp") -> "image/bmp"
            else -> "image/jpeg"
        }
    }

    // ---------------------------------------------------------------- DOCX
    private fun docx(f: File): String {
        return ZipFile(f).use { z ->
            val en = z.getEntry("word/document.xml") ?: return@use "<p>Belge boş veya okunamadı.</p>"
            val rel = rels(z, "word/_rels/document.xml.rels")
            val sb = StringBuilder()
            z.getInputStream(en).use { ins ->
                val p = newParser(ins)
                val para = StringBuilder()
                var heading = 0
                var bold = false
                var ital = false
                var ev = p.eventType
                while (ev != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG) {
                        when (p.name) {
                            "w:p" -> { para.setLength(0); heading = 0 }
                            "w:pStyle" -> {
                                val v = p.getAttributeValue(null, "w:val") ?: ""
                                if (v.startsWith("Heading", true) || v.startsWith("Balk", true)) {
                                    heading = v.filter { it.isDigit() }.toIntOrNull() ?: 1
                                } else if (v.equals("Title", true)) heading = 1
                            }
                            "w:r" -> { bold = false; ital = false }
                            "w:b" -> {
                                val v = p.getAttributeValue(null, "w:val")
                                bold = v != "0" && v != "false"
                            }
                            "w:i" -> {
                                val v = p.getAttributeValue(null, "w:val")
                                ital = v != "0" && v != "false"
                            }
                            "w:t" -> {
                                var t = esc(p.nextText())
                                if (bold) t = "<b>$t</b>"
                                if (ital) t = "<i>$t</i>"
                                para.append(t)
                            }
                            "w:tab" -> para.append("&emsp;")
                            "w:br", "w:cr" -> para.append("<br>")
                            "a:blip" -> {
                                val id = p.getAttributeValue(null, "r:embed")
                                val target = if (id != null) rel[id] else null
                                if (target != null) {
                                    val path = if (target.startsWith("/")) target.substring(1) else "word/$target"
                                    val ie = z.getEntry(path)
                                    if (ie != null && ie.size in 1..3_000_000) {
                                        try {
                                            val bytes = z.getInputStream(ie).use { it.readBytes() }
                                            para.append("<img src=\"data:" + mimeOf(path) + ";base64," +
                                                Base64.encodeToString(bytes, Base64.NO_WRAP) + "\">")
                                        } catch (_: Exception) { }
                                    }
                                }
                            }
                            "w:tbl" -> sb.append("<table>")
                            "w:tr" -> sb.append("<tr>")
                            "w:tc" -> sb.append("<td>")
                        }
                    } else if (ev == XmlPullParser.END_TAG) {
                        when (p.name) {
                            "w:p" -> {
                                val h = heading.coerceIn(0, 6)
                                val txt = para.toString()
                                if (h > 0) sb.append("<h$h>").append(txt).append("</h$h>")
                                else sb.append("<p>").append(if (txt.isEmpty()) "&nbsp;" else txt).append("</p>")
                                para.setLength(0)
                            }
                            "w:tbl" -> sb.append("</table>")
                            "w:tr" -> sb.append("</tr>")
                            "w:tc" -> sb.append("</td>")
                        }
                    }
                    ev = p.next()
                }
            }
            sb.toString()
        }
    }

    // ---------------------------------------------------------------- XLSX
    private fun colIdx(ref: String): Int {
        var n = 0
        for (ch in ref) {
            if (ch.isLetter()) n = n * 26 + (ch.uppercaseChar() - 'A' + 1) else break
        }
        return (n - 1).coerceAtLeast(0)
    }

    private fun xlsx(f: File): String {
        return ZipFile(f).use { z ->
            val shared = ArrayList<String>()
            val sst = z.getEntry("xl/sharedStrings.xml")
            if (sst != null) {
                z.getInputStream(sst).use { ins ->
                    val p = newParser(ins)
                    val sb = StringBuilder()
                    var ev = p.eventType
                    while (ev != XmlPullParser.END_DOCUMENT) {
                        if (ev == XmlPullParser.START_TAG) {
                            if (p.name == "si") sb.setLength(0)
                            else if (p.name == "t") sb.append(p.nextText())
                        } else if (ev == XmlPullParser.END_TAG && p.name == "si") {
                            shared.add(sb.toString())
                        }
                        ev = p.next()
                    }
                }
            }
            val names = ArrayList<String>()
            val rids = ArrayList<String>()
            val wb = z.getEntry("xl/workbook.xml")
            if (wb != null) {
                z.getInputStream(wb).use { ins ->
                    val p = newParser(ins)
                    var ev = p.eventType
                    while (ev != XmlPullParser.END_DOCUMENT) {
                        if (ev == XmlPullParser.START_TAG && p.name == "sheet") {
                            names.add(p.getAttributeValue(null, "name") ?: "Sayfa")
                            rids.add(p.getAttributeValue(null, "r:id") ?: "")
                        }
                        ev = p.next()
                    }
                }
            }
            val rel = rels(z, "xl/_rels/workbook.xml.rels")
            val out = StringBuilder()
            for (i in names.indices) {
                val t = rel[rids[i]] ?: continue
                val path = if (t.startsWith("/")) t.substring(1) else "xl/$t"
                val en = z.getEntry(path) ?: continue
                out.append("<h2>").append(esc(names[i])).append("</h2><div class=\"sc\"><table>")
                z.getInputStream(en).use { ins -> sheetRows(ins, shared, out) }
                out.append("</table></div>")
            }
            if (out.isEmpty()) "<p>Tablo boş veya okunamadı.</p>" else out.toString()
        }
    }

    private fun sheetRows(ins: InputStream, shared: List<String>, out: StringBuilder) {
        val p = newParser(ins)
        var ev = p.eventType
        var rows = 0
        var cells = ArrayList<String>()
        var col = 0
        var type = ""
        var v = ""
        var inCell = false
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (p.name) {
                    "row" -> { cells = ArrayList() }
                    "c" -> {
                        val r = p.getAttributeValue(null, "r") ?: ""
                        col = if (r.isEmpty()) cells.size else colIdx(r)
                        type = p.getAttributeValue(null, "t") ?: ""
                        v = ""
                        inCell = true
                    }
                    "v" -> { v = p.nextText() }
                    "t" -> { if (inCell && type == "inlineStr") v += p.nextText() }
                }
            } else if (ev == XmlPullParser.END_TAG) {
                when (p.name) {
                    "c" -> {
                        inCell = false
                        if (col < 60) {
                            while (cells.size <= col) cells.add("")
                            cells[col] = when (type) {
                                "s" -> shared.getOrElse(v.toIntOrNull() ?: -1) { "" }
                                "b" -> if (v == "1") "TRUE" else "FALSE"
                                else -> v
                            }
                        }
                    }
                    "row" -> {
                        if (rows < 3000) {
                            out.append("<tr>")
                            for (c in cells) out.append("<td>").append(esc(c)).append("</td>")
                            out.append("</tr>")
                        }
                        rows++
                    }
                }
            }
            ev = p.next()
        }
    }

    // ---------------------------------------------------------------- PPTX
    private fun pptx(f: File): String {
        return ZipFile(f).use { z ->
            val re = Regex("ppt/slides/slide\\d+\\.xml")
            val slides = z.entries().toList().map { it.name }.filter { re.matches(it) }
                .sortedBy { it.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }
            val out = StringBuilder()
            for ((i, name) in slides.withIndex()) {
                out.append("<h2>Slayt ").append(i + 1).append("</h2>")
                val en = z.getEntry(name) ?: continue
                z.getInputStream(en).use { ins ->
                    val p = newParser(ins)
                    val para = StringBuilder()
                    var ev = p.eventType
                    while (ev != XmlPullParser.END_DOCUMENT) {
                        if (ev == XmlPullParser.START_TAG) {
                            when (p.name) {
                                "a:p" -> para.setLength(0)
                                "a:t" -> para.append(esc(p.nextText()))
                                "a:br" -> para.append("<br>")
                            }
                        } else if (ev == XmlPullParser.END_TAG && p.name == "a:p") {
                            if (para.isNotBlank()) out.append("<p>").append(para).append("</p>")
                            para.setLength(0)
                        }
                        ev = p.next()
                    }
                }
            }
            if (slides.isEmpty()) "<p>Sunum boş veya okunamadı.</p>" else out.toString()
        }
    }

    // ---------------------------------------------------------------- RTF
    private fun rtf(f: File): String {
        val s = f.readText(Charsets.ISO_8859_1)
        val out = StringBuilder()
        var i = 0
        var depth = 0
        var skipDepth = -1
        val dests = listOf("{\\*", "{\\fonttbl", "{\\colortbl", "{\\stylesheet", "{\\info", "{\\pict")
        while (i < s.length) {
            val c = s[i]
            if (c == '{') {
                depth++
                if (skipDepth < 0 && dests.any { s.startsWith(it, i) }) skipDepth = depth
                i++
            } else if (c == '}') {
                if (depth == skipDepth) skipDepth = -1
                depth--
                i++
            } else if (c == '\\') {
                i++
                if (i >= s.length) break
                val n = s[i]
                if (n == '\\' || n == '{' || n == '}') {
                    if (skipDepth < 0) out.append(n)
                    i++
                } else if (n == '\'') {
                    val hex = s.substring(i + 1, minOf(i + 3, s.length))
                    val b = hex.toIntOrNull(16)
                    if (b != null && skipDepth < 0) {
                        out.append(String(byteArrayOf(b.toByte()), charset("windows-1254")))
                    }
                    i += 3
                } else if (n.isLetter()) {
                    var j = i
                    while (j < s.length && s[j].isLetter()) j++
                    val word = s.substring(i, j)
                    var k = j
                    if (k < s.length && (s[k] == '-' || s[k].isDigit())) {
                        k++
                        while (k < s.length && s[k].isDigit()) k++
                    }
                    val param = s.substring(j, k).toIntOrNull()
                    if (k < s.length && s[k] == ' ') k++
                    if (skipDepth < 0) {
                        when (word) {
                            "par", "line" -> out.append('\n')
                            "tab" -> out.append('\t')
                            "u" -> {
                                if (param != null) out.append((if (param < 0) param + 65536 else param).toChar())
                                if (k < s.length) k++
                            }
                        }
                    }
                    i = k
                } else {
                    i++
                }
            } else if (c == '\r' || c == '\n') {
                i++
            } else {
                if (skipDepth < 0) out.append(c)
                i++
            }
        }
        return "<pre>" + esc(out.toString()) + "</pre>"
    }
}
