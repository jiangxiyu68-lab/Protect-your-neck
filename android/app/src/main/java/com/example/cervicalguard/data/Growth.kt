package com.example.cervicalguard.data

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 成长系统：把「坐姿分」折算成经验，驱动吉祥物颈颈鹿升级。
 *
 * 口径刻意做得**简单且幂等**（这类系统最容易写错的就是重复计分）：
 *  - 当天**最高**坐姿分计入经验，每次刷新只补差额 → 刷新多少次都不会重复加；
 *  - 每完成一次放松操（≥2 次动作）额外 +[RELAX_XP]；
 *  - **等级只升不降**：历史经验不扣减，姿势差只影响当天分数。
 *    （「一断就清零」是最劝退的设计，参考竞品也是只升不降。）
 */
class Growth(context: Context) {

    private val sp = context.getSharedPreferences("growth", Context.MODE_PRIVATE)

    companion object {
        /** 累计经验门槛：索引 i 对应 Lv.(i+1)，值是该等级所需的累计 XP。 */
        val THRESHOLDS = intArrayOf(0, 100, 220, 380, 580, 820, 1100, 1420)

        /** 完成一次放松操给的经验。 */
        const val RELAX_XP = 20

        /** 等级名称（与设计稿的配饰对应）。 */
        fun levelName(lv: Int): String = when (lv) {
            1 -> "弯着脖子"
            2 -> "开始直了"
            3 -> "直了一点"
            4 -> "明显挺起"
            5 -> "挺起来"
            6 -> "很精神"
            7 -> "接近笔直"
            else -> "完全挺直"
        }
    }

    var totalXp: Int
        get() = sp.getInt("xp", 0)
        private set(v) { sp.edit().putInt("xp", v).apply() }

    fun level(): Int {
        val xp = totalXp
        var lv = 1
        for (i in THRESHOLDS.indices) {
            if (xp >= THRESHOLDS[i]) lv = i + 1
        }
        return lv.coerceIn(1, THRESHOLDS.size)
    }

    /** 距下一级还差多少经验；已满级返回 null。 */
    fun toNextLevel(): Int? {
        val lv = level()
        if (lv >= THRESHOLDS.size) return null
        return (THRESHOLDS[lv] - totalXp).coerceAtLeast(0)
    }

    /**
     * 把今天的坐姿分补记进经验（幂等）。
     * [score] 为 null（数据不足 60 秒）时不记。
     */
    fun creditToday(score: Int?) {
        if (score == null) return
        val today = dayKey()
        if (sp.getString("credit_day", "") != today) {
            // 跨天：重置当天已记分数
            sp.edit().putString("credit_day", today).putInt("credit_score", 0).apply()
        }
        val credited = sp.getInt("credit_score", 0)
        if (score > credited) {
            totalXp = totalXp + (score - credited)
            sp.edit().putInt("credit_score", score).apply()
        }
    }

    /** 完成一次放松操。 */
    fun addRelaxXp() {
        totalXp = totalXp + RELAX_XP
    }

    private fun dayKey(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
}
