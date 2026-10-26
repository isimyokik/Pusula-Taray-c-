package com.pusula.browser

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.MediaBrowserServiceCompat

/**
 * Android Auto medya servisi — kendi MediaSessionCompat'i ile listelenir.
 * Çalma komutları telefona (MainActivity / WebView) yayınlanır.
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

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot {
        // Android Auto (gearhead) + sistem medya tarayıcıları — asla null dönme
        val extras = Bundle()
        try {
            extras.putBoolean("android.media.browse.CONTENT_STYLE_SUPPORTED", true)
            extras.putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", 1)
            extras.putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", 2)
        } catch (_: Exception) { }
        return BrowserRoot(ROOT, extras)
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        val prefs = Prefs(this)
        val out = ArrayList<MediaBrowserCompat.MediaItem>()
        when (parentId) {
            ROOT -> {
                out.add(folder(HOME, "Ana sayfa"))
                out.add(folder(FAV, "Favoriler"))
                out.add(folder(RECENT, "Son dinlenenler"))
            }
            HOME, RECENT -> {
                for (e in prefs.history().take(40)) {
                    if (e.url.startsWith("http")) out.add(song(e.url, e.title.ifBlank { e.url }, e.url))
                }
                if (out.isEmpty()) {
                    out.add(song("https://music.youtube.com/", "YouTube Music", "Aç"))
                }
            }
            FAV -> {
                for (e in prefs.bookmarks().take(40)) {
                    if (e.url.startsWith("http")) out.add(song(e.url, e.title.ifBlank { e.url }, e.url))
                }
                if (out.isEmpty()) {
                    out.add(song("https://music.youtube.com/", "Favori yok · Music aç", "YouTube Music"))
                }
            }
        }
        result.sendResult(out)
    }

    override fun onSearch(query: String, extras: Bundle?, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        val q = query.trim()
        val prefs = Prefs(this)
        val out = ArrayList<MediaBrowserCompat.MediaItem>()
        if (q.isNotEmpty()) {
            val searchUrl = "https://music.youtube.com/search?q=" + android.net.Uri.encode(q)
            out.add(song(searchUrl, "\"$q\" ara", "YouTube Music"))
            for (e in (prefs.bookmarks() + prefs.history())) {
                if (e.title.contains(q, true) || e.url.contains(q, true)) {
                    out.add(song(e.url, e.title.ifBlank { e.url }, e.url))
                }
            }
        }
        result.sendResult(out)
    }

    override fun onDestroy() {
        try { session?.isActive = false; session?.release() } catch (_: Exception) { }
        session = null
        super.onDestroy()
    }

    private fun folder(id: String, title: String): MediaBrowserCompat.MediaItem {
        val d = MediaDescriptionCompat.Builder().setMediaId(id).setTitle(title).build()
        return MediaBrowserCompat.MediaItem(d, MediaBrowserCompat.MediaItem.FLAG_BROWSABLE)
    }

    private fun song(id: String, title: String, subtitle: String): MediaBrowserCompat.MediaItem {
        val d = MediaDescriptionCompat.Builder()
            .setMediaId(id)
            .setTitle(title.take(80))
            .setSubtitle(subtitle.take(80))
            .build()
        return MediaBrowserCompat.MediaItem(d, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
    }

    companion object {
        const val ROOT = "root"
        const val HOME = "home"
        const val FAV = "fav"
        const val RECENT = "recent"
    }
}
