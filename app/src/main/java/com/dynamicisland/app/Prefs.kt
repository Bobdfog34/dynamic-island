package com.dynamicisland.app

import android.content.Context
import android.graphics.Color

class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("dynamic_island_prefs", Context.MODE_PRIVATE)

    var showNotifications: Boolean
        get() = sp.getBoolean("show_notifications", true)
        set(v) = sp.edit().putBoolean("show_notifications", v).apply()

    var showMusic: Boolean
        get() = sp.getBoolean("show_music", true)
        set(v) = sp.edit().putBoolean("show_music", v).apply()

    var showBattery: Boolean
        get() = sp.getBoolean("show_battery", true)
        set(v) = sp.edit().putBoolean("show_battery", v).apply()

    var showCharging: Boolean
        get() = sp.getBoolean("show_charging", true)
        set(v) = sp.edit().putBoolean("show_charging", v).apply()

    var startOnBoot: Boolean
        get() = sp.getBoolean("start_on_boot", true)
        set(v) = sp.edit().putBoolean("start_on_boot", v).apply()

    var collapsedWidth: Int
        get() = sp.getInt("collapsed_width", 112)
        set(v) = sp.edit().putInt("collapsed_width", v).apply()

    var collapsedHeight: Int
        get() = sp.getInt("collapsed_height", 34)
        set(v) = sp.edit().putInt("collapsed_height", v).apply()

    var cornerRadius: Int
        get() = sp.getInt("corner_radius", 24)
        set(v) = sp.edit().putInt("corner_radius", v).apply()

    var offsetY: Int
        get() = sp.getInt("offset_y", 8)
        set(v) = sp.edit().putInt("offset_y", v).apply()

    var offsetX: Int
        get() = sp.getInt("offset_x", 0)
        set(v) = sp.edit().putInt("offset_x", v).apply()

    var bgColor: Int
        get() = sp.getInt("bg_color", Color.BLACK)
        set(v) = sp.edit().putInt("bg_color", v).apply()

    var bgAlpha: Int
        get() = sp.getInt("bg_alpha", 255)
        set(v) = sp.edit().putInt("bg_alpha", v).apply()

    var animDuration: Int
        get() = sp.getInt("anim_duration", 320)
        set(v) = sp.edit().putInt("anim_duration", v).apply()

    var autoCollapseMs: Int
        get() = sp.getInt("auto_collapse", 4000)
        set(v) = sp.edit().putInt("auto_collapse", v).apply()

    var borderEnabled: Boolean
        get() = sp.getBoolean("border_enabled", true)
        set(v) = sp.edit().putBoolean("border_enabled", v).apply()

    var glowEnabled: Boolean
        get() = sp.getBoolean("glow_enabled", true)
        set(v) = sp.edit().putBoolean("glow_enabled", v).apply()
}
