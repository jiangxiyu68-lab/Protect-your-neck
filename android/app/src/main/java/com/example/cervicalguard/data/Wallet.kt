package com.example.cervicalguard.data

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 积分账户（v1.2 激励模式）。
 *
 * 设计原则与成长经验（[Growth]）一致：**积分只能靠真实健康行为赚到，且结算幂等**。
 *  - 每累计 [SEC_PER_COIN] 秒「安全」坐姿 → 1 积分；
 *  - 完成一节放松操 → 额外 [RELAX_BONUS] 积分；
 *  - 只结算**新增**的安全时长（存当天已结算秒数），刷新多少次都不会重复发币；
 *  - 跨天重置日计数，但**余额永不减少**（除购买时装）。
 *
 * 为什么要单独一个账户而不复用经验：经验是「等级进度」（只升不降、不能花），
 * 积分是「可消费货币」。两者混在一起会导致「花掉积分就掉级」这种荒谬体验。
 */
class Wallet(context: Context) {

    private val sp: SharedPreferences = context.getSharedPreferences("wallet", Context.MODE_PRIVATE)

    companion object {
        /** 多少秒安全坐姿换 1 积分。60 秒 = 1 分钟 → 1 小时健康坐姿约 60 积分。 */
        const val SEC_PER_COIN = 60

        /** 完成一节放松操的积分奖励。 */
        const val RELAX_BONUS = 5

        /** 单日安全时长的积分上限折算（防止后台异常刷分）：8 小时。 */
        const val MAX_DAILY_SEC = 8 * 3600
    }

    /** 当前积分余额。 */
    val coins: Int
        get() = sp.getInt("coins", 0)

    /** 今日已结算的安全秒数（内部用）。 */
    private var creditedSec: Int
        get() = sp.getInt("credited_sec", 0)
        set(v) { sp.edit().putInt("credited_sec", v).apply() }

    private var creditDay: String
        get() = sp.getString("credit_day", "") ?: ""
        set(v) { sp.edit().putString("credit_day", v).apply() }

    /**
     * 按「今天累计安全秒数」结算积分（幂等）。
     *
     * @param todaySafeSec 今天累计处于安全坐姿的秒数（来自数据库统计，只增不减）
     * @return 本次新发放的积分（0 表示没有新增）
     */
    fun settle(todaySafeSec: Int): Int {
        if (todaySafeSec <= 0) return 0
        val today = dayKey()
        if (creditDay != today) {
            // 跨天：重置日进度（余额不动）
            sp.edit().putString("credit_day", today).putInt("credited_sec", 0).apply()
        }
        val capped = todaySafeSec.coerceAtMost(MAX_DAILY_SEC)
        val already = creditedSec
        if (capped <= already) return 0

        val delta = capped - already
        val earned = delta / SEC_PER_COIN
        // 只把「整分钟」部分记为已结算，余数留到下次，避免每次刷新都丢掉零头
        creditedSec = already + earned * SEC_PER_COIN
        if (earned > 0) sp.edit().putInt("coins", coins + earned).apply()
        return earned
    }

    /** 完成一节放松操的奖励。 */
    fun addRelaxBonus(): Int {
        sp.edit().putInt("coins", coins + RELAX_BONUS).apply()
        return RELAX_BONUS
    }

    /** 花钱。余额不足返回 false 且不扣款。 */
    fun spend(amount: Int): Boolean {
        if (amount <= 0) return false
        val cur = coins
        if (cur < amount) return false
        sp.edit().putInt("coins", cur - amount).apply()
        return true
    }

    /** 直接加币（里程碑奖励等）。 */
    fun grant(amount: Int) {
        if (amount <= 0) return
        sp.edit().putInt("coins", coins + amount).apply()
    }

    /** 今天距离下一个积分还差多少秒（给界面显示进度）。 */
    fun secToNextCoin(todaySafeSec: Int): Int {
        val today = dayKey()
        val already = if (creditDay != today) 0 else creditedSec
        val progressed = (todaySafeSec.coerceAtMost(MAX_DAILY_SEC) - already).coerceAtLeast(0)
        val rem = progressed % SEC_PER_COIN
        return if (rem == 0) SEC_PER_COIN else SEC_PER_COIN - rem
    }

    private fun dayKey(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
}
