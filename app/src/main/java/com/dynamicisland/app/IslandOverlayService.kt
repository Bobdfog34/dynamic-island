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
import android.graphics.Color
import android.graphics.PixelFormat
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

    private var timerEnd = 0L
    private val timerRunnable = object : Runnable {
        override fun run() {
            val left = timerEnd - System.currentTimeMillis()
            if (left <= 0) {
                showContent(IslandContent(IslandContent.Type.TIMER, "Таймер завершён", "0:00", emoji = "⏱"))
                handler.postDelayed({ restoreDefault() }, 4000)
            } else {
                val sec = (left / 1000).toInt()
                val m = sec / 60
                val s = sec % 60
                showContent(
                    IslandContent(
                        IslandContent.Type.TIMER,
                        "Таймер",
                        String.format("%d:%02d", m, s),
                        emoji = "⏱"
                    )
                )
                handler.postDelayed(this, 1000)
            }
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
                        showContent(
                            IslandContent(
                                IslandContent.Type.CHARGING,
                                "Зарядка",
                                "$pct%",
                                emoji = "⚡"
                            )
                        )
                    } else if (prefs.showBattery && !charging && pct in 1..15) {
                        showContent(
                            IslandContent(
                                IslandContent.Type.BATTERY_LOW,
                                "Низкий заряд",
                                "$pct%",
                                emoji = "🔋"
                            )
                        )
                    }
                    lastCharging = charging
                }
                Intent.ACTION_POWER_CONNECTED -> {
                    if (prefs.showCharging) {
                        showContent(
                            IslandContent(
                                IslandContent.Type.CHARGING,
                                "Зарядка",
                                "$lastBatteryLevel%",
                                emoji = "⚡"
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
            updateMusic(activeController)
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            updateMusic(activeController)
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
                if (!prefs.showNotifications) return START_STICKY
                val title = intent.getStringExtra("title") ?: return START_STICKY
                val text = intent.getStringExtra("text") ?: ""
                val pkg = intent.getStringExtra("pkg") ?: ""
                var icon: android.graphics.drawable.Drawable? = null
                if (pkg.isNotEmpty()) {
                    try {
                        icon = packageManager.getApplicationIcon(pkg)
                    } catch (_: Exception) {
                    }
                }
                showContent(
                    IslandContent(
                        IslandContent.Type.NOTIFICATION,
                        title,
                        text,
                        icon = icon,
                        emoji = "🔔"
                    )
                )
            }
            "ACTION_START_TIMER" -> {
                val minutes = intent.getIntExtra("minutes", 1)
                startTimer(minutes)
            }
            "ACTION_STOP_TIMER" -> stopTimer()
            "ACTION_CLEAR" -> islandView.clear()
        }
        return START_STICKY
    }

    private fun startTimer(minutes: Int) {
        timerEnd = System.currentTimeMillis() + minutes * 60_000L
        handler.removeCallbacks(timerRunnable)
        handler.post(timerRunnable)
    }

    private fun stopTimer() {
        handler.removeCallbacks(timerRunnable)
        restoreDefault()
    }

    fun showContent(content: IslandContent) {
        if (content.type == IslandContent.Type.MUSIC) lastMusic = content
        handler.post { islandView.showContent(content) }
    }

    private fun restoreDefault() {
        val music = lastMusic
        if (prefs.showMusic && music != null && activeController?.playbackState?.state == PlaybackState.STATE_PLAYING) {
            handler.post { islandView.showContent(music) }
        } else {
            handler.post { islandView.clear() }
        }
    }

    private fun startMusicListener() {
        try {
            mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val component = ComponentName(this, NotificationListener::class.java)
            mediaSessionManager?.addOnActiveSessionsChangedListener(sessionsChangedListener, component)
            bindController(mediaSessionManager?.getActiveSessions(component)?.firstOrNull())
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }
    }

    private fun bindController(controller: MediaController?) {
        if (activeController === controller) {
            updateMusic(controller)
            return
        }
        activeController?.unregisterCallback(controllerCallback)
        activeController = controller
        controller?.registerCallback(controllerCallback)
        updateMusic(controller)
    }

    private fun updateMusic(controller: MediaController?) {
        if (!prefs.showMusic) return
        if (controller == null) {
            lastMusic = null
            if (islandView.state() != IslandView.State.EXPANDED) islandView.clear()
            return
        }
        val metadata = controller.metadata ?: return
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING
        val content = IslandContent(
            IslandContent.Type.MUSIC,
            title,
            artist,
            emoji = if (playing) "🎵" else "⏸"
        )
        lastMusic = content
        if (islandView.state() != IslandView.State.EXPANDED) {
            handler.post { islandView.showContent(content) }
        }
    }

    override fun onToggled(expanded: Boolean) {
        if (!expanded) restoreDefault()
    }

    override fun onMoved(dx: Int, dy: Int) {
        params.x += dx
        params.y += dy
        try {
            windowManager.updateViewLayout(islandView, params)
        } catch (_: Exception) {
        }
    }

    override fun onLongPress() {
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionsChangedListener)
            } else {
                @Suppress("DEPRECATION")
                mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionsChangedListener)
            }
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
