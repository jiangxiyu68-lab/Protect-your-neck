package com.example.cervicalguard.core

import kotlin.math.atan2
import kotlin.math.round

/**
 * 颈椎坐姿分析核心算法（Kotlin 版，与 m0_verify/cva_core.py 逻辑 1:1）。
 *
 * 定位：健康提醒，非医学诊断。仅用「头部力矩简化模型 + Hansraj 经验表」
 * 做趋势性评估。侧面视角下图像 x 轴 ≈ 前后方向。
 */

/** 归一化关键点（对应 MediaPipe NormalizedLandmark 的 x/y）。 */
data class Landmark(val x: Float, val y: Float)

enum class Level { SAFE, WARN, DANGER }

data class PostureResult(
    val cvaDeg: Double,
    val forwardHeadDeg: Double,
    val equivLoadKg: Double,
    val level: Level,
    val desc: String,
)

object PostureAnalyzer {

    // MediaPipe Pose Landmarker 关键点索引
    const val LEFT_EAR = 7
    const val RIGHT_EAR = 8
    const val LEFT_SHOULDER = 11
    const val RIGHT_SHOULDER = 12

    // 健康提醒级阈值（可调）
    const val THRESHOLD_WARN = 15.0
    const val THRESHOLD_DANGER = 30.0

    // Hansraj(2014)：头前倾角(deg) -> 等效颈椎负荷(kg)
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

    fun classify(thetaDeg: Double): Pair<Level, String> = when {
        thetaDeg < THRESHOLD_WARN -> Level.SAFE to "坐姿端正"
        thetaDeg < THRESHOLD_DANGER -> Level.WARN to "轻度头前伸"
        else -> Level.DANGER to "明显头前伸"
    }

    /**
     * 从 33 点关键点列表计算坐姿颈椎指标。
     * @param landmarks 归一化关键点列表（长度 >= 33）
     * @param imgW 图像像素宽
     * @param imgH 图像像素高
     */
    fun analyze(landmarks: List<Landmark>, imgW: Float, imgH: Float): PostureResult {
        val ear = midpoint(landmarks[LEFT_EAR], landmarks[RIGHT_EAR])
        val shoulder = midpoint(landmarks[LEFT_SHOULDER], landmarks[RIGHT_SHOULDER])

        val ex = ear.x * imgW
        val ey = ear.y * imgH
        val sx = shoulder.x * imgW
        val sy = shoulder.y * imgH

        val dx = ex - sx              // 水平前伸，头在肩前方为正
        val dy = sy - ey              // 竖直高度，耳在肩上方为正
        val cva = if (dx != 0f) Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) else 90.0
        val theta = 90.0 - cva        // 相对竖直的头前倾角

        val load = equivalentLoad(theta)
        val (level, desc) = classify(theta)

        return PostureResult(
            cvaDeg = round(cva * 10) / 10,
            forwardHeadDeg = round(theta * 10) / 10,
            equivLoadKg = round(load * 10) / 10,
            level = level,
            desc = desc,
        )
    }

    private fun midpoint(a: Landmark, b: Landmark) =
        Landmark((a.x + b.x) / 2f, (a.y + b.y) / 2f)
}
