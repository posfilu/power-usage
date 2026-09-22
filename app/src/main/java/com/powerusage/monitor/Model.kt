package com.powerusage.monitor

/** 一次瞬时采样。 */
data class Sample(
    /** SystemClock.elapsedRealtime()，毫秒，含深度睡眠时间 */
    val elapsed: Long,
    /** System.currentTimeMillis()，毫秒 */
    val wall: Long,
    val currentMa: Double,
    val voltageMv: Double,
    val powerMw: Double,
    val screenOn: Boolean,
    val charging: Boolean,
    /** 电量计剩余电荷，微安时；不支持时为 -1 */
    val chargeCounterUah: Long,
)

/** 一分钟（按墙钟对齐）内累计的能量与时长。 */
data class MinuteBucket(
    val minuteStart: Long,
    var energyMj: Double = 0.0,
    var measuredMs: Long = 0,
    var screenEnergyMj: Double = 0.0,
    var screenMs: Long = 0,
    var chargingMs: Long = 0,
) {
    fun add(o: MinuteBucket) {
        energyMj += o.energyMj
        measuredMs += o.measuredMs
        screenEnergyMj += o.screenEnergyMj
        screenMs += o.screenMs
        chargingMs += o.chargingMs
    }

    fun toTotals() = Totals(energyMj, measuredMs, screenEnergyMj, screenMs, chargingMs)
}

/** 任意时间段的汇总。mJ / s = mW。 */
data class Totals(
    val energyMj: Double = 0.0,
    val measuredMs: Long = 0,
    val screenEnergyMj: Double = 0.0,
    val screenMs: Long = 0,
    val chargingMs: Long = 0,
) {
    operator fun plus(o: Totals) = Totals(
        energyMj + o.energyMj,
        measuredMs + o.measuredMs,
        screenEnergyMj + o.screenEnergyMj,
        screenMs + o.screenMs,
        chargingMs + o.chargingMs,
    )

    val avgMw: Double? get() = avg(energyMj, measuredMs)
    val screenOnAvgMw: Double? get() = avg(screenEnergyMj, screenMs)
    val screenOffAvgMw: Double? get() = avg(energyMj - screenEnergyMj, measuredMs - screenMs)
    val screenOffMs: Long get() = measuredMs - screenMs

    /** 能量换算为 mWh */
    val energyMwh: Double get() = energyMj / 3600.0

    private fun avg(mj: Double, ms: Long): Double? = if (ms < 1000) null else mj / (ms / 1000.0)
}

const val MINUTE_MS = 60_000L
const val HOUR_MS = 3_600_000L
