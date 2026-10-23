package com.example.cervicalguard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.cervicalguard.core.Level
import com.example.cervicalguard.core.PostureResult
import com.example.cervicalguard.core.PostureScore
import com.example.cervicalguard.data.Appearance
import com.example.cervicalguard.data.CsvExporter
import com.example.cervicalguard.data.Growth
import com.example.cervicalguard.data.Outfits
import com.example.cervicalguard.data.PersonalBaseline
import com.example.cervicalguard.data.PostureDb
import com.example.cervicalguard.data.Thresholds
import com.example.cervicalguard.data.Wallet
import com.example.cervicalguard.data.Wardrobe
import com.example.cervicalguard.ui.DataChartView
import com.example.cervicalguard.ui.EntryAnimation
import com.example.cervicalguard.ui.LandmarkOverlayView
import com.example.cervicalguard.ui.MascotView
import com.example.cervicalguard.ui.RelaxRingView
import com.example.cervicalguard.ui.SystemBars
import java.io.File
import java.util.Calendar
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var levelChip: TextView
    private lateinit var statusDetail: TextView
    private lateinit var fpsText: TextView
    private lateinit var previewView: PreviewView
    private lateinit var previewHint: TextView
    private lateinit var cvaText: TextView
    private lateinit var forwardText: TextView
    private lateinit var loadText: TextView
    private lateinit var toggleBtn: Button
    private lateinit var settingsBtn: Button
    private lateinit var dataBtn: Button
    private lateinit var relaxBtn: Button
    private lateinit var exportBtn: Button
    private lateinit var wardrobeBtn: Button
    private lateinit var statTotal: TextView
    private lateinit var scoreValue: TextView
    private lateinit var scoreGrade: TextView
    private lateinit var mascot: MascotView
    private lateinit var overlayView: LandmarkOverlayView
    private lateinit var speechText: TextView
    private lateinit var levelText: TextView
    private lateinit var coinText: TextView
    private lateinit var streakText: TextView
    private lateinit var xpText: TextView
    private lateinit var xpBarFill: View
    private lateinit var xpBarRest: View
    private lateinit var statSafe: TextView
    private lateinit var statWarn: TextView
    private lateinit var statDanger: TextView
    private lateinit var statMax: TextView
    private lateinit var barSafe: View
    private lateinit var barWarn: View
    private lateinit var barDanger: View

    private lateinit var db: PostureDb
    private lateinit var growth: Growth

    /** 积分账户与衣橱（v1.2 激励模式）。 */
    private lateinit var wallet: Wallet
    private lateinit var wardrobe: Wardrobe

    // 入场动画需要操作的分块（v1.3）
    private lateinit var rootLayout: View
    private lateinit var titleText: TextView
    private lateinit var subtitleText: TextView
    private lateinit var chipRow: View
    private lateinit var cardRow: View
    private lateinit var todayCard: View
    private lateinit var growthStrip: View

    /** 吉祥物当前的「表情状态」，供 onResume 重绘时复用（衣橱返回时没有相机帧）。 */
    private var lastMascotState = MascotView.State.SLEEP

    /** 连续达标天数：查 60 天分桶不便宜，节流缓存。 */
    private var streakCache = 0
    private var streakAtMs = 0L
    private var tick = 0
    private var surfaceAttached = false
    private var isResumed = false

    /**
     * 界面是否应该显示"实时数据"。
     * 服务停止是异步的，销毁前还会再推一两帧；若不在界面侧拦住，
     * 刚显示的「待机/守护已停止」会被最后一帧的数值覆盖掉（状态胶囊又变回「安全」）。
     */
    private var expectRunning = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) startGuard()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // 浅色主题下状态栏图标要转深色，否则时间和电量看不见（v1.5）
        SystemBars.apply(this)

        levelChip = findViewById(R.id.levelChip)
        statusDetail = findViewById(R.id.statusDetail)
        fpsText = findViewById(R.id.fpsText)
        previewView = findViewById(R.id.previewView)
        previewHint = findViewById(R.id.previewHint)
        cvaText = findViewById(R.id.cvaText)
        forwardText = findViewById(R.id.forwardText)
        loadText = findViewById(R.id.loadText)
        toggleBtn = findViewById(R.id.toggleBtn)
        settingsBtn = findViewById(R.id.settingsBtn)
        dataBtn = findViewById(R.id.dataBtn)
        relaxBtn = findViewById(R.id.relaxBtn)
        exportBtn = findViewById(R.id.exportBtn)
        wardrobeBtn = findViewById(R.id.wardrobeBtn)
        statTotal = findViewById(R.id.statTotal)
        scoreValue = findViewById(R.id.scoreValue)
        scoreGrade = findViewById(R.id.scoreGrade)
        statSafe = findViewById(R.id.statSafe)
        statWarn = findViewById(R.id.statWarn)
        statDanger = findViewById(R.id.statDanger)
        statMax = findViewById(R.id.statMax)
        barSafe = findViewById(R.id.barSafe)
        barWarn = findViewById(R.id.barWarn)
        barDanger = findViewById(R.id.barDanger)

        db = PostureDb(this)
        growth = Growth(this)
        wallet = Wallet(this)
        wardrobe = Wardrobe(this)

        // 入场动画要用到的块（v1.3）
        rootLayout = findViewById(R.id.rootLayout)
        titleText = findViewById(R.id.titleText)
        subtitleText = findViewById(R.id.subtitleText)
        chipRow = findViewById(R.id.chipRow)
        cardRow = findViewById(R.id.cardRow)
        todayCard = findViewById(R.id.todayCard)
        growthStrip = findViewById(R.id.growthStrip)
        mascot = findViewById(R.id.mascot)
        mascot.render(MascotView.State.SLEEP, growth.level(), wardrobe.equipped())
        overlayView = findViewById(R.id.overlayView)
        speechText = findViewById(R.id.speechText)
        levelText = findViewById(R.id.levelText)
        coinText = findViewById(R.id.coinText)
        streakText = findViewById(R.id.streakText)
        xpText = findViewById(R.id.xpText)
        xpBarFill = findViewById(R.id.xpBarFill)
        xpBarRest = findViewById(R.id.xpBarRest)
        refreshGrowth()
        settingsBtn.setOnClickListener { showSettings() }
        dataBtn.setOnClickListener { showData() }
        exportBtn.setOnClickListener { showExport() }
        relaxBtn.setOnClickListener { showRelax() }
        wardrobeBtn.setOnClickListener { openWardrobe() }
        // 积分胶囊也可以点进衣橱（注释里写了就让它真的能点，不然是误导）
        coinText.setOnClickListener { openWardrobe() }

        // 入场动画（v1.3）：等首帧布局完成后再播，否则拿不到真实宽高、位移会算错
        rootLayout.post { playEntryAnimation() }

        PostureService.onUpdate = { r, det ->
            runOnUiThread {
                // 停止后忽略残留帧，保持「待机」界面不被覆盖
                if (!expectRunning) return@runOnUiThread
                if (!det || r == null) {
                    setChip("未检出", R.color.text_faint)
                    statusDetail.text = "未检测到人体 · 请让身体侧面对着前置摄像头"
                    cvaText.text = "--"
                    forwardText.text = "--"
                    loadText.text = "--"
                } else {
                    val va = PostureService.lastViewAngleDeg
                    // 视角偏正时绝对数值会失真（前后位移被投影压缩、左右偏移混入）。
                    // 这时**整屏都要表达"不可信"**：只看灰色 chip + 一行小字不够，
                    // 因为右面的 CVA/负荷/分数仍是绿色的「安全」，自相矛盾。
                    val viewBad = va != null && va > PoseMonitor.MAX_ALERT_VIEW_DEG
                    val color = if (viewBad) R.color.text_faint else levelColor(r.level)
                    setChip(if (viewBad) "角度不可信" else levelText(r.level), color)
                    if (viewBad) {
                        statusDetail.text = "视角偏正 · 请侧身到 45° 以内"
                        // 说了"不可信"就别再显示具体数字：显示 42.5° 用户会当成真的，
                        // 而这个数在正视时主要由左右偏移贡献、与头前倾无关。
                        cvaText.text = "--"
                        forwardText.text = "--"
                        loadText.text = "--"
                    } else {
                        statusDetail.text = r.desc
                        cvaText.text = "${r.cvaDeg}°"
                        forwardText.text = "${r.forwardHeadDeg}°"
                        loadText.text = "${r.equivLoadKg} kg"
                    }
                    cvaText.setTextColor(getColorRes(color))
                    forwardText.setTextColor(getColorRes(color))
                    loadText.setTextColor(getColorRes(color))
                }
                tick++
                updateMascot(det, r)
                // 识别标注：把算法实际用到的耳/肩与 CVA 连线画在画面上，让「怎么算的」看得见。
                // 传入手柄的平滑 θ，保证底部读数条与右侧面板显示同一个数字。
                overlayView.render(
                    if (expectRunning) PostureService.lastOverlay else null,
                    if (expectRunning) r?.forwardHeadDeg?.toDouble() else null
                )
                if (expectRunning) {
                    // 视角只在**右上角一处**显示（这里），避免和 statusDetail 重复出现两次
                    val va = PostureService.lastViewAngleDeg
                    fpsText.text = if (va == null) {
                        String.format(Locale.CHINA, "%.1f fps", PostureService.lastFps)
                    } else {
                        String.format(Locale.CHINA, "%.1f fps · 视角 %.0f°", PostureService.lastFps, va)
                    }
                }
                if (tick % 10 == 0) refreshStats()
                // 服务启动相机是异步的，界面可能先就绪；有数据了就补挂 Surface
                if (isResumed) attachPreview()
            }
        }

        toggleBtn.setOnClickListener {
            if (PostureService.isRunning) stopGuard() else requestAndStart()
        }
    }

    override fun onResume() {
        super.onResume()
        isResumed = true
        expectRunning = PostureService.isRunning
        updateToggle(PostureService.isRunning)
        if (PostureService.isRunning) attachPreview() else showPreviewPlaceholder()
        refreshStats()
        // 从衣橱返回时必须主动重绘吉祥物：主界面的 mascot.render 只在 onCreate / stopGuard /
        // 相机帧回调里调用，而冷启动后没有帧（未开守护）→ 换完时装回来还是旧样子。
        // 验收专员实测：wardrobe.xml 已是 scarf，界面仍戴着上一套圆眼镜，要冷启动才更新。
        mascot.render(lastMascotState, growth.level(), wardrobe.equipped())
    }

    override fun onPause() {
        super.onPause()
        isResumed = false
        // 摘掉 Surface：服务继续分析，只是不再往界面推画面（后台零额外开销）
        detachPreview()
    }

    /**
     * 把 PreviewView 的 Surface 交给服务里的 Preview 用例。
     * 服务启动相机是异步的，界面可能先于它就绪，所以 onUpdate 里也会补一次。
     */
    private fun attachPreview() {
        val preview = PostureService.previewUseCase ?: return
        if (!surfaceAttached) {
            // Preview 只有 setSurfaceProvider、没有 getter，Kotlin 不会合成属性，必须显式调用
            preview.setSurfaceProvider(previewView.surfaceProvider)
            surfaceAttached = true
        }
        showLivePreview()
    }

    private fun detachPreview() {
        if (surfaceAttached) {
            PostureService.previewUseCase?.setSurfaceProvider(null)
            surfaceAttached = false
        }
    }

    /** 有画面：显示 PreviewView，藏掉占位提示。 */
    private fun showLivePreview() {
        previewView.visibility = View.VISIBLE
        previewHint.visibility = View.GONE
    }

    /**
     * 没画面：把 PreviewView 整个隐藏掉，恢复成初始的纯黑卡片 + 占位提示。
     * 只摘 Surface 是不够的——PreviewView 会保留最后一帧的缓冲，看起来很像是「定格住了」。
     */
    private fun showPreviewPlaceholder() {
        previewView.visibility = View.INVISIBLE
        previewHint.text = "画面未开启\n点「开始守护」后在此显示"
        previewHint.visibility = View.VISIBLE
    }

    /**
     * 显式传运行状态，而不是读 PostureService.isRunning：
     * startForegroundService() 是异步的，刚点完那一刻 isRunning 还是 false，会让按钮文字滞后一拍。
     */
    private fun updateToggle(running: Boolean) {
        if (running) {
            toggleBtn.text = "停止守护"
            toggleBtn.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(this, R.color.danger))
        } else {
            toggleBtn.text = "开始守护"
            toggleBtn.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(this, R.color.accent))
        }
    }

    /** 胶囊底色按等级 tint（drawable 只画形状，颜色在这里给）。 */
    private fun setChip(text: String, colorRes: Int) {
        levelChip.text = text
        levelChip.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, colorRes))
    }

    private fun getColorRes(colorRes: Int) = ContextCompat.getColor(this, colorRes)

    private fun levelColor(l: Level) = when (l) {
        Level.SAFE -> R.color.safe
        Level.WARN -> R.color.warn
        Level.DANGER -> R.color.danger
    }

    private fun requestAndStart() {
        val perms = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startGuard() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun startGuard() {
        ContextCompat.startForegroundService(this, Intent(this, PostureService::class.java))
        expectRunning = true
        updateToggle(true)
        setChip("启动中", R.color.accent)
        statusDetail.text = "守护已启动，画面马上出现 · 可切后台或锁屏"
        previewView.visibility = View.VISIBLE
        previewHint.text = "正在启动摄像头…"
        previewHint.visibility = View.VISIBLE
    }

    private fun stopGuard() {
        // 先把自己的 Surface 从服务的 Preview 上摘掉，再停服务（避免服务拆解时还挂着界面 Surface）
        detachPreview()
        stopService(Intent(this, PostureService::class.java))
        expectRunning = false
        updateToggle(false)
        setChip("待机", R.color.text_faint)
        statusDetail.text = "守护已停止"
        resetLiveMetrics()
        lastMascotState = MascotView.State.SLEEP
        mascot.render(MascotView.State.SLEEP, growth.level(), wardrobe.equipped())
        overlayView.render(null)
        // 停止后不再有帧回调，台词必须在这里手动收尾——
        // 否则会停在上一次（例如「我脖子有点酸…」），和睡着的样子对不上。
        speechText.text = "点「开始守护」，我们一起"
        // 回到初始的纯黑画面，不留最后一帧
        showPreviewPlaceholder()
    }

    /**
     * 吉祥物状态映射 + 它的「台词」。
     *
     * 优先级：未在守护 → 休眠；视角过正 → 歪头（这时角度不可信，与其假装在测，不如提示摆位）；
     * 未检出 → 休眠；否则按三级映射表情。
     */
    private fun updateMascot(detected: Boolean, r: PostureResult?) {
        val va = PostureService.lastViewAngleDeg
        val badView = va != null && va > PoseMonitor.MAX_ALERT_VIEW_DEG
        val state = when {
            !expectRunning -> MascotView.State.SLEEP
            badView -> MascotView.State.VIEW_BAD
            !detected || r == null -> MascotView.State.SLEEP
            r.level == Level.SAFE -> MascotView.State.SAFE
            r.level == Level.WARN -> MascotView.State.WARN
            else -> MascotView.State.DANGER
        }
        // 记住当前表情状态：从衣橱返回时没有相机帧可依赖，onResume 要用它重绘
        lastMascotState = state
        mascot.render(state, growth.level(), wardrobe.equipped())
        // 台词：说「我们」、给动作、不指责（文案调性见 吉祥物-颈颈鹿设计.md 第六节）
        speechText.text = when (state) {
            MascotView.State.SLEEP ->
                if (!expectRunning) "点「开始守护」，我们一起" else "眯一会儿，等你回来"
            MascotView.State.VIEW_BAD -> "我看不清你的侧面，转一点点"
            MascotView.State.SAFE -> "就这样，脖子最舒服"
            MascotView.State.WARN -> "我脖子有点酸…"
            MascotView.State.DANGER -> "快收下巴！我快撑不住了"
            MascotView.State.RELAX -> "跟着我，一、二、保持"
        }
    }

    /**
     * 成长条：等级 / 经验进度 / 连续达标天数。
     * 连续天数要查 60 天分桶，**节流到 60 秒一次**，不要跟着每 10 帧的刷新跑。
     */
    private fun refreshGrowth() {
        val lv = growth.level()
        val xp = growth.totalXp
        levelText.text = "Lv.$lv ${Growth.levelName(lv)}"
        // 积分余额（v1.2）：衣橱里花掉之后再回主界面要立刻看到变化，所以每次刷新都重读。
        // 不用 🪙 emoji —— 华为 EMUI 自带字体没有这个码位，会显示成豆腐块（真机截图确认）。
        coinText.text = "积分 ${wallet.coins}"
        val next = growth.toNextLevel()
        if (next == null) {
            xpText.text = "已满级 · 累计 $xp 分"
            setXpProgress(1f)
        } else {
            val from = Growth.THRESHOLDS[lv - 1]
            val to = Growth.THRESHOLDS[lv]
            val prog = ((xp - from).toFloat() / (to - from)).coerceIn(0f, 1f)
            xpText.text = String.format(
                Locale.CHINA, "再攒 %d 分升 Lv.%d（%d / %d）", next, lv + 1, xp, to
            )
            setXpProgress(prog)
        }

        val now = System.currentTimeMillis()
        if (now - streakAtMs > 60_000) {
            streakCache = db.streakDays()
            streakAtMs = now
        }
        streakText.text = if (streakCache > 0) "🔥 连续 $streakCache 天" else ""
    }

    private fun setXpProgress(p: Float) {
        fun set(v: View, w: Float) {
            val lp = v.layoutParams as LinearLayout.LayoutParams
            lp.width = 0
            lp.weight = w.coerceAtLeast(0.0001f)
            v.layoutParams = lp
        }
        set(xpBarFill, p)
        set(xpBarRest, 1f - p)
    }

    /** 清掉实时指标，别留着上一次的读数。 */
    private fun resetLiveMetrics() {
        cvaText.text = "--"
        forwardText.text = "--"
        loadText.text = "--"
        val dim = getColorRes(R.color.text)
        cvaText.setTextColor(dim)
        forwardText.setTextColor(dim)
        loadText.setTextColor(dim)
        // fps / 视角 也是「上一次」的读数：停止后不清会一直挂着（实测停了还显示 3.3 fps · 视角 39°）
        fpsText.text = "-- fps"
        PostureService.lastFps = 0.0
        PostureService.lastViewAngleDeg = null
        PostureService.lastOverlay = null
    }

    private fun refreshStats() {
        val s = db.todayStats()
        val total = s.totalSec
        // 坐姿分：单一分数便于比较和看趋势（秒数/百分比不直观）
        val score = PostureScore.of(s.safeSec, s.warnSec, s.dangerSec)
        growth.creditToday(score)   // 当天最高分折算成长经验（幂等）
        // 积分结算（v1.2）：只把**新增**的安全时长换成积分，刷新多少次都不会重复发。
        // 注意喂的是 safeSec（不含 warn/danger，也不含 unreliable）——积分奖励的就是"坐得对"。
        val earned = wallet.settle(s.safeSec)
        if (earned > 0) {
            Toast.makeText(this, "健康坐姿 +$earned 积分", Toast.LENGTH_SHORT).show()
        }
        refreshGrowth()
        if (score == null) {
            scoreValue.text = "--"
            scoreValue.setTextColor(getColorRes(R.color.text_faint))
            scoreGrade.text = if (total == 0) "" else "数据还少"
        } else {
            scoreValue.text = "$score"
            scoreValue.setTextColor(
                getColorRes(
                    when (PostureScore.gradeKey(score)) {
                        "safe" -> R.color.safe
                        "warn" -> R.color.warn
                        else -> R.color.danger
                    }
                )
            )
            scoreGrade.text = PostureScore.grade(score)
        }
        if (total == 0) {
            statTotal.text = "0分0秒"
            statSafe.text = "0分0秒"
            statWarn.text = "0分0秒"
            statDanger.text = "0分0秒"
            statMax.text = "0.0°"
            setBarWeights(0, 0, 0)
            return
        }
        fun fmt(sec: Int) = "${sec / 60}分${sec % 60}秒"
        // 有秒数但取整后为 0% 时显示 "<1%"，避免「0分13秒 · 0%」这种自相矛盾
        fun pct(v: Int): String {
            if (total == 0) return "0%"
            val p = v * 100 / total
            return if (p == 0 && v > 0) "<1%" else "$p%"
        }
        statTotal.text = fmt(total)
        statSafe.text = "${fmt(s.safeSec)} · ${pct(s.safeSec)}"
        statWarn.text = "${fmt(s.warnSec)} · ${pct(s.warnSec)}"
        statDanger.text = "${fmt(s.dangerSec)} · ${pct(s.dangerSec)}"
        statMax.text = String.format(Locale.CHINA, "%.1f°", s.maxForward)
        setBarWeights(s.safeSec, s.warnSec, s.dangerSec)
        // 「今日守护」应显示**总时长**（含未计分部分），否则用户会觉得时间对不上；
        // 但分数只用计分时长算，所以要把未计分部分明确标出来。
        if (s.unreliableSec >= 60) {
            statTotal.text = "${fmt(total + s.unreliableSec)}（${fmt(s.unreliableSec)}未计分）"
        }
    }

    private fun setBarWeights(safe: Int, warn: Int, danger: Int) {
        fun apply(v: View, w: Int) {
            val lp = v.layoutParams as LinearLayout.LayoutParams
            lp.width = 0
            lp.weight = w.toFloat()
            v.layoutParams = lp
            v.visibility = if (w > 0) View.VISIBLE else View.GONE
        }
        apply(barSafe, safe)
        apply(barWarn, warn)
        apply(barDanger, danger)
    }

    private fun levelText(l: Level) = when (l) {
        Level.SAFE -> "安全"
        Level.WARN -> "注意"
        Level.DANGER -> "危险"
    }

    /** M5：数据板块——日报（今日按小时）/ 周报（近 7 天）/ 月报（近 30 天），时间线可切换。 */
    private fun showData() {
        val pad = (resources.displayMetrics.density * 12).toInt()
        val gap = (resources.displayMetrics.density * 8).toInt()

        val chart = DataChartView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.density * 196).toInt()
            )
        }

        val summary = TextView(this).apply {
            textSize = 13f
            // 走资源：浅色下必须用深字（v1.5 一度写死 #ECECEC，白底上几乎看不见 —— 验收专员抓的）
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.chart_text))
            setPadding(pad, pad, pad, 0)
        }

        val disclaimer = TextView(this).apply {
            text = "※ 本页为趋势性评估（头部力矩简化模型），非医疗诊断。"
            textSize = 11f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_faint))
            setPadding(pad, pad, pad, pad)
        }

        // 时间线三档：日报按小时，周报/月报按天
        val ranges = listOf(
            Triple("今日", true, 0),
            Triple("近 7 天", false, 7),
            Triple("近 30 天", false, 30),
        )
        val selector = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(pad, pad, pad, 0)
        }
        val selectorButtons = ArrayList<Button>()

        fun render(index: Int) {
            val (label, hourly, days) = ranges[index]
            val buckets = if (hourly) db.todayHourly() else db.lastDays(days)
            chart.setData(
                buckets,
                if (hourly) DataChartView.Mode.HOURLY else DataChartView.Mode.DAILY
            )
            summary.text = buildSummary(label, buckets, hourly)
            selectorButtons.forEachIndexed { i, b -> styleSelector(b, i == index) }
        }

        ranges.forEachIndexed { i, range ->
            val b = Button(this).apply {
                text = range.first
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply { if (i > 0) marginStart = gap }
                setOnClickListener { render(i) }
            }
            selectorButtons.add(b)
            selector.addView(b)
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, DataChartView.COLOR_BG_RES))
            addView(selector)
            addView(chart)
            addView(summary)
            addView(disclaimer)
        }
        val scroll = ScrollView(this).apply { addView(box) }

        render(0)

        AlertDialog.Builder(this)
            .setTitle("数据")
            .setView(scroll)
            .setPositiveButton("关闭", null)
            .show()
    }

    /** 选中的档位用主色填充，其余透明。 */
    private fun styleSelector(b: Button, selected: Boolean) {
        b.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(
                this,
                if (selected) R.color.accent else android.R.color.transparent
            )
        )
        b.setTextColor(
            ContextCompat.getColor(
                this,
                if (selected) R.color.white else R.color.text_dim
            )
        )
    }

    /** 汇总文案：日报按小时、周报/月报按天，措辞与日均口径随之变化。 */
    private fun buildSummary(
        label: String,
        buckets: List<PostureDb.Bucket>,
        hourly: Boolean,
    ): String {
        val total = buckets.sumOf { it.totalSec }
        if (total == 0) return "${label}暂无数据，先点「开始守护」吧。"

        val danger = buckets.sumOf { it.dangerSec }
        val warn = buckets.sumOf { it.warnSec }
        val safe = buckets.sumOf { it.safeSec }
        val maxForward = buckets.maxOfOrNull { it.maxForward } ?: 0.0
        val active = buckets.count { it.totalSec > 0 }
        val unit = if (hourly) "小时" else "天"

        fun fmt(sec: Int) = "${sec / 60}分${sec % 60}秒"
        fun pct(v: Int): String {
            // 除零保护：v1.5 之前的版本少了这行，total==0 时是 ArithmeticException
            if (total <= 0) return "0%"
            val p = v * 100 / total
            return if (p == 0 && v > 0) "<1%" else "$p%"
        }

        val sb = StringBuilder()
        // 坐姿分放在最前面：它是这段时间的"总分"，比一长串秒数更快看懂
        PostureScore.of(safe, warn, danger)?.let {
            sb.append("坐姿分 $it 分（${PostureScore.grade(it)}）\n")
        }
        sb.append("${label}共守护 ${fmt(total)}（$active ${unit}有记录）\n")
        if (hourly) {
            val peak = buckets.maxByOrNull { it.totalSec }
            if (peak != null) {
                val hour = Calendar.getInstance()
                    .apply { timeInMillis = peak.startSec * 1000 }
                    .get(Calendar.HOUR_OF_DAY)
                sb.append("最久时段 ${hour}时（${fmt(peak.totalSec)}）\n")
            }
        }
        sb.append("危险 ${fmt(danger)}（${pct(danger)}）\n")
        sb.append("注意 ${fmt(warn)}（${pct(warn)}）\n")
        sb.append("安全 ${fmt(safe)}（${pct(safe)}）\n")
        // 未计分时长要单独说明：否则用户会发现「时长 ≠ 三段之和」而以为丢了数据
        val unreliable = buckets.sumOf { it.unreliableSec }
        if (unreliable >= 60) {
            sb.append("未计分 ${fmt(unreliable)}（视角偏正，角度不可信）\n")
        }
        sb.append("最大头前倾 ${String.format(Locale.CHINA, "%.1f", maxForward)}°")
        if (!hourly) sb.append(" · 日均危险 ${fmt(danger / buckets.size)}")
        return sb.toString()
    }

    /** M5：坐姿日志导出为 CSV + 通过 FileProvider 分享。 */
    private fun showExport() {
        val rows = db.allLogs()
        if (rows.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("导出 CSV")
                .setMessage("暂无坐姿日志可导出。先点「开始守护」攒一些数据吧。")
                .setPositiveButton("好", null)
                .show()
            return
        }

        val result = try {
            CsvExporter.export(this, rows)
        } catch (e: Exception) {
            AlertDialog.Builder(this)
                .setTitle("导出失败")
                .setMessage("写入文件时出错：${e.message}")
                .setPositiveButton("好", null)
                .show()
            return
        }

        val total = rows.sumOf { it.totalSec }
        val msg = "已导出 ${result.rows} 条分钟记录，合计 ${total / 60} 分 ${total % 60} 秒。\n\n" +
            "文件名：${result.file.name}\n" +
            "位置：${result.file.parent}\n\n" +
            "（CSV 为 UTF-8 BOM 编码，Excel 直接双击打开不乱码。）\n\n" +
            "※ 本数据为趋势性评估（头部力矩简化模型），非医疗诊断。"

        AlertDialog.Builder(this)
            .setTitle("导出 CSV")
            .setMessage(msg)
            .setPositiveButton("分享") { _, _ -> shareCsv(result.file) }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun shareCsv(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, file.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "分享 CSV"))
        } catch (e: Exception) {
            Toast.makeText(this, "没有可用的分享应用：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 放松环节：侧视角「收下巴」操。
     *
     * 相机由前台服务独占，所以放松必须建立在「守护已启动」之上——否则没有角度来源。
     * 会话状态机跑在服务的分析线程，界面只读快照 + 置标志位。
     */
    /**
     * 打开「小鹿衣橱」（v1.2）。
     *
     * 用类名反射而不是直接引用，是为了让**衣橱页由另一个开发者并行实现**时主界面仍可独立编译；
     * 页面缺失时给明确提示而不是崩掉。等页面稳定后可以改成直接 `startActivity(Intent(this, WardrobeActivity::class.java))`。
     */
    /**
     * 入场动画（v1.3）——参考多邻国的启动观感。
     *
     * 编排原则：
     *  - **自上而下错峰**（55ms/块），像"内容依次落位"，而不是全屏一起闪；
     *  - 标题从**上方**滑下（负位移），卡片从**下方**飞上（正位移），方向感成对；
     *  - 吉祥物单独做弹性放大 + 星星迸发，是视线焦点，所以给它 760ms 单独表演；
     *  - 分数用数字滚动，落定瞬间回弹一次。
     *
     * 只在 onCreate 调用（冷启动/被系统回收后重建）。从衣橱、设置等页面返回走 onResume，
     * **不重播**——每次返回都播一遍会烦人。
     */
    private fun playEntryAnimation() {
        var d = 0L
        // 标题向下滑入：起始 y 为负 → 从上落下
        EntryAnimation.popIn(titleText, d, fromTyDp = -18f, fromScale = 0.96f)
        d += EntryAnimation.STAGGER
        EntryAnimation.popIn(subtitleText, d, fromTyDp = -12f, fromScale = 0.97f, duration = 300)
        d += EntryAnimation.STAGGER
        EntryAnimation.popIn(chipRow, d, fromTyDp = 14f, fromScale = 0.94f)
        d += EntryAnimation.STAGGER
        EntryAnimation.popIn(cardRow, d, fromTyDp = 30f, fromScale = 0.90f, duration = 400)
        d += EntryAnimation.STAGGER
        EntryAnimation.popIn(todayCard, d, fromTyDp = 30f, fromScale = 0.92f, duration = 400)
        d += EntryAnimation.STAGGER
        EntryAnimation.popIn(growthStrip, d, fromTyDp = 26f, fromScale = 0.94f)
        d += EntryAnimation.STAGGER
        // 五个按钮逐个落位（比整排一起出现更有节奏感，也更"多邻国"）
        val btns = listOf(settingsBtn, relaxBtn, wardrobeBtn, dataBtn, exportBtn)
        btns.forEachIndexed { i, b ->
            EntryAnimation.popIn(b, d + i * 40L, fromTyDp = 16f, fromScale = 0.86f, duration = 300)
        }
        d += btns.size * 40L
        EntryAnimation.popIn(toggleBtn, d, fromTyDp = 24f, fromScale = 0.92f, duration = 380)

        // 吉祥物：和卡片同时段登场，但自己演 760ms 的弹性 + 星星
        mascot.postDelayed({ mascot.playEntrance() }, 260)

        // 分数滚动：落后卡片一点点，落定时正好接上 cardRow 的动画结束
        val s = db.todayStats()
        val score = PostureScore.of(s.safeSec, s.warnSec, s.dangerSec)
        if (score != null && score > 0) {
            EntryAnimation.countUp(scoreValue, score, delayMs = 330, duration = 620)
        }
    }

    private fun openWardrobe() {
        try {
            val cls = Class.forName("com.example.cervicalguard.ui.WardrobeActivity")
            startActivity(Intent(this, cls))
        } catch (e: Exception) {
            Toast.makeText(this, "衣橱页当前不可用，请更新到最新版本", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showRelax() {
        if (!PostureService.isRunning) {
            Toast.makeText(this, "请先点「开始守护」——放松操要用摄像头看你的头", Toast.LENGTH_LONG).show()
            return
        }
        val d = resources.displayMetrics.density
        val pad = (d * 20).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (d * 8).toInt(), pad, 0)
        }
        val ring = RelaxRingView(this)
        // 吉祥物在旁边做示范动作（一收一放），比文字说明直观——「跟着我」的那只鹿
        // 注意：对话框是白底，关掉深色底衬（那个底衬是给相机画面上的叠加用的，白底上会变成一块灰）
        val demoMascot = MascotView(this).apply {
            showBackdrop = false
            render(MascotView.State.RELAX, growth.level())
            setDemo(true)
        }
        val ringRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        ringRow.addView(
            demoMascot,
            LinearLayout.LayoutParams((d * 76).toInt(), (d * 104).toInt())
        )
        ringRow.addView(
            ring,
            LinearLayout.LayoutParams(0, (d * 218).toInt(), 1f)
        )
        root.addView(ringRow)
        val hint = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(getColorRes(R.color.text_dim))
            text = "点开始后有 4 秒转身时间：请侧面对着手机\n" +
                "下巴水平后收，做出「双下巴」，保持 2 秒再放松\n" +
                "看不见屏幕也没关系 —— 语音会领节奏，每次到位会震动"
        }
        root.addView(
            hint,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (d * 4).toInt() }
        )
        val whereLine = TextView(this).apply {
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(getColorRes(R.color.accent))
            text = "手机最好架在桌子侧面（你的斜前方 45°~90°）：身体保持侧对，眼睛扫一眼就能看到屏幕"
        }
        root.addView(
            whereLine,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (d * 10).toInt() }
        )
        val todayLine = TextView(this).apply {
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(getColorRes(R.color.text_faint))
            text = relaxTodayText()
        }
        root.addView(
            todayLine,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (d * 10).toInt() }
        )

        val dlg = AlertDialog.Builder(this)
            .setTitle("颈椎放松操 · 收下巴")
            .setView(root)
            .setPositiveButton("结束", null)   // 监听在 show() 之后挂，先dismiss再收尾
            .setCancelable(false)
            .create()

        dlg.setOnDismissListener {
            PostureService.onRelaxUpdate = null
            PostureService.relaxStopRequested = true   // 服务侧会在下一帧收尾并落库
            demoMascot.setDemo(false)                  // 停掉示范动画，别在后台空转
        }
        dlg.setOnShowListener {
            // 先把上一轮的停止标志清掉，否则新会话会在第一帧就被判定为「用户点了结束」
            PostureService.relaxStopRequested = false
            PostureService.onRelaxUpdate = { s ->
                runOnUiThread {
                    ring.render(s)
                    if (s != null && s.done) {
                        hint.text = if (s.rep > 0)
                            "完成 ${s.rep}/${s.total} 次 · 平均幅度 ${String.format(Locale.CHINA, "%.1f", s.avgDepthDeg)}°"
                        else "这次没有完成动作，再来一次？"
                        todayLine.text = relaxTodayText()
                        refreshStats()
                    }
                }
            }
            ring.render(PostureService.lastRelaxSnapshot)
            PostureService.relaxStartRequested = true
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { dlg.dismiss() }
        }
        dlg.show()
    }

    private fun relaxTodayText(): String {
        val r = db.relaxToday()
        return if (r.sessions == 0) "今日还未放松过"
        else "今日已放松 ${r.sessions} 次 · 共 ${r.totalSec} 秒 · 最高 ${r.bestScore} 分"
    }

    private fun showSettings() {
        val th = Thresholds(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 32, 56, 16)
        }
        fun field(label: String, value: Double): EditText {
            box.addView(TextView(this).apply { text = label })
            val et = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                setText(value.toString())
            }
            box.addView(et)
            return et
        }
        val eWarn = field("注意阈值（头前倾角 °）", th.warnDeg)
        val eDanger = field("危险阈值（°）", th.dangerDeg)
        val eHold = field("持续多久提醒（秒）", th.holdSeconds)
        val eCool = field("提醒后冷却（秒）", th.cooldownSeconds)

        val vibrateBox = CheckBox(this).apply {
            text = "危险时同时震动"
            isChecked = th.vibrateEnabled
            // 不硬编码颜色：对话框是白底，用主题自己的文字色（浅色模式深灰 / 深色模式浅色）才都看得清。
            // 之前写了 @color/text（#ECF1F6，专给深色界面用）→ 白字白底，几乎看不见。
            setPadding(0, 24, 0, 8)
        }
        box.addView(vibrateBox)

        val snapshotBox = CheckBox(this).apply {
            text = "提醒时附上画面（看一眼就知道该怎么坐）"
            isChecked = th.snapshotEnabled
            setPadding(0, 8, 0, 8)
        }
        box.addView(snapshotBox)
        box.addView(
            TextView(this).apply {
                text = "画面只在本机内存里交给通知，不写文件、不上传；" +
                    "但锁屏上通知会显示内容，不想被旁人看到就关掉。"
                textSize = 11f
                setPadding(0, 0, 0, 8)
            }
        )

        // ── 个人基线自适应（v1.1） ──
        // 默认关：不悄悄改变用户已经习惯的判定。开启后阈值按「你自己做得最好的姿态」平移。
        val baseline = PersonalBaseline(this)
        val adaptiveBox = CheckBox(this).apply {
            text = "按我的姿态自动校准阈值"
            isChecked = th.adaptiveEnabled
            setPadding(0, 16, 0, 8)
        }
        box.addView(adaptiveBox)
        box.addView(
            TextView(this).apply {
                text = baseline.describe()
                textSize = 11f
                setPadding(0, 0, 0, 4)
            }
        )
        box.addView(
            TextView(this).apply {
                text = "固定阈值对所有人都一样，但每个人的颈椎基础角度不同。" +
                    "开启后会用你做得最好的那部分姿态当基准，阈值随之上移或下移。" +
                    "需要先攒约 5 分钟可信数据；视角不对时段的样本不会参与学习。"
                textSize = 11f
                setPadding(0, 0, 0, 4)
            }
        )
        val resetBtn = TextView(this).apply {
            text = "↺ 清除已学习的基线（重新学习）"
            textSize = 11f
            setPadding(0, 8, 0, 8)
            setOnClickListener {
                baseline.reset()
                text = "已清除，重新开始学习"
            }
        }
        box.addView(resetBtn)

        // ── 外观：深色 / 浅色 / 跟随系统（v1.5） ──
        // 实现是整包资源切换（AppCompatDelegate.setDefaultNightMode），所以点完 Activity 会重建，
        // 弹窗随之关闭并立刻以新配色重开主界面 —— 这也是「弹窗终于不再是白底」的修法。
        box.addView(
            TextView(this).apply {
                text = "外观"
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setPadding(0, 24, 0, 8)
            }
        )
        val appearance = Appearance(this)
        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 8)
        }
        var dlgRef: AlertDialog? = null
        val modeViews = mutableListOf<TextView>()
        for (m in listOf(Appearance.SYSTEM, Appearance.DARK, Appearance.LIGHT)) {
            val tv = TextView(this).apply {
                text = Appearance.label(m)
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(9), dp(14), dp(9))
            }
            styleModeChip(tv, selected = m == appearance.mode)
            tv.setOnClickListener {
                if (!appearance.setIfChanged(m)) {
                    Toast.makeText(this, "已经是${Appearance.label(m)}了", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                // 重建前先收起弹窗，避免弹窗挂在已销毁的 Activity 上
                dlgRef?.dismiss()
                Toast.makeText(this, "已切换到${Appearance.label(m)}", Toast.LENGTH_SHORT).show()
            }
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            if (modeViews.isNotEmpty()) lp.marginStart = dp(6)
            modeRow.addView(tv, lp)
            modeViews.add(tv)
        }
        box.addView(modeRow)
        box.addView(
            TextView(this).apply {
                text = "深色是默认观感；切到浅色后主界面、弹窗、图表、放松环会一起变。"
                textSize = 11f
                setPadding(0, 0, 0, 8)
            }
        )

        val builtDlg = AlertDialog.Builder(this)
            .setTitle("阈值设置")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("保存") { _, _ ->
                th.warnDeg = eWarn.text.toString().toDoubleOrNull() ?: 15.0
                th.dangerDeg = eDanger.text.toString().toDoubleOrNull() ?: 30.0
                th.holdSeconds = eHold.text.toString().toDoubleOrNull() ?: 180.0
                th.cooldownSeconds = eCool.text.toString().toDoubleOrNull() ?: 600.0
                th.vibrateEnabled = vibrateBox.isChecked
                th.snapshotEnabled = snapshotBox.isChecked
                th.adaptiveEnabled = adaptiveBox.isChecked
                // v1.1：阈值改成**动态读取**，保存后立即生效，不再需要重启守护。
                statusDetail.text = "设置已保存"
            }
            .setNegativeButton("取消", null)
            .create()
        dlgRef = builtDlg
        builtDlg.show()
    }

    /** 外观选项胶囊：选中=主色实底+白字，未选中=描边+次要文字色。 */
    private fun styleModeChip(tv: TextView, selected: Boolean) {
        val bg = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            if (selected) {
                setColor(ContextCompat.getColor(this@MainActivity, R.color.accent))
            } else {
                setColor(Color.TRANSPARENT)
                setStroke(dp(1), ContextCompat.getColor(this@MainActivity, R.color.stroke))
            }
        }
        tv.background = bg
        tv.setTextColor(
            if (selected) Color.WHITE
            else ContextCompat.getColor(this@MainActivity, R.color.text_dim)
        )
    }

    /** dp → px（弹窗里布局是代码建的，需要手动换算）。 */
    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density + 0.5f).toInt()

    override fun onDestroy() {
        super.onDestroy()
        PostureService.onUpdate = null
    }
}
