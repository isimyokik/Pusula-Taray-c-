package com.pusula.browser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/** Bildirim ve kilit ekranı medya oynatıcısı. Düğmeler uygulamaya yayın olarak iletilir. */
class PlaybackService : Service() {
    private var session: MediaSession? = null
    private var playing = false
    private var title = ""
    private var artist = ""
    private var artUrl = ""
    @Volatile private var artBmp: android.graphics.Bitmap? = null
    private var foregroundStarted = false
    private var quiet = false
    private var position = PlaybackState.PLAYBACK_POSITION_UNKNOWN
    private var duration = -1L
    private val idleHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val idleStop = Runnable { stopSelf() }
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    /** Arama / başka uygulama ses alınca true; focus geri gelince devam */
    private var resumeAfterFocus = false
    private var heldFocus = false

    // Not: Otomatik pause/play kapalı — WebView medyası ile focus dinleyicisi
    // birbirini kesiyordu (play deyince stop). Telefon araması için ileride yumuşak eklenir.
    private val focusListener = AudioManager.OnAudioFocusChangeListener { _ -> }

    private fun requestFocus(): Boolean {
        val am = audioManager ?: return true
        if (heldFocus) return true
        val ok = if (Build.VERSION.SDK_INT >= 26) {
            val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setOnAudioFocusChangeListener(focusListener)
                .setAcceptsDelayedFocusGain(true)
                .setWillPauseWhenDucked(false)
                .build().also { focusRequest = it }
            am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                focusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        heldFocus = ok
        return ok
    }

    private fun abandonFocus() {
        val am = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                focusRequest?.let { am.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(focusListener)
            }
        } catch (_: Exception) { }
        heldFocus = false
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        try { startService(Intent(this, RotaMediaBrowserService::class.java)) } catch (_: Exception) { }
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            // IMPORTANCE_DEFAULT = bildirim çubuğunda görünür (LOW/MIN gizli kalıyordu)
            val ch = NotificationChannel(CHANNEL, "Medya oynatıcı", NotificationManager.IMPORTANCE_DEFAULT)
            ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
            val qc = NotificationChannel(CHANNEL_QUIET, "Medya oynatıcı (PiP)", NotificationManager.IMPORTANCE_LOW)
            qc.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            qc.setShowBadge(false)
            nm.createNotificationChannel(qc)
        }
        val s = MediaSession(this, "Rota")
        s.setCallback(object : MediaSession.Callback() {
            override fun onPlay() {
                send(ACTION_PLAY)
            }
            override fun onPause() {
                resumeAfterFocus = false
                send(ACTION_PAUSE)
            }
            override fun onSkipToNext() { send(ACTION_NEXT) }
            override fun onSkipToPrevious() { send(ACTION_PREVIOUS) }
            override fun onCustomAction(action: String, extras: android.os.Bundle?) {
                when (action) {
                    "close" -> closeAll()
                    "like" -> send(ACTION_LIKE)
                    "home" -> send(ACTION_HOME)
                }
            }
            override fun onPlayFromMediaId(mediaId: String, extras: android.os.Bundle?) {
                var u = mediaId?.trim().orEmpty() ?: return
                if (u.startsWith("sg:") || u.startsWith("pl:")) u = u.substring(3)
                if (!u.startsWith("http")) return
                sendBroadcast(Intent(ACTION_OPEN).setPackage(packageName).putExtra("url", u).putExtra("autoplay", true))
            }
            override fun onPlayFromSearch(query: String?, extras: android.os.Bundle?) {
                val q = query?.trim().orEmpty()
                if (q.isNotEmpty()) sendBroadcast(Intent(ACTION_SEARCH).setPackage(packageName).putExtra("q", q))
            }
            override fun onSeekTo(pos: Long) {
                sendBroadcast(Intent(ACTION_SEEK).setPackage(packageName).putExtra("pos", pos))
                position = pos
                refresh()
            }
        })
        try {
            val launch = packageManager.getLaunchIntentForPackage(packageName)
            if (launch != null) {
                launch.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                val pi = PendingIntent.getActivity(
                    this, 0, launch,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                s.setSessionActivity(pi)
            }
        } catch (_: Exception) { }
        s.isActive = true
        session = s
        sessionToken = s.sessionToken
    }

    private fun send(action: String) { sendBroadcast(Intent(action).setPackage(packageName)) }

    private fun closeAll() {
        resumeAfterFocus = false
        blockUntil = android.os.SystemClock.elapsedRealtime() + 5000L
        send(ACTION_CLOSE)
        playing = false
        abandonFocus()
        idleHandler.removeCallbacks(idleStop)
        try { session?.isActive = false } catch (_: Exception) { }
        try { session?.release() } catch (_: Exception) { }
        session = null
        try { stopForeground(Service.STOP_FOREGROUND_REMOVE) } catch (_: Exception) { }
        try { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID) } catch (_: Exception) { }
        foregroundStarted = false
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_UPDATE -> {
                playing = intent.getBooleanExtra("playing", false)
                // requestAudioFocus WebView (özellikle YT Music) ilk çalmayı kesiyordu — kapalı
                resumeAfterFocus = false
                val newTitle = intent.getStringExtra("title") ?: ""
                val titleChanged = newTitle != title
                title = newTitle
                artist = intent.getStringExtra("artist") ?: ""
                quiet = intent.getBooleanExtra("pip", false)
                val np = intent.getLongExtra("pos", -1L)
                if (np >= 0) position = np
                val nd = intent.getLongExtra("dur", -2L)
                if (nd != -2L) duration = nd
                val nu = intent.getStringExtra("art") ?: ""
                if (nu.isBlank()) {
                    // Boş gelirse (arka plana atınca olabiliyor) eski kapağı koru; yalnızca parça değiştiyse temizle
                    if (titleChanged) { artUrl = ""; artBmp = null }
                } else if (nu != artUrl) {
                    artUrl = nu
                    loadArt(nu)
                }
            }
            ACTION_PLAY, ACTION_TOGGLE, ACTION_PAUSE, ACTION_PREVIOUS, ACTION_NEXT -> send(intent.action!!)
            ACTION_CLOSE -> {
                closeAll()
                return START_NOT_STICKY
            }
        }
        refresh()
        return START_NOT_STICKY
    }

    private fun pi(code: Int, action: String): PendingIntent =
        PendingIntent.getBroadcast(this, code, Intent(action).setPackage(packageName), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun act(res: Int, label: String, p: PendingIntent): Notification.Action =
        Notification.Action.Builder(Icon.createWithResource(this, res), label, p).build()

    private fun loadArt(url: String) {
        Thread {
            for (attempt in 0..1) {
            try {
                val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 5000
                c.readTimeout = 5000
                c.setRequestProperty("User-Agent", "Mozilla/5.0")
                c.connect()
                var bmp = android.graphics.BitmapFactory.decodeStream(c.inputStream)
                c.disconnect()
                if (bmp != null) {
                    // Ortadan kare kırp (16:9 küçük resimlerde kapak ortada olur), en fazla 512px
                    val side = Math.min(bmp.width, bmp.height)
                    var sq = android.graphics.Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
                    if (side > 512) sq = android.graphics.Bitmap.createScaledBitmap(sq, 512, 512, true)
                    bmp = sq
                }
                if (bmp != null && artUrl == url) {
                    artBmp = bmp
                    // Ana thread'de bildirimi yenile
                    android.os.Handler(android.os.Looper.getMainLooper()).post { refresh() }
                }
                if (bmp != null) break
            } catch (_: Exception) { }
            try { Thread.sleep(3000) } catch (_: Exception) { }
            if (artUrl != url) break
            }
        }.start()
    }

    private fun refresh() {
        val s = session ?: return
        val shownTitle = if (title.isBlank()) "Rota" else title
        val mb = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, shownTitle)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
        if (duration > 0) mb.putLong(MediaMetadata.METADATA_KEY_DURATION, duration)
        try {
            artBmp?.let {
                mb.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it)
                mb.putBitmap(MediaMetadata.METADATA_KEY_ART, it)
            }
        } catch (_: Exception) { }
        // Auto / kilit ekranı için oturum servis yaşadığı sürece açık kalsın
                  s.isActive = true
                  idleHandler.removeCallbacks(idleStop)
                  if (!playing) idleHandler.postDelayed(idleStop, 90000L)
                  s.setMetadata(mb.build())
        // SEEK_TO: Auto ve bildirimde kaydırma çubuğu (süre biliniyorsa)
        var actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        if (duration > 0) {
            actions = actions or PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_REWIND or PlaybackState.ACTION_FAST_FORWARD
        }
        val posSafe = if (position >= 0) position else 0L
        s.setPlaybackState(PlaybackState.Builder()
            .setActions(actions)
            .addCustomAction(PlaybackState.CustomAction.Builder("close", "Kapat", R.drawable.ic_close).build())
            .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, posSafe, if (playing) 1f else 0f)
            .setBufferedPosition(posSafe)
            .build())

        // Sadece Android Auto MediaSession — telefon UI / WebView'e dokunmaz
        try {
            RotaMediaBrowserService.updateNowPlaying(
                title, artist, artUrl, playing, posSafe, if (duration > 0) duration else 0L
            )
            RotaMediaBrowserService.updateNowPlayingArt(artBmp)
        } catch (_: Exception) { }

        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, if (quiet) CHANNEL_QUIET else CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        b.setSmallIcon(R.drawable.ic_notif)
        try { artBmp?.let { b.setLargeIcon(it) } } catch (_: Exception) { }
        b.setContentTitle(shownTitle)
        b.setContentText(if (artist.isBlank()) "Rota" else artist)
        b.setContentIntent(open)
        b.setOngoing(playing)
        b.setVisibility(Notification.VISIBILITY_PUBLIC)
        b.addAction(act(android.R.drawable.ic_media_previous, "Önceki", pi(1, ACTION_PREVIOUS)))
        if (playing) b.addAction(act(android.R.drawable.ic_media_pause, "Duraklat", pi(2, ACTION_PAUSE)))
        else b.addAction(act(android.R.drawable.ic_media_play, "Oynat", pi(3, ACTION_PLAY)))
        b.addAction(act(android.R.drawable.ic_media_next, "Sonraki", pi(4, ACTION_NEXT)))
        val closePi = PendingIntent.getService(this, 5, Intent(this, PlaybackService::class.java).setAction(ACTION_CLOSE), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        b.addAction(act(android.R.drawable.ic_menu_close_clear_cancel, "Kapat", closePi))
        b.setStyle(Notification.MediaStyle().setMediaSession(s.sessionToken).setShowActionsInCompactView(0, 1, 2, 3))
        val n = b.build()
        try {
            if (!foregroundStarted) {
                if (Build.VERSION.SDK_INT >= 29) {
                    ServiceCompat.startForeground(this, NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                } else { startForeground(NOTIF_ID, n) }
                foregroundStarted = true
            } else {
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, n)
            }
            if (!playing) {
                stopForeground(Service.STOP_FOREGROUND_DETACH)
                foregroundStarted = false
            }
        } catch (_: Exception) { stopSelf() }
    }

    override fun onDestroy() {
        running = false
        resumeAfterFocus = false
        abandonFocus()
                  idleHandler.removeCallbacks(idleStop)
                  try { stopForeground(Service.STOP_FOREGROUND_REMOVE) } catch (_: Exception) { }
                  try { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID) } catch (_: Exception) { }
        try { session?.release() } catch (_: Exception) { }
        session = null
        sessionToken = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL = "playback_visible"
        const val CHANNEL_QUIET = "playback_quiet_v2"
        const val NOTIF_ID = 1002
        const val ACTION_UPDATE = "com.pusula.browser.MEDIA_UPDATE"
        const val ACTION_PLAY = "com.pusula.browser.MEDIA_PLAY"
        const val ACTION_TOGGLE = "com.pusula.browser.MEDIA_TOGGLE"
        const val ACTION_PAUSE = "com.pusula.browser.MEDIA_PAUSE"
        const val ACTION_PREVIOUS = "com.pusula.browser.MEDIA_PREVIOUS"
        const val ACTION_NEXT = "com.pusula.browser.MEDIA_NEXT"
        const val ACTION_SEEK = "com.pusula.browser.MEDIA_SEEK"
        const val ACTION_BACK10 = "com.pusula.browser.MEDIA_BACK10"
        const val ACTION_FWD10 = "com.pusula.browser.MEDIA_FWD10"
        const val ACTION_CLOSE = "com.pusula.browser.MEDIA_CLOSE"
        const val ACTION_LIKE = "com.pusula.browser.MEDIA_LIKE"
        const val ACTION_HOME = "com.pusula.browser.MEDIA_HOME"
        const val ACTION_SEARCH = "com.pusula.browser.MEDIA_SEARCH"
        const val ACTION_OPEN = "com.pusula.browser.MEDIA_OPEN"

        @Volatile var running = false
        @Volatile var sessionToken: MediaSession.Token? = null

        @Volatile var blockUntil = 0L

        fun update(ctx: Context, playing: Boolean, title: String, artist: String, pip: Boolean = false, pos: Long = -1L, dur: Long = -2L, art: String = "") {
            if (android.os.SystemClock.elapsedRealtime() < blockUntil) return
            val i = Intent(ctx, PlaybackService::class.java).setAction(ACTION_UPDATE)
                .putExtra("playing", playing).putExtra("title", title).putExtra("artist", artist).putExtra("pip", pip).putExtra("pos", pos).putExtra("dur", dur).putExtra("art", art)
            try { ContextCompat.startForegroundService(ctx, i) } catch (_: Exception) { }
        }

        fun stop(ctx: Context) {
            try { ctx.stopService(Intent(ctx, PlaybackService::class.java)) } catch (_: Exception) { }
        }
    }
}
