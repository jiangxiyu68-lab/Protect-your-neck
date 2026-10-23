package com.example.cervicalguard.core

/**
 * 「收下巴（chin tuck）」放松操的判定逻辑。
 *
 * 纯逻辑、不依赖 Android，也不依赖任何手机传感器——本机经 `dumpsys sensorservice` 与
 * `pm list features` 查证**没有陀螺仪、也没有磁力计**，手机绝对朝向拿不到；而手机本就架在支架上不动。
 * 因此头部姿态只能来自摄像头关键点，这里只吃 [PostureAnalyzer] 算出的 θ（头前倾角）。
 *
 * 为什么选「收下巴」：它是纯矢状面（前后向）动作，**现有侧视摄像头就能测准**，
 * 不需要新增偏航/侧倾解算，也不必让用户把手机挪到正前方。
 *
 * 动作原理：下巴水平后收（做出双下巴）时头部整体后移 → CVA 增大 → θ 减小。
 * 判定用**相对基准**而非绝对阈值：不同人、不同手机摆位下「坐正」的 θ 差别很大
 * （同一台真机上实测坐正约 8.5°~14.8°），所以开局先量几秒基准 θ0，
 * 之后「θ ≤ θ0 − [targetDropDeg]」并保持 [holdSec] 秒记为一次有效。
 */
class RelaxEngine(
    private val totalReps: Int = 8,
    val holdSec: Double = 2.0,
    val targetDropDeg: Double = 6.0,
    private val calibrateSec: Double = 5.0,
    private val restSec: Double = 1.0,
    private val maxSessionSec: Double = 180.0,
    private val minCalibSamples: Int = 6,
    /** 基准期样本极差超过它就先不采信，再等一档：否则基准偏差会被当成"收下巴"。 */
    private val maxCalibSpreadDeg: Double = 18.0,
    /** 单帧跳变超过它按噪声丢弃（真实收下巴是 1~2 秒的慢动作）。 */
    private val maxJumpDeg: Double = 8.0,
) {

    enum class Phase { CALIBRATING, WAITING, HOLDING, REST, DONE }

    sealed class Event {
        /** 检测到开始收下巴（可以震一下提示用户）。 */
        object RepStart : Event()

        /** 有效完成一次。[depthDeg] 为本次收下巴幅度。 */
        data class RepDone(val rep: Int, val depthDeg: Double) : Event()

        /** 整节结束（做满次数或超时）。 */
        data class Done(val reps: Int, val totalReps: Int, val avgDepthDeg: Double, val relaxScore: Int) : Event()
    }

    var phase: Phase = Phase.CALIBRATING
        private set
    var baselineTheta: Double = Double.NaN
        private set
    var currentTheta: Double = Double.NaN
        private set
    var holdElapsed: Double = 0.0
        private set
    var reps: Int = 0
        private set
    var score: Int = 0
        private set

    /** 当前帧未检出人体：所有计时暂停，避免「离开镜头」被算作保持。 */
    var signalLost: Boolean = false
        private set

    private var startSec = 0.0
    private var lastSec = -1.0   // -1 = 还没喂过帧（第 0 秒也是合法时间戳，不能用 0 当哨兵）
    private var phaseStartSec = 0.0
    private var restElapsed = 0.0
    private val calib = ArrayList<Double>()
    private var depthSum = 0.0
    private var depthCount = 0

    // 突变过滤：上一帧采信的角度 + 待确认的跳变
    private var lastAccepted = Double.NaN
    private var pendingJump = Double.NaN

    val total: Int get() = totalReps
    val avgDepthDeg: Double get() = if (depthCount == 0) 0.0 else depthSum / depthCount

    /** 收下巴幅度（相对基准），反向按 0 计。 */
    val depthDeg: Double
        get() = if (baselineTheta.isNaN() || currentTheta.isNaN()) 0.0
        else (baselineTheta - currentTheta).coerceAtLeast(0.0)

    /** 保持进度 0~1。 */
    val holdProgress: Double get() = (holdElapsed / holdSec).coerceIn(0.0, 1.0)

    /** 幅度达成度 0~1.5（画环与加分用）。 */
    val depthProgress: Double get() = (depthDeg / targetDropDeg).coerceIn(0.0, 1.5)

    /** 折算成 0~100 的「颈椎放松分」。 */
    val relaxScore: Int
        get() = ((score.toDouble() / (totalReps * 15.0)) * 100).toInt().coerceIn(0, 100)

    fun start(nowSec: Double) {
        startSec = nowSec
        lastSec = -1.0
        phaseStartSec = nowSec
        phase = Phase.CALIBRATING
        baselineTheta = Double.NaN
        currentTheta = Double.NaN
        holdElapsed = 0.0
        restElapsed = 0.0
        reps = 0
        score = 0
        calib.clear()
        depthSum = 0.0
        depthCount = 0
        signalLost = false
        lastAccepted = Double.NaN
        pendingJump = Double.NaN
    }

    /**
     * 送入一帧角度。[theta] 为 null 表示本帧未检出人体。
     * 返回本次调用产生的事件（可能为空）。
     */
    fun onTheta(nowSec: Double, theta: Double?): List<Event> {
        val events = ArrayList<Event>(2)
        if (phase == Phase.DONE) return events

        val dt = if (lastSec < 0) 0.0 else (nowSec - lastSec).coerceIn(0.0, 1.0)
        lastSec = nowSec
        signalLost = theta == null

        // 超时保护：长时间做不到也收尾，避免界面卡在会话里
        if (nowSec - startSec > maxSessionSec) {
            phase = Phase.DONE
            events.add(Event.Done(reps, totalReps, avgDepthDeg, relaxScore))
            return events
        }

        // 未检出 → 不推进任何计时（防作弊，也防误判）
        if (theta == null) return events
        currentTheta = theta

        // 校准阶段收原始样本（只看中位数），不做突变过滤——人可能正在挪位置坐下
        if (phase == Phase.CALIBRATING) {
            calib.add(theta)
            val elapsed = nowSec - phaseStartSec
            if (elapsed >= calibrateSec && calib.size >= minCalibSamples) {
                val sorted = calib.sorted()
                val spread = sorted.last() - sorted.first()
                // 基准要可信：这段里抖得太厉害说明人还没坐稳，先别采信（最多再等一档时间）
                if (spread > maxCalibSpreadDeg && elapsed < calibrateSec * 2) {
                    // 继续收集，下一帧再判
                } else {
                    baselineTheta = sorted[sorted.size / 2]
                    lastAccepted = baselineTheta
                    phase = Phase.WAITING
                }
            }
            return events
        }

        // 突变过滤：真实的「收下巴」是 1~2 秒的慢动作，单帧跳 ≥ maxJumpDeg 基本是跟踪抖动
        // （真机踩过：正面视角下没做动作却被判完成一次）。连续两帧停在新位置才认定为真实变化。
        if (!lastAccepted.isNaN() && kotlin.math.abs(theta - lastAccepted) > maxJumpDeg) {
            if (!pendingJump.isNaN() && kotlin.math.abs(theta - pendingJump) <= maxJumpDeg) {
                lastAccepted = theta
                pendingJump = Double.NaN
            } else {
                pendingJump = theta
                return events          // 丢弃本帧，计时不推进
            }
        } else {
            lastAccepted = theta
            pendingJump = Double.NaN
        }

        when (phase) {
            Phase.CALIBRATING -> Unit   // 上面已处理并 return

            Phase.WAITING -> {
                if (theta <= baselineTheta - targetDropDeg) {
                    phase = Phase.HOLDING
                    holdElapsed = 0.0
                    events.add(Event.RepStart)
                }
            }

            Phase.HOLDING -> {
                // 只收了一半就松开 → 本次作废，重新来
                if (theta > baselineTheta - targetDropDeg * 0.6) {
                    phase = Phase.WAITING
                    holdElapsed = 0.0
                } else {
                    holdElapsed += dt
                    if (holdElapsed >= holdSec) {
                        reps++
                        val d = depthDeg
                        depthSum += d
                        depthCount++
                        // 基础 10 分 + 幅度加成最多 5 分
                        score += 10 + (d / targetDropDeg * 5.0).toInt().coerceIn(0, 5)
                        events.add(Event.RepDone(reps, d))
                        if (reps >= totalReps) {
                            phase = Phase.DONE
                            events.add(Event.Done(reps, totalReps, avgDepthDeg, relaxScore))
                        } else {
                            phase = Phase.REST
                            restElapsed = 0.0
                        }
                    }
                }
            }

            Phase.REST -> {
                restElapsed += dt
                if (restElapsed >= restSec) phase = Phase.WAITING
            }

            Phase.DONE -> Unit
        }
        return events
    }
}

/**
 * 放松会话的一次快照：服务在分析线程填好，界面只读，避免跨线程共享可变状态。
 */
data class RelaxSnapshot(
    val phase: String,
    val rep: Int,
    val total: Int,
    val depthDeg: Double,
    val targetDeg: Double,
    val holdProgress: Double,
    val holdElapsed: Double,
    val holdSec: Double,
    val theta: Double,
    val baselineTheta: Double,
    val score: Int,          // 0~100 放松分
    val avgDepthDeg: Double,
    val signalLost: Boolean,
    val done: Boolean,
    /** 正对镜头：CVA 不可用，界面应提示转身而不是显示成绩。 */
    val frontal: Boolean = false,
    /** 准备期剩余秒数（>0 表示还在等用户转身，尚未开始）。 */
    val prepareSecLeft: Int = 0,
    /** 估计的拍摄视角（0°=正侧最佳，90°=正脸不可用），用于提示用户摆位。 */
    val viewAngleDeg: Double? = null,
)
