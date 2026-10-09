package id.example.iontvplayer

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.content.pm.ActivityInfo
import android.widget.*
import android.text.InputType
import androidx.core.view.setPadding
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

class MainActivity : Activity() {
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var urlInput: EditText
    private lateinit var refererInput: EditText
    private lateinit var originInput: EditText
    private lateinit var uaInput: EditText
    private lateinit var status: TextView
    private lateinit var root: LinearLayout

    private val bg = Color.rgb(16, 17, 20)
    private val panel = Color.rgb(29, 31, 37)
    private val accent = Color.rgb(66, 211, 146)
    private val white = Color.WHITE
    private val muted = Color.rgb(170, 175, 185)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        buildUi()
    }

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(16))
        }

        val title = TextView(this).apply {
            text = "ION TV PLAYER"
            textSize = 22f
            setTextColor(accent)
            setPadding(0, dp(8), 0, dp(4))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val subtitle = TextView(this).apply {
            text = "Pemutar streaming • HLS / M3U8"
            textSize = 13f
            setTextColor(muted)
        }
        root.addView(title)
        root.addView(subtitle)

        playerView = PlayerView(this).apply {
            useController = true
            setBackgroundColor(Color.BLACK)
        }
        root.addView(playerView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(220)
        ).apply { topMargin = dp(14); bottomMargin = dp(12) })

        urlInput = field("URL stream atau M3U8", "https://example.com/live.m3u8")
        refererInput = field("Referer (opsional)", "")
        originInput = field("Origin (opsional)", "")
        uaInput = field("User-Agent (opsional)", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")

        root.addView(urlInput)
        root.addView(refererInput)
        root.addView(originInput)
        root.addView(uaInput)

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val play = Button(this).apply {
            text = "PUTAR"
            setTextColor(Color.BLACK)
            setBackgroundColor(accent)
            setOnClickListener { playStream() }
        }
        val stop = Button(this).apply {
            text = "STOP"
            setOnClickListener {
                player?.stop()
                status.text = "Pemutaran dihentikan."
            }
        }
        buttons.addView(play, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginEnd = dp(8) })
        buttons.addView(stop, LinearLayout.LayoutParams(0, dp(50), 1f))
        root.addView(buttons)

        status = TextView(this).apply {
            text = "Masukkan URL stream yang kamu punya atau berhak akses."
            textSize = 12f
            setTextColor(muted)
            setPadding(0, dp(10), 0, dp(4))
        }
        root.addView(status)

        val hint = TextView(this).apply {
            text = "Catatan: playlist M3U perlu dipilih/dibuka sebagai URL playlist yang didukung; DRM memerlukan konfigurasi dan lisensi dari penyedia."
            textSize = 11f
            setTextColor(muted)
            setPadding(0, dp(6), 0, 0)
        }
        root.addView(hint)

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
    }

    private fun field(label: String, value: String): EditText {
        return EditText(this).apply {
            hint = label
            setText(value)
            setTextColor(white)
            setHintTextColor(muted)
            textSize = 13f
            singleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setPadding(dp(12))
            backgroundTintList = android.content.res.ColorStateList.valueOf(accent)
            setBackgroundColor(panel)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)
            )
            lp.topMargin = dp(7)
            layoutParams = lp
        }
    }

    private fun playStream() {
        val url = urlInput.text.toString().trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            status.text = "Masukkan URL HTTP/HTTPS yang valid."
            return
        }
        releasePlayer()

        try {
            val headers = mutableMapOf<String, String>()
            refererInput.text.toString().trim().takeIf { it.isNotEmpty() }?.let { headers["Referer"] = it }
            originInput.text.toString().trim().takeIf { it.isNotEmpty() }?.let { headers["Origin"] = it }
            uaInput.text.toString().trim().takeIf { it.isNotEmpty() }?.let { headers["User-Agent"] = it }

            val httpFactory = DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setDefaultRequestProperties(headers)

            player = ExoPlayer.Builder(this)
                .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(httpFactory))
                .build().also { exo ->
                    playerView.player = exo
                    val itemBuilder = MediaItem.Builder().setUri(url)
                    if (url.substringBefore("?").endsWith(".m3u8", true)) {
                        itemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
                    }
                    exo.setMediaItem(itemBuilder.build())
                    exo.addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            status.text = when (playbackState) {
                                Player.STATE_BUFFERING -> "Menghubungkan dan buffering…"
                                Player.STATE_READY -> "Stream siap diputar."
                                Player.STATE_ENDED -> "Stream selesai."
                                Player.STATE_IDLE -> "Siap."
                                else -> "Memutar…"
                            }
                        }
                        override fun onPlayerError(error: PlaybackException) {
                            status.text = "Gagal memutar: ${error.errorCodeName}. Periksa URL, koneksi, dan izin stream."
                        }
                    })
                    exo.prepare()
                    exo.playWhenReady = true
                }
        } catch (e: Exception) {
            status.text = "Kesalahan: ${e.message ?: "tidak diketahui"}"
        }
    }

    private fun releasePlayer() {
        playerView.player = null
        player?.release()
        player = null
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        releasePlayer()
        super.onDestroy()
    }
}
