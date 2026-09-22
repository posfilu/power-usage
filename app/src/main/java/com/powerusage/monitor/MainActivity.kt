package com.powerusage.monitor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings as SystemSettings
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var store: PowerStore

    private lateinit var tvNow: TextView
    private lateinit var tvNowDetail: TextView
    private lateinit var btnToggle: MaterialButton
    private lateinit var summaryGrid: GridLayout
    private lateinit var energyGrid: GridLayout
    private lateinit var rangeGroup: MaterialButtonToggleGroup
    private lateinit var chart: BarChartView
    private lateinit var detailList: LinearLayout

    private var showHours = false
    private var lastSnapshot: Snapshot? = null
    private val minuteFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val hourFmt = SimpleDateFormat("MM-dd HH:00", Locale.getDefault())
    private val dateFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    private val hourShortFmt = SimpleDateFormat("HH时", Locale.getDefault())

    private val refresh = object : Runnable {
        override fun run() {
            io.execute {
                val snap = Stats.load(store)
                ui.post { render(snap) }
            }
            ui.postDelayed(this, 2_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = PowerStore.get(this)

        tvNow = findViewById(R.id.tvNow)
        tvNowDetail = findViewById(R.id.tvNowDetail)
        btnToggle = findViewById(R.id.btnToggle)
        summaryGrid = findViewById(R.id.summaryGrid)
        energyGrid = findViewById(R.id.energyGrid)
        rangeGroup = findViewById(R.id.rangeGroup)
        chart = findViewById(R.id.chart)
        detailList = findViewById(R.id.detailList)

        btnToggle.setOnClickListener {
            if (MonitorService.running) {
                Settings.setMonitoringEnabled(this, false)
                MonitorService.stop(this)
            } else {
                Settings.setMonitoringEnabled(this, true)
                MonitorService.start(this)
            }
            ui.postDelayed({ updateToggle() }, 300)
        }
        findViewById<View>(R.id.btnMore).setOnClickListener { showSettings() }
        rangeGroup.addOnButtonCheckedListener { _, id, checked ->
            if (checked) {
                showHours = id == R.id.btnHour
                lastSnapshot?.let { renderDetail(it) }
            }
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        // 首次打开默认开始监测
        if (!getSharedPreferences("settings", MODE_PRIVATE).contains("enabled")) {
            Settings.setMonitoringEnabled(this, true)
        }
        if (Settings.monitoringEnabled(this) && !MonitorService.running) {
            MonitorService.start(this)
        }
    }

    override fun onResume() {
        super.onResume()
        updateToggle()
        ui.post(refresh)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(refresh)
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    private fun updateToggle() {
        btnToggle.text = if (MonitorService.running) "停止监测" else "开始监测"
    }

    private fun render(s: Snapshot) {
        lastSnapshot = s
        updateToggle()

        val l = s.latest
        when {
            !MonitorService.running -> {
                tvNow.text = "—"
                tvNowDetail.text = "监测未运行"
            }
            l == null -> {
                tvNow.text = "—"
                tvNowDetail.text = "等待采样…（若持续无数据，设备可能不支持读取电流）"
            }
            else -> {
                tvNow.text = Format.power(l.powerMw)
                tvNowDetail.text = String.format(
                    Locale.US, "%.0f mA × %.3f V · %s%s",
                    l.currentMa, l.voltageMv / 1000, if (l.screenOn) "亮屏" else "息屏",
                    if (l.charging) " · 充电中（不计入统计）" else ""
                )
            }
        }

        val b = s.sinceBoot
        val tiles = listOf(
            Tile("开机至今平均", Format.power(b.avgMw),
                "开机 ${Format.duration(s.sinceBootMs)} · 覆盖 ${Format.percent(b.measuredMs + b.chargingMs, s.sinceBootMs)}\n共耗电 ${Format.mwh(b.energyMwh)}"),
            Tile("亮屏平均（开机至今）", Format.power(b.screenOnAvgMw),
                "亮屏时长 ${Format.duration(b.screenMs)}"),
            Tile("息屏平均（开机至今）", Format.power(b.screenOffAvgMw),
                "息屏时长 ${Format.duration(b.screenOffMs)}"),
            Tile("上一分钟平均", Format.power(s.lastMinute.avgMw),
                "亮屏占 ${Format.percent(s.lastMinute.screenMs, s.lastMinute.measuredMs)}"),
            Tile("最近60分钟平均", Format.power(s.last60Min.avgMw),
                "亮屏平均 ${Format.power(s.last60Min.screenOnAvgMw)}"),
            Tile("本小时平均", Format.power(s.currentHour.avgMw),
                "亮屏平均 ${Format.power(s.currentHour.screenOnAvgMw)}"),
        )
        renderTiles(summaryGrid, tiles)

        val v = l?.voltageMv
        fun energyTile(title: String, t: Totals, extra: String) = Tile(
            title,
            Format.energy(t.energyMwh),
            listOf(Format.mah(t.energyMwh, v), extra).filter { it.isNotEmpty() }.joinToString(" · ") +
                "\n亮屏 ${Format.energy(t.screenOnMwh)} · 息屏 ${Format.energy(t.screenOffMwh)}"
        )
        renderTiles(energyGrid, listOf(
            energyTile("开机至今", b, "平均 ${Format.power(b.avgMw)}"),
            energyTile("今天", s.today, "平均 ${Format.power(s.today.avgMw)}"),
            energyTile("近24小时", s.last24h, "平均 ${Format.power(s.last24h.avgMw)}"),
            energyTile("全部记录", s.allTime,
                s.firstRecord?.let { "自 " + dateFmt.format(Date(it)) } ?: "暂无记录"),
        ))
        renderDetail(s)
    }

    private class Tile(val title: String, val value: String, val sub: String)

    private fun renderTiles(grid: GridLayout, tiles: List<Tile>) {
        if (grid.childCount != tiles.size) {
            grid.removeAllViews()
            repeat(tiles.size) { grid.addView(makeTileView()) }
        }
        tiles.forEachIndexed { i, t ->
            val card = grid.getChildAt(i) as MaterialCardView
            val col = card.getChildAt(0) as LinearLayout
            (col.getChildAt(0) as TextView).text = t.title
            (col.getChildAt(1) as TextView).text = t.value
            (col.getChildAt(2) as TextView).text = t.sub
        }
    }

    private fun makeTileView(): View {
        val d = resources.displayMetrics.density
        val card = MaterialCardView(this, null, com.google.android.material.R.attr.materialCardViewOutlinedStyle)
        card.layoutParams = GridLayout.LayoutParams(
            GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)
        ).apply {
            width = 0
            setMargins((4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt())
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (12 * d).toInt()
            setPadding(p, p, p, p)
        }
        col.addView(TextView(this).apply { setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium) })
        col.addView(TextView(this).apply { setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall) })
        col.addView(TextView(this).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
        })
        card.addView(col)
        return card
    }

    private fun renderDetail(s: Snapshot) {
        val rows = if (showHours) s.hours else s.minutes
        val fmtShort = if (showHours) hourShortFmt else minuteFmt
        chart.setBars(rows.map { (t, v) ->
            BarChartView.Bar(
                v.avgMw,
                if (v.measuredMs > 0) v.screenMs.toFloat() / v.measuredMs else 0f,
                fmtShort.format(Date(t)),
            )
        })

        // 列表：最新的在最上面
        val list = rows.asReversed()
        while (detailList.childCount < list.size) detailList.addView(makeRow())
        while (detailList.childCount > list.size) detailList.removeViewAt(detailList.childCount - 1)
        list.forEachIndexed { i, (t, v) ->
            val row = detailList.getChildAt(i) as LinearLayout
            val label = (if (showHours) hourFmt else minuteFmt).format(Date(t)) + if (i == 0) "（进行中）" else ""
            (row.getChildAt(0) as TextView).text = label
            (row.getChildAt(1) as TextView).text = Format.power(v.avgMw)
            (row.getChildAt(2) as TextView).text = when {
                v.measuredMs == 0L && v.chargingMs > 0 -> "充电中"
                v.measuredMs == 0L -> "无数据"
                else -> "耗电 ${Format.energy(v.energyMwh)} · 亮屏 ${Format.power(v.screenOnAvgMw)} · 息屏 ${Format.power(v.screenOffAvgMw)}"
            }
        }
    }

    private fun makeRow(): LinearLayout {
        val d = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt())
            addView(TextView(context), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.1f))
            addView(TextView(context).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.8f))
            addView(TextView(context).apply {
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                textSize = 12f
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.6f))
        }
    }

    private fun showSettings() {
        val unitNames = arrayOf("电流单位：自动识别", "电流单位：微安 (µA)", "电流单位：毫安 (mA)")
        val items = unitNames.map { it } + listOf("关闭电池优化（保证后台持续监测）", "清空统计数据")
        AlertDialog.Builder(this)
            .setTitle("设置（当前：${unitNames[Settings.unitMode(this)].substringAfter('：')}）")
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    0, 1, 2 -> {
                        Settings.setUnitMode(this, which)
                        if (which == Settings.UNIT_AUTO) Settings.setDetectedUnit(this, Settings.UNIT_AUTO)
                    }
                    3 -> runCatching {
                        startActivity(Intent(SystemSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                    4 -> AlertDialog.Builder(this)
                        .setMessage("确定清空所有统计数据？")
                        .setPositiveButton("清空") { _, _ -> io.execute { Recorder.clearAll(store) } }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
            .show()
    }
}
