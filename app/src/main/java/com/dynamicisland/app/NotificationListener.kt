package com.dynamicisland.app

import android.app.Notification
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class NotificationListener : NotificationListenerService() {

    companion object {
        const val ACTION_FORWARD = "com.dynamicisland.app.FORWARD_NOTIFICATION"
        var connected = false
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = true
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        connected = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return
        if (IslandBus.service == null) return
        val prefs = Prefs(this)
        if (!prefs.showNotifications) return

        val notification = sbn.notification ?: return
        if (sbn.packageName == packageName) return
        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return

        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: ""

        val intent = Intent(this, IslandOverlayService::class.java).apply {
            action = ACTION_FORWARD
            putExtra("title", title)
            putExtra("text", text)
            putExtra("pkg", sbn.packageName)
        }
        try {
            startService(intent)
        } catch (_: Exception) {
        }
    }
}
