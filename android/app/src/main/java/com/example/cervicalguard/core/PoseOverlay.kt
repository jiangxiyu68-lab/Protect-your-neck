package com.example.cervicalguard.core

/**
 * 一帧的「识别可视化」数据：把算法实际用到的点交给界面画出来。
 *
 * 为什么要这么做：只报一个「视角 56°」用户没法验证。把耳朵、肩膀、CVA 连线画在画面上，
 * 用户一眼就能看出「它盯的是哪里、这条线怎么算的」——这比任何文字解释都有说服力。
 *
 * 坐标全部**归一化到分析帧**（0~1，按旋转后的竖幅计），由界面负责映射到 View 坐标。
 */
data class PoseOverlay(
    // 实际参与计算的耳、肩（已按可见度选边）
    val earX: Float, val earY: Float,
    val shoulderX: Float, val shoulderY: Float,
    // 另一侧（画淡）：让人一眼看出「有两只耳朵/两只肩膀，算法选了这只」
    val farEarX: Float, val farEarY: Float,
    val farShoulderX: Float, val farShoulderY: Float,
    // 左右耳与鼻：用来画「视角」的测量依据
    val noseX: Float, val noseY: Float,
    val earMidX: Float, val earMidY: Float,
    val cvaDeg: Double,
    val thetaDeg: Double,
    val viewAngleDeg: Double?,
    /** 本帧用的是「鼻—肩」备用测量（耳机遮住耳朵时的降级路径）。 */
    val viaNose: Boolean = false,
)
