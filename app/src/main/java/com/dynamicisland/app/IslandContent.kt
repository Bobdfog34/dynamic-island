package com.dynamicisland.app

import android.graphics.drawable.Drawable

data class IslandContent(
    val type: Type,
    val title: String,
    val subtitle: String = "",
    val emoji: String = "",
    val icon: Drawable? = null,
    val progress: Int = -1,
    val timestamp: Long = System.currentTimeMillis()
) {
    enum class Type {
        IDLE, NOTIFICATION, MUSIC, CHARGING, BATTERY_LOW, TIMER, CALL
    }
}
