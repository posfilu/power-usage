package com.powerusage.monitor

import android.content.Context

object Settings {
    const val UNIT_AUTO = 0
    const val UNIT_UA = 1
    const val UNIT_MA = 2

    private fun prefs(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** 用户选择的电流单位：自动 / 微安 / 毫安 */
    fun unitMode(c: Context) = prefs(c).getInt("unit_mode", UNIT_AUTO)
    fun setUnitMode(c: Context, v: Int) = prefs(c).edit().putInt("unit_mode", v).apply()

    /** 自动检测出的单位（仅在 UNIT_AUTO 下使用） */
    fun detectedUnit(c: Context) = prefs(c).getInt("detected_unit", UNIT_AUTO)
    fun setDetectedUnit(c: Context, v: Int) = prefs(c).edit().putInt("detected_unit", v).apply()

    /** 用户是否希望监测处于开启状态（决定开机是否自启） */
    fun monitoringEnabled(c: Context) = prefs(c).getBoolean("enabled", false)
    fun setMonitoringEnabled(c: Context, v: Boolean) = prefs(c).edit().putBoolean("enabled", v).apply()
}
