package com.pusula.browser

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.MediaBrowserServiceCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * Android Auto: YouTube Music sayfasından çekilen liste/şarkı/kapakları gösterir.
 * Listeden seçim → MainActivity URL açar ve oynatmayı dener.
 */
class RotaMediaBrowserService : MediaBrowserServiceCompat() {

    private var session: MediaSessionCompat? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        val s = MediaSessionCompat(this, "RotaAuto")
        s.setFlags(
            MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
        )
        s.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() { send(PlaybackService.ACTION_PLAY) }
            override fun onPause() { send(PlaybackService.ACTION_PAUSE) }
            override fun onSkipToNext() { send(PlaybackService.ACTION_NEXT) }
            override fun onSkipToPrevious() { send(PlaybackService.ACTION_PREVIOUS) }
            override fun onSeekTo(pos: Long) {
                sendBroadcast(Intent(PlaybackService.ACTION_SEEK).setPackage(packageName).putExtra("pos", pos))
            }
            override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                val url = resolveUrl(mediaId?.trim().orEmpty()) ?: return
                // Auto'da medya kartını hemen aç (buffering)
                try {
                    session?.setPlaybackState(
                        PlaybackStateCompat.Builder()
                            .setActions(
                                PlaybackStateCompat.ACTION_PLAY or
                                    PlaybackStateCompat.ACTION_PAUSE or
                                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                            )
                            .setState(PlaybackStateCompat.STATE_BUFFERING, 0L, 1f)
                            .build()
                    )
                    if (autoClientConnected) session?.isActive = true
                } catch (_: Exception) { }
                openAndPlay(url)
            }
            override fun onPlayFromSearch(query: String?, extras: Bundle?) {
                val q = query?.trim().orEmpty()
                if (q.isEmpty()) return
                openAndPlay("https://music.youtube.com/search?q=" + Uri.encode(q))
            }
            override fun onCustomAction(action: String?, extras: Bundle?) {
                when (action) {
                    "like" -> send(PlaybackService.ACTION_LIKE)
                    "home" -> send(PlaybackService.ACTION_HOME)
                }
            }
        })
        s.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_SEEK_TO or
                        PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or
                        PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH
                )
                .setState(PlaybackStateCompat.STATE_NONE, 0L, 1f)
                .build()
        )
        s.setSessionActivity(
            PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        // Telefon bildirim oturumu (PlaybackService) ile çakışmasın: Auto bağlanana kadar pasif
        s.isActive = false
        session = s
        sessionToken = s.sessionToken
    }

    private fun send(action: String) {
        sendBroadcast(Intent(action).setPackage(packageName))
    }

    private fun openAndPlay(url: String) {
        if (!url.startsWith("http")) return
        try {
            val launch = Intent(this, MainActivity::class.java).apply {
                action = PlaybackService.ACTION_OPEN
                putExtra("url", url)
                putExtra("autoplay", true)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                )
            }
            startActivity(launch)
        } catch (_: Exception) { }
        sendBroadcast(
            Intent(PlaybackService.ACTION_OPEN).setPackage(packageName)
                .putExtra("url", url)
                .putExtra("autoplay", true)
        )
    }

    private fun resolveUrl(mediaId: String): String? {
        if (mediaId.startsWith("http")) return mediaId
        if (mediaId.startsWith("pl:") || mediaId.startsWith("sg:")) {
            val u = mediaId.substring(3)
            if (u.startsWith("http")) return u
        }
        return null
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot {
        // Sadece araba istemcisinde Auto oturumunu aç — telefon bildirim oturumuna dokunma
        val isCar = clientPackageName.contains("gearhead") ||
            clientPackageName.contains("android.car") ||
            clientPackageName.contains("projection")
        if (isCar) {
            autoClientConnected = true
            try { session?.isActive = true } catch (_: Exception) { }
            try { requestPhoneOpenMusic() } catch (_: Exception) { }
        }
        return BrowserRoot(ROOT, null)
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        val out = ArrayList<MediaBrowserCompat.MediaItem>()
        val cat = readCatalog()
        // Katalog boşsa: bir kez YT Music aç (boş ekran spam'i yok)
        if (cat.playlists.isEmpty() && cat.songs.isEmpty() && parentId == ROOT) {
            requestPhoneOpenMusic()
        }
        when {
            parentId == ROOT -> {
                out.add(folder(PLAYLISTS, "Listeler (${cat.playlists.size})"))
                out.add(folder(SONGS, "Şarkılar (${cat.songs.size})"))
                if (cat.playlists.isEmpty() && cat.songs.isEmpty()) {
                    out.add(song("https://music.youtube.com/", "YouTube Music'i telefonda aç", "Ana sayfa listeleri burada görünür", null))
                }
            }
            parentId == PLAYLISTS -> {
                if (cat.playlists.isEmpty()) {
                    out.add(song("https://music.youtube.com/", "Liste yok", "Telefonda YT Music ana sayfa açık olsun", null))
                } else {
                    for (p in cat.playlists) {
                        out.add(playlistFolder("pl:" + p.url, p.title, p.artist.ifBlank { "Liste" }, p.art))
                    }
                }
            }
            parentId == SONGS -> {
                if (cat.songs.isEmpty()) {
                    out.add(song("https://music.youtube.com/", "Şarkı yok", "Telefonda YT Music açık olsun", null))
                } else {
                    for (sItem in cat.songs) {
                        val id = if (sItem.url.startsWith("http")) "sg:" + sItem.url else "sg:" + sItem.title
                        out.add(song(id, sItem.title, sItem.artist.ifBlank { "Şarkı" }, sItem.art))
                    }
                }
            }
            parentId.startsWith("pl:") -> {
                val plUrl = parentId.removePrefix("pl:")
                val nested = cat.songsForPlaylist(plUrl)
                if (nested.isEmpty()) {
                    try { openAndPlay(plUrl) } catch (_: Exception) { }
                    out.add(song(plUrl, "Liste yükleniyor…", "Biraz sonra geri dönün", null))
                    for (sItem in cat.songs.take(40)) {
                        val id = if (sItem.url.startsWith("http")) "sg:" + sItem.url else "sg:" + sItem.title
                        out.add(song(id, sItem.title, sItem.artist.ifBlank { "Şarkı" }, sItem.art))
                    }
                } else {
                    for (sItem in nested) {
                        val id = if (sItem.url.startsWith("http")) "sg:" + sItem.url else "sg:" + sItem.title
                        out.add(song(id, sItem.title, sItem.artist.ifBlank { "Şarkı" }, sItem.art))
                    }
                }
            }
        }
        result.sendResult(out)
    }

    override fun onSearch(query: String, extras: Bundle?, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        val q = query.trim()
        val out = ArrayList<MediaBrowserCompat.MediaItem>()
        if (q.isNotEmpty()) {
            out.add(song("https://music.youtube.com/search?q=" + Uri.encode(q), "\"$q\" ara", "YouTube Music", null))
            val cat = readCatalog()
            for (p in cat.playlists) {
                if (p.title.contains(q, true) || p.artist.contains(q, true)) {
                    out.add(song("pl:" + p.url, p.title, p.artist, p.art))
                }
            }
            for (sItem in cat.songs) {
                if (sItem.title.contains(q, true) || sItem.artist.contains(q, true)) {
                    val id = if (sItem.url.startsWith("http")) "sg:" + sItem.url else "sg:" + sItem.title
                    out.add(song(id, sItem.title, sItem.artist, sItem.art))
                }
            }
        }
        result.sendResult(out)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        autoClientConnected = false
        try { session?.isActive = false; session?.release() } catch (_: Exception) { }
        session = null
        super.onDestroy()
    }

    private data class CatItem(val title: String, val artist: String, val url: String, val art: String)
    private data class Catalog(
        val playlists: List<CatItem>,
        val songs: List<CatItem>,
        val byPlaylist: Map<String, List<CatItem>> = emptyMap()
    ) {
        fun songsForPlaylist(plUrl: String): List<CatItem> {
            if (plUrl.isBlank()) return emptyList()
            byPlaylist[plUrl]?.takeIf { it.isNotEmpty() }?.let { return it }
            for ((k, v) in byPlaylist) {
                if (k.contains(plUrl.takeLast(20)) || plUrl.contains(k.takeLast(20))) return v
            }
            return emptyList()
        }
    }

    private fun readCatalog(): Catalog {
        val raw = try { Prefs(this).autoCatalog } catch (_: Exception) { "" }
        if (raw.isBlank()) return Catalog(emptyList(), emptyList())
        return try {
            val o = JSONObject(raw)
            val pl = ArrayList<CatItem>()
            val sg = ArrayList<CatItem>()
            val pla = o.optJSONArray("playlists") ?: JSONArray()
            for (i in 0 until pla.length()) {
                val it = pla.optJSONObject(i) ?: continue
                val url = it.optString("url")
                val title = it.optString("title")
                if (url.startsWith("http") && title.isNotBlank()) {
                    pl.add(CatItem(title, it.optString("artist"), url, it.optString("art")))
                }
            }
            val sga = o.optJSONArray("songs") ?: JSONArray()
            for (i in 0 until sga.length()) {
                val it = sga.optJSONObject(i) ?: continue
                val title = it.optString("title")
                if (title.isNotBlank()) {
                    sg.add(CatItem(title, it.optString("artist"), it.optString("url"), it.optString("art")))
                }
            }
            val by = HashMap<String, List<CatItem>>()
            val bp = o.optJSONObject("byPlaylist")
            if (bp != null) {
                val keys = bp.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val arr = bp.optJSONArray(key) ?: continue
                    val list = ArrayList<CatItem>()
                    for (i in 0 until arr.length()) {
                        val it = arr.optJSONObject(i) ?: continue
                        val title = it.optString("title")
                        if (title.isNotBlank()) {
                            list.add(CatItem(title, it.optString("artist"), it.optString("url"), it.optString("art")))
                        }
                    }
                    if (list.isNotEmpty()) by[key] = list
                }
            }
            Catalog(pl, sg, by)
        } catch (_: Exception) {
            Catalog(emptyList(), emptyList())
        }
    }

    private fun playlistFolder(id: String, title: String, subtitle: String, art: String?): MediaBrowserCompat.MediaItem {
        val b = MediaDescriptionCompat.Builder()
            .setMediaId(id)
            .setTitle(title.take(80))
            .setSubtitle(subtitle.take(80))
        if (!art.isNullOrBlank() && art.startsWith("http")) {
            try { b.setIconUri(Uri.parse(art)) } catch (_: Exception) { }
        }
        return MediaBrowserCompat.MediaItem(b.build(), MediaBrowserCompat.MediaItem.FLAG_BROWSABLE)
    }

    private fun folder(id: String, title: String): MediaBrowserCompat.MediaItem {
        val d = MediaDescriptionCompat.Builder().setMediaId(id).setTitle(title).build()
        return MediaBrowserCompat.MediaItem(d, MediaBrowserCompat.MediaItem.FLAG_BROWSABLE)
    }

    private fun song(id: String, title: String, subtitle: String, art: String?): MediaBrowserCompat.MediaItem {
        val b = MediaDescriptionCompat.Builder()
            .setMediaId(id)
            .setTitle(title.take(80))
            .setSubtitle(subtitle.take(80))
        if (!art.isNullOrBlank() && art.startsWith("http")) {
            try { b.setIconUri(Uri.parse(art)) } catch (_: Exception) { }
        }
        val playId = when {
            id.startsWith("http") -> id
            id.startsWith("pl:") || id.startsWith("sg:") -> id.substring(3)
            else -> id
        }
        if (playId.startsWith("http")) {
            try { b.setMediaUri(Uri.parse(playId)) } catch (_: Exception) { }
        }
        return MediaBrowserCompat.MediaItem(b.build(), MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
    }

    fun publishNowPlaying(
        title: String,
        artist: String,
        artUrl: String,
        playing: Boolean,
        positionMs: Long,
        durationMs: Long
    ) {
        try {
            val s = session ?: return
            val mb = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title.ifBlank { "Rota" })
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist.ifBlank { "YouTube Music" })
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title.ifBlank { "Rota" })
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, artist.ifBlank { "YouTube Music" })
            if (durationMs > 0) mb.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs)
            if (artUrl.startsWith("http")) {
                try {
                    mb.putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, artUrl)
                    mb.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, artUrl)
                    mb.putString(MediaMetadataCompat.METADATA_KEY_ART_URI, artUrl)
                } catch (_: Exception) { }
                val urlCopy = artUrl
                Thread {
                    try {
                        val conn = java.net.URL(urlCopy).openConnection() as java.net.HttpURLConnection
                        conn.connectTimeout = 4000
                        conn.readTimeout = 4000
                        conn.instanceFollowRedirects = true
                        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                        conn.connect()
                        val bmp = android.graphics.BitmapFactory.decodeStream(conn.inputStream)
                        conn.disconnect()
                        if (bmp != null) {
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                try { applyArtBitmap(bmp) } catch (_: Exception) { }
                            }
                        }
                    } catch (_: Exception) { }
                }.start()
            }
            s.setMetadata(mb.build())
            var actions =
                PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or
                    PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH
            if (durationMs > 0) actions = actions or PlaybackStateCompat.ACTION_SEEK_TO
            val st = if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
            s.setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setActions(actions)
                    .setState(st, positionMs.coerceAtLeast(0L), if (playing) 1f else 0f)
                    .build()
            )
            if (autoClientConnected) s.isActive = true
        } catch (_: Exception) { }
    }

    private fun applyArtBitmap(bmp: android.graphics.Bitmap) {
        val s = session ?: return
        try {
            val cur = s.controller.metadata
            val mb = MediaMetadataCompat.Builder()
            if (cur != null) {
                try {
                    val t = cur.getString(MediaMetadataCompat.METADATA_KEY_TITLE)
                    val a = cur.getString(MediaMetadataCompat.METADATA_KEY_ARTIST)
                    if (!t.isNullOrBlank()) {
                        mb.putString(MediaMetadataCompat.METADATA_KEY_TITLE, t)
                        mb.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, t)
                    }
                    if (!a.isNullOrBlank()) {
                        mb.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, a)
                        mb.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, a)
                    }
                    val d = cur.getLong(MediaMetadataCompat.METADATA_KEY_DURATION)
                    if (d > 0) mb.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, d)
                } catch (_: Exception) { }
            }
            mb.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bmp)
            mb.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, bmp)
            s.setMetadata(mb.build())
        } catch (_: Exception) { }
    }

    companion object {
        const val ROOT = "root"
        const val PLAYLISTS = "playlists"
        const val SONGS = "songs"

        @Volatile var instance: RotaMediaBrowserService? = null
        @Volatile var autoClientConnected: Boolean = false
        @Volatile private var lastMusicOpenMs = 0L

        /** Arabadan Rota seçilince telefonda YT Music (telefon launcher davranışına dokunmaz). */
        fun requestPhoneOpenMusic() {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastMusicOpenMs < 8000L) return
            lastMusicOpenMs = now
            val ctx = instance ?: return
            try {
                val launch = Intent(ctx, MainActivity::class.java).apply {
                    putExtra("auto_open_music", true)
                    putExtra("ensure_music", true)
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    )
                }
                ctx.startActivity(launch)
            } catch (_: Exception) { }
        }

        fun notifyCatalogChanged() {
            try {
                instance?.let { svc ->
                    svc.notifyChildrenChanged(ROOT)
                    svc.notifyChildrenChanged(PLAYLISTS)
                    svc.notifyChildrenChanged(SONGS)
                    try {
                        val cat = svc.readCatalog()
                        for (p in cat.playlists) {
                            svc.notifyChildrenChanged("pl:" + p.url)
                        }
                        for (k in cat.byPlaylist.keys) {
                            svc.notifyChildrenChanged("pl:" + k)
                        }
                    } catch (_: Exception) { }
                }
            } catch (_: Exception) { }
        }

        fun updateNowPlaying(
            title: String,
            artist: String,
            artUrl: String,
            playing: Boolean,
            positionMs: Long,
            durationMs: Long
        ) {
            try {
                instance?.publishNowPlaying(title, artist, artUrl, playing, positionMs, durationMs)
            } catch (_: Exception) { }
        }

        fun updateNowPlayingArt(bmp: android.graphics.Bitmap?) {
            if (bmp == null) return
            try {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    try { instance?.applyArtBitmap(bmp) } catch (_: Exception) { }
                }
            } catch (_: Exception) { }
        }
    }
}
