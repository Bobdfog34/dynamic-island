package com.dynamicisland.app

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private var updatingUi = false

    private lateinit var tvStatus: TextView
    private lateinit var swEnable: SwitchCompat

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        tvStatus = findViewById(R.id.tvStatus)
        swEnable = findViewById(R.id.swEnable)

        findViewById<Button>(R.id.btnOverlay).setOnClickListener { requestOverlay() }
        findViewById<Button>(R.id.btnNotifAccess).setOnClickListener { openNotifAccess() }

        swEnable.setOnCheckedChangeListener { _, checked ->
            if (updatingUi) return@setOnCheckedChangeListener
            if (checked) startIsland() else stopIsland()
        }

        bindSwitch(R.id.swNotifications) { prefs.showNotifications = it }
        bindSwitch(R.id.swMusic) { prefs.showMusic = it }
        bindSwitch(R.id.swBattery) { prefs.showBattery = it }
        bindSwitch(R.id.swCharging) { prefs.showCharging = it }
        bindSwitch(R.id.swBoot) { prefs.startOnBoot = it }
        bindSwitch(R.id.swBorder) { prefs.borderEnabled = it; applyNow() }

        setupSeek(
            R.id.sbWidth, R.id.tvWidth, prefs.collapsedWidth, 80, 220, "Ширина: %d dp"
        ) { prefs.collapsedWidth = it }
        setupSeek(
            R.id.sbHeight, R.id.tvHeight, prefs.collapsedHeight, 24, 80, "Высота: %d dp"
        ) { prefs.collapsedHeight = it }
        setupSeek(
            R.id.sbRadius, R.id.tvRadius, prefs.cornerRadius, 0, 60, "Скругление: %d dp"
        ) { prefs.cornerRadius = it }
        setupSeek(
            R.id.sbOffsetY, R.id.tvOffsetY, prefs.offsetY, 0, 200, "Отступ сверху: %d dp"
        ) { prefs.offsetY = it }
        setupSeek(
            R.id.sbAlpha, R.id.tvAlpha, prefs.bgAlpha, 40, 255, "Прозрачность: %d"
        ) { prefs.bgAlpha = it }

        findViewById<Button>(R.id.btnColorBlack).setOnClickListener { setColor(Color.BLACK) }
        findViewById<Button>(R.id.btnColorBlue).setOnClickListener { setColor(Color.parseColor("#0A84FF")) }
        findViewById<Button>(R.id.btnColorGray).setOnClickListener { setColor(Color.parseColor("#1C1C1E")) }

        findViewById<Button>(R.id.btnTestNotif).setOnClickListener { sendTestNotification() }
        findViewById<Button>(R.id.btnTimer1).setOnClickListener { startTimer(1) }
        findViewById<Button>(R.id.btnTimerStop).setOnClickListener { stopTimer() }
        findViewById<Button>(R.id.btnApply).setOnClickListener { applyNow(); toast("Настройки применены") }

        requestNotifPermission()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        loadUiFromPrefs()
    }

    private fun bindSwitch(id: Int, onChange: (Boolean) -> Unit) {
        val sw = findViewById<SwitchCompat>(id)
        sw.isChecked = when (id) {
            R.id.swNotifications -> prefs.showNotifications
            R.id.swMusic -> prefs.showMusic
            R.id.swBattery -> prefs.showBattery
            R.id.swCharging -> prefs.showCharging
            R.id.swBoot -> prefs.startOnBoot
            R.id.swBorder -> prefs.borderEnabled
            else -> false
        }
        sw.setOnCheckedChangeListener { _, checked -> onChange(checked) }
    }

    private fun loadUiFromPrefs() {
        updatingUi = true
        swEnable.isChecked = IslandBus.service != null
        updatingUi = false
    }

    private fun setupSeek(
        seekId: Int,
        labelId: Int,
        value: Int,
        min: Int,
        max: Int,
        format: String,
        onSet: (Int) -> Unit
    ) {
        val label = findViewById<TextView>(labelId)
        val seek = findViewById<SeekBar>(seekId)
        val range = max - min
        seek.max = range
        seek.progress = (value - min).coerceIn(0, range)
        label.text = String.format(format, value)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val v = min + progress
                label.text = String.format(format, v)
                onSet(v)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                applyNow()
            }
        })
    }

    private fun setColor(color: Int) {
        prefs.bgColor = color
        applyNow()
    }

    private fun applyNow() {
        if (IslandBus.service != null) {
            restartService()
        }
    }

    private fun restartService() {
        stopService(Intent(this, IslandOverlayService::class.java))
        if (Settings.canDrawOverlays(this)) {
            startIslandService()
        }
    }

    private fun updateStatus() {
        val overlay = Settings.canDrawOverlays(this)
        val notif = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        val running = IslandBus.service != null
        tvStatus.text = buildString {
            append("Поверх окон: ")
            append(if (overlay) "✅" else "❌")
            append("\nДоступ к уведомлениям: ")
            append(if (notif) "✅" else "❌")
            append("\nСервис острова: ")
            append(if (running) "работает ✅" else "остановлен ⏹")
        }
    }

    private fun requestOverlay() {
        if (Settings.canDrawOverlays(this)) {
            toast("Уже разрешено")
            return
        }
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    private fun openNotifAccess() {
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 10)
            }
        }
    }

    private fun startIsland() {
        if (!Settings.canDrawOverlays(this)) {
            toast("Сначала разреши показ поверх других окон")
            swEnable.isChecked = false
            requestOverlay()
            return
        }
        startIslandService()
        toast("Остров включён")
    }

    private fun startIslandService() {
        val intent = Intent(this, IslandOverlayService::class.java)
        ContextCompat.startForegroundService(this, intent)
        tvStatus.postDelayed({ updateStatus() }, 400)
    }

    private fun stopIsland() {
        stopService(Intent(this, IslandOverlayService::class.java))
        toast("Остров выключен")
        tvStatus.postDelayed({ updateStatus() }, 400)
    }

    private fun sendTestNotification() {
        val intent = Intent(this, IslandOverlayService::class.java).apply {
            action = NotificationListener.ACTION_FORWARD
            putExtra("title", "Сообщение")
            putExtra("text", "Привет! Это тестовое уведомление в острове 🎉")
            putExtra("pkg", "com.android.systemui")
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun startTimer(minutes: Int) {
        val intent = Intent(this, IslandOverlayService::class.java).apply {
            action = "ACTION_START_TIMER"
            putExtra("minutes", minutes)
        }
        ContextCompat.startForegroundService(this, intent)
        toast("Таймер на $minutes мин")
    }

    private fun stopTimer() {
        val intent = Intent(this, IslandOverlayService::class.java).apply {
            action = "ACTION_STOP_TIMER"
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
