package com.powerusage.monitor

import android.os.SystemClock
import java.util.TimeZone
import kotlin.math.floorMod

/** 界面需要的一次性统计快照 */
class Snapshot(
    val latest: Sample?,
    val sinceBootMs: Long,
    val sinceBoot: Totals,
    val lastMinute: Totals,
    val last60Min: Totals,
    val currentHour: Totals,
    /** 近 60 分钟，按时间正序，最后一项是正在进行的分钟 */
    val minutes: List<Pair<Long, Totals>>,
    /** 近 24 小时（本地时区整点），按时间正序，最后一项是当前小时 */
    val hours: List<Pair<Long, Totals>>,
)

object Stats {
    fun load(store: PowerStore): Snapshot {
        val now = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        val bootWall = now - elapsed
        val curMinute = now - floorMod(now, MINUTE_MS)

        // 近 60 分钟
        val minuteFrom = curMinute - 59 * MINUTE_MS
        val minuteMap = Recorder.buckets(store, minuteFrom, curMinute + MINUTE_MS).associateBy { it.minuteStart }
        val minutes = (0 until 60).map { i ->
            val t = minuteFrom + i * MINUTE_MS
            t to (minuteMap[t]?.toTotals() ?: Totals())
        }

        // 近 24 小时，按本地整点聚合
        val curHour = localHourStart(now)
        val hourFrom = curHour - 23 * HOUR_MS
        val hourMap = HashMap<Long, Totals>()
        for (b in Recorder.buckets(store, hourFrom, curMinute + MINUTE_MS)) {
            val h = localHourStart(b.minuteStart)
            hourMap[h] = (hourMap[h] ?: Totals()) + b.toTotals()
        }
        val hours = (0 until 24).map { i ->
            val t = hourFrom + i * HOUR_MS
            t to (hourMap[t] ?: Totals())
        }

        return Snapshot(
            latest = Recorder.latest,
            sinceBootMs = elapsed,
            sinceBoot = Recorder.totalsSince(store, bootWall - floorMod(bootWall, MINUTE_MS)),
            lastMinute = minutes[58].second,
            last60Min = minutes.fold(Totals()) { a, p -> a + p.second },
            currentHour = hours.last().second,
            minutes = minutes,
            hours = hours,
        )
    }

    private fun localHourStart(t: Long): Long {
        val offset = TimeZone.getDefault().getOffset(t)
        return t - floorMod(t + offset, HOUR_MS)
    }
}
