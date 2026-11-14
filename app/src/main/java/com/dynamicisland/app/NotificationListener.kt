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
        if (sbn.packageName == packageName) return

        val notification = sbn.notification ?: return
        val flags = notification.flags
        val ongoing = sbn.isOngoing ||
                (flags and Notification.FLAG_GROUP_SUMMARY != 0) ||
                (flags and Notification.FLAG_ONGOING_EVENT != 0)
        val clearable = sbn.isClearable

        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString()
            ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString()
            ?: ""
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.mapNotNull { it?.toString() }
            ?.filter { it.isNotBlank() }
            ?: emptyList()

        val hasContent = title.isNotBlank() || text.isNotBlank() || lines.isNotEmpty()

        // Block the system notification so it only appears in the island.
        val block = prefs.blockSystemNotifications &&
                prefs.showNotifications &&
                !prefs.isProtected(sbn.packageName) &&
                !ongoing && clearable && hasContent
        if (block) {
            try {
                cancelNotification(sbn.key)
            } catch (_: Exception) {
            }
        }

        if (!prefs.showNotifications) return
        if (ongoing) return
        if (!hasContent) return

        val finalTitle = title.ifBlank { lines.firstOrNull() ?: "" }
        val finalText = text.ifBlank { lines.drop(1).firstOrNull() ?: "" }
        val appLabel = try {
            val ai = packageManager.getApplicationInfo(sbn.packageName, 0)
            packageManager.getApplicationLabel(ai).toString()
        } catch (_: Exception) {
            ""
        }

        val contentIntent = notification.contentIntent

        val service = IslandBus.service
        if (service != null) {
            service.onNotificationReceived(
                finalTitle, finalText, sbn.packageName, appLabel, sbn.key, contentIntent
            )
        } else {
            val intent = Intent(this, IslandOverlayService::class.java).apply {
                action = ACTION_FORWARD
                putExtra("title", finalTitle)
                putExtra("text", finalText)
                putExtra("pkg", sbn.packageName)
                putExtra("appLabel", appLabel)
                putExtra("key", sbn.key)
                putExtra("contentIntent", contentIntent)
                putStringArrayListExtra("lines", ArrayList(lines))
            }
            try {
                startService(intent)
            } catch (_: Exception) {
            }
        }
    }
}
