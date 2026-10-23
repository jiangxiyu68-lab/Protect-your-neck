package com.example.cervicalguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.example.cervicalguard.core.Level
import com.example.cervicalguard.core.PoseOverlay
import com.example.cervicalguard.core.PostureResult
import com.example.cervicalguard.core.RelaxEngine
import com.example.cervicalguard.core.RelaxSnapshot
import com.example.cervicalguard.data.Growth
import com.example.cervicalguard.data.PersonalBaseline
import com.example.cervicalguard.data.PostureDb
import com.example.cervicalguard.data.Thresholds
import com.example.cervicalguard.data.Wallet
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 坐姿守护前台服务：承载相机 + MediaPipe 检测 + 常驻通知 + 语音提醒。
 * 前台服务是 Android 上让摄像头在锁屏/后台继续工作的唯一合法方式。
 */
class PostureService : Service() {

    companion object {
        const val CHANNEL_ID = "posture_guard"
        const val NOTIF_ID = 1001
        const val TAG = "PostureService"

        /**
         * 分析流目标分辨率（4:3，竖屏旋转后即 3:4）。
         *
         * **踩坑记录（2026-10-19 从真机日志发现）**：原来写 `Size(480,640)` + `FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER`，
         * 相机把分析流**回落到了更低的 320x240**（日志：`分析帧 320x240 rotation=270 → 旋转后 240x320`）——
         * 也就是说之前那次「480x640 vs 320x240 帧率对比」实验是无效的：两个配置实际都跑在 320x240。
         *
         * 现在改成 **640x480 + CLOSEST_HIGHER_THEN_LOWER**，并让**预览用同一个 ResolutionSelector**：
         * 两路同尺寸 → 同一传感器裁切 → 界面标注的归一化坐标才对得上（不同尺寸时裁切不同，会偏 0.1~0.17）。
         * 代价是帧率会下降（像素数是原来的 4 倍）。
         */
        val ANALYSIS_TARGET = Size(640, 480)

        @Volatile var isRunning = false

        /** 界面订阅用（Activity onResume 时挂上，onDestroy 时置 null）。 */
        @Volatile var onUpdate: ((PostureResult?, Boolean) -> Unit)? = null

        /**
         * 预览用例：相机由本服务独占，界面通过把 PreviewView 的 Surface 交给它来显示画面。
         * 这样界面**完全不经手 bitmap**，与 MediaPipe 的像素零交集——
         * 之前「服务推 bitmap 给界面」的写法会在 MediaPipe 持有该 bitmap 时被原生缩放打断，
         * 真机上表现为分析线程 SIGSEGV（nativeCreateRgbaImage 空指针），已废弃。
         */
        @Volatile var previewUseCase: Preview? = null

        /** 最近一帧的识别可视化数据（界面把耳朵/肩膀/CVA 连线画在画面上）。 */
        @Volatile var lastOverlay: PoseOverlay? = null

        /** 最近 5 秒的实测分析帧率，供界面显示（EMUI 会吞应用日志，界面是唯一可靠的观测窗）。 */
        @Volatile var lastFps = 0.0

        /**
         * 最近一帧估计的**拍摄视角**：0°=正侧（最佳），90°=正脸（CVA 失效）。
         * 放在界面显示，是为了让用户能自己把手机摆到位——比事后发现数值不对有用得多。
         */
        @Volatile var lastViewAngleDeg: Double? = null

        /** 放松会话的界面订阅：服务在分析线程填快照，界面只读。 */
        @Volatile var onRelaxUpdate: ((RelaxSnapshot?) -> Unit)? = null

        /**
         * 界面 → 服务的放松会话命令。
         * 用 volatile 标志而不是锁：会话状态机只在分析线程里跑（单一所有者），
         * 界面线程只置标志，避免两线程同时碰引擎内部状态。
         */
        @Volatile var relaxStartRequested = false

        @Volatile var relaxStopRequested = false

        /** 当前是否有放松会话在进行（界面据此决定是否显示/隐藏面板）。 */
        @Volatile var relaxRunning = false

        /** 最近一次放松快照：界面中途挂上订阅时先拿它兜底，避免空白。 */
        @Volatile var lastRelaxSnapshot: RelaxSnapshot? = null

        /** 点「放松」后的准备时长（毫秒）：让用户从「面对屏幕」转到「侧对镜头」。 */
        const val RELAX_PREPARE_MS = 4_000L

        /** 一节放松操的次数（引擎默认也是 8，这里显式化以便准备期就能显示总数）。 */
        const val RELAX_TOTAL_REPS = 8

        /** 语音报数用的中文数字——侧身看不见屏幕时，靠听就知道做到第几次了。 */
        val CN_NUM = arrayOf("一", "二", "三", "四", "五", "六", "七", "八", "九", "十")

        /** 带画面的提醒通知停留时长：太短用户看不到，太长会盖住常驻状态。 */
        const val ALERT_HOLD_MS = 20_000L
    }

    private lateinit var executor: ExecutorService
    private var monitor: PoseMonitor? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var tts: TextToSpeech? = null
    private var vibrator: Vibrator? = null
    private val lifecycleOwner = AlwaysAliveLifecycleOwner()

    private lateinit var db: PostureDb

    /** 个人基线学习器（v1.1）：记录用户做得最好的姿态，供自适应阈值使用。 */
    private lateinit var baseline: PersonalBaseline
    private var curMinute = -1L

    /**
     * 按「壁钟时长」累加，而不是按帧数。
     * 旧实现是每帧 cnt++ 却当秒写库，帧率 5~10fps 时所有时长虚高 5~10 倍。
     * 现在每个样本按其与上一帧的真实间隔 dt 计入当前等级，与帧率无关。
     */
    private var accDanger = 0.0
    private var accWarn = 0.0
    private var accSafe = 0.0

    /** 视角过正导致「角度不可信」的时长：单独记、**不计分**（详见 recordLog）。 */
    private var accUnreliable = 0.0
    private var maxForward = 0.0
    private var lastSampleMs = 0L

    /** 单次采样最多计入的秒数：防止「人离开一段时间后又回来」把空档算进某个等级。 */
    private val maxSampleGapSec = 5.0

    // 帧率量测（界面每 5 秒一刷，日志每 60 秒一条）：老设备上排查性能/统计口径都靠它
    private var fpsFrames = 0
    private var fpsWindowMs = 0L
    private var fpsLogFrames = 0
    private var fpsLogStartMs = 0L

    // 放松会话：引擎只在分析线程创建与销毁，界面侧只读 relaxRunning / 收快照
    @Volatile
    private var relaxEngine: RelaxEngine? = null
    private var relaxStartMs = 0L
    private var relaxSpokenHalfway = false

    /** 本帧是否正对镜头：正脸时角度不可用，界面要提示转身。 */
    @Volatile
    private var relaxFrontal = false

    /** 带画面的提醒通知要停留展示到这一刻，期间不被每帧的常驻通知覆盖。 */
    @Volatile
    private var alertHoldUntilMs = 0L

    /** 准备期截止时刻：点「放松」后先给用户转身的时间，否则转身动作会被当成基准。 */
    @Volatile
    private var relaxPrepareUntilMs = 0L

    private var lastFrontalWarnMs = 0L
    private var relaxSpokenGo = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        executor = Executors.newSingleThreadExecutor()
        db = PostureDb(this)
        baseline = PersonalBaseline(this)
        vibrator = getSystemService(Vibrator::class.java)
        createChannel()
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.CHINA
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // v1.6 发布准备：targetSdk 34 起前台服务**必须**在 startForeground 里声明类型，
        // 否则 Android 14+ 抛 MissingForegroundServiceTypeException（用户装上就闪退）。
        // 测试机是 Android 9，看不到这个问题 —— 典型"只在目标机型以外崩"。
        val notif = buildNotification("坐姿守护中", "正在监测您的坐姿…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        isRunning = true
        startCamera()
        return START_STICKY
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()

            // 预览与分析**共用同一个 ResolutionSelector**：两路同尺寸 → 同一传感器裁切 →
            // 界面标注的归一化坐标才对得上（不同尺寸时裁切不同，点会偏 0.1~0.17）。
            val selector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        ANALYSIS_TARGET,
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
                )
                .build()
            val preview = Preview.Builder()
                .setResolutionSelector(selector)
                .build()
            // 显式钉死目标旋转：否则 ImageAnalysis 的 rotationDegrees 会随当时的显示状态变化，
            // 一旦拿到横幅帧，CVA 会算出「假性低头 70~90°」。本 App 锁定竖屏，恒为 ROTATION_0。
            val analysis = ImageAnalysis.Builder()
                .setTargetRotation(Surface.ROTATION_0)
                .setResolutionSelector(selector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            monitor = PoseMonitor(
                context = this,
                onResult = { r, det ->
                    countFrame()          // 统计的是「分析管线出帧速度」，与是否检到人无关
                    onUpdate?.invoke(r, det)
                    updateNotification(r, det)
                    // 放松操期间的角度不能算作坐姿统计：动作本身就是大幅改变头前倾，
                    // 混进去会污染日报/周报（用户在做康复动作，却被记成"低头"）。
                    if (det && r != null && relaxEngine == null && !relaxStartRequested) recordLog(r)
                },
                onAlert = { r, thumb, reason ->
                    speak(r, reason)
                    vibrateAlert()
                    notifyAlert(r, thumb, reason)
                },
                // 准备期也要算「放松中」，否则回调不再触发，会话会卡在准备期
                wantRelax = { relaxEngine != null || relaxStartRequested || relaxPrepareUntilMs > 0L },
                onRelaxTheta = { theta -> handleRelaxFrame(theta) },
                onViewAngle = { a -> lastViewAngleDeg = a },
                wantSnapshot = { Thresholds(this).snapshotEnabled },
                onOverlay = { o -> lastOverlay = o }
            )
            analysis.setAnalyzer(executor, monitor!!)
            this.analysis = analysis
            cameraProvider?.unbindAll()
            // Preview 与 ImageAnalysis 同时绑在「恒为 RESUMED」的 owner 上：
            // 界面在不在前台都不影响分析，界面只是往 preview 上挂/摘自己的 Surface。
            cameraProvider?.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                preview,
                analysis
            )
            previewUseCase = preview
        }, ContextCompat.getMainExecutor(this))
    }

    /** 每分析完一帧调用一次（含未检出人体的帧），用于统计真实管线帧率。 */
    private fun countFrame() {
        val now = System.currentTimeMillis()
        fpsFrames++
        fpsLogFrames++
        // 5 秒滑动窗 → 界面显示；60 秒 → 落一条日志（真机日志常被 EMUI 吞掉，界面才是主要观测手段）
        if (fpsWindowMs == 0L) fpsWindowMs = now
        if (now - fpsWindowMs >= 5_000) {
            lastFps = fpsFrames * 1000.0 / (now - fpsWindowMs)
            fpsFrames = 0
            fpsWindowMs = now
        }
        if (fpsLogStartMs == 0L) fpsLogStartMs = now
        if (now - fpsLogStartMs >= 60_000) {
            Log.i(TAG, "分析帧率 ≈ ${"%.1f".format(fpsLogFrames * 1000.0 / (now - fpsLogStartMs))} fps（本分钟 ${fpsLogFrames} 帧）")
            fpsLogFrames = 0
            fpsLogStartMs = now
        }
    }

    /**
     * 记录一帧到当前分钟桶。
     *
     * **关键规则（v1.1）**：拍摄视角过正时，θ 主要由左右偏移贡献、与头前倾无关
     * （实测正视时 θ 会虚报 40°+），这段时间**不能按 SAFE/WARN/DANGER 任何一档计分**，
     * 而是单独累加到 `unreliable_sec`。否则会出现「明明坐得很好、分数却被拉低」。
     * 原始时长仍然保留在库里，便于回溯「今天有多久没摆对位置」。
     */
    private fun recordLog(r: PostureResult) {
        val now = System.currentTimeMillis()
        val minute = now / 60000
        if (curMinute == -1L) curMinute = minute
        if (minute != curMinute) {
            flushLog()
            curMinute = minute
        }

        // 计入本帧与上一帧之间的真实时长（首帧无间隔，记 0）
        val dtSec = if (lastSampleMs == 0L) 0.0 else (now - lastSampleMs) / 1000.0
        lastSampleMs = now
        val d = dtSec.coerceIn(0.0, maxSampleGapSec)

        val va = lastViewAngleDeg
        val viewBad = va != null && va > PoseMonitor.MAX_ALERT_VIEW_DEG
        if (viewBad) {
            accUnreliable += d
            return
        }

        // 个人基线学习：只喂**角度可信**的帧（上面的 return 已挡掉不可信的），
        // 否则学到的是「正视时虚报 40°」这种噪声，阈值会被带偏。
        if (d > 0) baseline.feed(r.forwardHeadDeg, d)

        when (r.level) {
            Level.DANGER -> accDanger += d
            Level.WARN -> accWarn += d
            Level.SAFE -> accSafe += d
        }
        if (r.forwardHeadDeg > maxForward) maxForward = r.forwardHeadDeg
    }

    private fun flushLog() {
        if (curMinute == -1L) return
        val d = Math.round(accDanger).toInt()
        val w = Math.round(accWarn).toInt()
        val s = Math.round(accSafe).toInt()
        val u = Math.round(accUnreliable).toInt()
        if (d + w + s + u == 0) return
        db.upsertMinute(curMinute * 60, d, w, s, maxForward, u)
        accDanger = 0.0; accWarn = 0.0; accSafe = 0.0; accUnreliable = 0.0; maxForward = 0.0
    }

    /**
     * 语音播报。
     * v1.1 起带上**触发原因**：同样是「低头」，持续低头和累计低头该说的话不一样——
     * 「累计低头 5 分钟」听起来是「你今天一直在低头」，比单次角度数字更有说服力。
     */
    private fun speak(r: PostureResult, reason: String = "") {
        val text = if (reason.contains("累计")) {
            "$reason，颈椎负荷 ${r.equivLoadKg} 公斤，抬头活动一下"
        } else {
            "已低头 ${r.forwardHeadDeg.toInt()} 度，颈椎等效负荷 ${r.equivLoadKg} 公斤，请抬头休息一下"
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "posture_alert")
    }

    /**
     * 危险提醒的震动通道（与语音并行；可在「设置」里单独关掉）。
     * 波形 = 短震 300ms → 停 200ms → 长震 500ms，比单次长震更像"提醒"而不是来电。
     */
    private fun vibrateAlert() {
        val v = vibrator ?: return
        if (!Thresholds(this).vibrateEnabled) return
        if (!v.hasVibrator()) return
        val pattern = longArrayOf(0, 300, 200, 500)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(pattern, -1)
        }
        Log.i(TAG, "震动提醒已触发（${pattern.joinToString(",")}）")
    }

    // ───────────────────────── 放松环节（收下巴操） ─────────────────────────

    /**
     * 放松模式下的每帧回调，**始终在分析线程**执行。
     * 引擎的创建/推进/销毁都收敛在这一个线程，界面只置 volatile 标志位，
     * 因此不需要任何锁（跨线程共享可变状态是这类功能最容易埋雷的地方）。
     */
    private fun handleRelaxFrame(theta: Double?) {
        val nowMs = System.currentTimeMillis()
        val viewAngle = lastViewAngleDeg
        // 视角过正 → 前后位移几乎测不到，暂停判定并提示转身（而不是拿假数据当成绩）
        val tooFrontal = (viewAngle ?: 0.0) > PoseMonitor.MAX_RELAX_VIEW_DEG
        relaxFrontal = tooFrontal

        // 用户点了「结束」
        if (relaxStopRequested) {
            relaxStopRequested = false
            relaxStartRequested = false
            val eng = relaxEngine
            finishRelax(
                completedReps = eng?.reps ?: 0,
                totalReps = eng?.total ?: 0,
                avgDepth = eng?.avgDepthDeg ?: 0.0,
                relaxScore = eng?.relaxScore ?: 0,
                speakText = "已结束放松"
            )
            return
        }

        var eng = relaxEngine
        if (eng == null) {
            if (relaxPrepareUntilMs > 0L && nowMs < relaxPrepareUntilMs) {
                publishPreparing(nowMs)      // 准备期：倒计时中，引擎还没开始跑
                return
            }
            if (relaxPrepareUntilMs > 0L) {
                // 准备期结束 → 正式开跑（会话内用相对秒数，避免与系统时钟跳变耦合）
                eng = RelaxEngine(totalReps = RELAX_TOTAL_REPS)
                eng.start(0.0)
                relaxEngine = eng
                relaxStartMs = nowMs
                relaxPrepareUntilMs = 0L
                publishRelax(eng, done = false)
                return
            }
            if (!relaxStartRequested) return
            // 首次请求：先进入准备期，给用户从「面对屏幕」转到「侧对镜头」的时间
            relaxStartRequested = false
            relaxStopRequested = false   // 清掉上一轮遗留的停止标志（会话结束后的 dismiss 会置位）
            relaxPrepareUntilMs = nowMs + RELAX_PREPARE_MS
            relaxSpokenHalfway = false
            relaxSpokenGo = false
            lastFrontalWarnMs = 0L
            relaxRunning = true
            // 关键：侧身时看不见屏幕，所以整节操由语音领节奏
            speakRelax("请侧身，让手机看到你的侧面，四秒后开始")
            publishPreparing(nowMs)
            return
        }

        // 视角不对时周期性提醒（正脸测不出头前倾，别让用户白做）
        if (tooFrontal && nowMs - lastFrontalWarnMs > 8_000) {
            lastFrontalWarnMs = nowMs
            speakRelax("角度太正了，请侧一点")
        }

        val nowSec = (nowMs - relaxStartMs) / 1000.0
        val events = eng.onTheta(nowSec, theta)

        // 基准刚记好 → 用语音告诉用户可以开始收了（看不见屏幕时的关键提示）
        if (!relaxSpokenGo && eng.phase == RelaxEngine.Phase.WAITING) {
            relaxSpokenGo = true
            speakRelax("基准已记录，下巴向后收，保持两秒")
        }

        for (e in events) {
            when (e) {
                is RelaxEngine.Event.RepStart -> vibrate(longArrayOf(0, 60))
                is RelaxEngine.Event.RepDone -> {
                    vibrate(longArrayOf(0, 40, 60, 120))
                    // 报数：侧身盲做时这就是唯一的进度指示
                    speakRelax(CN_NUM.getOrElse(e.rep - 1) { e.rep.toString() })
                    if (!relaxSpokenHalfway && e.rep * 2 >= eng.total) {
                        relaxSpokenHalfway = true
                        speakRelax("已经一半了，${CN_NUM.getOrElse(e.rep - 1) { "" }}")
                    }
                }
                is RelaxEngine.Event.Done -> {
                    finishRelax(e.reps, e.totalReps, e.avgDepthDeg, e.relaxScore,
                        "放松完成，共 ${e.reps} 次，放松分 ${e.relaxScore} 分")
                    return
                }
            }
        }
        publishRelax(eng, done = false)
    }

    /** 准备期的快照：界面显示倒计时，引擎还没开始跑。 */
    private fun publishPreparing(nowMs: Long) {
        val left = ((relaxPrepareUntilMs - nowMs) / 1000.0).coerceAtLeast(0.0)
        val snap = RelaxSnapshot(
            phase = "PREPARING",
            rep = 0, total = RELAX_TOTAL_REPS,
            depthDeg = 0.0, targetDeg = 6.0,
            holdProgress = 0.0, holdElapsed = 0.0, holdSec = 2.0,
            theta = Double.NaN, baselineTheta = Double.NaN,
            score = 0, avgDepthDeg = 0.0,
            signalLost = false, done = false, frontal = relaxFrontal,
            prepareSecLeft = kotlin.math.ceil(left).toInt(),
            viewAngleDeg = lastViewAngleDeg
        )
        lastRelaxSnapshot = snap
        onRelaxUpdate?.invoke(snap)
    }

    private fun publishRelax(eng: RelaxEngine, done: Boolean) {
        val snap = RelaxSnapshot(
            phase = eng.phase.name,
            rep = eng.reps, total = eng.total,
            depthDeg = eng.depthDeg, targetDeg = eng.targetDropDeg,
            holdProgress = eng.holdProgress, holdElapsed = eng.holdElapsed, holdSec = eng.holdSec,
            theta = eng.currentTheta, baselineTheta = eng.baselineTheta,
            score = eng.relaxScore, avgDepthDeg = eng.avgDepthDeg,
            signalLost = eng.signalLost, done = done, frontal = relaxFrontal,
            viewAngleDeg = lastViewAngleDeg
        )
        lastRelaxSnapshot = snap
        onRelaxUpdate?.invoke(snap)
    }

    /** 结束放松会话：落库 → 播报 → 发结算快照 → 让管线回到正常坐姿监测。 */
    private fun finishRelax(
        completedReps: Int,
        totalReps: Int,
        avgDepth: Double,
        relaxScore: Int,
        speakText: String,
    ) {
        val eng = relaxEngine
        val durationSec = if (relaxStartMs == 0L) 0
        else ((System.currentTimeMillis() - relaxStartMs) / 1000).toInt()

        // 一次都没做完就不落库：避免「点开又退出」在数据里留下 0 次记录。
        // 要求 ≥2 次也顺手挡掉「坐着一动不动被误判 1 次」这类噪声记录——宁可少记，也别脏数据。
        if (completedReps >= 2) {
            try {
                db.insertRelax(
                    PostureDb.RelaxRow(
                        startSec = relaxStartMs / 1000,
                        durationSec = durationSec,
                        reps = completedReps,
                        totalReps = totalReps,
                        avgDepth = avgDepth,
                        score = relaxScore
                    )
                )
                // 成长经验：完成一节放松操额外加经验（坐姿分那部分由界面按天结算）
                Growth(this).addRelaxXp()
                // 积分奖励（v1.2）：完成放松操额外给币，与「安全坐姿换积分」互补——
                // 前者奖励"主动康复"，后者奖励"保持正确"，两条都能赚才有正向循环。
                Wallet(this).addRelaxBonus()
            } catch (e: Exception) {
                Log.e(TAG, "放松记录写入失败", e)
            }
        }

        relaxEngine = null
        relaxRunning = false
        relaxStartMs = 0L
        relaxPrepareUntilMs = 0L
        speakRelax(speakText)

        val snap = RelaxSnapshot(
            phase = RelaxEngine.Phase.DONE.name,
            rep = completedReps, total = totalReps,
            depthDeg = 0.0, targetDeg = eng?.targetDropDeg ?: 6.0,
            holdProgress = 0.0, holdElapsed = 0.0, holdSec = eng?.holdSec ?: 2.0,
            theta = eng?.currentTheta ?: Double.NaN,
            baselineTheta = eng?.baselineTheta ?: Double.NaN,
            score = relaxScore, avgDepthDeg = avgDepth,
            signalLost = false, done = true, frontal = relaxFrontal,
            viewAngleDeg = lastViewAngleDeg
        )
        lastRelaxSnapshot = snap
        onRelaxUpdate?.invoke(snap)
        Log.i(TAG, "放松结束：完成 $completedReps/$totalReps 次，放松分 $relaxScore，时长 ${durationSec}s")
    }

    /** 放松操的通用震动（节拍/命中反馈）；不读「危险震动」开关——这是交互反馈，等同按键回馈。 */
    private fun vibrate(pattern: LongArray) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(pattern, -1)
        }
    }

    private fun speakRelax(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "relax_cue")
    }

    private fun updateNotification(r: PostureResult?, detected: Boolean) {
        // 提醒通知（带画面）有展示窗口期：这期间不要用每帧的常驻通知把它盖掉，
        // 否则附图只闪 0.3 秒——每帧都会调到这里（约 3 次/秒）。
        if (System.currentTimeMillis() < alertHoldUntilMs) return

        val title: String
        val text: String
        if (!detected || r == null) {
            title = "坐姿守护中"
            text = "未检测到人体（请让侧面对准前置摄像头）"
        } else {
            val lv = when (r.level) {
                Level.SAFE -> "安全"
                Level.WARN -> "注意"
                Level.DANGER -> "危险"
            }
            title = "坐姿：$lv"
            text = "头前倾 ${r.forwardHeadDeg}° · 颈椎负荷 ${r.equivLoadKg}kg"
        }
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(title, text))
    }

    /**
     * 提醒通知：**附上那一刻的画面**。
     *
     * 看数字没感觉，看一眼自己的样子立刻就懂该怎么坐——这是参考竞品 SuperShrimp 后加的能力
     * （它创始人的原话：*通知里带上我的姿态预览，我一眼就知道该怎么调*）。
     *
     * 隐私：缩略图只在本机内存里交给系统通知，**不写任何文件、不上传**，与「画面不落盘」一致；
     * 关掉设置里的开关就只发文字。注意锁屏上通知会显示内容，不想被看到就关掉。
     */
    private fun notifyAlert(r: PostureResult, thumb: Bitmap?, reason: String = "") {
        if (thumb != null) alertHoldUntilMs = System.currentTimeMillis() + ALERT_HOLD_MS
        val lv = when (r.level) {
            Level.SAFE -> "安全"
            Level.WARN -> "注意"
            Level.DANGER -> "危险"
        }
        // 标题带原因（v1.1）：「累计低头」和「持续低头」是不同的行为，用户看到原因才知道怎么改
        val titleSuffix = if (reason.contains("累计")) "（累计低头）" else ""
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("坐姿提醒：$lv$titleSuffix")
            .setContentText(
                if (reason.isNotEmpty()) "$reason —— 抬头活动一下"
                else "头前倾 ${r.forwardHeadDeg}° · 颈椎负荷 ${r.equivLoadKg}kg —— 看一眼，坐正"
            )
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    else PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        if (thumb != null) {
            b.setStyle(NotificationCompat.BigPictureStyle().bigPicture(thumb))
        }
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, b.build())
        Log.i(TAG, "提醒已发出（附图=${thumb != null}，${thumb?.width}x${thumb?.height}）")
    }

    private fun buildNotification(title: String, text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "坐姿守护", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    /**
     * 关停顺序很重要，否则必崩（真机实测 2/2 复现）：
     * 之前是先 `monitor.close()` 释放 MediaPipe 原生资源，而分析线程此刻可能正卡在
     * `BitmapImageBuilder.build()`（nativeCreateRgbaImage）里跑一帧 → use-after-free → SIGSEGV。
     * 正确顺序：解绑相机（停投递）→ 摘分析器 → 等分析线程真正结束 → 才释放原生资源与数据库。
     */
    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        previewUseCase = null

        cameraProvider?.unbindAll()
        analysis?.clearAnalyzer()
        executor.shutdown()

        var analyzerStopped = false
        try {
            analyzerStopped = executor.awaitTermination(3, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        if (analyzerStopped) {
            monitor?.close()
        } else {
            // 线程还没停下就释放 MediaPipe 必崩；宁可泄漏一次，也不要在用户面前闪退
            Log.w(TAG, "分析线程 3 秒内未结束，跳过 MediaPipe 释放以避免 use-after-free")
        }

        flushLog()
        // 基线是节流落盘的（约 10 秒一次），销毁前必须补一次，否则最后几秒学习成果丢失
        if (::baseline.isInitialized) baseline.flush()
        vibrator?.cancel()
        tts?.shutdown()
        db.close()
    }
}

/**
 * 一个「永远是 RESUMED」的 LifecycleOwner。
 *
 * CameraX 的用例绑在 LifecycleOwner 上，Activity 一暂停就会解绑相机——
 * 但本 App 的核心需求恰恰是**锁屏/后台继续守护**。
 * 所以不用 Activity 当 owner，自己造一个把状态钉死在 RESUMED 的 owner。
 */
class AlwaysAliveLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this)

    init {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override val lifecycle: Lifecycle get() = registry
}
