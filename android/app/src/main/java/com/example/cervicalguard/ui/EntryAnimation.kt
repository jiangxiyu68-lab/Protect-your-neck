package com.example.cervicalguard.ui

import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.TextView

/**
 * 入场动画工具箱（v1.3）。
 *
 * 参考多邻国的启动观感：**元素不是"淡入"，而是带着冲劲"弹进来"**。三个关键特征：
 *  1. **过冲（overshoot）**：缩放冲到 1.05~1.06 再回落，比匀速放大更有弹性；
 *  2. **错峰（stagger）**：每块晚 50~70ms，形成自上而下"依次落位"的节奏，
 *     而不是所有东西一起亮起来；
 *  3. **位移带方向**：从下方 20~30dp 飞上来，暗示"从下方升起"而不是凭空出现。
 *
 * 全部用 ViewPropertyAnimator（改的是渲染属性，不触发 layout/measure），
 * 结束后把 alpha/translation/scale 复位，避免下次进入时带着残值。
 */
object EntryAnimation {

    /** 单块动画时长（ms）。 */
    const val DUR = 340L

    /** 相邻两块之间的错峰间隔（ms）。 */
    const val STAGGER = 55L

    private fun dp(v: View, value: Float): Float = value * v.resources.displayMetrics.density

    /** 复位到"正常显示"状态（动画结束后调用，避免残值）。 */
    private fun reset(v: View) {
        v.alpha = 1f
        v.translationX = 0f
        v.translationY = 0f
        v.scaleX = 1f
        v.scaleY = 1f
    }

    /**
     * 一块内容的「弹入」：从下方 [fromTyDp] 飞上来 + 缩放 [fromScale] → 1 + 透明度 0 → 1。
     */
    fun popIn(
        v: View,
        delayMs: Long,
        fromTyDp: Float = 24f,
        fromScale: Float = 0.92f,
        duration: Long = DUR,
    ) {
        v.animate().cancel()
        v.alpha = 0f
        v.translationY = dp(v, fromTyDp)
        v.scaleX = fromScale
        v.scaleY = fromScale
        v.animate()
            .alpha(1f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setStartDelay(delayMs)
            .setDuration(duration)
            .setInterpolator(OvershootInterpolator(1.15f))
            .withEndAction { reset(v) }
            .start()
    }

    /** 一组元素依次弹入。 */
    fun staggerPop(
        views: List<View>,
        startDelay: Long,
        step: Long = STAGGER,
        fromTyDp: Float = 24f,
        fromScale: Float = 0.92f,
    ) {
        views.forEachIndexed { i, v -> popIn(v, startDelay + i * step, fromTyDp, fromScale) }
    }

    /** 从侧向滑入（并排两块：画面卡从左侧、指标卡从右侧）。 */
    fun slideIn(
        v: View,
        delayMs: Long,
        fromDxPx: Float,
        fromScale: Float = 0.94f,
        duration: Long = 380L,
    ) {
        v.animate().cancel()
        v.alpha = 0f
        v.translationX = fromDxPx
        v.scaleX = fromScale
        v.scaleY = fromScale
        v.animate()
            .alpha(1f)
            .translationX(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setStartDelay(delayMs)
            .setDuration(duration)
            .setInterpolator(OvershootInterpolator(1.1f))
            .withEndAction { reset(v) }
            .start()
    }

    /**
     * 数字滚动（0 → [to]），落定瞬间带一次小回弹。
     *
     * 多邻国那种「分数/连胜数跳出来」的关键不是滚动本身，而是
     * 滚动结束的瞬间那一下回弹——所以最后叠一个 1.18 → 1.0。
     */
    fun countUp(
        tv: TextView,
        to: Int,
        delayMs: Long,
        duration: Long = 600L,
        suffix: String = "",
    ) {
        val safeTo = to.coerceAtLeast(0)
        tv.animate().cancel()
        tv.text = "0$suffix"
        val anim = ValueAnimator.ofInt(0, safeTo).apply {
            this.duration = duration
            startDelay = delayMs
            // 先快后慢：符合"冲上来然后停住"的体感
            interpolator = DecelerateInterpolator(2.2f)
            addUpdateListener { tv.text = "${it.animatedValue}$suffix" }
        }
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                tv.text = "$safeTo$suffix"
                tv.animate()
                    .scaleX(1.16f).scaleY(1.16f).setDuration(90)
                    .withEndAction {
                        tv.animate().scaleX(1f).scaleY(1f).setDuration(180)
                            .setInterpolator(OvershootInterpolator(1.6f)).start()
                    }
                    .start()
            }
        })
        anim.start()
    }

    /** 让一块长条形（进度条）从 0 生长到目标比例的"拉长"效果。 */
    fun growWidth(fill: View, rest: View, targetFraction: Float, delayMs: Long, duration: Long = 480L) {
        val anim = ValueAnimator.ofFloat(0f, targetFraction.coerceIn(0f, 1f)).apply {
            this.duration = duration
            startDelay = delayMs
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { va ->
                val f = va.animatedValue as Float
                setWeight(fill, f.coerceAtLeast(0.0001f))
                setWeight(rest, (1f - f).coerceAtLeast(0.0001f))
            }
        }
        anim.start()
    }

    private fun setWeight(v: View, w: Float) {
        val lp = v.layoutParams as? android.widget.LinearLayout.LayoutParams ?: return
        lp.width = 0
        lp.weight = w
        v.layoutParams = lp
    }
}
