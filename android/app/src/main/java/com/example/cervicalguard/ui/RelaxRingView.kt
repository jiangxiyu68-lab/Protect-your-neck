package com.example.cervicalguard.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.example.cervicalguard.R
import com.example.cervicalguard.core.RelaxSnapshot

/**
 * 放松操的「收下巴」仪表盘（自绘，不引第三方图表库）。
 *
 * 三层信息：
 *  1. 外环轨道 + 幅度环：当前收下巴幅度 / 目标幅度（够到目标转绿）
 *  2. 内环：本次「保持」的进度（0~1），到满即计一次
 *  3. 圆心：阶段提示 + 保持读秒 + 次数
 *
 * 数据来自 [RelaxSnapshot]，只读；由服务在分析线程填好，界面线程调用 [render] 刷新。
 */
class RelaxRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private companion object {
        const val START_ANGLE = 150f      // 缺口朝下，视觉上像仪表盘
        const val SWEEP_ANGLE = 240f
    }

    // 配色走资源（v1.5 深/浅色切换）：以前写死成"适合白底对话框"的深色字，
    // 深色模式下对话框变深底，那套颜色就糊了。现在 values/ 与 values-night/ 各一套。
    private val COLOR_TRACK = ContextCompat.getColor(context, R.color.ring_track)
    private val COLOR_DEPTH_LOW = ContextCompat.getColor(context, R.color.accent)
    private val COLOR_DEPTH_OK = ContextCompat.getColor(context, R.color.safe)
    private val COLOR_HOLD = ContextCompat.getColor(context, R.color.warn)
    private val COLOR_TEXT = ContextCompat.getColor(context, R.color.ring_text)
    private val COLOR_TEXT_DIM = ContextCompat.getColor(context, R.color.ring_text_dim)
    private val COLOR_LOST = ContextCompat.getColor(context, R.color.danger)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arcRect = RectF()
    private var snap: RelaxSnapshot? = null

    fun render(s: RelaxSnapshot?) {
        snap = s
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = snap
        val cx = width / 2f
        val cy = height / 2f
        val outerR = minOf(width, height) / 2f - dp(14f)
        val ringW = dp(16f)
        val innerR = outerR - ringW - dp(12f)

        // 1) 外环轨道
        arcRect.set(cx - outerR, cy - outerR, cx + outerR, cy + outerR)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = ringW
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = COLOR_TRACK
        canvas.drawArc(arcRect, START_ANGLE, SWEEP_ANGLE, false, paint)

        if (s == null) {
            drawCenter(canvas, cx, cy, "准备中", "", "", COLOR_TEXT_DIM)
            return
        }

        // 2) 幅度环：0~1 为目标，超过目标最多画到 1（超出部分靠颜色与文字体现）
        val depthRatio = (s.depthDeg / s.targetDeg).coerceIn(0.0, 1.0).toFloat()
        if (depthRatio > 0.01f) {
            paint.color = if (s.depthDeg >= s.targetDeg) COLOR_DEPTH_OK else COLOR_DEPTH_LOW
            canvas.drawArc(arcRect, START_ANGLE, SWEEP_ANGLE * depthRatio, false, paint)
        }

        // 3) 内环：保持进度
        arcRect.set(cx - innerR, cy - innerR, cx + innerR, cy + innerR)
        paint.strokeWidth = dp(7f)
        paint.color = COLOR_TRACK
        canvas.drawArc(arcRect, START_ANGLE, SWEEP_ANGLE, false, paint)
        val holdRatio = s.holdProgress.toFloat()
        if (holdRatio > 0.01f) {
            paint.color = COLOR_HOLD
            canvas.drawArc(arcRect, START_ANGLE, SWEEP_ANGLE * holdRatio, false, paint)
        }

        // 4) 圆心文字
        when {
            s.done -> drawCenter(canvas, cx, cy, "已完成", "放松分 ${s.score}", "${s.rep}/${s.total} 次", COLOR_TEXT)
            // 准备期：先给用户转身的时间，别把「转身」当成基准
            s.phase == "PREPARING" -> drawCenter(
                canvas, cx, cy, "准备", "侧身对着手机 · ${s.prepareSecLeft} 秒后开始", "听语音提示即可", COLOR_DEPTH_LOW
            )
            // 视角优先级最高：角度太正时前后位移几乎测不到，宁可让用户侧一点，也不显示假的幅度与成绩
            s.frontal -> drawCenter(
                canvas, cx, cy, "角度太正",
                "现在是 ${s.viewAngleDeg?.let { String.format(java.util.Locale.CHINA, "%.0f°", it) } ?: "?"}（正脸）",
                "请侧一点，到 45° 以内", COLOR_LOST
            )
            s.signalLost -> drawCenter(canvas, cx, cy, "看不到你", "请侧面对着手机", "", COLOR_LOST)
            s.phase == "CALIBRATING" -> drawCenter(canvas, cx, cy, "先坐正", "保持不动，正在记录基准", "", COLOR_TEXT)
            s.phase == "HOLDING" -> drawCenter(
                canvas, cx, cy, "保持！",
                String.format(java.util.Locale.CHINA, "%.1f / %.1f 秒", s.holdElapsed, s.holdSec),
                "第 ${s.rep + 1} / ${s.total} 次", COLOR_HOLD
            )
            else -> drawCenter(
                canvas, cx, cy, "收下巴",
                if (s.depthDeg > 0.5) String.format(java.util.Locale.CHINA, "已收 %.1f° / 目标 %.0f°", s.depthDeg, s.targetDeg)
                else "水平后收，做出双下巴",
                "第 ${s.rep + 1} / ${s.total} 次" + (s.viewAngleDeg?.let {
                    " · 视角 " + String.format(java.util.Locale.CHINA, "%.0f°", it)
                } ?: ""),
                COLOR_TEXT
            )
        }
    }

    private fun drawCenter(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        line1: String,
        line2: String,
        line3: String,
        color: Int,
    ) {
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.color = color
        paint.textSize = dp(26f)
        canvas.drawText(line1, cx, cy - dp(2f), paint)
        paint.color = COLOR_TEXT_DIM
        paint.textSize = dp(13f)
        if (line2.isNotEmpty()) canvas.drawText(line2, cx, cy + dp(20f), paint)
        if (line3.isNotEmpty()) canvas.drawText(line3, cx, cy + dp(40f), paint)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}