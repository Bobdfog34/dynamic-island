package com.dynamicisland.app

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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

    companion object {
        private const val REPO = "Bobdfog34/dynamic-island"
        private const val VIDEO_URL =
            "https://github.com/Bobdfog34/dynamic-island/releases/latest/download/instruction.mp4"
        private const val CHANGELOG =
            "Что нового в 3.1:\n" +
                    "• Уведомления группируются: 35 сообщений — один блок со списком\n" +
                    "• Исправлена загрузка обновлений прямо в приложении\n" +
                    "• Блокировка уведомлений: приходят только в остров\n" +
                    "• Список приложений-исключений\n" +
                    "• Музыка: обложка, прогресс и кнопки управления"
    }

    private lateinit var prefs: Prefs
    private var updatingUi = false

    private lateinit var tvStatus: TextView
    private lateinit var swEnable: SwitchCompat
    private lateinit var swBlock: SwitchCompat
    private lateinit var tvVersion: TextView
    private lateinit var tvUpdateStatus: TextView
    private lateinit var tvChangelog: TextView
    private lateinit var btnDownloadUpdate: Button

    private val updater = Updater(REPO)
    private var latestApkUrl: String? = null
    private var pendingDownloadId = -1L

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id == pendingDownloadId) {
                installApk(id)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        tvStatus = findViewById(R.id.tvStatus)
        swEnable = findViewById(R.id.swEnable)
        swBlock = findViewById(R.id.swBlock)
        tvVersion = findViewById(R.id.tvVersion)
        tvUpdateStatus = findViewById(R.id.tvUpdateStatus)
        tvChangelog = findViewById(R.id.tvChangelog)
        btnDownloadUpdate = findViewById(R.id.btnDownloadUpdate)

        findViewById<Button>(R.id.btnOverlay).setOnClickListener { requestOverlay() }
        findViewById<Button>(R.id.btnNotifAccess).setOnClickListener { openNotifAccess() }

        swEnable.setOnCheckedChangeListener { _, checked ->
            if (updatingUi) return@setOnCheckedChangeListener
            if (checked) startIsland() else stopIsland()
        }

        swBlock.setOnCheckedChangeListener { _, checked ->
            if (updatingUi) return@setOnCheckedChangeListener
            if (checked && !hasNotificationAccess()) {
                toast("Сначала дай доступ к уведомлениям (кнопка выше)")
                updatingUi = true
                swBlock.isChecked = false
                updatingUi = false
                return@setOnCheckedChangeListener
            }
            prefs.blockSystemNotifications = checked
            toast(
                if (checked) "Теперь уведомления приходят только в остров"
                else "Блокировка уведомлений выключена"
            )
        }

        findViewById<Button>(R.id.btnApps).setOnClickListener {
            startActivity(Intent(this, AppListActivity::class.java))
        }

        bindSwitch(R.id.swNotifications) { prefs.showNotifications = it }
        bindSwitch(R.id.swMusic) { prefs.showMusic = it }
        bindSwitch(R.id.swBattery) { prefs.showBattery = it }
        bindSwitch(R.id.swCharging) { prefs.showCharging = it }
        bindSwitch(R.id.swBoot) { prefs.startOnBoot = it }
        bindSwitch(R.id.swBorder) { prefs.borderEnabled = it; applyNow() }
        bindSwitch(R.id.swGlow) { prefs.glowEnabled = it; applyNow() }
        bindSwitch(R.id.swSheen) { prefs.sheenEnabled = it; applyNow() }
        bindSwitch(R.id.swAppLabel) { prefs.showAppLabel = it }

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

        findViewById<Button>(R.id.btnCheckUpdate).setOnClickListener { checkUpdates() }
        findViewById<Button>(R.id.btnVideo).setOnClickListener { openUrl(VIDEO_URL) }
        btnDownloadUpdate.setOnClickListener {
            val url = latestApkUrl
            if (url != null) downloadUpdate(url) else toast("Нет ссылки на обновление")
        }

        tvVersion.text = "Dynamic Island  v${versionName()}"
        tvChangelog.text = CHANGELOG

        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        ContextCompat.registerReceiver(
            this, downloadReceiver, filter, ContextCompat.RECEIVER_EXPORTED
        )

        requestNotifPermission()
        checkUpdates()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        loadUiFromPrefs()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(downloadReceiver)
        } catch (_: Exception) {
        }
    }

    private fun versionName(): String {
        return try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (_: Exception) {
            "?"
        }
    }

    private fun hasNotificationAccess(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    private fun checkUpdates() {
        tvUpdateStatus.text = "Проверка обновлений…"
        btnDownloadUpdate.visibility = Button.GONE
        updater.check(versionName()) { info ->
            if (info == null) {
                tvUpdateStatus.text = "У вас последняя версия ✅"
                tvChangelog.text = CHANGELOG
            } else {
                tvUpdateStatus.text = "Доступно обновление v${info.versionName}"
                if (info.notes.isNotBlank()) {
                    tvChangelog.text = "Что нового в ${info.versionName}:\n" + info.notes.trim()
                }
                latestApkUrl = info.apkUrl ?: info.pageUrl
                btnDownloadUpdate.visibility = Button.VISIBLE
            }
        }
    }

    private fun downloadUpdate(url: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            toast("Разреши установку приложений из этого источника")
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (_: Exception) {
            }
            return
        }
        try {
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle("Dynamic Island")
                .setDescription("Загрузка обновления…")
                .setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                .setMimeType("application/vnd.android.package-archive")
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            pendingDownloadId = dm.enqueue(request)
            latestApkUrl = url
            tvUpdateStatus.text = "Загрузка обновления…"
            toast("Загрузка началась")
        } catch (e: Exception) {
            toast("Не удалось запустить загрузку, открываю в браузере")
            openUrl(url)
        }
    }

    private fun installApk(id: Long) {
        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cursor = dm.query(DownloadManager.Query().setFilterById(id))
        val status = if (cursor != null && cursor.moveToFirst()) {
            cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS))
        } else {
            -1
        }
        cursor?.close()

        if (status != DownloadManager.STATUS_SUCCESSFUL) {
            toast("Загрузка не удалась, открываю в браузере")
            latestApkUrl?.let { openUrl(it) }
            return
        }

        val uri = dm.getUriForDownloadedFile(id)
        if (uri == null) {
            toast("Не удалось открыть файл обновления")
            latestApkUrl?.let { openUrl(it) }
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
            tvUpdateStatus.text = "Установите обновление из открывшегося окна"
        } catch (e: Exception) {
            toast("Откройте скачанный файл вручную")
        }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            toast("Не удалось открыть ссылку")
        }
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
            R.id.swGlow -> prefs.glowEnabled
            R.id.swSheen -> prefs.sheenEnabled
            R.id.swAppLabel -> prefs.showAppLabel
            else -> false
        }
        sw.setOnCheckedChangeListener { _, checked -> onChange(checked) }
    }

    private fun loadUiFromPrefs() {
        updatingUi = true
        swEnable.isChecked = IslandBus.service != null
        swBlock.isChecked = prefs.blockSystemNotifications
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
        val notif = hasNotificationAccess()
        val running = IslandBus.service != null
        val blocking = prefs.blockSystemNotifications
        tvStatus.text = buildString {
            append("Поверх окон: ")
            append(if (overlay) "✅" else "❌")
            append("\nДоступ к уведомлениям: ")
            append(if (notif) "✅" else "❌")
            append("\nСервис острова: ")
            append(if (running) "работает ✅" else "остановлен ⏹")
            append("\nБлокировка уведомлений: ")
            append(if (blocking) "включена 🔕" else "выключена")
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
            putExtra("appLabel", "Сообщения")
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
