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
import android.widget.FrameLayout
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
    private lateinit var liveIndicator: LinearLayout
    private lateinit var liveIndicatorDot: View
    private val liveIndicatorRunnable = object : Runnable {
        override fun run() {
            updateLiveIndicator()
            controlsHandler.postDelayed(this, 1000L)
        }
    }
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
    private var brightnessPopup: FrameLayout? = null
    private var videoOverlayControls: List<View> = emptyList()
    private val pipSavedVisibility = linkedMapOf<View, Int>()
    private var pipOriginalVideoLayoutParams: ViewGroup.LayoutParams? = null

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
        val tvgId: String? = null,
        val headers: Map<String, String> = emptyMap(),
        val drmScheme: String? = null,
        val drmLicenseUrl: String? = null,
        val drmLicenseHeaders: Map<String, String> = emptyMap()
    )

    private data class EpgProgramme(
        val channelId: String,
        val title: String,
        val startMillis: Long,
        val stopMillis: Long
    )

    private data class EpgScheduleView(
        val entry: PlaylistEntry,
        val currentText: TextView,
        val nextText: TextView
    )

    private var epgProgrammesByChannel: Map<String, List<EpgProgramme>> = emptyMap()
    private var epgAliases: Map<String, String> = emptyMap()
    private val epgScheduleCache = mutableMapOf<String, List<EpgProgramme>>()
    private val epgScheduleViews = mutableListOf<EpgScheduleView>()
    private var epgLoading = false
    private var epgRetryRunnable: Runnable? = null

    private val epgScheduleRefreshRunnable = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            updateEpgScheduleViews()
            controlsHandler.postDelayed(this, 60_000L)
        }
    }

    private fun startEpgScheduleRefresh() {
        controlsHandler.removeCallbacks(epgScheduleRefreshRunnable)
        controlsHandler.postDelayed(epgScheduleRefreshRunnable, 60_000L)
    }

    private fun scheduleEpgRetry() {
        epgRetryRunnable?.let { controlsHandler.removeCallbacks(it) }
        val task = Runnable {
            epgRetryRunnable = null
            if (!isFinishing && !isDestroyed &&
                playlistChannels.isNotEmpty() && !epgLoading
            ) {
                loadEpgSchedule()
            }
        }
        epgRetryRunnable = task
        controlsHandler.postDelayed(task, 60_000L)
    }

    private fun updateEpgScheduleViews() {
        if (isFinishing || isDestroyed) return

        epgScheduleViews.forEach { item ->
            val (current, next) = findEpgSchedule(item.entry)

            item.currentText.text = current?.let {
                "● ${formatEpgTime(it.startMillis)} ${it.title}"
            } ?: if (epgLoading) {
                "● Jadwal sedang dimuat..."
            } else {
                "● Jadwal EPG tidak tersedia"
            }

            item.nextText.text = next?.let {
                "› ${formatEpgTime(it.startMillis)} Berikutnya: ${it.title}"
            } ?: "› Acara berikutnya belum tersedia"
        }
    }

    private fun normalizeEpgKey(value: String): String =
        value.substringBefore(" [Channel")
            .trim()
            .lowercase(java.util.Locale.ROOT)
            .removeSuffix(".id")
            .filter { it.isLetterOrDigit() }

    private fun parseXmltvTime(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        val match = Regex("""^(\d{14})(?:\s*([+-]\d{4}|Z))?.*$""")
            .find(value.trim()) ?: return null
        val zone = match.groupValues.getOrElse(2) { "" }
        val zoneText = if (zone.isBlank() || zone == "Z") "+0000" else zone
        return try {
            java.text.SimpleDateFormat(
                "yyyyMMddHHmmss Z",
                java.util.Locale.US
            ).apply { isLenient = false }
                .parse("${match.groupValues[1]} $zoneText")?.time
        } catch (_: Exception) {
            null
        }
    }

    private fun loadEpgSchedule() {
        if (epgLoading || isFinishing || isDestroyed) return
        epgRetryRunnable?.let { controlsHandler.removeCallbacks(it) }
        epgRetryRunnable = null
        epgLoading = true
        Thread {
            var connection: java.net.HttpURLConnection? = null
            try {
                connection = (java.net.URL(
                    "https://iptv-epg.org/files/epg-id.xml"
                ).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 25000
                    instanceFollowRedirects = true
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/xml,text/xml,*/*")
                    setRequestProperty("User-Agent", "IONTVPlayer/1.0")
                }
                val code = connection.responseCode
                if (code !in 200..299) {
                    throw IllegalStateException("HTTP $code")
                }

                val parser = android.util.Xml.newPullParser()
                val aliases = mutableMapOf<String, String>()
                val programmes = mutableListOf<EpgProgramme>()
                connection.inputStream.buffered().use { input ->
                    parser.setInput(input, null)
                    var event = parser.eventType
                    var channelId = ""
                    var programmeChannel = ""
                    var programmeStart: Long? = null
                    var programmeStop: Long? = null
                    var programmeTitle = ""

                    while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                        when (event) {
                            org.xmlpull.v1.XmlPullParser.START_TAG -> when (parser.name) {
                                "channel" -> {
                                    channelId = parser.getAttributeValue(null, "id") ?: ""
                                    if (channelId.isNotBlank()) {
                                        aliases[normalizeEpgKey(channelId)] = channelId
                                    }
                                }
                                "display-name" -> {
                                    val display = parser.nextText().trim()
                                    if (channelId.isNotBlank() && display.isNotBlank()) {
                                        aliases[normalizeEpgKey(display)] = channelId
                                        val shortName = display.substringAfterLast(" - ", display)
                                        aliases[normalizeEpgKey(shortName)] = channelId
                                    }
                                }
                                "programme" -> {
                                    programmeChannel =
                                        parser.getAttributeValue(null, "channel") ?: ""
                                    programmeStart = parseXmltvTime(
                                        parser.getAttributeValue(null, "start")
                                    )
                                    programmeStop = parseXmltvTime(
                                        parser.getAttributeValue(null, "stop")
                                    )
                                    programmeTitle = ""
                                }
                                "title" -> {
                                    val title = parser.nextText().trim()
                                    if (title.isNotBlank()) programmeTitle = title
                                }
                            }
                            org.xmlpull.v1.XmlPullParser.END_TAG -> {
                                if (parser.name == "programme") {
                                    val start = programmeStart
                                    val stop = programmeStop
                                    if (programmeChannel.isNotBlank() &&
                                        !programmeTitle.isBlank() &&
                                        start != null && stop != null && stop > start
                                    ) {
                                        programmes.add(
                                            EpgProgramme(
                                                programmeChannel,
                                                programmeTitle,
                                                start,
                                                stop
                                            )
                                        )
                                    }
                                } else if (parser.name == "channel") {
                                    channelId = ""
                                }
                            }
                        }
                        event = parser.next()
                    }
                }

                if (programmes.isEmpty()) {
                    throw IllegalStateException(
                        "XMLTV berhasil dibaca, tetapi tidak ada acara EPG yang valid"
                    )
                }

                val grouped = programmes.groupBy { normalizeEpgKey(it.channelId) }
                runOnUiThread {
                    epgRetryRunnable?.let { controlsHandler.removeCallbacks(it) }
                    epgRetryRunnable = null
                    epgAliases = aliases
                    epgProgrammesByChannel = grouped
                    epgScheduleCache.clear()
                    epgLoading = false
                    if (playlistChannels.isNotEmpty()) renderChannelGrid(playlistChannels)
                    android.util.Log.i(
                        "IONTV-EPG",
                        "EPG dimuat: ${programmes.size} acara, ${aliases.size} alias"
                    )
                }
            } catch (e: Exception) {
                runOnUiThread {
                    epgLoading = false
                    if (playlistChannels.isNotEmpty()) {
                        renderChannelGrid(playlistChannels)
                    }
                    android.util.Log.w("IONTV-EPG", "Gagal memuat EPG", e)
                    scheduleEpgRetry()
                }
            } finally {
                connection?.disconnect()
            }
        }.start()
    }

    private fun findEpgSchedule(entry: PlaylistEntry): Pair<EpgProgramme?, EpgProgramme?> {
        val now = System.currentTimeMillis()
        val normalizedName = normalizeEpgKey(entry.name)
        val keys = (
            listOfNotNull(entry.tvgId, entry.name)
                .map { normalizeEpgKey(it) }
                .filter { it.isNotBlank() } +
            if (normalizedName == "antv1" || normalizedName == "antv2") {
                listOf("antv")
            } else {
                emptyList()
            }
        ).distinct()
        val cacheKey = keys.joinToString("|")
        val programmes = epgScheduleCache.getOrPut(cacheKey) {
            val channelIds = keys.mapNotNull { epgAliases[it] }
                .map { normalizeEpgKey(it) } + keys
            channelIds.distinct()
                .flatMap { epgProgrammesByChannel[it].orEmpty() }
                .distinctBy { "${it.startMillis}:${it.title}" }
                .sortedBy { it.startMillis }
        }
        val current = programmes.firstOrNull {
            it.startMillis <= now && it.stopMillis > now
        }
        val next = programmes.firstOrNull { it.startMillis > now }
        return current to next
    }

    private fun formatEpgTime(timeMillis: Long): String =
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(timeMillis))

    private val fixedPlaylistUrl = "https://bit.ly/KPL203"
    private val bg = Color.rgb(9, 6, 22)
    private val panel = Color.rgb(23, 11, 43)
    private val accent = Color.rgb(157, 77, 255)
    private val white = Color.WHITE
    private val muted = Color.rgb(190, 160, 215)
    private var splashAnimator: android.animation.ValueAnimator? = null
    private var splashShowing = false
    private var splashTimeoutRunnable: Runnable? = null
    private var videoLoadingOverlay: FrameLayout? = null
    private var videoLoadingMark: View? = null
    private var videoLoadingAnimator: android.animation.ValueAnimator? = null
    private val videoLoadingAnimationEnabled = false
    private var videoFrameRendered = false



    private fun showCyberExitDialog() {
        val navy = Color.rgb(5, 11, 24)
        val cyan = Color.rgb(0, 245, 255)
        val whiteBlue = Color.rgb(215, 239, 247)

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(22), dp(22), dp(22), dp(18))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(navy)
                setStroke(dp(1), cyan)
                cornerRadius = dp(18).toFloat()
            }
        }

        val icon = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_power)
            imageTintList =
                android.content.res.ColorStateList.valueOf(cyan)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        }
        panel.addView(
            icon,
            LinearLayout.LayoutParams(dp(38), dp(38)).apply {
                bottomMargin = dp(12)
            }
        )

        panel.addView(TextView(this).apply {
            text = "Keluar dari aplikasi?"
            textSize = 19f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(cyan)
            setShadowLayer(dp(7).toFloat(), 0f, 0f, cyan)
        })

        panel.addView(TextView(this).apply {
            text = "Bossku yakin ingin keluar dari Cyber Neon TV?"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(whiteBlue)
            setPadding(0, dp(10), 0, dp(22))
        })

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        fun neonButton(label: String, filled: Boolean): TextView {
            return TextView(this).apply {
                text = label
                textSize = 13f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(if (filled) navy else cyan)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(if (filled) cyan else navy)
                    setStroke(dp(1), cyan)
                    cornerRadius = dp(9).toFloat()
                }
                isClickable = true
                isFocusable = true
                setPadding(dp(8), dp(12), dp(8), dp(12))
            }
        }

        val noButton = neonButton("TIDAK", false)
        val yesButton = neonButton("YA, KELUAR", true)

        buttons.addView(
            noButton,
            LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                marginEnd = dp(8)
            }
        )
        buttons.addView(
            yesButton,
            LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                marginStart = dp(8)
            }
        )
        panel.addView(
            buttons,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val dialog = android.app.Dialog(this)
        dialog.setContentView(panel)
        dialog.setCanceledOnTouchOutside(true)

        noButton.setOnClickListener {
            dialog.dismiss()
        }
        yesButton.setOnClickListener {
            dialog.dismiss()
            finish()
        }

        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.80f)
            setLayout(
                (resources.displayMetrics.widthPixels * 0.88f).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg

        buildUi()
        showSplash()
        restoreSavedPlaylist()
        checkForAppUpdate()
    }

    private fun checkForAppUpdate() {
        Thread({
            var connection: java.net.HttpURLConnection? = null
            try {
                val endpoint = java.net.URL(
                    "https://raw.githubusercontent.com/xyz850/cyber-neon-tv-site/main/version-v2.json"
                )
                connection = endpoint.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 8000
                connection.readTimeout = 8000
                connection.useCaches = false
                connection.setRequestProperty("Cache-Control", "no-cache")

                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val data = org.json.JSONObject(body)

                val latestCode = data.optInt("versionCode", 0)
                val latestName = data.optString("versionName", "")
                val notes = data.optString(
                    "releaseNotes",
                    "Pembaruan Cyber Neon TV tersedia."
                )
                val downloadUrl = data.optString("downloadUrl", "")

                val currentCode = packageManager
                    .getPackageInfo(packageName, 0).versionCode

                if (
                    latestCode > currentCode &&
                    latestName.isNotBlank() &&
                    downloadUrl.startsWith("https://")
                ) {
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread

                        android.app.AlertDialog.Builder(this)
                            .setTitle("⚡ Pembaruan Cyber Neon TV")
                            .setMessage(
                                "Versi baru $latestName tersedia!\n\n" +
                                "$notes\n\n" +
                                "Versi terpasang: " +
                                packageManager.getPackageInfo(packageName, 0).versionName
                            )
                            .setPositiveButton("Download Pembaruan") { _, _ ->
                                downloadAndInstallUpdate(downloadUrl)
                            }
                            .setNegativeButton("Nanti Saja", null)
                            .show()
                    }
                }
            } catch (_: Exception) {
                // Jika offline atau server gagal, pemutar tetap berjalan normal.
            } finally {
                connection?.disconnect()
            }
        }, "cyber-neon-update-check").start()
    }

    private fun downloadAndInstallUpdate(downloadUrl: String) {
        val uri = android.net.Uri.parse(downloadUrl)
        if (uri.scheme != "https" || uri.host != "github.com") {
            Toast.makeText(this, "Tautan pembaruan tidak valid.", Toast.LENGTH_LONG).show()
            return
        }

        if (android.os.Build.VERSION.SDK_INT >= 26 &&
            !packageManager.canRequestPackageInstalls()
        ) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Izin pemasangan diperlukan")
                .setMessage("Izinkan pemasangan dari sumber ini, lalu kembali dan tekan Download Pembaruan lagi.")
                .setPositiveButton("Buka Pengaturan") { _, _ ->
                    startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            android.net.Uri.parse("package:$packageName")
                        )
                    )
                }
                .setNegativeButton("Batal", null)
                .show()
            return
        }

        val progress = android.app.ProgressDialog(this).apply {
            setTitle("Cyber Neon TV")
            setMessage("Mengunduh pembaruan...")
            setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            setCancelable(false)
            setProgress(0)
            show()
        }

        Thread({
            var connection: java.net.HttpURLConnection? = null
            var file: java.io.File? = null
            var error: String? = null

            try {
                val folder = java.io.File(cacheDir, "updates")
                if (!folder.exists() && !folder.mkdirs()) {
                    throw java.io.IOException("Folder unduhan tidak dapat dibuat.")
                }

                val apk = java.io.File(folder, "cyber-neon-tv-update.apk")
                connection = (java.net.URL(downloadUrl).openConnection()
                    as java.net.HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15000
                    readTimeout = 30000
                    instanceFollowRedirects = true
                    useCaches = false
                    setRequestProperty("Cache-Control", "no-cache")
                }

                if (connection.responseCode != 200) {
                    throw java.io.IOException("Server gagal: HTTP ${connection.responseCode}")
                }

                val total = connection.contentLength.toLong()
                var count = 0L
                val buffer = ByteArray(65536)

                connection.inputStream.use { input ->
                    java.io.FileOutputStream(apk).use { output ->
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            count += n

                            if (total > 0) {
                                val pct = ((count * 100) / total).toInt().coerceIn(0, 100)
                                runOnUiThread {
                                    if (!isFinishing && !isDestroyed) {
                                        progress.setProgress(pct)
                                        progress.setMessage("Mengunduh pembaruan... $pct%")
                                    }
                                }
                            }
                        }
                        output.fd.sync()
                    }
                }

                if (count < 1_000_000L) {
                    throw java.io.IOException("Ukuran APK tidak wajar.")
                }

                val signature = ByteArray(2)
                java.io.FileInputStream(apk).use {
                    if (it.read(signature) != 2 ||
                        signature[0] != 0x50.toByte() ||
                        signature[1] != 0x4B.toByte()
                    ) {
                        throw java.io.IOException("File unduhan bukan APK yang valid.")
                    }
                }
                file = apk
            } catch (e: Exception) {
                error = e.message ?: "Unduhan gagal."
            } finally {
                connection?.disconnect()
            }

            runOnUiThread {
                progress.dismiss()
                if (isFinishing || isDestroyed) return@runOnUiThread

                val apk = file
                if (apk == null) {
                    Toast.makeText(this, "Unduhan gagal: $error", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }

                try {
                    val contentUri = androidx.core.content.FileProvider.getUriForFile(
                        this, "$packageName.fileprovider", apk
                    )
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                        setDataAndType(contentUri, "application/vnd.android.package-archive")
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(intent)
                } catch (_: Exception) {
                    Toast.makeText(this, "Pemasang Android tidak dapat dibuka.", Toast.LENGTH_LONG).show()
                }
            }
        }, "cyber-neon-apk-download").start()
    }

    private fun showSplash() {
        val splash = android.widget.FrameLayout(this).apply {
            setBackgroundColor(bg)
        }

        val splashContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24))
        }

        val logo = object : View(this) {
            private val paint = android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG
            )

            override fun onDraw(canvas: android.graphics.Canvas) {
                super.onDraw(canvas)
                val cx = width / 2f
                val cy = height / 2f
                val r = dp(39).toFloat()
                val path = android.graphics.Path().apply {
                    moveTo(cx, cy - r)
                    lineTo(cx + r * 0.866f, cy - r * 0.5f)
                    lineTo(cx + r * 0.866f, cy + r * 0.5f)
                    lineTo(cx, cy + r)
                    lineTo(cx - r * 0.866f, cy + r * 0.5f)
                    lineTo(cx - r * 0.866f, cy - r * 0.5f)
                    close()
                }

                paint.color = accent
                paint.style = android.graphics.Paint.Style.STROKE
                paint.strokeWidth = dp(2).toFloat()
                paint.setShadowLayer(dp(9).toFloat(), 0f, 0f, accent)
                canvas.drawPath(path, paint)

                val inner = android.graphics.Path().apply {
                    moveTo(cx, cy - r + dp(10))
                    lineTo(cx + r * 0.866f - dp(9), cy - r * 0.5f + dp(5))
                    lineTo(cx + r * 0.866f - dp(9), cy + r * 0.5f - dp(5))
                    lineTo(cx, cy + r - dp(10))
                    lineTo(cx - r * 0.866f + dp(9), cy + r * 0.5f - dp(5))
                    lineTo(cx - r * 0.866f + dp(9), cy - r * 0.5f + dp(5))
                    close()
                }
                paint.strokeWidth = dp(1).toFloat()
                paint.alpha = 170
                canvas.drawPath(inner, paint)

                val play = android.graphics.Path().apply {
                    moveTo(cx - dp(9).toFloat(), cy - dp(15).toFloat())
                    lineTo(cx + dp(16).toFloat(), cy)
                    lineTo(cx - dp(9).toFloat(), cy + dp(15).toFloat())
                    close()
                }
                paint.style = android.graphics.Paint.Style.FILL
                paint.alpha = 255
                canvas.drawPath(play, paint)
                paint.clearShadowLayer()
            }
        }.apply {
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        }

        splashContent.addView(
            logo,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(112)
            )
        )

        val title = TextView(this).apply {
            text = "CYBER NEON TV"
            textSize = 23f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setShadowLayer(dp(12).toFloat(), 0f, 0f, accent)
            setPadding(0, dp(8), 0, dp(10))
        }
        splashContent.addView(title)

        val message = TextView(this).apply {
            text = "Menyiapkan pemutar..."
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(muted)
            setPadding(0, 0, 0, dp(20))
        }
        splashContent.addView(message)

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

                val total = right - left
                val block = total * 0.42f
                val start = left + (total + block) * phase - block

                canvas.save()
                canvas.clipRect(track)
                paint.color = accent
                paint.setShadowLayer(dp(6).toFloat(), 0f, 0f, accent)
                val rect = android.graphics.RectF(
                    start, top, start + block, bottom
                )
                canvas.drawRoundRect(
                    rect, dp(3).toFloat(), dp(3).toFloat(), paint
                )
                paint.clearShadowLayer()
                canvas.restore()
            }
        }.apply {
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        }

        splashContent.addView(
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
        splashContent.addView(neonCaption)


        // Tulisan hak cipta di tengah bawah layar pembuka.
        val copyright = TextView(this).apply {
            text = "© Copyright Developer Bossku™"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(accent)
            setShadowLayer(dp(5).toFloat(), 0f, 0f, accent)
            letterSpacing = 0.04f
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        splash.addView(
            splashContent,
            android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )

        splash.addView(
            copyright,
            android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply {
                bottomMargin = dp(18)
            }
        )

        splashShowing = true
        setContentView(splash)
        splashAnimator?.cancel()
        splashAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1500L
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                val phase = it.animatedValue as Float
                loadingLine.phase = phase
                loadingLine.invalidate()
                val glow = kotlin.math.sin(phase * 2.0 * Math.PI).toFloat()
                logo.alpha = 0.78f + 0.22f * glow
                logo.scaleX = 0.94f + 0.06f * (glow + 1f)
                logo.scaleY = logo.scaleX
            }
            start()
        }

        splashTimeoutRunnable = Runnable {
            dismissSplash()
        }
        window.decorView.postDelayed(splashTimeoutRunnable!!, 300_000L)
    }


    private fun showVideoLoading() {
        if (!videoLoadingAnimationEnabled) {
            hideVideoLoading()
            return
        }

        val overlay = videoLoadingOverlay ?: return
        if (isFinishing || isDestroyed) return

        if (overlay.visibility == View.VISIBLE &&
            videoLoadingAnimator?.isRunning == true
        ) return

        overlay.visibility = View.VISIBLE
        videoLoadingAnimator?.cancel()

        videoLoadingAnimator =
            android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1400L
                repeatCount = android.animation.ValueAnimator.INFINITE
                interpolator = android.view.animation.LinearInterpolator()
                addUpdateListener {
                    val phase = it.animatedValue as Float
                    (videoLoadingMark as? NeonLoadingMark)?.phase = phase
                    videoLoadingMark?.alpha = 0.78f + 0.22f *
                        kotlin.math.sin(phase * 2.0 * Math.PI).toFloat()
                    videoLoadingMark?.invalidate()
                }
                start()
            }
    }

    private fun hideVideoLoading() {
        videoLoadingAnimator?.cancel()
        videoLoadingAnimator = null
        videoLoadingOverlay?.visibility = View.GONE
    }

    private inner class NeonLoadingMark(context: android.content.Context) :
        View(context) {
        var phase = 0f
        private val paint = android.graphics.Paint(
            android.graphics.Paint.ANTI_ALIAS_FLAG
        )

        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)

            val cx = width / 2f
            val cy = height / 2f
            val r = dp(32).toFloat()

            // Cincin neon berputar.
            val oval = android.graphics.RectF(
                cx - r, cy - r, cx + r, cy + r
            )
            canvas.save()
            canvas.rotate(phase * 360f, cx, cy)
            paint.style = android.graphics.Paint.Style.STROKE
            paint.strokeWidth = dp(3).toFloat()
            paint.color = accent
            paint.alpha = 100
            paint.clearShadowLayer()
            canvas.drawArc(oval, 0f, 360f, false, paint)
            paint.alpha = 255
            paint.setShadowLayer(dp(12).toFloat(), 0f, 0f, accent)
            canvas.drawArc(oval, -90f, 245f, false, paint)
            canvas.restore()

            // Lambang heksagon ION.
            val hex = android.graphics.Path().apply {
                moveTo(cx, cy - dp(23))
                lineTo(cx + dp(20), cy - dp(11))
                lineTo(cx + dp(20), cy + dp(11))
                lineTo(cx, cy + dp(23))
                lineTo(cx - dp(20), cy + dp(11))
                lineTo(cx - dp(20), cy - dp(11))
                close()
            }
            paint.style = android.graphics.Paint.Style.STROKE
            paint.strokeWidth = dp(2).toFloat()
            paint.alpha = 255
            paint.setShadowLayer(dp(10).toFloat(), 0f, 0f, accent)
            canvas.drawPath(hex, paint)

            val play = android.graphics.Path().apply {
                moveTo(cx - dp(6), cy - dp(10))
                lineTo(cx + dp(11), cy)
                lineTo(cx - dp(6), cy + dp(10))
                close()
            }
            paint.style = android.graphics.Paint.Style.FILL
            canvas.drawPath(play, paint)
            paint.clearShadowLayer()
        }
    }

    private fun dismissSplash() {
        if (!splashShowing) return
        splashShowing = false

        splashTimeoutRunnable?.let {
            window.decorView.removeCallbacks(it)
        }
        splashTimeoutRunnable = null

        splashAnimator?.cancel()
        splashAnimator = null

        if (!isFinishing && !isDestroyed) {
            setContentView(root)
        }
    }

    private fun restoreSavedPlaylist() {
        // Sumber playlist ditetapkan oleh aplikasi, bukan URL yang tersimpan.
        val savedPlaylistUrl = fixedPlaylistUrl
        urlInput.setText(savedPlaylistUrl)

        appPrefs.edit()
            .putString("playlist_url", savedPlaylistUrl)
            .apply()

        window.decorView.post {
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

            loadPlaylist(savedPlaylistUrl, headers, autoPlayFirst = true)
        }
    }

    override fun onConfigurationChanged(
        newConfig: android.content.res.Configuration
    ) {
        super.onConfigurationChanged(newConfig)

        // Jangan bangun ulang UI saat ukuran jendela PIP berubah.
        // Pertahankan ExoPlayer dan posisi media yang sedang berjalan.
        if (
            fullscreen ||
            splashShowing ||
            !::playerView.isInitialized ||
            (
                android.os.Build.VERSION.SDK_INT >=
                    android.os.Build.VERSION_CODES.O &&
                isInPictureInPictureMode
            )
        ) return

        val oldPlayerView = playerView
        val currentPlayer = player
        val shouldResume = currentPlayer?.playWhenReady ?: false
        val savedResizeMode = oldPlayerView.resizeMode

        val selectedChannelIndex =
            if (::channelSpinner.isInitialized) {
                channelSpinner.selectedItemPosition
            } else {
                0
            }

        // Lepaskan tampilan lama tanpa menghentikan pemutaran atau
        // memaksa stream live kembali ke posisi sebelumnya.
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
            useController = false
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


        val loadingOverlay = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(205, 2, 8, 20))
            visibility = View.GONE
            isClickable = false
            isFocusable = false
        }
        videoLoadingOverlay = loadingOverlay

        val loadingContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }

        val loadingMark = NeonLoadingMark(this).apply {
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        }
        videoLoadingMark = loadingMark

        loadingContent.addView(
            loadingMark,
            LinearLayout.LayoutParams(dp(104), dp(104))
        )

        loadingOverlay.addView(
            loadingContent,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )

        videoContainer.addView(
            loadingOverlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        liveIndicatorDot = View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.rgb(0, 245, 255))
            }
        }

        val liveLabel = TextView(this).apply {
            text = "LIVE"
            setTextColor(Color.rgb(0, 245, 255))
            textSize = 10f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
            letterSpacing = 0.04f
        }

        liveIndicator = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 0)
            background = null
            addView(
                liveIndicatorDot,
                LinearLayout.LayoutParams(dp(8), dp(8))
            )
            addView(
                liveLabel,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                ).apply {
                    marginStart = dp(6)
                }
            )
            visibility = View.GONE
        }

        videoContainer.addView(
            liveIndicator,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(28),
                Gravity.BOTTOM or Gravity.START
            ).apply {
                leftMargin = dp(8)
                bottomMargin = dp(8)
            }
        )

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
                setPadding(0, 0, 0, 0)
                setBackgroundColor(Color.TRANSPARENT)
                background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            }

            // Ikon tetap berfungsi, tanpa kotak atau warna latar.
            view.background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            view.backgroundTintList =
                android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)
            view.stateListAnimator = null

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
                        // Ubah mode ukuran video tanpa mengubah fullscreen.
                        playerView.resizeMode = when (position) {
                            1 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                            2 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }

                        playerView.requestLayout()
                        playerView.invalidate()
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
        // Slider kecerahan dipindahkan ke panel vertikal pada toolbar video.
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

        // Panel kontrol tambahan dibuka melalui roda gigi.
        settingsPanel.visibility = View.GONE

        val gearButton = android.widget.ImageButton(this).apply {
            contentDescription = "Buka menu pengaturan video"
            setImageResource(R.drawable.ic_settings_neon)
            imageTintList =
                android.content.res.ColorStateList.valueOf(accent)
            setBackgroundColor(Color.TRANSPARENT)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setPadding(dp(3), dp(3), dp(3), dp(3))
            setOnClickListener {
                settingsPanel.visibility = View.GONE
                sourcePanel.visibility = View.GONE
                showGearMenu()
                scheduleControlsAutoHide()
            }
        }

        // Tombol volume Cyber Neon: tampilkan kontrol volume Android.
        val volumeNeonButton = android.widget.ImageButton(this).apply {
            contentDescription = "Atur volume media"
            setImageResource(R.drawable.ic_volume_neon)
            imageTintList =
                android.content.res.ColorStateList.valueOf(accent)
            setBackgroundColor(Color.TRANSPARENT)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setPadding(dp(3), dp(3), dp(3), dp(3))
            setOnClickListener {
                val audio = getSystemService(AUDIO_SERVICE) as
                    android.media.AudioManager
                audio.adjustStreamVolume(
                    android.media.AudioManager.STREAM_MUSIC,
                    android.media.AudioManager.ADJUST_SAME,
                    android.media.AudioManager.FLAG_SHOW_UI
                )
            }
        }

        // Tombol subtitle Cyber Neon: pilih subtitle eksternal.
        val subtitleNeonButton = android.widget.ImageButton(this).apply {
            contentDescription = "Pilih file subtitle"
            setImageResource(R.drawable.ic_subtitle_neon)
            imageTintList =
                android.content.res.ColorStateList.valueOf(accent)
            setBackgroundColor(Color.TRANSPARENT)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setPadding(dp(3), dp(3), dp(3), dp(3))
            setOnClickListener {
                val intent = android.content.Intent(
                    android.content.Intent.ACTION_OPEN_DOCUMENT
                ).apply {
                    addCategory(android.content.Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                startActivityForResult(intent, 7341)
            }
        }

        // Tombol fullscreen Cyber Neon.
        val fullscreenNeonButton = android.widget.ImageButton(this).apply {
            contentDescription = "Buka atau tutup layar penuh"
            setImageResource(R.drawable.ic_fullscreen_neon)
            imageTintList =
                android.content.res.ColorStateList.valueOf(accent)
            setBackgroundColor(Color.TRANSPARENT)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setPadding(dp(3), dp(3), dp(3), dp(3))
            setOnClickListener {
                toggleFullscreen()
            }
        }

        // Tombol ukuran Cyber Neon: Fit, Fill, atau Zoom.
        val sizeNeonButton = android.widget.ImageButton(this).apply {
            contentDescription = "Pilih ukuran video"
            setImageResource(R.drawable.ic_size_neon)
            imageTintList =
                android.content.res.ColorStateList.valueOf(accent)
            setBackgroundColor(Color.TRANSPARENT)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setPadding(dp(3), dp(3), dp(3), dp(3))
            setOnClickListener {
                val popup = android.widget.PopupMenu(this@MainActivity, this)
                listOf("Fit", "Fill", "Zoom").forEachIndexed { index, title ->
                    popup.menu.add(title).setOnMenuItemClickListener {
                        val mode = when (index) {
                            1 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                            2 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }

                        playerView.resizeMode = mode
                        playerView.requestLayout()
                        playerView.invalidate()

                        if (sizeSpinner.selectedItemPosition != index) {
                            sizeSpinner.setSelection(index)
                        }

                        android.widget.Toast.makeText(
                            this@MainActivity,
                            "Mode ukuran: $title",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                        true
                    }
                }
                popup.show()
            }
        }

        // Ikon resolusi Cyber Neon di sebelah kiri roda gigi.
        val resolutionButton = android.widget.ImageButton(this).apply {
            contentDescription = "Pilih resolusi video"
            setImageResource(R.drawable.ic_resolution_neon)
            imageTintList =
                android.content.res.ColorStateList.valueOf(accent)
            setBackgroundColor(Color.TRANSPARENT)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setPadding(dp(3), dp(3), dp(3), dp(3))
            setOnClickListener {
                val popup = android.widget.PopupMenu(this@MainActivity, this)
                val adapter = resolutionSpinner.adapter

                if (adapter != null) {
                    for (index in 0 until adapter.count) {
                        val label = adapter.getItem(index)?.toString()
                            ?: continue

                        popup.menu.add(label).setOnMenuItemClickListener {
                            resolutionSpinner.setSelection(index)
                            true
                        }
                    }
                }

                popup.show()
            }
        }



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

        // Panel pengaturan ditampilkan di dalam layar video.
        videoContainer.addView(
            settingsPanel,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        )

// Tombol kecerahan Cyber Neon: tampilkan/sembunyikan slider.
        val brightnessNeonButton = android.widget.ImageButton(this).apply {
            contentDescription = "Atur kecerahan layar"
            setImageResource(R.drawable.ic_brightness_neon)
            imageTintList =
                android.content.res.ColorStateList.valueOf(accent)
            setBackgroundColor(Color.TRANSPARENT)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setPadding(dp(3), dp(3), dp(3), dp(3))
            setOnClickListener {
                brightnessPopup?.let { panel ->
                    panel.visibility =
                        if (panel.visibility == View.VISIBLE) View.GONE
                        else View.VISIBLE
                }
                scheduleControlsAutoHide()
            }
        }

        // Popup slider kecerahan vertikal di dekat ikon kecerahan.
        (brightnessBar.parent as? ViewGroup)?.removeView(brightnessBar)

        val brightnessPopupPanel = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            setPadding(dp(4), dp(8), dp(4), dp(8))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.argb(210, 3, 12, 30))
                setStroke(dp(1), Color.rgb(0, 220, 255))
                cornerRadius = dp(12).toFloat()
            }
            visibility = View.GONE
            addView(
                brightnessBar,
                FrameLayout.LayoutParams(
                    dp(132),
                    dp(36),
                    Gravity.CENTER
                )
            )
        }

        brightnessBar.rotation = -90f
        brightnessBar.progressTintList =
            android.content.res.ColorStateList.valueOf(accent)
        brightnessBar.thumbTintList =
            android.content.res.ColorStateList.valueOf(accent)
        brightnessBar.progressBackgroundTintList =
            android.content.res.ColorStateList.valueOf(
                Color.argb(85, 0, 220, 255)
            )
        brightnessBar.contentDescription = "Kecerahan vertikal"
        brightnessPopup = brightnessPopupPanel

        videoContainer.addView(
            brightnessPopupPanel,
            FrameLayout.LayoutParams(
                dp(52),
                dp(160),
                Gravity.BOTTOM or Gravity.END
            ).apply {
                rightMargin = dp(208)
                bottomMargin = dp(44)
            }
        )

        // Toolbar Cyber Neon di dalam area video.
        // Urutan kiri ke kanan: kecerahan, CC, volume,
        // fullscreen, ukuran, resolusi.
        // Roda gigi tetap berada paling kanan.
        fun placeVideoIcon(view: View, right: Int) {
            videoContainer.addView(
                view,
                FrameLayout.LayoutParams(
                    dp(30),
                    dp(30),
                    Gravity.BOTTOM or Gravity.END
                ).apply {
                    rightMargin = dp(right)
                    bottomMargin = dp(8)
                }
            )
        }

        placeVideoIcon(brightnessNeonButton, 220)
        placeVideoIcon(subtitleNeonButton, 184)
        placeVideoIcon(volumeNeonButton, 148)
        placeVideoIcon(fullscreenNeonButton, 112)
        placeVideoIcon(sizeNeonButton, 76)
        placeVideoIcon(resolutionButton, 40)

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
            // Daftar channel tetap di sisi kiri.
            val channelSide = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
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

            // Video dan tombol pengaturan di sisi kanan.
            val videoSide = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
            videoSide.addView(
                videoContainer,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
                )
            )

            mainContent.addView(
                channelSide,
                LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 0.42f
                )
            )
            mainContent.addView(
                videoSide,
                LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 0.58f
                )
            )
        } else {
            // Potret: video, tombol pengaturan, kategori, dan daftar channel.
            mainContent.addView(
                videoContainer,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(220)
                ).apply {
                    bottomMargin = dp(2)
                }
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

        // Bilah bawah layar utama.
        val bottomExitBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        }

        // Roda gigi tunggal di pojok kanan bawah area video.
        videoContainer.addView(
            gearButton,
            FrameLayout.LayoutParams(
                dp(32),
                dp(32),
                Gravity.BOTTOM or Gravity.END
            ).apply {
                rightMargin = dp(8)
                bottomMargin = dp(8)
            }
        )

        val bottomRefreshButton = android.widget.ImageButton(this).apply {
            contentDescription = "Muat ulang stream saat ini"
            setImageResource(R.drawable.ic_refresh)
            imageTintList =
                android.content.res.ColorStateList.valueOf(accent)
            setBackgroundColor(Color.TRANSPARENT)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setPadding(dp(5), dp(5), dp(5), dp(5))
            setOnClickListener {
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
        }

        val bottomExitButton = android.widget.ImageButton(this).apply {
            contentDescription = "Keluar dari aplikasi"
            setImageResource(R.drawable.ic_power)
            imageTintList =
                android.content.res.ColorStateList.valueOf(accent)
            setBackgroundColor(Color.TRANSPARENT)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setPadding(dp(5), dp(5), dp(5), dp(5))
            setOnClickListener {
                showCyberExitDialog()
            }
        }

        bottomExitBar.addView(
            bottomRefreshButton,
            LinearLayout.LayoutParams(dp(42), dp(38)).apply {
                marginEnd = dp(10)
            }
        )

        // Ruang fleksibel kiri untuk menyeimbangkan merek Cyber Neon TV.
        bottomExitBar.addView(
            View(this),
            LinearLayout.LayoutParams(0, 1, 1f)
        )

        // Identitas merek di tengah antara refresh dan exit.
        val cyberNeonBrand = TextView(this).apply {
            text = "C Y B E R • N E O N • T V"
            setTextColor(accent)
            textSize = 15f
            letterSpacing = 0.015f
            gravity = Gravity.CENTER
            maxLines = 1
            includeFontPadding = false
            setShadowLayer(
                dp(5).toFloat(),
                0f,
                0f,
                Color.rgb(0, 180, 220)
            )
            contentDescription = "Cyber Neon TV"
        }

        bottomExitBar.addView(
            cyberNeonBrand,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(38)
            )
        )

        // Ruang fleksibel kanan menjaga jarak menuju tombol exit.
        bottomExitBar.addView(
            View(this),
            LinearLayout.LayoutParams(0, 1, 1f)
        )

        bottomExitBar.addView(
            bottomExitButton,
            LinearLayout.LayoutParams(dp(42), dp(38))
        )

        // Bilah tombol berada di luar video, tepat setelah area video.
        val videoParent = videoContainer.parent as? LinearLayout
        val videoIndex = videoParent?.indexOfChild(videoContainer) ?: -1
        if (videoParent != null && videoIndex >= 0) {
            videoParent.addView(
                bottomExitBar,
                videoIndex + 1,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(42)
                )
            )
        } else {
            // Pengaman jika posisi induk video belum tersedia.
            root.addView(
                bottomExitBar,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(42)
                )
            )
        }

        // Kontrol Cyber Neon disembunyikan otomatis saat menonton.
        videoOverlayControls = listOf(
            brightnessNeonButton,
            subtitleNeonButton,
            volumeNeonButton,
            fullscreenNeonButton,
            sizeNeonButton,
            resolutionButton,
            gearButton
        )

        // Tombol website Cyber Neon TV di tengah bagian paling bawah layar.
        val cyberWebsiteButton = android.widget.TextView(this).apply {
            text = "🌐  WEBSITE"
            textSize = 12f
            letterSpacing = 0.08f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(0, 245, 255))
            setPadding(dp(18), dp(4), dp(18), dp(4))
            isClickable = true
            isFocusable = false
            setBackgroundColor(Color.TRANSPARENT)
            backgroundTintList = null
            stateListAnimator = null
            elevation = 0f
            contentDescription = "Buka website Cyber Neon TV"
            setOnClickListener {
                try {
                    startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(
                                "https://cyber-neon-tv-site.parabola-satellite.workers.dev"
                            )
                        )
                    )
                } catch (e: Exception) {
                    android.widget.Toast.makeText(
                        this@MainActivity,
                        "Website tidak dapat dibuka",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        root.addView(
            cyberWebsiteButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(36)
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(5)
                bottomMargin = dp(4)
            }
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
        if (channelGrid.width <= 0) {
            channelGrid.post {
                if (::channelGrid.isInitialized && channelGrid.width > 0) {
                    renderChannelGrid(channels)
                }
            }
            return
        }

        channelGrid.removeAllViews()
        epgScheduleViews.clear()
        channelUrlStatusViews.clear()
        channelUrlIndicatorViews.clear()
        channelGrid.columnCount = 1

        // Hitung lebar kartu dari panel yang benar-benar tersedia.
        val cardWidth = (channelGrid.width - dp(12)).coerceAtLeast(dp(1))

        val visibleChannels = channels.filter { entry ->
            selectedChannelCategory == "SEMUA" ||
                (channelUrlStatuses[entry.url] ?: "MEMERIKSA") ==
                    selectedChannelCategory
        }

        // Penghitung seluruh entri playlist, tidak terpengaruh filter kategori.
        val totalChannelsLabel = TextView(this).apply {
            text = "TOTAL SIARAN: ${playlistChannels.size}"
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.rgb(0, 245, 255))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(Color.rgb(8, 17, 31))
                setStroke(dp(1), Color.rgb(0, 190, 220))
            }
        }
        channelGrid.addView(
            totalChannelsLabel,
            GridLayout.LayoutParams().apply {
                width = cardWidth
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                setMargins(dp(3), dp(3), dp(3), dp(6))
            }
        )

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

        val playlistIndexByEntry = HashMap<PlaylistEntry, Int>(
            playlistChannels.size
        )
        playlistChannels.forEachIndexed { index, entry ->
            if (!playlistIndexByEntry.containsKey(entry)) {
                playlistIndexByEntry[entry] = index
            }
        }

        visibleChannels.forEach { entry ->
            val index = playlistIndexByEntry[entry]
                ?: playlistChannels.indexOfFirst { it.url == entry.url }

            if (index < 0) return@forEach
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(6), dp(4), dp(6), dp(4))
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = dp(10).toFloat()
                    setColor(Color.rgb(12, 13, 32))
                    setStroke(dp(1), Color.rgb(139, 92, 246))
                }
                elevation = dp(1).toFloat()
                isClickable = true
                isFocusable = true
                setOnFocusChangeListener { focusedView, hasFocus ->
                    (focusedView.background as? android.graphics.drawable.GradientDrawable)
                        ?.apply {
                            setColor(
                                if (hasFocus) Color.rgb(10, 35, 48)
                                else Color.rgb(12, 13, 32)
                            )
                            setStroke(
                                dp(if (hasFocus) 3 else 1),
                                if (hasFocus) Color.rgb(0, 245, 255)
                                else Color.rgb(139, 92, 246)
                            )
                        }
                    focusedView.elevation =
                        dp(if (hasFocus) 6 else 1).toFloat()
                }
            }

            val numberLabel = TextView(this).apply {
                text = (index + 1).toString().padStart(3, '0')
                textSize = 10f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.rgb(0, 245, 255))
                gravity = Gravity.CENTER
                maxLines = 1
                contentDescription = "Nomor siaran ${index + 1}"
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(40)).apply {
                    marginEnd = dp(4)
                }
            }
            card.addView(numberLabel)

            val logo = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                    marginEnd = dp(8)
                }
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(Color.TRANSPARENT)
                setPadding(dp(2), dp(2), dp(2), dp(2))
                setImageResource(R.drawable.ic_cyber_neon_tv)
                contentDescription = "Logo ${entry.name}"
            }
            card.addView(logo)

            val channelInfo = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val name = TextView(this).apply {
                text = entry.name
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.rgb(0, 245, 255))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            val normalizedStreamUrl = entry.url
                .substringBefore('?')
                .substringBefore('#')
                .lowercase()

            val streamFormat = when {
                normalizedStreamUrl.endsWith(".m3u8") -> "HLS"
                normalizedStreamUrl.endsWith(".mpd") -> "DASH"
                else -> "STREAM"
            }

            val nameRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val formatBadge = TextView(this).apply {
                text = streamFormat
                textSize = 8f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.rgb(192, 132, 252))
                gravity = Gravity.CENTER
                includeFontPadding = false
                setPadding(dp(5), dp(3), dp(5), dp(3))
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = dp(4).toFloat()
                    setColor(Color.rgb(48, 38, 74))
                    setStroke(dp(1), Color.rgb(192, 132, 252))
                }
                contentDescription = "Format stream $streamFormat"
            }

            nameRow.addView(
                name,
                LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
            )

            nameRow.addView(
                formatBadge,
                LinearLayout.LayoutParams(
                    dp(43),
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = dp(6)
                }
            )

            channelInfo.addView(
                nameRow,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )

            val (currentProgramme, nextProgramme) = findEpgSchedule(entry)
            val currentText = TextView(this).apply {
                text = currentProgramme?.let {
                    "● ${formatEpgTime(it.startMillis)} ${it.title}"
                } ?: if (epgLoading) "● Jadwal sedang dimuat..." else "● Jadwal EPG tidak tersedia"
                textSize = 9f
                setTextColor(Color.rgb(220, 248, 255))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            channelInfo.addView(currentText)

            val nextText = TextView(this).apply {
                text = nextProgramme?.let {
                    "› ${formatEpgTime(it.startMillis)} Berikutnya: ${it.title}"
                } ?: "› Acara berikutnya belum tersedia"
                textSize = 8f
                setTextColor(Color.rgb(145, 180, 200))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            channelInfo.addView(nextText)
            epgScheduleViews.add(EpgScheduleView(entry, currentText, nextText))
            card.addView(
                channelInfo,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
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
                height = dp(66)
                setMargins(dp(3), dp(3), dp(3), dp(3))
            }
            channelGrid.addView(card, params)


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


    private fun showGearMenu() {
        val options = arrayOf(
            "Picture-in-Picture (PIP)",
            "Orientasi layar",
            "Football Reminder"
        )
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Pengaturan")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> enterPlayerPip()
                    1 -> showOrientationDialog()
                    2 -> showFootballReminderDialog()
                }
            }
            .setNegativeButton("Tutup", null)
            .create()

        dialog.setOnShowListener {
            val list = dialog.listView
            list.dividerHeight = dp(2)
            list.setPadding(0, dp(2), 0, dp(2))
            for (i in 0 until list.childCount) {
                list.getChildAt(i)?.let { item ->
                    item.minimumHeight = dp(42)
                }
            }
            dialog.getButton(-2)?.textSize = 12f
            dialog.getButton(-2)?.setPadding(dp(12), 0, dp(12), 0)
        }
        dialog.show()
    }

    private fun showOrientationDialog() {
        val options = arrayOf("Otomatis", "Potret", "Lanskap")
        var selected = when (requestedOrientation) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT -> 1
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE -> 2
            else -> 0
        }

        android.app.AlertDialog.Builder(this)
            .setTitle("Orientasi layar")
            .setSingleChoiceItems(options, selected) { _, which ->
                selected = which
            }
            .setPositiveButton("Terapkan") { _, _ ->
                applyOrientation(selected)
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun containsPlayerView(view: View): Boolean {
        if (view === playerView) return true
        if (view !is ViewGroup) return false

        for (i in 0 until view.childCount) {
            if (containsPlayerView(view.getChildAt(i))) return true
        }
        return false
    }

    private fun prepareVideoOnlyPip(): Boolean {
        if (!::root.isInitialized ||
            !::playerView.isInitialized ||
            !::videoContainer.isInitialized
        ) return false

        pipSavedVisibility.clear()

        fun hideNonVideoViews(group: ViewGroup) {
            for (i in 0 until group.childCount) {
                val child = group.getChildAt(i)

                if (child === playerView) continue

                if (containsPlayerView(child)) {
                    if (child is ViewGroup) hideNonVideoViews(child)
                } else {
                    pipSavedVisibility[child] = child.visibility
                    child.visibility = View.GONE
                }
            }
        }

        hideNonVideoViews(root)

        val original = videoContainer.layoutParams
        pipOriginalVideoLayoutParams = original

        val pipParams = when (original) {
            is LinearLayout.LayoutParams ->
                LinearLayout.LayoutParams(original).apply {
                    width = ViewGroup.LayoutParams.MATCH_PARENT
                    height = ViewGroup.LayoutParams.MATCH_PARENT
                    weight = 0f
                }
            is FrameLayout.LayoutParams ->
                FrameLayout.LayoutParams(original).apply {
                    width = ViewGroup.LayoutParams.MATCH_PARENT
                    height = ViewGroup.LayoutParams.MATCH_PARENT
                }
            else ->
                ViewGroup.LayoutParams(original).apply {
                    width = ViewGroup.LayoutParams.MATCH_PARENT
                    height = ViewGroup.LayoutParams.MATCH_PARENT
                }
        }

        videoContainer.layoutParams = pipParams
        videoContainer.requestLayout()
        playerView.requestLayout()
        return true
    }

    private fun restorePipLayout() {
        pipOriginalVideoLayoutParams?.let {
            if (::videoContainer.isInitialized) {
                videoContainer.layoutParams = it
            }
        }
        pipOriginalVideoLayoutParams = null

        pipSavedVisibility.forEach { (view, visibility) ->
            view.visibility = visibility
        }
        pipSavedVisibility.clear()

        if (::videoContainer.isInitialized) videoContainer.requestLayout()
        if (::root.isInitialized) {
            root.requestLayout()
            root.invalidate()
        }
    }

    private fun enterPlayerPip() {
        if (android.os.Build.VERSION.SDK_INT <
            android.os.Build.VERSION_CODES.O
        ) {
            android.widget.Toast.makeText(
                this, "PIP memerlukan Android 8.0 atau lebih baru.",
                android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }

        if (fullscreen) {
            android.widget.Toast.makeText(
                this, "Keluar dari layar penuh sebelum memakai PIP.",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (player == null) {
            android.widget.Toast.makeText(
                this, "Pemutar belum siap.",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }

        try {
            if (!prepareVideoOnlyPip()) {
                restorePipLayout()
                return
            }

            val params =
                android.app.PictureInPictureParams.Builder()
                    .setAspectRatio(android.util.Rational(16, 9))
                    .build()

            if (!enterPictureInPictureMode(params)) {
                restorePipLayout()
            }
        } catch (_: Exception) {
            restorePipLayout()
            android.widget.Toast.makeText(
                this, "PIP tidak dapat dibuka pada perangkat ini.",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun showFootballReminderDialog() {
        android.app.AlertDialog.Builder(this@MainActivity)
            .setTitle("⚽ ION TV Football Reminder")
            .setMessage(
                "Jadwal gratis dari OpenFootball untuk lima liga: " +
                "Inggris, Jerman, Spanyol, Italia, dan Prancis.\n\n" +
                "Pengingat dijadwalkan lima menit sebelum kick-off. " +
                "Jadwal dapat berubah dan kelengkapan data tidak dijamin."
            )
            .setPositiveButton("Perbarui jadwal") { _, _ ->
                if (android.os.Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(
                        android.Manifest.permission.POST_NOTIFICATIONS
                    ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    requestPermissions(
                        arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                        9251
                    )
                    android.widget.Toast.makeText(
                        this@MainActivity,
                        "Izinkan notifikasi, lalu tekan roda gigi dan perbarui jadwal lagi.",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                } else {
                    FootballReminder.refresh(this@MainActivity) { count, leagueCount ->
                        if (count < 0) {
                            android.widget.Toast.makeText(
                                this@MainActivity,
                                "Jadwal gagal diunduh. Periksa internet lalu coba lagi.",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        } else {
                            val exact = FootballReminder.hasExactAlarmAccess(
                                this@MainActivity
                            )
                            val alarmInfo = if (exact) {
                                "Alarm presisi tersedia."
                            } else {
                                "Izin alarm presisi belum aktif; pengingat bisa terlambat."
                            }
                            android.widget.Toast.makeText(
                                this@MainActivity,
                                "Disiapkan $count pengingat dari $leagueCount liga. $alarmInfo",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
            .setNeutralButton("Izin alarm presisi") { _, _ ->
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    try {
                        startActivity(
                            android.content.Intent(
                                android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                android.net.Uri.parse("package:$packageName")
                            )
                        )
                    } catch (_: Exception) {
                        android.widget.Toast.makeText(
                            this@MainActivity,
                            "Buka Setelan Android > Aplikasi > ION TV > Alarm dan pengingat.",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                } else {
                    android.widget.Toast.makeText(
                        this@MainActivity,
                        "Android ini tidak memerlukan akses alarm khusus.",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton("Tutup", null)
            .show()
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
            videoOverlayControls.forEach { it.visibility = View.GONE }
            brightnessPopup?.visibility = View.GONE

            if (fullscreen) {
                fullscreenControlsView?.visibility = View.GONE
            }
            if (::playerView.isInitialized) {
                playerView.hideController()
            }
        }
        hideControlsRunnable = task
        controlsHandler.postDelayed(task, 4000L)
    }

    private fun showPlaybackControls() {
        videoOverlayControls.forEach { it.visibility = View.VISIBLE }

        if (fullscreen) {
            fullscreenControlsView?.visibility = View.VISIBLE
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
            // Toolbar atas fullscreen disembunyikan.
            // Kontrol Cyber Neon pada video tetap dipertahankan.
            toolbar.visibility = View.GONE

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
                        playerView.requestLayout()
                        playerView.invalidate()

                        if (sizeSpinner.selectedItemPosition != position) {
                            sizeSpinner.setSelection(position)
                        }

                        updateSizeSelection(position)
                        sizeSelector.visibility = View.GONE

                        android.widget.Toast.makeText(
                            this@MainActivity,
                            "Mode ukuran: $labelText",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()

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

            // Sembunyikan slider dan ikon volume/kecerahan di fullscreen.
            volumeSlider.visibility = View.GONE
            volumeLabel.visibility = View.GONE
            brightnessSlider.visibility = View.GONE
            brightnessLabel.visibility = View.GONE

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
            private fun styleCompactText(view: View): View {
                (view as? TextView)?.apply {
                    textSize = resources.getDimension(
                        R.dimen.ion_text_spinner
                    ) / resources.displayMetrics.scaledDensity
                    maxLines = 1
                    setTextColor(accent)
                    setPadding(dp(4), 0, dp(2), 0)
                }
                return view
            }

            override fun getView(
                position: Int,
                convertView: View?,
                parent: ViewGroup
            ): View {
                return styleCompactText(
                    super.getView(position, convertView, parent)
                )
            }

            override fun getDropDownView(
                position: Int,
                convertView: View?,
                parent: ViewGroup
            ): View {
                return styleCompactText(
                    super.getDropDownView(position, convertView, parent)
                )
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

    private fun loadPlaylist(
        url: String,
        headers: Map<String, String>,
        autoPlayFirst: Boolean = false
    ) {
        status.text = ""

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
                        val name = it.name.trim()
                        name.equals("001 TRAKTIR KOPI", ignoreCase = true) ||
                            name.equals("01 TRAKTIR KOPI", ignoreCase = true) ||
                            name.equals("TRAKTIR KOPI", ignoreCase = true) ||
                            name.equals("TES", ignoreCase = true)
                    }

                runOnUiThread {
                    dismissSplash()
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
                        startEpgScheduleRefresh()
                        loadEpgSchedule()
                        channelSpinner.adapter = ArrayAdapter(
                            this,
                            android.R.layout.simple_spinner_dropdown_item,
                            channels.map { it.name }
                        )
                        channelSpinner.setSelection(0)
                        status.text =
                            "Playlist dimuat: ${channels.size} channel."
                        startUrlMonitoring()

                        if (autoPlayFirst) {
                            channelSpinner.post {
                                if (!isFinishing && !isDestroyed &&
                                    loadedPlaylistUrl == url &&
                                    playlistChannels.isNotEmpty()
                                ) {
                                    channelSpinner.setSelection(0)
                                    playStream()
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    dismissSplash()
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
        var pendingTvgId: String? = null
        var pendingGroupTitle: String? = null
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

                pendingTvgId = Regex("""tvg-id="([^"]*)"""")
                    .find(line)?.groupValues?.getOrNull(1)
                    ?.takeIf { it.isNotBlank() }

                pendingGroupTitle = Regex("""group-title="([^"]*)"""")
                    .find(line)?.groupValues?.getOrNull(1)?.trim()

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
                        val isKbtrtvMoviesSeries =
                            pendingGroupTitle?.equals(
                                "MOVIES/SERIES KBTRTV",
                                ignoreCase = true
                            ) == true

                        if (!isKbtrtvMoviesSeries) {
                            result.add(
                                PlaylistEntry(
                                    name = pendingName!!,
                                    url = resolved,
                                    logoUrl = pendingLogoUrl,
                                    tvgId = pendingTvgId,
                                    headers = pendingHeaders.toMap(),
                                    drmScheme = pendingDrmScheme,
                                    drmLicenseUrl = pendingLicenseUrl,
                                    drmLicenseHeaders = pendingLicenseHeaders.toMap()
                                )
                            )
                        }
                    }
                } catch (_: Exception) {
                    // Abaikan URL yang tidak valid.
                }
                pendingName = null
                pendingGroupTitle = null
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

    @Deprecated("Deprecated in Android API; retained for document-picker compatibility")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: android.content.Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 7341 || resultCode != RESULT_OK) return

        val uri = data?.data ?: return
        val exo = player
        if (exo == null || exo.currentMediaItem == null) {
            Toast.makeText(
                this,
                "Putar video terlebih dahulu sebelum memasang subtitle.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        try {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
                // Sebagian penyedia dokumen tidak mendukung izin persisten.
            }

            var displayName = uri.lastPathSegment.orEmpty()
            try {
                contentResolver.query(
                    uri,
                    arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val column = cursor.getColumnIndex(
                            android.provider.OpenableColumns.DISPLAY_NAME
                        )
                        if (column >= 0) displayName = cursor.getString(column) ?: displayName
                    }
                }
            } catch (_: Exception) {
                // Gunakan nama dari URI jika nama berkas tidak tersedia.
            }

            val extension = displayName.substringAfterLast('.', "").lowercase()
            val mime = when (extension) {
                "srt" -> MimeTypes.APPLICATION_SUBRIP
                "vtt" -> MimeTypes.TEXT_VTT
                "ass", "ssa" -> MimeTypes.TEXT_SSA
                else -> null
            }

            if (mime == null) {
                Toast.makeText(
                    this,
                    "Format belum didukung. Gunakan file SRT, VTT, ASS, atau SSA.",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            val subtitle = MediaItem.SubtitleConfiguration.Builder(uri)
                .setMimeType(mime)
                .setLanguage("id")
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()

            val index = exo.currentMediaItemIndex
            val position = exo.currentPosition
            val wasPlaying = exo.playWhenReady
            val oldItem = exo.currentMediaItem ?: return
            val oldSubtitles =
                oldItem.localConfiguration?.subtitleConfigurations.orEmpty()
            val updatedItem = oldItem.buildUpon()
                .setSubtitleConfigurations(oldSubtitles + subtitle)
                .build()

            exo.replaceMediaItem(index, updatedItem)
            exo.seekTo(index, position)
            exo.playWhenReady = wasPlaying

            Toast.makeText(
                this,
                "Subtitle ditambahkan: $displayName",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Gagal memasang subtitle: ${e.message ?: "kesalahan tidak diketahui"}",
                Toast.LENGTH_LONG
            ).show()
        }
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

                    videoFrameRendered = false
                    showVideoLoading()
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
                            when (playbackState) {
                                Player.STATE_BUFFERING -> showVideoLoading()
                                Player.STATE_IDLE, Player.STATE_ENDED -> hideVideoLoading()
                                Player.STATE_READY -> {
                                    if (videoFrameRendered) hideVideoLoading()
                                }
                            }

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
                            videoFrameRendered = true
                            hideVideoLoading()
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
                            videoFrameRendered = false
                            hideVideoLoading()
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
                    controlsHandler.removeCallbacks(liveIndicatorRunnable)
                    controlsHandler.post(liveIndicatorRunnable)
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

                val baseLabel = "${format.height}p (${format.width}×${format.height})"
                val bitrateLabel = if (format.bitrate > 0) {
                    String.format(
                        java.util.Locale.ROOT,
                        "%.2f Mbps",
                        format.bitrate / 1_000_000.0
                    )
                } else {
                    "bitrate tidak diketahui"
                }
                val label = "$baseLabel · $bitrateLabel"
                options.add(VideoOption(label, group, i))
            }
        }

        videoOptions = options.sortedBy { it.group.getTrackFormat(it.trackIndex).height }

        val previousSelection = resolutionSpinner.selectedItem?.toString()
        updatingResolution = true
        val labels = listOf("Resolusi: otomatis") + videoOptions.map { it.label }
        resolutionSpinner.adapter = compactSpinnerAdapter(labels)
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

    private fun updateLiveIndicator() {
        if (!::liveIndicator.isInitialized) return

        val isLive = player?.isCurrentMediaItemLive == true
        liveIndicator.visibility = if (isLive) View.VISIBLE else View.GONE

        if (isLive) {
            if (liveIndicatorDot.animation == null) {
                liveIndicatorDot.startAnimation(
                    android.view.animation.AlphaAnimation(0.2f, 1.0f).apply {
                        duration = 650L
                        repeatMode = android.view.animation.Animation.REVERSE
                        repeatCount = android.view.animation.Animation.INFINITE
                    }
                )
            }
        } else {
            liveIndicatorDot.clearAnimation()
            liveIndicatorDot.alpha = 1f
        }
    }

    private fun releasePlayer() {
        controlsHandler.removeCallbacks(liveIndicatorRunnable)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (::playerView.isInitialized) playerView.player = null
        player?.release()
        player = null
        videoOptions = emptyList()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration
    ) {
        super.onPictureInPictureModeChanged(
            isInPictureInPictureMode, newConfig
        )

        if (isInPictureInPictureMode) {
            hideControlsRunnable?.let {
                controlsHandler.removeCallbacks(it)
            }
            return
        }

        window.decorView.post {
            if (isFinishing || isDestroyed ||
                isInPictureInPictureMode ||
                !::playerView.isInitialized
            ) return@post

            restorePipLayout()
            videoContainer.visibility = View.VISIBLE
            playerView.visibility = View.VISIBLE
            videoContainer.requestLayout()
            playerView.requestLayout()
            root.requestLayout()
            root.invalidate()
        }
    }

    override fun onBackPressed() {
        if (fullscreen) {
            toggleFullscreen()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        splashTimeoutRunnable?.let {
            window.decorView.removeCallbacks(it)
        }
        splashTimeoutRunnable = null
        splashShowing = false
        splashAnimator?.cancel()
        splashAnimator = null
        hideVideoLoading()
        videoLoadingOverlay = null
        videoLoadingMark = null
        urlMonitorRunnable?.let { controlsHandler.removeCallbacks(it) }
        controlsHandler.removeCallbacks(liveIndicatorRunnable)
        controlsHandler.removeCallbacks(epgScheduleRefreshRunnable)
        epgRetryRunnable?.let { controlsHandler.removeCallbacks(it) }
        epgRetryRunnable = null
        epgScheduleViews.clear()
        channelUrlBlinkRunnable?.let {
            controlsHandler.removeCallbacks(it)
        }
        channelUrlBlinkRunnable = null
        urlCheckExecutor.shutdownNow()
        releasePlayer()
        super.onDestroy()
    }
}
