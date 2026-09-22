package com.powerusage.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * 前台服务：亮屏时每秒、息屏时每 5 秒采样一次电流×电压，
 * 由 [Recorder] 积分成每分钟能量并写入数据库。
 */
class MonitorService : Service() {

    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private lateinit var reader: PowerReader
    private lateinit var store: PowerStore
    private var lastFlush = 0L
    private var lastNotify = 0L

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = updateBattery(intent)
    }

    /** 亮灭屏时立即采样，使区间在切换点被正确划分 */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            handler.removeCallbacks(tick)
            handler.post(tick)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            val s = reader.read()
            if (s != null) Recorder.onSample(s)
            val now = SystemClock.elapsedRealtime()
            if (now - lastFlush >= MINUTE_MS) {
                lastFlush = now
                Recorder.flush(store)
                store.deleteBefore(System.currentTimeMillis() - RETENTION_MS)
            }
            if (now - lastNotify >= 5_000) {
                lastNotify = now
                notifyStatus(s)
            }
            val screenOn = s?.screenOn ?: true
            handler.postDelayed(this, if (screenOn) 1_000 else 5_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        store = PowerStore.get(this)
        reader = PowerReader(this)
        createChannel()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification("正在启动…", null),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )

        thread = HandlerThread("power-sampler").also { it.start() }
        handler = Handler(thread.looper)

        ContextCompat.registerReceiver(
            this, batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), null, handler,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )?.let { updateBattery(it) }
        ContextCompat.registerReceiver(
            this, screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            null, handler, ContextCompat.RECEIVER_NOT_EXPORTED
        )

        Recorder.resetBaseline()
        lastFlush = SystemClock.elapsedRealtime()
        handler.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        unregisterReceiver(batteryReceiver)
        unregisterReceiver(screenReceiver)
        handler.removeCallbacksAndMessages(null)
        // 在采样线程上补最后一次采样并落库，然后退出线程
        handler.post {
            reader.read()?.let { Recorder.onSample(it) }
            Recorder.flush(store)
        }
        thread.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateBattery(intent: Intent) {
        var mv = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0).toDouble()
        if (mv in 0.1..100.0) mv *= 1000 // 少数设备以伏为单位
        reader.voltageMv = mv
        reader.plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
        )
    }

    private fun notifyStatus(s: Sample?) {
        val bootWall = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        val totals = Recorder.totalsSince(store, bootWall - bootWall % MINUTE_MS)
        val title = when {
            s == null -> "无法读取电流"
            s.charging -> "充电中（不计入统计）"
            else -> "当前 ${Format.power(s.powerMw)}"
        }
        val text = "开机平均 ${Format.power(totals.avgMw)} · 亮屏 ${Format.power(totals.screenOnAvgMw)}"
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(title, text))
    }

    private fun buildNotification(title: String, text: String?): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bolt)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "monitor"
        private const val NOTIFICATION_ID = 1
        private const val RETENTION_MS = 30L * 24 * HOUR_MS

        @Volatile
        var running = false
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MonitorService::class.java))
        }
    }
}
