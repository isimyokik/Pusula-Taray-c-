package com.pusula.browser

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * ExoPlayer oynatıcı + bildirim medyası + YouTube ile aynı PiP kontrolleri
 * (önceki / oynat-duraklat / sonraki → PlaybackService yayınları).
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MediaPlayerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var topBar: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var flash: TextView
    private lateinit var bars: WindowInsetsControllerCompat
    private val handler = Handler(Looper.getMainLooper())
    private var isAudio = false
    private var controlRegistered = false
    private var pipEntering = false
    private lateinit var repeatBtn: TextView
    private lateinit var shuffleBtn: TextView
    /** 0=sırayla (off), 1=liste tekrarı, 2=tek parça */
    private var repeatModeUi = 1
    private var currentArtPath: String = ""
    private val artCache = HashMap<String, String>()
    /** Ağır kapak çıkarma (gömülü resim / video karesi) her dosya için yalnızca bir kez denenir. */
    private val artTried = HashSet<String>()
    private val hideFlash = Runnable { flash.visibility = View.GONE }

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val p = player ?: return
            // Bildirim şu an web medyasına aitse (YouTube vb.) oynatıcı komutları yok sayar
            if (owner != "player") return
            when (intent?.action) {
                PlaybackService.ACTION_PLAY -> {
                    p.playWhenReady = true
                    p.play()
                    pushNotif()
                    updatePipActions()
                }
                PlaybackService.ACTION_PAUSE -> {
                    p.pause()
                    pushNotif()
                    updatePipActions()
                }
                PlaybackService.ACTION_TOGGLE -> {
                    if (p.isPlaying) p.pause() else { p.playWhenReady = true; p.play() }
                    pushNotif()
                    updatePipActions()
                }
                PlaybackService.ACTION_NEXT -> {
                    try {
                        if (p.hasNextMediaItem()) p.seekToNextMediaItem()
                        else if (p.mediaItemCount > 0) p.seekTo(0, 0L)
                        p.prepare()
                        p.playWhenReady = true
                        p.play()
                    } catch (_: Exception) { }
                    pushNotif()
                    updatePipActions()
                }
                PlaybackService.ACTION_PREVIOUS -> {
                    try {
                        if (p.currentPosition > 3000L || !p.hasPreviousMediaItem()) {
                            p.seekTo(0L)
                        } else {
                            p.seekToPreviousMediaItem()
                        }
                        p.prepare()
                        p.playWhenReady = true
                        p.play()
                    } catch (_: Exception) { }
                    pushNotif()
                    updatePipActions()
                }
                PlaybackService.ACTION_SEEK -> {
                    val pos = intent.getLongExtra("pos", -1L)
                    if (pos >= 0) p.seekTo(pos)
                    pushNotif()
                }
                PlaybackService.ACTION_CLOSE -> {
                    try { p.pause() } catch (_: Exception) { }
                    finish()
                }
            }
        }
    }

    private val progressTick = object : Runnable {
        override fun run() {
            pushNotif()
            updatePipActions()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val paths = intent.getStringArrayListExtra(EXTRA_PATHS) ?: arrayListOf()
        val titles = intent.getStringArrayListExtra(EXTRA_TITLES) ?: arrayListOf()
        if (paths.isEmpty()) { finish(); return }
        val start = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, paths.size - 1)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        bars = WindowInsetsControllerCompat(window, window.decorView)
        bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars.isAppearanceLightStatusBars = false
        bars.isAppearanceLightNavigationBars = false

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(b.left, 0, b.right, b.bottom)
            insets
        }

        playerView = PlayerView(this)
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        playerView.setControllerShowTimeoutMs(3500)
        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT)
        playerView.setDefaultArtwork(BitmapDrawable(resources, makeArt()))
        playerView.setShutterBackgroundColor(Color.BLACK)
        // İngilizce ses/stereo/track menüsü vs. kapalı
        try { playerView.setShowSubtitleButton(false) } catch (_: Exception) { }
        try { playerView.setShowVrButton(false) } catch (_: Exception) { }
        try { playerView.setShowShuffleButton(false) } catch (_: Exception) { }
        try {
            val m = playerView.javaClass.getMethod("setShowAudioTrackSelectionButton", Boolean::class.javaPrimitiveType)
            m.invoke(playerView, false)
        } catch (_: Exception) { }
        try {
            val m = playerView.javaClass.getMethod("setShowSettingsButton", Boolean::class.javaPrimitiveType)
            m.invoke(playerView, false)
        } catch (_: Exception) { }
        try {
            // Media3: tekrar tuşu kontrol çubuğunda istenirse kapalı (üstte bizimki var)
            val m = playerView.javaClass.getMethod("setRepeatToggleModes", Int::class.javaPrimitiveType)
            m.invoke(playerView, 0)
        } catch (_: Exception) { }
        root.addView(
            playerView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        topBar = LinearLayout(this)
        topBar.orientation = LinearLayout.HORIZONTAL
        topBar.gravity = Gravity.CENTER_VERTICAL
        topBar.setBackgroundColor(0x99000000.toInt())
        ViewCompat.setOnApplyWindowInsetsListener(topBar) { v, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(dp(6), sb.top + dp(6), dp(6), dp(8))
            insets
        }
        val close = TextView(this)
        close.text = "✕"
        close.textSize = 20f
        close.setTextColor(Color.WHITE)
        close.gravity = Gravity.CENTER
        close.setOnClickListener {
            try { player?.pause() } catch (_: Exception) { }
            if (owner == "player") PlaybackService.stop(this)
            finish()
        }
        topBar.addView(close, LinearLayout.LayoutParams(dp(52), dp(44)))

        titleView = TextView(this)
        titleView.textSize = 15f
        titleView.setTextColor(Color.WHITE)
        titleView.maxLines = 1
        titleView.ellipsize = android.text.TextUtils.TruncateAt.END
        topBar.addView(
            titleView,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) }
        )

        fun chip(label: String): TextView {
            val tv = TextView(this)
            tv.text = label
            tv.textSize = 13f
            tv.setTextColor(Color.WHITE)
            tv.gravity = Gravity.CENTER
            tv.setPadding(dp(8), 0, dp(8), 0)
            return tv
        }
        shuffleBtn = chip("🔀")
        shuffleBtn.contentDescription = "Karışık"
        shuffleBtn.setOnClickListener {
            val pl = player ?: return@setOnClickListener
            pl.shuffleModeEnabled = !pl.shuffleModeEnabled
            syncModeButtons()
        }
        topBar.addView(shuffleBtn, LinearLayout.LayoutParams(dp(44), dp(44)))

        repeatBtn = chip("🔁")
        repeatBtn.contentDescription = "Tekrar"
        repeatBtn.setOnClickListener {
            val pl = player ?: return@setOnClickListener
            repeatModeUi = (repeatModeUi + 1) % 3
            pl.repeatMode = when (repeatModeUi) {
                1 -> Player.REPEAT_MODE_ALL
                2 -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
            syncModeButtons()
        }
        topBar.addView(repeatBtn, LinearLayout.LayoutParams(dp(44), dp(44)))

        // YouTube menüsündeki gibi PiP tuşu
        val pipBtn = TextView(this)
        pipBtn.text = "⧉"
        pipBtn.textSize = 20f
        pipBtn.setTextColor(Color.WHITE)
        pipBtn.gravity = Gravity.CENTER
        pipBtn.contentDescription = "Küçük pencere"
        pipBtn.setOnClickListener { enterPip(goHome = true) }
        topBar.addView(pipBtn, LinearLayout.LayoutParams(dp(52), dp(44)))

        root.addView(
            topBar,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP)
        )

        flash = TextView(this)
        flash.textSize = 18f
        flash.setTextColor(Color.WHITE)
        flash.setBackgroundColor(0xAA000000.toInt())
        flash.setPadding(dp(18), dp(10), dp(18), dp(10))
        flash.visibility = View.GONE
        root.addView(
            flash,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        )

        active = true
        setContentView(root)

        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { v ->
                if (!isInPictureInPictureMode) {
                    topBar.visibility = if (v == View.VISIBLE || isAudio) View.VISIBLE else View.GONE
                }
            }
        )

        val attrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        val p = ExoPlayer.Builder(this)
            .setAudioAttributes(attrs, false)
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10000)
            .setSeekForwardIncrementMs(10000)
            .build()
        // Kilit ekranında / arka planda CPU uyumasın: şarkılar kesintisiz sürsün
        try { p.setWakeMode(C.WAKE_MODE_LOCAL) } catch (_: Exception) { }
        val items = buildItems(paths, titles)
        p.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                try {
                    titleView.text = mediaItem?.mediaMetadata?.title ?: ""
                    currentArtPath = ""
                    handler.post {
                        try {
                            refreshArtForCurrent()
                            pushNotif()
                            updatePipActions()
                        } catch (_: Exception) { }
                    }
                } catch (_: Exception) { }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                try {
                    if (isPlaying) {
                        playStartAt = android.os.SystemClock.elapsedRealtime()
                        extBaseline = -1
                        if (owner != "player") claimOwner()
                        handler.postDelayed(extCheck, 1500L)
                    }
                    pushNotif(); updatePipActions()
                } catch (_: Exception) { }
            }
            override fun onTracksChanged(tracks: Tracks) {
                try {
                    if (tracks.groups.isEmpty()) return
                    val hasVideo = tracks.isTypeSelected(C.TRACK_TYPE_VIDEO)
                    handler.post {
                        try { setAudioMode(!hasVideo) } catch (_: Exception) { }
                    }
                } catch (_: Exception) { }
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    handler.post {
                        try {
                            val pl = player ?: return@post
                            if (pl.mediaItemCount <= 0) return@post
                            if (pl.hasNextMediaItem()) {
                                pl.seekToNextMediaItem()
                            } else {
                                pl.seekTo(0, 0L)
                            }
                            pl.playWhenReady = true
                            pl.prepare()
                            pl.play()
                        } catch (_: Exception) {
                            try {
                                player?.seekTo(0, 0L)
                                player?.playWhenReady = true
                                player?.play()
                            } catch (_: Exception) { }
                        }
                    }
                }
                try { pushNotif(); updatePipActions() } catch (_: Exception) { }
            }
            override fun onPlayerError(error: PlaybackException) {
                // Çökme yok — sonraki parçaya geçmeyi dene
                try {
                    Toast.makeText(
                        this@MediaPlayerActivity,
                        "Atlandı: " + (error.message ?: "hata"),
                        Toast.LENGTH_SHORT
                    ).show()
                    val pl = player
                    if (pl != null && pl.mediaItemCount > 1) {
                        if (pl.hasNextMediaItem()) pl.seekToNextMediaItem()
                        else pl.seekTo(0, 0L)
                        pl.prepare()
                        pl.playWhenReady = true
                        pl.play()
                    }
                } catch (_: Exception) { }
            }
        })
        p.setMediaItems(items, start, 0L)
        p.repeatMode = Player.REPEAT_MODE_ALL
        p.prepare()
        p.playWhenReady = true
        playerView.player = p
        player = p
        p.shuffleModeEnabled = false
        repeatModeUi = 1
        p.repeatMode = Player.REPEAT_MODE_ALL
        syncModeButtons()
        refreshArtForCurrent()
        titleView.text = titles.getOrElse(start) { File(paths[start]).name }

        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val pl = player ?: return false
                val d = 10000L
                if (e.x < playerView.width / 2f) {
                    pl.seekTo(max(0L, pl.currentPosition - d))
                    flashMsg("⏪ 10 sn")
                } else {
                    var t = pl.currentPosition + d
                    if (pl.duration != C.TIME_UNSET) t = min(t, pl.duration)
                    pl.seekTo(t)
                    flashMsg("10 sn ⏩")
                }
                return true
            }
        })
        playerView.setOnTouchListener { _, ev -> gd.onTouchEvent(ev); false }

        pauseHook = {
            try { player?.pause() } catch (_: Exception) { }
            try { updatePipActions() } catch (_: Exception) { }
        }
        claimOwner()
        registerControls()
        registerDuckWatchers()
        pushNotif()
        handler.post(progressTick)
        applyBars()
        if (Build.VERSION.SDK_INT >= 26) {
            try { setPictureInPictureParams(buildPipParams()) } catch (_: Exception) { }
        }
    }

    private fun buildItems(paths: List<String>, titles: List<String>): List<MediaItem> =
        paths.mapIndexed { i, path ->
            MediaItem.Builder()
                .setUri(Uri.fromFile(File(path)))
                .setMediaMetadata(
                    MediaMetadata.Builder().setTitle(titles.getOrElse(i) { File(path).name }).build()
                )
                .build()
        }

    /**
     * Oynatıcı zaten açıkken (arka planda ya da küçük pencerede) yeni bir medya seçilirse
     * ikinci bir oynatıcı açılmaz; liste mevcut oynatıcıya yüklenir.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val paths = intent.getStringArrayListExtra(EXTRA_PATHS) ?: return
        val titles = intent.getStringArrayListExtra(EXTRA_TITLES) ?: arrayListOf()
        if (paths.isEmpty()) return
        val p = player ?: return
        val start = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, paths.size - 1)
        claimOwner()
        try {
            p.setMediaItems(buildItems(paths, titles), start, 0L)
            p.prepare()
            p.playWhenReady = true
            p.play()
            currentArtPath = ""
            titleView.text = titles.getOrElse(start) { File(paths[start]).name }
            refreshArtForCurrent()
            pushNotif()
            updatePipActions()
        } catch (_: Exception) { }
    }

    private fun pipAction(code: Int, icon: Int, label: String, action: String): RemoteAction {
        val pi = PendingIntent.getBroadcast(
            this, code,
            Intent(action).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return RemoteAction(Icon.createWithResource(this, icon), label, label, pi)
    }

    private fun buildPipParams(): PictureInPictureParams {
        // Yatay ve daha küçük yükseklik (16:9 tam ekrana yakın büyük açılabiliyor)
        val ratio = Rational(2, 1)
        val b = PictureInPictureParams.Builder().setAspectRatio(ratio)
        if (Build.VERSION.SDK_INT >= 26) {
            val acts = ArrayList<RemoteAction>()
            acts.add(pipAction(31, R.drawable.ic_pip_prev, "Önceki", PlaybackService.ACTION_PREVIOUS))
            val playing = player?.isPlaying == true || player?.playWhenReady == true
            val midIcon = if (playing) R.drawable.ic_pip_pause else R.drawable.ic_pip_play
            val midLabel = if (playing) "Duraklat" else "Oynat"
            acts.add(
                pipAction(
                    32, midIcon, midLabel,
                    if (playing) PlaybackService.ACTION_PAUSE else PlaybackService.ACTION_PLAY
                )
            )
            acts.add(pipAction(33, R.drawable.ic_pip_next, "Sonraki", PlaybackService.ACTION_NEXT))
            b.setActions(acts)
        }
        return b.build()
    }

    private fun updatePipActions() {
        if (Build.VERSION.SDK_INT < 26 || !isInPictureInPictureMode) return
        try { setPictureInPictureParams(buildPipParams()) } catch (_: Exception) { }
    }

    private val pipEnteringReset = Runnable { pipEntering = false }

    /**
     * @param goHome true ise küçük pencere açılınca arkadaki uygulama ana ekrana çekilir
     * (YouTube küçük penceresindeki gibi: arkada uygulama açık kalmaz). Ana ekrana basınca zaten böyle olur.
     */
    private var pendingGoHome = false

    private fun goHomeNow() {
        try {
            startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) { }
    }

    private fun enterPip(goHome: Boolean = false) {
        if (Build.VERSION.SDK_INT < 26) {
            Toast.makeText(this, "Bu cihazda küçük pencere yok", Toast.LENGTH_SHORT).show()
            return
        }
        if (isInPictureInPictureMode || pipEntering) return
        pipEntering = true
        var entered = false
        try {
            setPictureInPictureParams(buildPipParams())
            entered = enterPictureInPictureMode(buildPipParams())
        } catch (_: Exception) {
            try { enterPictureInPictureMode(); entered = true } catch (_: Exception) { }
        } finally {
            handler.removeCallbacks(pipEnteringReset)
            handler.postDelayed(pipEnteringReset, 400)
        }
        if (goHome && entered) {
            pendingGoHome = true
            // PiP'e geçiş bitince (onPipUi) ve yedek olarak gecikmeli: arkadaki uygulama ana ekrana çekilsin
            for (d in longArrayOf(250L, 700L)) {
                handler.postDelayed({ if (isInPictureInPictureMode && pendingGoHome) goHomeNow() }, d)
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val p = player
        if (Build.VERSION.SDK_INT >= 26 &&
            !isInPictureInPictureMode &&
            p != null &&
            (p.isPlaying || p.playWhenReady)
        ) {
            enterPip()
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        onPipUi(isInPictureInPictureMode)
    }

    @Deprecated("Deprecated in Java")
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
        onPipUi(isInPictureInPictureMode)
    }

    private fun onPipUi(pip: Boolean) {
        if (pip && pendingGoHome) { goHomeNow() }
        if (!pip) pendingGoHome = false
        topBar.visibility = if (pip) View.GONE else View.VISIBLE
        if (pip) {
            try { playerView.hideController() } catch (_: Exception) { }
        }
        pushNotif()
        updatePipActions()
    }

    private fun registerControls() {
        if (controlRegistered) return
        val f = IntentFilter().apply {
            addAction(PlaybackService.ACTION_PLAY)
            addAction(PlaybackService.ACTION_PAUSE)
            addAction(PlaybackService.ACTION_TOGGLE)
            addAction(PlaybackService.ACTION_NEXT)
            addAction(PlaybackService.ACTION_PREVIOUS)
            addAction(PlaybackService.ACTION_SEEK)
            addAction(PlaybackService.ACTION_CLOSE)
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(controlReceiver, f, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(controlReceiver, f)
            }
            controlRegistered = true
        } catch (_: Exception) { }
    }


    /** İndirilenler listesinde kayıtlı kapak (dosya adına göre) – hızlı, ağ/çözümleme yok. */
    private fun thumbForTitle(title: String): String {
        if (title.isBlank()) return ""
        return try {
            val safe = title.replace(Regex("[\\\\/:*?\"<>|]+"), "_").take(80)
            val f = File(File(cacheDir, "thumbs"), "$safe.jpg")
            if (f.exists() && f.length() > 500) f.absolutePath else ""
        } catch (_: Exception) { "" }
    }

    /** Dosyadan gömülü kapak, yoksa (video ise) bir kare çıkar → cache dosyası yolu. Arka plan iş parçacığında çağrılır. */
    private fun artForPath(mediaPath: String): String {
        artCache[mediaPath]?.let { if (File(it).exists()) return it }
        return try {
            val dir = File(cacheDir, "media_art").apply { mkdirs() }
            val out = File(dir, "art_" + mediaPath.hashCode().toUInt().toString(16) + ".jpg")
            if (out.exists() && out.length() > 0L) {
                artCache[mediaPath] = out.absolutePath
                return out.absolutePath
            }
            val r = android.media.MediaMetadataRetriever()
            try {
                r.setDataSource(mediaPath)
                val bytes = r.embeddedPicture
                if (bytes != null && bytes.isNotEmpty()) {
                    out.outputStream().use { it.write(bytes) }
                } else {
                    val ext = File(mediaPath).extension.lowercase()
                    if (ext in listOf("mp4", "mkv", "webm", "mov", "3gp")) {
                        val bmp = r.getFrameAtTime(1_000_000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                            ?: r.getFrameAtTime(0)
                        if (bmp != null) {
                            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
                        }
                    }
                }
            } finally {
                try { r.release() } catch (_: Exception) { }
            }
            if (out.exists() && out.length() > 0L) {
                artCache[mediaPath] = out.absolutePath
                out.absolutePath
            } else ""
        } catch (_: Throwable) {
            ""
        }
    }

    private fun currentFilePath(): String? {
        val p = player ?: return null
        val uri = try { p.currentMediaItem?.localConfiguration?.uri } catch (_: Exception) { null } ?: return null
        val path = try { uri.path } catch (_: Exception) { null }
        if (!path.isNullOrBlank() && path.startsWith("/")) return path
        return try {
            val s = uri.toString()
            if (s.startsWith("file:")) Uri.parse(s).path else null
        } catch (_: Exception) { null }
    }

    private fun refreshArtForCurrent() {
        val p = player ?: return
        val title = try { p.currentMediaItem?.mediaMetadata?.title?.toString() ?: "" } catch (_: Exception) { "" }
        // 1) İndirilenler listesindeki kapak (bildirimde de aynısı görünsün)
        val th = thumbForTitle(title)
        if (th.isNotBlank()) { currentArtPath = th; return }
        val filePath = currentFilePath()
        if (filePath.isNullOrBlank()) { currentArtPath = ""; return }
        // 2) Daha önce çıkarılmışsa
        val cached = artCache[filePath]
        if (cached != null && File(cached).exists()) { currentArtPath = cached; return }
        currentArtPath = ""
        // 3) Gömülü kapak / video karesi: arayüzü kilitlemesin diye arka planda, dosya başına bir kez
        if (!artTried.add(filePath)) return
        Thread {
            val r = artForPath(filePath)
            if (r.isNotBlank()) {
                handler.post {
                    if (currentFilePath() == filePath) {
                        currentArtPath = r
                        try { pushNotif() } catch (_: Exception) { }
                    }
                }
            }
        }.start()
    }

    private fun pushNotif() {
        val p = player ?: return
        if (owner != "player") return // bildirim şu an web medyasının
        val title = (p.currentMediaItem?.mediaMetadata?.title?.toString()
            ?: titleView.text?.toString()
            ?: "Rota").ifBlank { "Rota" }
        val pos = p.currentPosition.coerceAtLeast(0L)
        val dur = if (p.duration != C.TIME_UNSET && p.duration > 0) p.duration else -1L
        if (currentArtPath.isBlank()) refreshArtForCurrent()
        try {
            PlaybackService.update(
                this,
                p.isPlaying || p.playWhenReady,
                title,
                "İndirilenler",
                isInPictureInPictureMode,
                pos,
                dur,
                currentArtPath
            )
        } catch (_: Exception) { }
    }

    private fun syncModeButtons() {
        val pl = player
        val shuffleOn = pl?.shuffleModeEnabled == true
        shuffleBtn.alpha = if (shuffleOn) 1f else 0.45f
        shuffleBtn.text = if (shuffleOn) "🔀" else "🔀"
        when (repeatModeUi) {
            1 -> { // liste
                repeatBtn.text = "🔁"
                repeatBtn.alpha = 1f
                repeatBtn.contentDescription = "Liste tekrarı"
            }
            2 -> { // tek
                repeatBtn.text = "🔂"
                repeatBtn.alpha = 1f
                repeatBtn.contentDescription = "Tek parça tekrarı"
            }
            else -> { // sırayla bir kez
                repeatBtn.text = "➡️"
                repeatBtn.alpha = 0.45f
                repeatBtn.contentDescription = "Sırayla (bir kez)"
            }
        }
    }

    private fun setAudioMode(audio: Boolean) {
        isAudio = audio
        playerView.setControllerShowTimeoutMs(if (audio) 0 else 3500)
        playerView.setControllerHideOnTouch(!audio)
        if (audio) {
            playerView.showController()
            if (!isInPictureInPictureMode) topBar.visibility = View.VISIBLE
        }
        applyBars()
    }

    private fun applyBars() {
        if (isAudio) bars.show(WindowInsetsCompat.Type.systemBars())
        else bars.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun flashMsg(s: String) {
        flash.text = s
        flash.visibility = View.VISIBLE
        handler.removeCallbacks(hideFlash)
        handler.postDelayed(hideFlash, 700)
    }

    private fun makeArt(): Bitmap {
        val s = 720
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(
            0f, 0f, s.toFloat(), s.toFloat(),
            0xFF2F6BFF.toInt(), 0xFFFF4D9D.toInt(), Shader.TileMode.CLAMP
        )
        c.drawRoundRect(RectF(0f, 0f, s.toFloat(), s.toFloat()), 120f, 120f, p)
        val t = Paint(Paint.ANTI_ALIAS_FLAG)
        t.textSize = 320f
        t.textAlign = Paint.Align.CENTER
        c.drawText("🎵", s / 2f, s / 2f + 110f, t)
        return b
    }

    // ================= Ses kısma: WhatsApp sesi / mikrofon / arama (web medyasıyla aynı davranış) =================
    private val am: android.media.AudioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager }
    private var duckLevel = 1.0
    private var ducked = false
    private var duckRunnable: Runnable? = null
    private var duckSince = 0L
    private var extBaseline = -1
    private var extPeak = 1
    private var extSeenSince = 0L
    private var extQuietSince = 0L
    private var playStartAt = 0L
    private var micActive = false
    private var recCb: android.media.AudioManager.AudioRecordingCallback? = null
    private var playCb: android.media.AudioManager.AudioPlaybackCallback? = null
    private val extCheck = Runnable { evalExternal() }
    /** Dış sese/kayda göre sesi bu sürelerde yumuşakça kıs / aç. */
    private val duckFadeOutMs = 1000L
    private val duckFadeInMs = 1500L

    /** Bildirimi ve sesi bu oynatıcı devralır; web medyası durur, sekmeler uyur. */
    private fun claimOwner() {
        owner = "player"
        try { takeoverHook?.invoke() } catch (_: Exception) { }
    }

    private fun usageCounts(u: Int): Boolean = when (u) {
        android.media.AudioAttributes.USAGE_ALARM,
        android.media.AudioAttributes.USAGE_NOTIFICATION,
        android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE,
        android.media.AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_REQUEST,
        android.media.AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT,
        android.media.AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_DELAYED,
        android.media.AudioAttributes.USAGE_NOTIFICATION_EVENT,
        android.media.AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY,
        android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
        android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION,
        android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING -> false
        else -> true
    }

    private fun audioCount(): Int {
        if (Build.VERSION.SDK_INT < 26) return 0
        return try {
            am.activePlaybackConfigurations.count { usageCounts(it.audioAttributes.usage) }
        } catch (_: Exception) { 0 }
    }

    private fun runDuck(target: Double, ms: Long, done: () -> Unit = {}) {
        duckRunnable?.let { handler.removeCallbacks(it) }
        val start = duckLevel
        val t0 = android.os.SystemClock.uptimeMillis()
        val r = object : Runnable {
            override fun run() {
                val t = ((android.os.SystemClock.uptimeMillis() - t0).toDouble() / ms.toDouble()).coerceIn(0.0, 1.0)
                val e = 0.5 - 0.5 * Math.cos(Math.PI * t)
                duckLevel = (start + (target - start) * e).coerceIn(0.0, 1.0)
                try { player?.volume = duckLevel.toFloat() } catch (_: Exception) { }
                if (t >= 1.0) {
                    duckRunnable = null
                    duckLevel = target
                    try { player?.volume = target.toFloat() } catch (_: Exception) { }
                    done()
                } else handler.postDelayed(this, 50L)
            }
        }
        duckRunnable = r
        handler.post(r)
    }

    private fun beginDuck() {
        if (ducked || owner != "player") return
        ducked = true
        duckSince = android.os.SystemClock.elapsedRealtime()
        extQuietSince = 0L
        extPeak = audioCount().coerceAtLeast(1)
        runDuck(0.0, duckFadeOutMs)
        handler.removeCallbacks(extCheck)
        handler.postDelayed(extCheck, 600L)
    }

    private fun endDuck() {
        if (!ducked) return
        ducked = false
        runDuck(1.0, duckFadeInMs) {
            extBaseline = -1
            extSeenSince = 0L
            handler.removeCallbacks(extCheck)
            handler.postDelayed(extCheck, 100L)
        }
    }

    private fun evalExternal() {
        val now = android.os.SystemClock.elapsedRealtime()
        val n = audioCount()
        val inCall = try { am.mode != android.media.AudioManager.MODE_NORMAL } catch (_: Exception) { false }
        if (ducked) {
            if (micActive || inCall) { handler.postDelayed(extCheck, 500L); return }
            if (now - duckSince < 2000L) { handler.postDelayed(extCheck, 500L); return }
            if (n >= extPeak) { extQuietSince = 0L; handler.postDelayed(extCheck, 500L); return }
            if (extQuietSince == 0L) {
                extQuietSince = now
                handler.postDelayed(extCheck, 600L)
            } else if (now - extQuietSince >= 700L) {
                extQuietSince = 0L
                endDuck()
            } else handler.postDelayed(extCheck, 400L)
            return
        }
        val playing = try { player?.isPlaying == true } catch (_: Exception) { false }
        if (!playing || duckRunnable != null || owner != "player") { extBaseline = -1; extSeenSince = 0L; return }
        if (inCall) { beginDuck(); return }
        // Kendi sesimizin oturduğu taban: çalmaya başladıktan 1,5 sn sonra ölçülür
        if (now - playStartAt < 1500L) { handler.postDelayed(extCheck, 600L); return }
        if (n >= 1 && (extBaseline < 0 || n < extBaseline)) extBaseline = n
        if (extBaseline >= 1 && n > extBaseline) {
            if (extSeenSince == 0L) {
                extSeenSince = now
                handler.postDelayed(extCheck, 250L)
            } else if (now - extSeenSince >= 250L) {
                extSeenSince = 0L
                beginDuck()
            }
        } else extSeenSince = 0L
    }

    private fun registerDuckWatchers() {
        if (Build.VERSION.SDK_INT >= 26 && playCb == null) {
            try {
                val cb = object : android.media.AudioManager.AudioPlaybackCallback() {
                    override fun onPlaybackConfigChanged(configs: MutableList<android.media.AudioPlaybackConfiguration>?) {
                        handler.removeCallbacks(extCheck)
                        handler.postDelayed(extCheck, 60L)
                    }
                }
                am.registerAudioPlaybackCallback(cb, handler)
                playCb = cb
            } catch (_: Exception) { }
        }
        if (Build.VERSION.SDK_INT >= 24 && recCb == null) {
            try {
                val cb = object : android.media.AudioManager.AudioRecordingCallback() {
                    override fun onRecordingConfigChanged(configs: MutableList<android.media.AudioRecordingConfiguration>?) {
                        val rec = !configs.isNullOrEmpty()
                        if (rec && !micActive) {
                            micActive = true
                            handler.postDelayed({
                                if (micActive && !ducked && player?.isPlaying == true) beginDuck()
                            }, 250L)
                        } else if (!rec && micActive) {
                            micActive = false
                            handler.postDelayed({ if (!micActive && ducked) endDuck() }, 500L)
                        }
                    }
                }
                am.registerAudioRecordingCallback(cb, handler)
                recCb = cb
            } catch (_: Exception) { }
        }
    }

    private fun unregisterDuckWatchers() {
        try { playCb?.let { am.unregisterAudioPlaybackCallback(it) } } catch (_: Exception) { }
        try { recCb?.let { am.unregisterAudioRecordingCallback(it) } } catch (_: Exception) { }
        playCb = null; recCb = null
        duckRunnable?.let { handler.removeCallbacks(it) }
        duckRunnable = null
        handler.removeCallbacks(extCheck)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    override fun onStop() {
        super.onStop()
        pushNotif()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (controlRegistered) {
            try { unregisterReceiver(controlReceiver) } catch (_: Exception) { }
            controlRegistered = false
        }
        unregisterDuckWatchers()
        try { playerView.player = null } catch (_: Exception) { }
        player?.release()
        player = null
        active = false
        pauseHook = null
        val wasOwner = owner == "player"
        owner = "web"
        if (wasOwner) { try { PlaybackService.stop(this) } catch (_: Exception) { } }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PATHS = "paths"
        const val EXTRA_TITLES = "titles"
        const val EXTRA_INDEX = "index"
        @Volatile var active = false
        /** Bildirimin/sesin sahibi: "player" (bu oynatıcı) ya da "web" (YouTube, YT Music …). Aynı anda tek sahip. */
        @Volatile var owner: String = "web"
        /** MainActivity: oynatıcı devralınca web medyasını durdurup sekmeleri uyutur. */
        @Volatile var takeoverHook: (() -> Unit)? = null
        /** MainActivity: web medyası devralınca oynatıcıyı duraklatır. */
        @Volatile var pauseHook: (() -> Unit)? = null
    }
}
