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
    private var currentArtPath: String = ""
    private val artCache = HashMap<String, String>()

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val p = player ?: return
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
                    if (p.hasNextMediaItem()) p.seekToNextMediaItem()
                    else if (p.mediaItemCount > 0) p.seekTo(0, 0L)
                    p.playWhenReady = true
                    p.play()
                    pushNotif()
                    updatePipActions()
                }
                PlaybackService.ACTION_PREVIOUS -> {
                    if (p.currentPosition > 3000L || !p.hasPreviousMediaItem()) {
                        p.seekTo(0L)
                    } else {
                        p.seekToPreviousMediaItem()
                    }
                    p.playWhenReady = true
                    p.play()
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
            PlaybackService.stop(this)
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

        // YouTube menüsündeki gibi PiP tuşu
        val pipBtn = TextView(this)
        pipBtn.text = "⧉"
        pipBtn.textSize = 20f
        pipBtn.setTextColor(Color.WHITE)
        pipBtn.gravity = Gravity.CENTER
        pipBtn.contentDescription = "Küçük pencere"
        pipBtn.setOnClickListener { enterPip() }
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
            .setAudioAttributes(attrs, true)
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10000)
            .setSeekForwardIncrementMs(10000)
            .build()
        val items = paths.mapIndexed { i, path ->
            MediaItem.Builder()
                .setUri(Uri.fromFile(File(path)))
                .setMediaMetadata(
                    MediaMetadata.Builder().setTitle(titles.getOrElse(i) { File(path).name }).build()
                )
                .build()
        }
        p.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                titleView.text = mediaItem?.mediaMetadata?.title ?: ""
                currentArtPath = ""
                refreshArtForCurrent()
                pushNotif()
                updatePipActions()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                pushNotif()
                updatePipActions()
            }
            override fun onTracksChanged(tracks: Tracks) {
                if (tracks.groups.isNotEmpty()) setAudioMode(!tracks.isTypeSelected(C.TRACK_TYPE_VIDEO))
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    try {
                        if (p.mediaItemCount > 0) {
                            p.seekToNextMediaItem()
                            p.playWhenReady = true
                            p.play()
                        }
                    } catch (_: Exception) {
                        try {
                            p.seekTo(0, 0L)
                            p.playWhenReady = true
                            p.play()
                        } catch (_: Exception) { }
                    }
                }
                pushNotif()
                updatePipActions()
            }
            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(
                    this@MediaPlayerActivity,
                    "Oynatılamadı: " + (error.message ?: "hata"),
                    Toast.LENGTH_LONG
                ).show()
            }
        })
        p.setMediaItems(items, start, 0L)
        p.repeatMode = Player.REPEAT_MODE_ALL
        p.prepare()
        p.playWhenReady = true
        playerView.player = p
        player = p
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

        registerControls()
        pushNotif()
        handler.post(progressTick)
        applyBars()
        if (Build.VERSION.SDK_INT >= 26) {
            try { setPictureInPictureParams(buildPipParams()) } catch (_: Exception) { }
        }
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
        // Her zaman yatay 16:9 (dikey telefon oranını kullanma)
        val ratio = Rational(16, 9)
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

    private fun enterPip() {
        if (Build.VERSION.SDK_INT < 26) {
            Toast.makeText(this, "Bu cihazda küçük pencere yok", Toast.LENGTH_SHORT).show()
            return
        }
        if (isInPictureInPictureMode || pipEntering) return
        pipEntering = true
        try {
            setPictureInPictureParams(buildPipParams())
            enterPictureInPictureMode(buildPipParams())
        } catch (_: Exception) {
            try { enterPictureInPictureMode() } catch (_: Exception) { }
        } finally {
            handler.postDelayed({ pipEntering = false }, 400)
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


    /** Dosyadan gömülü kapak çıkar → cache dosyası yolu */
    private fun artForPath(mediaPath: String): String {
        artCache[mediaPath]?.let { if (File(it).exists()) return it }
        return try {
            val r = android.media.MediaMetadataRetriever()
            r.setDataSource(mediaPath)
            val bytes = r.embeddedPicture
            r.release()
            if (bytes == null || bytes.isEmpty()) return ""
            val dir = File(cacheDir, "media_art").apply { mkdirs() }
            val out = File(dir, "art_" + mediaPath.hashCode().toUInt().toString(16) + ".jpg")
            if (!out.exists() || out.length() == 0L) {
                out.outputStream().use { it.write(bytes) }
            }
            val p = out.absolutePath
            artCache[mediaPath] = p
            p
        } catch (_: Exception) {
            ""
        }
    }

    private fun refreshArtForCurrent() {
        val p = player ?: return
        val idx = p.currentMediaItemIndex
        val path = try {
            p.currentMediaItem?.localConfiguration?.uri?.path
                ?: p.currentMediaItem?.localConfiguration?.uri?.toString()
        } catch (_: Exception) { null }
        // EXTRA paths üzerinden de dene
        val filePath = when {
            path != null && path.startsWith("/") && File(path).exists() -> path
            else -> {
                val uri = p.currentMediaItem?.localConfiguration?.uri
                val s = uri?.toString() ?: ""
                when {
                    s.startsWith("file:") -> {
                        try { Uri.parse(s).path } catch (_: Exception) { null }
                    }
                    else -> null
                }
            }
        }
        currentArtPath = if (!filePath.isNullOrBlank()) artForPath(filePath) else ""
    }

    private fun pushNotif() {
        val p = player ?: return
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
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ flash.visibility = View.GONE }, 700)
        handler.post(progressTick)
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
        try { playerView.player = null } catch (_: Exception) { }
        player?.release()
        player = null
        active = false
        try { PlaybackService.stop(this) } catch (_: Exception) { }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PATHS = "paths"
        const val EXTRA_TITLES = "titles"
        const val EXTRA_INDEX = "index"
        @Volatile var active = false
    }
}
