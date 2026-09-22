package com.powerusage.monitor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors
import kotlin.math.max

/** 简单柱状图：每根柱 = 一个时间段的平均功耗，柱内黄色部分为亮屏时间占比。 */
class BarChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    class Bar(val valueMw: Double?, val screenFraction: Float, val label: String)

    private var bars: List<Bar> = emptyList()
    private val density = resources.displayMetrics.density

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.accent) }
    private val screenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.accent_screen) }
    private val textColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF757575.toInt())
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textSize = 10 * density
    }
    private val gridPaint = Paint().apply {
        color = textColor
        alpha = 60
        strokeWidth = density
    }

    fun setBars(b: List<Bar>) {
        bars = b
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (bars.isEmpty()) return
        val maxV = bars.maxOf { it.valueMw ?: 0.0 }.let { if (it <= 0) 1.0 else niceCeil(it) }
        val left = 44 * density
        val top = 8 * density
        val bottom = height - 16 * density
        val plotH = bottom - top
        val slot = (width - left) / bars.size
        val gap = max(1f, slot * 0.2f)

        // 纵轴：0、1/2、最大值
        for (i in 0..2) {
            val v = maxV * i / 2
            val y = bottom - (plotH * i / 2).toFloat()
            canvas.drawLine(left, y, width.toFloat(), y, gridPaint)
            canvas.drawText(Format.power(v), 0f, y + textPaint.textSize / 3, textPaint)
        }

        val labelEvery = max(1, (bars.size + 5) / 6)
        bars.forEachIndexed { i, bar ->
            val x0 = left + i * slot + gap / 2
            val x1 = x0 + slot - gap
            bar.valueMw?.let { v ->
                val h = (plotH * v / maxV).toFloat()
                canvas.drawRect(x0, bottom - h, x1, bottom, barPaint)
                if (bar.screenFraction > 0) {
                    canvas.drawRect(x0, bottom - h * bar.screenFraction, x1, bottom, screenPaint)
                }
            }
            if (i % labelEvery == 0) {
                canvas.drawText(bar.label, x0, height - 2 * density, textPaint)
            }
        }
    }

    private fun niceCeil(v: Double): Double {
        var step = 1.0
        while (step * 10 < v) step *= 10
        for (m in listOf(1.0, 2.0, 5.0, 10.0)) if (step * m >= v) return step * m
        return step * 10
    }
}
