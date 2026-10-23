package com.example.cervicalguard.data

import android.content.Context

/** 阈值与提醒参数配置（SharedPreferences 持久化，可被设置页修改）。 */
class Thresholds(private val context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** 头前倾角达到此值 → 「注意」。 */
    var warnDeg: Double
        get() = sp.getFloat("warn_deg", 15f).toDouble()
        set(v) { sp.edit().putFloat("warn_deg", v.toFloat()).apply() }

    /** 头前倾角达到此值 → 「危险」。 */
    var dangerDeg: Double
        get() = sp.getFloat("danger_deg", 30f).toDouble()
        set(v) { sp.edit().putFloat("danger_deg", v.toFloat()).apply() }

    /** 危险持续多少秒触发提醒。 */
    var holdSeconds: Double
        get() = sp.getFloat("hold_sec", 180f).toDouble()
        set(v) { sp.edit().putFloat("hold_sec", v.toFloat()).apply() }

    /** 提醒后的冷却秒数。 */
    var cooldownSeconds: Double
        get() = sp.getFloat("cooldown_sec", 600f).toDouble()
        set(v) { sp.edit().putFloat("cooldown_sec", v.toFloat()).apply() }

    /** 危险提醒时是否同时震动（与语音播报并行，默认开）。 */
    var vibrateEnabled: Boolean
        get() = sp.getBoolean("vibrate", true)
        set(v) { sp.edit().putBoolean("vibrate", v).apply() }

    /**
     * 提醒时是否附上那一刻的画面（默认开）。
     *
     * 看数字没感觉，看一眼自己的样子立刻就懂该怎么坐——这是参考竞品后加的能力。
     * 图像只在本机内存里交给系统通知，**不写任何文件**；关掉则只发文字提醒。
     * 注意：锁屏上会显示通知内容，不想被旁人看到画面就关掉它。
     */
    var snapshotEnabled: Boolean
        get() = sp.getBoolean("snapshot", true)
        set(v) { sp.edit().putBoolean("snapshot", v).apply() }

    /**
     * 是否启用「个人基线自适应」（默认**关**）。
     *
     * 关着的时候完全按用户手动设的 15°/30°，行为与 v1.0 一致；
     * 打开后阈值会按用户自己的最佳姿态平移，适合「一直被判危险、但这已经是我的极限」的人。
     * 默认关是为了**不悄悄改变用户已经习惯的判定**——自适应必须由用户明确开启。
     */
    var adaptiveEnabled: Boolean
        get() = sp.getBoolean("adaptive", false)
        set(v) { sp.edit().putBoolean("adaptive", v).apply() }

    /** 真正生效的「注意」阈值：自适应就绪且已开启时用基线推出的值，否则用手动值。 */
    fun effectiveWarnDeg(): Double {
        if (!adaptiveEnabled) return warnDeg
        return PersonalBaseline(context).adaptiveWarnDeg() ?: warnDeg
    }

    /** 真正生效的「危险」阈值。 */
    fun effectiveDangerDeg(): Double {
        if (!adaptiveEnabled) return dangerDeg
        return PersonalBaseline(context).adaptiveDangerDeg() ?: dangerDeg
    }
}
