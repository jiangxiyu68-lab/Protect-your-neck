package com.example.cervicalguard.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.cervicalguard.R
import com.example.cervicalguard.data.Growth
import com.example.cervicalguard.data.Outfit
import com.example.cervicalguard.data.Outfits
import com.example.cervicalguard.data.Wallet
import com.example.cervicalguard.data.Wardrobe

/**
 * 「小鹿衣橱」换装页（v1.2 激励模式）。
 *
 * 职责边界很明确：**状态只从 [Wallet] / [Wardrobe] 读，买与穿都交给它们写**。
 * 页面自己不持有任何内存态（除了控件引用），所以：
 *  - 退出重进 / 系统回收后重建，显示的都是持久化后的真实状态；
 *  - 购买扣币是 [Wardrobe.buy] 内部调 [Wallet.spend] 完成的，不存在两套账。
 *
 * 列表行是代码动态生成的（[Outfits.sorted] 的价格升序），不写 8 份重复 XML：
 * 改价格/稀有度只需改数据层，UI 自动跟随。
 *
 * 视觉风格与主界面一致：深色底（@color/bg）+ 圆角卡片（@color/surface / @color/stroke），
 * 文字一律浅色（@color/text / text_dim / text_faint），不会出现白底浅字。
 */
class WardrobeActivity : AppCompatActivity() {

    private lateinit var wallet: Wallet
    private lateinit var wardrobe: Wardrobe
    private lateinit var growth: Growth

    private lateinit var coinText: TextView
    private lateinit var equippedText: TextView
    private lateinit var ownedCountText: TextView
    private lateinit var outfitList: LinearLayout
    private lateinit var mascot: MascotView

    // 颜色统一从 colors.xml 取，保证和主界面/吉祥物同色
    private val cAccent: Int by lazy { ContextCompat.getColor(this, R.color.accent) }
    private val cSurface: Int by lazy { ContextCompat.getColor(this, R.color.surface) }
    private val cSurfaceAlt: Int by lazy { ContextCompat.getColor(this, R.color.surface_alt) }
    private val cStroke: Int by lazy { ContextCompat.getColor(this, R.color.stroke) }
    private val cText: Int by lazy { ContextCompat.getColor(this, R.color.text) }
    private val cTextDim: Int by lazy { ContextCompat.getColor(this, R.color.text_dim) }
    private val cTextFaint: Int by lazy { ContextCompat.getColor(this, R.color.text_faint) }
    private val cSafe: Int by lazy { ContextCompat.getColor(this, R.color.safe) }
    private val cWarn: Int by lazy { ContextCompat.getColor(this, R.color.warn) }

    /** 「传说」标签的金色，与「小皇冠」的描金一致。 */
    private val cLegend = 0xFFD4A017.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wardrobe)
        SystemBars.apply(this)

        wallet = Wallet(this)
        wardrobe = Wardrobe(this)
        growth = Growth(this)

        coinText = findViewById(R.id.coinText)
        equippedText = findViewById(R.id.equippedText)
        ownedCountText = findViewById(R.id.ownedCountText)
        outfitList = findViewById(R.id.outfitList)
        mascot = findViewById(R.id.mascot)

        // 纯色页面不需要相机底衬（showBackdrop 是给「叠在相机画面上」用的）
        mascot.showBackdrop = false
        // 衣橱是正向反馈场景，固定用 SAFE 的开心表情；姿态不参与换装预览
        mascot.render(MascotView.State.SAFE, growth.level(), wardrobe.equipped())

        findViewById<View>(R.id.backBtn).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        // 每次回到页面都重新读一次持久化状态：主界面可能刚结算过积分，或进程被回收后重建
        refreshAll()
    }

    /** 重新读余额 / 拥有 / 装备，并刷新全部 UI。 */
    private fun refreshAll() {
        val coins = wallet.coins
        val owned = wardrobe.owned()

        // 数据自愈：装备记录指向一件不存在/未拥有的时装时，回退到「不穿」
        var equipped = wardrobe.equipped()
        if (equipped.isNotEmpty() && !owned.contains(equipped)) {
            wardrobe.unequip()
            equipped = Outfits.NONE
        }

        coinText.text = "积分 $coins"
        ownedCountText.text = "已拥有 ${owned.size}/${Outfits.ALL.size}"
        equippedText.text = Outfits.byId(equipped)?.let { "已穿：${it.name}" }
            ?: "未穿时装 · 显示等级配饰"

        // 只换衣服不动姿势 → 实时预览，没有整页重建的闪烁
        mascot.setOutfit(equipped)

        rebuildList(coins, owned, equipped)
    }

    private fun rebuildList(coins: Int, owned: Set<String>, equipped: String) {
        outfitList.removeAllViews()
        for (outfit in Outfits.sorted()) {
            outfitList.addView(createRow(outfit, coins, owned, equipped))
        }
    }

    /** 一行一件时装：色条 + 名称/稀有度/描述 + 价格 + 状态按钮。 */
    private fun createRow(
        outfit: Outfit,
        coins: Int,
        owned: Set<String>,
        equipped: String,
    ): View {
        val isOwned = owned.contains(outfit.id)
        val isEquipped = equipped == outfit.id
        val affordable = coins >= outfit.price

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rowBackground(isEquipped, outfit.accent)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }

        // 左侧色条：用该时装的主题色，列表里一眼分得清
        row.addView(View(this).apply {
            background = solidRounded(withAlpha(outfit.accent, if (isEquipped) 255 else 160), dpF(2f))
            layoutParams = LinearLayout.LayoutParams(dp(4), dp(34)).apply { marginEnd = dp(10) }
        })

        // 中间：名称 + 稀有度标签 + 一句话描述
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val nameRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        nameRow.addView(TextView(this).apply {
            text = outfit.name
            setTextColor(if (isEquipped) cAccent else cText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
        })
        nameRow.addView(tierChip(outfit))
        info.addView(nameRow)
        info.addView(TextView(this).apply {
            text = outfit.desc
            setTextColor(cTextFaint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2) }
        })
        row.addView(info)

        // 右侧：价格 + 状态按钮
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                dp(96),
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
        }
        right.addView(TextView(this).apply {
            text = "${outfit.price} 积分"
            setTextColor(if (isOwned) cTextFaint else cWarn)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            gravity = Gravity.CENTER
        })
        right.addView(actionButton(outfit, isOwned, isEquipped, affordable))
        row.addView(right)

        return row
    }

    /**
     * 状态按钮，三态：
     *  - 未拥有：主色可点「购买 60」；余额不足时置灰（仍可点，点击给出还差多少的 Toast）；
     *  - 已拥有未穿：描边「穿上」；
     *  - 已装备：实心「脱下」。
     */
    private fun actionButton(
        outfit: Outfit,
        isOwned: Boolean,
        isEquipped: Boolean,
        affordable: Boolean,
    ): TextView {
        val label: String
        val textColor: Int
        val bg: StateListDrawable

        when {
            isEquipped -> {
                label = "脱下"
                textColor = Color.WHITE
                bg = actionable(cAccent, cAccent, 0, mix(cAccent, Color.BLACK, 0.18f))
            }
            isOwned -> {
                label = "穿上"
                textColor = cAccent
                bg = actionable(cSurface, cAccent, 1, mix(cSurface, cAccent, 0.22f))
            }
            affordable -> {
                label = "购买 ${outfit.price}"
                textColor = Color.WHITE
                bg = actionable(cAccent, cAccent, 0, mix(cAccent, Color.BLACK, 0.18f))
            }
            else -> {
                // 置灰但保持可点击：点下去要能告诉用户还差多少积分，禁用就没法提示了
                label = "购买 ${outfit.price}"
                textColor = cTextFaint
                bg = actionable(cSurfaceAlt, cStroke, 1, mix(cSurfaceAlt, Color.WHITE, 0.06f))
            }
        }

        return TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.DEFAULT_BOLD
            background = bg
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(34)
            ).apply { topMargin = dp(6) }
            setOnClickListener { handleAction(outfit) }
        }
    }

    /**
     * 按钮点击：以**点击时的持久化状态**为准（列表可能还没刷新），
     * 避免「刚买完又点了一次购买」这类竞态误操作。
     */
    private fun handleAction(outfit: Outfit) {
        val owned = wardrobe.isOwned(outfit.id)
        val equipped = wardrobe.equipped()

        when {
            equipped == outfit.id -> {
                wardrobe.unequip()
                Toast.makeText(this, "已脱下「${outfit.name}」", Toast.LENGTH_SHORT).show()
                refreshAll()
            }
            owned -> {
                if (wardrobe.equip(outfit.id)) {
                    Toast.makeText(this, "已穿上「${outfit.name}」", Toast.LENGTH_SHORT).show()
                }
                refreshAll()
            }
            else -> buyOutfit(outfit)
        }
    }

    private fun buyOutfit(outfit: Outfit) {
        val coins = wallet.coins
        if (coins < outfit.price) {
            val need = outfit.price - coins
            Toast.makeText(this, "积分不足，还差 $need 积分", Toast.LENGTH_SHORT).show()
            refreshAll() // 余额可能刚结算过，顺带刷新置灰状态
            return
        }

        if (wardrobe.buy(this, outfit.id)) {
            // 买完立即穿上：购买的意义就是马上看到效果
            wardrobe.equip(outfit.id)
            Toast.makeText(this, "已购买并穿上「${outfit.name}」", Toast.LENGTH_SHORT).show()
        } else {
            // 余额够却被拒绝：只可能是数据异常（已拥有/价格变化），重读一遍状态即可
            Toast.makeText(this, "购买失败，请稍后重试", Toast.LENGTH_SHORT).show()
        }
        refreshAll()
    }

    // ── 样式辅助 ─────────────────────────────────────────────

    private fun tierColor(tier: String): Int = when (tier) {
        "入门" -> cSafe
        "进阶" -> cAccent
        "稀有" -> cWarn
        "传说" -> cLegend
        else -> cTextDim
    }

    /** 稀有度小标签：半透明同色底 + 同色描边，深色底上足够醒目但不抢主次。 */
    private fun tierChip(outfit: Outfit): TextView {
        val color = tierColor(outfit.tier)
        return TextView(this).apply {
            text = outfit.tier
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setPadding(dp(6), dp(1), dp(6), dp(1))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpF(8f)
                setColor(withAlpha(color, 34))
                setStroke(dp(1), withAlpha(color, 90))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
        }
    }

    /** 行卡片：已装备 → 主色描边更粗 + 底色混一点主色；未装备 → 普通卡片。 */
    private fun rowBackground(equipped: Boolean, accent: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpF(14f)
            setColor(if (equipped) mix(cSurface, accent, 0.16f) else cSurface)
            setStroke(dp(if (equipped) 2 else 1), if (equipped) accent else cStroke)
        }

    /** 带按压反馈的圆角按钮底。 */
    private fun actionable(
        fill: Int,
        stroke: Int,
        strokeDp: Int,
        pressed: Int,
    ): StateListDrawable {
        fun layer(color: Int) = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpF(10f)
            setColor(color)
            if (strokeDp > 0) setStroke(dp(strokeDp), stroke)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), layer(pressed))
            addState(intArrayOf(), layer(fill))
        }
    }

    private fun solidRounded(color: Int, radiusPx: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(color)
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun dpF(v: Float): Float = v * resources.displayMetrics.density

    /** 给不透明色套一个 alpha（0~255）。 */
    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    /** 把 [color] 以 [amount] 的比例混进 [base]（amount = 0 时返回 base）。 */
    private fun mix(base: Int, color: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(base) + (Color.red(color) - Color.red(base)) * t).toInt(),
            (Color.green(base) + (Color.green(color) - Color.green(base)) * t).toInt(),
            (Color.blue(base) + (Color.blue(color) - Color.blue(base)) * t).toInt(),
        )
    }
}
