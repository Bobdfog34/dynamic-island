package com.dynamicisland.app

import android.graphics.drawable.Drawable

data class IslandContent(
    val type: Type,
    val title: String,
    val subtitle: String = "",
    val emoji: String = "",
    val icon: Drawable? = null,
    val progress: Int = -1,
    val appLabel: String = "",
    val albumArt: Drawable? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val lines: List<String> = emptyList(),
    val key: String = "",
    val count: Int = 0,
    val timestamp: Long = System.currentTimeMillis()
) {
    enum class Type {
        IDLE, NOTIFICATION, MUSIC, CHARGING, BATTERY_LOW, TIMER, CALL
    }

    fun progressFraction(): Float {
        if (durationMs <= 0L) return -1f
        return (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    }
}
