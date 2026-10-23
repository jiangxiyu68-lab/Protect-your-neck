package com.example.cervicalguard.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import kotlin.math.abs
import kotlin.math.min

/**
 * 吉祥物「颈颈鹿」（自绘）。
 *
 * 设计稿：`吉祥物-颈颈鹿.png` + `吉祥物-颈颈鹿设计.md`；
 * 几何参数与 `.workbuddy/mascot/build_mascot.py` **共用同一套 220×300 坐标**，
 * 所以改形象时两边能对得上。
 *
 * 核心表现力来自「脖子前倾度 lean」：0 = 挺直，1 = 头前伸到极限（危险），
 * 负值 = 收下巴。头会跟着脖子一起转，否则脖子弯了头还是平的，看不出低头。
 */
class MascotView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** 与 App 状态一一对应。 */
    enum class State { SLEEP, SAFE, WARN, DANGER, VIEW_BAD, RELAX }

    private data class Pose(
        val lean: Float,
        val eye: Int,
        val mouth: Int,
        val sparkle: Boolean = false,
        val sweat: Boolean = false,
        val zzz: Boolean = false,
        val note: Boolean = false,
        val dizzy: Boolean = false,
    )

    private companion object {
        // 眼睛
        const val EYE_HAPPY = 0
        const val EYE_CLOSED = 1
        const val EYE_WORRIED = 2
        const val EYE_NORMAL = 3

        // 嘴
        const val MOUTH_SMILE = 0
        const val MOUTH_FLAT = 1
        const val MOUTH_WAVY = 2
        const val MOUTH_OH = 3

        const val DESIGN_W = 220f
        const val DESIGN_H = 300f

        const val FUR = 0xFFF2B84B.toInt()
        const val FUR_DARK = 0xFFD99B2E.toInt()
        const val SPOT = 0xFFA9713A.toInt()
        const val BELLY = 0xFFFBE3B4.toInt()
        const val HOOF = 0xFF7A5A2E.toInt()
        const val INK = 0xFF2A2118.toInt()
        const val BLUSH = 0xFFF08A7A.toInt()
        const val ACCENT = 0xFF2E7DF6.toInt()
        const val ACCENT_DARK = 0xFF1E5FC0.toInt()
        const val SAFE = 0xFF34C759.toInt()
        const val WARN = 0xFFFFB020.toInt()
        const val TXT_DIM = 0xFF93A1B0.toInt()
        const val BACKDROP = 0x66000000
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()
    private val path = Path()

    private var level = 1

    /**
     * 当前穿的时装 id（[com.example.cervicalguard.data.Outfits.NONE] = 不穿）。
     * 穿了时装就**不画等级配饰**——两套叠在一起会很乱，而且时装本来就是"我自己选的"，
     * 应该盖过系统自动送的等级奖励。
     */
    private var outfit = ""
    private var pose = poseOf(State.SLEEP)
    private var displayLean = pose.lean
    private var leanAnim: ValueAnimator? = null
    private var demoAnim: ValueAnimator? = null
    private var isDemo = false

    // ── 入场动画状态（v1.3）──
    private var entranceAnim: ValueAnimator? = null
    private var entranceActive = false
    private var entranceProgress = 1f

    /** 底衬用**径向渐变**而不是实心圆：实心圆压在相机画面上像一块污渍，软渐变才像"聚光"。 */
    private var backdropShader: android.graphics.RadialGradient? = null

    /** 是否画柔和底衬（叠在相机画面上时用来保证可读性）。 */
    var showBackdrop = true
        set(v) { field = v; invalidate() }

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val r = min(w, h) * 0.5f
        backdropShader = android.graphics.RadialGradient(
            w / 2f, h / 2f, r,
            intArrayOf(0x99000000.toInt(), 0x55000000, 0x00000000),
            floatArrayOf(0f, 0.62f, 1f),
            android.graphics.Shader.TileMode.CLAMP
        )
    }

    /**
     * 设置状态、等级与时装。姿势变化用 300ms 缓动，不跳变。
     *
     * @param outfitId 时装 id（[com.example.cervicalguard.data.Outfits.NONE] = 不穿，显示等级配饰）
     */
    fun render(state: State, level: Int = 1, outfitId: String = "") {
        val target = poseOf(state)
        val levelChanged = this.level != level
        val outfitChanged = this.outfit != outfitId
        this.level = level
        this.outfit = outfitId
        if (isDemo) {
            if (outfitChanged) invalidate()
            return   // 示范动画期间不接受外部姿势，避免打架
        }
        if (target == pose && !levelChanged && !outfitChanged) return
        val poseChanged = target != pose
        pose = target
        // 只换了配饰/时装、姿势没变：重绘即可，不要白跑一次动画
        if (!poseChanged || levelChanged || outfitChanged) invalidate()
        if (!poseChanged) return
        leanAnim?.cancel()
        leanAnim = ValueAnimator.ofFloat(displayLean, target.lean).apply {
            duration = 300
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                displayLean = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /** 只换时装（换装页实时预览用，不动姿势）。 */
    fun setOutfit(outfitId: String) {
        if (outfit == outfitId) return
        outfit = outfitId
        invalidate()
    }

    /**
     * 示范模式：让颈颈鹿自己一收一放地做「收下巴」动作（放松操里用）。
     * 用 INFINITE + REVERSE 在两个 lean 之间往复。
     */
    fun setDemo(on: Boolean) {
        isDemo = on
        demoAnim?.cancel()
        if (!on) {
            leanAnim?.cancel()
            leanAnim = ValueAnimator.ofFloat(displayLean, pose.lean).apply {
                duration = 300
                addUpdateListener {
                    displayLean = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
            return
        }
        pose = poseOf(State.RELAX)
        demoAnim = ValueAnimator.ofFloat(0.38f, -0.16f).apply {
            duration = 1600
            startDelay = 200
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                displayLean = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun poseOf(s: State): Pose = when (s) {
        State.SLEEP -> Pose(0.15f, EYE_CLOSED, MOUTH_FLAT, zzz = true)
        State.SAFE -> Pose(0f, EYE_HAPPY, MOUTH_SMILE, sparkle = true)
        State.WARN -> Pose(0.45f, EYE_NORMAL, MOUTH_FLAT)
        State.DANGER -> Pose(0.95f, EYE_WORRIED, MOUTH_WAVY, sweat = true)
        State.VIEW_BAD -> Pose(0.30f, EYE_NORMAL, MOUTH_OH, dizzy = true)
        State.RELAX -> Pose(-0.10f, EYE_CLOSED, MOUTH_SMILE, note = true)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        leanAnim?.cancel()
        demoAnim?.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = min(width / DESIGN_W, height / DESIGN_H)
        val dx = (width - DESIGN_W * s) / 2f
        val dy = (height - DESIGN_H * s) / 2f

        if (showBackdrop) {
            backdropShader?.let {
                fill.shader = it
                canvas.drawCircle(width / 2f, height / 2f, min(width, height) * 0.5f, fill)
                fill.shader = null
            }
        }

        // ── 入场动画（v1.3）：绕"脚底中心"做弹性放大 + 轻微甩头 + 星星迸发 ──
        // 绕脚底而不是绕中心，视觉上像"从地面弹起来长出来"，这是多邻国那条鹿/猫头鹰的典型观感。
        var sparkT = -1f
        if (entranceActive) {
            val t = entranceProgress
            // 阻尼弹簧：t=0 → 0；中途过冲到 ~1.16；t=1 → 1
            val spring = 1.0 - kotlin.math.exp(-5.5 * t) * kotlin.math.cos(9.5 * t)
            val rot = (7.0 * kotlin.math.sin(13.0 * t) * kotlin.math.exp(-3.5 * t)).toFloat()
            // 落位前的"起跳"位移（Math.PI 是 Double，整体先算 Double 再转 Float）
            val hop = (-14.0 * s * kotlin.math.sin(Math.PI * t.coerceAtMost(1f).toDouble())).toFloat()
            canvas.save()
            canvas.translate(width / 2f, height.toFloat())
            canvas.rotate(rot.toFloat())
            canvas.scale(spring.toFloat(), spring.toFloat())
            canvas.translate(-width / 2f, -height.toFloat() + hop)
            sparkT = t
            drawBody(canvas, s, dx, dy)
            canvas.restore()
        } else {
            drawBody(canvas, s, dx, dy)
        }

        // 星星迸发：入场前半程向外飞散，后半程淡出
        if (sparkT in 0f..1f) {
            drawEntranceStars(canvas, sparkT)
        }
    }

    private fun drawBody(canvas: Canvas, s: Float, dx: Float, dy: Float) {
        canvas.save()
        canvas.translate(dx, dy)
        canvas.scale(s, s)
        drawGiraffe(canvas, displayLean)
        canvas.restore()
    }

    /**
     * 入场时从吉祥物身上向外迸出的星星。
     * 与「安全状态」的 sparkle 不同：这里是**一次性**的爆发，用完即灭，
     * 所以不能塞进 pose 里（pose 是常驻状态）。
     */
    private fun drawEntranceStars(canvas: Canvas, t: Float) {
        val cx = width / 2f
        val cy = height * 0.42f
        val maxR = min(width, height) * 0.72f
        // 0~0.45 快速飞出，0.45~1 淡出
        val out = (t / 0.45f).coerceAtMost(1f)
        val fade = if (t < 0.45f) 1f else (1f - (t - 0.45f) / 0.55f).coerceIn(0f, 1f)
        val baseAlpha = fill.alpha
        val angles = floatArrayOf(-70f, -25f, 20f, 65f, 130f, 200f)
        val colors = intArrayOf(SAFE, WARN, SAFE, WARN, SAFE, WARN)
        for (i in angles.indices) {
            val a = Math.toRadians((angles[i]).toDouble())
            val r = maxR * out * (0.55f + 0.45f * ((i % 3) / 2f))
            val px = cx + (r * Math.cos(a)).toFloat()
            val py = cy + (r * Math.sin(a)).toFloat()
            val starR = (3.5f + 2.5f * (1f - out)) * resources.displayMetrics.density
            fill.alpha = (255 * fade).toInt().coerceIn(0, 255)
            drawStar(canvas, px, py, starR, colors[i])
        }
        fill.alpha = baseAlpha
    }

    /** 播放一次入场：弹性放大 + 甩头 + 星星。 */
    fun playEntrance() {
        entranceAnim?.cancel()
        entranceActive = true
        entranceProgress = 0f
        invalidate()
        entranceAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 760
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                entranceProgress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    entranceActive = false
                    entranceProgress = 1f
                    invalidate()
                }
            })
            start()
        }
    }

    private fun drawGiraffe(c: Canvas, lean: Float) {
        // ── 脖子几何（与设计稿一致）
        val baseX = 150f
        val baseY = 196f
        val hx = 168f + 56f * lean
        val hy = 60f + 34f * lean
        val cx = 148f + 96f * lean
        val cy = 128f

        // 影子
        fill.color = 0x59000000
        oval(c, 120f, 279f, 72f, 9f)
        fill.color = FUR_DARK

        // 后腿
        for (lx in floatArrayOf(78f, 96f)) {
            fill.color = FUR_DARK
            round(c, lx, 228f, lx + 15f, 278f, 7f)
            fill.color = HOOF
            round(c, lx, 266f, lx + 15f, 278f, 5f)
        }
        // 尾巴
        stroke.color = FUR_DARK
        stroke.strokeWidth = 6f
        stroke.strokeCap = Paint.Cap.ROUND
        path.reset()
        path.moveTo(62f, 196f)
        path.quadTo(46f, 206f, 52f, 230f)
        c.drawPath(path, stroke)
        fill.color = SPOT
        oval(c, 52f, 232f, 7f, 7f)

        // 身体 + 肚皮 + 斑点
        fill.color = FUR
        oval(c, 118f, 212f, 58f, 34f)
        fill.color = BELLY
        fill.alpha = 140
        oval(c, 122f, 226f, 40f, 18f)
        fill.alpha = 255
        fill.color = SPOT
        fill.alpha = 190
        oval(c, 96f, 196f, 9f, 7.7f)
        oval(c, 120f, 202f, 7f, 6f)
        oval(c, 142f, 198f, 8f, 6.8f)
        oval(c, 108f, 224f, 6f, 5.1f)
        fill.alpha = 255

        // 前腿
        for (lx in floatArrayOf(138f, 158f)) {
            fill.color = FUR
            round(c, lx, 228f, lx + 16f, 278f, 8f)
            fill.color = HOOF
            round(c, lx, 266f, lx + 16f, 278f, 5f)
        }

        // 脖子（粗描边曲线）
        stroke.color = FUR
        stroke.strokeWidth = 30f
        stroke.strokeCap = Paint.Cap.ROUND
        path.reset()
        path.moveTo(baseX, baseY)
        path.quadTo(cx, cy, hx, hy)
        c.drawPath(path, stroke)

        // 脖子斑点（按二次贝塞尔取点）
        fill.color = SPOT
        fill.alpha = 180
        for (t in floatArrayOf(0.25f, 0.5f, 0.72f)) {
            val mt = 1 - t
            val px = mt * mt * baseX + 2 * mt * t * cx + t * t * hx
            val py = mt * mt * baseY + 2 * mt * t * cy + t * t * hy
            val off = if (t > 0.4f) 9f else -9f
            oval(c, px + off, py, 7f, 6f)
        }
        fill.alpha = 255

        // 围巾（Lv≥2；穿了时装就不画等级配饰）
        if (outfit.isEmpty() && level >= 2) {
            stroke.color = ACCENT
            stroke.strokeWidth = 11f
            path.reset()
            path.moveTo(baseX - 16f, baseY - 4f)
            path.quadTo(baseX + 2f, baseY + 8f, baseX + 18f, baseY - 6f)
            c.drawPath(path, stroke)
            fill.color = ACCENT
            path.reset()
            path.moveTo(baseX + 14f, baseY + 2f)
            path.lineTo(baseX + 20f, baseY + 24f)
            path.lineTo(baseX + 8f, baseY + 27f)
            path.close()
            c.drawPath(path, fill)
        }

        // ── 头（整组跟着 lean 旋转）
        val hgx = hx + 6f
        val hgy = hy
        c.save()
        c.rotate(lean * 20f, hgx, hgy)

        fill.color = FUR
        oval(c, hgx, hgy, 27f, 21f)
        oval(c, hgx + 22f, hgy + 5f, 16f, 13f)
        fill.color = BELLY
        fill.alpha = 178
        oval(c, hgx + 28f, hgy + 7f, 8f, 6f)
        fill.alpha = 255
        fill.color = INK
        fill.alpha = 200
        oval(c, hgx + 33f, hgy + 4f, 2.2f, 2.2f)
        fill.alpha = 255

        // 角 + 耳朵
        stroke.color = FUR_DARK
        stroke.strokeWidth = 5f
        stroke.strokeCap = Paint.Cap.ROUND
        c.drawLine(hgx - 9f, hgy - 17f, hgx - 11f, hgy - 30f, stroke)
        c.drawLine(hgx + 5f, hgy - 19f, hgx + 6f, hgy - 32f, stroke)
        fill.color = SPOT
        oval(c, hgx - 11.5f, hgy - 32f, 5.5f, 5.5f)
        oval(c, hgx + 6f, hgy - 34f, 5.5f, 5.5f)
        fill.color = FUR_DARK
        c.save()
        c.rotate(-25f, hgx - 22f, hgy - 12f)
        oval(c, hgx - 22f, hgy - 12f, 9f, 6f)
        c.restore()
        c.save()
        c.rotate(20f, hgx + 18f, hgy - 16f)
        oval(c, hgx + 18f, hgy - 16f, 9f, 6f)
        c.restore()

        // 帽子（Lv≥4）
        if (outfit.isEmpty() && level >= 4) {
            fill.color = ACCENT
            path.reset()
            path.moveTo(hgx - 24f, hgy - 22f)
            path.quadTo(hgx, hgy - 48f, hgx + 24f, hgy - 24f)
            path.close()
            c.drawPath(path, fill)
            fill.color = ACCENT_DARK
            round(c, hgx - 27f, hgy - 24f, hgx + 29f, hgy - 17f, 3.5f)
            fill.color = WARN
            oval(c, hgx, hgy - 40f, 4f, 4f)
        }

        // 眼睛
        val ex = hgx + 4f
        val ey = hgy - 3f
        stroke.color = INK
        stroke.strokeWidth = 3.4f
        stroke.strokeCap = Paint.Cap.ROUND
        when (pose.eye) {
            EYE_HAPPY -> {
                path.reset()
                path.moveTo(ex - 6f, ey)
                path.quadTo(ex, ey - 7f, ex + 6f, ey)
                c.drawPath(path, stroke)
            }
            EYE_CLOSED -> {
                path.reset()
                path.moveTo(ex - 6f, ey)
                path.quadTo(ex, ey + 6f, ex + 6f, ey)
                c.drawPath(path, stroke)
            }
            EYE_WORRIED -> {
                fill.color = INK
                oval(c, ex, ey, 4.2f, 4.2f)
                stroke.strokeWidth = 3f
                path.reset()
                path.moveTo(ex - 7f, ey - 8f)
                path.quadTo(ex, ey - 12f, ex + 6f, ey - 7f)
                c.drawPath(path, stroke)
            }
            else -> {
                fill.color = INK
                oval(c, ex, ey, 4.2f, 4.2f)
            }
        }

        // 嘴
        val mx = hgx + 28f
        val my = hgy + 11f
        stroke.color = INK
        stroke.strokeWidth = 2.8f
        when (pose.mouth) {
            MOUTH_SMILE -> {
                path.reset()
                path.moveTo(mx - 7f, my)
                path.quadTo(mx, my + 7f, mx + 7f, my - 2f)
                c.drawPath(path, stroke)
            }
            MOUTH_FLAT -> c.drawLine(mx - 7f, my, mx + 7f, my, stroke)
            MOUTH_WAVY -> {
                path.reset()
                path.moveTo(mx - 8f, my)
                path.quadTo(mx - 4f, my - 5f, mx, my)
                path.quadTo(mx + 4f, my + 5f, mx + 8f, my)
                c.drawPath(path, stroke)
            }
            MOUTH_OH -> {
                fill.color = INK
                fill.alpha = 217
                oval(c, mx, my + 1f, 5f, 6f)
                fill.alpha = 255
            }
        }

        // 腮红
        if (pose.eye == EYE_HAPPY || pose.eye == EYE_CLOSED) {
            fill.color = BLUSH
            fill.alpha = 128
            oval(c, hgx - 12f, hgy + 7f, 7f, 4.5f)
            fill.alpha = 255
        }

        // 头饰类时装（帽子/皇冠/眼镜/耳机）**必须画在旋转块内部**，
        // 否则头前倾时头饰不动，会明显离开头顶——衣橱预览固定 lean=0 看不出来，
        // 主界面 lean 最大 0.95（头转约 19°）就会穿模。（验收专员静态审查发现）
        if (outfit.isNotEmpty()) {
            drawOutfitHead(c, hgx, hgy)
        }
        c.restore()

        // 星项圈（Lv≥6）
        if (outfit.isEmpty() && level >= 6) {
            stroke.color = WARN
            stroke.strokeWidth = 5f
            path.reset()
            path.moveTo(baseX - 18f, baseY - 2f)
            path.quadTo(baseX, baseY + 14f, baseX + 18f, baseY - 6f)
            c.drawPath(path, stroke)
            drawStar(c, baseX + 6f, baseY + 20f, 8f, WARN)
        }

        // 奖牌（Lv≥8）
        if (outfit.isEmpty() && level >= 8) {
            stroke.color = ACCENT
            stroke.strokeWidth = 4f
            c.drawLine(baseX - 6f, baseY + 16f, baseX - 10f, baseY + 32f, stroke)
            fill.color = WARN
            oval(c, baseX - 10f, baseY + 40f, 10f, 10f)
            text.color = 0xFF7A4A00.toInt()
            text.textSize = 12f
            c.drawText("1", baseX - 10f, baseY + 44f, text)
        }

        // 躯干类时装（围巾/领结/书包/披风）画在旋转块外——它们系在脖子上，不该跟着头转
        if (outfit.isNotEmpty()) {
            drawOutfitBody(c, baseX, baseY)
        }

        // 附加元素
        if (pose.sparkle) {
            drawStar(c, hgx + 40f, hgy - 30f, 7f, SAFE)
            drawStar(c, hgx - 40f, hgy - 4f, 5f, SAFE)
            drawStar(c, hgx + 26f, hgy - 44f, 5f, SAFE)
        }
        if (pose.sweat) {
            fill.color = 0xFF7FD4F5.toInt()
            fill.alpha = 230
            drawDrop(c, hgx + 34f, hgy - 12f)
            drawDrop(c, hgx + 46f, hgy + 6f)
            fill.alpha = 255
        }
        if (pose.zzz) {
            text.color = TXT_DIM
            text.textAlign = Paint.Align.LEFT
            text.textSize = 15f
            c.drawText("z", hgx + 26f, hgy - 30f, text)
            text.textSize = 12f
            c.drawText("z", hgx + 40f, hgy - 44f, text)
            text.textSize = 9f
            c.drawText("z", hgx + 52f, hgy - 56f, text)
            text.textAlign = Paint.Align.CENTER
        }
        if (pose.note) {
            text.color = ACCENT
            text.textSize = 22f
            c.drawText("♪", hgx - 44f, hgy - 30f, text)
            text.textSize = 16f
            text.alpha = 178
            c.drawText("♪", hgx - 30f, hgy - 48f, text)
            text.alpha = 255
        }
        if (pose.dizzy) {
            stroke.color = WARN
            stroke.strokeWidth = 3f
            path.reset()
            path.moveTo(hgx - 20f, hgy - 48f)
            path.quadTo(hgx - 10f, hgy - 58f, hgx, hgy - 50f)
            path.quadTo(hgx + 10f, hgy - 42f, hgx + 20f, hgy - 52f)
            path.quadTo(hgx + 10f, hgy - 64f, hgx, hgy - 56f)
            path.quadTo(hgx - 12f, hgy - 46f, hgx - 20f, hgy - 54f)
            c.drawPath(path, stroke)
        }
    }

    /**
     * 画时装（v1.2）。
     *
     * 全部用几何图形自绘，与吉祥物本体同一套 220×300 设计坐标——
     * 不引图片资源，换机不糊、包体不涨，也让「时装」和「形象」风格一致。
     *
     * @param hgx/hgy 头部中心（已含 lean 旋转后的锚点）
     * @param baseX/baseY 脖子根部（围巾/领结/背包带都系在这里）
     */
    /**
     * 头饰类时装（帽子/皇冠/眼镜/耳机）。
     *
     * **调用位置很重要**：必须在头部的 `save()/rotate(lean)/restore()` 块**内部**调用，
     * 否则头前倾时头饰不跟着转，会明显离开头顶（衣橱预览 lean=0 所以看不出来）。
     */
    private fun drawOutfitHead(c: Canvas, hgx: Float, hgy: Float) {
        when (outfit) {
            "glasses" -> {
                // 圆眼镜：**必须画在眼睛上**，不是吻部。
                // 眼睛在 (hgx+4, hgy-3)（见下方绘制），吻部在 (hgx+22, hgy+5)。
                // 第一版照着"脸"的锚点画到了鼻子上（验收专员看图抓出来的）。
                val ex0 = hgx + 4f
                val ey0 = hgy - 3f
                stroke.color = 0xFF2A2118.toInt()
                stroke.strokeWidth = 3f
                // 左镜片（后侧，稍小表示透视）
                rect.set(ex0 - 13f, ey0 - 8f, ex0 - 2f, ey0 + 6f)
                c.drawOval(rect, stroke)
                // 右镜片（前侧，靠近吻部方向）
                rect.set(ex0 + 2f, ey0 - 9f, ex0 + 14f, ey0 + 7f)
                c.drawOval(rect, stroke)
                // 鼻梁
                c.drawLine(ex0 - 2f, ey0 - 1f, ex0 + 2f, ey0 - 1f, stroke)
                // 镜腿（往耳朵方向）
                c.drawLine(ex0 - 13f, ey0 - 2f, hgx - 22f, ey0 - 5f, stroke)
                // 镜片淡反光
                fill.color = 0x33FFFFFF
                oval(c, ex0 - 7f, ey0 - 2f, 3f, 3f)
                oval(c, ex0 + 8f, ey0 - 2f, 3f, 3f)
            }

            "hat" -> {
                val col = 0xFF6B4E2E.toInt()
                fill.color = col
                // 帽顶
                path.reset()
                path.moveTo(hgx - 26f, hgy - 20f)
                path.quadTo(hgx - 4f, hgy - 52f, hgx + 24f, hgy - 22f)
                path.close()
                c.drawPath(path, fill)
                // 帽檐
                fill.color = 0xFF573C22.toInt()
                round(c, hgx - 30f, hgy - 23f, hgx + 30f, hgy - 15f, 4f)
                // 帽扣
                fill.color = 0xFFD4A017.toInt()
                oval(c, hgx - 2f, hgy - 40f, 3.5f, 3.5f)
            }

            "headphone" -> {
                val col = 0xFF3E4C59.toInt()
                val pad = 0xFFDCE3EA.toInt()
                // 侧脸：只画近侧一个耳罩（远侧被头挡住）+ 头梁。
                // 耳罩位置要贴着"耳朵"——这个形象的脸朝右（吻部在 +x），耳朵在头后侧约 hgx-14，
                // 所以耳罩中心放在 hgx-16 附近，太靠后（hgx-30 之外）会像贴在后脑勺上、认不出是耳机。
                stroke.color = col
                stroke.strokeWidth = 6f
                path.reset()
                path.moveTo(hgx - 20f, hgy - 4f)
                path.quadTo(hgx - 6f, hgy - 46f, hgx + 16f, hgy - 16f)
                c.drawPath(path, stroke)
                // 近侧耳罩（覆盖耳朵位置，不与眼睛/嘴重叠）
                fill.color = col
                round(c, hgx - 27f, hgy - 14f, hgx - 5f, hgy + 14f, 9f)
                fill.color = pad
                round(c, hgx - 23f, hgy - 10f, hgx - 9f, hgy + 10f, 6f)
            }

            "crown" -> {
                val gold = 0xFFD4A017.toInt()
                val dark = 0xFFA67C0E.toInt()
                fill.color = gold
                path.reset()
                path.moveTo(hgx - 22f, hgy - 20f)
                path.lineTo(hgx - 22f, hgy - 36f)
                path.lineTo(hgx - 11f, hgy - 27f)
                path.lineTo(hgx, hgy - 42f)
                path.lineTo(hgx + 11f, hgy - 27f)
                path.lineTo(hgx + 22f, hgy - 36f)
                path.lineTo(hgx + 22f, hgy - 20f)
                path.close()
                c.drawPath(path, fill)
                fill.color = dark
                round(c, hgx - 23f, hgy - 22f, hgx + 23f, hgy - 16f, 3f)
                // 宝石
                fill.color = 0xFFE4572E.toInt()
                oval(c, hgx, hgy - 30f, 3.5f, 3.5f)
                fill.color = 0xFF2E7DF6.toInt()
                oval(c, hgx - 13f, hgy - 26f, 2.5f, 2.5f)
                oval(c, hgx + 13f, hgy - 26f, 2.5f, 2.5f)
            }
        }
    }

    /**
     * 躯干类时装（围巾/领结/书包/披风）。
     *
     * 这些系在**脖子/身上**，不该跟着头旋转，所以画在头部 rotate 块外面。
     */
    private fun drawOutfitBody(c: Canvas, baseX: Float, baseY: Float) {
        when (outfit) {
            "scarf" -> {
                val col = 0xFFE4572E.toInt()
                val dark = 0xFFB23A1E.toInt()
                stroke.color = col
                stroke.strokeWidth = 13f
                stroke.strokeCap = Paint.Cap.ROUND
                path.reset()
                path.moveTo(baseX - 19f, baseY - 5f)
                path.quadTo(baseX + 2f, baseY + 10f, baseX + 20f, baseY - 7f)
                c.drawPath(path, stroke)
                // 垂下来的一角
                fill.color = dark
                path.reset()
                path.moveTo(baseX + 15f, baseY + 1f)
                path.lineTo(baseX + 23f, baseY + 27f)
                path.lineTo(baseX + 8f, baseY + 30f)
                path.close()
                c.drawPath(path, fill)
            }

            "bowtie" -> {
                val col = 0xFFB0446E.toInt()
                fill.color = col
                path.reset()
                path.moveTo(baseX - 2f, baseY + 2f)
                path.lineTo(baseX - 22f, baseY - 9f)
                path.lineTo(baseX - 22f, baseY + 13f)
                path.close()
                c.drawPath(path, fill)
                path.reset()
                path.moveTo(baseX + 2f, baseY + 2f)
                path.lineTo(baseX + 22f, baseY - 9f)
                path.lineTo(baseX + 22f, baseY + 13f)
                path.close()
                c.drawPath(path, fill)
                fill.color = 0xFF7E2C4C.toInt()
                oval(c, baseX, baseY + 2f, 6f, 7f)
            }

            "backpack" -> {
                val col = 0xFF2E7D6B.toInt()
                // 背带（两条，跨过肩膀）
                stroke.color = col
                stroke.strokeWidth = 7f
                c.drawLine(baseX - 14f, baseY + 2f, baseX - 24f, baseY + 52f, stroke)
                c.drawLine(baseX + 14f, baseY + 2f, baseX + 6f, baseY + 52f, stroke)
                // 书包本体（画在身侧，露出一点）
                fill.color = col
                round(c, baseX - 44f, baseY + 34f, baseX - 12f, baseY + 78f, 8f)
                fill.color = 0xFF1E5A4C.toInt()
                round(c, baseX - 40f, baseY + 50f, baseX - 16f, baseY + 62f, 4f)
            }

            "cape" -> {
                val col = 0xFF7A3E9D.toInt()
                fill.color = col
                fill.alpha = 235
                // 披风：从脖子往下展开的梯形，边缘带波浪
                path.reset()
                path.moveTo(baseX - 20f, baseY - 4f)
                path.quadTo(baseX - 62f, baseY + 44f, baseX - 54f, baseY + 96f)
                path.quadTo(baseX - 26f, baseY + 84f, baseX - 4f, baseY + 96f)
                path.quadTo(baseX + 18f, baseY + 84f, baseX + 44f, baseY + 94f)
                path.quadTo(baseX + 40f, baseY + 40f, baseX + 20f, baseY - 5f)
                path.close()
                c.drawPath(path, fill)
                fill.alpha = 255
                // 领口纽扣
                fill.color = 0xFFD4A017.toInt()
                oval(c, baseX, baseY + 2f, 5f, 5f)
            }
        }
    }

    private fun oval(c: Canvas, cxx: Float, cyy: Float, rx: Float, ry: Float) {
        rect.set(cxx - rx, cyy - ry, cxx + rx, cyy + ry)
        c.drawOval(rect, fill)
    }

    private fun round(c: Canvas, l: Float, t: Float, r: Float, b: Float, rad: Float) {
        rect.set(l, t, r, b)
        c.drawRoundRect(rect, rad, rad, fill)
    }

    private fun drawStar(c: Canvas, cxx: Float, cyy: Float, r: Float, color: Int) {
        fill.color = color
        path.reset()
        for (i in 0 until 10) {
            val ang = (-90.0 + i * 36.0) * Math.PI / 180.0
            val rr = if (i % 2 == 0) r else r * 0.32f
            val px = cxx + (rr * Math.cos(ang)).toFloat()
            val py = cyy + (rr * Math.sin(ang)).toFloat()
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        c.drawPath(path, fill)
    }

    private fun drawDrop(c: Canvas, x: Float, y: Float) {
        path.reset()
        path.moveTo(x, y - 9f)
        path.quadTo(x + 7f, y + 1f, x, y + 5f)
        path.quadTo(x - 7f, y + 1f, x, y - 9f)
        path.close()
        c.drawPath(path, fill)
    }

    /** 给调试/预览用：当前是否在示范动画中。 */
    fun isDemoRunning(): Boolean = isDemo
}
