package com.example.cervicalguard.data

import android.content.Context

/**
 * 小鹿时装目录（v1.2）。
 *
 * 每件时装只有一个 [id]，**渲染由 [com.example.cervicalguard.ui.MascotView] 按 id 负责**
 * （自绘，不依赖图片资源 —— 与吉祥物本身一致，换机不糊、体积不涨）。
 *
 * 定价跨度刻意拉开：最便宜的约 1~2 天正常使用能买到，最贵的需要一周左右，
 * 让「今天就能换一件」和「长期目标」同时存在。
 */
data class Outfit(
    val id: String,
    val name: String,
    val price: Int,
    val desc: String,
    /** 主题色：列表卡片与预览用，与 MascotView 里的绘制颜色保持一致。 */
    val accent: Int,
    /** 稀有度标签（纯展示）。 */
    val tier: String,
)

object Outfits {

    const val NONE = ""

    val ALL: List<Outfit> = listOf(
        Outfit(
            "scarf", "红围巾", 60, "最简单的一条，挡风也挡低头", 0xFFE4572E.toInt(), "入门"
        ),
        Outfit(
            "glasses", "圆眼镜", 120, "看起来更有学问，也提醒你别凑太近", 0xFF5B7DB1.toInt(), "入门"
        ),
        Outfit(
            "bowtie", "小领结", 180, "正装鹿，开会专用", 0xFFB0446E.toInt(), "进阶"
        ),
        Outfit(
            "hat", "报童帽", 240, "经典款，脖子再长也压得住", 0xFF6B4E2E.toInt(), "进阶"
        ),
        Outfit(
            "headphone", "头戴耳机", 320, "和你同款（但它的耳朵没被挡住）", 0xFF3E4C59.toInt(), "进阶"
        ),
        Outfit(
            "backpack", "小书包", 420, "背上就是上学鹿", 0xFF2E7D6B.toInt(), "稀有"
        ),
        Outfit(
            "cape", "披风", 600, "挺直脖子的英雄", 0xFF7A3E9D.toInt(), "稀有"
        ),
        Outfit(
            "crown", "小皇冠", 900, "连续守护的证明，最贵也最闪", 0xFFD4A017.toInt(), "传说"
        ),
    )

    fun byId(id: String?): Outfit? = ALL.firstOrNull { it.id == id }

    /** 按价格升序（换装页默认顺序）。 */
    fun sorted(): List<Outfit> = ALL.sortedBy { it.price }
}

/**
 * 衣橱：拥有关系 + 当前装备（SharedPreferences 持久化）。
 *
 * 装备语义：**同一时间只穿一套**（[Outfits.NONE] = 不穿，显示等级自带配饰）。
 * 这样界面只有「已装备/已拥有/未拥有」三态，不容易做出"穿了帽子又穿皇冠"的叠加怪。
 */
class Wardrobe(context: Context) {

    private val sp = context.getSharedPreferences("wardrobe", Context.MODE_PRIVATE)

    /** 已拥有的时装 id（逗号分隔）。默认送一条围巾，让用户第一次进来就能穿上看看。 */
    fun owned(): Set<String> {
        val raw = sp.getString("owned", null) ?: return setOf("scarf").also {
            sp.edit().putString("owned", "scarf").apply()
        }
        return raw.split(',').filter { it.isNotBlank() }.toSet()
    }

    fun isOwned(id: String): Boolean = owned().contains(id)

    /**
     * 购买：余额够就扣款并加入已拥有。
     * @return true = 购买成功
     */
    fun buy(context: Context, id: String): Boolean {
        val o = Outfits.byId(id) ?: return false
        if (isOwned(id)) return false
        val wallet = Wallet(context)
        if (!wallet.spend(o.price)) return false
        val set = owned().toMutableSet().apply { add(id) }
        sp.edit().putString("owned", set.joinToString(",")).apply()
        return true
    }

    /** 当前装备的时装 id；[Outfits.NONE] = 没穿。 */
    fun equipped(): String = sp.getString("equipped", Outfits.NONE) ?: Outfits.NONE

    /** 穿上（必须已拥有）。传 [Outfits.NONE] 表示脱下。 */
    fun equip(id: String): Boolean {
        if (id != Outfits.NONE && !isOwned(id)) return false
        sp.edit().putString("equipped", id).apply()
        return true
    }

    fun unequip() {
        sp.edit().putString("equipped", Outfits.NONE).apply()
    }
}
