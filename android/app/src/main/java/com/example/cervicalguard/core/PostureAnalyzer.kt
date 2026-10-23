package com.example.cervicalguard.core

import kotlin.math.atan2
import kotlin.math.round

/**
 * 颈椎坐姿分析核心算法（与 m0_verify/cva_core.py 逻辑 1:1）。
 * 定位：健康提醒，非医学诊断。
 */

/** 归一化关键点（对应 MediaPipe NormalizedLandmark 的 x / y / visibility）。 */
data class Landmark(val x: Float, val y: Float, val visibility: Float = 1f)

enum class Level { SAFE, WARN, DANGER }

data class PostureResult(
    val cvaDeg: Double,
    val forwardHeadDeg: Double,
    val equivLoadKg: Double,
    val level: Level,
    val desc: String,
)

object PostureAnalyzer {

    const val LEFT_EAR = 7
    const val RIGHT_EAR = 8
    const val LEFT_SHOULDER = 11
    const val RIGHT_SHOULDER = 12

    const val THRESHOLD_WARN = 15.0
    const val THRESHOLD_DANGER = 30.0

    /**
     * 关键点可见度低于此值即视为「不可用」。
     * 侧面拍摄时远侧耳/肩被遮挡，MediaPipe 会给一个「猜」的坐标，
     * 直接取双耳/双肩中点会把 CVA 拉偏（真机曾出现头前倾 68.9° 这种离谱值）。
     */
    const val MIN_VISIBILITY = 0.5f

    /** 头前倾角 θ 的物理合理区间（°）。超出即判为误检并丢弃该帧。 */
    const val THETA_MIN = -15.0
    const val THETA_MAX = 80.0

    /**
     * 耳点「可信程度」低于此值时，改用「鼻—肩」备用测量（v1.1，耳机场景）。
     *
     * 背景：戴头戴式耳机时两只耳朵被完全盖住，MediaPipe 对耳点只能"推理"，
     * 位置会漂 0.05~0.1（真机实测），导致 θ 抖动。鼻子永远不会被耳机遮住，用它更稳。
     * 注意 MediaPipe 对**遮挡部位也常给 1.00 的 visibility**，所以这里取的是
     * 「同侧耳/肩可见度的较小值」+ 稳定性判定，而不是单看耳点 visibility。
     */
    const val EAR_STABILITY_MIN = 0.9f

    /**
     * 「鼻—肩」角度换算成 CVA 口径的经验偏移（°）。
     * 鼻子比耳朵靠前，同一姿势下鼻—肩角更大；实测同一姿势耳-肩 θ≈5°、鼻-肩 θ≈18°。
     * 这只是一个把两者拉到同一量纲的**经验值**，不是解剖学精确换算——
     * 它的作用是在耳点不可靠时提供稳定读数，而不是替代 CVA。
     */
    const val NOSE_TO_EAR_OFFSET = -13.0

    private val HANSRAJ = listOf(
        0.0 to 5.0, 15.0 to 12.0, 30.0 to 18.0, 45.0 to 22.0, 60.0 to 27.0
    )

    fun equivalentLoad(thetaDeg: Double): Double {
        val t = maxOf(0.0, thetaDeg)
        if (t <= HANSRAJ.first().first) return HANSRAJ.first().second
        for (i in 0 until HANSRAJ.size - 1) {
            val (a0, l0) = HANSRAJ[i]
            val (a1, l1) = HANSRAJ[i + 1]
            if (t in a0..a1) {
                return l0 + (l1 - l0) * (t - a0) / (a1 - a0)
            }
        }
        return HANSRAJ.last().second
    }

    fun classify(
        thetaDeg: Double,
        warnDeg: Double = THRESHOLD_WARN,
        dangerDeg: Double = THRESHOLD_DANGER,
    ): Pair<Level, String> = when {
        thetaDeg < warnDeg -> Level.SAFE to "坐姿端正"
        thetaDeg < dangerDeg -> Level.WARN to "轻度头前伸"
        else -> Level.DANGER to "明显头前伸"
    }

    /**
     * 单帧几何测量 → 头前倾角 θ（°）。
     * 关键点不可信（都不可见）或数值不合理时返回 null，调用方应视为「未检出」：
     * 不计数、不落库、不提醒，避免单帧误检污染统计与误报。
     */
    fun measure(
        landmarks: List<Landmark>,
        imgW: Float,
        imgH: Float,
        earJittery: Boolean = false,
    ): Double? = measureDetail(landmarks, imgW, imgH, earJittery)?.theta

    /**
     * 单帧测量详情：θ + **实际用到的**耳/肩点（供界面把识别过程画出来）。
     * measure() 内部会按可见度选边，只返回角度就没法解释「它到底在看哪里」。
     *
     * [nearLeft] 记录选中的是**受试者的哪一侧**（true=左侧），界面据此把另一侧画淡。
     * [viaNose] 为 true 表示本帧用的是「鼻—肩」备用测量（耳朵被遮挡时的降级路径）。
     */
    data class MeasureDetail(
        val theta: Double,
        val ear: Landmark,
        val shoulder: Landmark,
        val cvaDeg: Double,
        val nearLeft: Boolean,
        val viaNose: Boolean = false,
    )

    fun measureDetail(
        landmarks: List<Landmark>,
        imgW: Float,
        imgH: Float,
        /**
         * 调用方判断「耳点抖动明显大于鼻点」时为 true（见 PoseMonitor 的抖动统计）。
         * **为什么不用 visibility 判断**：MediaPipe 对被耳机完全遮住的耳朵也常给 1.00，
         * 按可见度永远不会触发备用测量——真机验证时才发现，所以改用跨帧抖动。
         */
        earJittery: Boolean = false,
    ): MeasureDetail? {
        if (landmarks.size <= RIGHT_SHOULDER) return null
        // 同侧成对取点（见 bestSide 注释）：耳与肩必须来自同一侧，否则 CVA 没有几何意义
        val side = bestSide(landmarks) ?: return null
        val ear = side.ear
        val shoulder = side.shoulder

        val dy = (shoulder.y - ear.y) * imgH
        if (dy <= 0f) return null                       // 肩必须在耳下方，否则这一帧的点是坏的

        // ⚠ 朝向归一化（2026-10-19 修）：θ = 90 − atan2(dy, dx) 只对「人朝画面右侧」成立。
        // 人朝左时耳朵在肩膀左边（dx<0），同一姿势会被算成**后仰**（实测 −32.6°），
        // 超出合理范围后整帧被丢弃——换个朝向就测不出前倾了。
        // 用鼻子相对双耳中线的位置判断朝向，把水平位移统一成「前为正」。
        val nose = landmarks[0]
        val earMidX = (landmarks[LEFT_EAR].x + landmarks[RIGHT_EAR].x) / 2f
        val facingLeft = nose.x < earMidX

        // ── 耳机场景：耳点不可靠时用「鼻—肩」降级（v1.1） ──
        // 戴头戴式耳机时两只耳朵被完全盖住，MediaPipe 的耳点是"推理"出来的，
        // 位置会漂 0.05~0.1，导致 θ 抖动。鼻子永远不会被耳机遮住，用它更稳。
        // 触发条件是**跨帧抖动**（earJittery），不是 visibility——见参数注释。
        val useNose = earJittery && nose.visibility >= MIN_VISIBILITY
        if (useNose) {
            val dxN = (nose.x - shoulder.x) * imgW
            val dxF = if (facingLeft) -dxN else dxN
            val dyN = (shoulder.y - nose.y) * imgH
            if (dyN <= 0f) return null
            val theta = noseThetaToForward(dyN, dxF)
            if (theta == null) return null
            val cva = 90.0 - theta
            return MeasureDetail(theta, nose, shoulder, cva, side.isLeft, viaNose = true)
        }

        val dxRaw = (ear.x - shoulder.x) * imgW
        val dxForward = if (facingLeft) -dxRaw else dxRaw
        if (dxForward == 0f && dy == 0f) return null

        val cva = Math.toDegrees(atan2(dy.toDouble(), dxForward.toDouble()))
        val theta = 90.0 - cva
        if (theta !in THETA_MIN..THETA_MAX) return null
        return MeasureDetail(theta, ear, shoulder, cva, side.isLeft)
    }

    /**
     * 「鼻—肩」几何 → 头前倾角（°）。
     *
     * 鼻子比耳朵更靠前，所以同一个姿势下鼻—肩连线更接近水平、算出的角度更大。
     * 这里不做物理换算（人头尺寸个体差异太大），而是用**固定的经验偏移**把它拉回
     * 与 CVA 同一量纲——目的只是「和耳-肩口径一致、可比」，不是解剖学精确值。
     *
     * 实测（同一姿势对比）：耳-肩 θ≈5°，鼻-肩 θ≈18°，故偏移取 −13°。
     * 这个数字随手机高度/距离会变，所以只在耳点确实不稳时才用（宁可略偏，也好过剧烈抖动）。
     */
    private fun noseThetaToForward(dyN: Float, dxForward: Float): Double? {
        val cvaNose = Math.toDegrees(atan2(dyN.toDouble(), dxForward.toDouble()))
        val raw = 90.0 - cvaNose
        val theta = raw + NOSE_TO_EAR_OFFSET
        return if (theta in THETA_MIN..THETA_MAX) theta else null
    }

    /**
     * 估计**拍摄视角**：0° = 纯侧面（最佳），90° = 正对镜头（CVA 失效）。
     *
     * 为什么需要它：CVA 定义在矢状面上，而图像上量到的水平位移是投影——
     * `dx_measured ≈ 真实前后位移·cos φ + 左右偏移·sin φ`。耳与肩在左右方向基本重合，
     * 所以 45° 时前后位移只剩 71%（相对变化仍单调可用），60° 只剩一半，
     * 正脸时几乎全部来自左右偏移、与头前倾无关。
     *
     * 估计方法（只用现成关键点，代价为零）：设头宽≈15cm、鼻到耳中线≈12cm，则
     *   耳间距投影 ≈ 15·sin φ，鼻相对耳中线的水平投影 ≈ 12·cos φ
     *   r = 鼻偏移 / 耳间距 = 0.8·cot φ  →  φ = atan(0.8 / r)
     * 人头尺寸因人而异，这是**粗略但单调**的估计，够用来给用户做摆位引导。
     */
    fun estimateViewAngleDeg(landmarks: List<Landmark>): Double? {
        if (landmarks.size <= RIGHT_EAR) return null
        val nose = landmarks[0]
        val le = landmarks[LEFT_EAR]
        val re = landmarks[RIGHT_EAR]
        if (nose.visibility < MIN_VISIBILITY) return null
        val interEar = kotlin.math.abs(le.x - re.x).toDouble()
        val noseOff = kotlin.math.abs(nose.x - (le.x + re.x) / 2f).toDouble()
        // 耳间距趋近 0 → 接近正侧面（两耳在图像上重合），比值会爆掉，直接按 0° 处理
        if (interEar < 0.02) return 0.0
        val r = (noseOff / interEar).coerceAtLeast(1e-3)
        return Math.toDegrees(kotlin.math.atan(0.8 / r)).coerceIn(0.0, 90.0)
    }

    /** 由（已平滑的）头前倾角生成完整结果：等效负荷 + 分级。 */
    fun fromTheta(
        thetaDeg: Double,
        warnDeg: Double = THRESHOLD_WARN,
        dangerDeg: Double = THRESHOLD_DANGER,
    ): PostureResult {
        val load = equivalentLoad(thetaDeg)
        val (level, desc) = classify(thetaDeg, warnDeg, dangerDeg)
        return PostureResult(
            cvaDeg = round((90.0 - thetaDeg) * 10) / 10,
            forwardHeadDeg = round(thetaDeg * 10) / 10,
            equivLoadKg = round(load * 10) / 10,
            level = level,
            desc = desc,
        )
    }

    /**
     * 单帧完整分析（保留此 API 便于对照/迭代）。
     * 注意：实际管线（PoseMonitor）走的是 measure → 滑窗中位数 → fromTheta，
     * 比本函数多了时间平滑，因此与 m0_verify 的 Python 原型已非严格 1:1。
     */
    fun analyze(
        landmarks: List<Landmark>,
        imgW: Float,
        imgH: Float,
        warnDeg: Double = THRESHOLD_WARN,
        dangerDeg: Double = THRESHOLD_DANGER,
    ): PostureResult? =
        measure(landmarks, imgW, imgH)?.let { fromTheta(it, warnDeg, dangerDeg) }

    /**
     * 选边策略：**用几何关系选，不用 visibility**。
     *
     * 真机实测（2026-10-19）：
     * ```
     * earL=(0.72,0.75,v=1.00) earR=(0.59,0.75,v=1.00)
     * shL =(0.78,0.97,v=0.98) shR =(0.35,0.99,v=0.98)
     * ```
     * **四个点的 visibility 全是 ~1.00，连被头完全挡住的那只耳也是**——所以拿 visibility 选边等于随机，
     * 之前的实现就是这么错的：选中了 (earR 0.59, shR 0.35)，横向差 0.24，把一根斜线当成 CVA
     * → 虚报出头前倾 40°（用户一眼看出「肩膀有两个，你测错肩膀了」，才查到）。
     *
     * 正确判据来自解剖：**同一矢状面上的耳与肩，投影下来几乎在同一条竖线上**
     * （耳屏在肩峰正上方偏前少许）。所以同一侧配对里 `|耳x − 肩x| / 纵向差距` 会明显更小：
     * 正确配对 0.06/0.22 ≈ 0.27，错误配对 0.24/0.24 ≈ 1.0，区分度足够。
     *
     * 约束：肩必须在耳下方（图像 y 更大），否则这一侧的点是坏的。
     */
    private fun bestSide(landmarks: List<Landmark>): Side? {
        val leftEar = landmarks[LEFT_EAR]
        val rightEar = landmarks[RIGHT_EAR]
        if (leftEar.visibility < MIN_VISIBILITY || rightEar.visibility < MIN_VISIBILITY) return null

        /** 越小越像「同一条竖线上的耳和肩」。纵向差距太小说明肩膀点被猜到了头部高度。 */
        fun slant(ear: Landmark, shoulder: Landmark): Double {
            if (shoulder.visibility < MIN_VISIBILITY) return Double.MAX_VALUE
            val dy = (shoulder.y - ear.y).toDouble()
            if (dy <= 0.06) return Double.MAX_VALUE
            return kotlin.math.abs((ear.x - shoulder.x).toDouble()) / dy
        }

        val leftSh = landmarks[LEFT_SHOULDER]
        val rightSh = landmarks[RIGHT_SHOULDER]
        val l = slant(leftEar, leftSh)
        val r = slant(rightEar, rightSh)
        return when {
            l == Double.MAX_VALUE && r == Double.MAX_VALUE -> null
            l <= r -> Side(leftEar, leftSh, true, minOf(leftEar.visibility, leftSh.visibility))
            else -> Side(rightEar, rightSh, false, minOf(rightEar.visibility, rightSh.visibility))
        }
    }

    /** 选中的一侧：耳、肩、左侧与否，以及耳点的「可信程度」（0~1，越高越可信）。 */
    data class Side(
        val ear: Landmark,
        val shoulder: Landmark,
        val isLeft: Boolean,
        /** 耳点可信度：取同侧耳/肩可见度的较小值；被耳机遮挡时会明显偏低。 */
        val earStability: Float = 1f,
    )
}
