package com.powerusage.monitor

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class PowerStore private constructor(context: Context) :
    SQLiteOpenHelper(context, "power.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE minute (
                minute_start INTEGER PRIMARY KEY,
                energy_mj REAL NOT NULL DEFAULT 0,
                measured_ms INTEGER NOT NULL DEFAULT 0,
                screen_energy_mj REAL NOT NULL DEFAULT 0,
                screen_ms INTEGER NOT NULL DEFAULT 0,
                charging_ms INTEGER NOT NULL DEFAULT 0
            )"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun addBuckets(buckets: Collection<MinuteBucket>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (b in buckets) {
                db.execSQL("INSERT OR IGNORE INTO minute(minute_start) VALUES (?)", arrayOf(b.minuteStart))
                db.execSQL(
                    """UPDATE minute SET
                        energy_mj = energy_mj + ?,
                        measured_ms = measured_ms + ?,
                        screen_energy_mj = screen_energy_mj + ?,
                        screen_ms = screen_ms + ?,
                        charging_ms = charging_ms + ?
                    WHERE minute_start = ?""",
                    arrayOf(b.energyMj, b.measuredMs, b.screenEnergyMj, b.screenMs, b.chargingMs, b.minuteStart)
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun queryBuckets(from: Long, to: Long): List<MinuteBucket> {
        val out = ArrayList<MinuteBucket>()
        readableDatabase.rawQuery(
            """SELECT minute_start, energy_mj, measured_ms, screen_energy_mj, screen_ms, charging_ms
               FROM minute WHERE minute_start >= ? AND minute_start < ? ORDER BY minute_start""",
            arrayOf(from.toString(), to.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out += MinuteBucket(c.getLong(0), c.getDouble(1), c.getLong(2), c.getDouble(3), c.getLong(4), c.getLong(5))
            }
        }
        return out
    }

    fun sumSince(from: Long): Totals {
        readableDatabase.rawQuery(
            """SELECT TOTAL(energy_mj), TOTAL(measured_ms), TOTAL(screen_energy_mj), TOTAL(screen_ms), TOTAL(charging_ms)
               FROM minute WHERE minute_start >= ?""",
            arrayOf(from.toString())
        ).use { c ->
            if (!c.moveToFirst()) return Totals()
            return Totals(c.getDouble(0), c.getLong(1), c.getDouble(2), c.getLong(3), c.getLong(4))
        }
    }

    /** 最早一条记录的时间，无记录时为 null */
    fun firstMinute(): Long? {
        readableDatabase.rawQuery("SELECT MIN(minute_start) FROM minute WHERE measured_ms > 0", null).use { c ->
            return if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    }

    fun deleteBefore(time: Long) {
        writableDatabase.execSQL("DELETE FROM minute WHERE minute_start < ?", arrayOf(time))
    }

    fun clear() {
        writableDatabase.execSQL("DELETE FROM minute")
    }

    companion object {
        @Volatile
        private var instance: PowerStore? = null

        fun get(context: Context): PowerStore =
            instance ?: synchronized(this) {
                instance ?: PowerStore(context.applicationContext).also { instance = it }
            }
    }
}
