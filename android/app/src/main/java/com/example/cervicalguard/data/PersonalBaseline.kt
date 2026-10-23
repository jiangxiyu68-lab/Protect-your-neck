package com.example.cervicalguard.data

import android.content.Context

/**
 * 个人基线自适应（v1.1）。
 *
 * 为什么需要：固定的 15°/30° 对所有人一套标准，但**每个人的颈椎基础角度不同**——
 * 有人天生脖子前倾 20° 才算"正常"，有人 8° 就已经很僵。用固定阈值，
 * 前者永远被判"注意"（挫败），后者永远"安全"（没约束）。
 *
 * 做法：持续记录用户的**低分位姿势**（最好的那 20% 时刻），把它当作"这个人能做到的最好姿态"，
 * 阈值随之平移：
 *   warn = 基线 + 10°，danger = 基线 + 25°
 * 但**设上下限**防止跑飞：
 *   - warn 不低于 12°（低于这个数，任何人的头前伸都该提醒）
 *   - warn 不高于 22°（否则对姿态差的人等于没有约束）
 *
 * 注意：基线只从**角度可信**的帧学习（视角过正的帧不参与），否则学到的是噪声。
 */
class PersonalBaseline(context: Context) {
    private val sp = context.getSharedPreferences("baseline", Context.MODE_PRIVATE)

    companion object {
        /** 学习窗口：需要多少秒可信样本才启用自适应。 */
        const val MIN_SAMPLES_SEC = 300

        /** 阈值相对基线的偏移。 */
        const val WARN_OFFSET = 10.0
        const val DANGER_OFFSET = 25.0

        /** 阈值安全范围（防跑飞）。 */
        const val WARN_MIN = 12.0
        const val WARN_MAX = 22.0
        const val DANGER_MIN = 27.0
        const val DANGER_MAX = 40.0

        /** 每喂这么多次才落一次盘（约 10 秒）；避免每帧写 SharedPreferences。 */
        private const val WRITE_EVERY_N_FEEDS = 30
    }

    /**
     * 已学习的可信样本秒数（用于判断是否够用）。
     *
     * **踩坑（2026-10-21 实测发现）**：原来是 `sampleSec += dtSec.toInt()`，
     * 而每帧 dt 只有约 0.3 秒，`toInt()` 每次都是 0 →
     * 学一整天 sample_sec 仍然是 0，**自适应永远不会启用**（真机验证时 sample_sec=0 才暴露）。
     * 现在用 [sampleAcc] 累积小数，够 1 秒才进位写库。
     */
    var sampleSec: Int
        get() = sp.getInt("sample_sec", 0)
        private set(v) { sp.edit().putInt("sample_sec", v).apply() }

    /** 亚秒累加器（配合 sampleSec 使用）。 */
    private var sampleAcc = 0.0

    /**
     * 个人基线角（°）：用户做得最好时的 θ。
     * 用「指数滑动平均 + 只接受低于当前基线的值」来估计低分位——
     * 比存所有样本省内存，也能缓慢适应姿势改善。
     *
     * 注意：**内存值才是最新的**，落盘有节流（见 [flush]）——
     * 每帧写一次 SharedPreferences（约 3 次/秒）既伤闪存又拖慢分析线程。
     */
    var baselineDeg: Double
        get() = if (memBaseline.isNaN()) sp.getFloat("baseline_deg", Float.NaN).toDouble()
        else memBaseline
        private set(v) {
            memBaseline = v
            pendingWrites++
            if (pendingWrites >= WRITE_EVERY_N_FEEDS) flush()
        }

    private var memBaseline = Double.NaN
    private var pendingWrites = 0

    /** 把内存值落盘（onDestroy 时也要调一次）。 */
    fun flush() {
        if (pendingWrites == 0 || memBaseline.isNaN()) return
        sp.edit().putFloat("baseline_deg", memBaseline.toFloat()).apply()
        pendingWrites = 0
    }

    val ready: Boolean get() = sampleSec >= MIN_SAMPLES_SEC && !baselineDeg.isNaN()

    /**
     * 喂一个**可信**的 θ 样本（视角过正的帧不要传进来）。
     * @param dtSec 该样本代表的时长（秒）
     */
    fun feed(thetaDeg: Double, dtSec: Double) {
        if (thetaDeg.isNaN() || dtSec <= 0) return
        val cur = baselineDeg
        baselineDeg = if (cur.isNaN()) {
            thetaDeg
        } else if (thetaDeg < cur) {
            // 更好（更低）的姿势 → 快速跟上（0.15 的学习率）
            cur + (thetaDeg - cur) * 0.15
        } else {
            // 更差（更高）的姿势 → 极慢地回升（0.002），
            // 否则某天一直低头会把基线拖高，等于纵容坏姿势
            cur + (thetaDeg - cur) * 0.002
        }
        // 小数累积：每帧 dt≈0.3 秒，攒满 1 秒才写库（旧写法 toInt() 会永远得 0）
        sampleAcc += dtSec
        if (sampleAcc >= 1.0) {
            val add = sampleAcc.toInt()
            sampleAcc -= add
            sampleSec += add
        }
    }

    /** 自适应后的「注意」阈值。未就绪时返回 null（调用方回退到用户手动设置）。 */
    fun adaptiveWarnDeg(): Double? {
        if (!ready) return null
        return (baselineDeg + WARN_OFFSET).coerceIn(WARN_MIN, WARN_MAX)
    }

    /** 自适应后的「危险」阈值。 */
    fun adaptiveDangerDeg(): Double? {
        if (!ready) return null
        val w = adaptiveWarnDeg() ?: return null
        return (baselineDeg + DANGER_OFFSET).coerceIn(DANGER_MIN, DANGER_MAX)
            .coerceAtLeast(w + 10.0)
    }

    /** 重置学习（设置页可调用）。 */
    fun reset() {
        memBaseline = Double.NaN
        pendingWrites = 0
        sampleAcc = 0.0
        sp.edit().clear().apply()
    }

    /** 给用户看的说明。 */
    fun describe(): String {
        if (!ready) {
            val left = ((MIN_SAMPLES_SEC - sampleSec) / 60).coerceAtLeast(0)
            return "还在学习你的姿态（还需约 $left 分钟可信数据）"
        }
        val w = adaptiveWarnDeg() ?: return "学习中"
        val d = adaptiveDangerDeg() ?: return "学习中"
        return "你的基线 ${String.format(java.util.Locale.CHINA, "%.0f", baselineDeg)}° → " +
            "注意 ${String.format(java.util.Locale.CHINA, "%.0f", w)}° / " +
            "危险 ${String.format(java.util.Locale.CHINA, "%.0f", d)}°"
    }
}
