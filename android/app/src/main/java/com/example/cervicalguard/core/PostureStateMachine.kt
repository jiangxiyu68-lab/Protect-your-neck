package com.example.cervicalguard.core

/**
 * 坐姿提醒状态机。
 *
 * 基础规则（v1.0）：
 *  1. 检测不到人（离席）→ 危险计时清零
 *  2. 进入「危险」态并持续 dangerHoldSeconds 秒 → 触发一次提醒
 *  3. 提醒后进入 cooldownSeconds 秒冷却，期间不再提醒
 *  4. 冷却结束且仍危险 → 立即再次提醒
 *  5. 危险途中恢复安全 → 计时清零
 *
 * v1.1 新增两条**互补**的触发条件（都走同一个冷却，不会连环轰炸）：
 *
 *  A. **累计低头**（cumulative）：滑动窗口内累计危险时长超阈值也提醒。
 *     为什么需要：旧规则只看「连续」危险，用户只要每 10 秒抬一下头，
 *     计时就永远清零 → **一直在低头却一次都不提醒**。实测一天里这种情况很常见。
 *
 *  B. **疲劳累积**（fatigue）：连续工作越久，判定越敏感（hold 时间按比例缩短）。
 *     为什么需要：颈部疲劳是**累积**的。工作 3 小时后，低头 30 秒的伤害
 *     远大于刚开始工作时的同样 30 秒，旧规则对两者一视同仁。
 */
data class AlertEvent(val time: Double, val reason: String)

class PostureStateMachine(
    private val dangerHoldSeconds: Double = 180.0,
    private val cooldownSeconds: Double = 600.0,
    /** 累计低头窗口（秒）；只统计这个时间窗内的危险时长。 */
    private val cumulativeWindowSec: Double = 900.0,
    /** 窗口内累计危险达到这么多秒就提醒。 */
    private val cumulativeLimitSec: Double = 300.0,
    /** 连续工作多久后开始降低 hold 阈值（秒）。 */
    private val fatigueAfterSec: Double = 3600.0,
    /** 疲劳最多把 hold 缩短到这么多倍（0.6 = 最短 60%）。 */
    private val fatigueMinFactor: Double = 0.6,
) {
    private var dangerSince: Double? = null
    private var lastAlertAt: Double? = null

    /** 会话起点（第一次更新时记），用于疲劳模型。 */
    private var sessionStart: Double? = null

    /** 滑动窗口内的危险样本（时间, 时长秒）。 */
    private val dangerSamples = ArrayDeque<Pair<Double, Double>>()

    /** 累计窗口内的危险总秒数（供界面显示进度）。 */
    var cumulativeDangerSec: Double = 0.0
        private set

    /**
     * @param dtSec 本次更新代表的时长（秒）；用于累计与疲劳，传 0 退化为旧行为。
     */
    fun update(
        now: Double,
        level: Level,
        personDetected: Boolean,
        dtSec: Double = 0.0,
    ): AlertEvent? {
        if (sessionStart == null) sessionStart = now

        if (!personDetected) {
            // 离席：连续计时清零；累计样本保留（人只是站起来喝口水），
            // 但窗口按时间自然淘汰，不会一直背着。
            dangerSince = null
            prune(now)
            return null
        }

        prune(now)

        if (level != Level.DANGER) {
            // 关键改动：**不再清零累计**。用户每 10 秒抬一下头就永远不提醒，
            // 这正是旧规则最大的漏报来源。
            dangerSince = null
            return null
        }

        if (dangerSince == null) dangerSince = now
        if (dtSec > 0) {
            dangerSamples.addLast(now to dtSec)
            cumulativeDangerSec += dtSec
        }

        // 冷却期内不提醒（先判，避免白算）
        lastAlertAt?.let {
            if (now - it < cooldownSeconds) return null
        }

        // 条件 B：疲劳模型 → hold 越久越短
        val hold = effectiveHoldSeconds(now)

        // 条件 A：连续危险够久
        val since = dangerSince ?: return null
        val continuousSec = now - since
        if (continuousSec >= hold) {
            lastAlertAt = now
            // 提醒后重置起点：否则危险持续时冷却一过就立刻再提醒（真机曾每秒一震）
            dangerSince = now
            clearCumulative()
            return AlertEvent(
                now,
                if (hold < dangerHoldSeconds) {
                    "已连续工作 ${(workMinutes(now))} 分钟，持续低头 ${continuousSec.toInt()} 秒"
                } else {
                    "持续低头超过 ${hold.toInt()} 秒"
                }
            )
        }

        // 条件 B：累计危险够多（捕捉「反复低头」）
        if (cumulativeDangerSec >= cumulativeThreshold(now)) {
            lastAlertAt = now
            dangerSince = now
            val cum = cumulativeDangerSec
            clearCumulative()
            return AlertEvent(
                now,
                "${(cumulativeWindowSec / 60).toInt()} 分钟内累计低头 ${cum.toInt()} 秒"
            )
        }

        return null
    }

    /** 疲劳后的 hold 阈值：工作越久越敏感，最短 [fatigueMinFactor] 倍。 */
    private fun effectiveHoldSeconds(now: Double): Double {
        val start = sessionStart ?: return dangerHoldSeconds
        val worked = now - start
        if (worked <= fatigueAfterSec) return dangerHoldSeconds
        // 超过 1 小时后线性收紧，最多收到 fatigueMinFactor
        val extra = ((worked - fatigueAfterSec) / fatigueAfterSec).coerceIn(0.0, 1.0)
        val factor = 1.0 - (1.0 - fatigueMinFactor) * extra
        return (dangerHoldSeconds * factor).coerceAtLeast(10.0)
    }

    /** 疲劳后累计阈值也同步收紧（同样的道理）。 */
    private fun cumulativeThreshold(now: Double): Double {
        val start = sessionStart ?: return cumulativeLimitSec
        val worked = now - start
        if (worked <= fatigueAfterSec) return cumulativeLimitSec
        val extra = ((worked - fatigueAfterSec) / fatigueAfterSec).coerceIn(0.0, 1.0)
        return (cumulativeLimitSec * (1.0 - 0.3 * extra)).coerceAtLeast(60.0)
    }

    private fun workMinutes(now: Double): Int {
        val start = sessionStart ?: return 0
        return ((now - start) / 60).toInt()
    }

    /** 淘汰滑窗外的样本。 */
    private fun prune(now: Double) {
        val floor = now - cumulativeWindowSec
        while (dangerSamples.isNotEmpty() && dangerSamples.first().first < floor) {
            cumulativeDangerSec -= dangerSamples.removeFirst().second
        }
        if (cumulativeDangerSec < 0) cumulativeDangerSec = 0.0
    }

    private fun clearCumulative() {
        dangerSamples.clear()
        cumulativeDangerSec = 0.0
    }
}
