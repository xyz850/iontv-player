package id.example.iontvplayer
import android.net.Uri
import android.util.Base64

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.graphics.BitmapFactory
import android.content.pm.ActivityInfo
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.view.WindowManager
import android.widget.*
import android.text.InputType
import androidx.core.view.setPadding
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.media3.ui.AspectRatioFrameLayout

class MainActivity : Activity() {
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var videoContainer: FrameLayout
    private lateinit var urlInput: EditText
    private lateinit var refererInput: EditText
    private lateinit var originInput: EditText
    private lateinit var uaInput: EditText
    private lateinit var status: TextView
    private lateinit var diagnosticStatus: TextView
    private lateinit var root: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var resolutionSpinner: Spinner
    private lateinit var orientationSpinner: Spinner
    private lateinit var sizeSpinner: Spinner
    private lateinit var brightnessBar: SeekBar
    private lateinit var channelSpinner: Spinner
    private lateinit var channelGrid: GridLayout
    private var playlistChannels: List<PlaylistEntry> = emptyList()
    private var loadedPlaylistUrl: String? = null
    private var selectedChannelCategory = "SEMUA"
    private var channelCategoryRefreshRunnable: Runnable? = null

    private val channelUrlStatuses = mutableMapOf<String, String>()
    private val channelUrlDetails = mutableMapOf<String, String>()
    private val channelUrlStatusViews =
        mutableMapOf<String, MutableList<TextView>>()
    private val channelUrlIndicatorViews =
        mutableMapOf<String, MutableList<View>>()
    private var channelUrlBlinkRunnable: Runnable? = null
    private var channelUrlBlinkVisible = true

    private var playlistRequestHeaders: Map<String, String> = emptyMap()
    private var urlScanInProgress = false
    private var urlMonitorRunnable: Runnable? = null

    private val urlCheckExecutor =
        java.util.concurrent.Executors.newFixedThreadPool(4)

    private val appPrefs by lazy {
        getSharedPreferences("iontv_settings", MODE_PRIVATE)
    }

    private var fullscreen = false
    private val controlsHandler = Handler(Looper.getMainLooper())
    private var hideControlsRunnable: Runnable? = null
    private var settingsPanelView: View? = null
    private var fullscreenControlsView: View? = null
    private var fullscreenPreviousRequestedOrientation =
        ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var fullscreenPreviousLayoutOrientation =
        android.content.res.Configuration.ORIENTATION_PORTRAIT
    private var fullscreenOriginalParent: ViewGroup? = null
    private var fullscreenOriginalIndex = -1
    private var fullscreenOriginalLayoutParams: ViewGroup.LayoutParams? = null
    private var videoOptions: List<VideoOption> = emptyList()
    private var updatingResolution = false
    private var previousScreenBrightness = -1f

    private data class VideoOption(
        val label: String,
        val group: Tracks.Group,
        val trackIndex: Int
    )

    private data class PlaylistEntry(
        val name: String,
        val url: String,
        val logoUrl: String? = null,
        val headers: Map<String, String> = emptyMap(),
        val drmScheme: String? = null,
        val drmLicenseUrl: String? = null,
        val drmLicenseHeaders: Map<String, String> = emptyMap()
    )

    private val fixedPlaylistUrl = "https://bit.ly/KPL203"
    private val bg = Color.rgb(5, 8, 18)
    private val panel = Color.rgb(16, 22, 36)
    private val accent = Color.rgb(0, 245, 255)
    private val white = Color.WHITE
    private val muted = Color.rgb(157, 185, 200)
    private var splashAnimator: android.animation.ValueAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg

        showSplash()

        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            splashAnimator?.cancel()
            splashAnimator = null
            buildUi()
            restoreSavedPlaylist()
        }, 1400)
    }

    private fun showSplash() {
        val splash = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(bg)
            setPadding(dp(24))
        }

        val logo = TextView(this).apply {
            text = "▶"
            textSize = 58f
            gravity = Gravity.CENTER
            setTextColor(accent)
        }
        splash.addView(
            logo,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val title = TextView(this).apply {
            text = "ION TV PLAYER"
            textSize = 23f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, dp(8), 0, dp(10))
        }
        splash.addView(title)

        val message = TextView(this).apply {
            text = "Menyiapkan pemutar..."
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(muted)
            setPadding(0, 0, 0, dp(20))
        }
        splash.addView(message)

        val loadingLine = object : View(this) {
            var phase = 0f
            private val paint = android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG
            )

            override fun onDraw(canvas: android.graphics.Canvas) {
                super.onDraw(canvas)
                val left = dp(4).toFloat()
                val right = width - dp(4).toFloat()
                val top = (height - dp(6)) / 2f
                val bottom = top + dp(6)
                val track = android.graphics.RectF(left, top, right, bottom)

                paint.clearShadowLayer()
                paint.color = Color.rgb(20, 38, 54)
                canvas.drawRoundRect(track, dp(3).toFloat(), dp(3).toFloat(), paint)

                val colors = intArrayOf(
                    Color.rgb(0, 245, 255),
                    Color.rgb(255, 54, 209),
                    Color.rgb(75, 160, 255)
                )
                val total = right - left
                val block = total * 0.42f
                val start = left + (total + block) * phase - block
                val segment = block / colors.size

                canvas.save()
                canvas.clipRect(track)
                for (i in colors.indices) {
                    paint.color = colors[i]
                    paint.setShadowLayer(dp(6).toFloat(), 0f, 0f, colors[i])
                    val rect = android.graphics.RectF(
                        start + i * segment, top,
                        start + (i + 1) * segment, bottom
                    )
                    canvas.drawRoundRect(
                        rect, dp(3).toFloat(), dp(3).toFloat(), paint
                    )
                }
                paint.clearShadowLayer()
                canvas.restore()
            }
        }.apply {
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        }

        splash.addView(
            loadingLine,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(20)
            )
        )

        val neonCaption = TextView(this).apply {
            text = "CYBER NEON  •  STREAM PLAYER"
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(75, 160, 255))
            letterSpacing = 0.10f
            setPadding(0, dp(14), 0, 0)
        }
        splash.addView(neonCaption)

        setContentView(splash)
        splashAnimator?.cancel()
        splashAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1250L
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                loadingLine.phase = it.animatedValue as Float
                loadingLine.invalidate()
            }
            start()
        }
    }

    private fun restoreSavedPlaylist() {
        // Sumber playlist ditetapkan oleh aplikasi, bukan URL yang tersimpan.
        val savedPlaylistUrl = fixedPlaylistUrl
        urlInput.setText(savedPlaylistUrl)

        appPrefs.edit()
            .putString("playlist_url", savedPlaylistUrl)
            .apply()

        urlInput.post {
            refererInput.setText(appPrefs.getString("referer", "") ?: "")
            originInput.setText(appPrefs.getString("origin", "") ?: "")
            uaInput.setText(
                appPrefs.getString(
                    "user_agent",
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
                ) ?: ""
            )

            status.text = "Memuat daftar channel tersimpan…"
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

            loadPlaylist(savedPlaylistUrl, headers)
        }
    }

    override fun onConfigurationChanged(
        newConfig: android.content.res.Configuration
    ) {
        super.onConfigurationChanged(newConfig)

        if (fullscreen || !::playerView.isInitialized) return

        val oldPlayerView = playerView
        val currentPlayer = player
        val savedPosition = currentPlayer?.currentPosition ?: 0L
        val shouldResume = currentPlayer?.playWhenReady ?: false
        val savedResizeMode = oldPlayerView.resizeMode

        val selectedChannelIndex =
            if (::channelSpinner.isInitialized) {
                channelSpinner.selectedItemPosition
            } else {
                0
            }

        // Hentikan sementara pemutaran saat permukaan video diganti.
        currentPlayer?.playWhenReady = false
        oldPlayerView.player = null

        // Susun ulang UI sesuai orientasi perangkat.
        buildUi()
        playerView.resizeMode = savedResizeMode

        if (playlistChannels.isNotEmpty()) {
            renderChannelGrid(playlistChannels)

            channelSpinner.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                playlistChannels.map { it.name }
            )

            channelSpinner.setSelection(
                selectedChannelIndex.coerceIn(
                    0,
                    playlistChannels.lastIndex
                )
            )
        }

        // Pasang player setelah tampilan baru siap, lalu lanjutkan video.
        playerView.post {
            playerView.player = currentPlayer

            if (currentPlayer != null) {
                if (currentPlayer.currentMediaItem != null) {
                    currentPlayer.seekTo(savedPosition)
                }
                currentPlayer.playWhenReady = shouldResume
            }
        }
    }

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(16))
        }

        playerView = PlayerView(this).apply {
            useController = true
            controllerShowTimeoutMs = 4000
            setBackgroundColor(Color.BLACK)
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        videoContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(
                playerView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }

        val settingsPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(panel)
                setStroke(dp(1), accent)
                cornerRadius = dp(8).toFloat()
            }
            setPadding(dp(4))
            elevation = dp(3).toFloat()
        }
        settingsPanelView = settingsPanel

        val controlsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
            clipToPadding = false
            setPadding(dp(2), 0, dp(2), 0)
        }
        val controlsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(44)
            )
        }
        controlsScroll.addView(
            controlsRow,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(44)
            )
        )
        settingsPanel.addView(
            controlsScroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(44)
            )
        )

        fun addControl(view: View, width: Int) {
            val column = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(2), 0, dp(2), 0)
            }
            column.addView(
                view,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(40)
                )
            )
            controlsRow.addView(
                column,
                LinearLayout.LayoutParams(dp(width), dp(44))
            )
        }

        fun addNeonControl(
            symbol: String,
            label: String,
            description: String,
            action: () -> Unit
        ): LinearLayout {
            val control = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                contentDescription = description
                background =
                    android.graphics.drawable.GradientDrawable().apply {
                        setColor(Color.rgb(16, 22, 36))
                        setStroke(dp(1), accent)
                        cornerRadius = dp(7).toFloat()
                    }
                setOnClickListener { action() }
            }

            if (label == "keluar") {
                control.addView(
                    android.widget.ImageView(this).apply {
                        setImageResource(R.drawable.ic_power)
                        imageTintList =
                            android.content.res.ColorStateList.valueOf(accent)
                        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                        contentDescription = description
                    },
                    LinearLayout.LayoutParams(dp(21), dp(21))
                )
            } else {
                control.addView(
                    TextView(this).apply {
                        text = symbol
                        textSize = 16f
                        gravity = Gravity.CENTER
                        includeFontPadding = false
                        setTextColor(accent)
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(21)
                    )
                )
            }

            control.addView(
                TextView(this).apply {
                    text = label
                    textSize = 9f
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    setTextColor(accent)
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(14)
                )
            )

            controlsRow.addView(
                control,
                LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                    marginStart = dp(1)
                    marginEnd = dp(1)
                }
            )
            return control
        }

        addNeonControl(
            "⛶", "fullscreen", "Buka layar penuh"
        ) {
            toggleFullscreen()
        }

        lateinit var sizeButton: LinearLayout
        sizeButton = addNeonControl(
            "▣", "ukuran", "Pilih ukuran video"
        ) {
            val popup = android.widget.PopupMenu(this, sizeButton)
            listOf("Fit", "Fill", "Zoom").forEachIndexed { index, title ->
                popup.menu.add(title).setOnMenuItemClickListener {
                    sizeSpinner.setSelection(index)
                    true
                }
            }
            popup.show()
        }

        addNeonControl(
            "↻", "refresh", "Muat ulang stream saat ini"
        ) {
            val exo = player
            if (exo != null && exo.currentMediaItem != null) {
                status.text = "Memuat ulang stream..."
                exo.seekToDefaultPosition()
                exo.prepare()
                exo.playWhenReady = true
            } else {
                playStream()
            }
        }

        addNeonControl(
            " ", "keluar", "Keluar dari aplikasi"
        ) {
            finish()
        }

        fun addSpinnerControl(label: String, spinner: Spinner, width: Int) {
            val column = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                visibility = View.GONE
            }
            if (label.isNotBlank()) {
                column.addView(TextView(this).apply {
                    text = label
                    textSize = 10f
                    setTextColor(white)
                })
            }
            column.addView(spinner, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40)
            ))
            controlsRow.addView(column, LinearLayout.LayoutParams(
                dp(width), dp(44)
            ))
        }

        orientationSpinner = Spinner(this).apply {
            adapter = compactSpinnerAdapter(
                listOf("Otomatis", "Potret", "Lanskap")
            )
            onItemSelectedListener =
                object : AdapterView.OnItemSelectedListener {
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                    override fun onItemSelected(
                        parent: AdapterView<*>?, view: View?,
                        position: Int, id: Long
                    ) {
                        if (!fullscreen) applyOrientation(position)
                    }
                }
        }
        addSpinnerControl("", orientationSpinner, 100)

        sizeSpinner = Spinner(this).apply {
            adapter = compactSpinnerAdapter(
                listOf("Fit", "Fill", "Zoom")
            )
            onItemSelectedListener =
                object : AdapterView.OnItemSelectedListener {
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                    override fun onItemSelected(
                        parent: AdapterView<*>?, view: View?,
                        position: Int, id: Long
                    ) {
                        // Fit dan Fill menampilkan seluruh gambar tanpa crop.
                        // Fill membuka fullscreen setelah event spinner selesai.
                        playerView.resizeMode = when (position) {
                            1 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                            2 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }

                        playerView.post {
                            playerView.requestLayout()
                            playerView.invalidate()

                            if (position == 1 && !fullscreen) {
                                toggleFullscreen()
                            }
                        }
                    }
                }
        }
        sizeSpinner.visibility = View.GONE

        resolutionSpinner = Spinner(this).apply {
            adapter = compactSpinnerAdapter(listOf("Otomatis"))
            onItemSelectedListener =
                object : AdapterView.OnItemSelectedListener {
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                    override fun onItemSelected(
                        parent: AdapterView<*>?, view: View?,
                        position: Int, id: Long
                    ) {
                        if (updatingResolution) return
                        val exo = player ?: return
                        if (position == 0) {
                            exo.trackSelectionParameters =
                                exo.trackSelectionParameters.buildUpon()
                                    .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                                    .build()
                        } else {
                            val option = videoOptions.getOrNull(position - 1)
                                ?: return
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
        }
        addSpinnerControl("", resolutionSpinner, 104)

        val brightnessColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        brightnessBar = SeekBar(this).apply {
            max = 100
            progress = 100
            setOnSeekBarChangeListener(object :
                SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?, progress: Int, fromUser: Boolean
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
        brightnessColumn.addView(brightnessBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(40)
        ))
        controlsRow.addView(brightnessColumn, LinearLayout.LayoutParams(
            dp(112), dp(44)
        ))
        brightnessColumn.visibility = View.GONE

        urlInput = field("URL playlist atau stream", "https://bit.ly/KPL203")
        refererInput = field("Referer (opsional)", "")
        originInput = field("Origin (opsional)", "")
        uaInput = field(
            "User-Agent (opsional)",
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
        )

        val sourcePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(4))
            visibility = View.GONE
        }

        sourcePanel.visibility = View.GONE

        // Sumber dan playlist tetap tersedia tanpa tombol lipat.
        settingsPanel.addView(sourcePanel)

        val sourceHint = TextView(this).apply {
            visibility = View.GONE
        }

        sourcePanel.addView(sourceHint)
        sourcePanel.addView(urlInput)
        sourcePanel.addView(refererInput)
        sourcePanel.addView(originInput)
        sourcePanel.addView(uaInput)

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val play = Button(this).apply {
            text = "PUTAR / MUAT PLAYLIST"
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
            LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                marginEnd = dp(4)
            }
        )
        buttons.addView(stop, LinearLayout.LayoutParams(0, dp(40), 1f))
        sourcePanel.addView(buttons)

        settingsPanel.visibility = View.VISIBLE

        val channelContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Panel kontrol dipasang di luar area scroll channel.

        val channelLabel = TextView(this).apply {
            visibility = View.GONE
            text = "DAFTAR CHANNEL"
            textSize = 13f
            setTextColor(accent)
            setPadding(0, dp(10), 0, dp(3))
        }
        channelContent.addView(channelLabel)

        channelSpinner = Spinner(this).apply {
            visibility = View.GONE
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("Playlist belum dimuat")
            )
        }
        channelContent.addView(
            channelSpinner,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
            )
        )

        val screenWidthDp = resources.configuration.screenWidthDp
        val columns = when {
            screenWidthDp < 420 -> 2
            screenWidthDp < 650 -> 3
            else -> 5
        }

        channelGrid = GridLayout(this).apply {
            columnCount = columns
            alignmentMode = GridLayout.ALIGN_MARGINS
            useDefaultMargins = false
        }
        channelContent.addView(
            channelGrid,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        status = TextView(this).apply {
            text = "Masukkan URL stream yang kamu punya atau berhak akses."
            textSize = 12f
            setTextColor(muted)
            setPadding(0, dp(10), 0, dp(4))
        }
        channelContent.addView(status)

        diagnosticStatus = TextView(this).apply {
            text = "Diagnosis: pilih channel untuk mulai."
            textSize = 11f
            setTextColor(Color.WHITE)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setBackgroundColor(Color.rgb(38, 42, 50))
        }

        val hint = TextView(this).apply {
            text = "Resolusi bergantung pada stream. DRM memerlukan konfigurasi dan lisensi dari penyedia."
            textSize = 11f
            setTextColor(muted)
            setPadding(0, dp(6), 0, 0)
        }
        channelContent.addView(hint)

        channelContent.visibility = View.VISIBLE

        scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(channelContent)
        }

        swipeRefreshLayout = SwipeRefreshLayout(this).apply {
            setColorSchemeColors(accent)
            addView(
                scroll,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            setOnRefreshListener {
                val url = (loadedPlaylistUrl
                    ?: fixedPlaylistUrl).trim()

                if (url.isBlank()) {
                    isRefreshing = false
                    status.text = "Playlist belum tersedia."
                } else {
                    val headers = linkedMapOf<String, String>()
                    refererInput.text.toString().trim()
                        .takeIf { it.isNotEmpty() }
                        ?.let { headers["Referer"] = it }
                    originInput.text.toString().trim()
                        .takeIf { it.isNotEmpty() }
                        ?.let { headers["Origin"] = it }
                    uaInput.text.toString().trim()
                        .takeIf { it.isNotEmpty() }
                        ?.let { headers["User-Agent"] = it }

                    loadPlaylist(url, headers)
                }
            }
        }
        val channelCategoryRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(5), dp(4), dp(5), dp(4))
        }

        fun makeCategoryButton(title: String, description: String): TextView {
            return TextView(this).apply {
                text = title
                textSize = 9f
                gravity = Gravity.CENTER
                setTypeface(null, android.graphics.Typeface.BOLD)
                isClickable = true
                isFocusable = true
                contentDescription = description
                setPadding(dp(1), 0, dp(1), 0)
            }
        }

        val allFolder = makeCategoryButton(
            "SEMUA", "Tampilkan semua channel playlist"
        )
        val onlineFolder = makeCategoryButton(
            "ONLINE", "Tampilkan URL ONLINE"
        )
        val offlineFolder = makeCategoryButton(
            "OFFLINE", "Tampilkan URL OFFLINE"
        )
        val checkingFolder = makeCategoryButton(
            "CEK", "Tampilkan URL yang sedang diperiksa"
        )

        val updateFolderStyles = {
            val cyan = Color.rgb(0, 245, 255)
            val magenta = Color.rgb(255, 54, 209)
            val blueNeon = Color.rgb(75, 160, 255)
            val muted = Color.rgb(145, 160, 175)

            val items = listOf(
                Triple(allFolder, "SEMUA", cyan),
                Triple(onlineFolder, "ONLINE", cyan),
                Triple(offlineFolder, "OFFLINE", magenta),
                Triple(checkingFolder, "MEMERIKSA", blueNeon)
            )

            items.forEach { (view, category, color) ->
                val selected = selectedChannelCategory == category
                view.setTextColor(if (selected) color else muted)
                view.background =
                    android.graphics.drawable.GradientDrawable().apply {
                        shape =
                            android.graphics.drawable.GradientDrawable.RECTANGLE
                        cornerRadius = dp(7).toFloat()
                        setColor(
                            if (selected) Color.rgb(9, 24, 36)
                            else Color.rgb(17, 24, 36)
                        )
                        setStroke(
                            dp(1),
                            if (selected) color else Color.rgb(0, 75, 95)
                        )
                    }
            }
        }

        allFolder.setOnClickListener {
            selectedChannelCategory = "SEMUA"
            updateFolderStyles()
            renderChannelGrid(playlistChannels)
        }

        onlineFolder.setOnClickListener {
            selectedChannelCategory = "ONLINE"
            updateFolderStyles()
            renderChannelGrid(playlistChannels)
        }

        offlineFolder.setOnClickListener {
            selectedChannelCategory = "OFFLINE"
            updateFolderStyles()
            renderChannelGrid(playlistChannels)
        }

        checkingFolder.setOnClickListener {
            selectedChannelCategory = "MEMERIKSA"
            updateFolderStyles()
            renderChannelGrid(playlistChannels)
        }

        listOf(allFolder, onlineFolder, offlineFolder, checkingFolder)
            .forEachIndexed { index, view ->
                channelCategoryRow.addView(
                    view,
                    LinearLayout.LayoutParams(0, dp(34), 1f).apply {
                        if (index < 3) marginEnd = dp(3)
                    }
                )
            }

        updateFolderStyles()

        val landscapeLayout =
            resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE

        val mainContent = LinearLayout(this).apply {
            orientation = if (landscapeLayout) {
                LinearLayout.HORIZONTAL
            } else {
                LinearLayout.VERTICAL
            }
        }

        if (landscapeLayout) {
            // Kontrol tetap di atas daftar channel pada sisi kiri.
            val channelSide = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
            channelSide.addView(
                settingsPanel,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            channelSide.addView(
                channelCategoryRow,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            channelSide.addView(
                swipeRefreshLayout,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
                )
            )
            mainContent.addView(
                channelSide,
                LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, 0.42f)
            )
            mainContent.addView(
                videoContainer,
                LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, 0.58f)
            )
        } else {
            // Potret: video, kontrol, lalu daftar channel yang bisa digulir.
            mainContent.addView(
                videoContainer,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(220)
                ).apply {
                    bottomMargin = dp(4)
                }
            )
            mainContent.addView(
                settingsPanel,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            mainContent.addView(
                channelCategoryRow,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            mainContent.addView(
                swipeRefreshLayout,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
                )
            )
        }

        root.addView(
            mainContent,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )

        setContentView(root)
        settingsPanelView?.let { keepControlsVisibleWhileTouching(it) }

        playerView.setOnClickListener {
            showPlaybackControls()
        }
        showPlaybackControls()

        // Simpan URL setiap kali pengguna mengubah kolom playlist.
        // Simpan header jaringan setiap kali nilainya berubah.
        fun watchAndSaveHeader(
            input: EditText,
            preferenceKey: String
        ) {
            input.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?, start: Int, count: Int, after: Int
                ) {}

                override fun onTextChanged(
                    s: CharSequence?, start: Int, before: Int, count: Int
                ) {
                    appPrefs.edit()
                        .putString(preferenceKey, s?.toString().orEmpty())
                        .apply()
                }

                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }

        watchAndSaveHeader(refererInput, "referer")
        watchAndSaveHeader(originInput, "origin")
        watchAndSaveHeader(uaInput, "user_agent")

        urlInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(
                s: CharSequence?, start: Int, count: Int, after: Int
            ) {}

            override fun onTextChanged(
                s: CharSequence?, start: Int, before: Int, count: Int
            ) {
                appPrefs.edit()
                    .putString("playlist_url", s?.toString().orEmpty())
                    .putString("referer", refererInput.text.toString())
                    .putString("origin", originInput.text.toString())
                    .putString("user_agent", uaInput.text.toString())
                    .apply()
            }

            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }


    private fun startChannelUrlBlinking() {
        if (channelUrlBlinkRunnable != null) return

        val task = object : Runnable {
            override fun run() {
                if (isFinishing || isDestroyed) return

                channelUrlBlinkVisible = !channelUrlBlinkVisible
                val visibleRect = android.graphics.Rect()

                channelUrlIndicatorViews.values.forEach { indicators ->
                    indicators.forEach { dot ->
                        val isVisible = dot.isShown &&
                            dot.getGlobalVisibleRect(visibleRect)

                        dot.alpha = if (!isVisible) {
                            1f
                        } else if (channelUrlBlinkVisible) {
                            1f
                        } else {
                            0.28f
                        }
                    }
                }

                controlsHandler.postDelayed(this, 550L)
            }
        }

        channelUrlBlinkRunnable = task
        controlsHandler.post(task)
    }

    private fun updateChannelUrlStatus(
        url: String,
        state: String,
        detail: String = ""
    ) {
        val normalized = when (state) {
            "ONLINE", "OFFLINE" -> state
            else -> "MEMERIKSA"
        }

        val previousStatus = channelUrlStatuses[url]
        channelUrlStatuses[url] = normalized
        channelUrlDetails[url] = detail

        // Jangan render ulang seluruh daftar saat status URL berubah.
        // Ini menjaga posisi daftar dan pilihan channel tetap stabil.

        val neonColor = when (normalized) {
            "ONLINE" -> Color.rgb(0, 245, 255)   // Cyan neon
            "OFFLINE" -> Color.rgb(255, 54, 209) // Magenta neon
            else -> Color.rgb(255, 215, 64)      // Kuning neon
        }

        val badgeFill = when (normalized) {
            "ONLINE" -> Color.rgb(5, 30, 42)
            "OFFLINE" -> Color.rgb(38, 13, 42)
            else -> Color.rgb(48, 39, 12)
        }

        channelUrlStatusViews[url]?.forEach { badge ->
            badge.text = normalized
            badge.setTextColor(neonColor)
            badge.contentDescription =
                "Status URL $normalized" +
                    if (detail.isBlank()) "" else ": $detail"
        }

        channelUrlIndicatorViews[url]?.forEach { indicator ->
            indicator.background =
                android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(neonColor)
                }
            indicator.contentDescription = "Indikator $normalized"
            indicator.alpha = 1f
        }

        // Warna latar baris status mengikuti status aktif.
        channelUrlStatusViews[url]?.forEach { badge ->
            val row = badge.parent
            if (row is android.view.View) {
                row.background =
                    android.graphics.drawable.GradientDrawable().apply {
                        shape =
                            android.graphics.drawable.GradientDrawable.RECTANGLE
                        cornerRadius = dp(5).toFloat()
                        setColor(badgeFill)
                        setStroke(dp(1), neonColor)
                    }
            }
        }
    }

    private fun scheduleChannelCategoryRefresh() {
        if (channelCategoryRefreshRunnable != null) return

        val task = Runnable {
            channelCategoryRefreshRunnable = null
            if (!isFinishing && !isDestroyed &&
                ::channelGrid.isInitialized && playlistChannels.isNotEmpty()) {
                renderChannelGrid(playlistChannels)
            }
        }

        channelCategoryRefreshRunnable = task
        controlsHandler.postDelayed(task, 650L)
    }

    private fun checkChannelUrl(entry: PlaylistEntry): Pair<String, String> {
        var connection: java.net.HttpURLConnection? = null

        return try {
            connection = (java.net.URL(entry.url).openConnection()
                as java.net.HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 8000
                readTimeout = 8000
                requestMethod = "GET"
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Range", "bytes=0-0")

                val headers = linkedMapOf<String, String>()
                headers.putAll(playlistRequestHeaders)
                headers.putAll(entry.headers)

                if (headers.keys.none {
                        it.equals("User-Agent", ignoreCase = true)
                    }) {
                    setRequestProperty(
                        "User-Agent",
                        "Mozilla/5.0 (Android) IONTVPlayer"
                    )
                }

                headers.forEach { (name, value) ->
                    setRequestProperty(name, value)
                }
            }

            val code = connection.responseCode
            if (code in 200..399 || code == 416) {
                "ONLINE" to "HTTP $code"
            } else {
                "OFFLINE" to "HTTP $code"
            }
        } catch (e: Exception) {
            "OFFLINE" to (e.message ?: e.javaClass.simpleName)
        } finally {
            connection?.disconnect()
        }
    }

    private fun scanPlaylistUrls() {
        if (urlScanInProgress || playlistChannels.isEmpty()) return

        val entries = playlistChannels.distinctBy { it.url }
        if (entries.isEmpty()) return

        urlScanInProgress = true

        entries.forEach { entry ->
            updateChannelUrlStatus(
                entry.url,
                "MEMERIKSA",
                "Pemeriksaan URL sedang berjalan"
            )
        }

        // Segarkan kategori agar URL yang sedang diperiksa langsung terlihat.
        scheduleChannelCategoryRefresh()

        val remaining =
            java.util.concurrent.atomic.AtomicInteger(entries.size)

        entries.forEach { entry ->
            try {
                urlCheckExecutor.execute {
                    val result = checkChannelUrl(entry)

                    runOnUiThread {
                        if (!isFinishing && !isDestroyed &&
                            playlistChannels.any { it.url == entry.url }) {
                            updateChannelUrlStatus(
                                entry.url,
                                result.first,
                                result.second
                            )
                        }

                        if (remaining.decrementAndGet() == 0) {
                            urlScanInProgress = false
                        }
                    }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                if (remaining.decrementAndGet() == 0) {
                    urlScanInProgress = false
                }
            }
        }
    }

    private fun startUrlMonitoring() {
        urlMonitorRunnable?.let { controlsHandler.removeCallbacks(it) }

        val task = object : Runnable {
            override fun run() {
                if (isFinishing || isDestroyed) return

                // Pemindaian massal dinonaktifkan agar tidak membebani
                // jaringan ketika video sedang diputar.
                controlsHandler.postDelayed(this, 5 * 60 * 1000L)
            }
        }

        urlMonitorRunnable = task
        controlsHandler.post(task)
    }

    private fun renderChannelGrid(channels: List<PlaylistEntry>) {
        channelGrid.removeAllViews()
        channelUrlStatusViews.clear()
        channelUrlIndicatorViews.clear()
        channelGrid.columnCount = 1

        val screenWidthDp = resources.configuration.screenWidthDp
        val landscapeLayout =
            resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val cardWidth = if (landscapeLayout) {
            dp((screenWidthDp * 0.42f - 28).toInt().coerceAtLeast(220))
        } else {
            dp((screenWidthDp - 24).coerceAtLeast(240))
        }

        val visibleChannels = channels.filter { entry ->
            selectedChannelCategory == "SEMUA" ||
                (channelUrlStatuses[entry.url] ?: "MEMERIKSA") ==
                    selectedChannelCategory
        }

        if (visibleChannels.isEmpty()) {
            val pending = channels.any { entry ->
                (channelUrlStatuses[entry.url] ?: "MEMERIKSA") == "MEMERIKSA"
            }

            val emptyMessage = TextView(this).apply {
                text = when {
                    selectedChannelCategory == "MEMERIKSA" && pending ->
                        "URL sedang diperiksa..."
                    selectedChannelCategory == "MEMERIKSA" ->
                        "Tidak ada URL yang sedang diperiksa."
                    pending ->
                        "Sedang memeriksa URL..."
                    selectedChannelCategory == "ONLINE" ->
                        "Belum ada URL ONLINE yang terdeteksi."
                    else ->
                        "Belum ada URL OFFLINE yang terdeteksi."
                }
                textSize = 12f
                setTextColor(
                    when (selectedChannelCategory) {
                        "ONLINE" -> Color.rgb(0, 245, 255)
                        "OFFLINE" -> Color.rgb(255, 54, 209)
                        else -> Color.rgb(75, 160, 255)
                    }
                )
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(14), dp(8), dp(14))
            }

            channelGrid.addView(
                emptyMessage,
                GridLayout.LayoutParams().apply {
                    width = cardWidth
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                    setMargins(dp(3), dp(3), dp(3), dp(3))
                }
            )
            return
        }

        visibleChannels.forEach { entry ->
            val index = playlistChannels.indexOf(entry)
                .takeIf { it >= 0 }
                ?: playlistChannels.indexOfFirst { it.url == entry.url }

            if (index < 0) return@forEach
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(6), dp(4), dp(6), dp(4))
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = dp(10).toFloat()
                    setColor(Color.rgb(8, 17, 31))
                    setStroke(dp(1), Color.rgb(0, 190, 220))
                }
                elevation = dp(1).toFloat()
                isClickable = true
                isFocusable = true
            }

            val logo = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply {
                    marginEnd = dp(8)
                }
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageResource(android.R.drawable.ic_menu_gallery)
                contentDescription = "Logo ${entry.name}"
            }
            card.addView(logo)

            val name = TextView(this).apply {
                text = entry.name
                textSize = 12f
                setTextColor(Color.rgb(220, 248, 255))
                gravity = Gravity.CENTER_VERTICAL
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            card.addView(
                name,
                LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
            )

            val statusRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(7), dp(4), dp(7), dp(4))
            }

            val statusDot = View(this).apply {
                background =
                    android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(Color.rgb(255, 215, 64))
                    }
                contentDescription = "Indikator status URL"
                elevation = dp(4).toFloat()
            }

            statusRow.addView(
                statusDot,
                LinearLayout.LayoutParams(dp(9), dp(9)).apply {
                    marginEnd = dp(6)
                }
            )

            val statusBadge = TextView(this).apply {
                textSize = 9f
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER_VERTICAL
                isSingleLine = true
            }
            statusRow.addView(statusBadge)
            card.addView(statusRow)

            channelUrlIndicatorViews.getOrPut(entry.url) {
                mutableListOf()
            }.add(statusDot)

            channelUrlStatusViews.getOrPut(entry.url) {
                mutableListOf()
            }.add(statusBadge)

            startChannelUrlBlinking()

            updateChannelUrlStatus(
                entry.url,
                channelUrlStatuses[entry.url] ?: "MEMERIKSA",
                channelUrlDetails[entry.url] ?: "Menunggu pemeriksaan"
            )

            val playSelectedChannel = {
                channelSpinner.setSelection(index)
                playStream()
            }

            card.setOnClickListener { playSelectedChannel() }
            logo.setOnClickListener { playSelectedChannel() }
            name.setOnClickListener { playSelectedChannel() }

            val params = GridLayout.LayoutParams().apply {
                width = cardWidth
                height = dp(48)
                setMargins(dp(3), dp(3), dp(3), dp(3))
            }
            channelGrid.addView(card, params)

            val logoUrl = entry.logoUrl
            if (!logoUrl.isNullOrBlank()) {
                Thread {
                    var connection: java.net.HttpURLConnection? = null
                    try {
                        connection = java.net.URL(logoUrl).openConnection()
                            as java.net.HttpURLConnection
                        connection.connectTimeout = 8000
                        connection.readTimeout = 8000
                        connection.instanceFollowRedirects = true
                        connection.setRequestProperty(
                            "User-Agent",
                            "Mozilla/5.0 (Android) IONTVPlayer"
                        )

                        val bitmap = connection.inputStream.use {
                            BitmapFactory.decodeStream(it)
                        }

                        if (bitmap != null) {
                            runOnUiThread {
                                if (!isFinishing && logo.parent != null) {
                                    logo.setImageBitmap(bitmap)
                                }
                            }
                        }
                    } catch (_: Exception) {
                        // Gunakan ikon pengganti jika logo gagal dimuat.
                    } finally {
                        connection?.disconnect()
                    }
                }.start()
            }
        }
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

    private fun scheduleControlsAutoHide() {
        hideControlsRunnable?.let { controlsHandler.removeCallbacks(it) }

        val task = Runnable {
            if (fullscreen) {
                fullscreenControlsView?.visibility = View.GONE
            } else {
                settingsPanelView?.visibility = View.GONE
            }
            if (::playerView.isInitialized) {
                playerView.hideController()
            }
        }
        hideControlsRunnable = task
        controlsHandler.postDelayed(task, 3000L)
    }

    private fun showPlaybackControls() {
        if (fullscreen) {
            fullscreenControlsView?.visibility = View.VISIBLE
        } else {
            settingsPanelView?.visibility = View.VISIBLE
        }

        if (::playerView.isInitialized) {
            playerView.showController()
        }
        scheduleControlsAutoHide()
    }

    private fun keepControlsVisibleWhileTouching(view: View) {
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN,
                android.view.MotionEvent.ACTION_MOVE,
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL ->
                    scheduleControlsAutoHide()
            }
            false
        }

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                keepControlsVisibleWhileTouching(view.getChildAt(i))
            }
        }
    }

    private fun toggleFullscreen() {
        if (!fullscreen) {
            val parent = videoContainer.parent as? ViewGroup
            if (parent == null) {
                status.text = "Tidak dapat membuka layar penuh."
                return
            }

            fullscreenPreviousRequestedOrientation = requestedOrientation
            fullscreenOriginalParent = parent
            fullscreenOriginalIndex = parent.indexOfChild(videoContainer)
            fullscreenOriginalLayoutParams = videoContainer.layoutParams
            previousScreenBrightness = window.attributes.screenBrightness
            fullscreenPreviousLayoutOrientation =
                resources.configuration.orientation

            parent.removeView(videoContainer)

            fullscreenContainer = FrameLayout(this).apply {
                setBackgroundColor(Color.BLACK)
                addView(
                    videoContainer,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        Gravity.CENTER
                    )
                )
            }

            // Semua kontrol fullscreen berada dalam satu lapisan.
            val fullscreenControls = FrameLayout(this).apply {
                setBackgroundColor(Color.TRANSPARENT)
            }

            // Bilah kontrol horizontal di bagian atas.
            val toolbar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), 0, dp(8), 0)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.rgb(5, 8, 18))
                    setStroke(dp(1), accent)
                    cornerRadius = dp(8).toFloat()
                }
            }

            fun makeButton(label: String, description: String): Button {
                return Button(this).apply {
                    text = label
                    contentDescription = description
                    textSize = 20f
                    setTextColor(accent)
                    setBackgroundColor(Color.TRANSPARENT)
                    stateListAnimator = null
                    minWidth = dp(44)
                    minimumWidth = dp(44)
                    setPadding(dp(6), 0, dp(6), 0)
                }
            }

            val exitButton = android.widget.ImageButton(this).apply {
                contentDescription = "Keluar dari layar penuh"
                setImageResource(R.drawable.ic_power)
                imageTintList =
                    android.content.res.ColorStateList.valueOf(accent)
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(Color.TRANSPARENT)
                stateListAnimator = null
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setOnClickListener { toggleFullscreen() }
            }

            val playButton = makeButton("▶", "Putar atau jeda")
            toolbar.addView(
                playButton,
                LinearLayout.LayoutParams(dp(52), dp(44))
            )
            playButton.setOnClickListener {
                val exo = player
                if (exo != null) {
                    if (exo.isPlaying) {
                        exo.pause()
                        playButton.text = "▶"
                    } else {
                        exo.play()
                        playButton.text = "Ⅱ"
                    }
                }
            }

            val ccButton = makeButton("CC", "Aktifkan atau matikan subtitle")
            toolbar.addView(
                ccButton,
                LinearLayout.LayoutParams(dp(52), dp(44))
            )
            ccButton.setOnClickListener {
                val exo = player
                if (exo == null) {
                    android.widget.Toast.makeText(
                        this, "Pemutar belum siap.",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }

                val groups = exo.currentTracks.groups.filter { group ->
                    group.type == C.TRACK_TYPE_TEXT &&
                        (0 until group.length).any { i ->
                            group.isTrackSupported(i)
                        }
                }

                if (groups.isEmpty()) {
                    android.widget.Toast.makeText(
                        this,
                        "Subtitle tidak tersedia atau belum dimuat oleh channel ini.",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                    return@setOnClickListener
                }

                val selected = groups.any { group ->
                    (0 until group.length).any { i ->
                        group.isTrackSelected(i)
                    }
                }

                val builder = exo.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)

                if (selected) {
                    builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    ccButton.text = "CC"
                    android.widget.Toast.makeText(
                        this, "Subtitle dimatikan.",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                } else {
                    val group = groups.first()
                    val index = (0 until group.length).firstOrNull { i ->
                        group.isTrackSupported(i)
                    } ?: 0

                    builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .addOverride(
                            TrackSelectionOverride(group.mediaTrackGroup, index)
                        )
                    ccButton.text = "CC ✓"
                    android.widget.Toast.makeText(
                        this, "Subtitle diaktifkan.",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }

                exo.trackSelectionParameters = builder.build()
            }

            fullscreenControls.addView(
                toolbar,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(48),
                    Gravity.TOP
                )
            )

            // Menu ukuran video: satu ikon untuk membuka/tutup pilihan.
            val sizeModes = listOf(
                Triple(
                    "Fit",
                    androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT,
                    0
                ),
                Triple(
                    "Fill",
                    androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL,
                    1
                ),
                Triple(
                    "Zoom",
                    androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
                    2
                )
            )

            val sizeButtons = mutableListOf<Pair<TextView, Int>>()

            val sizeSelector = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(3), dp(3), dp(3), dp(3))
                setBackgroundColor(Color.argb(210, 18, 18, 18))
                elevation = dp(4).toFloat()
                visibility = View.GONE
            }

            fun updateSizeSelection(selectedPosition: Int) {
                sizeButtons.forEach { (label, position) ->
                    val selected = position == selectedPosition
                    label.setTextColor(
                        if (selected) Color.WHITE
                        else Color.argb(175, 255, 255, 255)
                    )
                    label.alpha = if (selected) 1f else 0.78f
                    label.paint.isFakeBoldText = selected
                }
            }

            sizeModes.forEach { (labelText, mode, position) ->
                val label = TextView(this).apply {
                    text = labelText
                    textSize = 12f
                    gravity = Gravity.CENTER
                    setTextColor(accent)
                    setBackgroundColor(Color.TRANSPARENT)
                    minWidth = 0
                    minimumWidth = 0
                    minHeight = 0
                    setPadding(dp(12), dp(5), dp(12), dp(5))
                    contentDescription = "Ukuran video $labelText"
                    isClickable = true
                    isFocusable = true

                    setOnClickListener {
                        playerView.resizeMode = mode

                        if (sizeSpinner.selectedItemPosition != position) {
                            sizeSpinner.setSelection(position)
                        }

                        updateSizeSelection(position)
                        sizeSelector.visibility = View.GONE
                        scheduleControlsAutoHide()
                    }
                }

                sizeButtons.add(label to position)
                sizeSelector.addView(
                    label,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(32)
                    )
                )
            }

            val sizeToggle = makeButton(
                "▣", "Buka pilihan Fit, Fill, Zoom"
            ).apply {
                textSize = 20f
                minWidth = 0
                minimumWidth = 0
                setPadding(0, 0, 0, 0)
            }

            // Kelompok tombol kanan: ukuran, refresh, keluar fullscreen.
            val refreshFullscreenButton = makeButton(
                "↻", "Muat ulang stream saat ini"
            ).apply {
                textSize = 20f
                minWidth = 0
                minimumWidth = 0
                setPadding(0, 0, 0, 0)
            }

            refreshFullscreenButton.setOnClickListener {
                val exo = player
                if (exo != null && exo.currentMediaItem != null) {
                    status.text = "Memuat ulang stream..."
                    exo.seekToDefaultPosition()
                    exo.prepare()
                    exo.playWhenReady = true
                } else {
                    playStream()
                }
                scheduleControlsAutoHide()
            }

            // Ruang fleksibel memisahkan tombol playback dari toolbar kanan.
            toolbar.addView(
                View(this),
                LinearLayout.LayoutParams(0, dp(1), 1f)
            )
            toolbar.addView(
                sizeToggle,
                LinearLayout.LayoutParams(dp(40), dp(44))
            )
            toolbar.addView(
                refreshFullscreenButton,
                LinearLayout.LayoutParams(dp(40), dp(44))
            )
            toolbar.addView(
                exitButton,
                LinearLayout.LayoutParams(dp(40), dp(44))
            )

            // Menu pilihan muncul tepat di bawah bilah tombol.
            fullscreenControls.addView(
                sizeSelector,
                FrameLayout.LayoutParams(
                    dp(76),
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.END or Gravity.TOP
                ).apply {
                    topMargin = dp(50)
                    rightMargin = dp(10)
                }
            )

            sizeToggle.setOnClickListener {
                sizeSelector.visibility =
                    if (sizeSelector.visibility == View.VISIBLE) {
                        View.GONE
                    } else {
                        View.VISIBLE
                    }
                scheduleControlsAutoHide()
            }

            updateSizeSelection(sizeSpinner.selectedItemPosition)

            // Volume media Android: slider horizontal diputar menjadi vertikal.
            val audioManager =
                getSystemService(AUDIO_SERVICE) as android.media.AudioManager

            val volumeSlider = SeekBar(this).apply {
                max = audioManager.getStreamMaxVolume(
                    android.media.AudioManager.STREAM_MUSIC
                ).coerceAtLeast(1)
                progress = audioManager.getStreamVolume(
                    android.media.AudioManager.STREAM_MUSIC
                )
                rotation = -90f
                progressTintList =
                    android.content.res.ColorStateList.valueOf(accent)
                thumbTintList =
                    android.content.res.ColorStateList.valueOf(accent)
                progressBackgroundTintList =
                    android.content.res.ColorStateList.valueOf(
                        Color.argb(85, 0, 220, 255)
                    )
                alpha = 0.92f
                contentDescription = "Volume vertikal"
            }

            volumeSlider.setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                        seekBar: SeekBar?, progress: Int, fromUser: Boolean
                    ) {
                        if (fromUser) {
                            audioManager.setStreamVolume(
                                android.media.AudioManager.STREAM_MUSIC,
                                progress,
                                0
                            )
                        }
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                }
            )

            fullscreenControls.addView(
                volumeSlider,
                FrameLayout.LayoutParams(
                    dp(140), dp(32),
                    Gravity.START or Gravity.CENTER_VERTICAL
                )
            )

            val volumeLabel = TextView(this).apply {
                text = "🔊"
                textSize = 20f
                gravity = Gravity.CENTER
                contentDescription = "Volume"
            }
            fullscreenControls.addView(
                volumeLabel,
                FrameLayout.LayoutParams(
                    dp(44), dp(40),
                    Gravity.START or Gravity.CENTER_VERTICAL
                )
            )

            // Kecerahan jendela, dipulihkan saat keluar fullscreen.
            val brightnessSlider = SeekBar(this).apply {
                max = 100
                progress = if (
                    previousScreenBrightness >= 0f &&
                    previousScreenBrightness <= 1f
                ) {
                    (previousScreenBrightness * 100).toInt().coerceIn(5, 100)
                } else {
                    50
                }
                rotation = -90f
                progressTintList =
                    android.content.res.ColorStateList.valueOf(accent)
                thumbTintList =
                    android.content.res.ColorStateList.valueOf(accent)
                progressBackgroundTintList =
                    android.content.res.ColorStateList.valueOf(
                        Color.argb(85, 0, 220, 255)
                    )
                alpha = 0.92f
                contentDescription = "Kecerahan vertikal"
            }

            brightnessSlider.setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                        seekBar: SeekBar?, progress: Int, fromUser: Boolean
                    ) {
                        if (fromUser) {
                            val attrs = window.attributes
                            attrs.screenBrightness =
                                (progress.coerceIn(5, 100) / 100f)
                            window.attributes = attrs
                        }
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                }
            )

            fullscreenControls.addView(
                brightnessSlider,
                FrameLayout.LayoutParams(
                    dp(140), dp(32),
                    Gravity.END or Gravity.CENTER_VERTICAL
                )
            )

            val brightnessLabel = TextView(this).apply {
                text = "☀"
                textSize = 20f
                gravity = Gravity.CENTER
                contentDescription = "Kecerahan"
            }
            fullscreenControls.addView(
                brightnessLabel,
                FrameLayout.LayoutParams(
                    dp(44), dp(40),
                    Gravity.END or Gravity.CENTER_VERTICAL
                )
            )

            fullscreenContainer.addView(
                fullscreenControls,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            fullscreenControlsView = fullscreenControls
            keepControlsVisibleWhileTouching(fullscreenControls)

            val decor = window.decorView as ViewGroup
            decor.addView(
                fullscreenContainer,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            fullscreenContainer.bringToFront()
            root.visibility = View.INVISIBLE

            fullscreen = true
            settingsPanelView?.visibility = View.GONE
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            showPlaybackControls()

        } else {
            val parent = fullscreenOriginalParent
            val params = fullscreenOriginalLayoutParams
            val overlay = fullscreenContainer

            if (parent == null || params == null) {
                status.text = "Induk video tidak tersedia; fullscreen dipertahankan."
                return
            }

            (videoContainer.parent as? ViewGroup)?.removeView(videoContainer)

            try {
                val index = fullscreenOriginalIndex.coerceIn(
                    0, parent.childCount
                )
                parent.addView(videoContainer, index, params)
            } catch (e: Exception) {
                overlay.addView(
                    videoContainer,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        Gravity.CENTER
                    )
                )
                status.text = "Gagal memulihkan video."
                return
            }

            (overlay.parent as? ViewGroup)?.removeView(overlay)

            val attrs = window.attributes
            attrs.screenBrightness = previousScreenBrightness
            window.attributes = attrs

            fullscreen = false
            fullscreenControlsView = null
            fullscreenOriginalParent = null
            fullscreenOriginalIndex = -1
            fullscreenOriginalLayoutParams = null

            root.visibility = View.VISIBLE
            videoContainer.visibility = View.VISIBLE
            playerView.visibility = View.VISIBLE
            parent.visibility = View.VISIBLE
            parent.requestLayout()
            videoContainer.requestLayout()
            playerView.requestLayout()
            root.requestLayout()
            root.invalidate()

            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            requestedOrientation = fullscreenPreviousRequestedOrientation
            showPlaybackControls()
        }
    }
    private fun compactSpinnerAdapter(
        items: List<String>
    ): ArrayAdapter<String> {
        return object : ArrayAdapter<String>(
            this,
            android.R.layout.simple_spinner_item,
            items
        ) {
            override fun getView(
                position: Int,
                convertView: View?,
                parent: ViewGroup
            ): View {
                return super.getView(position, convertView, parent).apply {
                    (this as? TextView)?.apply {
                        textSize = 10f
                        maxLines = 1
                        setTextColor(accent)
                        setPadding(dp(4), 0, dp(2), 0)
                    }
                }
            }
        }.apply {
            setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item
            )
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
        val url = fixedPlaylistUrl
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            status.text = "Masukkan URL HTTP/HTTPS yang valid."
            return
        }

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

        if (loadedPlaylistUrl == url && playlistChannels.isNotEmpty()) {
            val selected =
                playlistChannels.getOrNull(channelSpinner.selectedItemPosition)
            if (selected == null) {
                status.text = "Pilih channel terlebih dahulu."
                return
            }
            // Header global menjadi nilai awal.
            // Metadata header channel mengesampingkan nilai global.
            val effectiveHeaders = headers.toMutableMap()
            effectiveHeaders.putAll(selected.headers)
            playMedia(
                selected.url,
                effectiveHeaders,
                selected.drmScheme,
                selected.drmLicenseUrl,
                selected.drmLicenseHeaders
            )
            return
        }

        val path = url.substringBefore("?").lowercase()
        val looksLikePlaylist = path.endsWith(".m3u") ||
            path.endsWith(".m3u8") ||
            url.contains("bit.ly/", ignoreCase = true)

        val looksLikeDash = path.endsWith(".mpd")

        when {
            looksLikeDash -> playMedia(url, headers)
            looksLikePlaylist -> loadPlaylist(url, headers)
            else -> detectPlaylistOrPlayMedia(url, headers)
        }
    }

    private fun detectPlaylistOrPlayMedia(
        url: String,
        headers: Map<String, String>
    ) {
        status.text = "Memeriksa jenis stream…"

        Thread {
            var connection: java.net.HttpURLConnection? = null
            try {
                connection =
                    (java.net.URL(url).openConnection()
                        as java.net.HttpURLConnection).apply {
                        instanceFollowRedirects = true
                        connectTimeout = 10000
                        readTimeout = 10000
                        requestMethod = "GET"
                        setRequestProperty("Accept", "*/*")
                        headers.forEach { (name, value) ->
                            setRequestProperty(name, value)
                        }
                    }

                val code = connection.responseCode
                if (code !in 200..299) {
                    throw IllegalStateException("HTTP $code")
                }

                val prefix = connection.inputStream.use { input ->
                    val buffer = ByteArray(8192)
                    val count = input.read(buffer)
                    if (count <= 0) "" else
                        String(buffer, 0, count, Charsets.UTF_8)
                }

                val isPlaylist =
                    prefix.contains("#EXTM3U", ignoreCase = true) ||
                    connection.contentType
                        ?.contains("mpegurl", ignoreCase = true) == true

                runOnUiThread {
                    if (isPlaylist) {
                        loadPlaylist(url, headers)
                    } else {
                        playMedia(url, headers)
                    }
                }
            } catch (_: Exception) {
                runOnUiThread {
                    playMedia(url, headers)
                }
            } finally {
                connection?.disconnect()
            }
        }.start()
    }

    private fun loadPlaylist(url: String, headers: Map<String, String>) {
        status.text = "Mengunduh dan membaca playlist…"

        Thread {
            var connection: java.net.HttpURLConnection? = null
            try {
                connection =
                    (java.net.URL(url).openConnection()
                        as java.net.HttpURLConnection).apply {
                        instanceFollowRedirects = true
                        connectTimeout = 15000
                        readTimeout = 20000
                        requestMethod = "GET"
                        setRequestProperty("Accept", "*/*")
                        headers.forEach { (name, value) ->
                            setRequestProperty(name, value)
                        }
                    }

                val code = connection.responseCode
                if (code !in 200..299) {
                    throw IllegalStateException("Server mengembalikan HTTP $code")
                }

                val text = connection.inputStream
                    .bufferedReader(Charsets.UTF_8)
                    .use { it.readText() }

                val finalUrl = connection.url.toString()
                val channels = parsePlaylist(text, finalUrl)
                    .filterNot {
                        it.name.trim().equals(
                            "001 TRAKTIR KOPI",
                            ignoreCase = true
                        )
                    }

                runOnUiThread {
                    swipeRefreshLayout.isRefreshing = false
                    if (channels.isEmpty()) {
                        loadedPlaylistUrl = null
                        playlistChannels = emptyList()
                        status.text =
                            "Playlist tidak berisi channel yang dikenali. Periksa format M3U."
                    } else {
                        loadedPlaylistUrl = url
                        playlistRequestHeaders = headers.toMap()
                        playlistChannels = channels
                        renderChannelGrid(channels)
                        channelSpinner.adapter = ArrayAdapter(
                            this,
                            android.R.layout.simple_spinner_dropdown_item,
                            channels.map { it.name }
                        )
                        channelSpinner.setSelection(0)
                        status.text =
                            "Playlist dimuat: ${channels.size} channel. Ketuk channel untuk memutar."
                        startUrlMonitoring()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    swipeRefreshLayout.isRefreshing = false
                    status.text =
                        "Gagal membaca playlist: ${e.message ?: "koneksi atau format tidak valid"}"
                }
            } finally {
                connection?.disconnect()
            }
        }.start()
    }

    private fun parsePlaylist(
        text: String,
        baseUrl: String
    ): List<PlaylistEntry> {
        val header = text.indexOf("#EXTM3U", ignoreCase = true)
        val body = if (header >= 0) text.substring(header) else text
        val lines = body.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val result = mutableListOf<PlaylistEntry>()
        var pendingName: String? = null
        var pendingLogoUrl: String? = null
        var pendingHeaders = mutableMapOf<String, String>()
        var pendingDrmScheme: String? = null
        var pendingLicenseUrl: String? = null
        var pendingLicenseHeaders = mutableMapOf<String, String>()

        for (line in lines) {
            if (line.startsWith("#EXTINF", ignoreCase = true)) {
                val comma = line.indexOf(',')
                val title =
                    if (comma >= 0) line.substring(comma + 1).trim() else ""

                val tvgName = Regex("""tvg-name="([^"]+)"""")
                    .find(line)?.groupValues?.getOrNull(1)

                pendingName = tvgName?.takeIf { it.isNotBlank() }
                    ?: title.takeIf { it.isNotBlank() }
                    ?: "Channel ${result.size + 1}"

                pendingLogoUrl = Regex("""tvg-logo="([^"]+)"""")
                    .find(line)?.groupValues?.getOrNull(1)
                    ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }

                pendingHeaders = mutableMapOf()
                pendingDrmScheme = null
                pendingLicenseUrl = null
                pendingLicenseHeaders = mutableMapOf()
            } else if (line.startsWith("#EXTVLCOPT:", ignoreCase = true)
                && pendingName != null) {
                val option = line.substringAfter(':')
                val separator = option.indexOf('=')
                if (separator <= 0) continue

                val key = option.substring(0, separator)
                    .trim().lowercase()
                val value = option.substring(separator + 1).trim()

                val headerName = when (key) {
                    "http-user-agent" -> "User-Agent"
                    "http-referrer", "http-referer" -> "Referer"
                    "http-origin" -> "Origin"
                    "http-cookie" -> "Cookie"
                    else -> null
                }

                if (headerName != null && value.isNotEmpty()) {
                    pendingHeaders[headerName] = value
                }
            } else if (line.startsWith("#KODIPROP:", ignoreCase = true)
                && pendingName != null) {
                val property = line.substringAfter(':')
                val separator = property.indexOf('=')
                if (separator > 0) {
                    val key = property.substring(0, separator)
                        .trim().lowercase()
                    val value = property.substring(separator + 1).trim()

                    when (key) {
                        "inputstream.adaptive.license_type" -> {
                            pendingDrmScheme = when {
                                value.equals("com.widevine.alpha", true) ||
                                    value.equals("widevine", true) -> "widevine"
                                value.equals("clearkey", true) ||
                                    value.equals("clear_key", true) -> "clearkey"
                                else -> null
                            }
                        }

                        "inputstream.adaptive.license_key" -> {
                            val parts = value.split('|')
                            val rawLicenseUrl =
                                parts.firstOrNull()?.trim().orEmpty()

                            if (rawLicenseUrl.isNotEmpty()) {
                                if (pendingDrmScheme == "clearkey" &&
                                    isInlineClearKeyValue(rawLicenseUrl)) {
                                    pendingLicenseUrl = rawLicenseUrl
                                } else {
                                    try {
                                        pendingLicenseUrl = java.net.URL(
                                            java.net.URL(baseUrl),
                                            rawLicenseUrl
                                        ).toString()
                                    } catch (_: Exception) {
                                        pendingLicenseUrl = rawLicenseUrl
                                    }
                                }
                            }

                            pendingLicenseHeaders = mutableMapOf()
                            val headerText = parts.getOrNull(1).orEmpty()

                            for (header in headerText.split('&')) {
                                val eq = header.indexOf('=')
                                if (eq <= 0) continue

                                val headerName = header.substring(0, eq).trim()
                                val encodedValue = header.substring(eq + 1).trim()

                                if (headerName.isNotEmpty()) {
                                    val decodedValue = try {
                                        java.net.URLDecoder.decode(
                                            encodedValue,
                                            "UTF-8"
                                        )
                                    } catch (_: Exception) {
                                        encodedValue
                                    }
                                    if (decodedValue.isNotEmpty()) {
                                        pendingLicenseHeaders[headerName] =
                                            decodedValue
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (!line.startsWith("#") && pendingName != null) {
                try {
                    val resolved = java.net.URL(
                        java.net.URL(baseUrl), line
                    ).toString()

                    if (resolved.startsWith("http://") ||
                        resolved.startsWith("https://")) {
                        result.add(
                            PlaylistEntry(
                                name = pendingName!!,
                                url = resolved,
                                logoUrl = pendingLogoUrl,
                                headers = pendingHeaders.toMap(),
                                drmScheme = pendingDrmScheme,
                                drmLicenseUrl = pendingLicenseUrl,
                                drmLicenseHeaders = pendingLicenseHeaders.toMap()
                            )
                        )
                    }
                } catch (_: Exception) {
                    // Abaikan URL yang tidak valid.
                }
                pendingName = null
                pendingHeaders = mutableMapOf()
                pendingDrmScheme = null
                pendingLicenseUrl = null
                pendingLicenseHeaders = mutableMapOf()
            }
        }

        return result
    }

    private fun isInlineClearKeyValue(value: String): Boolean {
        val raw = value.trim()
        return Regex("""(?i)^[0-9a-f]{32}:[0-9a-f]{32}$""")
            .matches(raw) ||
            (raw.startsWith("{") &&
                raw.contains("\"keys\"", ignoreCase = true))
    }

    private fun buildClearKeyResponse(value: String?): String? {
        val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        // JSON respons ClearKey yang sudah disediakan sumber.
        if (raw.startsWith("{") &&
            raw.contains("\"keys\"", ignoreCase = true)) {
            return raw
        }

        // Format lokal KID:KEY, keduanya 16 byte dalam bentuk heksadesimal.
        val match = Regex("""(?i)^([0-9a-f]{32}):([0-9a-f]{32})$""")
            .matchEntire(raw) ?: return null

        fun hexToBytes(hex: String): ByteArray =
            hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        fun base64Url(bytes: ByteArray): String =
            Base64.encodeToString(
                bytes,
                Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
            )

        val kid = base64Url(hexToBytes(match.groupValues[1]))
        val key = base64Url(hexToBytes(match.groupValues[2]))

        return """{"keys":[{"kty":"oct","kid":"$kid","k":"$key"}],"type":"temporary"}"""
    }

    private fun playMedia(
        url: String,
        headers: Map<String, String>,
        drmScheme: String? = null,
        drmLicenseUrl: String? = null,
        drmLicenseHeaders: Map<String, String> = emptyMap()
    ) {
        releasePlayer()

        if (::resolutionSpinner.isInitialized) {
            updatingResolution = true
            resolutionSpinner.setSelection(0)
            updatingResolution = false
        }

        try {
            val httpFactory = DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(10_000)
                .setReadTimeoutMs(15_000)
                .setAllowCrossProtocolRedirects(true)
                .setDefaultRequestProperties(headers)

            val inlineClearKeyResponse =
                if (drmScheme == "clearkey") {
                    buildClearKeyResponse(drmLicenseUrl)
                } else {
                    null
                }

            if (drmScheme == "clearkey" &&
                inlineClearKeyResponse == null) {
                val license = drmLicenseUrl
                if (license.isNullOrBlank() ||
                    !(license.startsWith("https://", true) ||
                      license.startsWith("http://", true))) {
                    throw IllegalArgumentException(
                        "ClearKey harus memakai URL lisensi atau format KID:KEY yang didukung."
                    )
                }
            }

            val mediaSourceFactory =
                DefaultMediaSourceFactory(this)
                    .setDataSourceFactory(httpFactory)

            if (inlineClearKeyResponse != null) {
                val clearKeyManager =
                    DefaultDrmSessionManager.Builder()
                        .setUuidAndExoMediaDrmProvider(
                            C.CLEARKEY_UUID,
                            FrameworkMediaDrm.DEFAULT_PROVIDER
                        )
                        .build(
                            LocalMediaDrmCallback(
                                inlineClearKeyResponse.toByteArray(Charsets.UTF_8)
                            )
                        )

                mediaSourceFactory.setDrmSessionManagerProvider {
                    _ -> clearKeyManager
                }
            }

            val loadControl =
                androidx.media3.exoplayer.DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        15_000, // Buffer minimum: 15 detik
                        50_000, // Buffer maksimum: 50 detik
                        2_500,  // Mulai memutar: 2,5 detik
                        5_000   // Lanjut setelah buffering: 5 detik
                    )
                    .build()

            player = ExoPlayer.Builder(
                this,
                androidx.media3.exoplayer.DefaultRenderersFactory(this)
                    .setEnableDecoderFallback(true)
            )
                .setLoadControl(loadControl)
                .setMediaSourceFactory(mediaSourceFactory)
                .build()
                .also { exo ->
                    playerView.player = exo
                    val itemBuilder = MediaItem.Builder().setUri(url)
                    val cleanUrl = url.substringBefore("?")

                    when {
                        cleanUrl.endsWith(".m3u8", true) ->
                            itemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
                        cleanUrl.endsWith(".mpd", true) ->
                            itemBuilder.setMimeType(MimeTypes.APPLICATION_MPD)
                    }

                    when (drmScheme) {
                        "widevine" -> {
                            val license = drmLicenseUrl
                            if (license.isNullOrBlank()) {
                                throw IllegalArgumentException(
                                    "Channel Widevine tidak memiliki URL lisensi."
                                )
                            }
                            if (!license.startsWith("https://", ignoreCase = true)) {
                                throw IllegalArgumentException(
                                    "URL lisensi Widevine harus menggunakan HTTPS."
                                )
                            }

                            val drmConfiguration =
                                MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                                    .setLicenseUri(Uri.parse(license))
                                    .setLicenseRequestHeaders(drmLicenseHeaders)
                                    .build()

                            itemBuilder.setDrmConfiguration(drmConfiguration)
                        }

                        "clearkey" -> {
                            val drmBuilder =
                                MediaItem.DrmConfiguration.Builder(C.CLEARKEY_UUID)

                            if (inlineClearKeyResponse == null) {
                                drmBuilder
                                    .setLicenseUri(Uri.parse(drmLicenseUrl!!))
                                    .setLicenseRequestHeaders(drmLicenseHeaders)
                            }

                            itemBuilder.setDrmConfiguration(drmBuilder.build())
                        }
                    }

                    exo.setMediaItem(itemBuilder.build())
                    exo.addListener(object : Player.Listener {
                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            if (isPlaying) {
                                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                            } else {
                                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                            }
                        }

                        override fun onPlaybackStateChanged(playbackState: Int) {
                            status.text = when (playbackState) {
                                Player.STATE_BUFFERING ->
                                    "Menghubungkan dan buffering…"
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

                            val lines = mutableListOf<String>()

                            for (g in tracks.groups) {
                                val kind = when (g.type) {
                                    C.TRACK_TYPE_VIDEO -> "VIDEO"
                                    C.TRACK_TYPE_AUDIO -> "AUDIO"
                                    else -> continue
                                }

                                for (i in 0 until g.length) {
                                    val f = g.getTrackFormat(i)
                                    val supportCode = g.getTrackSupport(i)

                                    val support = when (supportCode) {
                                        C.FORMAT_HANDLED -> "DIDUKUNG"
                                        C.FORMAT_EXCEEDS_CAPABILITIES -> "DI LUAR KEMAMPUAN"
                                        C.FORMAT_UNSUPPORTED_DRM -> "DRM TIDAK DIDUKUNG"
                                        C.FORMAT_UNSUPPORTED_SUBTYPE -> "CODEC TIDAK DIDUKUNG"
                                        C.FORMAT_UNSUPPORTED_TYPE -> "JENIS FORMAT TIDAK DIDUKUNG"
                                        else -> "STATUS $supportCode"
                                    }

                                    val mime = f.sampleMimeType ?: "MIME?"
                                    val codec = f.codecs ?: "-"
                                    val selected = if (g.isTrackSelected(i)) " | AKTIF" else ""
                                    val extra = if (kind == "VIDEO") {
                                        " | ${f.width}x${f.height} | fps=${f.frameRate}"
                                    } else {
                                        " | ${f.channelCount}ch | ${f.sampleRate}Hz"
                                    }

                                    lines.add(
                                        "$kind: $mime | codec=$codec$extra | $support$selected"
                                    )
                                }
                            }

                            val details = lines.take(8).joinToString("\n")
                            diagnosticStatus.text = if (details.isBlank()) {
                                "TREK STREAM: belum terdeteksi"
                            } else {
                                "DIAGNOSIS STREAM\n$details"
                            }

                            android.util.Log.d("IONTVPlayer", "Tracks:\n$details")
                        }

                        override fun onRenderedFirstFrame() {
                            updateChannelUrlStatus(
                                url,
                                "ONLINE",
                                "Gambar berhasil ditampilkan"
                            )
                            diagnosticStatus.text = "Gambar berhasil ditampilkan."
                            android.util.Log.d(
                                "IONTVPlayer",
                                "FIRST_FRAME_RENDERED"
                            )
                        }

                        override fun onVideoSizeChanged(
                            videoSize: androidx.media3.common.VideoSize
                        ) {
                            android.util.Log.d(
                                "IONTVPlayer",
                                "Video size: ${videoSize.width}x${videoSize.height}, rotation=${videoSize.unappliedRotationDegrees}"
                            )
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            var causeForUrlStatus: Throwable? = error
                            var serverUnavailable = false

                            while (causeForUrlStatus != null) {
                                if (causeForUrlStatus is
                                    androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
                                ) {
                                    val code = causeForUrlStatus.responseCode
                                    if (code == 404 || code == 410 || code >= 500) {
                                        serverUnavailable = true
                                    }
                                }
                                causeForUrlStatus = causeForUrlStatus.cause
                            }

                            if (serverUnavailable) {
                                updateChannelUrlStatus(
                                    url,
                                    "OFFLINE",
                                    "Server mengembalikan error HTTP"
                                )
                            }

                            val causes = mutableListOf<String>()
                            var e: Throwable? = error
                            while (e != null && causes.size < 8) {
                                causes.add("${e.javaClass.simpleName}: ${e.message ?: "(tanpa pesan)"}")
                                e = e.cause
                            }
                            val detail = causes.joinToString("\n")
                            diagnosticStatus.text = "GAGAL ${error.errorCodeName} (${error.errorCode})\n$detail"
                            android.util.Log.e("IONTVPlayer", "Playback error:\n$detail", error)
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

        val previousSelection = resolutionSpinner.selectedItem?.toString()
        updatingResolution = true
        val labels = listOf("Resolusi: otomatis") + videoOptions.map { it.label }
        resolutionSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            labels
        )
        val selectedIndex = labels.indexOf(previousSelection).let {
            if (it >= 0) it else 0
        }
        resolutionSpinner.setSelection(selectedIndex)
        updatingResolution = false

        if (videoOptions.isEmpty()) {
            status.text =
                "Stream siap. Pilihan resolusi manual tidak tersedia untuk stream ini."
        }
    }

    private fun releasePlayer() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
        splashAnimator?.cancel()
        splashAnimator = null
        urlMonitorRunnable?.let { controlsHandler.removeCallbacks(it) }
        channelUrlBlinkRunnable?.let {
            controlsHandler.removeCallbacks(it)
        }
        channelUrlBlinkRunnable = null
        urlCheckExecutor.shutdownNow()
        releasePlayer()
        super.onDestroy()
    }
}
