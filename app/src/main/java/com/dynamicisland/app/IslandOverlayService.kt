package com.dynamicisland.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

object IslandBus {
    var service: IslandOverlayService? = null
}

class IslandOverlayService : Service(), IslandView.Listener {

    companion object {
        const val CHANNEL_ID = "island_service_channel"
        const val NOTIF_ID = 1001
    }

    private lateinit var windowManager: WindowManager
    private lateinit var islandView: IslandView
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())

    private var lastMusic: IslandContent? = null
    private var lastCharging = false
    private var lastBatteryLevel = -1

    private class Agg(
        val pkg: String,
        var appLabel: String,
        var icon: Drawable?,
        val lines: LinkedHashMap<String, String>,
        var pendingIntent: PendingIntent? = null
    )

    private val aggregates = LinkedHashMap<String, Agg>()

    private var currentAppPackage: String = ""
    private var currentPendingIntent: PendingIntent? = null

    private var timerEnd = 0L
    private var timerTotal = 0L
    private val timerRunnable = object : Runnable {
        override fun run() {
            currentAppPackage = ""
            currentPendingIntent = null
            val left = timerEnd - System.currentTimeMillis()
            if (left <= 0) {
                islandView.show(
                    IslandContent(
                        IslandContent.Type.TIMER, "Таймер", "0:00",
                        emoji = "⏰", durationMs = timerTotal, positionMs = 0L
                    )
                )
                handler.postDelayed({ restoreDefault() }, 4000)
            } else {
                val sec = (left / 1000).toInt()
                val m = sec / 60
                val s = sec % 60
                islandView.show(
                    IslandContent(
                        IslandContent.Type.TIMER,
                        "Таймер",
                        String.format("%d:%02d", m, s),
                        emoji = "⏱",
                        durationMs = timerTotal,
                        positionMs = left
                    )
                )
                handler.postDelayed(this, 1000)
            }
        }
    }

    private val progressTicker = object : Runnable {
        override fun run() {
            val c = activeController
            if (prefs.showMusic && c != null &&
                c.playbackState?.state == PlaybackState.STATE_PLAYING &&
                islandView.currentType() == IslandContent.Type.MUSIC
            ) {
                buildMusicContent(c)?.let { islandView.refresh(it) }
            }
            handler.postDelayed(this, 1000)
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                    val pct = if (scale > 0) level * 100 / scale else -1
                    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                            status == BatteryManager.BATTERY_STATUS_FULL
                    lastBatteryLevel = pct
                    if (prefs.showCharging && charging && !lastCharging) {
                        islandView.show(
                            IslandContent(
                                IslandContent.Type.CHARGING,
                                "Зарядка",
                                "$pct%",
                                emoji = "⚡",
                                progress = pct
                            )
                        )
                    } else if (prefs.showBattery && !charging && pct in 1..15) {
                        islandView.show(
                            IslandContent(
                                IslandContent.Type.BATTERY_LOW,
                                "Низкий заряд",
                                "$pct%",
                                emoji = "🔋",
                                progress = pct
                            )
                        )
                    }
                    lastCharging = charging
                }
                Intent.ACTION_POWER_CONNECTED -> {
                    if (prefs.showCharging) {
                        islandView.show(
                            IslandContent(
                                IslandContent.Type.CHARGING,
                                "Зарядка",
                                "$lastBatteryLevel%",
                                emoji = "⚡",
                                progress = lastBatteryLevel
                            )
                        )
                    }
                    lastCharging = true
                }
                Intent.ACTION_POWER_DISCONNECTED -> lastCharging = false
            }
        }
    }

    private var mediaSessionManager: MediaSessionManager? = null
    private val sessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            bindController(controllers?.firstOrNull())
        }
    private var activeController: MediaController? = null
    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            val c = activeController
            val content = c?.let { buildMusicContent(it) }
            lastMusic = content
            if (content != null && prefs.showMusic && islandView.state() != IslandView.State.EXPANDED) {
                islandView.show(content)
            }
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            val c = activeController
            val content = c?.let { buildMusicContent(it) }
            lastMusic = content
            if (content != null && prefs.showMusic && islandView.state() != IslandView.State.EXPANDED) {
                islandView.show(content)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        IslandBus.service = this
        prefs = Prefs(this)
        createChannel()
        startForegroundSafely()

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        islandView = IslandView(this)
        islandView.prefs = prefs
        islandView.listener = this

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        params.x = prefs.offsetX
        params.y = prefs.offsetY

        try {
            windowManager.addView(islandView, params)
        } catch (e: Exception) {
            stopSelf()
            return
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        ContextCompat.registerReceiver(
            this, batteryReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )

        if (prefs.showMusic) startMusicListener()
        handler.postDelayed(progressTicker, 1000)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.service_channel_name),
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = getString(R.string.service_channel_desc)
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun startForegroundSafely() {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_island)
            .setContentTitle(getString(R.string.service_title))
            .setContentText(getString(R.string.service_text))
            .setContentIntent(openIntent)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            NotificationListener.ACTION_FORWARD -> {
                val title = intent.getStringExtra("title") ?: return START_STICKY
                val text = intent.getStringExtra("text") ?: ""
                val pkg = intent.getStringExtra("pkg") ?: ""
                val appLabel = intent.getStringExtra("appLabel") ?: ""
                val key = intent.getStringExtra("key") ?: ""
                @Suppress("DEPRECATION")
                val contentIntent = intent.getParcelableExtra<PendingIntent>("contentIntent")
                onNotificationReceived(title, text, pkg, appLabel, key, contentIntent)
            }
            "ACTION_START_TIMER" -> startTimer(intent.getIntExtra("minutes", 1))
            "ACTION_STOP_TIMER" -> stopTimer()
            "ACTION_CLEAR" -> islandView.clear()
        }
        return START_STICKY
    }

    fun onNotificationReceived(
        title: String,
        text: String,
        pkg: String,
        appLabel: String = "",
        key: String = "",
        contentIntent: PendingIntent? = null
    ) {
        if (!prefs.showNotifications) return
        if (title.isBlank() && text.isBlank()) return

        var icon: Drawable? = null
        if (pkg.isNotEmpty()) {
            try {
                icon = packageManager.getApplicationIcon(pkg)
            } catch (_: Exception) {
            }
        }

        val agg = aggregates.getOrPut(pkg) {
            Agg(pkg, appLabel, icon, LinkedHashMap())
        }
        if (appLabel.isNotBlank()) agg.appLabel = appLabel
        if (icon != null) agg.icon = icon
        if (contentIntent != null) agg.pendingIntent = contentIntent
        currentAppPackage = pkg
        currentPendingIntent = agg.pendingIntent

        val line = when {
            title.isNotBlank() && text.isNotBlank() -> "$title: $text"
            title.isNotBlank() -> title
            else -> text
        }
        val lineKey = key.ifBlank { line }
        agg.lines[lineKey] = line
        while (agg.lines.size > 30) {
            val it = agg.lines.entries.iterator()
            if (it.hasNext()) {
                it.next()
                it.remove()
            }
        }

        handler.post { islandView.showNotification(buildNotifContent(agg)) }
    }

    private fun buildNotifContent(agg: Agg): IslandContent {
        val all = agg.lines.values.toList()
        val count = all.size
        val recent = all.takeLast(5).reversed()
        val subtitle = if (count > 1) "$count новых сообщений" else (all.lastOrNull() ?: "")
        return IslandContent(
            IslandContent.Type.NOTIFICATION,
            agg.appLabel.ifBlank { "Уведомления" },
            subtitle,
            icon = agg.icon,
            emoji = if (agg.icon == null) "🔔" else "",
            appLabel = agg.appLabel,
            lines = recent,
            key = agg.pkg,
            count = count
        )
    }

    fun onListenerReady() {
        if (prefs.showMusic) startMusicListener()
    }

    private fun startTimer(minutes: Int) {
        timerTotal = minutes * 60_000L
        timerEnd = System.currentTimeMillis() + timerTotal
        handler.removeCallbacks(timerRunnable)
        handler.post(timerRunnable)
    }

    private fun stopTimer() {
        handler.removeCallbacks(timerRunnable)
        restoreDefault()
    }

    private fun restoreDefault() {
        val music = lastMusic
        if (prefs.showMusic && music != null &&
            activeController?.playbackState?.state == PlaybackState.STATE_PLAYING
        ) {
            handler.post { islandView.show(music) }
        } else {
            handler.post { islandView.clear() }
        }
    }

    private fun startMusicListener() {
        try {
            if (mediaSessionManager == null) {
                mediaSessionManager =
                    getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            }
            val component = ComponentName(this, NotificationListener::class.java)
            mediaSessionManager?.addOnActiveSessionsChangedListener(sessionsChangedListener, component)
            bindController(mediaSessionManager?.getActiveSessions(component)?.firstOrNull())
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }
    }

    private fun bindController(controller: MediaController?) {
        if (activeController === controller) {
            return
        }
        activeController?.unregisterCallback(controllerCallback)
        activeController = controller
        controller?.registerCallback(controllerCallback)
        val content = controller?.let { buildMusicContent(it) }
        lastMusic = content
        if (content != null && prefs.showMusic && islandView.state() != IslandView.State.EXPANDED) {
            handler.post { islandView.show(content) }
        } else if (controller == null && prefs.showMusic &&
            islandView.state() != IslandView.State.EXPANDED
        ) {
            islandView.clear()
        }
    }

    private fun buildMusicContent(controller: MediaController): IslandContent? {
        val metadata = controller.metadata ?: return null
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return null
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING
        val position = controller.playbackState?.position ?: 0L
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val art: Drawable? = try {
            val bmp = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            bmp?.let { BitmapDrawable(resources, it) }
        } catch (_: Exception) {
            null
        }
        val appLabel = try {
            val ai = packageManager.getApplicationInfo(controller.packageName, 0)
            packageManager.getApplicationLabel(ai).toString()
        } catch (_: Exception) {
            ""
        }
        currentAppPackage = controller.packageName
        currentPendingIntent = null
        return IslandContent(
            IslandContent.Type.MUSIC,
            title,
            artist,
            emoji = if (playing) "🎵" else "⏸",
            appLabel = appLabel,
            albumArt = art,
            isPlaying = playing,
            positionMs = position,
            durationMs = duration
        )
    }

    override fun onIdle() {
        aggregates.clear()
        restoreDefault()
    }

    override fun onMoved(dx: Int, dy: Int) {
        params.x += dx
        params.y += dy
        try {
            windowManager.updateViewLayout(islandView, params)
        } catch (_: Exception) {
        }
    }

    override fun onExpandToggle() {
        handler.post { islandView.toggle() }
    }

    override fun onTap() {
        val pi = currentPendingIntent
        if (pi != null) {
            try {
                pi.send()
                return
            } catch (_: Exception) {
            }
        }
        val pkg = currentAppPackage
        if (pkg.isNotEmpty()) {
            val li = packageManager.getLaunchIntentForPackage(pkg)
            if (li != null) {
                li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    startActivity(li)
                    return
                } catch (_: Exception) {
                }
            }
        }
        openSettings()
    }

    private fun openSettings() {
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (_: Exception) {
        }
    }

    override fun onMediaAction(action: Int) {
        val controller = activeController ?: return
        val tc = controller.transportControls
        try {
            when (action) {
                IslandView.MEDIA_PREV -> tc.skipToPrevious()
                IslandView.MEDIA_PLAY_PAUSE -> {
                    if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
                        tc.pause()
                    } else {
                        tc.play()
                    }
                }
                IslandView.MEDIA_NEXT -> tc.skipToNext()
            }
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        try {
            unregisterReceiver(batteryReceiver)
        } catch (_: Exception) {
        }
        try {
            activeController?.unregisterCallback(controllerCallback)
            mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionsChangedListener)
        } catch (_: Exception) {
        }
        try {
            windowManager.removeView(islandView)
        } catch (_: Exception) {
        }
        IslandBus.service = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }
}
