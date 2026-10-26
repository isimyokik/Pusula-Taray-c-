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
                val id = mediaId ?: return
                if (id.startsWith("http")) {
                    sendBroadcast(Intent(PlaybackService.ACTION_OPEN).setPackage(packageName).putExtra("url", id))
                }
            }
            override fun onPlayFromSearch(query: String?, extras: Bundle?) {
                val q = query?.trim().orEmpty()
                if (q.isNotEmpty()) {
                    sendBroadcast(Intent(PlaybackService.ACTION_SEARCH).setPackage(packageName).putExtra("q", q))
                }
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
                .setState(PlaybackStateCompat.STATE_PAUSED, 0, 1f)
                .build()
        )
        s.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "Rota")
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, "Rota")
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, "Android Auto")
                .build()
        )
        s.isActive = true
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
        return BrowserRoot(ROOT, null)
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        val out = ArrayList<MediaBrowserCompat.MediaItem>()
        val cat = readCatalog()
        // Katalog boşsa telefonda YT Music'i arka planda açtırmayı dene
        if (cat.playlists.isEmpty() && cat.songs.isEmpty() && parentId == ROOT) {
            try {
                val launch = Intent(this, MainActivity::class.java).apply {
                    putExtra("ensure_music", true)
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    )
                }
                startActivity(launch)
            } catch (_: Exception) { }
        }
        when (parentId) {
            ROOT -> {
                out.add(folder(PLAYLISTS, "Listeler (${cat.playlists.size})"))
                out.add(folder(SONGS, "Şarkılar (${cat.songs.size})"))
                if (cat.playlists.isEmpty() && cat.songs.isEmpty()) {
                    out.add(song("https://music.youtube.com/", "YouTube Music'i telefonda aç", "Listeler burada görünür", null))
                    for (e in Prefs(this).history().take(15)) {
                        if (e.url.startsWith("http")) out.add(song(e.url, e.title.ifBlank { e.url }, e.url, null))
                    }
                } else {
                    for (p in cat.playlists.take(15)) {
                        out.add(song("pl:" + p.url, p.title, p.artist.ifBlank { "Liste" }, p.art))
                    }
                }
            }
            PLAYLISTS -> {
                if (cat.playlists.isEmpty()) {
                    out.add(song("https://music.youtube.com/", "Liste yok", "Telefonda YT Music ana sayfa açık olsun", null))
                } else {
                    for (p in cat.playlists) {
                        out.add(song("pl:" + p.url, p.title, p.artist.ifBlank { "Liste" }, p.art))
                    }
                }
            }
            SONGS -> {
                if (cat.songs.isEmpty()) {
                    out.add(song("https://music.youtube.com/", "Şarkı yok", "Telefonda YT Music açık olsun", null))
                } else {
                    for (sItem in cat.songs) {
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
        try { session?.isActive = false; session?.release() } catch (_: Exception) { }
        session = null
        super.onDestroy()
    }

    private data class CatItem(val title: String, val artist: String, val url: String, val art: String)
    private data class Catalog(val playlists: List<CatItem>, val songs: List<CatItem>)

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
            Catalog(pl, sg)
        } catch (_: Exception) {
            Catalog(emptyList(), emptyList())
        }
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
            s.isActive = true
        } catch (_: Exception) { }
    }

    companion object {
        const val ROOT = "root"
        const val PLAYLISTS = "playlists"
        const val SONGS = "songs"

        @Volatile var instance: RotaMediaBrowserService? = null

        fun notifyCatalogChanged() {
            try {
                instance?.let { svc ->
                    svc.notifyChildrenChanged(ROOT)
                    svc.notifyChildrenChanged(PLAYLISTS)
                    svc.notifyChildrenChanged(SONGS)
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
    }
}
