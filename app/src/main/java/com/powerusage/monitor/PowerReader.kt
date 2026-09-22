package com.powerusage.monitor

import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import kotlin.math.abs

/**
 * 读取电池瞬时电流与电压。
 *
 * BATTERY_PROPERTY_CURRENT_NOW 按文档单位是微安，但部分厂商返回毫安，
 * 符号约定也不统一，因此放电时取绝对值，并自动识别单位。
 */
class PowerReader(private val context: Context) {
    private val bm = context.getSystemService(BatteryManager::class.java)
    private val pm = context.getSystemService(PowerManager::class.java)

    private var smallSamples = 0

    /** 由电池广播更新 */
    @Volatile var voltageMv: Double = 0.0
    @Volatile var plugged: Boolean = false

    fun read(): Sample? {
        val raw = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        if (raw == Long.MIN_VALUE || raw == Int.MIN_VALUE.toLong()) return null
        if (voltageMv <= 0) return null

        val factorToUa = unitFactor(raw)
        val currentMa = abs(raw) * factorToUa / 1000.0

        val cc = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val ccUah = if (cc == Long.MIN_VALUE || cc == Int.MIN_VALUE.toLong() || cc <= 0) -1L else cc * factorToUa

        return Sample(
            elapsed = SystemClock.elapsedRealtime(),
            wall = System.currentTimeMillis(),
            currentMa = currentMa,
            voltageMv = voltageMv,
            powerMw = currentMa * voltageMv / 1000.0,
            screenOn = pm.isInteractive,
            charging = plugged,
            chargeCounterUah = ccUah,
        )
    }

    /** 返回把原始读数换算成微安的系数 */
    private fun unitFactor(raw: Long): Long {
        when (Settings.unitMode(context)) {
            Settings.UNIT_UA -> return 1
            Settings.UNIT_MA -> return 1000
        }
        when (Settings.detectedUnit(context)) {
            Settings.UNIT_UA -> return 1
            Settings.UNIT_MA -> return 1000
        }
        // 未确定：手机运行时电流几乎总是 > 10mA，
        // 读数 ≥ 10000 说明单位是微安；连续很多次都 < 10000 则判断为毫安。
        val a = abs(raw)
        if (a >= 10_000) {
            Settings.setDetectedUnit(context, Settings.UNIT_UA)
            return 1
        }
        if (a > 0 && !plugged && ++smallSamples >= 30) {
            Settings.setDetectedUnit(context, Settings.UNIT_MA)
        }
        return if (a in 1..9_999) 1000 else 1
    }
}
