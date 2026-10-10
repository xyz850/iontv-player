package id.example.iontvplayer

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.content.pm.ActivityInfo
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.view.WindowManager
import android.widget.*
import android.text.InputType
import androidx.core.view.setPadding
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.media3.ui.AspectRatioFrameLayout

class MainActivity : Activity() {
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var urlInput: EditText
    private lateinit var refererInput: EditText
    private lateinit var originInput: EditText
    private lateinit var uaInput: EditText
    private lateinit var status: TextView
    private lateinit var root: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var resolutionSpinner: Spinner
    private lateinit var orientationSpinner: Spinner
    private lateinit var brightnessBar: SeekBar

    private var fullscreen = false
    private var videoOptions: List<VideoOption> = emptyList()
    private var updatingResolution = false
    private var previousScreenBrightness = -1f

    private data class VideoOption(
        val label: String,
        val group: Tracks.Group,
        val trackIndex: Int
    )

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
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        root.addView(
            playerView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(220)
            ).apply {
                topMargin = dp(14)
                bottomMargin = dp(12)
            }
        )

        val settingsTitle = TextView(this).apply {
            text = "PENGATURAN LAYAR"
            setTextColor(accent)
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, dp(4), 0, dp(4))
        }
        root.addView(settingsTitle)

        val fullButton = Button(this).apply {
            text = "⛶ LAYAR PENUH"
            setOnClickListener { toggleFullscreen() }
        }
        root.addView(fullButton)

        orientationSpinner = addLabeledSpinner(
            root,
            "Orientasi",
            listOf("Otomatis", "Potret", "Lanskap")
        ) { position ->
            if (!fullscreen) applyOrientation(position)
        }

        addLabeledSpinner(
            root,
            "Ukuran video",
            listOf("Fit", "Fill", "Zoom")
        ) { position ->
            playerView.resizeMode = when (position) {
                1 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                2 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
        }

        resolutionSpinner = Spinner(this)
        resolutionSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("Resolusi: otomatis")
        )
        resolutionSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) {}

                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    if (updatingResolution) return
                    if (position == 0) {
                        player?.trackSelectionParameters =
                            player!!.trackSelectionParameters.buildUpon()
                                .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                                .build()
                    } else {
                        val option = videoOptions.getOrNull(position - 1)
                            ?: return
                        val exo = player ?: return
                        exo.trackSelectionParameters =
                            exo.trackSelectionParameters.buildUpon()
                                .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                                .addOverride(
                                    TrackSelectionOverride(
                                        option.group.mediaTrackGroup,
                                        listOf(option.trackIndex)
                                    )
                                )
                                .build()
                    }
                }
            }
        addLabeledView(root, "Resolusi video", resolutionSpinner)

        val brightnessLabel = TextView(this).apply {
            text = "Kecerahan layar"
            setTextColor(white)
            textSize = 13f
        }
        root.addView(brightnessLabel)

        brightnessBar = SeekBar(this).apply {
            max = 100
            progress = 100
            setOnSeekBarChangeListener(object :
                SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    val attributes = window.attributes
                    attributes.screenBrightness =
                        if (progress == 0) 0.01f else progress / 100f
                    window.attributes = attributes
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        root.addView(brightnessBar)

        urlInput = field("URL stream atau M3U8", "https://example.com/live.m3u8")
        refererInput = field("Referer (opsional)", "")
        originInput = field("Origin (opsional)", "")
        uaInput = field(
            "User-Agent (opsional)",
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
        )

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

        buttons.addView(
            play,
            LinearLayout.LayoutParams(0, dp(50), 1f).apply {
                marginEnd = dp(8)
            }
        )
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
            text = "Resolusi bergantung pada stream. DRM memerlukan konfigurasi dan lisensi dari penyedia."
            textSize = 11f
            setTextColor(muted)
            setPadding(0, dp(6), 0, 0)
        }
        root.addView(hint)

        scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }
        setContentView(scroll)
    }

    private fun addLabeledSpinner(
        parent: LinearLayout,
        label: String,
        options: List<String>,
        selected: (Int) -> Unit
    ): Spinner {
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            options
        )
        spinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) {}
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    selected(position)
                }
            }
        addLabeledView(parent, label, spinner)
        return spinner
    }

    private fun addLabeledView(
        parent: LinearLayout,
        label: String,
        view: View
    ) {
        val labelView = TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(white)
            setPadding(0, dp(5), 0, 0)
        }
        parent.addView(labelView)
        parent.addView(
            view,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
            )
        )
    }

    private fun applyOrientation(position: Int) {
        requestedOrientation = when (position) {
            1 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            2 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun toggleFullscreen() {
        if (!fullscreen) {
            fullscreen = true
            previousScreenBrightness = window.attributes.screenBrightness

            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

            val parent = playerView.parent as? ViewGroup
            parent?.removeView(playerView)

            fullscreenContainer = FrameLayout(this).apply {
                setBackgroundColor(Color.BLACK)
                addView(
                    playerView,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        Gravity.CENTER
                    )
                )
            }

            (window.decorView as ViewGroup).addView(
                fullscreenContainer,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )

            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        } else {
            fullscreen = false

            (fullscreenContainer.parent as? ViewGroup)
                ?.removeView(fullscreenContainer)

            fullscreenContainer.removeView(playerView)

            root.addView(
                playerView,
                2,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(220)
                ).apply {
                    topMargin = dp(14)
                    bottomMargin = dp(12)
                }
            )

            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            root.setPadding(dp(16))
            applyOrientation(orientationSpinner.selectedItemPosition)
        }
    }

    private fun field(label: String, value: String): EditText {
        return EditText(this).apply {
            hint = label
            setText(value)
            setTextColor(white)
            setHintTextColor(muted)
            textSize = 13f
            setSingleLine(true)
            inputType =
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setPadding(dp(12))
            backgroundTintList =
                android.content.res.ColorStateList.valueOf(accent)
            setBackgroundColor(panel)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)
            ).apply {
                topMargin = dp(7)
            }
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
            refererInput.text.toString().trim()
                .takeIf { it.isNotEmpty() }
                ?.let { headers["Referer"] = it }
            originInput.text.toString().trim()
                .takeIf { it.isNotEmpty() }
                ?.let { headers["Origin"] = it }
            uaInput.text.toString().trim()
                .takeIf { it.isNotEmpty() }
                ?.let { headers["User-Agent"] = it }

            val httpFactory = DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setDefaultRequestProperties(headers)

            player = ExoPlayer.Builder(this)
                .setMediaSourceFactory(
                    DefaultMediaSourceFactory(this)
                        .setDataSourceFactory(httpFactory)
                )
                .build()
                .also { exo ->
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
                            if (playbackState == Player.STATE_READY) {
                                updateVideoOptions(exo.currentTracks)
                            }
                        }

                        override fun onTracksChanged(tracks: Tracks) {
                            updateVideoOptions(tracks)
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            status.text =
                                "Gagal memutar: ${error.errorCodeName}. Periksa URL, koneksi, dan izin stream."
                        }
                    })
                    exo.prepare()
                    exo.playWhenReady = true
                }
        } catch (e: Exception) {
            status.text = "Kesalahan: ${e.message ?: "tidak diketahui"}"
        }
    }

    private fun updateVideoOptions(tracks: Tracks) {
        val options = mutableListOf<VideoOption>()

        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_VIDEO) continue

            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                if (!group.isTrackSupported(i)) continue
                if (format.width <= 0 || format.height <= 0) continue

                val label = "${format.height}p (${format.width}×${format.height})"
                if (options.none { it.label == label }) {
                    options.add(VideoOption(label, group, i))
                }
            }
        }

        videoOptions = options.sortedBy { it.group.getTrackFormat(it.trackIndex).height }

        updatingResolution = true
        val labels = listOf("Resolusi: otomatis") + videoOptions.map { it.label }
        resolutionSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            labels
        )
        resolutionSpinner.setSelection(0)
        updatingResolution = false

        if (videoOptions.isEmpty()) {
            status.text =
                "Stream siap. Pilihan resolusi manual tidak tersedia untuk stream ini."
        }
    }

    private fun releasePlayer() {
        if (::playerView.isInitialized) playerView.player = null
        player?.release()
        player = null
        videoOptions = emptyList()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onBackPressed() {
        if (fullscreen) {
            toggleFullscreen()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        releasePlayer()
        super.onDestroy()
    }
}
