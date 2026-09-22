package com.powerusage.monitor

import java.util.Locale

object Format {
    fun power(mw: Double?): String = when {
        mw == null -> "—"
        mw >= 1000 -> String.format(Locale.US, "%.2f W", mw / 1000)
        else -> String.format(Locale.US, "%.0f mW", mw)
    }

    fun duration(ms: Long): String {
        val totalMin = ms / MINUTE_MS
        val d = totalMin / (24 * 60)
        val h = totalMin / 60 % 24
        val m = totalMin % 60
        return when {
            d > 0 -> "${d}天${h}小时${m}分"
            h > 0 -> "${h}小时${m}分"
            else -> "${m}分"
        }
    }

    fun percent(part: Long, whole: Long): String =
        if (whole <= 0) "—" else String.format(Locale.US, "%.0f%%", 100.0 * part / whole)

    fun mwh(v: Double): String = String.format(Locale.US, "%.0f mWh", v)

    /** 能量，≥1 Wh 时以 Wh 显示 */
    fun energy(mwh: Double): String = when {
        mwh >= 1000 -> String.format(Locale.US, "%.2f Wh", mwh / 1000)
        mwh >= 10 -> String.format(Locale.US, "%.0f mWh", mwh)
        else -> String.format(Locale.US, "%.1f mWh", mwh)
    }

    /** 按电压折算成电池容量 mAh（近似） */
    fun mah(mwh: Double, voltageMv: Double?): String =
        if (voltageMv == null || voltageMv <= 0) "" else String.format(Locale.US, "≈%.0f mAh", mwh / (voltageMv / 1000))
}
