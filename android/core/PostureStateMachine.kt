package com.example.cervicalguard.core

/**
 * 坐姿提醒状态机（Kotlin 版，与 m0_verify/posture_state_machine.py 逻辑 1:1）。
 *
 * 规则：
 *  1. 检测不到人（离席）→ 危险计时清零
 *  2. 进入「危险」态并持续 dangerHoldSeconds 秒 → 触发一次提醒
 *  3. 提醒后进入 cooldownSeconds 秒冷却，期间不再提醒
 *  4. 冷却结束且仍危险 → 立即再次提醒（间隔约 = cooldown）
 *  5. 危险途中恢复安全 → 计时清零
 */
data class AlertEvent(val time: Double, val reason: String)

class PostureStateMachine(
    private val dangerHoldSeconds: Double = 180.0,  // 危险持续多久触发（秒）
    private val cooldownSeconds: Double = 600.0,    // 提醒后冷却（秒）
) {
    private var dangerSince: Double? = null
    private var lastAlertAt: Double? = null

    /** 每帧调用：now 为秒级时间戳，返回 AlertEvent 表示本帧需提醒，否则 null。 */
    fun update(now: Double, level: Level, personDetected: Boolean): AlertEvent? {
        if (!personDetected) {
            dangerSince = null
            return null
        }
        if (level != Level.DANGER) {
            dangerSince = null
            return null
        }
        if (dangerSince == null) {
            dangerSince = now
        }
        lastAlertAt?.let {
            if (now - it < cooldownSeconds) return null
        }
        val since = dangerSince ?: return null
        if (now - since >= dangerHoldSeconds) {
            lastAlertAt = now
            return AlertEvent(now, "持续低头超过 ${dangerHoldSeconds.toInt()} 秒")
        }
        return null
    }
}
