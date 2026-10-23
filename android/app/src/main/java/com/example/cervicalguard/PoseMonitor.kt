package com.example.cervicalguard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.example.cervicalguard.core.Landmark
import com.example.cervicalguard.core.Level
import com.example.cervicalguard.core.PoseOverlay
import com.example.cervicalguard.core.PostureAnalyzer
import com.example.cervicalguard.core.PostureResult
import com.example.cervicalguard.core.PostureStateMachine
import com.example.cervicalguard.data.Thresholds
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult

/**
 * CameraX 帧 → MediaPipe 姿态 → CVA 分析 → 状态机 → 提醒。
 * 侧面视角拍摄；颈椎判定为健康提醒级，非医学诊断。
 */
class PoseMonitor(
    private val context: Context,
    private val onResult: (PostureResult?, Boolean) -> Unit,
    /** 提醒回调：附带那张画面缩略图（设置里可关）+ 触发原因（v1.1 起区分「持续低头」/「累计低头」）。 */
    private val onAlert: (PostureResult, Bitmap?, String) -> Unit,
    /** 是否处于放松模式：放松时用快窗角度、并挂起姿态告警。 */
    private val wantRelax: () -> Boolean = { false },
    /** 放松模式下的「跟手」角度回调（null = 本帧未检出人体）。 */
    private val onRelaxTheta: (Double?) -> Unit = {},
    /** 每帧上报估计的拍摄视角（0°=正侧，90°=正脸），供界面提示用户摆位。 */
    private val onViewAngle: (Double?) -> Unit = {},
    /** 提醒时是否附带画面缩略图。 */
    private val wantSnapshot: () -> Boolean = { false },
    /** 每帧上报「识别可视化」数据（界面据此把耳朵/肩膀/CVA 连线画在画面上）。 */
    private val onOverlay: (PoseOverlay?) -> Unit = {},
) : ImageAnalysis.Analyzer {

    companion object {
        private const val TAG = "PoseMonitor"

        /**
         * 放松操能容忍的最大拍摄夹角。
         * 60° 时前后位移只剩约一半（cos60°=0.5），相对变化仍单调可判，
         * 用户只要收得稍深一点照样计分；再正就基本测不到前后位移了。
         */
        const val MAX_RELAX_VIEW_DEG = 60.0

        /**
         * 超过这个夹角就**不弹坐姿提醒**。
         * 视角越正，量到的"头前倾"里混入的左右偏移越多（真机上 47° 时读数 41°、90° 时只有 7°，
         * 误差方向还不一致）——拿这种数值去报警就是误报，宁可安静。
         */
        const val MAX_ALERT_VIEW_DEG = 50.0

        /** 提醒缩略图的宽度（像素）：够看清姿势，又不至于让通知超限。 */
        private const val THUMB_W = 360
    }

    private var lastFrameW = -1
    private var lastFrameH = -1
    private var lastRotation = -1

    /** 关键点诊断日志节流。 */
    private var lastLmLogMs = 0L

    /** 本帧估计的拍摄视角（0°=正侧，90°=正脸）。 */
    private var viewAngle: Double? = null

    private val stateMachine = PostureStateMachine(
        dangerHoldSeconds = Thresholds(context).holdSeconds,
        cooldownSeconds = Thresholds(context).cooldownSeconds
    )
    private val startMs = System.currentTimeMillis()

    /**
     * 阈值**动态读取**（v1.1 起）。
     * 原来在构造时把 warnDeg/dangerDeg 读成固定值，导致「设置里改了阈值要重启才生效」，
     * 后期加了「个人基线自适应」后更必须动态读——基线会随使用慢慢变化。
     * 每次都读 SharedPreferences 有开销，但每帧只有 2~3 次，可忽略。
     */
    private val warnDeg: Double get() = Thresholds(context).effectiveWarnDeg()
    private val dangerDeg: Double get() = Thresholds(context).effectiveDangerDeg()
    private var lastTs = 0L

    /** 上一次喂给状态机的时间戳（毫秒），用于算帧间时长 dt。 */
    private var lastStateMs = 0L

    // ── 耳点抖动检测（v1.1，耳机场景） ──
    private var prevEarX = Float.NaN
    private var prevEarY = Float.NaN
    private var prevNoseX = Float.NaN
    private var prevNoseY = Float.NaN
    /** 耳点与鼻点的滑动抖动均值（归一化位移）。 */
    private var earJitterEma = 0.0
    private var noseJitterEma = 0.0
    private var earJittery = false

    /**
     * 用**跨帧抖动**判断耳点是否可信。
     *
     * 为什么不用 visibility：MediaPipe 对被耳机完全遮住的耳朵也常给 1.00，
     * 按可见度判断永远不会触发备用测量（真机验证时发现）。
     * 而抖动是真实的：耳点被遮挡时会逐帧乱跳，鼻子则稳定得多。
     */
    private fun updateJitter(lms: List<Landmark>) {
        val ear = lms[PostureAnalyzer.LEFT_EAR]
        val earR = lms[PostureAnalyzer.RIGHT_EAR]
        // 取两只耳的平均位置，避免"这一帧选左那只、下一帧选右那只"直接算成巨大抖动
        val ex = (ear.x + earR.x) / 2f
        val ey = (ear.y + earR.y) / 2f
        val nose = lms[0]
        if (!prevEarX.isNaN()) {
            val de = kotlin.math.hypot((ex - prevEarX).toDouble(), (ey - prevEarY).toDouble())
            val dn = kotlin.math.hypot(
                (nose.x - prevNoseX).toDouble(), (nose.y - prevNoseY).toDouble()
            )
            earJitterEma = earJitterEma * 0.8 + de * 0.2
            noseJitterEma = noseJitterEma * 0.8 + dn * 0.2
            // 耳点抖动是鼻点的 2.5 倍以上、且绝对值足够大 → 判定不可靠
            earJittery = earJitterEma > 0.012 && earJitterEma > noseJitterEma * 2.5
        }
        prevEarX = ex; prevEarY = ey
        prevNoseX = nose.x; prevNoseY = nose.y
    }

    /**
     * 头前倾角的滑动中位数（抗单帧/短时误检）。
     * 按「时间窗」而非「帧数窗」：真机实测本机检出帧率仅约 2.2 fps（见 PostureService 日志），
     * 固定 5 帧只覆盖约 2.3 秒，尖峰容易漏过；10 秒窗约合 22 个样本，既能滤掉短时误检，
     * 又不会带来设计文档里「30 秒窗」那种十几秒的判定滞后。
     * 无效帧不入窗，且过期样本按时间戳淘汰（人离开后不会残留旧值）。
     */
    private val thetaSamples = ArrayDeque<Pair<Long, Double>>()
    private val smoothWindowMs = 10_000L

    /**
     * 放松操专用的**快窗**（1.5 秒）。
     * 10 秒窗做游戏会滞后约 5 秒——用户已经收好下巴了界面还显示「没收到」，没法玩。
     * 只在放松模式下维护，平时不产生额外开销。
     * 注意别把快窗结果用于姿态分级/落库：窗口太短，正是当初会误报的那种抖动。
     */
    private val fastSamples = ArrayDeque<Pair<Long, Double>>()
    private val fastWindowMs = 1_500L

    private val landmarker: PoseLandmarker = run {
        val base = BaseOptions.builder()
            .setModelAssetPath("pose_landmarker_lite.task")
            .build()
        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.VIDEO)
            .setNumPoses(1)
            .setMinPoseDetectionConfidence(0.5f)
            .setMinPosePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .build()
        PoseLandmarker.createFromOptions(context, options)
    }

    override fun analyze(image: ImageProxy) {
        try {
            val original: Bitmap = image.toBitmap()
            val rotation = image.imageInfo.rotationDegrees
            var bitmap: Bitmap = original
            if (rotation != 0) {
                val m = Matrix()
                m.postRotate(rotation.toFloat())
                val rotated = Bitmap.createBitmap(original, 0, 0, original.width, original.height, m, true)
                if (rotated != original) bitmap = rotated
            }

            // 诊断用：帧尺寸/旋转一旦变化就记一条。CVA 对方向极其敏感——
            // 若分析帧退化成横幅，人的竖直轴会落到图像 x 轴上，θ 会假性飙到 70~90°（真机踩过）。
            if (bitmap.width != lastFrameW || bitmap.height != lastFrameH || rotation != lastRotation) {
                Log.i(
                    TAG,
                    "分析帧 ${original.width}x${original.height} rotation=$rotation → 旋转后 ${bitmap.width}x${bitmap.height}"
                )
                lastFrameW = bitmap.width
                lastFrameH = bitmap.height
                lastRotation = rotation
            }
            val mpImage = BitmapImageBuilder(bitmap).build()

            var ts = System.currentTimeMillis() - startMs
            if (ts <= lastTs) ts = lastTs + 1
            lastTs = ts

            val result = landmarker.detectForVideo(mpImage, ts)
            processResult(result, bitmap.width.toFloat(), bitmap.height.toFloat(), bitmap)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            image.close()
        }
    }

    private fun processResult(result: PoseLandmarkerResult, w: Float, h: Float, frame: Bitmap) {
        val lms = result.landmarks()
        val nowMs = System.currentTimeMillis()
        val nowSec = (nowMs - startMs) / 1000.0

        // 本帧代表的时长（秒）：驱动「累计低头」和「疲劳模型」。
        // 不传它的话，v1.1 新增的两条提醒规则就完全不工作（等于还是 v1.0 行为）。
        val dtSec = if (lastStateMs == 0L) 0.0
        else ((nowMs - lastStateMs) / 1000.0).coerceIn(0.0, 3.0)
        lastStateMs = nowMs

        var detail: PostureAnalyzer.MeasureDetail? = null
        var landmarks: List<Landmark> = emptyList()
        if (!lms.isNullOrEmpty()) {
            val lm = lms[0]
            landmarks = (0 until lm.size).map {
                // visibility() 是 Optional<Float>（MediaPipe Java API），缺省按 1.0 处理
                Landmark(lm[it].x(), lm[it].y(), lm[it].visibility().orElse(1f))
            }
            viewAngle = PostureAnalyzer.estimateViewAngleDeg(landmarks)
            // 先更新抖动统计，再测量（本帧就要用这个判断）
            updateJitter(landmarks)
            detail = PostureAnalyzer.measureDetail(landmarks, w, h, earJittery)
            // 诊断日志（每 2 秒一条）：把耳/肩四个点的坐标与可见度打出来。
            // 选边规则不能靠猜——到底哪只耳可见、MediaPipe 给的 visibility 可不可信，必须看数字。
            if (nowMs - lastLmLogMs > 2_000) {
                lastLmLogMs = nowMs
                fun fmt(i: Int) = landmarks.getOrNull(i)?.let {
                    "(%.3f,%.3f,v=%.2f)".format(it.x, it.y, it.visibility)
                } ?: "-"
                Log.i(TAG, "LM earL=${fmt(7)} earR=${fmt(8)} shL=${fmt(11)} shR=${fmt(12)} " +
                        "选中=${if (detail == null) "无" else if (detail.nearLeft) "左" else "右"}")
            }
        }
        val rawTheta = detail?.theta
        onViewAngle(viewAngle)
        // 把这一帧的耳/肩/鼻与角度交给界面画出来（标不出来，用户就只能盲信）
        onOverlay(buildOverlay(detail, landmarks, viewAngle))

        // 无人体或关键点不可信（远侧耳/肩被遮挡、数值离谱）→ 一律按「未检出」处理：
        // 不计数、不落库、不推进状态机，从源头挡掉误报与脏数据。
        val relaxing = wantRelax()
        if (rawTheta == null) {
            if (relaxing) onRelaxTheta(null)
            stateMachine.update(nowSec, Level.SAFE, personDetected = false, dtSec = dtSec)
            onResult(null, false)
            return
        }

        // 放松模式：用快窗角度驱动游戏，并挂起姿态告警
        // （否则用户正在做放松操，却收到「低头太久」的提醒，自相矛盾；其角度也会污染坐姿统计）
        if (relaxing) {
            // 视角过正时角度不可用：按「未检出」喂给引擎让计时暂停，界面提示转身
            val tooFrontal = (viewAngle ?: 0.0) > MAX_RELAX_VIEW_DEG
            onRelaxTheta(if (tooFrontal) null else medianFast(rawTheta, nowMs))
            onResult(PostureAnalyzer.fromTheta(medianTheta(rawTheta, nowMs), warnDeg, dangerDeg), true)
            return
        }

        val theta = medianTheta(rawTheta, nowMs)
        val r = PostureAnalyzer.fromTheta(theta, warnDeg, dangerDeg)
        onResult(r, true)
        val ev = stateMachine.update(nowSec, r.level, personDetected = true, dtSec = dtSec)
        if (ev != null) {
            // 视角过正时不提醒：数值本身就不可信，弹出去只是误报（详见 MAX_ALERT_VIEW_DEG 注释）
            val usable = (viewAngle ?: 0.0) <= MAX_ALERT_VIEW_DEG
            if (usable) {
                onAlert(r, if (wantSnapshot()) makeThumbnail(frame) else null, ev.reason)
            }
        }
    }

    /**
     * 把测量细节 + 鼻/双耳整理成界面可直接画的归一化数据。
     * 缺关键点（未检出）时返回 null，界面就什么都不画。
     */
    private fun buildOverlay(
        d: PostureAnalyzer.MeasureDetail?,
        lms: List<Landmark>,
        viewAngle: Double?,
    ): PoseOverlay? {
        if (d == null || lms.size <= PostureAnalyzer.RIGHT_SHOULDER) return null
        val nose = lms[0]
        val le = lms[PostureAnalyzer.LEFT_EAR]
        val re = lms[PostureAnalyzer.RIGHT_EAR]
        // 另一侧的点：选中的是左侧就用右侧的点做对照（画淡），反之亦然
        val farEar = if (d.nearLeft) re else le
        val farShoulder = if (d.nearLeft) lms[PostureAnalyzer.RIGHT_SHOULDER]
        else lms[PostureAnalyzer.LEFT_SHOULDER]
        return PoseOverlay(
            earX = d.ear.x, earY = d.ear.y,
            shoulderX = d.shoulder.x, shoulderY = d.shoulder.y,
            farEarX = farEar.x, farEarY = farEar.y,
            farShoulderX = farShoulder.x, farShoulderY = farShoulder.y,
            noseX = nose.x, noseY = nose.y,
            earMidX = (le.x + re.x) / 2f, earMidY = (le.y + re.y) / 2f,
            cvaDeg = d.cvaDeg, thetaDeg = d.theta, viewAngleDeg = viewAngle,
            viaNose = d.viaNose
        )
    }

    /**
     * 生成提醒用的缩略图。
     *
     * **必须是新位图**：绝不能把分析用的 [frame] 直接交给系统通知——它同时被 MediaPipe 持有，
     * 跨线程共享同一块像素正是之前 nativeCreateRgbaImage 崩溃（SIGSEGV）的成因。
     * createScaledBitmap 会新建一份，天然隔离。
     */
    private fun makeThumbnail(frame: Bitmap): Bitmap? = try {
        val w = THUMB_W
        val h = (frame.height.toFloat() * w / frame.width).toInt().coerceAtLeast(1)
        Bitmap.createScaledBitmap(frame, w, h, true)
    } catch (e: Exception) {
        Log.w(TAG, "缩略图生成失败：${e.message}")
        null
    }

    /** 放松模式快窗中位数（1.5 秒）。 */
    private fun medianFast(theta: Double, nowMs: Long): Double {
        fastSamples.addLast(nowMs to theta)
        while (fastSamples.isNotEmpty() && nowMs - fastSamples.first().first > fastWindowMs) {
            fastSamples.removeFirst()
        }
        val sorted = fastSamples.map { it.second }.sorted()
        return sorted[sorted.size / 2]
    }

    /** 时间窗滑动中位数：淘汰超期样本后取中位。 */
    private fun medianTheta(theta: Double, nowMs: Long): Double {
        thetaSamples.addLast(nowMs to theta)
        while (thetaSamples.isNotEmpty() &&
            nowMs - thetaSamples.first().first > smoothWindowMs
        ) {
            thetaSamples.removeFirst()
        }
        val sorted = thetaSamples.map { it.second }.sorted()
        return sorted[sorted.size / 2]
    }

    fun close() {
        landmarker.close()
    }
}
