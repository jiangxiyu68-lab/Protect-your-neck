package com.example.cervicalguard.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.example.cervicalguard.core.PoseOverlay
import kotlin.math.atan2
import kotlin.math.min

/**
 * 识别标注层：把算法**实际用到的点**画在相机画面上。
 *
 * 只报一个「视角 56°」用户没法验证；画出耳朵、肩膀、CVA 连线与夹角，「它到底在看哪里」一目了然。
 *
 * 坐标映射：PoseOverlay 是归一化到分析帧的（0~1），这里按**预览的显示区域**映射——
 * 服务和界面都把画面钉成 3:4（分析帧 240×320、预览 AspectRatio 4:3 竖屏），
 * 所以只需等比缩放 + 居中（fitCenter），再用 [mirror] 处理前置摄像头的镜像。
 */
class LandmarkOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private companion object {
        /** 分析帧的宽高比（宽/高）：240×320 → 0.75。 */
        const val IMG_ASPECT = 0.75f
        const val ACCENT = 0xFF2E7DF6.toInt()
        const val SAFE = 0xFF34C759.toInt()
        const val WARN = 0xFFFFB020.toInt()
        const val DANGER = 0xFFFF453A.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
    }

    /**
     * 是否水平镜像。
     * 前置摄像头的预览默认是镜像的（像照镜子），而分析帧不是——标注要跟着镜像才落在正确一侧。
     * 真机验证：置 false 时蓝点正好落在他可见的那只耳朵上（true 会左右反）。
     */
    var mirror = false
        set(v) { field = v; invalidate() }

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val badge = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()

    private var data: PoseOverlay? = null

    /**
     * 平滑后的头前倾角（由界面传入，与右侧面板显示的一致）。
     * 底部读数条如果用单帧瞬时值，会出现「底部 θ -7.1°、面板 19.5°」这种同屏两个数打架的情况——
     * 用户只会觉得程序算错了。所以 θ 一律显示平滑值；CVA 与视角保持单帧（它们本来就是用来解释这一帧几何的）。
     */
    private var smoothedTheta: Double? = null
    private var dw = 0f
    private var dh = 0f
    private var ox = 0f
    private var oy = 0f

    fun render(d: PoseOverlay?, smoothedThetaDeg: Double? = null) {
        data = d
        smoothedTheta = smoothedThetaDeg
        invalidate()
    }

    private fun px(nx: Float) = ox + (if (mirror) 1f - nx else nx) * dw
    private fun py(ny: Float) = oy + ny * dh

    private fun dp(v: Float) = v * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = data ?: return
        if (width == 0 || height == 0) return

        // fitCenter：按 3:4 等比放进 View，居中
        val viewAspect = width.toFloat() / height
        if (viewAspect > IMG_ASPECT) {
            dh = height.toFloat(); dw = dh * IMG_ASPECT
        } else {
            dw = width.toFloat(); dh = dw / IMG_ASPECT
        }
        ox = (width - dw) / 2f
        oy = (height - dh) / 2f

        // 备用测量（鼻—肩）时，earX/earY 里放的其实是**鼻子**，
        // 所以连线和角标自动跟着变，只需换掉颜色和加一行说明。
        val ex = px(d.earX)
        val ey = py(d.earY)
        val sx = px(d.shoulderX)
        val sy = py(d.shoulderY)

        // ① 参考点处的水平参考线（虚）
        line.color = 0x99FFFFFF.toInt()
        line.strokeWidth = dp(1f)
        line.pathEffect = DashPathEffect(floatArrayOf(dp(6f), dp(5f)), 0f)
        canvas.drawLine(ex, ey, ox + dw, ey, line)
        line.pathEffect = null

        // ② 参考点→肩连线（CVA 依据的线；备用模式下是鼻→肩）
        line.color = if (d.viaNose) 0xFFFFB020.toInt() else WHITE
        line.strokeWidth = dp(2f)
        canvas.drawLine(ex, ey, sx, sy, line)

        // ③ 夹角弧（水平线与参考点→肩线之间）
        val ang = Math.toDegrees(atan2((sy - ey).toDouble(), (sx - ex).toDouble())).toFloat()
        val r = dp(30f)
        rect.set(ex - r, ey - r, ex + r, ey + r)
        line.color = if (d.viaNose) 0xFFFFB020.toInt() else ACCENT
        line.strokeWidth = dp(2.5f)
        canvas.drawArc(rect, 0f, ang, false, line)

        // ④ 角标文字
        label.color = if (d.viaNose) 0xFFFFB020.toInt() else ACCENT
        label.textSize = dp(11f)
        val midRad = Math.toRadians(ang / 2.0)
        val lx = ex + (r + dp(16f)) * Math.cos(midRad).toFloat()
        val ly = ey + (r + dp(16f)) * Math.sin(midRad).toFloat()
        drawTextWithBg(canvas, String.format(java.util.Locale.CHINA, "%.0f°", d.cvaDeg), lx, ly)

        // ⑤ 鼻子→耳中线（视角估计的依据）
        val nx = px(d.noseX)
        val ny = py(d.noseY)
        val mx = px(d.earMidX)
        val my = py(d.earMidY)
        line.color = 0xCCFFB020.toInt()
        line.strokeWidth = dp(1.5f)
        line.pathEffect = DashPathEffect(floatArrayOf(dp(4f), dp(4f)), 0f)
        canvas.drawLine(nx, ny, mx, my, line)
        line.pathEffect = null
        dot.color = 0xCCFFB020.toInt()
        canvas.drawCircle(nx, ny, dp(3f), dot)
        canvas.drawCircle(mx, my, dp(2.5f), dot)

        // ⑥ 关键点：耳（蓝）、肩（绿），带白圈保证在亮背景上也看得见
        //    先把**另一侧**的点画成淡色空心圈：一眼就能看出「有两只耳朵/两只肩膀，算法选的是实心这只」
        line.color = 0x66FFFFFF
        line.strokeWidth = dp(1f)
        canvas.drawCircle(px(d.farEarX), py(d.farEarY), dp(5f), line)
        line.color = 0x66FFFFFF
        canvas.drawCircle(px(d.farShoulderX), py(d.farShoulderY), dp(5f), line)

        dot.color = WHITE
        canvas.drawCircle(ex, ey, dp(7f), dot)
        canvas.drawCircle(sx, sy, dp(7f), dot)
        dot.color = ACCENT
        canvas.drawCircle(ex, ey, dp(5f), dot)
        dot.color = SAFE
        canvas.drawCircle(sx, sy, dp(5f), dot)

        // ⑦ 读数条：**放在画面下方、不压相机画面**
        //    早先压在画面顶端左右两角，结果左字左边被裁（只剩「°」）、右字顶到边，
        //    看起来就是「位置不对」。现在统一成底部一条半透明横条，左中右三段。
        //    字号/文案都做紧凑处理：画面只有约 170dp 宽，三段挤不下就会互相压字。
        label.textSize = dp(9.5f)
        val lvlColor = when {
            d.thetaDeg < 15.0 -> SAFE
            d.thetaDeg < 30.0 -> WARN
            else -> DANGER
        }
        val barH = dp(16f)
        val barTop = oy + dh - barH
        // 视角偏正 → 数值不可信：读数条也不给具体数字，只用「--」占位并标红视角。
        // 否则会出现「chip 说不可信、底栏却显示 42.5°」的自相矛盾。
        val va = d.viewAngleDeg
        val viewBad = va != null && va > 50.0
        if (barTop > oy + dh * 0.5f) {          // 画面够高才画，避免小尺寸时挤占人脸
            // 读数条按 **View 宽度**排布，不用 ox/dw。
            // 实测这条 View 的左边缘有约 24px 被父布局（圆角 bg_preview）裁掉，
            // 从 0 起画会把第一个字切掉（「CVA」只剩「VA」/「/A」）。
            // 所以左右各留 26dp 安全边距——读数条是纯文字信息，不需要贴边。
            val barL = dp(26f)
            val barR = width.toFloat() - dp(26f)
            badge.color = 0xB3000000.toInt()
            canvas.drawRect(barL, barTop, barR, oy + dh, badge)
            val ty = barTop + barH * 0.70f
            label.color = ACCENT
            canvas.drawText(
                if (viewBad) "CVA --" else String.format(java.util.Locale.CHINA, "CVA %.0f°", d.cvaDeg),
                barL + dp(6f), ty, label
            )
            label.color = if (viewBad) DANGER else lvlColor
            // θ 用**平滑值**（与右侧面板一致），负角写成「θ -7.1°」避免和减号混淆
            val shownTheta = smoothedTheta ?: d.thetaDeg
            val mid = if (viewBad) "θ --"
            else String.format(java.util.Locale.CHINA, "θ %+.1f°", shownTheta)
            canvas.drawText(mid, (barL + barR) / 2f - label.measureText(mid) / 2f, ty, label)
            d.viewAngleDeg?.let { va ->
                label.color = if (va <= 50.0) SAFE else DANGER
                // 备用测量时把「视角」换成「鼻测」提示，让用户知道当前不是标准 CVA
                val vr = if (d.viaNose) String.format(java.util.Locale.CHINA, "鼻测 %.0f°", va)
                else String.format(java.util.Locale.CHINA, "视角 %.0f°", va)
                canvas.drawText(vr, barR - dp(6f) - label.measureText(vr), ty, label)
            }
        }

        // ⑧ 关键点可信度告警：耳到肩的竖直距离太小 → 肩膀点被"猜"到了脖子上，
        //    此时 CVA 会明显偏大（真机上 49° 视角时肩膀点落在下巴高度，θ 直接飙到 49°）。
        //    正常人耳屏到肩峰约 20~25cm，画面里通常占高度 0.2 以上。
        //    位置：**左对齐**在读数条上方。左上/右侧都被占用（左边缘会被父布局裁、右下是吉祥物），
        //    所以取「左边靠内 26dp + 贴着读数条上方」这个唯一不冲突的位置。
        val earShoulderDy = kotlin.math.abs(d.shoulderY - d.earY)
        if (earShoulderDy < 0.16f) {
            drawTextWithBg(
                canvas,
                "角度会偏大",
                dp(26f), barTop - dp(20f),
                alignLeft = true, textColor = DANGER
            )
        }
    }

    private fun drawTextWithBg(
        canvas: Canvas,
        text: String,
        cx: Float,
        cy: Float,
        alignLeft: Boolean = false,
        alignRight: Boolean = false,
        textColor: Int = WHITE,
    ) {
        val w = label.measureText(text)
        val padH = dp(4f)
        val padV = dp(3f)
        val left = if (alignLeft) cx else if (alignRight) cx - w else cx - w / 2f
        rect.set(left - padH, cy - label.textSize - padV, left + w + padH, cy + padV)
        badge.color = 0xB3000000.toInt()
        canvas.drawRoundRect(rect, dp(4f), dp(4f), badge)
        label.color = textColor
        canvas.drawText(text, left, cy - dp(2f), label)
    }
}
