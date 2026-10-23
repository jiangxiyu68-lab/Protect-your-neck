package com.example.cervicalguard.core

/**
 * 「坐姿分」（0~100）。
 *
 * 为什么要有它：秒数和百分比不直观——"安全 18分10秒 · 84%"这种信息用户记不住也用不上。
 * 单一分数便于横向比较、便于看趋势，也便于一眼判断"今天表现如何"。
 * （参考竞品 SuperShrimp：它把 0~100 分作为核心指标，确实比原始时长好懂。）
 *
 * 计分口径刻意做得**简单、可解释**，不玩加权玄学：
 *   安全秒数计满分、注意秒数计半分、危险秒数不计分
 *   score = (safe×100 + warn×50) / (safe + warn + danger)
 *
 * 样本太少（默认 <60 秒）返回 null——避免"守了 10 秒就拿 100 分"的自欺。
 */
object PostureScore {

    /** 低于这个时长不给分（数据不足，分数没有意义）。 */
    const val MIN_SECONDS = 60

    /** 「达标」线：连续天数按这个分数判定（与界面上的「良好」一致）。 */
    const val TARGET_SCORE = 75

    fun of(safeSec: Int, warnSec: Int, dangerSec: Int, minSeconds: Int = MIN_SECONDS): Int? {
        val total = safeSec + warnSec + dangerSec
        if (total < minSeconds) return null
        return Math.round((safeSec * 100.0 + warnSec * 50.0) / total).toInt().coerceIn(0, 100)
    }

    /** 分数对应的口语化评价。 */
    fun grade(score: Int): String = when {
        score >= 90 -> "很棒"
        score >= 75 -> "良好"
        score >= 60 -> "一般"
        else -> "需注意"
    }

    /** 分数对应的配色资源名（由调用方映射到具体颜色）。 */
    fun gradeKey(score: Int): String = when {
        score >= 75 -> "safe"
        score >= 60 -> "warn"
        else -> "danger"
    }
}
