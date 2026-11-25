package com.vladimir.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.KeyEvent
import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.content.res.Configuration
import android.widget.*
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.UUID
import java.net.URLEncoder
import android.content.pm.ActivityInfo
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import android.text.format.DateFormat
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import kotlin.concurrent.thread

class MainActivity : Activity() {

    data class Entry(
        val title: String,
        val url: String,
        val group: String,
        val logo: String = "",
        val type: String = "tv",
        val epgId: String = "",
        val archiveDays: Int = 0,
        val archiveBase: String = "",
        val archiveStreamId: String = "",
        val description: String = "",
        val catchupSource: String = "",
        val catchupMode: String = "",
        val catchupCorrectionHours: Double = 0.0,
        val portalRequest: String = "",
        val portalCollection: Boolean = false,
        val videoMetadata: String = "",
        val stalkerId: String = "",
        val stalkerCommand: String = "",
        val stalkerResolved: Boolean = false
    )

    private val prefs by lazy {
        getSharedPreferences("vladimir_online", MODE_PRIVATE)
    }

    private val items = mutableListOf<Entry>()
    private val vodItems = mutableListOf<Entry>()
    private val vodPlaylistItems = linkedMapOf<String, List<Entry>>()
    private var vodLoadGeneration = 0
    private var currentEntry: Entry? = null
    private var catalogGroup: String? = null
    private var playbackChannels = emptyList<Entry>()
    private var returnToLiveAfterArchive = false
    private var liveChannelPlayback = false
    private var playingArchiveProgramme: Programme? = null
    private var videoPanelSide: Boolean? = null
    private var guideWaitingForData = false
    @Volatile private var epgLoadGeneration = 0
    private val epgCacheLock = Any()
    private var epgStatusText = ""
    private var epgStatusView: TextView? = null
    private var epgStatusColor = Color.WHITE
    private val playlistStates = mutableMapOf<String, Int>()
    private val playlistStatusViews = mutableMapOf<String, TextView>()
    private var vodStatusView: TextView? = null
    private var vodStatusText = ""
    private var vodStatusColor = Color.WHITE
    data class Programme(val channel: String, val start: Long, val stop: Long, val title: String, val description: String)
    private val epgSchedule = mutableMapOf<String, MutableList<Programme>>()
    private var epgAliases = emptyMap<String, String>()
    private val stalkerEpgChannels = linkedMapOf<String, Entry>()
    private var epgIcons = emptyMap<String, String>()
    private val qualitySuffix = Regex("""(?i)(?:\s+(?:UHD|FHD|HD|SD|4K|BY|RU|DE))+$""")
    private val guideAliasGroups = listOf(
        setOf("ntv", "ntv.ru", "нтв"),
        setOf("1tv", "1tv.ru", "1 канал", "первый канал", "channel one russia", "ort"),
        setOf("russia 1", "россия 1", "россия1", "rtr", "rtr planeta"),
        setOf("russia 24", "россия 24", "россия24", "russia24"),
        setOf("ren tv", "рентв", "рен тв", "ren-tv"),
        setOf("sts", "стс"), setOf("tnt", "тнт"), setOf("mir", "мир"),
        setOf("zvezda", "звезда"), setOf("domashniy", "домашний"),
        setOf("karusel", "карусель"), setOf("match tv", "матч тв", "матч!"),
        setOf("kultura", "россия к", "россия культура", "культура")
    )
    private data class GuideFeed(
        val programmes: Map<String, MutableList<Programme>>,
        val names: Map<String, Set<String>>,
        val icons: Map<String, String>
    )
    private var playerFrame: FrameLayout? = null
    private var sidePanel: View? = null
    private var sidePanelBack: (() -> Unit)? = null
    private val clockHandler = Handler(Looper.getMainLooper())
    private val lists = mutableListOf<Pair<String, String>>()

    private var current = "home"
    private var lang = "ru"
    private var activeList = -1
    private var lastSourceRefreshAt = 0L
    private var lastVodRefreshEpoch = 0L
    private var dlnaScanGeneration = 0

    private lateinit var base: LinearLayout
    private lateinit var content: LinearLayout

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var homePreviewPlayer: ExoPlayer? = null
    private var homePreviewView: PlayerView? = null
    private var networkSpeedView: TextView? = null
    private var networkSpeedMonitor: Runnable? = null
    private var stalkerClient: StalkerPortalClient? = null
    private var stalkerPlayGeneration = 0
    private var radioStations = emptyList<RadioStation>()
    private var radioLoadGeneration = 0
    private var musicLoadGeneration = 0
    private var mediaSession: androidx.media3.session.MediaSession? = null
    private var playbackErrorPanel: View? = null
    private var resumePlaybackOnStart = false
    private var playingVideo: Entry? = null
    private val saveVideoProgress = object : Runnable {
        override fun run() {
            if (current != "player" || playingVideo == null) return
            rememberVideoProgress()
            clockHandler.postDelayed(this, 15000L)
        }
    }

    @Volatile private var remoteServer: ServerSocket? = null
    private val remoteToken = UUID.randomUUID()
        .toString().replace("-", "")

    @Volatile private var remoteStarting = false
    @Volatile private var remoteRunning = false

    private val bg = Color.rgb(7, 20, 63)
    private val panel = Color.rgb(18, 74, 150)
    private val accent: Int
        get() = when (prefs.getString("theme", "blue")) {
            "red" -> Color.rgb(229, 35, 66)
            "green" -> Color.rgb(34, 171, 111)
            "purple" -> Color.rgb(148, 90, 222)
            "gold" -> Color.rgb(211, 155, 50)
            "sky" -> Color.rgb(102, 209, 255)
            else -> Color.rgb(0, 178, 255)
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun isPhone(): Boolean {
        val mode = resources.configuration.uiMode and
            Configuration.UI_MODE_TYPE_MASK
        val width = resources.displayMetrics.widthPixels /
            resources.displayMetrics.density
        return mode != Configuration.UI_MODE_TYPE_TELEVISION &&
            !packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK) &&
            !packageManager.hasSystemFeature("amazon.hardware.fire_tv") && width < 700
    }

    private fun tr(ru: String, de: String, en: String): String =
        when (lang) {
            "de" -> de
            "en" -> en
            else -> ru
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!isPhone()) {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }

        lang = prefs.getString("language", "ru") ?: "ru"
        reportPreviousProcessExit()

        try {
            val stored = prefs.getString("playlists", "[]") ?: "[]"
            val array = JSONArray(stored)

            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                lists.add(
                    obj.optString("name") to obj.optString("url")
                )
            }
        } catch (_: Exception) {
        }

        activeList = prefs.getInt(
            "active",
            if (lists.isEmpty()) -1 else 0
        )

        val bufferSeconds = prefs.getInt("buffer_size_seconds", 30).coerceIn(15, 300)
        val maxBufferMs = bufferSeconds * 1000
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs((maxBufferMs / 2).coerceAtLeast(5_000), maxBufferMs,
                minOf(5_000, maxBufferMs), minOf(8_000, maxBufferMs))
            .setTargetBufferBytes(when {
                bufferSeconds <= 30 -> 48 * 1024 * 1024
                bufferSeconds <= 60 -> 64 * 1024 * 1024
                bufferSeconds <= 120 -> 96 * 1024 * 1024
                else -> 128 * 1024 * 1024
            })
            .build()
        player = ExoPlayer.Builder(this).setLoadControl(loadControl).build()
        setMediaSessionEnabled(prefs.getBoolean("system_media_session", true))
        player?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    playbackStartedAt = android.os.SystemClock.elapsedRealtime()
                    clearLiveStreamNotice()
                }
                val entry = currentEntry ?: return
                if (isPlaying && current == "player" && entry.type == "tv") {
                    val updated = (listOf(entry.url) + channelHistory().filterNot { it == entry.url }).take(30)
                    prefs.edit().putString("tv_history", JSONArray(updated).toString()).apply()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                clockHandler.removeCallbacks(bufferTimeout)
                if (playbackState == Player.STATE_BUFFERING && current == "player") {
                    clockHandler.postDelayed(bufferTimeout, 25_000L)
                }
                if (playbackState == Player.STATE_ENDED) {
                    if (current == "player" && liveChannelPlayback && player?.playWhenReady == true) {
                        retryPlayback("LIVE_EOF")
                        return
                    }
                    rememberVideoProgress(completed = true)
                    val next = nextEpisode
                    nextEpisode = null
                    if (current == "player" && next != null) clockHandler.post {
                        if (playbackScreenStarted && current == "player" && player?.playbackState == Player.STATE_ENDED && !isDestroyed) next()
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                clockHandler.removeCallbacks(bufferTimeout)
                val httpCode = generateSequence(error.cause) { it.cause }
                    .filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>()
                    .firstOrNull()?.responseCode
                if (httpCode == 408 || httpCode == 429 || (httpCode != null && httpCode in 500..599)) {
                    retryPlayback(error.errorCodeName)
                    return
                }
                when (error.errorCode) {
                    PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW,
                    PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                        retryPlayback(error.errorCodeName)
                    else -> {
                        cancelPlaybackRetry()
                        showPlaybackError(error.errorCodeName)
                    }
                }
            }
        })

        startRemoteServer()
        showHome()
        checkForAppUpdate(false)

    }

    private fun saveLists() {
        val array = JSONArray()

        lists.forEach {
            array.put(
                JSONObject()
                    .put("name", it.first)
                    .put("url", it.second)
            )
        }

        prefs.edit()
            .putString("playlists", array.toString())
            .putInt("active", activeList)
            .apply()
    }

    private fun background(color: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(12).toFloat()
        }

    private fun label(
        value: String,
        size: Float = 19f,
        bold: Boolean = false
    ): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(9), dp(12), dp(9))

            if (bold) {
                setTypeface(null, Typeface.BOLD)
            }
        }

    private val remoteFocusColor = Color.rgb(231, 54, 155)
    private var settingsButton: Button? = null

    private fun moduleEnabled(key: String, default: Boolean = true) = prefs.getBoolean("module_$key", default)

    private fun focusDown(upper: View, lower: View) {
        upper.nextFocusDownId = lower.id
        lower.nextFocusUpId = upper.id
    }

    private fun focusRight(left: View, right: View) {
        left.nextFocusRightId = right.id
        right.nextFocusLeftId = left.id
    }

    private fun button(
        value: String,
        action: () -> Unit
    ): Button =
        Button(this).apply {
            text = value
            textSize = 16f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = background(panel)
            isFocusable = true
            setOnFocusChangeListener { _, hasFocus ->
                background = background(if (hasFocus) remoteFocusColor else panel)
            }
            setOnClickListener { action() }
        }

    private var updateInProgress = false
    private var pendingApk: java.io.File? = null

    private fun versionParts(value: String): List<Int>? {
        val parts = value.removePrefix("v").split('.')
        if (parts.size != 3) return null
        val numbers = parts.map { it.toIntOrNull()?.takeIf { n -> n >= 0 } }
        return if (numbers.any { it == null }) null else numbers.filterNotNull()
    }

    private fun checkForAppUpdate(manual: Boolean) {
        if (updateInProgress) return
        val installed = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        val installedParts = versionParts(installed) ?: return
        updateInProgress = true
        thread {
            val result = runCatching {
                val connection = URL("https://api.github.com/repos/SchwaWladi/VladiMir-TV-Online/releases/latest")
                    .openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 8_000
                    connection.readTimeout = 8_000
                    connection.setRequestProperty("Accept", "application/vnd.github+json")
                    if (connection.responseCode == 404) return@runCatching null
                    check(connection.responseCode == 200)
                    val release = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                    val tag = release.getString("tag_name")
                    val next = versionParts(tag) ?: return@runCatching null
                    val firstDifference = next.zip(installedParts).firstOrNull { it.first != it.second }
                    if (firstDifference == null || firstDifference.first < firstDifference.second) return@runCatching null
                    val assets = release.getJSONArray("assets")
                    val base = "https://github.com/SchwaWladi/VladiMir-TV-Online/releases/download/$tag/"
                    val asset = (0 until assets.length()).asSequence().map { assets.getJSONObject(it) }
                        .map { it.optString("browser_download_url") }
                        .firstOrNull { it.startsWith(base) && it.removePrefix(base).matches(Regex("[A-Za-z0-9._-]+[.]apk")) }
                        ?: return@runCatching null
                    Triple(tag, asset, release.optString("body").take(1800))
                } finally {
                    connection.disconnect()
                }
            }
            runOnUiThread {
                updateInProgress = false
                if (isFinishing || isDestroyed || (!manual && current != "home")) return@runOnUiThread
                val release = result.getOrNull()
                if (release == null) {
                    if (manual) toast(if (result.isSuccess)
                        tr("Обновлений нет", "Keine Updates", "No updates available")
                    else tr("Не удалось проверить обновление", "Updateprüfung fehlgeschlagen", "Could not check for updates"))
                    return@runOnUiThread
                }
                AlertDialog.Builder(this)
                    .setTitle(tr("Доступна версия", "Neue Version", "New version") + " " + release.first)
                    .setMessage(release.third.ifBlank {
                        tr("Установить поверх текущей версии?", "Über die aktuelle Version installieren?",
                            "Install over the current version?")
                    })
                    .setPositiveButton(tr("Скачать", "Herunterladen", "Download")) { _, _ -> downloadUpdate(release.second) }
                    .setNegativeButton(tr("Позже", "Später", "Later"), null).show()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun validUpdate(file: java.io.File): Boolean {
        val flag = android.content.pm.PackageManager.GET_SIGNATURES
        val currentApp = packageManager.getPackageInfo(packageName, flag)
        val candidate = packageManager.getPackageArchiveInfo(file.absolutePath, flag) ?: return false
        val signers = currentApp.signatures.orEmpty().toSet()
        return candidate.packageName == packageName && candidate.versionCode > currentApp.versionCode &&
            signers.isNotEmpty() && signers == candidate.signatures.orEmpty().toSet()
    }

    private fun downloadUpdate(address: String) {
        if (updateInProgress) return
        updateInProgress = true
        toast(tr("Скачиваю обновление…", "Update wird heruntergeladen…", "Downloading update…"))
        thread {
            val result = runCatching {
                val dir = java.io.File(cacheDir, "updates").apply { mkdirs() }
                val target = java.io.File(dir, "update.apk")
                try {
                    val connection = URL(address).openConnection() as HttpURLConnection
                    try {
                        connection.connectTimeout = 10_000
                        connection.readTimeout = 20_000
                        check(connection.responseCode == 200 && connection.url.protocol == "https")
                        connection.inputStream.use { input ->
                            target.outputStream().buffered().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                var read = input.read(buffer)
                                var total = 0L
                                while (read != -1) {
                                    total += read
                                    check(total <= 96L * 1024 * 1024)
                                    output.write(buffer, 0, read)
                                    read = input.read(buffer)
                                }
                            }
                        }
                    } finally {
                        connection.disconnect()
                    }
                    check(validUpdate(target))
                    target
                } catch (error: Exception) {
                    target.delete()
                    throw error
                }
            }
            runOnUiThread {
                updateInProgress = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                val file = result.getOrNull()
                if (file != null) offerInstallation(file)
                else toast(tr("Обновление недоступно или подпись не совпадает",
                    "Update nicht verfügbar oder Signatur stimmt nicht", "Update unavailable or signature mismatch"))
            }
        }
    }

    private fun offerInstallation(file: java.io.File) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
                pendingApk = file
                startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")))
                return
            }
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.updates", file)
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (_: Exception) {
            pendingApk = null
            toast(tr("Не удалось открыть установщик", "Installer konnte nicht geöffnet werden", "Could not open installer"))
        }
    }

    override fun onResume() {
        super.onResume()
        val elapsed = android.os.SystemClock.elapsedRealtime()
        val firstResume = lastSourceRefreshAt == 0L
        if (firstResume || elapsed - lastSourceRefreshAt >= 5 * 60_000L) {
            lastSourceRefreshAt = elapsed
            if (activeList in lists.indices) loadPlaylist(activeList)
            if (prefs.getString("xtream_server", "").orEmpty().isNotBlank()) loadXtream()
        }
        val now = System.currentTimeMillis()
        val vodInterval = prefs.getInt("vod_refresh_hours", 24).coerceIn(1, 168) * 60L * 60L * 1000L
        val previousVodRefresh = prefs.getLong("vod_last_refresh", 0L)
        if (firstResume || previousVodRefresh == 0L || now - previousVodRefresh >= vodInterval) {
            lastVodRefreshEpoch = now
            loadVodPlaylist()
        }
        val file = pendingApk ?: return
        pendingApk = null
        if (android.os.Build.VERSION.SDK_INT >= 26 && packageManager.canRequestPackageInstalls()) offerInstallation(file)
    }

    private fun reportPreviousProcessExit() {
        if (android.os.Build.VERSION.SDK_INT < 30) return
        runCatching {
            val manager = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
            val exit = manager.getHistoricalProcessExitReasons(packageName, 0, 1).firstOrNull()
                ?: return@runCatching
            if (exit.timestamp <= prefs.getLong("last_reported_exit", 0L)) return@runCatching
            prefs.edit().putLong("last_reported_exit", exit.timestamp).apply()
            val reason = when (exit.reason) {
                android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> tr("нехватка памяти", "Speichermangel", "low memory")
                android.app.ApplicationExitInfo.REASON_CRASH -> tr("сбой приложения", "App-Absturz", "app crash")
                android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> tr("сбой нативного кода", "Absturz im nativen Code", "native crash")
                android.app.ApplicationExitInfo.REASON_ANR -> tr("приложение не отвечало", "App reagierte nicht", "app not responding")
                else -> return@runCatching
            }
            AlertDialog.Builder(this)
                .setTitle(tr("Предыдущий выход из приложения", "Letztes Beenden der App", "Previous app exit"))
                .setMessage(reason + " (код " + exit.reason + ")")
                .setPositiveButton("OK", null).show()
        }
    }

    private fun artwork(url: String, width: Int, height: Int, bounded: Boolean = false, fallbackUrl: String = ""): ImageView =
        ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(width), dp(height)).apply {
                marginEnd = dp(10)
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.rgb(35, 45, 63))
            contentDescription = tr("Изображение канала или фильма", "Sender- oder Filmbild", "Channel or movie artwork")
            if (url.startsWith("https://") || url.startsWith("http://")) {
                val request = Glide.with(this@MainActivity).load(url)
                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                    .centerInside()
                if (fallbackUrl.isNotBlank() && fallbackUrl != url) {
                    request.error(Glide.with(this@MainActivity).load(fallbackUrl)
                        .error(android.R.drawable.ic_menu_gallery).centerInside())
                } else request.error(android.R.drawable.ic_menu_gallery)
                if (bounded) request.override(dp(width), dp(height))
                request.into(this)
            } else {
                setImageResource(android.R.drawable.ic_menu_gallery)
            }
        }

    private fun sectionTitle(value: String) {
        content.addView(label(value, 25f, true))
    }

    private fun tile(
        title: String,
        subtitle: String,
        artwork: Int = 0,
        tint: Int = accent,
        action: () -> Unit
    ): LinearLayout {
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true
            isClickable = true
            setPadding(dp(14), dp(10), dp(14), dp(10))

            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    Color.rgb(25, 150, 239),
                    Color.rgb(84, 55, 199)
                )
            ).apply {
                cornerRadius = dp(18).toFloat()
            }

            setOnFocusChangeListener { _, focused ->
                (background as GradientDrawable).apply {
                    colors = if (focused) intArrayOf(Color.rgb(255, 78, 163), Color.rgb(40, 119, 238))
                        else intArrayOf(Color.rgb(25, 150, 239), Color.rgb(84, 55, 199))
                    setStroke(dp(if (focused) 3 else 1), if (focused) Color.WHITE else tint)
                }
            }
            (background as GradientDrawable).setStroke(dp(1), tint)
            setOnClickListener { action() }
        }

        if (artwork != 0) {
            val icon = ImageView(this).apply {
                setImageResource(artwork)
                if (artwork != R.drawable.ic_cinema && artwork != R.drawable.ic_live_television) setColorFilter(tint)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            view.addView(icon, LinearLayout.LayoutParams(dp(if (artwork == R.drawable.ic_cinema || artwork == R.drawable.ic_live_television) 64 else 36), dp(36)))
        }
        view.addView(label(title, 22f, true))
        view.addView(label(subtitle, 13f))
        return view
    }

    private val handwrittenBrand by lazy {
        Typeface.createFromAsset(assets, "caveat-bold.ttf")
    }

    private fun scaffold(page: String) {
        stopHomePreview()
        networkSpeedMonitor?.let(clockHandler::removeCallbacks)
        networkSpeedMonitor = null
        networkSpeedView = null
        cancelPlaybackRetry()
        clockHandler.removeCallbacks(bufferTimeout)
        internetPairGeneration++
        if (isPhone()) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        closeSidePanel()
        current = page
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        base = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }

        setContentView(base)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        brand.addView(
            label("", 34f, true).apply {
                typeface = handwrittenBrand
                contentDescription = "ВладиМир TV Онлайн"
                text = android.text.SpannableString("ВладиМир TV").apply {
                    fun color(from: Int, to: Int, value: Int) {
                        setSpan(android.text.style.ForegroundColorSpan(value), from, to,
                            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    color(0, 5, 0xFF52DDFF.toInt())
                    color(5, 8, 0xFFFFCB52.toInt())
                    color(9, 11, 0xFFFF79BD.toInt())
                }
            }
        )
        brand.addView(TextView(this).apply {
            text = "ONLINE"
            textSize = 12f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.rgb(0, 191, 255), Color.rgb(221, 55, 177))).apply {
                cornerRadius = dp(14).toFloat()
            }
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
        header.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))

        val settings = button("⚙") { showSettings() }.apply { id = View.generateViewId() }
        settingsButton = settings
        header.addView(settings)

        val screen = base
        if (prefs.getBoolean("show_clock", true)) {
            val clock = label("", 17f, true).apply { gravity = Gravity.END }
            header.addView(clock)
            val refresh = object : Runnable {
                override fun run() {
                    if (base === screen && current != "player" && !isDestroyed) {
                        val now = Date()
                        val locale = Locale.forLanguageTag(lang)
                        val time = SimpleDateFormat("HH:mm", locale).format(now)
                        clock.text = if (prefs.getBoolean("show_date", true)) {
                            val date = SimpleDateFormat("EEEE, d MMMM", locale).format(now)
                            "$time\n$date"
                        } else time
                        clockHandler.postDelayed(this, 30000)
                    }
                }
            }
            refresh.run()
        }
        base.addView(header)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
        }

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(20))
        }

        scroll.addView(content)

        base.addView(
            scroll,
            LinearLayout.LayoutParams(-1, 0, 1f)
        )
    }

    private fun showHome() {
        scaffold("home")
        val phone = isPhone()
        val workspace = LinearLayout(this).apply {
            orientation = if (phone) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        }
        val navigation = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = background(Color.rgb(19, 28, 43))
        }
        val main = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(4), 0)
        }
        workspace.addView(navigation, LinearLayout.LayoutParams(if (phone) -1 else dp(204), if (phone) -2 else -1))
        workspace.addView(main, if (phone) LinearLayout.LayoutParams(-1, -2)
            else LinearLayout.LayoutParams(0, -1, 1f))
        content.addView(workspace, if (phone) LinearLayout.LayoutParams(-1, -2)
            else LinearLayout.LayoutParams(-1, 0, 1f))

        val navItems = mutableListOf<View>()
        fun navigationItem(title: String, action: () -> Unit): Button {
            val item = button(title, action).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                textSize = 15f
                id = View.generateViewId()
            }
            navigation.addView(item, LinearLayout.LayoutParams(-1, dp(44)).apply { bottomMargin = dp(4) })
            navItems.add(item)
            return item
        }
        val live = if (moduleEnabled("live")) navigationItem(tr("Прямой эфир", "Live-TV", "Live TV")) { showTvMenu() } else null
        if (moduleEnabled("guide") || moduleEnabled("archive"))
            navigationItem(tr("Телегид и архив", "Programm und Archiv", "Guide and catch-up")) { showCatalog("guide") }
        if (moduleEnabled("vod")) navigationItem(tr("Видеотека", "Mediathek", "Video library")) { showMediaHub() }
        if (moduleEnabled("online")) navigationItem(tr("Онлайн", "Online", "Online")) { showOnlineHub() }
        if (moduleEnabled("stalker") && prefs.getString("stalker_url", "").orEmpty().isNotBlank())
            navigationItem("Stalker Portal") { showStalkerCategories() }
        if (moduleEnabled("radio")) navigationItem(tr("Радио", "Radio", "Radio")) { showRadioMenu() }
        if (moduleEnabled("music")) navigationItem(tr("Музыка", "Musik", "Music")) { showMusicHub() }
        if (moduleEnabled("youtube")) navigationItem("YouTube") { openYouTube() }
        if (moduleEnabled("fireplace")) navigationItem(tr("Камин", "Kamin", "Fireplace")) { showFireplace() }
        navigationItem(tr("Мои источники", "Meine Quellen", "My sources")) { showSources() }
        navigationItem(tr("Настроить через телефон", "Per Handy einrichten", "Set up from phone")) { showInternetPairing() }
        navigationItem(tr("Настройки", "Einstellungen", "Settings")) { showSettings() }
        if (!phone) navigation.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
        val speed = label(tr("Wi-Fi: измеряю…", "WLAN: messe…", "Wi-Fi: measuring…"), 12f, true).apply {
            setTextColor(Color.rgb(172, 208, 246))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.rgb(26, 43, 66))
                cornerRadius = dp(10).toFloat()
            }
            networkSpeedView = this
        }
        navigation.addView(speed, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        startNetworkSpeedMonitor(speed)

        val recent = channelHistory().firstNotNullOfOrNull { url -> items.firstOrNull { it.type == "tv" && it.url == url } }
            ?: items.firstOrNull { it.type == "tv" }
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            minimumHeight = dp(if (phone) 260 else 350)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(0, 181, 255), Color.rgb(40, 78, 220), Color.rgb(116, 46, 191))).apply {
                cornerRadius = dp(18).toFloat()
            }
        }
        val watch: Button
        if (recent != null) {
            hero.addView(label(tr("СЕЙЧАС В ЭФИРЕ", "JETZT LIVE", "LIVE NOW"), 14f, true))
            val summary = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            info.addView(label(recent.title, 22f, true))
            info.addView(label(programmeLine(recent, System.currentTimeMillis()), 15f))
            summary.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
            val monitor = PlayerView(this).apply {
                useController = false
                controllerAutoShow = false
                resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                setShutterBackgroundColor(Color.BLACK)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                isFocusable = false
                isFocusableInTouchMode = false
                background = GradientDrawable().apply {
                    setColor(Color.BLACK)
                    cornerRadius = dp(12).toFloat()
                    setStroke(dp(2), Color.rgb(107, 205, 255))
                }
                clipToOutline = true
            }
            summary.addView(monitor, LinearLayout.LayoutParams(dp(if (phone) 136 else 208), dp(if (phone) 77 else 117))
                .apply { marginStart = dp(14) })
            hero.addView(summary, LinearLayout.LayoutParams(-1, 0, 1f))
            watch = button(tr("▶ Смотреть канал", "▶ Sender ansehen", "▶ Watch channel")) { play(recent) }
            hero.post { if (current == "home" && !isDestroyed) startHomePreview(recent, monitor) }
        } else {
            hero.addView(label(tr("Подключи свой плейлист", "Eigene Playlist hinzufügen", "Add your playlist"), 24f, true))
            watch = button(tr("Добавить телеканалы", "TV-Sender hinzufügen", "Add TV channels")) { showPlaylists() }
        }
        watch.id = View.generateViewId()
        hero.addView(watch)
        val cinema = tile(tr("Видеотека", "Mediathek", "Video library"), "", R.drawable.ic_cinema, accent) {
            showMediaHub()
        }.apply {
            id = View.generateViewId()
            gravity = Gravity.CENTER_VERTICAL
        }
        val videoPoster = newestVideoPoster()
        if (videoPoster == null) cinema.getChildAt(cinema.childCount - 1).visibility = View.GONE
        videoPoster?.let { entry ->
            val poster = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = entry.title
                clipToOutline = true
                background = GradientDrawable().apply { cornerRadius = dp(10).toFloat() }
                Glide.with(this@MainActivity).load(entry.logo)
                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC).centerCrop()
                    .error(android.R.drawable.ic_menu_gallery).into(this)
            }
            cinema.addView(poster, 0, LinearLayout.LayoutParams(-1, dp(92)).apply { bottomMargin = dp(8) })
            (cinema.getChildAt(3) as? TextView)?.apply {
                text = entry.title
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
        }
        val top = LinearLayout(this).apply {
            orientation = if (phone) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            addView(hero, if (phone) LinearLayout.LayoutParams(-1, -2) else
                LinearLayout.LayoutParams(0, dp(356), 1.05f).apply { marginEnd = dp(8) })
            addView(cinema, if (phone) LinearLayout.LayoutParams(-1, dp(88)) else
                LinearLayout.LayoutParams(0, -1, 1f))
        }
        main.addView(top, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })

        val actions = LinearLayout(this).apply {
            orientation = if (phone) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        }
        val quickItems = mutableListOf<View>()
        fun quick(title: String, icon: Int, posterEntry: Entry? = null, action: () -> Unit) {
            val params = if (phone) LinearLayout.LayoutParams(-1, dp(88))
                else LinearLayout.LayoutParams(0, -1, 1f)
            params.setMargins(dp(4), dp(4), dp(4), dp(4))
            val card = tile(title, "", icon, accent, action)
            card.getChildAt(card.childCount - 1).visibility = View.GONE
            posterEntry?.let { entry ->
                card.orientation = LinearLayout.HORIZONTAL
                card.getChildAt(0)?.visibility = View.GONE
                val poster = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    contentDescription = entry.title
                    clipToOutline = true
                    Glide.with(this@MainActivity).load(entry.logo).centerCrop()
                        .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC).into(this)
                }
                card.addView(poster, 0, LinearLayout.LayoutParams(dp(70), dp(90)).apply { marginEnd = dp(10) })
                (card.getChildAt(2) as? TextView)?.text = title + "\n" + entry.title
            }
            card.id = View.generateViewId()
            card.gravity = Gravity.CENTER_VERTICAL
            quickItems.add(card)
            actions.addView(card, params)
        }
        quick(tr("ТВ-избранное", "TV-Favoriten", "TV favorites"), android.R.drawable.btn_star_big_on) { showCatalog("favorites") }
        quick(tr("Продолжить смотреть", "Weitersehen", "Continue watching"), R.drawable.ic_movie_theater,
            newestVideoPoster()) { showVideoShelf(0) }
        quick(newMoviesTitle(), android.R.drawable.ic_menu_gallery) { openNewMovies() }
        actions.minimumHeight = dp(108)
        main.addView(actions, if (phone) LinearLayout.LayoutParams(-1, -2)
            else LinearLayout.LayoutParams(-1, 0, 1f))
        if (!phone) {
            navItems.zipWithNext(::focusDown)
            quickItems.zipWithNext(::focusRight)
            settingsButton?.let { focusDown(it, watch) }
            live?.let { focusRight(it, watch) }
            focusRight(watch, cinema)
            focusRight(navItems.last(), quickItems.first())
            focusDown(watch, quickItems[1])
            focusDown(cinema, quickItems[2])
            quickItems.first().nextFocusUpId = watch.id
            navItems.drop(1).dropLast(1).forEach { it.nextFocusRightId = watch.id }
        }
        navItems.firstOrNull()?.requestFocus() ?: watch.requestFocus()
    }

    private fun startNetworkSpeedMonitor(target: TextView) {
        val uid = android.os.Process.myUid()
        var lastSample = android.os.SystemClock.elapsedRealtime()
        fun receivedBytes(): Long {
            val appBytes = android.net.TrafficStats.getUidRxBytes(uid)
            return if (appBytes != android.net.TrafficStats.UNSUPPORTED.toLong()) appBytes
            else android.net.TrafficStats.getTotalRxBytes()
        }
        var lastBytes = receivedBytes()
        val monitor = object : Runnable {
            override fun run() {
                if (current != "home" || target.parent == null || isDestroyed) return
                val now = android.os.SystemClock.elapsedRealtime()
                val bytes = receivedBytes()
                val elapsed = (now - lastSample).coerceAtLeast(1L)
                val mbps = if (bytes >= lastBytes && lastBytes >= 0L) (bytes - lastBytes) * 8.0 / elapsed / 1000.0 else 0.0
                lastSample = now
                lastBytes = bytes
                val networkName = runCatching {
                    val manager = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                    manager.activeNetwork?.let(manager::getNetworkCapabilities)?.let {
                        when {
                            it.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> tr("Wi-Fi", "WLAN", "Wi-Fi")
                            it.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> tr("Кабель", "LAN", "Ethernet")
                            else -> tr("Сеть", "Netzwerk", "Network")
                        }
                    }
                }.getOrNull()
                target.text = if (networkName == null) tr("Сеть не подключена", "Kein Netzwerk", "Network disconnected")
                else "$networkName · " + tr("приём %.1f Мбит/с", "Empfang %.1f Mbit/s", "receiving %.1f Mbps")
                    .format(Locale.US, mbps)
                clockHandler.postDelayed(this, 1500L)
            }
        }
        networkSpeedMonitor = monitor
        monitor.run()
    }

    private fun startHomePreview(entry: Entry, view: PlayerView) {
        stopHomePreview()
        if (current != "home" || view.parent == null || entry.url.isBlank()) return
        runCatching {
            val preview = ExoPlayer.Builder(this).build()
            homePreviewPlayer = preview
            homePreviewView = view
            view.player = preview
            preview.volume = 0f
            preview.repeatMode = Player.REPEAT_MODE_ONE
            preview.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    if (homePreviewPlayer === preview) stopHomePreview()
                }
            })
            preview.setMediaItem(MediaItem.fromUri(Uri.parse(entry.url)))
            preview.prepare()
            preview.playWhenReady = true
        }.onFailure { stopHomePreview() }
    }

    private fun stopHomePreview() {
        val view = homePreviewView
        val preview = homePreviewPlayer
        homePreviewView = null
        homePreviewPlayer = null
        view?.player = null
        preview?.release()
    }

    private fun channelHistory(): List<String> {
        val saved = prefs.getString("tv_history", null)
        if (saved == null) return listOfNotNull(prefs.getString("recent", null))
        val entries = runCatching { JSONArray(saved) }.getOrElse { JSONArray() }
        return (0 until entries.length()).map { entries.optString(it) }.filter { it.isNotBlank() }
    }

    private fun showChannelHistory() {
        scaffold("tv_history")
        sectionTitle(tr("Недавние каналы", "Zuletzt gesehene Sender", "Recent channels"))
        val byUrl = items.filter { it.type == "tv" }.associateBy { it.url }
        val recent = channelHistory().mapNotNull(byUrl::get)
        if (recent.isEmpty()) content.addView(label(tr("История пока пуста", "Noch kein Verlauf", "No viewing history yet")))
        recent.forEach { entry ->
            content.addView(button(entry.title) {
                portalPlaybackBack = { showChannelHistory() }
                play(entry, channelOrder = recent)
            })
        }
    }

    private fun videoShelfModes(): List<String> = listOf(
        tr("Продолжить просмотр", "Weiterschauen", "Continue watching"),
        tr("Просмотренные", "Angesehen", "Watched"),
        tr("Смотреть позже", "Merkliste", "Watch later"),
        tr("Избранное", "Favoriten", "Favorites")
    )

    private fun videoKey(entry: Entry): String =
        if (entry.portalRequest.isBlank()) entry.url else "vportal:" + entry.portalRequest

    private fun videoRecords(): JSONObject = runCatching {
        JSONObject(prefs.getString("video_history", "{}").orEmpty())
    }.getOrElse { JSONObject() }

    private fun videoRecord(entry: Entry): JSONObject = videoRecords().optJSONObject(videoKey(entry)) ?: JSONObject()

    private fun putVideoRecord(records: JSONObject, entry: Entry, record: JSONObject) {
        record.put("title", entry.title).put("url", entry.url).put("logo", entry.logo)
            .put("description", entry.description).put("portal", entry.portalRequest)
            .put("collection", entry.portalCollection).put("metadata", entry.videoMetadata)
            .put("updated", System.currentTimeMillis())
        records.put(videoKey(entry), record)
        prefs.edit().putString("video_history", records.toString()).apply()
    }

    private fun rememberVideoProgress(completed: Boolean = false) {
        val entry = playingVideo ?: return
        val activePlayer = player ?: return
        if (!completed && activePlayer.playbackState !in setOf(Player.STATE_READY, Player.STATE_BUFFERING)) return
        val position = activePlayer.currentPosition
        if (!completed && position <= 0L) return
        val records = videoRecords()
        val record = records.optJSONObject(videoKey(entry)) ?: JSONObject()
        record.put("position", if (completed) 0L else position).put("completed", completed)
        if (completed) record.put("later", false)
        putVideoRecord(records, entry, record)
    }

    private fun watchLaterButton(entry: Entry, onChange: (() -> Unit)? = null): Button {
        fun caption(saved: Boolean) = if (saved)
            tr("✓ В списке", "✓ Gemerkt", "✓ On watchlist")
        else tr("+ Смотреть позже", "+ Merken", "+ Watch later")
        val view = button(caption(videoRecord(entry).optBoolean("later"))) {}
        view.setOnClickListener {
            val records = videoRecords()
            val record = records.optJSONObject(videoKey(entry)) ?: JSONObject()
            val saved = !record.optBoolean("later")
            record.put("later", saved)
            putVideoRecord(records, entry, record)
            view.text = caption(saved)
            onChange?.invoke()
        }
        view.textSize = 12f
        return view
    }

    private fun showVideoShelf(mode: Int) {
        scaffold("video_history")
        sectionTitle(videoShelfModes()[mode])
        val records = videoRecords()
        val visible = records.keys().asSequence().mapNotNull { records.optJSONObject(it) }
            .filter { record ->
                when (mode) {
                    0 -> !record.optBoolean("completed") && record.optLong("position") > 0
                    1 -> record.optBoolean("completed")
                    2 -> record.optBoolean("later")
                    else -> record.optBoolean("favorite") || isLegacyVideoFavorite(record.optString("url"))
                }
            }.sortedByDescending { it.optLong("updated") }.toList()
        if (visible.isEmpty()) content.addView(label(tr("Список пока пуст", "Die Liste ist noch leer", "This list is empty")))
        val posters = PosterGrid(content)
        visible.forEach { record ->
            val entry = Entry(record.optString("title"), record.optString("url"), "",
                logo = record.optString("logo"), type = "movies", description = record.optString("description"),
                portalRequest = record.optString("portal"), portalCollection = record.optBoolean("collection"),
                videoMetadata = record.optString("metadata"))
            posters.add(entry) {
                showPosterDetails(entry) { openSavedVideo(entry) { showVideoShelf(mode) } }
            }
        }
    }

    private inner class PosterGrid(private val target: LinearLayout) {
        private val columns = if (isPhone()) 2 else 6
        private var row: LinearLayout? = null
        private var count = 0
        private val gaps = mutableListOf<View>()

        fun breakRow() {
            row = null
            count = 0
            gaps.clear()
        }

        private fun posterCellParams() = LinearLayout.LayoutParams(0, -2, 1f).apply {
            setMargins(dp(4), dp(4), dp(4), dp(8))
        }

        fun add(entry: Entry, fallbackIcon: Int = 0, action: () -> Unit) {
            val line = row ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                target.addView(this, LinearLayout.LayoutParams(-1, -2))
                repeat(columns) {
                    val gap = View(this@MainActivity)
                    gaps.add(gap)
                    addView(gap, posterCellParams())
                }
                row = this
            }
            val width = (resources.displayMetrics.widthPixels - dp(32)) / columns - dp(8)
            val height = (width * 3 / 2).coerceAtMost(dp(180))
            val card = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                isFocusable = true
                isClickable = true
                contentDescription = entry.title
                setPadding(dp(4), dp(4), dp(4), dp(4))
                background = background(panel)
                setOnFocusChangeListener { view, focused ->
                    view.background = background(if (focused) accent else panel)
                }
                setOnClickListener { action() }
            }
            card.addView(artwork(entry.logo, 120, 180, bounded = true).apply {
                if (entry.logo.isBlank() && fallbackIcon != 0) setImageResource(fallbackIcon)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(-1, height))
            card.addView(label(entry.title, 14f, true).apply {
                maxLines = 2
                minLines = 2
                gravity = Gravity.CENTER
                ellipsize = android.text.TextUtils.TruncateAt.END
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(-1, -2))
            if (entry.videoMetadata.isNotBlank()) card.addView(label(entry.videoMetadata, 12f).apply {
                maxLines = 1
                gravity = Gravity.CENTER
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            line.removeView(gaps[count])
            line.addView(card, count, posterCellParams())
            if (++count == columns) breakRow()
        }
    }

    private fun isLegacyVideoFavorite(url: String): Boolean =
        url.isNotBlank() && prefs.getStringSet("favorites", emptySet()).orEmpty().contains(url)

    private fun showPosterDetails(entry: Entry, playable: Boolean = true, playVideo: () -> Unit) {
        val host = findViewById<FrameLayout>(android.R.id.content)
        openPanel(false, entry.title, host) { body ->
            val watchLabel = if (entry.portalCollection)
                tr("Открыть список", "Auswahl öffnen", "Open selection")
            else tr("▶ Смотреть", "▶ Ansehen", "▶ Watch")
            val watch = button(watchLabel) {
                closeSidePanel()
                playVideo()
            }
            body.addView(watch)
            watch.isEnabled = playable
            fun favoriteLabel(saved: Boolean) = if (saved)
                tr("★ Убрать из избранного", "★ Aus Favoriten entfernen", "★ Remove favorite")
            else tr("☆ В избранное", "☆ Zu Favoriten hinzufügen", "☆ Add favorite")
            val favorite = button(favoriteLabel(videoRecord(entry).optBoolean("favorite") ||
                isLegacyVideoFavorite(entry.url))) {}
            if (playable) {
                favorite.setOnClickListener {
                    val records = videoRecords()
                    val record = records.optJSONObject(videoKey(entry)) ?: JSONObject()
                    val legacy = isLegacyVideoFavorite(entry.url)
                    val saved = !(record.optBoolean("favorite") || legacy)
                    if (legacy) toggleFav(entry)
                    record.put("favorite", saved)
                    putVideoRecord(records, entry, record)
                    favorite.text = favoriteLabel(saved)
                }
                body.addView(favorite)
                body.addView(watchLaterButton(entry))
            }
            if (entry.videoMetadata.isNotBlank()) body.addView(label(entry.videoMetadata, 14f))
            body.addView(label(entry.description.ifBlank {
                tr("Описание недоступно", "Keine Beschreibung verfügbar", "Description unavailable")
            }, 17f))
        }
    }

    private var nextEpisode: (() -> Unit)? = null

    private fun openSavedVideo(entry: Entry, onEnded: (() -> Unit)? = null, back: () -> Unit) {
        if (entry.portalRequest.isBlank()) {
            portalPlaybackBack = back
            play(entry)
            nextEpisode = onEnded
            return
        }
        val portal = parseVideoPortal(prefs.getString("video_portal_link", "").orEmpty())
        val operation = runCatching { JSONObject(entry.portalRequest) }.getOrNull()
        if (portal == null || operation == null) {
            toast(tr("Проверь настройки VPortal", "VPortal-Einstellungen prüfen", "Check VPortal settings"))
            return
        }
        if (entry.portalCollection) {
            showPortalPage(portal, operation, entry.title, episodeCover = entry.logo, back = back)
            return
        }
        val ticket = ++portalGeneration
        val page = base
        thread {
            val url = resolvePortalStream(portal, operation)
            runOnUiThread {
                if (isDestroyed || base !== page || ticket != portalGeneration) return@runOnUiThread
                if (url == null) toast(tr("Видео недоступно", "Video nicht verfügbar", "Video is unavailable"))
                else {
                    portalPlaybackBack = back
                    play(entry.copy(url = url))
                    nextEpisode = onEnded
                }
            }
        }
    }

    private fun resolvePortalStream(portal: VideoPortal, operation: JSONObject): String? {
        fun valid(value: String): String? = value.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
        val media = runCatching { requestVideoPortal(portal, operation) }.getOrNull() ?: return null
        val variants = media.optJSONObject("variants")
        return valid(media.optString("url"))
            ?: variants?.let { options ->
                options.keys().asSequence().mapNotNull { valid(options.optString(it)) }.firstOrNull()
            }
    }

    private val adultCategory = Regex(
        """(?iu)(?:^|[^\p{L}\p{N}])(?:x{2,3}|xxl|frk|18\+|adult|adults|erotik|эротика|взрослых)(?=$|[^\p{L}\p{N}])"""
    )
    private val parentalPinKey = "tv_parental_pin"

    private fun askParentalPin(title: String, verify: (String) -> Boolean) {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(android.text.InputFilter.LengthFilter(8))
            isSingleLine = true
        }
        val dialog = AlertDialog.Builder(this).setTitle(title).setView(input)
            .setNegativeButton(tr("Отмена", "Abbrechen", "Cancel"), null)
            .setPositiveButton("OK", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pin = input.text.toString()
                if (pin.length in 4..8 && pin.all { it in '0'..'9' } && verify(pin)) dialog.dismiss()
                else input.error = tr("Проверь PIN (4–8 цифр)", "PIN prüfen (4–8 Ziffern)", "Check PIN (4–8 digits)")
            }
        }
        dialog.show()
    }

    private fun changeParentalPin() {
        val saved = prefs.getString(parentalPinKey, "1111") ?: "1111"
        fun enterNewPin() {
            askParentalPin(tr("Новый PIN", "Neue PIN", "New PIN")) { pin ->
                prefs.edit().putString(parentalPinKey, pin).apply()
                true
            }
        }
        askParentalPin(tr("Текущий PIN", "Aktuelle PIN", "Current PIN")) { pin ->
            if (pin != saved) false else {
                clockHandler.post { enterNewPin() }
                true
            }
        }
    }

    private fun withCategoryAccess(group: String, action: () -> Unit) {
        if (!adultCategory.containsMatchIn(group) || !prefs.getBoolean("require_pin_for_adult_channels", true)) {
            action()
            return
        }
        requireParentalPin(tr("PIN для взрослых каналов", "PIN für Erwachsenen-Kanäle", "PIN for adult channels"), action)
    }

    private fun requireParentalPin(title: String, action: () -> Unit) {
        val saved = prefs.getString(parentalPinKey, "1111") ?: "1111"
        askParentalPin(title) { pin ->
            if (pin != saved) false else {
                clockHandler.post { if (!isFinishing && !isDestroyed) action() }
                true
            }
        }
    }

    private fun requirePlaylistPin(action: () -> Unit) {
        if (!prefs.getBoolean("require_pin_for_sources", true)) action()
        else requireParentalPin(tr("PIN для доступа к источникам", "PIN für Quellenzugriff", "PIN for source settings"), action)
    }

    private val categoryCountryNames by lazy {
        val languages = listOf(Locale.ENGLISH, Locale.GERMAN, Locale.forLanguageTag("ru"))
        Locale.getISOCountries().flatMap { code ->
            languages.map { language ->
                Locale("", code).getDisplayCountry(language).lowercase(Locale.ROOT) to code
            }
        }.groupBy({ it.first }, { it.second }).mapNotNull { (name, codes) ->
            codes.distinct().singleOrNull()?.let { name to it }
        }.toMap()
    }

    private fun tvCategorySymbol(title: String): String? {
        val words = Regex("[\\p{L}]+").findAll(title.lowercase(Locale.ROOT)).map { it.value }.toSet()
        if (adultCategory.containsMatchIn(title)) return "🔒"
        val countries = listOf(
            "AZ" to setOf("azerbaijan", "aserbaidschan", "азербайджан", "азербайджанские"),
            "AM" to setOf("armenia", "armenien", "армения", "армянские"),
            "US" to setOf("usa", "сша", "америка", "американские", "unitedstates"),
            "RU" to setOf("russia", "russland", "россия", "российские", "русские"),
            "DE" to setOf("germany", "deutschland", "германия", "немецкие"),
            "GB" to setOf("uk", "britain", "великобритания", "британские"),
            "FR" to setOf("france", "frankreich", "франция", "французские"),
            "IT" to setOf("italy", "italia", "italien", "италия", "итальянские"),
            "ES" to setOf("españa", "spain", "spanien", "испания", "испанские"),
            "UA" to setOf("ukraine", "украина", "украинские"),
            "BY" to setOf("belarus", "беларусь", "белоруссия", "белорусские"),
            "TR" to setOf("türkiye", "turkey", "турция", "турецкие"),
            "PL" to setOf("poland", "polen", "польша", "польские"),
            "KZ" to setOf("kazakhstan", "казахстан", "казахстанские"),
            "AT" to setOf("austria", "österreich", "австрия"),
            "CH" to setOf("switzerland", "schweiz", "швейцария"),
            "CA" to setOf("canada", "kanada", "канада", "канадские")
        )
        val codes = countries.filter { (_, aliases) -> aliases.any(words::contains) }.map { it.first }
        val country = codes.singleOrNull() ?: if (codes.isEmpty()) {
            val normalized = title.trim().uppercase(Locale.ROOT)
            when {
                normalized == "UK" -> "GB"
                normalized in Locale.getISOCountries() -> normalized
                "united" in words && "states" in words -> "US"
                "united" in words && "kingdom" in words -> "GB"
                else -> categoryCountryNames[title.trim().lowercase(Locale.ROOT)]
            }
        } else null
        if (country != null) return country.map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
        return when {
            words.any { it in setOf("новости", "news", "nachrichten", "информационные", "информация", "info") } -> "ℹ"
            words.any { it in setOf("развлекательные", "развлечения", "entertainment", "unterhaltung") } -> "🎭"
            words.any { it in setOf("спорт", "sport", "sports", "спортивные") } -> "🏆"
            words.any { it in setOf("музыка", "музыкальные", "musik", "music") } -> "♫"
            words.any { it in setOf("фильмы", "кино", "movies", "filme", "cinema") } -> "🎬"
            words.any { it in setOf("детские", "детям", "kids", "kinder") } -> "🧸"
            words.any { it in setOf("мир", "world", "welt", "международные", "international") } -> "🌐"
            else -> null
        }
    }

    private fun showTvMenu() {
        scaffold("tv_menu")
        sectionTitle(tr("Телевидение", "Fernsehen", "Live TV"))
        val menu = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(menu, LinearLayout.LayoutParams(-1, -2))
        val favorites = button(tr("Избранное", "Favoriten", "Favorites")) {
            showCatalog("favorites")
        }
        menu.addView(favorites)
        menu.addView(button(tr("Недавние каналы", "Zuletzt gesehen", "Recent channels")) { showChannelHistory() })
        if (moduleEnabled("recent_started"))
            menu.addView(button(tr("Недавно начавшиеся передачи", "Kürzlich gestartete Sendungen", "Recently started programmes")) {
                showRecentlyStarted()
            })
        if (moduleEnabled("stalker") && prefs.getString("stalker_url", "").orEmpty().isNotBlank())
            menu.addView(button("Stalker Portal") { showStalkerCategories() })
        val groups = items.filter { it.type == "tv" }.map { it.group }.distinct().sorted()
        menu.addView(label(tr("Категории", "Kategorien", "Categories"), 20f, true))
        if (groups.isEmpty()) {
            menu.addView(label(tr("Добавь ТВ-плейлист, чтобы увидеть категории.", "TV-Playlist für Kategorien hinzufügen.", "Add a TV playlist to see categories.")))
            menu.addView(button(tr("Добавить с телефона · QR", "Per Handy hinzufügen · QR", "Add from phone · QR")) { showRemote() })
            menu.addView(button("+ M3U") { showPlaylists() })
        }
        val categories = PortalCategoryGrid(menu)
        groups.forEach { group ->
            val name = group.ifBlank { tr("Другие", "Sonstige", "Other") }
            categories.add(name, R.drawable.ic_live_television, accent, symbol = tvCategorySymbol(name)) {
                withCategoryAccess(group) { showCatalog("tv", group) }
            }
        }
        menu.addView(button(tr("Все каналы", "Alle Sender", "All channels")) { showCatalog("tv") })
        favorites.requestFocus()
    }

    private fun showRecentlyStarted() {
        scaffold("recent_started")
        sectionTitle(tr("Недавно начавшиеся передачи", "Kürzlich gestartete Sendungen", "Recently started programmes"))
        val now = System.currentTimeMillis()
        val recent = items.filter { it.type == "tv" }.mapNotNull { channel ->
            guideEntries(channel).firstOrNull { programme -> now in programme.start until programme.stop && now - programme.start <= 20 * 60_000L }
                ?.let { channel to it }
        }.sortedBy { it.second.start }
        if (recent.isEmpty()) content.addView(label(tr("Нет передач из EPG, начавшихся за последние 20 минут.",
            "Keine EPG-Sendungen, die in den letzten 20 Minuten begonnen haben.",
            "No EPG programmes started in the last 20 minutes.")))
        recent.forEach { (channel, programme) ->
            content.addView(button("${channel.title}\n${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(programme.start))} · ${programme.title}") {
                play(channel, channelOrder = recent.map { it.first })
            })
        }
    }

    private fun showMediaHub() {
        scaffold("media_hub")
        sectionTitle(tr("Видеотека", "Mediathek", "Video library"))
        val tiles = PortalCategoryGrid(content, tvColumns = 4)
        tiles.add(tr("Фильмы", "Filme", "Movies"), R.drawable.ic_cinema, Color.rgb(224, 142, 255)) {
            showCatalog("movies", "films")
        }
        tiles.add(tr("Сериалы", "Serien", "Series"), R.drawable.ic_live_television, Color.rgb(106, 201, 255)) {
            showCatalog("movies", "series")
        }
        tiles.add(tr("Поиск", "Suche", "Search"), android.R.drawable.ic_menu_search, Color.rgb(112, 211, 255)) {
            openMovieSearch()
        }
        tiles.add(newMoviesTitle(), R.drawable.ic_cinema, Color.rgb(255, 190, 110)) { openNewMovies() }
        videoShelfModes().forEachIndexed { index, name ->
            val icon = if (index == 3) android.R.drawable.btn_star_big_on else android.R.drawable.ic_menu_recent_history
            tiles.add(name, icon, accent) { showVideoShelf(index) }
        }
        tiles.add(tr("Плейлисты видеотеки", "Mediathek-Playlisten", "Video library playlists"),
            android.R.drawable.ic_menu_add, Color.rgb(255, 196, 106)) { manageVodPlaylists() }
    }

    private fun showOnlineHub() {
        scaffold("online_hub")
        sectionTitle(tr("Онлайн-кинотеатр", "Online-Kino", "Online cinema"))
        content.addView(label(tr("Фильмы и новинки из подключённого онлайн-каталога.",
            "Filme und Neuheiten aus dem verbundenen Online-Katalog.",
            "Movies and new releases from your connected online catalog."), 16f))
        val grid = PortalCategoryGrid(content, tvColumns = 3)
        grid.add(tr("Открыть кинотеатр", "Kino öffnen", "Open cinema"),
            R.drawable.ic_movie_theater, Color.rgb(255, 102, 153)) { openVideoPortal() }
        grid.add(newMoviesTitle(), R.drawable.ic_cinema, Color.rgb(255, 183, 64)) { openNewMovies() }
        grid.add(tr("Найти фильм", "Film suchen", "Find a movie"),
            android.R.drawable.ic_menu_search, Color.rgb(63, 211, 255)) { openMovieSearch() }
        grid.add(tr("Фильмы из моих плейлистов", "Filme aus meinen Playlisten", "Movies from my playlists"),
            R.drawable.ic_cinema, Color.rgb(150, 101, 255)) { showCatalog("movies", "films") }
    }

    private fun makeStalkerClient(): StalkerPortalClient? {
        val address = prefs.getString("stalker_url", "").orEmpty().trim()
        val mac = prefs.getString("stalker_mac", "").orEmpty().trim()
        if (address.isBlank() || mac.isBlank()) return null
        return stalkerClient ?: StalkerPortalClient(address, mac,
            prefs.getString("stalker_login", "").orEmpty(),
            prefs.getString("stalker_password", "").orEmpty(),
            prefs.getInt("stalker_auth_mode", 0)).also { stalkerClient = it }
    }

    private fun showStalkerCategories() {
        val client = makeStalkerClient() ?: return toast(tr("Сначала настрой Stalker-портал", "Stalker-Portal zuerst einrichten", "Set up the Stalker portal first"))
        scaffold("stalker_categories")
        sectionTitle(tr("Каналы Stalker", "Stalker-Sender", "Stalker channels"))
        content.addView(label(tr("Загружаю категории…", "Kategorien werden geladen…", "Loading categories…")))
        val screen = base
        thread {
            val result = runCatching { client.verify(); client.categories() }
            runOnUiThread {
                if (isDestroyed || base !== screen) return@runOnUiThread
                content.removeAllViews()
                sectionTitle(tr("Каналы Stalker", "Stalker-Sender", "Stalker channels"))
                val categories = result.getOrNull()
                if (categories == null) {
                    content.addView(label(tr("Портал не подтвердил доступ: ${result.exceptionOrNull()?.message.orEmpty()}",
                        "Portalzugriff nicht bestätigt: ${result.exceptionOrNull()?.message.orEmpty()}",
                        "Portal access was not confirmed: ${result.exceptionOrNull()?.message.orEmpty()}")))
                    content.addView(button(tr("Настроить портал", "Portal einrichten", "Configure portal")) { showSettings("stalker", authorized = true) })
                    return@runOnUiThread
                }
                val grid = PortalCategoryGrid(content, tvColumns = 4)
                grid.add(tr("Все каналы", "Alle Sender", "All channels"), R.drawable.ic_live_television, accent) {
                    loadStalkerChannels(client, StalkerPortalClient.Category("*", tr("Все каналы", "Alle Sender", "All channels")))
                }
                categories.forEach { category ->
                    grid.add(category.name, R.drawable.ic_live_television, accent,
                        symbol = tvCategorySymbol(category.name)) { loadStalkerChannels(client, category) }
                }
                content.addView(button(tr("Настроить портал", "Portal einrichten", "Configure portal")) {
                    showSettings("stalker", authorized = true)
                })
            }
        }
    }

    private fun loadStalkerChannels(client: StalkerPortalClient, category: StalkerPortalClient.Category) {
        scaffold("stalker_channels")
        sectionTitle(category.name)
        content.addView(label(tr("Загружаю телеканалы…", "Sender werden geladen…", "Loading channels…")))
        val screen = base
        thread {
            val result = runCatching { client.channels(category.id) }
            runOnUiThread {
                if (isDestroyed || base !== screen) return@runOnUiThread
                content.removeAllViews()
                sectionTitle(category.name)
                val channels = result.getOrNull()
                if (channels == null) {
                    content.addView(label(tr("Не удалось получить каналы: ${result.exceptionOrNull()?.message.orEmpty()}",
                        "Sender konnten nicht geladen werden: ${result.exceptionOrNull()?.message.orEmpty()}",
                        "Could not load channels: ${result.exceptionOrNull()?.message.orEmpty()}")))
                    return@runOnUiThread
                }
                if (channels.isEmpty()) content.addView(label(tr("В этой категории нет каналов", "Keine Sender in dieser Kategorie", "No channels in this category")))
                val newEpgChannels = channels.map { channel ->
                    Entry(channel.name, "stalker://${channel.id}", category.name, channel.logo, "tv",
                        epgId = channel.epgId, stalkerId = channel.id, stalkerCommand = channel.command)
                }
                val hasNewEpgChannels = newEpgChannels.any { entry ->
                    val key = entry.epgId.ifBlank { entry.stalkerId }
                    key !in stalkerEpgChannels
                }
                newEpgChannels.forEach { entry ->
                    stalkerEpgChannels[entry.epgId.ifBlank { entry.stalkerId }] = entry
                }
                channels.forEach { channel ->
                    val entry = stalkerEpgChannels[channel.epgId.ifBlank { channel.id }]!!
                    val programme = programmeLine(entry, System.currentTimeMillis())
                    val row = button(if (programme.isBlank()) channel.name else "${channel.name}\n$programme") {
                        play(entry, channelOrder = channels.map { c ->
                            Entry(c.name, "stalker://${c.id}", category.name, c.logo, "tv", epgId = c.epgId,
                                stalkerId = c.id, stalkerCommand = c.command)
                        })
                    }
                    content.addView(row)
                }
                if (hasNewEpgChannels && prefs.getBoolean("refresh_epg_on_channels", true)) loadEpg()
            }
        }
    }

    private fun openSystemIntent(action: String) {
        try { startActivity(Intent(action)) }
        catch (_: Exception) { toast(tr("Настройки устройства недоступны", "Geräteeinstellungen nicht verfügbar", "Device settings are unavailable")) }
    }

    private fun setMediaSessionEnabled(enabled: Boolean) {
        if (enabled) {
            if (mediaSession == null) mediaSession = player?.let { runCatching { androidx.media3.session.MediaSession.Builder(this, it).build() }.getOrNull() }
        } else {
            mediaSession?.release()
            mediaSession = null
        }
    }

    private fun resetOnlineSettings() {
        AlertDialog.Builder(this).setTitle(tr("Сбросить приложение?", "App zurücksetzen?", "Reset the app?"))
            .setMessage(tr("Будут удалены плейлисты, пароли, PIN-код и настройки этого приложения. Старый плеер не затрагивается.",
                "Playlisten, Passwörter, PIN und Einstellungen dieser App werden gelöscht. Der alte Player bleibt unverändert.",
                "This app's playlists, passwords, PIN and settings will be erased. The old player is not affected."))
            .setPositiveButton(tr("Сбросить", "Zurücksetzen", "Reset")) { _, _ ->
                prefs.edit().clear().apply()
                runCatching { guideCacheFile().delete() }
                finish()
                startActivity(Intent(this, MainActivity::class.java))
            }.setNegativeButton(tr("Отмена", "Abbrechen", "Cancel"), null).show()
    }

    private fun startRadio(countryCode: String) {
        val generation = ++radioLoadGeneration
        scaffold("radio")
        sectionTitle(if (countryCode == "RU") tr("Радио России", "Russisches Radio", "Russian radio")
            else tr("Радио Германии", "Deutsches Radio", "German radio"))
        content.addView(label(tr("Загружаю открытый каталог радиостанций…", "Offenes Radioverzeichnis wird geladen…",
            "Loading the public radio directory…")))
        val screen = base
        thread {
            val result = runCatching { RadioBrowserClient.stations(countryCode) }
            runOnUiThread {
                if (isDestroyed || base !== screen || generation != radioLoadGeneration) return@runOnUiThread
                content.removeAllViews()
                sectionTitle(if (countryCode == "RU") tr("Радио России", "Russisches Radio", "Russian radio")
                    else tr("Радио Германии", "Deutsches Radio", "German radio"))
                val stations = result.getOrNull()
                if (stations == null) {
                    content.addView(label(tr("Каталог радио сейчас недоступен", "Radioverzeichnis gerade nicht erreichbar",
                        "Radio directory is temporarily unavailable")))
                    content.addView(button(tr("Повторить", "Erneut versuchen", "Retry")) { startRadio(countryCode) })
                    return@runOnUiThread
                }
                radioStations = stations
                stations.forEach { station ->
                    val detail = listOfNotNull(station.language.takeIf { it.isNotBlank() },
                        station.codec.takeIf { it.isNotBlank() }, station.bitrate.takeIf { it > 0 }?.let { "$it kbps" })
                        .joinToString(" · ")
                    content.addView(button(if (detail.isBlank()) station.name else "${station.name}\n$detail") {
                        thread { RadioBrowserClient.registerClick(station.uuid) }
                        play(Entry(station.name, station.streamUrl, station.country, station.logo, type = "radio"))
                    })
                }
                if (stations.isEmpty()) content.addView(label(tr("Станций не найдено", "Keine Sender gefunden", "No stations found")))
            }
        }
    }

    private fun showRadioMenu() {
        scaffold("radio_menu")
        sectionTitle(tr("Радио", "Radio", "Radio"))
        content.addView(label(tr("Открытые радиопотоки из каталога Radio Browser. Доступность зависит от станции.",
            "Öffentliche Streams aus dem Radio-Browser-Verzeichnis. Die Verfügbarkeit hängt vom Sender ab.",
            "Public streams from Radio Browser. Each station's availability depends on its broadcaster."), 14f))
        content.addView(button(tr("Радио Германии", "Radio Deutschland", "German radio")) { startRadio("DE") })
        content.addView(button(tr("Радио России", "Radio Russland", "Russian radio")) { startRadio("RU") })
    }

    private fun showMusicHub() {
        scaffold("music_hub")
        sectionTitle(tr("Бесплатные музыкальные станции", "Kostenlose Musikstationen", "Free music stations"))
        content.addView(label(tr("Открытые интернет-радиостанции из каталога Radio Browser. Это музыкальные эфиры, не загрузка отдельных песен.",
            "Öffentliche Internetradios aus dem Radio-Browser-Verzeichnis. Musikstreams, keine einzelnen Song-Downloads.",
            "Public internet radio stations from Radio Browser. These are music streams, not individual song downloads."), 14f))
        content.addView(button(tr("Поиск станции", "Sender suchen", "Search stations")) {
            val field = EditText(this).apply {
                hint = tr("Название станции", "Sendername", "Station name")
                setSingleLine(true)
            }
            AlertDialog.Builder(this).setTitle(tr("Найти музыку", "Musik suchen", "Find music"))
                .setView(field)
                .setPositiveButton(tr("Поиск", "Suchen", "Search")) { _, _ ->
                    val query = field.text.toString().trim()
                    if (query.isNotBlank()) loadMusicStations(name = query)
                }
                .setNegativeButton(tr("Отмена", "Abbrechen", "Cancel"), null).show()
        })
        val genres = listOf(
            "pop" to tr("Поп", "Pop", "Pop"),
            "rock" to tr("Рок", "Rock", "Rock"),
            "jazz" to tr("Джаз", "Jazz", "Jazz"),
            "classical" to tr("Классика", "Klassik", "Classical"),
            "chillout" to "Chillout",
            "electronic" to tr("Электронная", "Elektronisch", "Electronic")
        )
        genres.forEach { (tag, title) ->
            content.addView(button(title) { loadMusicStations(tag = tag) })
        }
        content.addView(button(tr("Все музыкальные станции", "Alle Musikstationen", "All music stations")) {
            loadMusicStations(tag = "music")
        })
    }

    private fun openYouTube() {
        val packages = listOf("com.amazon.firetv.youtube", "com.google.android.youtube.tv", "com.google.android.youtube")
        val appIntent = packages.firstNotNullOfOrNull { packageManager.getLaunchIntentForPackage(it) }
        try {
            if (appIntent != null) startActivity(appIntent)
            else startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/")))
        } catch (_: Exception) {
            toast(tr("Не удалось открыть YouTube. Установи приложение из магазина Fire TV.",
                "YouTube konnte nicht geöffnet werden. Installiere die App aus dem Fire-TV-Appstore.",
                "Could not open YouTube. Install the app from the Fire TV app store."))
        }
    }

    private fun loadMusicStations(tag: String? = null, name: String? = null) {
        val generation = ++musicLoadGeneration
        scaffold("music_stations")
        val heading = name?.let { tr("Поиск: $it", "Suche: $it", "Search: $it") }
            ?: tag?.let { tr("Музыка · $it", "Musik · $it", "Music · $it") }
            ?: tr("Музыкальные станции", "Musikstationen", "Music stations")
        sectionTitle(heading)
        content.addView(label(tr("Загружаю список открытых станций…", "Lade öffentliche Sender…", "Loading public stations…")))
        val screen = base
        thread {
            val result = runCatching { RadioBrowserClient.musicStations(tag = tag, name = name) }
            runOnUiThread {
                if (isDestroyed || base !== screen || generation != musicLoadGeneration) return@runOnUiThread
                content.removeAllViews()
                sectionTitle(heading)
                content.addView(button(tr("Назад к музыке", "Zurück zur Musik", "Back to music")) { showMusicHub() })
                val stations = result.getOrNull()
                if (stations == null) {
                    content.addView(label(tr("Музыкальный каталог временно недоступен.",
                        "Musikkatalog vorübergehend nicht erreichbar.",
                        "Music directory is temporarily unavailable.")))
                    content.addView(button(tr("Повторить", "Erneut versuchen", "Retry")) { loadMusicStations(tag, name) })
                    return@runOnUiThread
                }
                stations.forEach { station ->
                    val detail = listOfNotNull(station.country.takeIf { it.isNotBlank() },
                        station.codec.takeIf { it.isNotBlank() }, station.bitrate.takeIf { it > 0 }?.let { "$it kbps" })
                        .joinToString(" · ")
                    content.addView(button(if (detail.isBlank()) station.name else "${station.name}\n$detail") {
                        thread { RadioBrowserClient.registerClick(station.uuid) }
                        play(Entry(station.name, station.streamUrl, station.country, station.logo, type = "music"))
                    })
                }
                if (stations.isEmpty()) content.addView(label(tr("Музыкальных станций не найдено", "Keine Musikstationen gefunden",
                    "No music stations found")))
            }
        }
    }

    private fun showFireplace() {
        current = "fireplace"
        closeSidePanel()
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        setContentView(FireplaceView(this))
    }

    private fun scanDlnaServers() {
        val progress = AlertDialog.Builder(this).setTitle(tr("Поиск UPnP / DLNA", "UPnP-/DLNA-Suche", "Scanning UPnP / DLNA"))
            .setMessage(tr("Ищу медиасерверы в локальной сети…", "Mediensuche im lokalen Netzwerk…", "Searching the local network for media servers…"))
            .setNegativeButton(tr("Отмена", "Abbrechen", "Cancel"), null).create()
        progress.show()
        val generation = ++dlnaScanGeneration
        thread {
            val lock = runCatching {
                (getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager).createMulticastLock("VladiMirDlna").apply { setReferenceCounted(false); acquire() }
            }.getOrNull()
            val locations = linkedSetOf<String>()
            runCatching {
                java.net.DatagramSocket().use { socket ->
                    socket.soTimeout = 450
                    val group = java.net.InetAddress.getByName("239.255.255.250")
                    val request = listOf(
                        "M-SEARCH * HTTP/1.1", "HOST: 239.255.255.250:1900", "MAN: \"ssdp:discover\"",
                        "MX: 2", "ST: urn:schemas-upnp-org:device:MediaServer:1", "", ""
                    ).joinToString("\r\n").toByteArray(Charsets.US_ASCII)
                    socket.send(java.net.DatagramPacket(request, request.size, group, 1900))
                    val until = android.os.SystemClock.elapsedRealtime() + 3_000L
                    while (android.os.SystemClock.elapsedRealtime() < until) {
                        val bytes = ByteArray(8192)
                        val packet = java.net.DatagramPacket(bytes, bytes.size)
                        try {
                            socket.receive(packet)
                            String(packet.data, 0, packet.length, Charsets.ISO_8859_1).lineSequence()
                                .firstOrNull { it.startsWith("LOCATION:", true) }
                                ?.substringAfter(':')?.trim()?.takeIf { it.startsWith("http", true) }?.let(locations::add)
                        } catch (_: java.net.SocketTimeoutException) { }
                    }
                }
            }
            runCatching { if (lock?.isHeld == true) lock.release() }
            runOnUiThread {
                if (isDestroyed || generation != dlnaScanGeneration) return@runOnUiThread
                progress.dismiss()
                val hosts = locations.mapNotNull { address -> runCatching { URL(address).host }.getOrNull()?.let { "$it\n$address" } }
                val message = if (hosts.isEmpty()) tr("Медиасерверы не обнаружены. Проверь, что телевизор и сервер в одной сети и UPnP включён.",
                    "Keine Medienserver gefunden. Geräte müssen im selben Netzwerk sein und UPnP muss aktiviert sein.",
                    "No media servers found. Check that the TV and server share a network and UPnP is enabled.")
                else hosts.joinToString("\n\n")
                AlertDialog.Builder(this).setTitle(tr("Найдено серверов: ${hosts.size}", "Gefundene Server: ${hosts.size}", "Servers found: ${hosts.size}"))
                    .setMessage(message).setPositiveButton("OK", null).show()
            }
        }
    }

    private val portalSearchEntries = linkedMapOf<String, Entry>()

    private val movieVoiceRequest = 7342
    private var movieVoiceResult: ((String) -> Unit)? = null

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != movieVoiceRequest) return
        val result = movieVoiceResult
        movieVoiceResult = null
        if (resultCode == RESULT_OK) {
            data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { result?.invoke(it) }
        }
    }

    private fun startMovieVoice(language: String, result: (String) -> Unit) {
        val intent = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, language)
            .putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        movieVoiceResult = result
        try {
            startActivityForResult(intent, movieVoiceRequest)
        } catch (_: android.content.ActivityNotFoundException) {
            movieVoiceResult = null
            toast(tr("Голосовой ввод на устройстве недоступен", "Spracheingabe auf diesem Gerät nicht verfügbar",
                "Voice input is unavailable on this device"))
        }
    }

    private fun movieKeyboard(title: EditText, year: EditText): View {
        var german = prefs.getBoolean("movie_keyboard_german", false)
        var target = title
        val keys = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val keyboard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun type(text: String) {
            val from = target.selectionStart.coerceIn(0, target.length())
            val to = target.selectionEnd.coerceIn(0, target.length())
            target.text.replace(minOf(from, to), maxOf(from, to), text)
        }
        fun row(container: LinearLayout, items: List<Pair<String, () -> Unit>>) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            items.forEach { (caption, action) ->
                line.addView(button(caption, action).apply {
                    textSize = 14f
                    minWidth = 0
                    minimumWidth = 0
                    setPadding(dp(2), 0, dp(2), 0)
                }, LinearLayout.LayoutParams(0, dp(38), 1f))
            }
            container.addView(line)
        }
        fun drawKeys() {
            val rows = if (target === year) listOf("1234567890")
                else if (german) listOf("qwertzuiopü", "asdfghjklöä", "yxcvbnmß", "1234567890")
                else listOf("йцукенгшщзхъ", "фывапролджэ", "ячсмитьбюё", "1234567890")
            keys.removeAllViews()
            rows.forEach { letters ->
                row(keys, letters.map { letter -> letter.toString() to { type(letter.toString()) } })
            }
        }
        val switchLanguage = button(if (german) "Deutsch → Русский" else "Русский → Deutsch") { }
        switchLanguage.setOnClickListener {
            german = !german
            prefs.edit().putBoolean("movie_keyboard_german", german).apply()
            switchLanguage.text = if (german) "Deutsch → Русский" else "Русский → Deutsch"
            drawKeys()
        }
        keyboard.addView(switchLanguage)
        row(keyboard, listOf(
            tr("Название", "Titel", "Title") to { target = title; drawKeys() },
            tr("Год", "Jahr", "Year") to { target = year; drawKeys() },
            tr("Голос", "Sprache", "Voice") to {
                startMovieVoice(if (german) "de-DE" else "ru-RU") { spoken ->
                    title.setText(spoken)
                    title.setSelection(title.length())
                }
            }
        ))
        keyboard.addView(keys)
        row(keyboard, listOf(
            tr("Пробел", "Leerzeichen", "Space") to { if (target === title) type(" ") },
            "⌫" to {
                val from = target.selectionStart.coerceIn(0, target.length())
                val to = target.selectionEnd.coerceIn(0, target.length())
                if (from != to) target.text.delete(minOf(from, to), maxOf(from, to))
                else if (from > 0) target.text.delete(from - 1, from)
            }
        ))
        title.setOnFocusChangeListener { _, focused -> if (focused && target !== title) { target = title; drawKeys() } }
        year.setOnFocusChangeListener { _, focused -> if (focused && target !== year) { target = year; drawKeys() } }
        drawKeys()
        return keyboard
    }

    private fun openMovieSearch() {
        val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val title = EditText(this).apply {
            hint = tr("Название фильма", "Filmtitel", "Movie title")
            isSingleLine = true
            showSoftInputOnFocus = false
        }
        val year = EditText(this).apply {
            hint = tr("Год выпуска", "Erscheinungsjahr", "Release year")
            showSoftInputOnFocus = false
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(android.text.InputFilter.LengthFilter(4))
        }
        fields.addView(title)
        fields.addView(year)
        fields.addView(movieKeyboard(title, year))
        val scroll = ScrollView(this).apply { addView(fields) }
        val dialog = AlertDialog.Builder(this).setTitle(tr("Поиск по видеотеке", "Mediathek durchsuchen", "Search video library"))
            .setView(scroll).setNegativeButton(tr("Отмена", "Abbrechen", "Cancel"), null)
            .setPositiveButton(tr("Искать", "Suchen", "Search"), null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val query = title.text.toString().trim()
                val releaseYear = year.text.toString()
                if (releaseYear.isNotEmpty() && !releaseYear.matches(Regex("[0-9]{4}"))) {
                    year.error = tr("Введи 4 цифры", "Vier Ziffern eingeben", "Enter four digits")
                } else if (query.isEmpty() && releaseYear.isEmpty()) {
                    title.error = tr("Введи название или год", "Titel oder Jahr eingeben", "Enter a title or year")
                } else {
                    dialog.dismiss()
                    showMovieSearch(query, releaseYear)
                }
            }
        }
        dialog.setOnDismissListener { movieVoiceResult = null }
        dialog.show()
    }

    private fun showMovieSearch(query: String, year: String, page: Int = 0) {
        scaffold("vportal")
        portalBack = { showMediaHub() }
        portalGeneration++
        sectionTitle(tr("Результаты поиска", "Suchergebnisse", "Search results"))
        syncPortalCatalog()
        val pattern = year.takeIf { it.isNotEmpty() }?.let { Regex("(?<![0-9])" + it + "(?![0-9])") }
        val results = (vodPlaylistItems.values.flatten() + vodItems + portalSearchEntries.values)
            .distinctBy(::videoKey).filter { entry ->
                entry.title.contains(query, ignoreCase = true) &&
                    (pattern == null || pattern.containsMatchIn(entry.title) || pattern.containsMatchIn(entry.videoMetadata))
            }
        content.addView(label(tr("Поиск по загруженной видеотеке и открытым разделам VPortal.",
            "Suche in geladenen Titeln und geöffneten VPortal-Bereichen.",
            "Search covers loaded titles and opened VPortal sections."), 14f))
        if (results.isEmpty()) content.addView(label(tr("Ничего не найдено", "Keine Treffer", "No results")))
        if (page > 0) content.addView(button(tr("Предыдущая страница", "Vorherige Seite", "Previous page")) {
            showMovieSearch(query, year, page - 1)
        })
        val posters = PosterGrid(content)
        val pageSize = 36
        results.drop(page * pageSize).take(pageSize).forEach { entry ->
            posters.add(entry) {
                showPosterDetails(entry) {
                    openSavedVideo(entry) { showMovieSearch(query, year, page) }
                }
            }
        }
        if ((page + 1) * pageSize < results.size) content.addView(button(tr("Следующая страница", "Nächste Seite", "Next page")) {
            showMovieSearch(query, year, page + 1)
        })
    }

    private fun newMoviesTitle() = tr("Новинки кинотеатра", "Neu im Kino", "Cinema new releases")

    private fun openNewMovies() {
        syncPortalCatalog()
        val portal = parseVideoPortal(arrivalsProvider)
        if (portal == null) {
            showPortalArrivals({ showMediaHub() }, moviesOnly = true)
            return
        }
        scaffold("vportal")
        portalBack = { showMediaHub() }
        val generation = ++portalGeneration
        sectionTitle(newMoviesTitle())
        content.addView(label(tr("Загрузка…", "Lädt…", "Loading…")))
        thread {
            val operation = runCatching {
                val rows = requestVideoPortal(portal, JSONObject()).optJSONArray("items")
                if (rows == null) null else (0 until rows.length()).asSequence()
                    .mapNotNull { rows.optJSONObject(it) }
                    .firstOrNull { it.optString("type") == "category" && isNewArrivalsCategory(it.optString("title")) }
                    ?.optJSONObject("request")
            }.getOrNull()
            runOnUiThread {
                if (isDestroyed || current != "vportal" || generation != portalGeneration) return@runOnUiThread
                if (operation == null) showPortalArrivals({ showMediaHub() }, moviesOnly = true)
                else showPortalPage(portal, operation, newMoviesTitle(), moviesOnly = true) { showMediaHub() }
            }
        }
    }

    private fun showCatalog(type: String, selectedGroup: String? = null, movieYear: String? = null, movieQuery: String = "") {
        scaffold(type)
        catalogGroup = selectedGroup

        sectionTitle(
            when (type) {
                "tv" -> tr("Телеканалы", "Fernsehen", "Live TV")
                "guide" -> tr("Телегид", "TV-Programm", "TV guide")
                "movies" -> tr("Фильмы", "Filme", "Movies")
                else -> tr("Избранное", "Favoriten", "Favorites")
            }
        )

        if (type == "movies") {
            content.addView(button(tr("☰ Разделы видеотеки", "☰ Bereiche", "☰ Library sections")) {
                val host = findViewById<FrameLayout>(android.R.id.content)
                openPanel(true, tr("Видеотека", "Mediathek", "Media library"), host) { menu ->
                    menu.addView(button(tr("Онлайн-кинотеатр", "Online-Kino", "Online cinema")) { showOnlineHub() })
                    menu.addView(button(tr("Поиск фильма", "Film suchen", "Find a movie")) { openMovieSearch() })
                    menu.addView(button(tr("Все фильмы по годам", "Filme nach Jahr", "Movies by year")) { showCatalog("movies") })
                    videoShelfModes().forEachIndexed { index, name ->
                        menu.addView(button(name) { showVideoShelf(index) })
                    }
                }
            })
        }

        val catalog = if (type == "movies") {
            (vodPlaylistItems.values.flatten() + vodItems).distinctBy { it.url }
        } else items
        if (catalog.isEmpty()) {
            content.addView(
                label(
                    tr(
                        "Сначала добавь M3U-плейлист",
                        "Bitte M3U-Playlist hinzufügen",
                        "Please add an M3U playlist"
                    )
                )
            )

            content.addView(
                button("+ M3U") { showPlaylists() }
            )
            return
        }

        val favorites =
            prefs.getStringSet("favorites", emptySet())
                ?: emptySet()

        val candidates = catalog.filter {
            when (type) {
                "favorites" -> favorites.contains(it.url)
                "tv", "guide" -> it.type == "tv" && (selectedGroup == null || it.group == selectedGroup)
                "movies" -> true
                else -> it.type == type
            }
        }

        if (candidates.isEmpty()) {
            content.addView(
                label(
                    tr(
                        "Нет данных",
                        "Keine Einträge",
                        "No entries"
                    )
                )
            )
            return
        }

        val orderedCandidates = if (type == "tv" && prefs.getBoolean("sort_channels_alpha", false))
            candidates.sortedBy { it.title.lowercase(Locale.ROOT) }
        else candidates

        val groups = orderedCandidates.map { it.group }
            .distinct()
            .sorted()

        val mediaFilters = if (type == "movies") listOf(
            tr("Фильмы", "Filme", "Movies"),
            tr("Сериалы", "Serien", "Series")
        ) else emptyList()
        val options = listOf(
            tr(
                "Все категории",
                "Alle Kategorien",
                "All categories"
            )
        ) + mediaFilters + groups

        val filter = Spinner(this)

        filter.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            options
        )

        if (type != "tv" && type != "favorites") content.addView(filter)
        var activeYear = movieYear.orEmpty()
        val movieYears = if (type == "movies") orderedCandidates.mapNotNull(::movieReleaseYear).distinct().sortedDescending() else emptyList()
        val yearFilter = if (type == "movies") Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf(tr("Все годы", "Alle Jahre", "All years")) + movieYears)
        } else null
        yearFilter?.let { content.addView(it) }

        val initialFilter = if (type == "movies") when (selectedGroup) {
            "films" -> 1
            "series" -> 2
            else -> 0
        } else 0
        var activeGroup = ""
        var activeMediaFilter = initialFilter

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        content.addView(list)

        fun fill(group: String, mediaFilter: Int = 0, page: Int = 0) {
            val now = System.currentTimeMillis()
            list.removeAllViews()

            val matching = orderedCandidates.filter { entry ->
                (group.isEmpty() || entry.group == group) &&
                    (mediaFilter == 0 || isSeries(entry) == (mediaFilter == 2)) &&
                    (activeYear.isEmpty() || movieReleaseYear(entry) == activeYear) &&
                    (movieQuery.isBlank() || entry.title.contains(movieQuery, true))
            }
            val pageSize = if (type == "movies") 36 else 100
            val visible = matching.drop(page * pageSize).take(pageSize)
            if (page > 0) list.addView(button(tr("Предыдущая страница", "Vorherige Seite", "Previous page")) {
                fill(group, mediaFilter, page - 1)
                list.getChildAt(0)?.requestFocus()
            })
            fun nextPage() {
                if ((page + 1) * pageSize < matching.size) {
                    list.addView(button(tr("Следующая страница", "Nächste Seite", "Next page")) {
                        fill(group, mediaFilter, page + 1)
                        list.getChildAt(0)?.requestFocus()
                    })
                }
            }
            if (visible.isEmpty()) list.addView(label(tr("Нет записей", "Keine Einträge", "No entries")))
            if (type == "movies") {
                val posters = PosterGrid(list)
                visible.forEach { entry ->
                    posters.add(entry) { showPosterDetails(entry) { play(entry) } }
                }
                nextPage()
                return
            }
            val channelList = type == "tv" || type == "favorites"
            var firstChannel: View? = null
            fun registerChannel(card: View) {
                card.id = View.generateViewId()
                if (firstChannel == null) {
                    firstChannel = card
                    settingsButton?.let { focusDown(it, card) }
                }
            }
            val mode = if (channelList) {
                if (type == "tv" && moduleEnabled("studio", false)) 3 else prefs.getInt("channel_layout", 3)
            } else 0
            val showProgramme = type == "guide" || prefs.getBoolean("channel_show_programme", true)
            if (channelList && mode in 3..4) {
                val logosOnly = mode == 4
                val columns = if (isPhone()) 2 else if (logosOnly) 4 else 5
                var previousCards = emptyList<View>()
                visible.chunked(columns).forEach { entries ->
                    val cards = mutableListOf<View>()
                    val gridRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                    entries.forEach { entry ->
                        val logo = channelLogo(entry)
                        val validLogo = logo.isNotBlank()
                        val card = channelCard(entry.title, vertical = true) { play(entry, channelOrder = orderedCandidates) }
                        registerChannel(card)
                        cards.add(card)
                        card.setOnLongClickListener {
                            toggleFav(entry)
                            toast(if (prefs.getStringSet("favorites", emptySet()).orEmpty().contains(entry.url))
                                tr("Добавлено в избранное", "Zu Favoriten hinzugefügt", "Added to favorites")
                            else tr("Убрано из избранного", "Aus Favoriten entfernt", "Removed from favorites"))
                            if (type == "favorites") showCatalog(type, selectedGroup)
                            true
                        }
                        if (validLogo) card.addView(
                            channelArtwork(entry, 136, 106),
                            LinearLayout.LayoutParams(-1, dp(106)).apply { marginEnd = 0 }
                        )
                        if (!logosOnly || !validLogo) {
                            card.addView(channelText(entry.title, 15f, true, centered = true))
                        }
                        if (!logosOnly && showProgramme) {
                            card.addView(channelText(programmeLine(entry, now), 11f, false, centered = true))
                        }
                        gridRow.addView(card, LinearLayout.LayoutParams(0, dp(if (logosOnly) 124 else 154), 1f).apply {
                            setMargins(dp(4), dp(4), dp(4), dp(4))
                        })
                    }
                    cards.zipWithNext(::focusRight)
                    previousCards.zip(cards).forEach { (upper, lower) -> focusDown(upper, lower) }
                    previousCards = cards
                    repeat(columns - entries.size) {
                        gridRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply {
                            setMargins(dp(4), 0, dp(4), 0)
                        })
                    }
                    list.addView(gridRow)
                }
                nextPage()
                return
            }

            visible.forEach { entry ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val card = channelCard(entry.title, vertical = false) {
                    if (type == "guide") showGuideDialog(entry) else play(entry, channelOrder = orderedCandidates)
                }
                registerChannel(card)
                if (prefs.getBoolean("show_artwork", true)) {
                    val width = when (mode) { 1 -> 172; 2 -> 72; else -> 144 }
                    val height = when (mode) { 1 -> 106; 2 -> 48; else -> 88 }
                    card.addView(channelArtwork(entry, width, height))
                }
                val details = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(4), dp(4), dp(4))
                }
                details.addView(channelText(entry.title, 18f, true))
                if (showProgramme) details.addView(channelText(programmeLine(entry, now), 12f, false))
                card.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(card, LinearLayout.LayoutParams(0, -2, 1f).apply {
                    setMargins(0, dp(3), dp(8), dp(3))
                })
                val star = button(if (favorites.contains(entry.url)) "★" else "☆") {}
                star.setOnClickListener {
                    toggleFav(entry)
                    if (type == "favorites") showCatalog(type, selectedGroup)
                    else star.text = if (prefs.getStringSet("favorites", emptySet()).orEmpty().contains(entry.url)) "★" else "☆"
                }
                row.addView(star)
                list.addView(row)
            }
            nextPage()
        }

        fill(activeGroup, activeMediaFilter)
        filter.setSelection(initialFilter)
        filter.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {

                override fun onNothingSelected(
                    parent: AdapterView<*>?
                ) {
                }

                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    if (position in 1..mediaFilters.size) {
                        activeGroup = ""
                        activeMediaFilter = position
                    } else {
                        activeGroup = groups.getOrNull(position - mediaFilters.size - 1).orEmpty()
                        activeMediaFilter = 0
                    }
                    fill(activeGroup, activeMediaFilter)
                }
            }
        yearFilter?.let { spinner ->
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    activeYear = movieYears.getOrNull(position - 1).orEmpty()
                    fill(activeGroup, activeMediaFilter)
                }
            }
            spinner.setSelection(if (activeYear.isBlank()) 0 else movieYears.indexOf(activeYear).takeIf { it >= 0 }?.plus(1) ?: 0)
        }
    }

    private fun isSeries(entry: Entry): Boolean {
        val category = entry.group.lowercase(Locale.ROOT)
        val path = Uri.parse(entry.url).path.orEmpty().lowercase(Locale.ROOT)
        return "/series/" in path ||
            listOf("сериал", "series", "serien", "tv shows").any { category.contains(it) } ||
            Regex("""(?i)\bS\d{1,3}E\d{1,4}\b""").containsMatchIn(entry.title)
    }

    private fun movieReleaseYear(entry: Entry): String? =
        Regex("(?<![0-9])(?:19[0-9]{2}|20[0-9]{2})(?![0-9])")
            .find(entry.videoMetadata + " " + entry.title + " " + entry.description)?.value

    private fun channelCard(title: String, vertical: Boolean, action: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = if (vertical) Gravity.CENTER else Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            contentDescription = title
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = background(panel)
            setOnFocusChangeListener { view, focused ->
                view.background = background(if (focused) Color.rgb(18, 91, 180) else panel)
            }
            setOnClickListener { action() }
        }

    private fun channelArtwork(entry: Entry, width: Int, height: Int): ImageView =
        artwork(channelLogo(entry), width, height, fallbackUrl = epgIcons[guideChannelId(entry)].orEmpty()).apply {
            setBackgroundColor(Color.TRANSPARENT)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

    private fun channelText(value: String, size: Float, bold: Boolean, centered: Boolean = false): TextView =
        label(value, size, bold).apply {
            if (centered) gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

    private fun programmeLine(entry: Entry, now: Long): String =
        guideEntries(entry).firstOrNull { now in it.start until it.stop }?.title
            ?: if (prefs.getBoolean("epg_empty_placeholder", true))
                tr("Нет данных о передаче", "Keine Sendungsdaten", "No programme data") else ""

    private fun toggleFav(entry: Entry) {
        val set = (
            prefs.getStringSet("favorites", emptySet())
                ?: emptySet()
        ).toMutableSet()

        if (!set.add(entry.url)) {
            set.remove(entry.url)
        }

        prefs.edit()
            .putStringSet("favorites", set)
            .apply()
    }

    private fun updatePlaylistStatus(url: String, state: Int) {
        playlistStates[url] = state
        playlistStatusViews[url]?.let { renderPlaylistStatus(it, state) }
    }

    private fun renderPlaylistStatus(view: TextView, state: Int) {
        view.text = when (state) {
            1 -> "… " + tr("Загрузка", "Lädt", "Loading")
            2 -> "✓ " + tr("Загружен", "Geladen", "Loaded")
            3 -> "✕ " + tr("Ошибка загрузки", "Ladefehler", "Loading failed")
            else -> ""
        }
        view.setTextColor(when (state) {
            2 -> Color.rgb(109, 233, 153)
            3 -> Color.rgb(255, 121, 129)
            else -> Color.WHITE
        })
    }

    private fun showPlaylists(authorized: Boolean = false) {
        if (!authorized) {
            requirePlaylistPin { showPlaylists(true) }
            return
        }
        playlistStatusViews.clear()
        scaffold("playlists")

        sectionTitle(
            tr(
                "Управление плейлистами",
                "Playlisten verwalten",
                "Manage playlists"
            )
        )

        content.addView(
            button(
                tr(
                    "+ Добавить M3U",
                    "+ M3U hinzufügen",
                    "+ Add M3U"
                )
            ) {
                editList(-1)
            }
        )

        lists.toList().forEachIndexed { index, pair ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            row.addView(
                button(
                    (if (index == activeList) "✓ " else "") +
                        pair.first
                ) {
                    activeList = index
                    saveLists()
                    loadPlaylist(index)
                },
                LinearLayout.LayoutParams(0, dp(60), 1f)
            )

            row.addView(
                button("✎") {
                    editList(index)
                }
            )

            row.addView(
                button("×") {
                    AlertDialog.Builder(this)
                        .setMessage(
                            tr(
                                "Удалить плейлист?",
                                "Playlist löschen?",
                                "Delete playlist?"
                            )
                        )
                        .setPositiveButton("OK") { _, _ ->
                            lists.removeAt(index)
                            activeList =
                                if (lists.isEmpty()) -1 else 0

                            saveLists()

                            if (activeList >= 0) {
                                loadPlaylist(activeList)
                            } else {
                                items.clear()
                            }

                            showPlaylists(authorized = true)
                        }
                        .setNegativeButton(
                            tr("Отмена", "Abbrechen", "Cancel"),
                            null
                        )
                        .show()
                }
            )

            content.addView(row)
            val status = label("", 13f)
            playlistStatusViews[pair.second] = status
            renderPlaylistStatus(status, playlistStates[pair.second] ?: 0)
            content.addView(status)
        }
    }

    private fun importedPlaylistName(url: String): String? =
        prefs.getString("private_playlist:$url", null)

    private fun storeImportedPlaylistName(url: String, title: String) {
        prefs.edit().putString("private_playlist:$url", title).apply()
    }

    private fun editList(index: Int) {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }

        val name = EditText(this).apply {
            hint = tr("Название", "Name", "Name")
            setSingleLine(true)
            setText(lists.getOrNull(index)?.first ?: "")
        }

        val storedAddress = lists.getOrNull(index)?.second
        val privateAddress = storedAddress?.takeIf { importedPlaylistName(it) != null }
        val url = EditText(this).apply {
            hint = "https://..."
            setSingleLine(true)
            if (privateAddress == null) setText(storedAddress.orEmpty())
        }

        form.addView(name)
        if (privateAddress == null) form.addView(url)

        AlertDialog.Builder(this)
            .setTitle("M3U")
            .setView(form)
            .setPositiveButton(
                tr("Сохранить", "Speichern", "Save")
            ) { _, _ ->

                val value = privateAddress ?: url.text.toString().trim()

                if (!value.startsWith("https://") &&
                    !value.startsWith("http://")
                ) {
                    toast("HTTP(S) URL required")
                    return@setPositiveButton
                }

                val title = name.text.toString()
                    .trim()
                    .ifEmpty { "Playlist ${lists.size + 1}" }

                if (index >= 0) {
                    lists[index] = title to value
                } else {
                    lists.add(title to value)
                }

                activeList =
                    if (index >= 0) index else lists.lastIndex

                saveLists()
                loadPlaylist(activeList)
                showPlaylists(authorized = true)
            }
            .setNegativeButton(
                tr("Отмена", "Abbrechen", "Cancel"),
                null
            )
            .show()
    }

    private fun loadPlaylist(index: Int) {
        val url = lists.getOrNull(index)?.second ?: return
        updatePlaylistStatus(url, 1)
        thread {
            try {
                val connection =
                    URL(url).openConnection() as HttpURLConnection

                connection.connectTimeout = 15000
                connection.readTimeout = 30000
                connection.instanceFollowRedirects = true

                val body = connection.inputStream
                    .bufferedReader()
                    .use { it.readText() }

                val resolvedUrl = connection.url.toString()
                connection.disconnect()

                val parsed = parseM3u(body, resolvedUrl)
                require(parsed.isNotEmpty())
                val guides = m3uGuideUrls(body, resolvedUrl)

                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    updatePlaylistStatus(url, 2)
                    if (index == activeList && lists.getOrNull(index)?.second == url) {
                        playlistGuideUrls = guides
                        val knownChannelUrls = items.map { it.url }.toHashSet()
                        val foundNewChannels = parsed.any { it.type == "tv" && it.url !in knownChannelUrls }
                        val merged = items.filter { it.archiveStreamId.isNotBlank() }.associateByTo(linkedMapOf()) { it.url }
                        parsed.forEach { merged[it.url] = it }
                        items.clear()
                        items.addAll(merged.values)
                        if (foundNewChannels && prefs.getBoolean("refresh_epg_on_channels", true)) loadEpg()
                        toast("${parsed.size} items")
                        if (current == "home") showHome()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    updatePlaylistStatus(url, 3)
                    toast(tr("Не удалось загрузить плейлист", "Playlist konnte nicht geladen werden", "Could not load playlist"))
                }
            }
        }
    }

    private fun m3uGuideUrls(body: String, playlistUrl: String): List<String> {
        val guideKeys = setOf("url-tvg", "x-tvg-url", "tvg-url", "epg-url", "epg")
        val attribute = Regex("""([\w-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s,]+))""")
        val values = body.lineSequence().map { it.trim().trimStart('\uFEFF') }
            .filter { it.startsWith("#EXTM3U", true) || it.startsWith("#EXTINF", true) ||
                it.startsWith("#EXT-X-TVG-URL:", true) || it.startsWith("#EPG:", true) }
            .flatMap { line ->
                val attrs = attribute.findAll(line).mapNotNull { match ->
                    val key = match.groupValues[1].lowercase(Locale.ROOT)
                    if (key !in guideKeys) null
                    else match.groupValues.drop(2).firstOrNull(String::isNotEmpty)
                }.toList()
                val directive = if (line.startsWith("#EXT-X-TVG-URL:", true) || line.startsWith("#EPG:", true))
                    listOf(line.substringAfter(':').trim().trim('"', '\'')) else emptyList()
                (attrs + directive).asSequence()
            }
            .flatMap { it.split(',', ';').asSequence() }
        return values.mapNotNull { value ->
                runCatching { URL(URL(playlistUrl), value.trim()).toString() }.getOrNull()
            }.filter { it.startsWith("http://") || it.startsWith("https://") }.distinct().take(8).toList()
    }

    private fun parseM3u(raw: String, playlistUrl: String): List<Entry> {
        val result = ArrayList<Entry>()

        var title = ""
        var group = ""
        var logo = ""
        var epgId = ""
        var description = ""
        var year = ""
        var catchup = ""
        var catchupMode = ""
        var correction = 0.0
        var days = 0

        val attributes = Regex("""([\w-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s,]+))""")
        fun attributesIn(line: String) = attributes.findAll(line).associate {
            it.groupValues[1].lowercase(Locale.ROOT) to it.groupValues.drop(2).firstOrNull(String::isNotEmpty).orEmpty()
        }
        val header = raw.lineSequence().firstOrNull { it.trim().trimStart('\uFEFF').startsWith("#EXTM3U", true) }.orEmpty()
        val defaults = attributesIn(header)

        for (line in raw.lineSequence().map { it.trim().trimStart('\uFEFF') }) {
            if (line.startsWith("#EXTINF", true)) {
                val attrs = defaults + attributesIn(line)
                var quote: Char? = null
                val separator = line.indices.firstOrNull { i ->
                    val char = line[i]
                    if (char == quote) quote = null
                    else if (quote == null && (char == '"' || char.code == 39)) quote = char
                    char == ',' && quote == null
                }
                title = separator?.let { line.substring(it + 1).trim() } ?: attrs["tvg-name"].orEmpty()

                group = attrs["group-title"] ?: "Other"
                logo = sequenceOf("tvg-logo", "logo", "channel-logo").mapNotNull(attrs::get)
                    .firstNotNullOfOrNull { webUrl(playlistUrl, it) }.orEmpty()
                epgId = attrs["tvg-id"]?.takeIf { it.isNotBlank() } ?: attrs["tvg-name"].orEmpty()
                val source = attrs["catchup-source"].orEmpty()
                catchupMode = (attrs["catchup"] ?: attrs["catchup-type"]).orEmpty().lowercase(Locale.ROOT)
                catchup = if (source.isNotBlank() && (catchupMode in setOf("append", "shift", "siptv") ||
                    source.startsWith("?") || source.startsWith("&")))
                    "@append:" + source else source
                val declaredDays = (attrs["catchup-days"] ?: attrs["tvg-rec"] ?: attrs["timeshift"] ?: attrs["catchup-window"])
                    ?.trim()?.toIntOrNull()?.coerceIn(0, 30)
                val xtreamUrl = Regex("""/live/[^/]+/[^/]+/[^/]+(?:\.ts|\.m3u8)?(?:[?#]|$)""", RegexOption.IGNORE_CASE)
                    .containsMatchIn(line)
                if (catchupMode.isBlank() && xtreamUrl) catchupMode = "xc"
                if (catchupMode.isBlank() && source.isNotBlank()) catchupMode =
                    if (catchup.startsWith("@append:")) "append" else "default"
                val fallbackMode = prefs.getString("m3u_catchup_fallback", "auto").orEmpty()
                if (catchupMode.isBlank() && fallbackMode != "auto") catchupMode = fallbackMode
                if (catchupMode.isBlank() && declaredDays != null && declaredDays > 0) catchupMode = "append"
                days = declaredDays ?: if (catchupMode in setOf("default", "append", "shift", "siptv",
                    "flussonic", "fs", "xc", "xtream", "xtream-codes", "vod", "true", "1") || source.isNotBlank()) 7 else 0
                if (declaredDays == null && catchupMode.isNotBlank() && fallbackMode != "auto")
                    days = prefs.getInt("m3u_catchup_days", 7).coerceIn(1, 30)
                correction = attrs["catchup-correction"]?.toDoubleOrNull()?.coerceIn(-14.0, 14.0) ?: 0.0
                year = sequenceOf("year", "tvg-year", "release-year").mapNotNull(attrs::get)
                    .firstOrNull { it.matches(Regex("[0-9]{4}")) }.orEmpty()
                description = sequenceOf("plot", "description", "tvg-description")
                    .mapNotNull(attrs::get).firstOrNull { it.isNotBlank() }.orEmpty()

            } else if (line.startsWith("#EXTGRP:", true)) {
                group = line.substringAfter(':').trim()
            } else if (listOf("http://", "https://", "rtsp://").any { line.startsWith(it, true) }) {
                // A TV playlist's group-title is a category, not proof of VOD.
                val type = "tv"

                result.add(
                    Entry(
                        title.ifBlank { "Stream" },
                        line,
                        group.ifBlank { "Other" },
                        logo,
                        type,
                        epgId,
                        archiveDays = days,
                        description = description,
                        catchupSource = catchup,
                        catchupMode = catchupMode,
                        catchupCorrectionHours = correction,
                        videoMetadata = year
                    )
                )

                title = ""
                group = ""
                logo = ""
                epgId = ""
                description = ""
                year = ""
                catchup = ""
                catchupMode = ""
                correction = 0.0
                days = 0
            }
        }

        return result
    }

    private fun play(
        entry: Entry, streamUrl: String = entry.url, archived: Programme? = null,
        channelOrder: List<Entry>? = null,
        returnToLive: Boolean = false,
        categoryAuthorized: Boolean = false
    ) {
        if (entry.type == "tv" && !categoryAuthorized && adultCategory.containsMatchIn(entry.group)) {
            withCategoryAccess(entry.group) {
                play(entry, streamUrl, archived, channelOrder, returnToLive, categoryAuthorized = true)
            }
            return
        }
        val requestGeneration = ++stalkerPlayGeneration
        if (entry.stalkerId.isNotBlank() && !entry.stalkerResolved) {
            val client = makeStalkerClient() ?: return toast(tr("Сначала активируй Stalker-портал", "Stalker-Portal zuerst aktivieren", "Activate the Stalker portal first"))
            toast(tr("Подключаю канал…", "Sender wird verbunden…", "Connecting to channel…"))
            thread {
                val stream = runCatching { client.stream(StalkerPortalClient.Channel(entry.stalkerId, entry.title,
                    entry.logo, entry.epgId, entry.stalkerCommand)) }
                runOnUiThread {
                    if (isDestroyed || requestGeneration != stalkerPlayGeneration) return@runOnUiThread
                    if (stream.isSuccess) play(entry.copy(stalkerResolved = true), stream.getOrThrow(), archived,
                        channelOrder, returnToLive, categoryAuthorized = true)
                    else toast(tr("Портал не выдал поток: ${stream.exceptionOrNull()?.message.orEmpty()}",
                        "Portal hat keinen Stream geliefert: ${stream.exceptionOrNull()?.message.orEmpty()}",
                        "Portal did not return a stream: ${stream.exceptionOrNull()?.message.orEmpty()}"))
                }
            }
            return
        }
        if (entry.type == "movies" && prefs.getInt("vod_player_type", 0) == 1 && tryExternalPlayer(streamUrl)) return
        nextEpisode = null
        returnToLiveAfterArchive = returnToLive && archived != null && entry.type == "tv"
        val liveTv = entry.type == "tv" && archived == null
        if (liveTv && (channelOrder != null || current != "player" || !liveChannelPlayback)) {
            playbackChannels = (channelOrder ?: allChannelsInOrder()).filter { it.type == "tv" }.distinctBy { it.url }
        }
        liveChannelPlayback = liveTv
        playingArchiveProgramme = archived
        closeSidePanel()
        clockHandler.removeCallbacks(bufferTimeout)
        cancelPlaybackRetry()
        playbackRetryCount = 0
        playbackStartedAt = null
        rememberVideoProgress()
        clockHandler.removeCallbacks(saveVideoProgress)
        playingVideo = null
        clearPlaybackError()
        clearLiveStreamNotice()
        playerView?.player = null
        currentEntry = entry
        requestedOrientation = if (isPhone()) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        current = "player"
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        val frame = FrameLayout(this).apply { setBackgroundColor(if (prefs.getBoolean("player_black_on_zap", true)) Color.BLACK else bg) }
        val view = PlayerView(this).apply {
            useController = !liveTv
            controllerAutoShow = false
            controllerShowTimeoutMs = 5000
            setShowSubtitleButton(true)
            isFocusable = true
            isFocusableInTouchMode = true
            resizeMode = when (prefs.getString("video_scale", "fit")) {
                "zoom" -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                "fill" -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
                else -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
            player = this@MainActivity.player
            setOnClickListener { if (liveTv) showChannelOverlay() else showController() }
            setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_MENU -> { if (liveTv) showChannelOverlay() else showController(); true }
                    else -> false
                }
            }
        }
        playerView = view
        frame.addView(view, FrameLayout.LayoutParams(-1, -1))
        playerFrame = frame
        sidePanel = null
        sidePanelBack = null
        if (liveTv && isPhone()) {
            val left = View(this).apply {
                setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener { showSideGuide() }
            }
            val right = View(this).apply {
                setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener { showChannelOverlay() }
            }
            frame.addView(left, FrameLayout.LayoutParams(dp(42), -1, Gravity.START))
            frame.addView(right, FrameLayout.LayoutParams(dp(42), -1, Gravity.END))
        }
        if (prefs.getBoolean("show_clock", true)) {
            val clock = android.widget.TextClock(this).apply {
                format24Hour = "HH:mm"; format12Hour = "hh:mm a"
                setTextColor(Color.WHITE); textSize = 19f
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setBackgroundColor(0x99000000.toInt())
            }
            frame.addView(clock, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
        }
        if (entry.type == "tv") showChannelInfo(frame, entry, archived)
        if (entry.type == "radio" || entry.type == "music") {
            val stationType = if (entry.type == "music") tr("Музыкальная станция", "Musikstation", "Music station")
                else tr("Интернет-радио", "Internetradio", "Internet radio")
            val station = label("♫  ${entry.title}\n$stationType", 25f, true).apply {
                gravity = Gravity.CENTER
                setBackgroundColor(0xBB09194F.toInt())
            }
            frame.addView(station, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        }
        setContentView(frame)
        view.requestFocus()
        if (liveTv) loadChannelGuide(entry)
        try {
            val uri = Uri.parse(streamUrl)
            val media = MediaItem.Builder().setUri(uri)
            when (uri.takeIf { it.isHierarchical }?.getQueryParameter("output")?.lowercase(Locale.ROOT)) {
                "m3u8", "hls" -> media.setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8)
                "mpd", "dash" -> media.setMimeType(androidx.media3.common.MimeTypes.APPLICATION_MPD)
            }
            val video = entry.takeIf { it.type == "movies" }
            val resumeAt = video?.let {
                videoRecord(it).takeUnless { record -> record.optBoolean("completed") }?.optLong("position")
            } ?: 0L
            val mediaItem = media.build()
            if (entry.stalkerId.isNotBlank()) {
                val headers = makeStalkerClient()?.playbackHeaders().orEmpty()
                val http = androidx.media3.datasource.DefaultHttpDataSource.Factory().setDefaultRequestProperties(headers)
                val data = androidx.media3.datasource.DefaultDataSource.Factory(this, http)
                val sourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(this).setDataSourceFactory(data)
                player?.setMediaSource(sourceFactory.createMediaSource(mediaItem), resumeAt)
            } else player?.setMediaItem(mediaItem, resumeAt)
            playingVideo = video
            if (video != null) clockHandler.postDelayed(saveVideoProgress, 15000L)
            player?.prepare()
            player?.play()
        } catch (e: Exception) {
            showPlaybackError(e.javaClass.simpleName)
        }
    }

    private fun tryExternalPlayer(url: String): Boolean {
        return try {
            val mime = when {
                url.contains(".m3u8", true) -> "application/vnd.apple.mpegurl"
                url.contains(".mpd", true) -> "application/dash+xml"
                else -> "video/*"
            }
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), mime)
            startActivity(Intent.createChooser(view, tr("Выбери видеоплеер", "Videoplayer auswählen", "Choose a video player")))
            true
        } catch (_: Exception) {
            toast(tr("Внешний плеер не открыл ссылку; запускаю встроенный", "Externer Player konnte den Link nicht öffnen; integrierter Player wird verwendet",
                "External player could not open this link; using the built-in player"))
            false
        }
    }

    private var playbackRetryCount = 0
    private var playbackStartedAt: Long? = null
    private var pendingPlaybackRetry: Runnable? = null
    private var playbackScreenStarted = false
    private var liveStreamNotice: View? = null

    private fun clearLiveStreamNotice() {
        liveStreamNotice?.let { (it.parent as? FrameLayout)?.removeView(it) }
        liveStreamNotice = null
    }

    private fun showLiveStreamNotice(reconnecting: Boolean) {
        if (!prefs.getBoolean("player_show_stream_status", true)) return
        val frame = playerFrame ?: return
        clearLiveStreamNotice()
        val message = if (reconnecting) tr("Восстанавливаю эфир…", "Live-Stream wird neu verbunden…",
            "Reconnecting live stream…")
        else tr("Эфир недоступен · ↑/↓ сменить канал", "Live-Stream nicht verfügbar · ↑/↓ Sender wechseln",
            "Live stream unavailable · ↑/↓ change channel")
        val notice = label(message, 15f, true).apply {
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setBackgroundColor(0xB0192234.toInt())
        }
        liveStreamNotice = notice
        frame.addView(notice, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(24)
        })
    }

    private val bufferTimeout = Runnable {
        val active = player
        if (active != null && active.playWhenReady && active.playbackState == Player.STATE_BUFFERING) {
            retryPlayback(tr("Поток не отвечает", "Stream antwortet nicht", "Stream is not responding"))
        }
    }

    private fun cancelPlaybackRetry() {
        pendingPlaybackRetry?.let { clockHandler.removeCallbacks(it) }
        pendingPlaybackRetry = null
    }

    private fun retryPlayback(code: String) {
        if (current != "player" || isFinishing || isDestroyed || pendingPlaybackRetry != null) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (playbackStartedAt?.let { now - it >= 30_000L } == true) playbackRetryCount = 0
        playbackStartedAt = null
        val delays = if (liveChannelPlayback) longArrayOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 30_000L)
            else longArrayOf(1_000L, 2_000L, 4_000L)
        if ((!liveChannelPlayback && playbackRetryCount >= delays.size) || player?.currentMediaItem == null) {
            player?.pause()
            showPlaybackError(code)
            return
        }
        val delay = delays[playbackRetryCount.coerceAtMost(delays.lastIndex)]
        playbackRetryCount = (playbackRetryCount + 1).coerceAtMost(delays.size)
        if (liveChannelPlayback && liveStreamNotice == null) showLiveStreamNotice(reconnecting = true)
        val retry = Runnable {
            pendingPlaybackRetry = null
            if (playbackScreenStarted && current == "player" && !isFinishing && !isDestroyed) {
                recoverPlayback()
            }
        }
        pendingPlaybackRetry = retry
        if (playbackScreenStarted) clockHandler.postDelayed(retry, delay)
    }

    private fun recoverPlayback() {
        val active = player ?: return
        if (active.currentMediaItem == null) return
        try {
            active.stop()
            if (liveChannelPlayback) active.seekToDefaultPosition()
            active.prepare()
            active.play()
        } catch (error: Exception) {
            showPlaybackError(error.javaClass.simpleName)
        }
    }

    private fun clearPlaybackError() {
        playbackErrorPanel?.let { playerFrame?.removeView(it) }
        playbackErrorPanel = null
    }

    private fun showPlaybackError(code: String) {
        val frame = playerFrame ?: return
        if (current != "player" || isFinishing || isDestroyed) return
        if (liveChannelPlayback) {
            showLiveStreamNotice(reconnecting = false)
            return
        }
        if (videoPanelSide != null) closeSidePanel()
        clearPlaybackError()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xF0192234.toInt())
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        panel.addView(label(tr("Поток недоступен", "Stream nicht verfügbar", "Stream unavailable"), 22f, true))
        panel.addView(label(code, 14f))
        val retry = button(tr("Повторить", "Erneut versuchen", "Retry")) {
            clearPlaybackError()
            playerView?.requestFocus()
            playbackRetryCount = 0
            playbackStartedAt = null
            recoverPlayback()
        }
        panel.addView(retry)
        panel.addView(button(tr("Назад", "Zurück", "Back")) { onBackPressed() })
        playbackErrorPanel = panel
        frame.addView(panel, FrameLayout.LayoutParams(dp(300), -2, Gravity.CENTER))
        retry.requestFocus()
    }

    private fun allChannelsInOrder(): List<Entry> {
        val channels = (items.filter { it.type == "tv" } + stalkerEpgChannels.values).distinctBy { it.epgId.ifBlank { it.url } }
        return if (prefs.getBoolean("sort_channels_alpha", false))
            channels.sortedBy { it.title.lowercase(Locale.ROOT) } else channels
    }

    private fun changeChannel(direction: Int) {
        val index = playbackChannels.indexOfFirst { it.url == currentEntry?.url }
        if (index < 0) return
        playbackChannels.getOrNull(index + direction)?.let { play(it) }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && videoPanelSide != null) restartPanelTimeout()
        if (current != "player" || playbackErrorPanel != null) {
            return super.dispatchKeyEvent(event)
        }
        val key = event.keyCode
        if (!liveChannelPlayback) {
            if (playingArchiveProgramme == null || key != KeyEvent.KEYCODE_DPAD_LEFT) {
                return super.dispatchKeyEvent(event)
            }
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && sidePanel == null) {
                playerView?.hideController()
                showSideGuide()
            }
            return true
        }
        val panelOpen = sidePanel != null
        val handled = key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT ||
            (!panelOpen && key in setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN,
                KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS))
        if (!handled) return super.dispatchKeyEvent(event)
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return true
        when (key) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (videoPanelSide != true) showSideGuide()
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val group = currentFocus?.tag as? TvGroupChoice
                if (videoPanelSide == false && group != null) openGroupChannels(group.name)
                else if (videoPanelSide != false) showPlaybackCategories()
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> changeChannel(-1)
            else -> changeChannel(1)
        }
        return true
    }

    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        if (videoPanelSide != null) restartPanelTimeout()
        return super.dispatchTouchEvent(event)
    }

    private var focusBeforePanel: View? = null
    private val hideVideoPanel = Runnable {
        if (videoPanelSide != null) closeSidePanel()
    }

    private fun restartPanelTimeout() {
        clockHandler.removeCallbacks(hideVideoPanel)
        if (videoPanelSide != null) clockHandler.postDelayed(hideVideoPanel, 8_000L)
    }

    private fun closeSidePanel() {
        clockHandler.removeCallbacks(hideVideoPanel)
        videoPanelSide = null
        guideWaitingForData = false
        sidePanel?.let { panelView -> (panelView.parent as? FrameLayout)?.removeView(panelView) }
        sidePanel = null
        sidePanelBack = null
        focusBeforePanel?.requestFocus()
        focusBeforePanel = null
    }

    private fun openPanel(
        left: Boolean,
        heading: String,
        host: FrameLayout? = playerFrame,
        overVideo: Boolean = false,
        fill: (LinearLayout) -> Unit
    ) {
        val frame = host ?: return
        closeSidePanel()
        focusBeforePanel = currentFocus
        val screenWidth = resources.displayMetrics.widthPixels
        val panelWidth = (screenWidth * (if (overVideo) 0.34f else 0.42f)).toInt()
            .coerceAtLeast(dp(220)).coerceAtMost(minOf(dp(440), screenWidth))
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = if (overVideo) GradientDrawable(
                if (left) GradientDrawable.Orientation.LEFT_RIGHT else GradientDrawable.Orientation.RIGHT_LEFT,
                intArrayOf(0xB5142030.toInt(), Color.TRANSPARENT)
            ) else GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.rgb(27, 39, 66), Color.rgb(10, 17, 31))
            ).apply { cornerRadius = dp(18).toFloat(); setStroke(dp(2), accent) }
            isFocusableInTouchMode = true
        }
        val headingRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        headingRow.addView(label(heading, 21f, true).apply {
            if (overVideo) setShadowLayer(5f, 0f, 1f, Color.BLACK)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (!overVideo) headingRow.addView(button("✕") { sidePanelBack?.invoke() ?: closeSidePanel() })
        shell.addView(headingRow)
        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)
        shell.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        frame.addView(shell, FrameLayout.LayoutParams(panelWidth, -1,
            if (left) Gravity.START else Gravity.END).apply {
                if (overVideo) setMargins(dp(8), dp(46), dp(8), dp(28))
            })
        shell.translationX = if (left) -panelWidth.toFloat() else panelWidth.toFloat()
        shell.animate().translationX(0f).setDuration(220L).start()
        sidePanel = shell
        if (overVideo) {
            videoPanelSide = left
            restartPanelTimeout()
        }
        fill(body)
        if (overVideo) {
            val options = (0 until body.childCount).map { body.getChildAt(it) }.filter { it.isFocusable }
            options.forEach { it.id = View.generateViewId() }
            options.forEachIndexed { index, row ->
                row.nextFocusUpId = options[(index - 1).coerceAtLeast(0)].id
                row.nextFocusDownId = options[(index + 1).coerceAtMost(options.lastIndex)].id
            }
        }
        shell.requestFocus()
        (0 until body.childCount).asSequence().map { body.getChildAt(it) }
            .firstOrNull { it.isFocusable }?.requestFocus()
    }

    private fun videoOverlayButton(value: String, action: () -> Unit): Button =
        button(value, action).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setShadowLayer(6f, 0f, 1f, Color.BLACK)
            setBackgroundColor(Color.TRANSPARENT)
            setOnFocusChangeListener { _, focused ->
                background = if (focused) background(0xB0203852.toInt()) else null
            }
        }

    private fun currentProgrammeTitle(entry: Entry, now: Long): String? =
        guideEntries(entry).firstOrNull { now in it.start until it.stop }
            ?.title?.takeIf { it.isNotBlank() }

    private data class TvGroupChoice(val name: String)

    private fun showPlaybackCategories() {
        val selected = currentEntry?.group
        val groups = allChannelsInOrder().map { it.group }.distinct().sorted()
        openPanel(false, tr("Категории", "Kategorien", "Categories"), overVideo = true) { body ->
            groups.forEach { group ->
                val name = group.ifBlank { tr("Другие", "Sonstige", "Other") }
                val title = listOfNotNull(tvCategorySymbol(name), name).joinToString(" ")
                val row = videoOverlayButton(title) { openGroupChannels(group) }.apply { tag = TvGroupChoice(group) }
                body.addView(row)
                if (group == selected) row.post { if (row.isAttachedToWindow) row.requestFocus() }
            }
        }
    }

    private fun openGroupChannels(group: String) {
        withCategoryAccess(group) { showChannelOverlay(group = group) }
    }

    private fun showChannelOverlay(requestedPage: Int? = null, group: String? = null) {
        val selected = currentEntry
        val channels = if (group == null) playbackChannels else allChannelsInOrder().filter { it.group == group }
        val pageSize = 36
        val currentIndex = channels.indexOfFirst { it.url == selected?.url }.coerceAtLeast(0)
        val page = requestedPage ?: currentIndex / pageSize
        val first = page * pageSize
        val visible = channels.drop(first).take(pageSize)
        openPanel(false, tr("Телеканалы", "Sender", "Channels"), overVideo = true) { body ->
            if (page > 0) body.addView(videoOverlayButton(tr("↑ Предыдущая страница", "↑ Vorherige Seite",
                "↑ Previous page")) { showChannelOverlay(page - 1, group) })
            var playingRow: View? = null
            val programmeLabels = mutableListOf<Pair<Entry, TextView>>()
            visible.forEachIndexed { offset, entry ->
                val playing = entry.url == selected?.url
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    isFocusable = true
                    isClickable = true
                    contentDescription = entry.title
                    setPadding(dp(8), dp(3), dp(8), dp(3))
                    background = if (playing) background(0x64375478) else null
                    setOnFocusChangeListener { view, focused ->
                        view.background = if (focused) background(0xDD245FA5.toInt())
                            else if (playing) background(0x64375478) else null
                        if (focused && currentProgrammeTitle(entry, System.currentTimeMillis()) == null) {
                            loadChannelGuide(entry)
                        }
                    }
                    setOnClickListener { play(entry, channelOrder = channels, categoryAuthorized = group != null) }
                }
                val number = if (playing) "▶" else (first + offset + 1).toString()
                row.addView(label(number, 12f, true).apply { setTextColor(0xFF9CD5FF.toInt()) },
                    LinearLayout.LayoutParams(dp(46), -2))
                val info = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(channelText(entry.title, 16f, true).apply { setPadding(0, dp(6), 0, dp(2)) })
                    val programme = channelText("", 13f, false).apply {
                        setTextColor(0xFFD2E2F4.toInt())
                        setPadding(0, 0, 0, dp(6))
                    }
                    programmeLabels.add(entry to programme)
                    addView(programme)
                }
                row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
                body.addView(row)
                if (playing) playingRow = row
            }
            if (first + visible.size < channels.size) body.addView(videoOverlayButton(tr("↓ Следующая страница",
                "↓ Nächste Seite", "↓ Next page")) { showChannelOverlay(page + 1, group) })
            if (visible.isEmpty()) body.addView(label(tr("Нет каналов", "Keine Sender", "No channels")))
            fun updateProgrammeLabels() {
                val now = System.currentTimeMillis()
                programmeLabels.forEach { (entry, label) ->
                    val title = currentProgrammeTitle(entry, now)
                        ?: tr("Нет данных о передаче", "Keine Sendungsdaten", "No programme data")
                    if (label.text.toString() != title) label.text = title
                }
            }
            updateProgrammeLabels()
            body.postDelayed(object : Runnable {
                override fun run() {
                    if (!body.isAttachedToWindow) return
                    updateProgrammeLabels()
                    body.postDelayed(this, 1_000L)
                }
            }, 1_000L)
            playingRow?.let { row -> row.post { if (row.isAttachedToWindow) row.requestFocus() } }
        }
        if (group != null && sidePanel != null) sidePanelBack = { showPlaybackCategories() }
    }

    private fun showChannelInfo(frame: FrameLayout, entry: Entry, archived: Programme?) {
        if (archived != null && !prefs.getBoolean("player_show_archive_marker", true)) return
        frame.findViewWithTag<View>("channel-info")?.let { frame.removeView(it) }
        val now = System.currentTimeMillis()
        val schedule = guideEntries(entry)
        val airing = schedule.firstOrNull { now in it.start until it.stop }
        val next = schedule.firstOrNull { it.start >= (airing?.stop ?: now) }
        val programme = archived ?: airing
        val time = SimpleDateFormat("HH:mm", Locale.forLanguageTag(lang))
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = GradientDrawable().apply {
                setColors(intArrayOf(0xE4162235.toInt(), 0xCC263A53.toInt()))
                orientation = GradientDrawable.Orientation.LEFT_RIGHT
                cornerRadius = dp(14).toFloat()
            }
        }
        panel.addView(label(entry.title, 19f, true))
        if (programme != null) {
            val range = time.format(Date(programme.start)) + "\u2013" + time.format(Date(programme.stop))
            val status = if (archived == null) tr("\u0421\u0435\u0439\u0447\u0430\u0441", "Jetzt", "Now") else tr("\u0410\u0440\u0445\u0438\u0432", "Archiv", "Archive")
            panel.addView(label("$status \u00b7 $range   ${programme.title}", 17f, true))
        } else {
            panel.addView(label(tr("\u041d\u0435\u0442 \u0442\u0435\u043b\u0435\u043f\u0440\u043e\u0433\u0440\u0430\u043c\u043c\u044b \u0434\u043b\u044f \u044d\u0442\u043e\u0433\u043e \u043a\u0430\u043d\u0430\u043b\u0430", "Keine Programmdaten f\u00fcr diesen Sender", "No guide data for this channel"), 15f))
        }
        if (archived == null && airing != null) {
            val duration = airing.stop - airing.start
            if (duration > 0L) {
                val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100
                    this.progress = ((now - airing.start) * 100 / duration).coerceIn(0L, 100L).toInt()
                    progressTintList = android.content.res.ColorStateList.valueOf(accent)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                panel.addView(progress, LinearLayout.LayoutParams(-1, dp(4)).apply {
                    setMargins(dp(12), 0, dp(12), dp(3))
                })
            }
        }
        if (archived == null && next != null) {
            panel.addView(label(tr("\u0414\u0430\u043b\u0435\u0435", "Danach", "Next") + " \u00b7 " +
                time.format(Date(next.start)) + "   " + next.title, 15f))
        }
        panel.tag = "channel-info"
        val gravity = when (prefs.getInt("player_overlay_style", 2)) {
            1 -> Gravity.TOP
            3 -> Gravity.CENTER
            else -> Gravity.BOTTOM
        }
        frame.addView(panel, FrameLayout.LayoutParams(-1, -2, gravity).apply {
            setMargins(dp(20), 0, dp(20), dp(16))
        })
        panel.postDelayed({ frame.removeView(panel) }, 5_000L)
    }

    private data class ChannelGuide(val entries: List<Programme>, val savedAt: Long)
    private val channelGuides = linkedMapOf<String, ChannelGuide>()
    private val channelGuideRequests = mutableSetOf<String>()

    private fun guideEntries(entry: Entry): List<Programme> {
        val local = epgSchedule[guideChannelId(entry)].orEmpty()
        val direct = channelGuides[entry.url]?.entries ?: return local
        return if (local.isEmpty()) direct else (direct + local).distinctBy { it.start }.sortedBy { it.start }
    }

    private fun loadChannelGuide(entry: Entry) {
        val configured = prefs.getString("epg_url", "").orEmpty()
        val server = prefs.getString("xtream_server", "").orEmpty().trimEnd('/')
        val user = prefs.getString("xtream_user", "").orEmpty()
        val password = prefs.getString("xtream_pass", "").orEmpty()
        val streamId = entry.archiveStreamId
        fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
        val isXtream = streamId.isNotBlank() && server.isNotBlank() && user.isNotBlank() &&
            password.isNotBlank() && entry.url.startsWith("$server/live/")
        val address = when {
            isXtream -> "$server/player_api.php?username=${enc(user)}&password=${enc(password)}" +
                "&action=get_simple_data_table&stream_id=${enc(streamId)}"
            configured.contains("{channel}") && entry.epgId.isNotBlank() ->
                configured.replace("{channel}", enc(entry.epgId))
            else -> return
        }
        val now = System.currentTimeMillis()
        val prior = channelGuides[entry.url]
        if (prior != null && now - prior.savedAt < 10 * 60_000L) return
        if (!channelGuideRequests.add(entry.url)) return
        thread {
            val rows = runCatching {
                if (isXtream) readXtreamChannelGuide(address, entry)
                else readGuide(address, now, setOf(normalizedGuideName(entry.epgId))) {}.programmes.values.flatten()
            }.getOrDefault(emptyList())
            runOnUiThread {
                channelGuideRequests.remove(entry.url)
                if (isDestroyed || rows.isEmpty()) return@runOnUiThread
                channelGuides[entry.url] = ChannelGuide(rows.distinctBy { it.start }.sortedBy { it.start }, now)
                while (channelGuides.size > 40) channelGuides.remove(channelGuides.keys.first())
                if (current == "player" && currentEntry?.url == entry.url) {
                    if (videoPanelSide == true && guideWaitingForData) showSideGuide()
                    else if (liveChannelPlayback && sidePanel == null && playbackErrorPanel == null) {
                        playerFrame?.let { frame -> showChannelInfo(frame, entry, null) }
                    }
                }
            }
        }
    }

    private fun readXtreamChannelGuide(address: String, entry: Entry): List<Programme> {
        val connection = URL(address).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 12_000
            connection.instanceFollowRedirects = false
            val json = connection.inputStream.bufferedReader().use { reader ->
                val output = StringBuilder()
                val buffer = CharArray(4096)
                var read = reader.read(buffer)
                while (read != -1) {
                    require(output.length + read <= 2 * 1024 * 1024)
                    output.append(buffer, 0, read)
                    read = reader.read(buffer)
                }
                JSONObject(output.toString())
            }
            val rows = json.optJSONArray("epg_listings") ?: return emptyList()
            fun decoded(value: String): String = runCatching {
                String(android.util.Base64.decode(value, android.util.Base64.DEFAULT), Charsets.UTF_8)
                    .takeUnless { it.contains('\uFFFD') } ?: value
            }.getOrDefault(value)
            return (0 until rows.length()).mapNotNull { index ->
                val item = rows.optJSONObject(index) ?: return@mapNotNull null
                val start = item.optLong("start_timestamp") * 1000L
                val stop = item.optLong("stop_timestamp") * 1000L
                val title = decoded(item.optString("title")).take(240)
                if (start <= 0 || stop <= start || title.isBlank()) return@mapNotNull null
                Programme(entry.epgId, start, stop, title, decoded(item.optString("description")).take(500))
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun normalizedGuideName(value: String): String {
        val cyrillic = mapOf(
            'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "yo",
            'ж' to "zh", 'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m",
            'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u",
            'ф' to "f", 'х' to "kh", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh", 'щ' to "shch",
            'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya"
        )
        return buildString {
            value.lowercase(Locale.ROOT).forEach { ch ->
                append(cyrillic[ch] ?: if (ch.isLetterOrDigit()) ch.toString() else "")
            }
        }
    }

    private fun guideNameKeys(value: String): Set<String> {
        val plain = value.trim().replace(qualitySuffix, "")
        val normalize = prefs.getBoolean("epg_normalize_names", true)
        val key: (String) -> String = if (normalize) { text -> normalizedGuideName(text) }
            else { text -> text.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]"), "") }
        val baseKeys = setOf(key(value), key(plain)).filter(String::isNotEmpty).toMutableSet()
        if (prefs.getInt("epg_match_mode", 2) < 2 || !normalize) return baseKeys
        val matchedGroups = guideAliasGroups.filter { group -> group.any { normalizedGuideName(it) in baseKeys } }
        matchedGroups.flatMap { it }.mapTo(baseKeys, ::normalizedGuideName)
        return baseKeys
    }

    private fun guideChannelId(entry: Entry): String? {
        entry.epgId.takeIf { it in epgSchedule || it in epgIcons }?.let { return it }
        if (prefs.getInt("epg_match_mode", 2) == 0) return null
        return sequenceOf(entry.epgId, entry.title, entry.title.trim().replace(qualitySuffix, ""))
            .flatMap { guideNameKeys(it).asSequence() }.mapNotNull(epgAliases::get).firstOrNull()
    }

    private fun webUrl(base: String, value: String): String? {
        if (value.isBlank()) return null
        return runCatching { URL(URL(base), value.trim()).toString() }.getOrNull()
            ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
    }

    private fun channelLogo(entry: Entry): String =
        entry.logo.ifBlank { if (entry.type == "tv") epgIcons[guideChannelId(entry)].orEmpty() else "" }

    private val guideHistoryWindowMs: Long
        get() = prefs.getInt("epg_history_days", 14).coerceIn(1, 30) * 86_400_000L

    private fun archiveUrl(entry: Entry, programme: Programme, playbackTime: Long): String? {
        val durationMinutes = ((programme.stop - programme.start + 59999) / 60000)
        if (programme.stop <= playbackTime && durationMinutes > 0 &&
            entry.archiveDays > 0 && programme.start >=
                playbackTime - minOf(guideHistoryWindowMs, entry.archiveDays * 86_400_000L)) {
            val mode = entry.catchupMode
            val template = entry.catchupSource
            val correctionMs = (entry.catchupCorrectionHours * 3_600_000.0).toLong()
            val startMs = programme.start + correctionMs
            val stopMs = programme.stop + correctionMs
            val start = startMs / 1000L
            val stop = stopMs / 1000L
            val now = playbackTime / 1000L
            fun datePart(pattern: String): String = SimpleDateFormat(pattern, Locale.ROOT).format(Date(startMs))
            fun appendQuery(base: String, query: String): String =
                base + (if ('?' in base) "&" else "?") + query.removePrefix("?").removePrefix("&")
            fun flussonic(): String? = runCatching {
                val source = URL(entry.url)
                val path = source.path.substringBeforeLast('/', "") + "/timeshift_abs-$start.ts"
                URL(source.protocol, source.host, source.port, path + (source.query?.let { "?$it" } ?: "")).toString()
            }.getOrNull()
            fun xtream(): String? = runCatching {
                val source = URL(entry.url)
                val parts = source.path.trim('/').split('/')
                val liveIndex = parts.indexOfLast { it.equals("live", true) }
                if (liveIndex < 0 || parts.size < liveIndex + 4) return@runCatching null
                val user = parts[liveIndex + 1]
                val password = parts[liveIndex + 2]
                val stream = parts[liveIndex + 3].substringBeforeLast('.')
                val timestamp = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.ROOT).format(Date(startMs))
                val path = (parts.take(liveIndex) + listOf("timeshift", user, password, durationMinutes.toString(), timestamp, "$stream.ts"))
                    .joinToString("/", prefix = "/")
                URL(source.protocol, source.host, source.port, path + (source.query?.let { "?$it" } ?: "")).toString()
            }.getOrNull()
            val queryTemplate = "?utc={utc}&lutc={lutc}"
            return when {
                mode in setOf("xc", "xtream", "xtream-codes") -> xtream()
                mode in setOf("fs", "flussonic") && template.isBlank() -> flussonic()
                template.isNotBlank() || mode.isNotBlank() -> runCatching {
                    val append = template.startsWith("@append:") || mode in setOf("append", "shift", "siptv")
                    var resolved = template.removePrefix("@append:").ifBlank { queryTemplate }
                        .replace("&amp;", "&")
                        .replace("{utcend}", stop.toString()).replace("${'$'}{end}", stop.toString())
                        .replace("{utc}", start.toString()).replace("${'$'}{start}", start.toString())
                        .replace("{start}", start.toString()).replace("{end}", stop.toString())
                        .replace("${'$'}{duration}", (stop - start).toString()).replace("{duration}", (stop - start).toString())
                        .replace("{lutc}", now.toString()).replace("${'$'}{now}", now.toString())
                        .replace("{Y}", datePart("yyyy")).replace("{m}", datePart("MM")).replace("{d}", datePart("dd"))
                        .replace("{H}", datePart("HH")).replace("{M}", datePart("mm")).replace("{S}", datePart("ss"))
                    val address = if (append) appendQuery(entry.url, resolved)
                    else if (resolved.startsWith("http://") || resolved.startsWith("https://")) resolved
                    else URL(URL(entry.url), resolved).toString()
                    address.takeIf { !it.contains('{') && (it.startsWith("https://") || it.startsWith("http://")) }
                }.getOrNull()
                entry.archiveBase.isNotBlank() -> {
                    val timestamp = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.ROOT)
                        .format(Date(programme.start))
                    "${entry.archiveBase}/$durationMinutes/$timestamp/${entry.archiveStreamId}.ts"
                }
                else -> null
            }

        }
        return null
    }

    private fun showSideGuide(requestedPage: Int? = null) {
        val entry = currentEntry ?: return
        loadChannelGuide(entry)
        openPanel(true, tr("Телепрограмма", "TV-Programm", "TV guide"), overVideo = true) { body ->
            guideWaitingForData = requestedPage == null && guideEntries(entry).isEmpty()
            fillChannelGuide(entry, body, overVideo = true, requestedPage = requestedPage)
        }
    }

    private fun showGuideDialog(entry: Entry) {
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(body) }
        val dialog = AlertDialog.Builder(this).setView(scroll)
            .setNegativeButton(tr("Закрыть", "Schließen", "Close"), null).create()
        fillChannelGuide(entry, body) { dialog.dismiss() }
        dialog.show()
    }

    private fun fillChannelGuide(
        entry: Entry, body: LinearLayout, overVideo: Boolean = false,
        requestedPage: Int? = null, beforeReplay: () -> Unit = {}
    ) {
        val now = System.currentTimeMillis()
        body.addView(label(entry.title, 19f, true).apply {
            if (overVideo) setShadowLayer(5f, 0f, 1f, Color.BLACK)
        })
        val schedule = guideEntries(entry)
        if (schedule.isEmpty()) {
            body.addView(label(tr("Нет EPG для этого канала. Добавь XMLTV в настройках.",
                "Kein EPG für diesen Sender. XMLTV in Einstellungen hinzufügen.",
                "No EPG for this channel. Add XMLTV in settings.")))
        }
        val format = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
        val available = schedule.filter { it.stop > now - guideHistoryWindowMs && it.start < now + 7L * 86400000 }
        val pageSize = 36
        val archiveStart = playingArchiveProgramme?.start?.takeIf { overVideo }
        val archiveIndex = available.indexOfFirst { it.start == archiveStart }
        val currentIndex = archiveIndex.takeIf { it >= 0 } ?: available.indexOfFirst { it.stop > now }.let {
            if (it < 0) available.lastIndex.coerceAtLeast(0) else it
        }
        val page = requestedPage ?: currentIndex / pageSize
        fun guidePageButton(title: String, targetPage: Int): Button {
            val action: () -> Unit = {
                if (overVideo) showSideGuide(targetPage)
                else {
                    body.removeAllViews()
                    fillChannelGuide(entry, body, requestedPage = targetPage, beforeReplay = beforeReplay)
                    (0 until body.childCount).asSequence().map { body.getChildAt(it) }
                        .firstOrNull { it.isFocusable }?.requestFocus()
                }
            }
            return if (overVideo) videoOverlayButton(title, action) else button(title, action)
        }
        if (page > 0) body.addView(guidePageButton(
            tr("↑ Ранее", "↑ Früher", "↑ Earlier"), page - 1))
        available.drop(page * pageSize).take(pageSize).forEach { programme ->
                val past = programme.stop <= now
                val replay = archiveUrl(entry, programme, now)
                val text = format.format(Date(programme.start)) + "  " + programme.title +
                    when {
                        replay != null -> tr("  · ◷ Архив", "  · ◷ Archiv", "  · ◷ Replay")
                        now in programme.start until programme.stop -> tr("  · Сейчас", "  · Jetzt", "  · Now")
                        else -> ""
                    }
                val action: () -> Unit = action@{
                    if (replay != null) {
                        beforeReplay()
                        play(entry, replay, archived = programme, returnToLive = overVideo)
                        return@action
                    }
                    AlertDialog.Builder(this)
                        .setTitle(programme.title)
                        .setMessage(format.format(Date(programme.start)) + " – " +
                            format.format(Date(programme.stop)) + "\n\n" + programme.description +
                            if (past) "\n\n" + tr(
                                "Для воспроизведения записи требуется Catch-up провайдера.",
                                "Zur Wiedergabe ist Anbieter-Catch-up erforderlich.",
                                "Playback requires provider Catch-up support.") else "")
                        .setPositiveButton("OK", null).show()
                }
                val row = if (overVideo) videoOverlayButton(text, action) else button(text, action)
                body.addView(row)
                val focusProgramme = if (archiveIndex >= 0) programme.start == archiveStart
                    else now in programme.start until programme.stop
                if (overVideo && requestedPage == null && focusProgramme) {
                    row.post { if (row.isAttachedToWindow) row.requestFocus() }
                }
            }
        if ((page + 1) * pageSize < available.size) body.addView(guidePageButton(
            tr("↓ Позже", "↓ Später", "↓ Later"), page + 1))
    }

    private fun vodPlaylistUrls(): List<String> {
        val stored = prefs.getString("vod_playlist_urls", null)
        if (stored == null) return listOfNotNull(prefs.getString("vod_url", null)?.takeIf { it.isNotBlank() })
        return runCatching {
            val array = JSONArray(stored)
            (0 until array.length()).map { array.getString(it) }
        }.getOrDefault(emptyList())
    }

    private fun saveVodPlaylistUrls(addresses: List<String>) {
        prefs.edit().putString("vod_playlist_urls", JSONArray(addresses.distinct()).toString()).apply()
        loadVodPlaylist()
    }

    private fun showVodStatus(text: String, color: Int = Color.WHITE) {
        vodStatusText = text
        vodStatusColor = color
        vodStatusView?.let { view -> view.text = text; view.setTextColor(color) }
    }

    private val vodPlaylistArrivals = linkedMapOf<String, List<Entry>>()

    private fun sourceSnapshotKey(address: String, kind: String): String = kind + "_" +
        java.security.MessageDigest.getInstance("SHA-256").digest(address.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun newestVideoPoster(): Entry? {
        portalNewEntries.values.firstOrNull { it.logo.isNotBlank() && !it.portalCollection && !isSeries(it) }
            ?.let { return it }
        val liveEntries = vodPlaylistItems.values.flatten().associateBy { it.url }
        for (address in vodPlaylistUrls()) {
            val ids = vodPlaylistArrivals[address].orEmpty().map { it.url }.ifEmpty {
                runCatching {
                    val array = JSONArray(prefs.getString(sourceSnapshotKey(address, "vod_new"), "[]"))
                    (0 until array.length()).map { array.optString(it) }
                }.getOrDefault(emptyList())
            }
            ids.firstNotNullOfOrNull { liveEntries[it]?.takeIf { entry -> entry.logo.isNotBlank() } }?.let { return it }
        }
        return (vodPlaylistItems.values.flatten() + vodItems).firstOrNull { it.logo.isNotBlank() && !isSeries(it) }
    }

    private fun loadVodPlaylist() {
        val addresses = vodPlaylistUrls()
        val generation = ++vodLoadGeneration
        vodPlaylistItems.keys.retainAll(addresses.toSet())
        vodPlaylistArrivals.keys.retainAll(addresses.toSet())
        if (addresses.isEmpty()) {
            showVodStatus("")
            if (current == "movies") showCatalog("movies", catalogGroup)
            return
        }
        showVodStatus("… " + tr("Загрузка плейлистов", "Playlisten werden geladen", "Loading playlists"))
        thread {
            val loaded = linkedMapOf<String, List<Entry>>()
            addresses.forEach { address ->
                runCatching {
                    val conn = URL(address).openConnection() as HttpURLConnection
                    try {
                        conn.connectTimeout = 15000
                        conn.readTimeout = 30000
                        val raw = conn.inputStream.bufferedReader().use { it.readText() }
                        require(raw.trimStart().trimStart('\uFEFF').startsWith("#EXTM3U", true))
                        parseM3u(raw, address).map { it.copy(type = "movies") }
                    } finally {
                        conn.disconnect()
                    }
                }.getOrNull()?.let { loaded[address] = it }
            }
            runOnUiThread {
                if (isDestroyed || generation != vodLoadGeneration) return@runOnUiThread
                loaded.forEach { (address, entries) ->
                    val snapshotKey = sourceSnapshotKey(address, "vod_seen")
                    val arrivalsKey = sourceSnapshotKey(address, "vod_new")
                    val previous = runCatching {
                        val array = JSONArray(prefs.getString(snapshotKey, "[]"))
                        (0 until array.length()).map { array.optString(it) }.toHashSet()
                    }.getOrDefault(emptySet())
                    val current = entries.map { it.url }.toHashSet()
                    val storedArrivals = runCatching {
                        val array = JSONArray(prefs.getString(arrivalsKey, "[]"))
                        (0 until array.length()).mapNotNull { entries.firstOrNull { entry -> entry.url == array.optString(it) } }
                    }.getOrDefault(emptyList())
                    val newcomers = if (prefs.contains(snapshotKey)) entries.filter { it.url !in previous && !isSeries(it) }
                        else emptyList()
                    val arrivals = (newcomers + storedArrivals.filter { it.url in current }).distinctBy { it.url }.take(36)
                    vodPlaylistArrivals[address] = arrivals
                    val snapshot = JSONArray(entries.map { it.url }.distinct().takeLast(8000))
                    val newSnapshot = JSONArray(arrivals.map { it.url })
                    prefs.edit().putString(snapshotKey, snapshot.toString()).putString(arrivalsKey, newSnapshot.toString()).apply()
                }
                vodPlaylistItems.putAll(loaded)
                if (loaded.isNotEmpty()) {
                    lastVodRefreshEpoch = System.currentTimeMillis()
                    prefs.edit().putLong("vod_last_refresh", lastVodRefreshEpoch).apply()
                }
                if (current == "movies" && sidePanel == null) showCatalog("movies", catalogGroup)
                else if (current == "home") showHome()
                val failures = addresses.size - loaded.size
                val icon = if (failures == 0) "✓ " else "✕ "
                showVodStatus(icon + tr("Плейлисты загружены", "Playlisten geladen", "Playlists loaded") +
                    ": ${loaded.size} / ${addresses.size}",
                    if (failures == 0) Color.rgb(109, 233, 153) else Color.rgb(255, 121, 129))
                if (failures > 0) toast(tr("Источники видеотеки недоступны", "Mediathek-Quellen nicht erreichbar",
                    "Video library sources unavailable") + ": $failures")
            }
        }
    }

    private data class VideoPortal(val endpoint: URL, val key: String)
    private var portalBack: (() -> Unit)? = null
    private var portalPlaybackBack: (() -> Unit)? = null
    private var portalGeneration = 0

    private fun parseVideoPortal(value: String): VideoPortal? {
        val match = Regex("""portal::\[key:([^\]\s]+)\](https?://\S+)""", RegexOption.IGNORE_CASE)
            .matchEntire(value.trim()) ?: return null
        val endpoint = runCatching { URL(match.groupValues[2]) }.getOrNull() ?: return null
        if (endpoint.host.isBlank() || endpoint.userInfo != null || endpoint.ref != null) return null
        return VideoPortal(endpoint, match.groupValues[1])
    }

    private fun editVideoPortal(authorized: Boolean = false) {
        if (!authorized) {
            requirePlaylistPin { editVideoPortal(true) }
            return
        }
        val field = EditText(this).apply {
            hint = "portal::[key:…]https://…/api/v1/"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("video_portal_link", ""))
        }
        val dialog = AlertDialog.Builder(this).setTitle("VPortal").setView(field)
            .setPositiveButton(tr("Сохранить", "Speichern", "Save"), null)
            .setNegativeButton(tr("Отмена", "Abbrechen", "Cancel"), null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = field.text.toString().trim()
                if (value.isNotEmpty() && parseVideoPortal(value) == null) {
                    field.error = tr("Вставь полную ссылку из кабинета", "Vollständigen Portallink einfügen", "Paste the full portal link")
                    return@setOnClickListener
                }
                prefs.edit().putString("video_portal_link", value).apply()
                dialog.dismiss()
                if (value.isNotEmpty()) openVideoPortal()
            }
        }
        dialog.show()
    }

    private fun requestVideoPortal(portal: VideoPortal, params: JSONObject): JSONObject {
        val body = JSONObject(params.toString()).apply {
            put("app", "ott-play")
            put("key", portal.key)
        }.toString().toByteArray(Charsets.UTF_8)
        val connection = portal.endpoint.openConnection() as HttpURLConnection
        connection.connectTimeout = 12000
        connection.readTimeout = 25000
        connection.instanceFollowRedirects = false
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        connection.setRequestProperty("Accept", "application/json")
        try {
            connection.outputStream.use { it.write(body) }
            if (connection.responseCode !in 200..299) throw java.io.IOException()
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { input ->
                val result = StringBuilder()
                val buffer = CharArray(4096)
                var count = input.read(buffer)
                while (count != -1) {
                    if (result.length + count > 8 * 1024 * 1024) throw java.io.IOException()
                    result.append(buffer, 0, count)
                    count = input.read(buffer)
                }
                JSONObject(result.toString())
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun syncPortalCatalog() {
        val configuredLink = prefs.getString("video_portal_link", "").orEmpty()
        if (configuredLink != arrivalsProvider) {
            portalSeenPages.clear()
            portalNewEntries.clear()
            portalSearchEntries.clear()
            arrivalsProvider = configuredLink
        }
    }

    private fun openVideoPortal() {
        syncPortalCatalog()
        val portal = parseVideoPortal(arrivalsProvider)
        if (portal == null) editVideoPortal()
        else showPortalPage(portal, JSONObject(), "VPortal") { showMediaHub() }
    }

    private var arrivalsProvider = ""
    private val portalSeenPages = mutableMapOf<String, Set<String>>()
    private val portalNewEntries = linkedMapOf<String, Entry>()

    private fun newArrivalsTitle() =
        tr("Новые поступления", "Neu hinzugefügt", "New arrivals")

    private fun isNewArrivalsCategory(title: String): Boolean {
        val name = title.lowercase(Locale.ROOT)
        return listOf("новые поступления", "новые фильмы", "новинки", "недавно добавлен", "neu hinzugefügt",
            "recently added", "new arrivals", "new movies", "neue filme").any { name.contains(it) }
    }

    private fun showPortalArrivals(back: () -> Unit, moviesOnly: Boolean = false) {
        scaffold("vportal")
        portalBack = back
        portalGeneration++
        sectionTitle(if (moviesOnly) newMoviesTitle() else newArrivalsTitle())
        val portalEntries = portalNewEntries.values.filter { !moviesOnly || (!it.portalCollection && !isSeries(it)) }
        val entries = if (moviesOnly) (portalEntries.asReversed() + vodPlaylistArrivals.values.flatten())
            .distinctBy(::videoKey).take(100) else portalEntries.asReversed()
        if (entries.isEmpty()) content.addView(label(tr(
            "После обновления открытых разделов здесь появятся новые позиции.",
            "Neue Titel erscheinen hier nach dem erneuten Laden besuchter Bereiche.",
            "New titles appear here after previously opened sections are reloaded.")))
        val posters = PosterGrid(content)
        entries.forEach { entry ->
            posters.add(entry) {
                showPosterDetails(entry) { openSavedVideo(entry) { showPortalArrivals(back, moviesOnly) } }
            }
        }
    }

    private val categoryIconPositions by lazy {
        assets.open("category-icons.txt").bufferedReader().useLines { lines ->
            lines.mapIndexed { index, key -> key to index }.toMap()
        }
    }

    private val categoryIconAtlas by lazy {
        assets.open("category-icons.png").use { android.graphics.BitmapFactory.decodeStream(it) }
    }

    private val categoryIcons = android.util.LruCache<String, Bitmap>(64)

    private fun categoryIcon(key: String): Bitmap? {
        categoryIcons.get(key)?.let { return it }
        val index = categoryIconPositions[key] ?: return null
        val atlas = categoryIconAtlas ?: return null
        val cellWidth = 96
        val cellHeight = 72
        val columns = 16
        return Bitmap.createBitmap(atlas, (index % columns) * cellWidth, (index / columns) * cellHeight,
            cellWidth, cellHeight).also { categoryIcons.put(key, it) }
    }

    private inner class PortalCategoryGrid(private val target: LinearLayout, tvColumns: Int = 5) {
        private val columns = if (isPhone()) 2 else tvColumns
        private var row: LinearLayout? = null
        private var count = 0

        fun breakRow() {
            row = null
            count = 0
        }

        fun add(title: String, icon: Int, tint: Int, symbol: String? = null, action: () -> Unit) {
            val line = row ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                target.addView(this, LinearLayout.LayoutParams(-1, -2))
                row = this
            }
            val size = ((resources.displayMetrics.widthPixels - dp(32)) / columns - dp(8))
                .coerceAtLeast(dp(54))
            val card = tile(title, "", icon, tint, action).apply {
                gravity = Gravity.CENTER
                getChildAt(childCount - 1).visibility = View.GONE
                if (symbol != null) {
                    val key = when (symbol) {
                        "ℹ" -> "news"
                        "🎭" -> "entertainment"
                        "🏆" -> "sports"
                        "♫" -> "music"
                        "🎬" -> "films"
                        "🧸" -> "kids"
                        "🌐" -> "world"
                        "🔒" -> "locked"
                        else -> if (symbol.length == 4) listOf(0, 2).joinToString("") {
                            ('a' + (Character.codePointAt(symbol, it) - 0x1F1E6)).toString()
                        } else ""
                    }
                    categoryIcon(key)?.let { image -> (getChildAt(0) as ImageView).apply {
                        clearColorFilter()
                        setImageBitmap(image)
                        layoutParams = LinearLayout.LayoutParams(dp(68), dp(46))
                    } }
                }
                (getChildAt(1) as TextView).apply {
                    textSize = 15f
                    maxLines = 2
                    gravity = Gravity.CENTER
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
            }
            line.addView(card, LinearLayout.LayoutParams(size, size).apply {
                setMargins(dp(4), dp(4), dp(4), dp(8))
            })
            if (++count == columns) breakRow()
        }
    }

    private fun showPortalPage(
        portal: VideoPortal, request: JSONObject, heading: String, page: Int = 0,
        episodeCover: String? = null, moviesOnly: Boolean = false, back: () -> Unit
    ) {
        scaffold("vportal")
        portalBack = back
        val generation = ++portalGeneration
        sectionTitle(heading)
        val status = label(tr("Загрузка каталога…", "Katalog wird geladen…", "Loading catalog…"))
        content.addView(status)
        fun streamUrl(value: String): String? = value.takeIf {
            it.startsWith("https://", true) || it.startsWith("http://", true)
        }
        fun failure() {
            status.text = tr("Не удалось открыть VPortal. Проверь ссылку и подписку.",
                "VPortal nicht erreichbar. Link und Abo prüfen.",
                "Could not open VPortal. Check the link and subscription.")
        }
        thread {
            val response = runCatching { requestVideoPortal(portal, request) }.getOrNull()
            runOnUiThread {
                if (current != "vportal" || generation != portalGeneration || isDestroyed) return@runOnUiThread
                val rows = response?.optJSONArray("items")
                if (rows == null) {
                    failure()
                    return@runOnUiThread
                }
                status.visibility = View.GONE
                val reload = { showPortalPage(portal, request, heading, page, episodeCover, moviesOnly, back) }
                val posters = PosterGrid(content)
                val categories = PortalCategoryGrid(content)
                val pageEntries = linkedMapOf<String, Entry>()
                val rootPage = request.length() == 0 && page == 0
                val providerArrivals = (0 until rows.length()).asSequence()
                    .mapNotNull { rows.optJSONObject(it) }
                    .firstOrNull { it.optString("type") == "category" &&
                        isNewArrivalsCategory(it.optString("title")) }
                    ?.optJSONObject("request")
                if (rootPage) categories.add(newArrivalsTitle(), android.R.drawable.ic_menu_recent_history,
                    Color.rgb(255, 199, 114)) {
                    if (providerArrivals != null) showPortalPage(portal, providerArrivals, newArrivalsTitle(), back = reload)
                    else showPortalArrivals(reload)
                }
                val pageSize = 36
                if (page > 0) content.addView(button(tr("Предыдущая страница", "Vorherige Seite", "Previous page")) {
                    showPortalPage(portal, request, heading, page - 1, episodeCover, moviesOnly, back)
                })
                val firstRow = page * pageSize
                val endRow = (firstRow + pageSize).coerceAtMost(rows.length())
                val episodeRows = linkedMapOf<Int, Entry>()
                fun playEpisode(index: Int) {
                    val entry = episodeRows[index] ?: return
                    val following = episodeRows.keys.firstOrNull { it > index }
                    openSavedVideo(entry, onEnded = following?.let { next -> { playEpisode(next) } }, back = reload)
                }
                for (index in 0 until rows.length()) {
                    val visible = index in firstRow until endRow
                    val row = rows.optJSONObject(index) ?: continue
                    val name = row.optString("title").ifBlank { "VPortal" }
                    val operation = row.optJSONObject("request")
                    val type = row.optString("type")
                    if (type == "category" && operation != null) {
                        if (!visible) continue
                        if (rootPage && isNewArrivalsCategory(name)) continue
                        posters.breakRow()
                        val nameKey = name.lowercase(Locale.ROOT)
                        val series = listOf("сериал", "series", "serien", "телепередач").any { nameKey.contains(it) }
                        val icon = if (series) R.drawable.ic_live_television else R.drawable.ic_cinema
                        val tint = if (series) Color.rgb(104, 198, 255) else Color.rgb(217, 155, 251)
                        categories.add(name, icon, tint) { showPortalPage(portal, operation, name, episodeCover = episodeCover, moviesOnly = moviesOnly, back = reload) }
                    } else if (type == "next" && operation != null) {
                        if (!visible) continue
                        posters.breakRow()
                        categories.breakRow()
                        content.addView(button(name) { showPortalPage(portal, operation, heading, episodeCover = episodeCover, moviesOnly = moviesOnly, back = reload) })
                    } else if (type == "stream" || type == "multistream") {
                        if (!visible && episodeCover == null) continue
                        if (visible) categories.breakRow()
                        val poster = sequenceOf("imglr", "img", "poster", "cover")
                            .mapNotNull { key -> row.optString(key).takeUnless { it == "null" } }
                            .mapNotNull { webUrl(portal.endpoint.toString(), it) }.firstOrNull().orEmpty()
                        val description = row.optString("description").takeUnless { it == "null" }.orEmpty()
                        val collection = type == "multistream" && operation != null
                        val direct = if (collection) null else streamUrl(row.optString("url"))
                        val metadata = listOf(row.optString("year"), row.optString("genre"))
                            .map(String::trim).filter { it.isNotEmpty() && it != "null" }.joinToString(" · ")
                        val entry = Entry(name, direct.orEmpty(), heading, logo = poster.ifBlank { episodeCover.orEmpty() }, type = "movies",
                            description = description, portalRequest = if (direct == null) operation?.toString().orEmpty() else "",
                            portalCollection = collection, videoMetadata = metadata)
                        val canPlay = direct != null || operation != null
                        val episode = episodeCover != null && canPlay && !collection &&
                            Regex("""(?iu)(?:серия|серии|эпизод|episode|folge|s\d+e\d+|^\s*\d+(?:\s|[.:-]|$))""").containsMatchIn(name)
                        if (episode) episodeRows[index] = entry
                        if (!visible || (moviesOnly && (collection || episode || isSeries(entry)))) continue
                        if (canPlay) pageEntries[videoKey(entry)] = entry
                        posters.add(entry, fallbackIcon = if (episodeCover != null) R.drawable.ic_cinema else 0) {
                            showPosterDetails(entry, canPlay) {
                                if (episode) playEpisode(index) else openSavedVideo(entry, back = reload)
                            }
                        }
                    }
                }
                if (pageEntries.isNotEmpty()) {
                    portalSearchEntries.putAll(pageEntries)
                    while (portalSearchEntries.size > 2000) portalSearchEntries.remove(portalSearchEntries.keys.first())
                    val pageKey = portal.endpoint.toString() + request.toString() + "#" + page
                    val previous = portalSeenPages.put(pageKey, pageEntries.keys.toSet())
                    if (previous != null) {
                        pageEntries.forEach { (key, entry) ->
                            if (key !in previous) portalNewEntries[key] = entry
                        }
                        while (portalNewEntries.size > 100) portalNewEntries.remove(portalNewEntries.keys.first())
                    }
                }
                if (endRow < rows.length()) content.addView(button(tr(
                    "Следующая страница", "Nächste Seite", "Next page")) {
                    showPortalPage(portal, request, heading, page + 1, episodeCover, moviesOnly, back)
                })
                if (content.childCount <= 2) {
                    status.visibility = View.VISIBLE
                    status.text = tr("Каталог пуст", "Katalog ist leer", "The catalog is empty")
                } else content.getChildAt(2)?.requestFocus()
            }
        }
    }

    private fun manageVodPlaylists(authorized: Boolean = false) {
        if (!authorized) {
            requirePlaylistPin { manageVodPlaylists(true) }
            return
        }
        val addresses = vodPlaylistUrls()
        val titles = addresses.mapIndexed { index, url ->
            importedPlaylistName(url) ?: "${index + 1}. M3U"
        }
        AlertDialog.Builder(this)
            .setTitle(tr("Плейлисты видеотеки", "Mediathek-Playlisten", "Video library playlists"))
            .setItems(titles.toTypedArray()) { _, index -> editVodUrl(addresses[index]) }
            .setPositiveButton(tr("Добавить M3U", "M3U hinzufügen", "Add M3U")) { _, _ -> editVodUrl() }
            .setNegativeButton(tr("Закрыть", "Schließen", "Close"), null)
            .show()
    }

    private fun editVodUrl(original: String? = null) {
        val privateTitle = original?.let(::importedPlaylistName)
        if (original != null && privateTitle != null) {
            AlertDialog.Builder(this).setTitle(privateTitle)
                .setNeutralButton(tr("Удалить", "Entfernen", "Remove")) { _, _ ->
                    saveVodPlaylistUrls(vodPlaylistUrls().filterNot { it == original })
                }
                .setNegativeButton(tr("Закрыть", "Schließen", "Close"), null).show()
            return
        }
        val field = EditText(this).apply {
            hint = "https://..."
            setSingleLine(true)
            setText(original.orEmpty())
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(tr("Ссылка видеотеки M3U", "Mediathek-M3U-URL", "Video library M3U URL"))
            .setView(field)
            .setPositiveButton(tr("Сохранить", "Speichern", "Save"), null)
            .setNegativeButton(tr("Отмена", "Abbrechen", "Cancel"), null)
        if (original != null) {
            builder.setNeutralButton(tr("Удалить", "Entfernen", "Remove")) { _, _ ->
                saveVodPlaylistUrls(vodPlaylistUrls().filterNot { it == original })
            }
        }
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val url = field.text.toString().trim()
                val valid = runCatching { URL(url).let { it.protocol in setOf("http", "https") &&
                    it.host.isNotBlank() } }.getOrDefault(false)
                if (!valid) {
                    field.error = tr("Нужен адрес HTTP(S)", "HTTP(S)-Adresse erforderlich", "HTTP(S) address required")
                } else {
                    val currentUrls = vodPlaylistUrls()
                    saveVodPlaylistUrls(if (original == null) currentUrls + url
                        else currentUrls.map { if (it == original) url else it })
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun settingToggle(title: String, key: String, default: Boolean, onChanged: (Boolean) -> Unit = {}): Button {
        fun caption() = (if (prefs.getBoolean(key, default)) "✓ " else "□ ") + title
        val toggle = button(caption()) {}
        toggle.setOnClickListener {
            val value = !prefs.getBoolean(key, default)
            prefs.edit().putBoolean(key, value).apply()
            toggle.text = caption()
            onChanged(value)
        }
        return toggle
    }

    private fun settingChoice(title: String, key: String, default: Int, options: List<Pair<Int, String>>, section: String = "epg"): Button {
        val currentValue = prefs.getInt(key, default)
        val currentLabel = options.firstOrNull { it.first == currentValue }?.second.orEmpty()
        return button("$title\n$currentLabel") {
            val labels = options.map { it.second }.toTypedArray()
            val selected = options.indexOfFirst { it.first == currentValue }.coerceAtLeast(0)
            AlertDialog.Builder(this).setTitle(title)
                .setSingleChoiceItems(labels, selected) { dialog, index ->
                    options.getOrNull(index)?.let { (value, _) -> prefs.edit().putInt(key, value).apply() }
                    dialog.dismiss()
                    if (current in setOf("settings", "settings_section")) {
                        showSettings(section, authorized = section in setOf("tv_playlists", "vod_playlists", "stalker"))
                        if (key == "buffer_size_seconds") {
                            AlertDialog.Builder(this)
                                .setTitle(tr("Буфер сохранён", "Puffer gespeichert", "Buffer saved"))
                                .setMessage(tr("Настройка применяется после перезапуска приложения. При 2–5 минутах может возрасти расход памяти, а запуск видео — занять дольше. Фактический запас зависит от битрейта потока и памяти устройства.",
                                    "Die Einstellung gilt nach einem App-Neustart. Bei 2–5 Minuten kann der Speicherbedarf steigen und der Videostart länger dauern. Der tatsächliche Puffer hängt von Bitrate und Gerätespeicher ab.",
                                    "This setting takes effect after restarting the app. A 2–5 minute buffer can use more memory and delay startup. Actual buffering depends on stream bitrate and device memory."))
                                .setPositiveButton(tr("Перезапустить сейчас", "Jetzt neu starten", "Restart now")) { _, _ -> recreate() }
                                .setNegativeButton(tr("Позже", "Später", "Later"), null).show()
                        }
                    }
                }.setNegativeButton(tr("Отмена", "Abbrechen", "Cancel"), null).show()
        }
    }

    private fun selectCatchupFallback() {
        val modes = listOf("auto", "append", "flussonic", "xc")
        val labels = arrayOf(
            tr("Авто: только по данным плейлиста", "Auto: nur nach Playlist-Angaben", "Auto: use playlist metadata"),
            tr("Edem / SIPTV (utc/lutc)", "Edem / SIPTV (utc/lutc)", "Edem / SIPTV (utc/lutc)"),
            "Flussonic", "Xtream Codes"
        )
        AlertDialog.Builder(this)
            .setTitle(tr("Формат архива по умолчанию", "Catch-up-Format", "Catch-up format"))
            .setItems(labels) { _, which ->
                val mode = modes[which]
                prefs.edit().putString("m3u_catchup_fallback", mode).apply()
                if (mode == "auto") {
                    if (activeList in lists.indices) loadPlaylist(activeList)
                    return@setItems
                }
                val dayOptions = arrayOf("4", "7", "14")
                AlertDialog.Builder(this)
                    .setTitle(tr("Длина архива по умолчанию (дней)", "Standard-Archivdauer (Tage)", "Default archive window (days)"))
                    .setItems(dayOptions) { _, dayIndex ->
                        prefs.edit().putInt("m3u_catchup_days", dayOptions[dayIndex].toInt()).apply()
                        if (activeList in lists.indices) loadPlaylist(activeList)
                    }
                    .show()
            }
            .show()
    }

    private var lastSettingsItem: String? = null
    private fun showSettings(section: String? = null, authorized: Boolean = false) {
        if (!authorized && section in setOf("tv_playlists", "vod_playlists", "stalker")) {
            requirePlaylistPin { showSettings(section, authorized = true) }
            return
        }
        val sections = linkedMapOf(
            "tv_playlists" to tr("Плейлисты телевидения", "TV-Playlisten", "TV playlists"),
            "vod_playlists" to tr("Плейлисты видеотеки", "Mediathek-Playlisten", "Video library playlists"),
            "stalker" to tr("Stalker / Ministra", "Stalker / Ministra", "Stalker / Ministra"),
            "epg" to tr("Телепрограмма (EPG)", "TV-Programm (EPG)", "TV guide (EPG)"),
            "modules" to tr("Включённые модули", "Aktive Module", "Enabled modules"),
            "system" to tr("Системные настройки", "Systemeinstellungen", "System settings"),
            "colors" to tr("Цветовые темы", "Farbschemata", "Color themes"),
            "player" to tr("Плеер", "Wiedergabe", "Player"),
            "parental" to tr("Родительский контроль", "Kindersicherung", "Parental controls"),
            "tv" to tr("Телеканалы", "Fernsehsender", "TV channels"),
            "appearance" to tr("Оформление", "Darstellung", "Appearance"),
            "language" to tr("Язык", "Sprache", "Language"),
            "updates" to tr("Обновление приложения", "App-Update", "App update"),
            "remote" to tr("Телефон и QR-код", "Handy und QR-Code", "Phone and QR code"),
            "radio" to tr("Радио и музыка", "Radio und Musik", "Radio and music")
        )
        val host = findViewById<FrameLayout>(android.R.id.content)
        val heading = sections[section] ?: tr("\u041d\u0430\u0441\u0442\u0440\u043e\u0439\u043a\u0438", "Einstellungen", "Settings")
        openPanel(false, heading, host) { content ->
        when (section) {
            "parental" -> {
                content.addView(button(tr("Задать или сменить PIN для взрослых каналов", "PIN für Erwachsenen-Kanäle ändern",
                    "Set or change adult-channel PIN")) { changeParentalPin() })
                content.addView(settingToggle(tr("Запрашивать PIN для взрослых каналов", "PIN für Erwachsenen-Kanäle verlangen",
                    "Require PIN for adult channels"), "require_pin_for_adult_channels", true))
                content.addView(settingToggle(tr("Запрашивать PIN при настройке источников", "PIN für Quellenverwaltung verlangen",
                    "Require PIN to manage sources"), "require_pin_for_sources", true))
                content.addView(label(tr("PIN взрослых каналов и PIN настройки источников переключаются отдельно.",
                    "Die PIN für Erwachsenen-Kanäle und Quellenverwaltung werden getrennt gesteuert.",
                    "Adult-channel PIN and source-management PIN are controlled separately."), 13f))
            }
            "updates" -> {
                content.addView(label(tr("Версия", "Version", "Version") + " " +
                    packageManager.getPackageInfo(packageName, 0).versionName, 16f))
                content.addView(button(tr("Проверить обновление", "Nach Updates suchen", "Check for updates")) {
                    checkForAppUpdate(true)
                })
            }
            "tv_playlists" -> {
                content.addView(button(tr("Управление ТВ-плейлистами M3U", "TV-Playlisten verwalten (M3U)", "Manage TV playlists (M3U)")) {
                    showPlaylists(true)
                })
                content.addView(button("Xtream Codes") { editXtream(true) })
                content.addView(button(tr("Обновить телеканалы", "Sender aktualisieren", "Refresh channels")) {
                    if (activeList in lists.indices) loadPlaylist(activeList)
                    loadXtream()
                    loadEpg()
                })
            }
            "vod_playlists" -> {
                content.addView(settingChoice(tr("Плеер видеотеки", "Mediathek-Player", "Video library player"),
                    "vod_player_type", 0, listOf(
                        0 to tr("Встроенный", "Integriert", "Built-in player"),
                        1 to tr("Внешний плеер", "Externer Player", "External player")
                    ), section = "vod_playlists"))
                content.addView(settingChoice(tr("Обновлять видеотеку", "Mediathek aktualisieren", "Refresh video library"),
                    "vod_refresh_hours", 24, listOf(1, 6, 12, 24, 48, 168).map { hours ->
                        hours to if (hours == 168) tr("Раз в неделю", "Wöchentlich", "Weekly")
                            else tr("Каждые $hours ч", "Alle $hours Std.", "Every $hours hours")
                    }, section = "vod_playlists"))
                content.addView(button(tr("Найти UPnP / DLNA-серверы", "UPnP-/DLNA-Server suchen", "Scan for UPnP / DLNA servers")) {
                    scanDlnaServers()
                })
                content.addView(button(tr("VPortal с телефона · QR", "VPortal per Handy · QR", "VPortal from phone · QR")) {
                    showRemote("vportal", authorized = true)
                })
                content.addView(button("VPortal") { editVideoPortal(true) })
                content.addView(button(tr("Добавить или изменить ссылку M3U", "M3U-Link hinzufügen oder ändern", "Add or edit M3U URL")) {
                    manageVodPlaylists(true)
                })
                content.addView(button(tr("Обновить видеотеку", "Mediathek aktualisieren", "Refresh video library")) {
                    loadVodPlaylist()
                    loadXtream()
                })
                val status = label(vodStatusText, 14f)
                status.setTextColor(vodStatusColor)
                vodStatusView = status
                content.addView(status)
            }
            "stalker" -> {
                val providerName = EditText(this).apply {
                    hint = tr("Имя провайдера или портала", "Name des Anbieters oder Portals", "Provider or portal name")
                    setSingleLine(true); setText(prefs.getString("stalker_name", ""))
                }
                val portal = EditText(this).apply {
                    hint = tr("Адрес портала (URL)", "Portal-Adresse (URL)", "Portal URL")
                    setSingleLine(true); setText(prefs.getString("stalker_url", ""))
                }
                val login = EditText(this).apply {
                    hint = tr("Логин (если выдан провайдером)", "Login (falls vom Anbieter vergeben)", "Login (if supplied by provider)")
                    setSingleLine(true); setText(prefs.getString("stalker_login", ""))
                }
                val password = EditText(this).apply {
                    hint = tr("Пароль (если выдан провайдером)", "Passwort (falls vom Anbieter vergeben)", "Password (if supplied by provider)")
                    setSingleLine(true); inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                    setText(prefs.getString("stalker_password", ""))
                }
                val mac = EditText(this).apply {
                    hint = "MAC · 00:1A:79:00:00:00"
                    setSingleLine(true); setText(prefs.getString("stalker_mac", ""))
                }
                content.addView(providerName); content.addView(portal); content.addView(login); content.addView(password); content.addView(mac)
                content.addView(label(tr("Способ авторизации", "Authentifizierungsmethode", "Authorization method"), 14f, true))
                val authModes = listOf(
                    tr("Зарегистрированный MAC", "Registrierte MAC-Adresse", "Registered MAC address"),
                    tr("Логин/пароль · первая активация", "Login/Passwort · erste Aktivierung", "Login/password · first activation"),
                    tr("Логин/пароль · при каждом входе", "Login/Passwort · bei jedem Login", "Login/password · every connection")
                )
                val authModeSpinner = Spinner(this).apply {
                    adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, authModes)
                    setSelection(prefs.getInt("stalker_auth_mode", 0).coerceIn(0, authModes.lastIndex))
                }
                authModeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                        if (prefs.getInt("stalker_auth_mode", 0) != position) {
                            prefs.edit().putInt("stalker_auth_mode", position).apply()
                            stalkerClient = null
                        }
                    }
                }
                content.addView(authModeSpinner)
                val active = TextView(this).apply {
                    textSize = 17f; setPadding(dp(12), dp(10), dp(12), dp(10))
                }
                fun showStatus(enabled: Boolean, message: String) {
                    active.text = (if (enabled) "● " else "● ") + message
                    active.setTextColor(if (enabled) Color.rgb(64, 224, 125) else Color.rgb(255, 105, 105))
                }
                showStatus(prefs.getBoolean("stalker_active", false), if (prefs.getBoolean("stalker_active", false))
                    prefs.getString("stalker_name", "").orEmpty().takeIf { it.isNotBlank() }?.let {
                        tr("$it · портал авторизован", "$it · Portal autorisiert", "$it · portal authorized")
                    } ?: tr("Портал авторизован", "Portal autorisiert", "Portal authorized")
                    else tr("Не активирован", "Nicht aktiviert", "Not activated"))
                content.addView(active)
                listOf(portal, login, password, mac).forEach { field ->
                    field.addTextChangedListener(object : android.text.TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                            if (prefs.getBoolean("stalker_active", false)) {
                                prefs.edit().putBoolean("stalker_active", false).apply()
                                showStatus(false, tr("Данные изменены · проверь портал заново", "Daten geändert · Portal erneut prüfen",
                                    "Details changed · verify portal again"))
                            }
                        }
                        override fun afterTextChanged(s: android.text.Editable?) = Unit
                    })
                }
                content.addView(label(tr("Проверка использует MAC портала. Логин и пароль могут проверяться только тем способом, который поддерживает ваш провайдер.",
                    "Die Prüfung verwendet die Portal-MAC. Login und Passwort können nur geprüft werden, wenn der Anbieter das unterstützt.",
                    "Verification uses the portal MAC. Login and password can only be checked if your provider supports them."), 13f))
                content.addView(label(tr("При HTTP передаются без шифрования. Используй HTTPS, если провайдер его предоставляет.",
                    "Bei HTTP werden die Daten unverschlüsselt übertragen. Verwende HTTPS, falls der Anbieter es unterstützt.",
                    "HTTP sends credentials without encryption. Use HTTPS if your provider offers it."), 12f))
                content.addView(button(tr("Проверить и активировать", "Prüfen und aktivieren", "Verify and activate")) {
                    val address = portal.text.toString().trim()
                    val macValue = mac.text.toString().trim()
                    if (address.isBlank() || macValue.isBlank()) {
                        showStatus(false, tr("Нужны URL портала и MAC", "Portal-URL und MAC erforderlich", "Portal URL and MAC are required"))
                        return@button
                    }
                    prefs.edit().putString("stalker_name", providerName.text.toString().trim())
                        .putString("stalker_url", address).putString("stalker_login", login.text.toString().trim())
                        .putString("stalker_password", password.text.toString()).putString("stalker_mac", macValue)
                        .putBoolean("stalker_active", false).apply()
                    stalkerClient = null
                    showStatus(false, tr("Проверяю портал…", "Portal wird geprüft…", "Checking portal…"))
                    val client = makeStalkerClient()!!
                    val screen = base
                    thread {
                        val result = runCatching { client.verify() }
                        runOnUiThread {
                            if (isDestroyed || base !== screen) return@runOnUiThread
                            val verified = result.isSuccess
                            prefs.edit().putBoolean("stalker_active", verified).apply()
                            val providerLabel = providerName.text.toString().trim().takeIf { it.isNotBlank() }
                                ?.let { "$it · " }.orEmpty()
                            showStatus(verified, if (verified) {
                                providerLabel + tr("Активен · профиль получен", "Aktiv · Profil empfangen", "Active · profile received")
                            }
                                else tr("Ошибка: ${result.exceptionOrNull()?.message.orEmpty()}",
                                    "Fehler: ${result.exceptionOrNull()?.message.orEmpty()}",
                                    "Error: ${result.exceptionOrNull()?.message.orEmpty()}"))
                            if (verified) stalkerClient = client
                        }
                    }
                })
                content.addView(button(tr("Открыть категории и каналы", "Kategorien und Sender öffnen", "Open categories and channels")) {
                    showStalkerCategories()
                })
            }
            "epg" -> {
                content.addView(settingChoice(tr("Частота обновления EPG", "EPG-Aktualisierung", "EPG refresh interval"),
                    "epg_refresh_hours", 2, listOf(1, 2, 4, 6, 12, 24).map { hours ->
                        hours to tr("Каждые $hours ч", "Alle $hours Std.", "Every $hours hours")
                    }))
                content.addView(button(tr("Формат архива по умолчанию", "Catch-up-Format", "Catch-up format")) {
                    selectCatchupFallback()
                })
                content.addView(button(tr("Настроить адрес XMLTV", "XMLTV-Adresse einstellen", "Set XMLTV URL")) { editEpg() })
                content.addView(button(tr("Добавить или заменить адреса EPG", "EPG-Adressen hinzufügen oder ändern",
                    "Add or edit EPG addresses")) { manageCustomEpgSources() })
                content.addView(button(tr("Источники EPG · выбрать несколько", "EPG-Quellen · mehrere wählen", "EPG sources · select multiple")) {
                    selectPlaylistGuide()
                })
                content.addView(button(tr("Обновить телепрограмму XMLTV", "XMLTV-Programm aktualisieren", "Refresh XMLTV guide")) { loadEpg(downloadFull = true, forceRefresh = true) })
                content.addView(settingChoice(tr("Максимум дней телепрограммы и архива", "Maximale Tage für Programm und Archiv",
                    "Maximum guide and archive days"), "epg_history_days", 14,
                    listOf(1, 3, 7, 14, 30).map { days -> days to "$days" }))
                content.addView(settingChoice(tr("Сопоставление каналов с EPG", "Senderzuordnung zum EPG", "Match channels to EPG"),
                    "epg_match_mode", 2, listOf(
                        0 to "tvg-id",
                        1 to "tvg-id + tvg-name",
                        2 to tr("ID, название и варианты написания", "ID, Name und Schreibweisen", "ID, name and aliases")
                    )))
                content.addView(settingToggle(tr("Нормализовать названия каналов", "Sendernamen normalisieren", "Normalize channel names"),
                    "epg_normalize_names", true))
                content.addView(settingToggle(tr("Обновлять EPG при появлении новых каналов", "EPG bei neuen Sendern aktualisieren",
                    "Refresh EPG when new channels appear"), "refresh_epg_on_channels", true))
                content.addView(settingToggle(tr("Параллельно загружать несколько EPG", "Mehrere EPG parallel laden",
                    "Download multiple EPG feeds in parallel"), "epg_parallel", true))
                content.addView(settingToggle(tr("Показывать «Нет данных», если программа отсутствует", "«Keine Daten» anzeigen, wenn Programm fehlt",
                    "Show a no-data placeholder when programme is missing"), "epg_empty_placeholder", true))
                content.addView(button(tr("Очистить загруженные данные EPG", "EPG-Daten löschen", "Clear downloaded EPG data")) {
                    AlertDialog.Builder(this).setTitle(tr("Очистить телегид?", "TV-Programm löschen?", "Clear the guide?"))
                        .setMessage(tr("Сохранённые XMLTV-данные будут удалены. Их можно скачать заново.",
                            "Gespeicherte XMLTV-Daten werden gelöscht und können erneut geladen werden.",
                            "Cached XMLTV data will be removed and can be downloaded again."))
                        .setPositiveButton(tr("Очистить", "Löschen", "Clear")) { _, _ -> clearEpgData(); showSettings("epg") }
                        .setNegativeButton(tr("Отмена", "Abbrechen", "Cancel"), null).show()
                })
                val status = label(epgStatusText, 14f)
                epgStatusView = status
                status.setTextColor(epgStatusColor)
                content.addView(status)
            }
            "player" -> {
                content.addView(settingToggle(tr("Показывать часы во время просмотра", "Uhr beim Abspielen anzeigen", "Show clock while watching"),
                    "show_clock", true))
                content.addView(settingChoice(tr("Стиль экранной информации", "Stil der Bildschirminformation", "On-screen information style"),
                    "player_overlay_style", 2, listOf(
                        1 to tr("Сверху", "Oben", "Top"),
                        2 to tr("Снизу", "Unten", "Bottom"),
                        3 to tr("Компактно по центру", "Kompakt in der Mitte", "Compact center")
                    ), section = "player"))
                content.addView(settingToggle(tr("Показывать подсказки меню плеера", "Player-Menü-Hinweise anzeigen", "Show player menu hints"),
                    "player_show_menu_hints", true))
                content.addView(settingToggle(tr("Показывать статус потока и переподключения", "Streamstatus und Neuverbindung anzeigen",
                    "Show stream and reconnect status"), "player_show_stream_status", true))
                content.addView(settingToggle(tr("Показывать маркер архивной передачи", "Archivmarker anzeigen", "Show archive marker"),
                    "player_show_archive_marker", true))
                content.addView(settingToggle(tr("Чёрный фон при переключении канала", "Schwarzer Bildschirm beim Senderwechsel",
                    "Black screen while changing channels"), "player_black_on_zap", true))
                content.addView(settingChoice(tr("Размер буфера", "Puffergröße", "Buffer size"), "buffer_size_seconds", 30,
                    listOf(15, 30, 60, 75, 120, 300).map { seconds ->
                        seconds to if (seconds == 120 || seconds == 300) "${seconds / 60} ${tr("мин", "Min.", "min")}" else "$seconds ${tr("секунд", "Sekunden", "seconds")}" 
                    }, section = "player"))
                content.addView(label(tr("Общий буфер для IPTV и видеотеки. Максимум — выбранное время; фактический объём зависит от битрейта и памяти устройства. После изменения перезапусти приложение.",
                    "Gemeinsamer Puffer für IPTV und Mediathek. Die Auswahl ist die maximale Dauer; der tatsächliche Umfang hängt von Bitrate und Gerätespeicher ab. App danach neu starten.",
                    "Shared buffer for IPTV and video library. The selected time is the maximum; actual buffering depends on bitrate and device memory. Restart the app after changing it."), 13f))
                val scales = listOf(
                    "fit" to tr("Оригинальные пропорции", "Originalformat", "Original aspect ratio"),
                    "zoom" to tr("На весь экран с обрезкой", "Bildschirm füllen (Zuschnitt)", "Fill screen (crop)"),
                    "fill" to tr("Растянуть на экран", "Bild strecken", "Stretch to fit")
                )
                val selected = prefs.getString("video_scale", "fit")
                scales.forEach { (id, title) ->
                    content.addView(button((if (selected == id) "✓ " else "○ ") + title) {
                        prefs.edit().putString("video_scale", id).apply()
                        playerView?.resizeMode = when (id) {
                            "zoom" -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                            "fill" -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
                            else -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }
                        showSettings("player")
                    })
                }
            }
            "modules" -> {
                val modules = listOf(
                    "live" to tr("Прямой эфир", "Live-TV", "Live TV"),
                    "guide" to tr("Телегид", "TV-Programm", "TV guide"),
                    "archive" to tr("Архив передач", "Sendungsarchiv", "Programme archive"),
                    "vod" to tr("Медиатека", "Mediathek", "Video library"),
                    "online" to tr("Онлайн-кинотеатр", "Online-Kino", "Online cinema"),
                    "stalker" to tr("Stalker / Ministra", "Stalker / Ministra", "Stalker / Ministra"),
                    "radio" to tr("Радио и музыка", "Radio und Musik", "Radio and music"),
                    "music" to tr("Музыкальные станции", "Musikstationen", "Music stations"),
                    "youtube" to "YouTube",
                    "fireplace" to tr("Заставка «Камин»", "Kamin-Bildschirmschoner", "Fireplace screensaver"),
                    "studio" to tr("Студийная сетка каналов", "Studio-Senderraster", "Studio channel grid"),
                    "pip" to tr("Картинка-в-картинке", "Bild-in-Bild", "Picture-in-picture"),
                    "recent_started" to tr("Недавно начавшиеся передачи", "Kürzlich gestartete Sendungen", "Recently started programmes")
                )
                modules.forEach { (key, title) ->
                    content.addView(settingToggle(title, "module_$key", key != "studio", onChanged = {
                        if (current == "settings_section") showSettings("modules")
                    }))
                }
            }
            "system" -> {
                content.addView(settingToggle(tr("Запускать приложение при включении устройства", "App beim Gerätestart öffnen",
                    "Launch app when device starts"), "system_start_on_boot", false))
                content.addView(settingToggle(tr("Закрывать приложение кнопкой Назад с главного экрана", "App mit Zurück auf dem Startbildschirm schließen",
                    "Close app with Back from home"), "system_back_exits", true))
                content.addView(settingToggle(tr("Показывать управление в системной MediaSession", "Steuerung in Android MediaSession anzeigen",
                    "Expose playback controls through MediaSession"), "system_media_session", true) { setMediaSessionEnabled(it) })
                content.addView(button(tr("Часовой пояс и дата устройства", "Zeitzone und Gerätedatum", "Device time zone and date")) {
                    openSystemIntent(android.provider.Settings.ACTION_DATE_SETTINGS)
                })
                content.addView(button(tr("Сеть и Wi-Fi устройства", "Gerätenetzwerk und WLAN", "Device network and Wi-Fi")) {
                    openSystemIntent(android.provider.Settings.ACTION_WIFI_SETTINGS)
                })
                content.addView(button(tr("Удалить настройки и восстановить значения по умолчанию", "Einstellungen löschen und Standardwerte wiederherstellen",
                    "Clear settings and restore defaults")) { resetOnlineSettings() })
            }
            "radio" -> {
                content.addView(button(tr("Слушать радио Германии", "Deutsches Radio hören", "Listen to German radio")) { startRadio("DE") })
                content.addView(button(tr("Слушать радио России", "Russisches Radio hören", "Listen to Russian radio")) { startRadio("RU") })
                content.addView(button(tr("Открыть YouTube", "YouTube öffnen", "Open YouTube")) { openYouTube() })
                content.addView(button(tr("Бесплатные музыкальные станции", "Kostenlose Musikstationen", "Free music stations")) { showMusicHub() })
                content.addView(label(tr("Каталог бесплатный; отдельные радиостанции могут менять адрес или ограничивать доступ.",
                    "Das Verzeichnis ist kostenlos; einzelne Sender können Adressen ändern oder den Zugriff beschränken.",
                    "The directory is free; individual stations may change URLs or restrict access."), 13f))
            }
            "tv" -> {
            content.addView(button(tr("Вид каналов", "Senderansicht", "Channel view")) {
                val labels = arrayOf(
                    tr("Обычный список", "Normale Liste", "Standard list"),
                    tr("Список с крупными логотипами", "Liste mit großen Logos", "List with large logos"),
                    tr("Список с мелкими логотипами", "Liste mit kleinen Logos", "List with small logos"),
                    tr("Сетка каналов", "Senderraster", "Channel grid"),
                    tr("Крупная сетка: только логотипы", "Großes Raster: nur Logos", "Large grid: logos only")
                )
                AlertDialog.Builder(this)
                    .setTitle(tr("Вид каналов", "Senderansicht", "Channel view"))
                    .setSingleChoiceItems(labels, prefs.getInt("channel_layout", 3)) { dialog, index ->
                        prefs.edit().putInt("channel_layout", index).apply()
                        dialog.dismiss()
                        toast(tr("Вид сохранён", "Ansicht gespeichert", "Layout saved"))
                    }.setNegativeButton(tr("Отмена", "Abbrechen", "Cancel"), null).show()
            })
            content.addView(settingToggle(tr("Показывать текущую передачу", "Aktuelle Sendung anzeigen",
                "Show current programme"), "channel_show_programme", true))
            content.addView(settingToggle(tr("Сортировать каналы по алфавиту", "Sender alphabetisch sortieren",
                "Sort channels alphabetically"), "sort_channels_alpha", false))
            }
            "colors" -> {
                val themes = listOf(
                    "blue" to tr("Синяя", "Blau", "Blue"),
                    "red" to tr("Красная", "Rot", "Red"),
                    "green" to tr("Зелёная", "Grün", "Green"),
                    "purple" to tr("Фиолетовая", "Lila", "Purple"),
                    "gold" to tr("Золотая", "Gold", "Gold"),
                    "sky" to tr("Голубая", "Hellblau", "Sky blue")
                )
                val selected = prefs.getString("theme", "blue")
                themes.forEach { (id, title) ->
                    content.addView(button((if (selected == id) "✓ " else "") + title) {
                        prefs.edit().putString("theme", id).apply()
                        showSettings(section)
                    })
                }
            }
            "appearance" -> {
                content.addView(settingToggle(tr("Логотипы и постеры", "Logos und Poster", "Logos and posters"),
                    "show_artwork", true))
                content.addView(settingToggle(tr("Часы", "Uhr", "Clock"), "show_clock", true))
                content.addView(settingToggle(tr("Дата и день недели", "Datum und Wochentag", "Date and weekday"),
                    "show_date", true))
            }
            "language" -> {
            val languages = listOf(
                "Русский" to "ru",
                "Deutsch" to "de",
                "English" to "en"
            )

            languages.forEach { (name, code) ->
                content.addView(
                    button(
                        (if (lang == code) "✓ " else "") + name
                    ) {
                        lang = code
                        prefs.edit()
                            .putString("language", code)
                            .apply()
                        showSettings(section)
                    }
                )
            }

            }
            else -> sections.forEach { (id, title) ->
                content.addView(button(title) {
                    lastSettingsItem = id
                    if (id == "remote") showRemote() else showSettings(id)
                })
            }
        }
        val selected = if (section == null) sections.keys.toList().indexOf(lastSettingsItem.orEmpty()).coerceAtLeast(0) else 0
            content.getChildAt(selected)?.let { item -> item.post { item.requestFocus() } }
        }
        if (section != null) sidePanelBack = { showSettings() }
    }

    private fun showSources(authorized: Boolean = false) {
        if (!authorized) {
            requirePlaylistPin { showSources(true) }
            return
        }
        val sources = listOf(
            tr("ТВ-плейлисты M3U", "TV-Playlisten (M3U)", "TV playlists (M3U)") to { showPlaylists(true) },
            "Xtream Codes" to { editXtream(true) },
            tr("Телепрограмма XMLTV", "TV-Programm (XMLTV)", "TV guide (XMLTV)") to { editEpg() },
            tr("Плейлисты видеотеки", "Mediathek-Playlisten", "Video library playlists") to { manageVodPlaylists(true) },
            "VPortal" to { editVideoPortal(true) },
            tr("Настройка через интернет", "Über Internet einrichten", "Set up over internet") to { showInternetPairing(true) }
        )
        AlertDialog.Builder(this)
            .setTitle(tr("Источники", "Quellen", "Sources"))
            .setItems(sources.map { it.first }.toTypedArray()) { _, index -> sources[index].second() }
            .setNeutralButton(tr("С телефона · QR", "Per Handy · QR", "From phone · QR")) { _, _ -> showRemote(authorized = true) }
            .setNegativeButton(tr("Закрыть", "Schließen", "Close"), null)
            .show()
    }

    private fun editXtream(authorized: Boolean = false) {
        if (!authorized) {
            requirePlaylistPin { editXtream(true) }
            return
        }
        val wrapper = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        fun field(hint: String, key: String, secret: Boolean = false): EditText = EditText(this).apply {
            this.hint = hint
            setSingleLine(true)
            setText(prefs.getString(key, ""))
            if (secret) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            wrapper.addView(this)
        }
        val server = field(tr("Сервер https://...", "Server https://...", "Server https://..."), "xtream_server")
        val user = field(tr("Логин", "Benutzername", "Username"), "xtream_user")
        val pass = field(tr("Пароль", "Passwort", "Password"), "xtream_pass", true)
        AlertDialog.Builder(this).setTitle("Xtream Codes").setView(wrapper)
            .setPositiveButton(tr("Сохранить", "Speichern", "Save")) { _, _ ->
                val host = server.text.toString().trim().trimEnd('/')
                if (host.isNotBlank() && !host.startsWith("http://") && !host.startsWith("https://")) {
                    toast(tr("Нужен адрес HTTP(S)", "HTTP(S)-Adresse erforderlich", "HTTP(S) address required"))
                } else {
                    prefs.edit().putString("xtream_server", host).putString("xtream_user", user.text.toString().trim())
                        .putString("xtream_pass", pass.text.toString()).apply()
                    if (host.isNotBlank()) loadXtream()
                }
            }.setNegativeButton(tr("Отмена", "Abbrechen", "Cancel"), null).show()
    }

    private fun loadXtream() {
        val server = prefs.getString("xtream_server", "").orEmpty().trimEnd('/')
        val user = prefs.getString("xtream_user", "").orEmpty()
        val pass = prefs.getString("xtream_pass", "").orEmpty()
        if (server.isBlank() || user.isBlank() || pass.isBlank()) return
        thread {
            try {
                fun encode(s: String) = URLEncoder.encode(s, "UTF-8")
                val query = "username=${encode(user)}&password=${encode(pass)}"
                fun fetch(action: String): JSONArray {
                    val url = URL("$server/player_api.php?$query&action=$action")
                    val c = url.openConnection() as HttpURLConnection
                    c.connectTimeout = 15000; c.readTimeout = 30000
                    try { return JSONArray(c.inputStream.bufferedReader().use { it.readText() }) }
                    finally { c.disconnect() }
                }
                val liveGroups = mutableMapOf<String, String>()
                val vodGroups = mutableMapOf<String, String>()
                fun categories(action: String, target: MutableMap<String, String>) {
                    val array = fetch(action)
                    for (i in 0 until array.length()) {
                        val obj = array.getJSONObject(i)
                        target[obj.optString("category_id")] = obj.optString("category_name", "Other")
                    }
                }
                categories("get_live_categories", liveGroups)
                categories("get_vod_categories", vodGroups)
                val live = mutableListOf<Entry>()
                val streams = fetch("get_live_streams")
                for (i in 0 until streams.length()) {
                    val o = streams.getJSONObject(i)
                    val id = o.optString("stream_id")
                    if (id.isNotBlank()) live.add(Entry(o.optString("name", "TV"),
                        "$server/live/${encode(user)}/${encode(pass)}/$id.ts",
                        liveGroups[o.optString("category_id")] ?: "Other", o.optString("stream_icon"), "tv",
                        o.optString("epg_channel_id"),
                        if (o.optInt("tv_archive") == 1) o.optInt("tv_archive_duration") else 0,
                        "$server/timeshift/${encode(user)}/${encode(pass)}", id))
                }
                val films = mutableListOf<Entry>()
                val vod = fetch("get_vod_streams")
                for (i in 0 until vod.length()) {
                    val o = vod.getJSONObject(i)
                    val id = o.optString("stream_id")
                    if (id.isNotBlank()) films.add(Entry(o.optString("name", "Movie"),
                        "$server/movie/${encode(user)}/${encode(pass)}/$id.${o.optString("container_extension", "mp4")}",
                        vodGroups[o.optString("category_id")] ?: "Other", o.optString("stream_icon"), "movies",
                        description = o.optString("plot").takeUnless { it.isBlank() || it == "null" }
                            ?: o.optString("description").takeUnless { it == "null" }.orEmpty()))
                }
                runOnUiThread {
                    // Xtream is an additional source; do not silently overwrite M3U content.
                    val knownLiveUrls = items.filter { it.type == "tv" }.map { it.url }.toHashSet()
                    val foundNewChannels = live.any { it.url !in knownLiveUrls }
                    items.removeAll { it.url.startsWith("$server/live/") }
                    vodItems.removeAll { it.url.startsWith("$server/movie/") }
                    items.addAll(live); vodItems.addAll(films)
                    if (foundNewChannels && prefs.getBoolean("refresh_epg_on_channels", true)) loadEpg()
                    toast("Xtream: ${live.size} TV / ${films.size} VOD")
                    if (current == "home") showHome()
                }
            } catch (e: Exception) {
                runOnUiThread { toast("Xtream: ${e.message ?: "Connection error"}") }
            }
        }
    }

    private fun editEpg() {
        val field = EditText(this).apply {
            hint = "https://.../epg.xml · {channel}"; setSingleLine(true)
            setText(prefs.getString("epg_url", ""))
        }
        AlertDialog.Builder(this).setTitle("XMLTV EPG").setView(field)
            .setPositiveButton(tr("Сохранить", "Speichern", "Save")) { _, _ ->
                val address = field.text.toString().trim()
                if (address.isEmpty() || address.startsWith("http://") || address.startsWith("https://")) {
                    prefs.edit().putString("epg_url", address).apply(); loadEpg()
                } else toast("HTTP(S) URL required")
            }.setNegativeButton(tr("Отмена", "Abbrechen", "Cancel"), null).show()
    }

    private var playlistGuideUrls = emptyList<String>()
    private val countryGuideUrls = listOf(
        "https://free-epg.de/api/epg/de.xml.gz",
        "https://free-epg.de/api/epg/ru.xml.gz"
    )

    private val alternativeCountryGuideUrls = listOf(
        "https://iptv-epg.org/files/epg-de.xml.gz",
        "https://iptv-epg.org/files/epg-ru.xml.gz"
    )

    private val europeanGuideOptions = linkedMapOf(
        "epg-de · DE Master" to listOf("https://kaimandura.github.io/epg-de/DE-MASTER.xml.gz"),
        "EPGShare01 · DE" to listOf("https://epgshare01.online/epgshare01/epg_ripper_DE1.xml.gz")
    )

    private fun playlistGuideKey(): String? = lists.getOrNull(activeList)?.second?.let { url ->
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
        "playlist_epg_" + hash.joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun epgSourceOptions(): LinkedHashMap<String, Pair<String, List<String>>> {
        val manual = prefs.getString("epg_url", "").orEmpty().trim()
            .takeUnless { it.isBlank() || it.contains("{channel}") }
        val server = prefs.getString("xtream_server", "").orEmpty().trimEnd('/')
        val user = prefs.getString("xtream_user", "").orEmpty()
        val pass = prefs.getString("xtream_pass", "").orEmpty()
        val provider = if (server.isNotBlank() && user.isNotBlank() && pass.isNotBlank())
                    "$server/xmltv.php?username=${URLEncoder.encode(user, "UTF-8")}&password=${URLEncoder.encode(pass, "UTF-8")}" else ""
        val options = linkedMapOf(
            "playlist" to (tr("EPG из плейлиста", "EPG aus Playlist", "Playlist EPG") to playlistGuideUrls.take(2)),
            "manual" to (tr("Мой XMLTV-адрес", "Meine XMLTV-Adresse", "My XMLTV URL") to listOfNotNull(manual)),
            "xtream" to ("Xtream XMLTV" to listOfNotNull(provider.takeIf { it.isNotBlank() })),
            "de" to ("FreeEPG · Deutschland" to listOf(countryGuideUrls[0])),
            "ru" to ("FreeEPG · Россия" to listOf(countryGuideUrls[1])),
            "iptv_de" to ("IPTV-EPG · DE · альтернативный" to listOf(alternativeCountryGuideUrls[0])),
            "iptv_ru" to ("IPTV-EPG · RU · альтернативный" to listOf(alternativeCountryGuideUrls[1])),
            "de_master" to ("DE Master" to europeanGuideOptions.values.elementAtOrNull(0).orEmpty()),
            "epgshare_de" to ("EPGShare01 · DE" to europeanGuideOptions.values.elementAtOrNull(1).orEmpty())
        )
        options.entries.forEach { entry ->
            entry.setValue(entry.value.first to entry.value.second.filter {
                it.startsWith("http://") || it.startsWith("https://")
            })
        }
        customEpgUrls().forEachIndexed { index, address ->
            options["custom_$index"] = (tr("Мой EPG · источник ${index + 1}", "Mein EPG · Quelle ${index + 1}",
                "My EPG · source ${index + 1}") to listOf(address))
        }
        return options
    }

    private fun customEpgUrls(): MutableList<String> = runCatching {
        val raw = JSONArray(prefs.getString("custom_epg_urls", "[]"))
        (0 until raw.length()).mapNotNull { raw.optString(it).trim().takeIf(String::isNotBlank) }.toMutableList()
    }.getOrDefault(mutableListOf())

    private fun manageCustomEpgSources() {
        val addresses = customEpgUrls()
        val choices = (listOf(tr("＋ Добавить адрес EPG", "＋ EPG-Adresse hinzufügen", "＋ Add EPG address")) +
            addresses.mapIndexed { index, value -> "${index + 1}. $value" }).toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(tr("Дополнительные EPG-источники", "Zusätzliche EPG-Quellen", "Additional EPG sources"))
            .setMessage(tr("Адреса можно добавлять и менять. Существующие источники сохраняются. Включение и выключение выполняется галочками в списке EPG.",
                "Quellen lassen sich hinzufügen und ändern. Vorhandene Einträge bleiben gespeichert. Aktivieren und Deaktivieren erfolgt über die EPG-Auswahl.",
                "You can add and edit addresses. Existing feeds stay saved. Enable or disable them with the checkboxes in the EPG source list."))
            .setItems(choices) { _, which -> editCustomEpgSource(if (which == 0) null else which - 1) }
            .setNegativeButton(tr("Закрыть", "Schließen", "Close"), null).show()
    }

    private fun editCustomEpgSource(index: Int?) {
        val addresses = customEpgUrls()
        val field = EditText(this).apply {
            hint = "https://.../epg.xml или .xml.gz"
            setSingleLine(true)
            if (index != null) setText(addresses.getOrNull(index).orEmpty())
        }
        AlertDialog.Builder(this)
            .setTitle(if (index == null) tr("Добавить EPG", "EPG hinzufügen", "Add EPG")
                else tr("Изменить адрес EPG", "EPG-Adresse ändern", "Edit EPG address"))
            .setView(field)
            .setPositiveButton(tr("Сохранить", "Speichern", "Save")) { _, _ ->
                val address = field.text.toString().trim()
                val valid = runCatching { URL(address).let { it.protocol.lowercase(Locale.ROOT) in setOf("http", "https") && it.host.isNotBlank() } }
                    .getOrDefault(false)
                if (!valid) {
                    toast(tr("Укажи полный адрес HTTP(S)", "Vollständige HTTP(S)-Adresse eingeben", "Enter a complete HTTP(S) URL"))
                    return@setPositiveButton
                }
                val savedIndex = index ?: addresses.size
                if (index == null) addresses.add(address) else if (index in addresses.indices) addresses[index] = address
                prefs.edit().putString("custom_epg_urls", JSONArray(addresses).toString()).apply()
                val sourceKey = playlistGuideKey()?.let { "${it}_sources" }
                if (sourceKey != null) {
                    val selected = (prefs.getStringSet(sourceKey, null)
                        ?: (setOf("playlist", "manual", "xtream", "de", "ru") + customEpgUrls().indices.map { "custom_$it" })).toMutableSet()
                    selected.add("custom_$savedIndex")
                    prefs.edit().putStringSet(sourceKey, selected).apply()
                }
                loadEpg(downloadFull = true, forceRefresh = true)
                showSettings("epg")
            }
            .setNegativeButton(tr("Отмена", "Abbrechen", "Cancel"), null).show()
    }

    private fun selectPlaylistGuide() {
        val key = playlistGuideKey() ?: return toast(tr("Сначала выбери плейлист", "Zuerst Playlist wählen", "Select a playlist first"))
        val options = epgSourceOptions().filterValues { it.second.isNotEmpty() }
        val ids = options.keys.toList()
        val labels = options.values.map { it.first }.toTypedArray()
        val preferenceKey = "${key}_sources"
        val stored = prefs.getStringSet(preferenceKey, null)
        val defaults = setOf("playlist", "manual", "xtream", "de", "ru") + customEpgUrls().indices.map { "custom_$it" }
        val selected = (stored ?: defaults).toMutableSet()
        val checked = ids.map { it in selected }.toBooleanArray()
        AlertDialog.Builder(this).setTitle(tr("Выбери EPG (можно несколько)", "EPG-Quellen wählen (mehrere möglich)",
            "Choose EPG sources (select multiple)"))
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                ids.getOrNull(which)?.let { if (isChecked) selected.add(it) else selected.remove(it) }
            }
            .setPositiveButton(tr("Сохранить", "Speichern", "Save")) { _, _ ->
                if (selected.none { it in ids }) {
                    toast(tr("Отметь хотя бы один источник", "Wähle mindestens eine Quelle", "Select at least one source"))
                } else {
                    prefs.edit().putStringSet(preferenceKey, selected.toSet()).apply()
                    loadEpg(downloadFull = true, forceRefresh = true)
                }
            }
            .setNegativeButton(tr("Отмена", "Abbrechen", "Cancel"), null).show()
    }

    private val epgRefreshIntervalMs: Long
        get() = prefs.getInt("epg_refresh_hours", 2).coerceIn(1, 24) * 60L * 60L * 1000L
    private var lastEpgRequestAt: Long? = null
    private val dailyEpgRefresh = Runnable { scheduleEpgRefresh() }

    private fun scheduleEpgRefresh() {
        clockHandler.removeCallbacks(dailyEpgRefresh)
        if (current == "player") {
            clockHandler.postDelayed(dailyEpgRefresh, 60_000L)
            return
        }
        val lastRequest = lastEpgRequestAt
        val elapsed = if (lastRequest == null) epgRefreshIntervalMs
            else android.os.SystemClock.elapsedRealtime() - lastRequest
        if (elapsed >= epgRefreshIntervalMs) loadEpg()
        val sinceRequest = android.os.SystemClock.elapsedRealtime() - (lastEpgRequestAt ?: 0L)
        clockHandler.postDelayed(dailyEpgRefresh, (epgRefreshIntervalMs - sinceRequest).coerceAtLeast(1L))
    }

    private data class CachedGuide(
        val programmes: Map<String, MutableList<Programme>>,
        val aliases: Map<String, String>,
        val icons: Map<String, String>,
        val savedAt: Long
    )

    private fun guideCacheFile() = android.util.AtomicFile(java.io.File(filesDir, "xmltv-cache-v1"))

    private fun readGuideCache(key: String, now: Long): CachedGuide? = synchronized(epgCacheLock) {
        runCatching {
            java.io.DataInputStream(guideCacheFile().openRead().buffered()).use { input ->
                if (input.readUTF() != key) return@use null
                val savedAt = input.readLong()
                if (savedAt > now || now - savedAt > 3L * 86400000L) return@use null
                fun count(max: Int) = input.readInt().also { require(it in 0..max) }
                fun strings(): Map<String, String> = buildMap {
                    repeat(count(100_000)) { put(input.readUTF(), input.readUTF()) }
                }
                val aliases = strings()
                val icons = strings()
                val schedule = linkedMapOf<String, MutableList<Programme>>()
                repeat(count(20_000)) {
                    val id = input.readUTF()
                    val entries = mutableListOf<Programme>()
                    repeat(count(2_000)) {
                        val start = input.readLong()
                        val stop = input.readLong()
                        val title = input.readUTF()
                        val description = input.readUTF()
                        if (stop > now - guideHistoryWindowMs) entries.add(Programme(id, start, stop, title, description))
                    }
                    if (entries.isNotEmpty()) schedule[id] = entries
                }
                CachedGuide(schedule, aliases, icons, savedAt)
            }
        }.getOrNull()
    }

    private fun writeGuideCache(key: String, now: Long, guide: CachedGuide, generation: Int) {
        synchronized(epgCacheLock) {
            if (generation != epgLoadGeneration) return
            val file = guideCacheFile()
            val stream = file.startWrite()
            try {
                val output = java.io.DataOutputStream(stream.buffered())
                output.writeUTF(key)
                output.writeLong(now)
                fun strings(values: Map<String, String>) {
                    output.writeInt(values.size)
                    values.forEach { (k, v) -> output.writeUTF(k); output.writeUTF(v) }
                }
                strings(guide.aliases)
                strings(guide.icons)
                output.writeInt(guide.programmes.size)
                guide.programmes.forEach { (id, programmes) ->
                    output.writeUTF(id)
                    val entries = programmes.take(2_000)
                    output.writeInt(entries.size)
                    entries.forEach {
                        output.writeLong(it.start)
                        output.writeLong(it.stop)
                        output.writeUTF(it.title)
                        output.writeUTF(it.description)
                    }
                }
                output.flush()
                file.finishWrite(stream)
            } catch (failure: Exception) {
                file.failWrite(stream)
                throw failure
            }
        }
    }

    private fun applyGuide(guide: CachedGuide) {
        epgSchedule.clear()
        epgSchedule.putAll(guide.programmes)
        epgAliases = guide.aliases
        epgIcons = guide.icons
        if (sidePanel == null) {
            if (current in setOf("tv", "favorites", "guide")) showCatalog(current, catalogGroup)
            else if (current == "home") showHome()
        }
    }

    private fun showEpgStatus(value: String, color: Int = Color.WHITE) {
        epgStatusText = value
        epgStatusColor = color
        epgStatusView?.let { view ->
            view.text = value
            view.setTextColor(color)
        }
    }

    private fun clearEpgData() {
        epgLoadGeneration++
        epgSchedule.clear()
        epgAliases = emptyMap()
        epgIcons = emptyMap()
        channelGuides.clear()
        channelGuideRequests.clear()
        runCatching { guideCacheFile().delete() }
        showEpgStatus(tr("Данные EPG очищены", "EPG-Daten gelöscht", "EPG data cleared"))
    }

    private fun loadEpg(
        downloadFull: Boolean = true,
        forceRefresh: Boolean = false
    ) {
        val channels = items.filter { it.type == "tv" }
        if (channels.isEmpty()) {
            showEpgStatus(tr("Сначала загрузи каналы", "Zuerst Sender laden", "Load channels first"))
            lastEpgRequestAt = android.os.SystemClock.elapsedRealtime()
            return
        }
        showEpgStatus(tr("Подготовка телегида…", "Programm wird vorbereitet…", "Preparing TV guide…"))
        val wanted = channels.flatMap { listOf(it.epgId, it.title) }
            .flatMap(::guideNameKeys).toHashSet()
        lastEpgRequestAt = android.os.SystemClock.elapsedRealtime()
        val generation = ++epgLoadGeneration
        val manual = prefs.getString("epg_url", "").orEmpty().trim()
        val options = epgSourceOptions()
        val selectionKey = playlistGuideKey()?.let { "${it}_sources" }
        val selected = selectionKey?.let { prefs.getStringSet(it, null) }
            ?: setOf("playlist", "manual", "xtream", "de", "ru")
        val addresses = selected.asSequence().flatMap { options[it]?.second.orEmpty().asSequence() }
            .filter { it.startsWith("http://") || it.startsWith("https://") }.distinct().toList()
        if (addresses.isEmpty()) {
            showEpgStatus(tr("Нет отмеченных адресов EPG", "Keine ausgewählten EPG-Adressen",
                "No selected EPG source URLs"))
            return
        }
        val now = System.currentTimeMillis()
        val cacheKey = java.security.MessageDigest.getInstance("SHA-256")
            .digest((addresses.joinToString("\n") + "\n" + wanted.sorted().joinToString("\n"))
                .toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
        thread {
            val cached = readGuideCache(cacheKey, now)
            if (cached != null && cached.programmes.isNotEmpty()) runOnUiThread {
                if (generation == epgLoadGeneration && !isDestroyed) applyGuide(cached)
            }
            if (!forceRefresh && cached != null && now - cached.savedAt < epgRefreshIntervalMs &&
                cached.programmes.values.any { entries -> entries.any { now in it.start until it.stop } }) {
                runOnUiThread {
                    if (generation == epgLoadGeneration && !isDestroyed) {
                        showEpgStatus(tr("Телегид загружен из памяти", "Programm aus Cache geladen", "TV guide loaded from cache"))
                    }
                }
                return@thread
            }
            if (!downloadFull) {
                runOnUiThread {
                    if (generation == epgLoadGeneration && !isDestroyed) showEpgStatus(tr(
                        "Расписание запрашивается при открытии канала. Для общего XMLTV нажми «Обновить телепрограмму».",
                        "Programm wird beim Öffnen des Senders angefragt. Für XMLTV die Aktualisierung starten.",
                        "Guide is requested when you open a channel. Use Refresh TV guide for a full XMLTV file."))
                }
                return@thread
            }
            val schedule = mutableMapOf<String, MutableList<Programme>>()
            val names = mutableMapOf<String, MutableSet<String>>()
            val icons = mutableMapOf<String, String>()
            fun publish(lastSource: Boolean): Boolean {
                val aliases = mutableMapOf<String, MutableSet<String>>()
                (schedule.keys + icons.keys).forEach { id ->
                    (names[id].orEmpty() + id).forEach { name ->
                        guideNameKeys(name).forEach { key -> aliases.getOrPut(key) { mutableSetOf() }.add(id) }
                    }
                }
                val uniqueAliases = aliases.mapNotNull { (key, ids) ->
                    ids.singleOrNull()?.let { key to it }
                }.toMap()
                val sorted = schedule.mapValues { (id, entries) ->
                    val past = cached?.programmes?.get(id).orEmpty()
                        .filter { it.stop <= now }
                    (entries + past).distinctBy { it.start }.sortedBy { it.start }.toMutableList()
                }
                val snapshot = CachedGuide(sorted, uniqueAliases, icons.toMap(), now)
                val airingIds = sorted.filterValues { entries ->
                    entries.any { now in it.start until it.stop }
                }.keys
                val complete = channels.all { entry ->
                    val id = entry.epgId.takeIf { it in sorted }
                        ?: sequenceOf(entry.epgId, entry.title, entry.title.trim().replace(qualitySuffix, ""))
                            .flatMap { guideNameKeys(it).asSequence() }.mapNotNull(uniqueAliases::get).firstOrNull()
                    id != null && id in airingIds
                }
                val finished = lastSource || complete
                if (finished && sorted.isNotEmpty()) {
                    runCatching { writeGuideCache(cacheKey, now, snapshot, generation) }
                }
                runOnUiThread {
                    if (generation != epgLoadGeneration || isDestroyed) return@runOnUiThread
                    if (sorted.isNotEmpty() || (epgSchedule.isEmpty() && snapshot.icons.isNotEmpty())) {
                        applyGuide(snapshot)
                    }
                    if (finished) {
                        if (sorted.isEmpty()) toast(tr("Не удалось обновить телегид", "Programm konnte nicht aktualisiert werden",
                            "Could not refresh TV guide"))
                        val matched = channels.count { guideEntries(it).isNotEmpty() }
                        toast(tr("Телегид", "TV-Programm", "TV guide") + ": $matched / ${channels.size}")
                    }
                }
                return finished
            }
            val results = mutableListOf<String>()
            val threadCount = if (prefs.getBoolean("epg_parallel", true)) minOf(4, addresses.size) else 1
            val executor = java.util.concurrent.Executors.newFixedThreadPool(threadCount.coerceAtLeast(1))
        val completed = java.util.concurrent.ExecutorCompletionService<Pair<Int, Result<GuideFeed>>>(executor)
            addresses.forEachIndexed { index, address ->
                completed.submit(java.util.concurrent.Callable {
                    index to runCatching { readGuide(address, now, wanted) { percent ->
                        if (generation == epgLoadGeneration) runOnUiThread {
                            if (generation == epgLoadGeneration && !isDestroyed) showEpgStatus(
                                tr("Загрузка EPG", "EPG wird geladen", "Loading EPG") + " ${index + 1}/${addresses.size}: $percent%")
                        }
                    } }
                })
            }
            try {
                var count = 0
                var finished = false
                while (count < addresses.size && !finished) {
                    if (generation != epgLoadGeneration) return@thread
                    val (index, result) = completed.take().get()
                    count++
                    val address = addresses[index]
                    val host = runCatching { URL(address).host }.getOrDefault("XMLTV")
                    runOnUiThread {
                        if (generation == epgLoadGeneration && !isDestroyed) showEpgStatus(
                            tr("Загрузка EPG", "EPG wird geladen", "Loading EPG") + " $count/${addresses.size}: $host")
                    }
                    val feed = result.getOrNull()
                    results.add("$host: " + (feed?.let { "${it.programmes.size}" }
                        ?: result.exceptionOrNull()?.let {
                            if (it is java.io.IOException && it.message.orEmpty().matches(Regex("HTTP [0-9]{3}")))
                                it.message.orEmpty() else it.javaClass.simpleName
                        }.orEmpty()))
                    if (feed != null) {
                        feed.programmes.forEach { (id, entries) -> schedule.getOrPut(id) { mutableListOf() }.addAll(entries) }
                        feed.names.forEach { (id, aliases) -> names.getOrPut(id) { mutableSetOf() }.addAll(aliases) }
                        feed.icons.forEach { (id, icon) -> icons.putIfAbsent(id, icon) }
                    }
                    finished = (feed != null || count == addresses.size) && publish(count == addresses.size)
                }
            } finally {
                executor.shutdownNow()
            }
            val report = results.joinToString("\n")
            runOnUiThread {
                if (generation == epgLoadGeneration && !isDestroyed) {
                    val matched = channels.count { guideEntries(it).isNotEmpty() }
                    val success = schedule.isNotEmpty() && matched > 0
                    val prefix = if (success) "✓ " else "✕ "
                    showEpgStatus(prefix + tr("Каналы с телегидом", "Sender mit Programm", "Channels with guide") +
                        ": $matched / ${channels.size}\n$report",
                        if (success) Color.rgb(109, 233, 153) else Color.rgb(255, 121, 129))
                }
            }
        }
    }

    private fun readGuide(address: String, now: Long, wanted: Set<String>, progress: (Int) -> Unit): GuideFeed {
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.connectTimeout = 12000
        connection.readTimeout = 30000
        val schedule = mutableMapOf<String, MutableList<Programme>>()
        val names = mutableMapOf<String, MutableSet<String>>()
        val icons = mutableMapOf<String, String>()
        val timeFormat = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US).apply { isLenient = false }
        val timeSeparator = Regex("\\s+")
        fun time(value: String?): Long {
            val parts = value.orEmpty().trim().split(timeSeparator, limit = 2)
            val stamp = parts[0]
            if (stamp.length !in listOf(8, 10, 12, 14) || !stamp.all(Char::isDigit)) return 0L
            val zone = parts.getOrNull(1)?.replace(":", "") ?: "+0000"
            return runCatching { timeFormat.parse(stamp.padEnd(14, '0') + " " + zone)?.time ?: 0L }.getOrDefault(0L)
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw java.io.IOException("HTTP $status")
            val total = connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
            val inputStream = object : java.io.FilterInputStream(connection.inputStream) {
                private var received = 0L
                private var last = -2
                private fun count(bytes: Int) {
                    if (bytes <= 0 || total <= 0L) return
                    received += bytes
                    val percent = (received * 100 / total).coerceAtMost(99).toInt()
                    if (percent >= last + 2) {
                        last = percent
                        progress(percent)
                    }
                }
                override fun read(): Int = super.read().also { if (it >= 0) count(1) }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                    super.read(buffer, offset, length).also { count(it) }
            }
            inputStream.buffered(64 * 1024).use { raw ->
                raw.mark(2)
                val gzip = raw.read() == 0x1f && raw.read() == 0x8b
                raw.reset()
                val data = if (gzip) java.util.zip.GZIPInputStream(raw, 64 * 1024) else raw
                data.use { input ->
                    val xml = android.util.Xml.newPullParser()
                    xml.setInput(input, null)
                    require(xml.nextTag() == org.xmlpull.v1.XmlPullParser.START_TAG && xml.name == "tv")
                    fun requested(value: String): Boolean = guideNameKeys(value).any { it in wanted }
                    val wantedIds = HashSet<String>()
                    var storedProgrammes = 0
                    val programmeIds = HashMap<String, Boolean>()
                    var wantedProgramme = false
                    var id = ""
                    var channel = ""
                    var title = ""
                    var description = ""
                    var start = 0L
                    var stop = 0L
                    while (xml.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                        when (xml.eventType) {
                            org.xmlpull.v1.XmlPullParser.START_TAG -> when (xml.name) {
                                "channel" -> id = xml.getAttributeValue(null, "id").orEmpty()
                                "display-name" -> {
                                    val name = xml.nextText().trim()
                                    if (id.isNotBlank() && name.isNotBlank()) names.getOrPut(id) { mutableSetOf() }.add(name)
                                }
                                "icon" -> if (id.isNotBlank()) {
                                    webUrl(connection.url.toString(), xml.getAttributeValue(null, "src").orEmpty())
                                        ?.let { icons.putIfAbsent(id, it) }
                                    Unit
                                } else Unit
                                "programme" -> {
                                    channel = xml.getAttributeValue(null, "channel").orEmpty()
                                    wantedProgramme = programmeIds.getOrPut(channel) { channel in wantedIds || requested(channel) }
                                    start = if (wantedProgramme) time(xml.getAttributeValue(null, "start")) else 0L
                                    stop = if (wantedProgramme) time(xml.getAttributeValue(null, "stop")) else 0L
                                    title = ""
                                    description = ""
                                }
                                "title" -> if (wantedProgramme) { title = xml.nextText().trim().take(240) } else Unit
                                "desc" -> if (wantedProgramme) { description = xml.nextText().trim().take(500) } else Unit
                                else -> Unit
                            }
                            org.xmlpull.v1.XmlPullParser.END_TAG -> when (xml.name) {
                                "channel" -> {
                                    if (requested(id) || names[id].orEmpty().any(::requested)) {
                                        wantedIds.add(id)
                                        programmeIds[id] = true
                                    }
                                    else {
                                        names.remove(id)
                                        icons.remove(id)
                                    }
                                    id = ""
                                }
                                "programme" -> if (channel.isNotBlank() && title.isNotBlank() && start > 0L &&
                                    stop > start && stop > now - guideHistoryWindowMs && start < now + 3L * 86400000) {
                                    val programmes = schedule.getOrPut(channel) { mutableListOf() }
                                    if (programmes.size < 600 && storedProgrammes < 60_000) {
                                        programmes.add(Programme(channel, start, stop, title, description))
                                        storedProgrammes++
                                    }
                                } else Unit
                                else -> Unit
                            }
                            else -> Unit
                        }
                        xml.next()
                    }
                }
            }
        } finally { connection.disconnect() }
        return GuideFeed(schedule, names, icons)
    }

    private fun localIp(): String? {
        val manager = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        manager.activeNetwork?.let { manager.getLinkProperties(it) }?.linkAddresses
            ?.map { it.address }?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?.hostAddress?.let { return it }
        return try {
            val interfaces =
                NetworkInterface.getNetworkInterfaces()

            var ip: String? = null

            while (interfaces.hasMoreElements() && ip == null) {
                val network = interfaces.nextElement()

                if (!network.isLoopback && network.isUp) {
                    val addresses = network.inetAddresses

                    while (addresses.hasMoreElements()) {
                        val address = addresses.nextElement()

                        if (
                            address is Inet4Address &&
                            !address.isLoopbackAddress &&
                            !address.isLinkLocalAddress
                        ) {
                            ip = address.hostAddress
                            break
                        }
                    }
                }
            }

            ip
        } catch (_: Exception) {
            null
        }
    }

    private fun remoteUrl(): String? =
        remoteServer?.takeUnless { it.isClosed }?.localPort?.let { port ->
            localIp()?.let { ip -> "http://$ip:$port/?key=$remoteToken" }
        }

    private var remoteReturnPage = "home"

    private var remoteSource = ""

    private fun showRemote(
        source: String = if (current == "remote") remoteSource else "",
        authorized: Boolean = false
    ) {
        if (!authorized) {
            requirePlaylistPin { showRemote(source, authorized = true) }
            return
        }
        if (current != "remote") remoteReturnPage = current
        remoteSource = source
        startRemoteServer()
        scaffold("remote")

        sectionTitle(
            tr(
                "Управление с телефона",
                "Steuerung mit Handy",
                "Phone control"
            )
        )

        val url = remoteUrl()?.let { if (source == "vportal") "$it&source=vportal" else it }

        if (url == null || !remoteRunning) {
            content.addView(button(tr("Повторить", "Erneut versuchen", "Try again")) {
                startRemoteServer()
                showRemote()
            })
            content.addView(
                label(
                    tr(
                        "Подключи телефон и ТВ к одному Wi-Fi",
                        "TV und Handy mit demselben WLAN verbinden",
                        "Connect TV and phone to the same Wi-Fi"
                    )
                )
            )
            return
        }

        content.addView(
            label(
                tr(
                    "Сканируй QR-код телефоном",
                    "QR-Code mit Handy scannen",
                    "Scan QR with phone"
                )
            )
        )

        try {
            val matrix = MultiFormatWriter().encode(
                url,
                BarcodeFormat.QR_CODE,
                240,
                240
            )

            val bitmap = Bitmap.createBitmap(
                240,
                240,
                Bitmap.Config.ARGB_8888
            )

            for (y in 0 until 240) {
                for (x in 0 until 240) {
                    bitmap.setPixel(
                        x,
                        y,
                        if (matrix.get(x, y))
                            Color.BLACK else Color.WHITE
                    )
                }
            }

            val image = ImageView(this)
            image.setImageBitmap(bitmap)

            content.addView(
                image,
                LinearLayout.LayoutParams(dp(240), dp(240)).apply { gravity = Gravity.CENTER_HORIZONTAL }
            )

        } catch (e: Exception) {
            content.addView(
                label("QR error: ${e.message}")
            )
        }

        content.addView(label(url, 14f))
    }

    @Volatile private var internetPairGeneration = 0

    private fun relayCall(base: URL, method: String, key: String? = null): JSONObject {
        val connection = URL(base, "/api/pair").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.instanceFollowRedirects = false
            if (key != null) connection.setRequestProperty("Authorization", "Bearer $key")
            val status = connection.responseCode
            if (status == 410) return JSONObject().put("expired", true)
            check(status in 200..299)
            return connection.inputStream.bufferedReader().use { input ->
                val payload = CharArray(16_384)
                var length = 0
                while (length < payload.size) {
                    val count = input.read(payload, length, payload.size - length)
                    if (count < 0) return@use JSONObject(String(payload, 0, length))
                    length += count
                }
                throw java.io.IOException("Response too large")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun showInternetPairing(authorized: Boolean = false) {
        if (!authorized) {
            requirePlaylistPin { showInternetPairing(true) }
            return
        }
        scaffold("remote_internet")
        sectionTitle(tr("Настройка через интернет", "Über Internet einrichten", "Set up over internet"))
        content.addView(button(tr("Адрес сервера подключения", "Adresse des Verbindungsservers", "Pairing server address")) {
            val field = EditText(this).apply {
                hint = "https://…"
                setSingleLine(true)
                setText(prefs.getString("pairing_server", ""))
            }
            AlertDialog.Builder(this).setView(field).setTitle(tr("Сервер подключения", "Verbindungsserver", "Pairing server"))
                .setPositiveButton(tr("Сохранить", "Speichern", "Save")) { _, _ ->
                    val address = runCatching { URL(field.text.toString().trim()) }.getOrNull()
                    if (address == null || address.protocol != "https" || address.host.isBlank() ||
                        address.userInfo != null || address.query != null || address.ref != null ||
                        address.path !in listOf("", "/")) {
                        toast(tr("Нужен HTTPS-адрес сервера", "HTTPS-Serveradresse erforderlich", "HTTPS server address required"))
                    } else {
                        prefs.edit().putString("pairing_server", address.toString()).apply()
                        showInternetPairing()
                    }
                }
                .setNegativeButton(tr("Отмена", "Abbrechen", "Cancel"), null).show()
        })
        val address = prefs.getString("pairing_server", "").orEmpty()
        if (address.isBlank()) {
            content.addView(label(tr("Укажи HTTPS-адрес своего сервера подключения.",
                "Gib die HTTPS-Adresse deines Verbindungsservers ein.", "Enter your HTTPS pairing server address.")))
            return
        }
        val ticket = internetPairGeneration
        val status = label(tr("Создаём код…", "Code wird erstellt…", "Creating code…"))
        content.addView(status)
        thread {
            try {
                val server = URL(address)
                val session = relayCall(server, "POST")
                val key = session.getString("key")
                val link = URL(session.getString("link"))
                check(link.protocol == "https" && link.host == server.host &&
                    link.port == server.port && link.userInfo == null)
                val matrix = MultiFormatWriter().encode(link.toString(), BarcodeFormat.QR_CODE, 300, 300)
                val pixels = IntArray(300 * 300) { i ->
                    if (matrix.get(i % 300, i / 300)) Color.BLACK else Color.WHITE
                }
                val bitmap = Bitmap.createBitmap(pixels, 300, 300, Bitmap.Config.ARGB_8888)
                runOnUiThread {
                    if (ticket != internetPairGeneration || isDestroyed) return@runOnUiThread
                    status.text = tr("Отправь фото QR. Код действует 10 минут.",
                        "Sende ein Foto des QR-Codes. Er gilt 10 Minuten.", "Share a photo of this QR. It lasts 10 minutes.")
                    content.addView(ImageView(this).apply {
                        setImageBitmap(bitmap)
                        contentDescription = "QR"
                    }, LinearLayout.LayoutParams(dp(230), dp(230)))
                }
                val deadline = android.os.SystemClock.elapsedRealtime() + 600_000L
                while (ticket == internetPairGeneration && android.os.SystemClock.elapsedRealtime() < deadline) {
                    Thread.sleep(3_000L)
                    if (ticket != internetPairGeneration) return@thread
                    val received = relayCall(server, "GET", key)
                    if (received.optBoolean("expired")) break
                    if (received.optBoolean("pending")) continue
                    val url = URL(received.getString("url"))
                    val kind = received.getString("kind")
                    check(url.protocol in setOf("http", "https") && url.host.isNotBlank() && kind in setOf("tv", "vod"))
                    val name = received.optString("name").trim().take(80)
                        .takeUnless { it.contains("://") }.orEmpty().ifBlank {
                            tr("Новый плейлист", "Neue Playlist", "New playlist")
                        }
                    runOnUiThread {
                        if (ticket != internetPairGeneration || isDestroyed) return@runOnUiThread
                        status.text = tr("Плейлист получен", "Playlist empfangen", "Playlist received")
                        AlertDialog.Builder(this).setTitle(tr("Добавить плейлист?", "Playlist hinzufügen?", "Add playlist?"))
                            .setMessage(name)
                            .setPositiveButton(tr("Добавить", "Hinzufügen", "Add")) { _, _ ->
                                storeImportedPlaylistName(url.toString(), name)
                                if (kind == "vod") saveVodPlaylistUrls(vodPlaylistUrls() + url.toString())
                                else {
                                    val existing = lists.indexOfFirst { it.second == url.toString() }
                                    if (existing < 0) lists.add(name to url.toString())
                                    else lists[existing] = name to url.toString()
                                    activeList = if (existing < 0) lists.lastIndex else existing
                                    saveLists()
                                    loadPlaylist(activeList)
                                }
                                showHome()
                            }
                            .setNegativeButton(tr("Отмена", "Abbrechen", "Cancel")) { _, _ -> showHome() }
                            .show()
                    }
                    return@thread
                }
                runOnUiThread {
                    if (ticket == internetPairGeneration && !isDestroyed) status.text =
                        tr("Код истёк. Открой этот раздел снова.", "Code abgelaufen. Bereich erneut öffnen.", "Code expired. Reopen this section.")
                }
            } catch (_: Exception) {
                runOnUiThread {
                    if (ticket == internetPairGeneration && !isDestroyed) status.text =
                        tr("Сервер недоступен", "Server nicht erreichbar", "Pairing server unavailable")
                }
            }
        }
    }

    private fun startRemoteServer() {
        if (remoteRunning || remoteStarting) return
        remoteStarting = true

        thread(name = "VladiMirRemote") {
            try {
                val server = ServerSocket(0)
                remoteServer = server
                remoteRunning = true
                remoteStarting = false
                runOnUiThread { if (!isDestroyed && current == "remote") showRemote() }

                while (!server.isClosed) {
                    val client = server.accept()
                    thread {
                        handleRemote(client)
                    }
                }
            } catch (_: Exception) {
                remoteRunning = false
                remoteStarting = false
            }
        }
    }

    private fun escapeHtml(value: String): String =
        value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

    private fun remoteSourceForm(source: String, title: String, fields: String = "") = """
        <h4>$title</h4>
        <form method="post" action="/add?key=$remoteToken">
          <input type="hidden" name="source" value="$source">
          <input name="url" type="${if (source == "vportal") "password" else "url"}"
                 placeholder="${if (source == "vportal") "portal::[key:…]https://…/api/v1/" else "https://…"}"
                 autocomplete="off" required>
          $fields
          <button>Сохранить / Speichern / Save</button>
        </form>
    """.trimIndent()

    private fun handleRemote(socket: Socket) {
        socket.use { client ->
            try {
                client.soTimeout = 5000

                val input = client.getInputStream()
                    .bufferedReader()

                val first = input.readLine() ?: return
                val parts = first.split(' ')

                if (parts.size < 2) return

                val uri = Uri.parse(parts[1])
                val path = uri.path ?: "/"
                val key = uri.getQueryParameter("key")

                var body = ""
                var status = "200 OK"

                if (key != remoteToken) {
                    status = "403 Forbidden"
                    body = "Forbidden"

                } else if (path == "/play") {
                    val index = uri.getQueryParameter("id")
                        ?.toIntOrNull()

                    if (index != null) {
                        runOnUiThread {
                            items.getOrNull(index)?.let {
                                play(it)
                            }
                        }
                    }

                    body = """
                        <p>Sent to TV</p>
                        <a href="/?key=$remoteToken">Back</a>
                    """.trimIndent()

                } else if (path == "/add" && parts[0] == "POST") {
                    var contentLength = 0
                    while (true) {
                        val header = input.readLine() ?: return
                        if (header.isEmpty()) break
                        if (header.startsWith("Content-Length:", ignoreCase = true)) {
                            contentLength = header.substringAfter(':').trim().toIntOrNull() ?: return
                        }
                    }
                    if (contentLength !in 1..24000) return
                    val formData = CharArray(contentLength)
                    for (i in formData.indices) {
                        val value = input.read()
                        if (value < 0) return
                        formData[i] = value.toChar()
                    }
                    val form = Uri.parse("http://localhost/?" + String(formData))
                    fun field(name: String) = form.getQueryParameter(name).orEmpty()
                    val source = field("source")
                    val name = form.getQueryParameter("name")
                        ?.take(100)?.trim().orEmpty()

                    val url = form.getQueryParameter("url")
                        ?.take(4096)?.trim().orEmpty()

                    val user = field("user").trim()
                    val password = field("password")
                    val validUrl = if (source == "vportal") parseVideoPortal(url) != null
                        else url.startsWith("https://") || url.startsWith("http://")
                    if (source !in setOf("tv", "vod", "epg", "xtream", "vportal") ||
                        !validUrl ||
                        (source == "xtream" && (user.isBlank() || password.isBlank()))) {
                        status = "400 Bad Request"
                        body = "Invalid source details"
                    } else {
                        runOnUiThread {
                            when (source) {
                                "vportal" -> {
                                    prefs.edit().putString("video_portal_link", url).apply()
                                    if (current == "remote") showSettings("vod_playlists")
                                    toast(tr("VPortal сохранён", "VPortal gespeichert", "VPortal saved"))
                                }
                                "tv" -> {
                                    lists.add(name.ifBlank { "Playlist ${lists.size + 1}" } to url)
                                    activeList = lists.lastIndex
                                    saveLists()
                                    loadPlaylist(activeList)
                                }
                                "vod" -> {
                                    saveVodPlaylistUrls(vodPlaylistUrls() + url)
                                }
                                "epg" -> {
                                    prefs.edit().putString("epg_url", url).apply()
                                    loadEpg()
                                }
                                "xtream" -> {
                                    prefs.edit().putString("xtream_server", url.trimEnd('/'))
                                        .putString("xtream_user", user).putString("xtream_pass", password).apply()
                                    loadXtream()
                                }
                            }
                        }

                        body = """
                            <meta charset="utf-8">
                            <p>Источник передан / Quelle gesendet / Source submitted</p>
                            <a href="/?key=$remoteToken">Back</a>
                        """.trimIndent()
                    }

                } else if (path == "/" && uri.getQueryParameter("source") == "vportal") {
                    body = """
                        <meta charset="utf-8">
                        <meta name="viewport" content="width=device-width, initial-scale=1">
                        ${remoteSourceForm("vportal", "VPortal · Медиатека")}
                    """.trimIndent()
                } else if (path == "/" || path == "/search") {
                    val query =
                        uri.getQueryParameter("q") ?: ""

                    val matches = items.toList()
                        .withIndex()
                        .filter {
                            query.isBlank() ||
                            it.value.title.contains(query, true) ||
                            it.value.group.contains(query, true)
                        }
                        .take(80)

                    val links = matches.joinToString("<hr>") {
                        (index, entry) ->
                        """
                        <a href="/play?key=$remoteToken&id=$index">
                        ▶ ${escapeHtml(entry.title)}
                        </a>
                        <small>${escapeHtml(entry.group)}</small>
                        """.trimIndent()
                    }

                    body = """
                    <!doctype html>
                    <html lang="ru">
                    <head>
                      <meta charset="utf-8">
                      <meta name="viewport"
                            content="width=device-width,initial-scale=1">
                      <title>VladiMir-TV Remote</title>
                      <style>
                        body {
                          background:#0c111d;
                          color:white;
                          font:18px sans-serif;
                          padding:18px;
                        }
                        input,button {
                          font:20px sans-serif;
                          padding:12px;
                          max-width:95%;
                        }
                        a {
                          color:#9ac6ff;
                          display:inline-block;
                          padding:8px;
                        }
                      </style>
                    </head>
                    <body>
                      <h2>VladiMir-TV</h2>
                      <p>Поиск / Suche / Search</p>
                      <form action="/search">
                        <input type="hidden"
                               name="key"
                               value="$remoteToken">
                        <input name="q"
                               value="${escapeHtml(query)}"
                               placeholder="Канал / Film / Channel">
                        <button>Поиск / Search</button>
                      </form>
                      <p>${matches.size} результатов</p>
                      $links
                      <hr>
                      <h3>Источники / Quellen / Sources</h3>
                      ${remoteSourceForm("tv", "ТВ M3U / TV M3U",
                          "<input name='name' placeholder='Название / Name'>")}
                      ${remoteSourceForm("vod", "Видеотека M3U / Mediathek / Video library")}
                      ${remoteSourceForm("vportal", "Видеотека VPortal / Mediathek / Video library")}
                      ${remoteSourceForm("epg", "Телепрограмма / TV guide XMLTV")}
                      ${remoteSourceForm("xtream", "Xtream Codes",
                          "<input name='user' placeholder='Login' required>" +
                          "<input name='password' type='password' placeholder='Password' required>")}
                    </body>
                    </html>
                    """.trimIndent()

                } else {
                    status = "404 Not Found"
                    body = "Not found"
                }

                val bytes = body.toByteArray(Charsets.UTF_8)

                val headers = """
                    HTTP/1.1 $status
                    Content-Type: text/html; charset=utf-8
                    Content-Length: ${bytes.size}
                    Cache-Control: no-store
                    Connection: close

                """.trimIndent().replace("\n", "\r\n") + "\r\n"

                client.getOutputStream().write(
                    headers.toByteArray(Charsets.UTF_8)
                )

                client.getOutputStream().write(bytes)
                client.getOutputStream().flush()

            } catch (_: Exception) {
            }
        }
    }

    private fun toast(value: String) {
        Toast.makeText(
            this,
            value,
            Toast.LENGTH_LONG
        ).show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (sidePanel != null) {
            sidePanelBack?.invoke() ?: closeSidePanel()
            return
        }
        if (current == "home") {
            if (prefs.getBoolean("system_back_exits", true)) finish()
            else AlertDialog.Builder(this).setTitle(tr("Выйти из приложения?", "App beenden?", "Exit the app?"))
                .setPositiveButton(tr("Выйти", "Beenden", "Exit")) { _, _ -> finish() }
                .setNegativeButton(tr("Остаться", "Bleiben", "Stay"), null).show()
            return
        }
        if (current == "fireplace") { showHome(); return }
        if (current == "stalker_channels") { showStalkerCategories(); return }
        if (current == "stalker_categories") { showHome(); return }
        if (current == "radio") { showRadioMenu(); return }
        if (current == "music_stations") { showMusicHub(); return }
        if (current == "music_hub") { showHome(); return }
        if (current == "radio_menu") { showHome(); return }
        if (current == "player") {
            cancelPlaybackRetry()
            val entry = currentEntry
            if (returnToLiveAfterArchive && entry != null) {
                play(entry, channelOrder = playbackChannels)
                return
            }
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            resumePlaybackOnStart = false
            rememberVideoProgress()
            clockHandler.removeCallbacks(saveVideoProgress)
            playingVideo = null
            player?.stop()
            clearPlaybackError()
            clearLiveStreamNotice()
            playerView?.player = null
            playerView = null
            playerFrame = null
            playingArchiveProgramme = null
            val returnToPortal = portalPlaybackBack
            portalPlaybackBack = null
            if (returnToPortal != null) returnToPortal()
            else if (currentEntry?.type == "movies") showCatalog("movies")
            else if (currentEntry?.type == "music") showMusicHub()
            else if (currentEntry?.type == "radio") showRadioMenu()
            else showTvMenu()
        } else if (current == "vportal") {
            portalGeneration++
            portalBack?.invoke() ?: showCatalog("movies")
        } else if (current == "remote") {
            when (remoteReturnPage) {
                "settings_section" -> showSettings(if (remoteSource == "vportal") "vod_playlists" else null)
                "settings" -> showSettings()
                "tv_menu" -> showTvMenu()
                else -> showHome()
            }
        } else if (current == "video_history" || current == "movies") {
            showMediaHub()
        } else if (current == "tv_history") {
            showTvMenu()
        } else if (current == "settings_section") {
            showSettings()
        } else if (current == "tv" || current == "favorites") {
            showTvMenu()
        } else {
            showHome()
        }
    }

    override fun onStart() {
        super.onStart()
        playbackScreenStarted = true
        if (current == "home") {
            homePreviewPlayer?.play()
            networkSpeedView?.let(::startNetworkSpeedMonitor)
        }
        pendingPlaybackRetry?.let { clockHandler.post(it) }
        scheduleEpgRefresh()
        if (current == "player" && playingVideo != null) {
            clockHandler.removeCallbacks(saveVideoProgress)
            clockHandler.postDelayed(saveVideoProgress, 15000L)
        }
        if (resumePlaybackOnStart && current == "player") {
            player?.play()
            if (player?.playbackState == Player.STATE_BUFFERING) clockHandler.postDelayed(bufferTimeout, 25_000L)
        }
        resumePlaybackOnStart = false
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (android.os.Build.VERSION.SDK_INT >= 26 && current == "player" && moduleEnabled("pip") &&
            currentEntry?.type in setOf("tv", "movies")) {
            runCatching {
                val ratio = android.util.Rational(16, 9)
                enterPictureInPictureMode(android.app.PictureInPictureParams.Builder().setAspectRatio(ratio).build())
            }
        }
    }

    override fun onStop() {
        playbackScreenStarted = false
        playbackStartedAt = null
        homePreviewPlayer?.pause()
        networkSpeedMonitor?.let(clockHandler::removeCallbacks)
        pendingPlaybackRetry?.let { clockHandler.removeCallbacks(it) }
        if (videoPanelSide != null) closeSidePanel()
        clockHandler.removeCallbacks(bufferTimeout)
        clockHandler.removeCallbacks(saveVideoProgress)
        clockHandler.removeCallbacks(dailyEpgRefresh)
        resumePlaybackOnStart = current == "player" && player?.playWhenReady == true &&
            player?.playerError == null
        rememberVideoProgress()
        player?.pause()
        super.onStop()
    }

    override fun onDestroy() {
        internetPairGeneration++
        clockHandler.removeCallbacksAndMessages(null)
        stopHomePreview()
        mediaSession?.release()
        mediaSession = null
        remoteServer?.close()
        remoteRunning = false
        playerView?.player = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
