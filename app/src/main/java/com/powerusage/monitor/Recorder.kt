package com.powerusage.monitor

import kotlin.math.min

/**
 * 进程内的积分器：把相邻两次采样之间的能量累加到按分钟划分的桶里，
 * 定期写入 [PowerStore]。UI 读取时把尚未写库的桶合并进来。
 */
object Recorder {
    /** 相邻采样超过该间隔视为 CPU 休眠造成的空档 */
    private const val GAP_MS = 8_000L

    /** 超过该间隔（如关机、服务被杀很久）不再补算 */
    private const val MAX_INTERVAL_MS = 6 * HOUR_MS

    private val lock = Any()
    private val pending = HashMap<Long, MinuteBucket>()
    private var last: Sample? = null

    @Volatile
    var latest: Sample? = null
        private set

    /** 服务重新开始采样时调用，避免把服务停止期间算进去 */
    fun resetBaseline() = synchronized(lock) {
        last = null
        latest = null
    }

    fun onSample(s: Sample) = synchronized(lock) {
        val p = last
        last = s
        latest = s
        if (p != null) integrate(p, s)
    }

    private fun integrate(p: Sample, s: Sample) {
        val dt = s.elapsed - p.elapsed
        if (dt <= 0 || dt > MAX_INTERVAL_MS) return
        val charging = p.charging || s.charging
        // 屏幕状态切换时会立即采样，所以区间内屏幕状态取起点状态
        val screenOn = p.screenOn
        val energyMj = when {
            charging -> 0.0
            dt <= GAP_MS -> (p.powerMw + s.powerMw) / 2.0 * dt / 1000.0
            else -> gapEnergyMj(p, s, dt)
        }
        addInterval(s.wall - dt, s.wall, energyMj, screenOn, charging)
    }

    /**
     * 休眠空档：优先用电量计的电荷差估算（能反映深睡时的低电流），
     * 电量计不可用或分辨率不够时，用两端较小的功率保守估算。
     */
    private fun gapEnergyMj(p: Sample, s: Sample, dt: Long): Double {
        if (p.chargeCounterUah > 0 && s.chargeCounterUah > 0) {
            val deltaUah = p.chargeCounterUah - s.chargeCounterUah
            if (deltaUah > 0) {
                val avgMa = deltaUah / 1000.0 / (dt / HOUR_MS.toDouble())
                if (avgMa < 20_000) {
                    val avgV = (p.voltageMv + s.voltageMv) / 2.0 / 1000.0
                    val mWh = deltaUah / 1000.0 * avgV
                    return mWh * 3600.0
                }
            }
        }
        return min(p.powerMw, s.powerMw) * dt / 1000.0
    }

    private fun addInterval(start: Long, end: Long, energyMj: Double, screenOn: Boolean, charging: Boolean) {
        val total = (end - start).toDouble()
        var t = start
        while (t < end) {
            val minuteStart = t - t.mod(MINUTE_MS)
            val segEnd = min(end, minuteStart + MINUTE_MS)
            val len = segEnd - t
            val b = pending.getOrPut(minuteStart) { MinuteBucket(minuteStart) }
            if (charging) {
                b.chargingMs += len
            } else {
                val e = energyMj * len / total
                b.energyMj += e
                b.measuredMs += len
                if (screenOn) {
                    b.screenEnergyMj += e
                    b.screenMs += len
                }
            }
            t = segEnd
        }
    }

    /** 把内存中的桶写入数据库 */
    fun flush(store: PowerStore) = synchronized(lock) {
        if (pending.isEmpty()) return
        store.addBuckets(pending.values)
        pending.clear()
    }

    fun clearAll(store: PowerStore) = synchronized(lock) {
        pending.clear()
        store.clear()
    }

    /** 在锁内读取「数据库 + 未写库」的分钟桶，保证与 flush 不会重复或遗漏 */
    fun buckets(store: PowerStore, from: Long, to: Long): List<MinuteBucket> = synchronized(lock) {
        val map = LinkedHashMap<Long, MinuteBucket>()
        for (b in store.queryBuckets(from, to)) map[b.minuteStart] = b
        for (b in pending.values) {
            if (b.minuteStart < from || b.minuteStart >= to) continue
            map.getOrPut(b.minuteStart) { MinuteBucket(b.minuteStart) }.add(b)
        }
        map.values.sortedBy { it.minuteStart }
    }

    fun firstRecord(store: PowerStore): Long? = synchronized(lock) {
        val p = pending.values.filter { it.measuredMs > 0 }.minOfOrNull { it.minuteStart }
        listOfNotNull(store.firstMinute(), p).minOrNull()
    }

    fun totalsSince(store: PowerStore, from: Long): Totals = synchronized(lock) {
        var t = store.sumSince(from)
        for (b in pending.values) if (b.minuteStart >= from) t += b.toTotals()
        t
    }
}
