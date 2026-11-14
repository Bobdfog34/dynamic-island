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
        IslandBus.service?.onListenerReady()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        connected = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return
        val prefs = Prefs(this)
        if (!prefs.showNotifications) return
        if (sbn.packageName == packageName) return
        if (sbn.isOngoing) return

        val notification = sbn.notification ?: return
        val flags = notification.flags
        if (flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (flags and Notification.FLAG_ONGOING_EVENT != 0) return

        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString()
            ?: return
        if (title.isBlank()) return

        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString()
            ?: extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.firstOrNull()?.toString()
            ?: ""

        val service = IslandBus.service
        if (service != null) {
            service.onNotificationReceived(title, text, sbn.packageName)
        } else {
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
}
