package com.example.cervicalguard.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import com.example.cervicalguard.R
import com.example.cervicalguard.data.PostureDb
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * 数据趋势图：分级堆叠柱状图（自下而上 安全 / 注意 / 危险）。
 *
 * 两种模式：
 *  - [Mode.HOURLY] 日报：今天 24 个小时桶，标签每 3 小时一个，当前小时高亮；
 *  - [Mode.DAILY]  周报/月报：最近 N 天，7 天标每一天、30 天每 5 天标一个，今天高亮。
 *
 * 自绘实现——工程是纯 XML + AppCompat，不引第三方图表库（老设备 dex 更小、无新依赖风险）。
 * 深色底自带，浅色/深色主题下对比度都够。
 */
class DataChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Mode { HOURLY, DAILY }

    companion object {
        /**
         * 图表底色**资源 id**（v1.5）：深/浅色由 values/ 与 values-night/ 各一套决定，
         * 代码里不再写死 ARGB —— 否则浅色主题下会在白色弹窗里留一块深色方砖。
         */
        val COLOR_BG_RES = R.color.chart_bg
    }

    // 深/浅色由资源决定（values/ 与 values-night/ 各一套），不要在代码里写死 ARGB
    private val colorBg = ContextCompat.getColor(context, R.color.chart_bg)
    private val colorSafe = ContextCompat.getColor(context, R.color.safe)
    private val colorWarn = ContextCompat.getColor(context, R.color.warn)
    private val colorDanger = ContextCompat.getColor(context, R.color.danger)
    private val colorText = ContextCompat.getColor(context, R.color.chart_text)
    private val colorTextDim = ContextCompat.getColor(context, R.color.chart_text_dim)
    private val colorGrid = ContextCompat.getColor(context, R.color.chart_grid)

    private val density = resources.displayMetrics.density
    private val dayFmt = SimpleDateFormat("M/d", Locale.CHINA)

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorText
        textSize = sp(11f)
        textAlign = Paint.Align.CENTER
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorTextDim
        textSize = sp(10f)
        textAlign = Paint.Align.CENTER
    }
    private val gridPaint = Paint().apply {
        color = colorGrid
        strokeWidth = 1f * density
    }

    private var buckets: List<PostureDb.Bucket> = emptyList()
    private var mode = Mode.DAILY

    fun setData(data: List<PostureDb.Bucket>, mode: Mode) {
        this.buckets = data
        this.mode = mode
        invalidate()
    }

    private fun dp(v: Float) = v * density

    /** sp → px（用 TypedValue 而非已弃用的 scaledDensity）。 */
    private fun sp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(colorBg)

        val legendH = dp(24f)
        val labelH = dp(20f)
        // 柱顶数值标签（如「230分」）画在柱子上方 dp(4f) 处。最高的柱子顶就在 chartTop，
        // 它的标签会因此画到 chartTop 之上、压住图例（v1.5 之前一直存在的布局问题）。
        // 这里在图表顶部再预留一个文字高度，标签就永远落在图例下方。
        val valueLabelH = textPaint.textSize + dp(4f)
        val left = dp(4f)
        val right = width - dp(4f)
        val chartTop = legendH + valueLabelH
        val chartBottom = height - labelH
        val chartH = chartBottom - chartTop
        if (chartH <= 0 || right <= left || buckets.isEmpty()) return

        drawLegend(canvas, legendH)

        if (buckets.all { it.totalSec == 0 }) {
            val save = textPaint.color
            textPaint.color = colorTextDim
            canvas.drawText("该时段暂无坐姿数据", (left + right) / 2f, chartTop + chartH / 2f, textPaint)
            textPaint.color = save
            drawAxisLabels(canvas, left, right, chartBottom)
            return
        }

        val n = buckets.size
        val slot = (right - left) / n
        // 桶多就收窄柱子，别糊成一片
        val barW = slot * (if (slot > dp(24f)) 0.55f else 0.7f)
        // 纵轴上限：单桶最大总时长（不足 1 分钟按 1 分钟算，避免柱子撑满失真）
        val maxTotal = max(buckets.maxOf { it.totalSec }.toFloat(), 60f)
        val baselineY = chartBottom
        val showValue = slot >= dp(26f)

        canvas.drawLine(left, baselineY, right, baselineY, gridPaint)

        buckets.forEachIndexed { i, b ->
            val cx = left + slot * (i + 0.5f)
            val bLeft = cx - barW / 2f
            val bRight = cx + barW / 2f

            var y = baselineY
            fun seg(sec: Int, color: Int) {
                if (sec <= 0) return
                val h = chartH * (sec / maxTotal)
                barPaint.color = color
                canvas.drawRect(RectF(bLeft, y - h, bRight, y), barPaint)
                y -= h
            }
            seg(b.safeSec, colorSafe)
            seg(b.warnSec, colorWarn)
            seg(b.dangerSec, colorDanger)

            if (showValue && b.totalSec > 0) {
                textPaint.color = colorTextDim
                canvas.drawText(
                    fmtMin(b.totalSec),
                    cx,
                    baselineY - chartH * (b.totalSec / maxTotal) - dp(4f),
                    textPaint
                )
            }
        }
        textPaint.color = colorText

        drawAxisLabels(canvas, left, right, chartBottom)
    }

    private fun drawLegend(canvas: Canvas, legendH: Float) {
        val square = dp(8f)
        val gap = dp(4f)
        val itemGap = dp(14f)
        val items = listOf(
            "安全" to colorSafe,
            "注意" to colorWarn,
            "危险" to colorDanger,
        )
        val widths = items.map { square + gap + textPaint.measureText(it.first) }
        val totalW = widths.sum() + itemGap * (items.size - 1)
        var x = (width - totalW) / 2f
        val cy = legendH / 2f
        items.forEachIndexed { i, (label, color) ->
            barPaint.color = color
            canvas.drawRect(RectF(x, cy - square / 2f, x + square, cy + square / 2f), barPaint)
            val old = textPaint.color
            textPaint.color = colorTextDim
            textPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(label, x + square + gap, cy + dp(4f), textPaint)
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.color = old
            x += widths[i] + itemGap
        }
    }

    /** 横轴标签：小时模式每 3 小时一个，天模式 7 天内全标、更长则每 5 天标一个。 */
    private fun drawAxisLabels(canvas: Canvas, left: Float, right: Float, chartBottom: Float) {
        val n = buckets.size
        if (n == 0) return
        val slot = (right - left) / n
        val cal = Calendar.getInstance()
        val nowHour = cal.get(Calendar.HOUR_OF_DAY)
        val step = when {
            mode == Mode.HOURLY -> 3
            n <= 7 -> 1
            else -> 5
        }

        buckets.forEachIndexed { i, b ->
            val isLast = i == n - 1
            val text: String?
            val highlight: Boolean
            if (mode == Mode.HOURLY) {
                highlight = i == nowHour
                text = if (i % step == 0 || highlight) "${i}时" else null
            } else {
                highlight = isLast
                text = when {
                    isLast -> "今天"
                    i % step == 0 -> dayFmt.format(Date(b.startSec * 1000))
                    else -> null
                }
            }
            if (text != null) {
                labelPaint.color = if (highlight) colorText else colorTextDim
                canvas.drawText(text, left + slot * (i + 0.5f), chartBottom + dp(14f), labelPaint)
            }
        }
    }

    private fun fmtMin(sec: Int): String {
        if (sec <= 0) return "0"
        val m = sec / 60
        return if (m <= 0) "${sec}秒" else "${m}分"
    }
}
