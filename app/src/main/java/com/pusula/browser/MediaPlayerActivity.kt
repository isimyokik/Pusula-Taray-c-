package com.pusula.browser

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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

/** ExoPlayer tabanlı, kararlı uygulama içi video / müzik oynatıcı. */
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
        root.addView(playerView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Üst çubuk: geri + başlık (kontroller görününce görünür)
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
        close.setOnClickListener { finish() }
        topBar.addView(close, LinearLayout.LayoutParams(dp(52), dp(44)))
        titleView = TextView(this)
        titleView.textSize = 15f
        titleView.setTextColor(Color.WHITE)
        titleView.maxLines = 1
        titleView.ellipsize = android.text.TextUtils.TruncateAt.END
        topBar.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(12) })
        root.addView(topBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        flash = TextView(this)
        flash.textSize = 18f
        flash.setTextColor(Color.WHITE)
        flash.setBackgroundColor(0xAA000000.toInt())
        flash.setPadding(dp(18), dp(10), dp(18), dp(10))
        flash.visibility = View.GONE
        root.addView(flash, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        setContentView(root)

        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v ->
            topBar.visibility = if (v == View.VISIBLE || isAudio) View.VISIBLE else View.GONE
        })

        val attrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
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
                .setMediaMetadata(MediaMetadata.Builder().setTitle(titles.getOrElse(i) { File(path).name }).build())
                .build()
        }
        p.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                titleView.text = mediaItem?.mediaMetadata?.title ?: ""
            }
            override fun onTracksChanged(tracks: Tracks) {
                if (tracks.groups.isNotEmpty()) setAudioMode(!tracks.isTypeSelected(C.TRACK_TYPE_VIDEO))
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    // Tek öğe / kenar durum: tekrar başlat
                    try {
                        if (p.mediaItemCount > 0) {
                            p.seekToNextMediaItem()
                            p.playWhenReady = true
                            p.play()
                        }
                    } catch (_: Exception) {
                        try { p.seekTo(0, 0L); p.playWhenReady = true; p.play() } catch (_: Exception) { }
                    }
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(this@MediaPlayerActivity, "Oynatılamadı: " + (error.message ?: "hata"), Toast.LENGTH_LONG).show()
            }
        })
        p.setMediaItems(items, start, 0L)
        // Liste bitince başa sar; kullanıcı kapatana kadar çalmaya devam
        p.repeatMode = Player.REPEAT_MODE_ALL
        p.prepare()
        p.playWhenReady = true
        playerView.player = p
        player = p

        // Çift dokunma: sol yarı geri, sağ yarı ileri 10 sn
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

        applyBars()
    }

    private fun setAudioMode(audio: Boolean) {
        isAudio = audio
        playerView.setControllerShowTimeoutMs(if (audio) 0 else 3500)
        playerView.setControllerHideOnTouch(!audio)
        if (audio) {
            playerView.showController()
            topBar.visibility = View.VISIBLE
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
    }

    private fun makeArt(): Bitmap {
        val s = 720
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, s.toFloat(), s.toFloat(), 0xFF2F6BFF.toInt(), 0xFFFF4D9D.toInt(), Shader.TileMode.CLAMP)
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
        // Kilit / arka plan: durdurma. Kullanıcı ✕ ile çıkana kadar çalsın.
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try { playerView.player = null } catch (_: Exception) { }
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PATHS = "paths"
        const val EXTRA_TITLES = "titles"
        const val EXTRA_INDEX = "index"
    }
}
