/**
 * utils/posture.js
 * ============================================================================
 * 从 Android/Kotlin 版「颈椎守护」逐条翻译过来的坐姿算法。
 *
 * 来源（只读，未改动）：
 *   cervical-guard/android/app/src/main/java/com/example/cervicalguard/core/PostureAnalyzer.kt
 *   cervical-guard/android/app/src/main/java/com/example/cervicalguard/core/PostureScore.kt
 *   cervical-guard/android/app/src/main/java/com/example/cervicalguard/core/PostureStateMachine.kt
 *   cervical-guard/android/app/src/main/java/com/example/cervicalguard/PoseMonitor.kt   （平滑/抖动部分）
 *   cervical-guard/android/app/src/main/java/com/example/cervicalguard/data/Thresholds.kt（默认阈值）
 *
 * 这个文件是小程序版里**唯一真正从旧代码复用的部分**：
 *   - 角度公式、Hansraj 等效负荷表、分级、选边策略、视角估计、滑窗中位数、状态机、坐姿分
 *   全部按 Kotlin 逻辑 1:1 翻译，没有"重新发明"。
 *   - 它不依赖任何小程序 API，所以能在 Node 里直接跑单测：
 *       node miniprogram/test/posture.selftest.js
 *
 * 没翻译的部分（属于 Android 专属，必须重写）：
 *   CameraX、MediaPipe PoseLandmarker、Bitmap/Matrix 旋转、前台服务、通知、SQLite。
 *
 * 单位约定：关键点坐标是**归一化坐标**（0~1），imgW/imgH 是像素尺寸。
 * 定位：健康提醒，非医疗诊断。
 * ============================================================================
 */

'use strict';

// ───────────────────────────── 常量（对齐 Kotlin） ─────────────────────────────

/** MediaPipe PoseLandmarker 的 33 点索引里我们用到的那几个。 */
const LM = {
  NOSE: 0,
  LEFT_EAR: 7,
  RIGHT_EAR: 8,
  LEFT_SHOULDER: 11,
  RIGHT_SHOULDER: 12,
};

/** 头前倾角阈值（°，PostureAnalyzer.THRESHOLD_WARN / THRESHOLD_DANGER）。 */
const THRESHOLD_WARN = 15.0;
const THRESHOLD_DANGER = 30.0;

/** 关键点可见度低于此值即视为不可用（PostureAnalyzer.MIN_VISIBILITY）。 */
const MIN_VISIBILITY = 0.5;

/** 头前倾角的物理合理区间（°），超出即判为误检并丢弃该帧。 */
const THETA_MIN = -15.0;
const THETA_MAX = 80.0;

/** 耳点可信度下限（Kotlin 里保留字段，当前管线用跨帧抖动判断，保留以对齐）。 */
const EAR_STABILITY_MIN = 0.9;

/** 「鼻—肩」备用测量换算成 CVA 口径的经验偏移（°）。 */
const NOSE_TO_EAR_OFFSET = -13.0;

/** Hansraj 等效负荷表：头前倾角(°) → 颈椎等效负荷(kg)。 */
const HANSRAJ = [
  [0.0, 5.0],
  [15.0, 12.0],
  [30.0, 18.0],
  [45.0, 22.0],
  [60.0, 27.0],
];

/** 三级判定（Kotlin enum Level）。 */
const LEVEL = { SAFE: 'SAFE', WARN: 'WARN', DANGER: 'DANGER' };

const LEVEL_TEXT = {
  SAFE: '坐姿端正',
  WARN: '轻度头前伸',
  DANGER: '明显头前伸',
};

// ── PoseMonitor.kt 里的常量（平滑与视角闸门） ──
/** 姿态平滑窗口：10 秒（按时间窗，不是帧数窗）。 */
const SMOOTH_WINDOW_MS = 10000;
/** 放松操快窗：1.5 秒。 */
const FAST_WINDOW_MS = 1500;
/** 拍摄视角超过 50° 不弹坐姿提醒（数值本身不可信）。 */
const MAX_ALERT_VIEW_DEG = 50.0;
/** 放松操能容忍的最大拍摄夹角 60°。 */
const MAX_RELAX_VIEW_DEG = 60.0;
/** 耳点抖动判定：EMA 绝对值下限 + 相对鼻点的倍数。 */
const EAR_JITTER_MIN = 0.012;
const EAR_JITTER_RATIO = 2.5;
/** 抖动 EMA 系数（Kotlin: *0.8 + 新值*0.2）。 */
const JITTER_EMA_KEEP = 0.8;

// ───────────────────────────── 小工具 ─────────────────────────────

function clamp(v, lo, hi) {
  return Math.min(hi, Math.max(lo, v));
}

/** 对齐 Kotlin `round(x * 10) / 10`（浮点 ties 极罕见，差异可忽略）。 */
function round1(v) {
  return Math.round(v * 10) / 10;
}

function deg(rad) {
  return (rad * 180) / Math.PI;
}

/** 造一个关键点。 */
function landmark(x, y, visibility) {
  return { x: x, y: y, visibility: visibility === undefined ? 1 : visibility };
}

/** 造一个「空」的关键点数组（长度 33），未赋值的点 visibility=0。 */
function emptyLandmarks() {
  const out = [];
  for (let i = 0; i < 33; i++) out.push(landmark(0, 0, 0));
  return out;
}

// ───────────────────────── 等效负荷 / 分级 / 结果 ─────────────────────────

/**
 * 头前倾角 → 颈椎等效负荷（kg）。Hansraj 表线性插值，两端截断。
 * 对齐 PostureAnalyzer.equivalentLoad。
 */
function equivalentLoad(thetaDeg) {
  const t = Math.max(0.0, thetaDeg);
  if (t <= HANSRAJ[0][0]) return HANSRAJ[0][1];
  for (let i = 0; i < HANSRAJ.length - 1; i++) {
    const a0 = HANSRAJ[i][0];
    const l0 = HANSRAJ[i][1];
    const a1 = HANSRAJ[i + 1][0];
    const l1 = HANSRAJ[i + 1][1];
    if (t >= a0 && t <= a1) {
      return l0 + ((l1 - l0) * (t - a0)) / (a1 - a0);
    }
  }
  return HANSRAJ[HANSRAJ.length - 1][1];
}

/**
 * 头前倾角 → 三级判定。
 * 对齐 PostureAnalyzer.classify。
 * @returns {{level:string, desc:string}}
 */
function classify(thetaDeg, warnDeg, dangerDeg) {
  const w = warnDeg === undefined ? THRESHOLD_WARN : warnDeg;
  const d = dangerDeg === undefined ? THRESHOLD_DANGER : dangerDeg;
  if (thetaDeg < w) return { level: LEVEL.SAFE, desc: LEVEL_TEXT.SAFE };
  if (thetaDeg < d) return { level: LEVEL.WARN, desc: LEVEL_TEXT.WARN };
  return { level: LEVEL.DANGER, desc: LEVEL_TEXT.DANGER };
}

/**
 * 由（已平滑的）头前倾角生成完整结果。
 * 对齐 PostureAnalyzer.fromTheta。
 * @returns {{cvaDeg:number, forwardHeadDeg:number, equivLoadKg:number, level:string, desc:string}}
 */
function fromTheta(thetaDeg, warnDeg, dangerDeg) {
  const load = equivalentLoad(thetaDeg);
  const cls = classify(thetaDeg, warnDeg, dangerDeg);
  return {
    cvaDeg: round1(90.0 - thetaDeg),
    forwardHeadDeg: round1(thetaDeg),
    equivLoadKg: round1(load),
    level: cls.level,
    desc: cls.desc,
  };
}

// ───────────────────────── 选边 / 单帧测量 ─────────────────────────

/**
 * 选边策略：**用几何关系选，不用 visibility**。
 *
 * 真机实测（Android 版 2026-10-19）：四个点 visibility 全是 ~1.00，连被头完全
 * 挡住的那只耳也是，所以按 visibility 选边等于随机，会虚报 40°。
 * 正确判据来自解剖：同一矢状面上的耳与肩投影下来几乎在同一条竖线上，
 * 同侧配对的 |耳x−肩x| / 纵向差 会明显更小。
 *
 * 对齐 PostureAnalyzer.bestSide。
 * @returns {{ear:object, shoulder:object, isLeft:boolean, earStability:number}|null}
 */
function bestSide(landmarks) {
  if (!landmarks || landmarks.length <= LM.RIGHT_SHOULDER) return null;
  const leftEar = landmarks[LM.LEFT_EAR];
  const rightEar = landmarks[LM.RIGHT_EAR];
  if (!leftEar || !rightEar) return null;
  if (leftEar.visibility < MIN_VISIBILITY || rightEar.visibility < MIN_VISIBILITY) {
    return null;
  }

  // 越小越像「同一条竖线上的耳和肩」。纵向差距太小说明肩膀点被猜到了头部高度。
  function slant(ear, shoulder) {
    if (!shoulder) return Infinity;
    if (shoulder.visibility < MIN_VISIBILITY) return Infinity;
    const dy = shoulder.y - ear.y;
    if (dy <= 0.06) return Infinity;
    return Math.abs(ear.x - shoulder.x) / dy;
  }

  const leftSh = landmarks[LM.LEFT_SHOULDER];
  const rightSh = landmarks[LM.RIGHT_SHOULDER];
  const l = slant(leftEar, leftSh);
  const r = slant(rightEar, rightSh);

  if (l === Infinity && r === Infinity) return null;
  if (l <= r) {
    return {
      ear: leftEar,
      shoulder: leftSh,
      isLeft: true,
      earStability: Math.min(leftEar.visibility, leftSh.visibility),
    };
  }
  return {
    ear: rightEar,
    shoulder: rightSh,
    isLeft: false,
    earStability: Math.min(rightEar.visibility, rightSh.visibility),
  };
}

/**
 * 「鼻—肩」几何 → 头前倾角（°）。鼻子比耳朵靠前，用固定经验偏移拉回同一量纲。
 * 对齐 PostureAnalyzer.noseThetaToForward。
 */
function noseThetaToForward(dyN, dxForward) {
  const cvaNose = deg(Math.atan2(dyN, dxForward));
  const raw = 90.0 - cvaNose;
  const theta = raw + NOSE_TO_EAR_OFFSET;
  return theta >= THETA_MIN && theta <= THETA_MAX ? theta : null;
}

/**
 * 单帧几何测量 → 头前倾角 + 实际用到的耳/肩点（供界面把识别过程画出来）。
 * 对齐 PostureAnalyzer.measureDetail。
 *
 * @param {Array} landmarks 归一化关键点（MediaPipe 33 点顺序）
 * @param {number} imgW 帧宽（像素）
 * @param {number} imgH 帧高（像素）
 * @param {boolean} earJittery 耳点是否抖动明显（由 JitterTracker 给出）
 * @returns {{theta:number, ear:object, shoulder:object, cvaDeg:number, nearLeft:boolean, viaNose:boolean}|null}
 */
function measureDetail(landmarks, imgW, imgH, earJittery) {
  if (!landmarks || landmarks.length <= LM.RIGHT_SHOULDER) return null;
  const side = bestSide(landmarks);
  if (!side) return null;

  const ear = side.ear;
  const shoulder = side.shoulder;

  const dy = (shoulder.y - ear.y) * imgH;
  if (dy <= 0) return null; // 肩必须在耳下方，否则这一帧的点是坏的

  // 朝向归一化：θ = 90 − atan2(dy, dx) 只对「人朝画面右侧」成立。
  // 人朝左时 dx<0，同一姿势会被算成后仰。用鼻子相对双耳中线的位置判断朝向。
  const nose = landmarks[LM.NOSE];
  const earMidX = (landmarks[LM.LEFT_EAR].x + landmarks[LM.RIGHT_EAR].x) / 2;
  const facingLeft = nose.x < earMidX;

  // ── 耳机场景：耳点不可靠时用「鼻—肩」降级 ──
  const useNose = !!earJittery && nose.visibility >= MIN_VISIBILITY;
  if (useNose) {
    const dxN = (nose.x - shoulder.x) * imgW;
    const dxF = facingLeft ? -dxN : dxN;
    const dyN = (shoulder.y - nose.y) * imgH;
    if (dyN <= 0) return null;
    const theta = noseThetaToForward(dyN, dxF);
    if (theta === null) return null;
    return {
      theta: theta,
      ear: nose,
      shoulder: shoulder,
      cvaDeg: 90.0 - theta,
      nearLeft: side.isLeft,
      viaNose: true,
    };
  }

  const dxRaw = (ear.x - shoulder.x) * imgW;
  const dxForward = facingLeft ? -dxRaw : dxRaw;
  if (dxForward === 0 && dy === 0) return null;

  const cva = deg(Math.atan2(dy, dxForward));
  const theta = 90.0 - cva;
  if (theta < THETA_MIN || theta > THETA_MAX) return null;

  return {
    theta: theta,
    ear: ear,
    shoulder: shoulder,
    cvaDeg: cva,
    nearLeft: side.isLeft,
    viaNose: false,
  };
}

/** 只要角度时用这个（对齐 PostureAnalyzer.measure）。 */
function measure(landmarks, imgW, imgH, earJittery) {
  const d = measureDetail(landmarks, imgW, imgH, earJittery);
  return d ? d.theta : null;
}

/**
 * 估计**拍摄视角**：0° = 纯侧面（最佳），90° = 正对镜头（CVA 失效）。
 *
 * 设头宽≈15cm、鼻到耳中线≈12cm：
 *   耳间距投影 ≈ 15·sin φ，鼻相对耳中线的水平投影 ≈ 12·cos φ
 *   r = 鼻偏移 / 耳间距 = 0.8·cot φ  →  φ = atan(0.8 / r)
 * 人头尺寸因人而异，这是粗略但单调的估计，够用来给用户做摆位引导。
 *
 * 对齐 PostureAnalyzer.estimateViewAngleDeg。
 * @returns {number|null} 0~90
 */
function estimateViewAngleDeg(landmarks) {
  if (!landmarks || landmarks.length <= LM.RIGHT_EAR) return null;
  const nose = landmarks[LM.NOSE];
  const le = landmarks[LM.LEFT_EAR];
  const re = landmarks[LM.RIGHT_EAR];
  if (!nose || !le || !re) return null;
  if (nose.visibility < MIN_VISIBILITY) return null;

  const interEar = Math.abs(le.x - re.x);
  const noseOff = Math.abs(nose.x - (le.x + re.x) / 2);
  // 耳间距趋近 0 → 接近正侧面（两耳在图像上重合），比值会爆掉，直接按 0° 处理
  if (interEar < 0.02) return 0.0;
  const r = Math.max(noseOff / interEar, 1e-3);
  return clamp(deg(Math.atan(0.8 / r)), 0, 90);
}

/** 单帧完整分析（保留此 API 便于对照/迭代）。对齐 PostureAnalyzer.analyze。 */
function analyze(landmarks, imgW, imgH, warnDeg, dangerDeg) {
  const theta = measure(landmarks, imgW, imgH, false);
  if (theta === null) return null;
  return fromTheta(theta, warnDeg, dangerDeg);
}

// ───────────────────────── 坐姿分（PostureScore.kt） ─────────────────────────

/** 低于这个时长不给分（数据不足，分数没有意义）。 */
const MIN_SECONDS = 60;
/** 「达标」线：连续天数按这个分数判定。 */
const TARGET_SCORE = 75;

/**
 * 坐姿分（0~100）。安全计满分、注意计半分、危险不计分。
 *   score = (safe×100 + warn×50) / (safe + warn + danger)
 * 对齐 PostureScore.of。
 * @returns {number|null} 样本不足返回 null
 */
function scoreOf(safeSec, warnSec, dangerSec, minSeconds) {
  const min = minSeconds === undefined ? MIN_SECONDS : minSeconds;
  const total = safeSec + warnSec + dangerSec;
  if (total < min) return null;
  const raw = Math.round((safeSec * 100.0 + warnSec * 50.0) / total);
  return clamp(raw, 0, 100);
}

/** 分数对应的口语化评价。对齐 PostureScore.grade。 */
function scoreGrade(score) {
  if (score >= 90) return '很棒';
  if (score >= 75) return '良好';
  if (score >= 60) return '一般';
  return '需注意';
}

/** 分数对应的配色键。对齐 PostureScore.gradeKey。 */
function scoreGradeKey(score) {
  if (score >= 75) return 'safe';
  if (score >= 60) return 'warn';
  return 'danger';
}

// ──────────────── 耳点抖动跟踪（PoseMonitor.updateJitter） ────────────────

/**
 * 用**跨帧抖动**判断耳点是否可信（而不是 visibility）。
 * 戴头戴式耳机时两耳被完全盖住，MediaPipe 仍常给 1.00 的 visibility，
 * 按可见度永远不触发备用测量；而抖动是真实的。
 */
class JitterTracker {
  constructor() {
    this.prevEarX = NaN;
    this.prevEarY = NaN;
    this.prevNoseX = NaN;
    this.prevNoseY = NaN;
    this.earJitterEma = 0;
    this.noseJitterEma = 0;
    this.earJittery = false;
  }

  /** @param {Array} lms 归一化关键点 */
  update(lms) {
    if (!lms || lms.length <= LM.RIGHT_SHOULDER) return this.earJittery;
    const earL = lms[LM.LEFT_EAR];
    const earR = lms[LM.RIGHT_EAR];
    const nose = lms[LM.NOSE];
    // 取两只耳的平均位置，避免「这帧选左耳、下帧选右耳」被算成巨大抖动
    const ex = (earL.x + earR.x) / 2;
    const ey = (earL.y + earR.y) / 2;

    if (!Number.isNaN(this.prevEarX)) {
      const de = Math.hypot(ex - this.prevEarX, ey - this.prevEarY);
      const dn = Math.hypot(nose.x - this.prevNoseX, nose.y - this.prevNoseY);
      this.earJitterEma = this.earJitterEma * JITTER_EMA_KEEP + de * (1 - JITTER_EMA_KEEP);
      this.noseJitterEma = this.noseJitterEma * JITTER_EMA_KEEP + dn * (1 - JITTER_EMA_KEEP);
      // 耳点抖动是鼻点的 2.5 倍以上、且绝对值足够大 → 判定不可靠
      this.earJittery =
        this.earJitterEma > EAR_JITTER_MIN &&
        this.earJitterEma > this.noseJitterEma * EAR_JITTER_RATIO;
    }
    this.prevEarX = ex;
    this.prevEarY = ey;
    this.prevNoseX = nose.x;
    this.prevNoseY = nose.y;
    return this.earJittery;
  }
}

// ──────────────── 时间窗滑动中位数（PoseMonitor.medianTheta） ────────────────

/**
 * 按**时间窗**（不是帧数窗）取中位数，抗单帧/短时误检。
 * Android 真机检出帧率仅约 2.2 fps，固定 5 帧只覆盖 2.3 秒；
 * 10 秒窗约合 22 个样本，既能滤掉短时误检，又不会带来十几秒的判定滞后。
 */
class MedianWindow {
  /** @param {number} windowMs 窗口长度（毫秒） */
  constructor(windowMs) {
    this.windowMs = windowMs === undefined ? SMOOTH_WINDOW_MS : windowMs;
    this.samples = []; // [{t:number, v:number}]
  }

  /**
   * 推入一个样本并返回当前窗口中位数。
   * @param {number} theta 角度
   * @param {number} nowMs 当前时间（毫秒）
   * @returns {number}
   */
  push(theta, nowMs) {
    this.samples.push({ t: nowMs, v: theta });
    while (this.samples.length && nowMs - this.samples[0].t > this.windowMs) {
      this.samples.shift();
    }
    const sorted = this.samples.map((s) => s.v).sort((a, b) => a - b);
    return sorted[Math.floor(sorted.length / 2)];
  }

  get size() {
    return this.samples.length;
  }

  reset() {
    this.samples = [];
  }
}

// ──────────── 坐姿提醒状态机（PostureStateMachine.kt，逐行对齐） ────────────

/**
 * 坐姿提醒状态机。
 *
 * 基础规则（v1.0）：
 *  1. 检测不到人（离席）→ 危险计时清零
 *  2. 进入「危险」态并持续 dangerHoldSeconds 秒 → 触发一次提醒
 *  3. 提醒后进入 cooldownSeconds 秒冷却，期间不再提醒
 *  4. 冷却结束且仍危险 → 立即再次提醒
 *  5. 危险途中恢复安全 → 计时清零
 *
 * v1.1 新增两条互补触发条件（都走同一个冷却，不会连环轰炸）：
 *  A. 累计低头：滑窗内累计危险时长超阈值也提醒（治「每 10 秒抬一下头」的漏报）
 *  B. 疲劳累积：连续工作越久判定越敏感（hold 按比例缩短，最短 60%）
 *
 * 注意：Android 版靠前台服务持续喂帧；小程序切后台 5 秒就挂起，
 * 所以这个状态机在小程序里只在**前台页面可见**期间有效，且 dtSec 由真实帧间隔给出。
 */
class PostureStateMachine {
  constructor(options) {
    const o = options || {};
    this.dangerHoldSeconds = o.dangerHoldSeconds === undefined ? 180.0 : o.dangerHoldSeconds;
    this.cooldownSeconds = o.cooldownSeconds === undefined ? 600.0 : o.cooldownSeconds;
    this.cumulativeWindowSec = o.cumulativeWindowSec === undefined ? 900.0 : o.cumulativeWindowSec;
    this.cumulativeLimitSec = o.cumulativeLimitSec === undefined ? 300.0 : o.cumulativeLimitSec;
    this.fatigueAfterSec = o.fatigueAfterSec === undefined ? 3600.0 : o.fatigueAfterSec;
    this.fatigueMinFactor = o.fatigueMinFactor === undefined ? 0.6 : o.fatigueMinFactor;

    this.dangerSince = null;
    this.lastAlertAt = null;
    this.sessionStart = null;
    this.dangerSamples = []; // [{t:number, dt:number}]
    this._cumulativeDangerSec = 0;
  }

  /** 累计窗口内的危险总秒数（供界面显示进度）。 */
  get cumulativeDangerSec() {
    return this._cumulativeDangerSec;
  }

  /**
   * @param {number} now 秒
   * @param {string} level LEVEL.SAFE / WARN / DANGER
   * @param {boolean} personDetected 本帧是否检出可用人体
   * @param {number} dtSec 本次更新代表的时长（秒），用于累计与疲劳；传 0 退化为旧行为
   * @returns {{time:number, reason:string}|null}
   */
  update(now, level, personDetected, dtSec) {
    const dt = dtSec === undefined ? 0 : dtSec;
    if (this.sessionStart === null) this.sessionStart = now;

    if (!personDetected) {
      // 离席：连续计时清零；累计样本保留（人只是站起来喝口水），窗口按时间自然淘汰
      this.dangerSince = null;
      this.prune(now);
      return null;
    }

    this.prune(now);

    if (level !== LEVEL.DANGER) {
      // 关键：**不再清零累计**。用户每 10 秒抬一下头就永远不提醒，是旧规则最大漏报来源。
      this.dangerSince = null;
      return null;
    }

    if (this.dangerSince === null) this.dangerSince = now;
    if (dt > 0) {
      this.dangerSamples.push({ t: now, dt: dt });
      this._cumulativeDangerSec += dt;
    }

    // 冷却期内不提醒（先判，避免白算）
    if (this.lastAlertAt !== null && now - this.lastAlertAt < this.cooldownSeconds) {
      return null;
    }

    // 条件 B：疲劳模型 → hold 越久越短
    const hold = this.effectiveHoldSeconds(now);

    // 条件 A：连续危险够久
    const since = this.dangerSince;
    if (since === null) return null;
    const continuousSec = now - since;
    if (continuousSec >= hold) {
      this.lastAlertAt = now;
      // 提醒后重置起点：否则危险持续时冷却一过就立刻再提醒（真机曾每秒一震）
      this.dangerSince = now;
      this.clearCumulative();
      return {
        time: now,
        reason:
          hold < this.dangerHoldSeconds
            ? '已连续工作 ' + this.workMinutes(now) + ' 分钟，持续低头 ' + Math.trunc(continuousSec) + ' 秒'
            : '持续低头超过 ' + Math.trunc(hold) + ' 秒',
      };
    }

    // 条件 B：累计危险够多（捕捉「反复低头」）
    if (this._cumulativeDangerSec >= this.cumulativeThreshold(now)) {
      this.lastAlertAt = now;
      this.dangerSince = now;
      const cum = this._cumulativeDangerSec;
      this.clearCumulative();
      return {
        time: now,
        reason: Math.trunc(this.cumulativeWindowSec / 60) + ' 分钟内累计低头 ' + Math.trunc(cum) + ' 秒',
      };
    }

    return null;
  }

  /** 疲劳后的 hold 阈值：工作越久越敏感，最短 fatigueMinFactor 倍。 */
  effectiveHoldSeconds(now) {
    const start = this.sessionStart;
    if (start === null) return this.dangerHoldSeconds;
    const worked = now - start;
    if (worked <= this.fatigueAfterSec) return this.dangerHoldSeconds;
    const extra = clamp((worked - this.fatigueAfterSec) / this.fatigueAfterSec, 0, 1);
    const factor = 1.0 - (1.0 - this.fatigueMinFactor) * extra;
    return Math.max(this.dangerHoldSeconds * factor, 10.0);
  }

  /** 疲劳后累计阈值也同步收紧。 */
  cumulativeThreshold(now) {
    const start = this.sessionStart;
    if (start === null) return this.cumulativeLimitSec;
    const worked = now - start;
    if (worked <= this.fatigueAfterSec) return this.cumulativeLimitSec;
    const extra = clamp((worked - this.fatigueAfterSec) / this.fatigueAfterSec, 0, 1);
    return Math.max(this.cumulativeLimitSec * (1.0 - 0.3 * extra), 60.0);
  }

  workMinutes(now) {
    const start = this.sessionStart;
    if (start === null) return 0;
    return Math.trunc((now - start) / 60);
  }

  /** 淘汰滑窗外的样本。 */
  prune(now) {
    const floor = now - this.cumulativeWindowSec;
    while (this.dangerSamples.length && this.dangerSamples[0].t < floor) {
      this._cumulativeDangerSec -= this.dangerSamples.shift().dt;
    }
    if (this._cumulativeDangerSec < 0) this._cumulativeDangerSec = 0;
  }

  clearCumulative() {
    this.dangerSamples = [];
    this._cumulativeDangerSec = 0;
  }
}

// ──────────── VisionKit Body(23点) → MediaPipe(33点) 适配层 ────────────

/**
 * 微信 VisionKit「Body 检测」输出 23 个 2D 关键点（基础库 2.28.0+）。
 * 按官方点位图：0=鼻、1/2=眼、3/4=耳、5/6=肩、7/8=肘、9/10=腕、11/12=髋…
 *
 * 该映射是**按官方点位图推断**的，左右顺序（哪一侧是 3、哪一侧是 4）
 * 与置信度字段名必须真机实测确认 —— 见技术方案「P0 待实测」。
 */
const VK_BODY = {
  NOSE: 0,
  EYE_A: 1,
  EYE_B: 2,
  EAR_A: 3,
  EAR_B: 4,
  SHOULDER_A: 5,
  SHOULDER_B: 6,
};

/** 取一个点上的置信度，兼容 confidence / score / visibility 三种命名。 */
function pointConfidence(p) {
  if (!p) return 0;
  if (typeof p.confidence === 'number') return p.confidence;
  if (typeof p.score === 'number') return p.score;
  if (typeof p.visibility === 'number') return p.visibility;
  return 1; // 字段缺失时按 1 处理（与 Kotlin `visibility().orElse(1f)` 一致）
}

/**
 * VisionKit Body 的 23 点 → 本模块使用的 33 点结构。
 *
 * 关键假设（需实测）：
 *  - points 为**像素坐标**，因此除以 width/height 归一化；
 *  - 3/4 分别是同一侧耳，5/6 是同侧肩，耳与肩的**配对顺序一致**；
 *  - 若画面被镜像（前置摄像头预览），调用方需先做 x → 1-x 再传入，
 *    否则 facingLeft 判断会翻转、角度符号出错。
 *
 * @param {Array} points VK anchor.points
 * @param {number} width 帧宽（像素）
 * @param {number} height 帧高（像素）
 * @param {{mirrored?:boolean}} [opts]
 * @returns {Array} 33 点结构（仅 0/7/8/11/12 有值，其余 visibility=0）
 */
function fromVKBody(points, width, height, opts) {
  const mirrored = !!(opts && opts.mirrored);
  const lms = emptyLandmarks();
  if (!points || !points.length) return lms;
  const w = width || 1;
  const h = height || 1;

  function put(targetIndex, p) {
    if (!p) return;
    let x = p.x / w;
    const y = p.y / h;
    if (mirrored) x = 1 - x;
    lms[targetIndex] = landmark(x, y, pointConfidence(p));
  }

  // 0 = 鼻；7/8 = 双耳；11/12 = 双肩
  put(LM.NOSE, points[VK_BODY.NOSE]);
  put(LM.LEFT_EAR, points[VK_BODY.EAR_A]);
  put(LM.RIGHT_EAR, points[VK_BODY.EAR_B]);
  put(LM.LEFT_SHOULDER, points[VK_BODY.SHOULDER_A]);
  put(LM.RIGHT_SHOULDER, points[VK_BODY.SHOULDER_B]);
  return lms;
}

/**
 * 一步到位：VisionKit 23 点 → 坐姿结果。
 * @returns {{result:object|null, theta:number|null, viewAngleDeg:number|null, detail:object|null}}
 */
function analyzeVKBody(points, width, height, opts) {
  const o = opts || {};
  const lms = fromVKBody(points, width, height, o);
  const viewAngleDeg = estimateViewAngleDeg(lms);
  const detail = measureDetail(lms, width, height, !!o.earJittery);
  if (!detail) {
    return { result: null, theta: null, viewAngleDeg: viewAngleDeg, detail: null };
  }
  return {
    result: fromTheta(detail.theta, o.warnDeg, o.dangerDeg),
    theta: detail.theta,
    viewAngleDeg: viewAngleDeg,
    detail: detail,
  };
}

// ───────────────────────────── 导出 ─────────────────────────────
//
// 同一份文件要同时满足两个运行环境：
//   · Node（自测）：`require('./posture.js')`
//   · 浏览器（网页版）：`<script src="js/posture.js">` 之后用 `window.Posture`
// 所以下面不写死 module.exports，先构造对象再按环境挂载。

const PostureAPI = {
  // 常量
  LM: LM,
  LEVEL: LEVEL,
  LEVEL_TEXT: LEVEL_TEXT,
  THRESHOLD_WARN: THRESHOLD_WARN,
  THRESHOLD_DANGER: THRESHOLD_DANGER,
  MIN_VISIBILITY: MIN_VISIBILITY,
  THETA_MIN: THETA_MIN,
  THETA_MAX: THETA_MAX,
  EAR_STABILITY_MIN: EAR_STABILITY_MIN,
  NOSE_TO_EAR_OFFSET: NOSE_TO_EAR_OFFSET,
  HANSRAJ: HANSRAJ,
  SMOOTH_WINDOW_MS: SMOOTH_WINDOW_MS,
  FAST_WINDOW_MS: FAST_WINDOW_MS,
  MAX_ALERT_VIEW_DEG: MAX_ALERT_VIEW_DEG,
  MAX_RELAX_VIEW_DEG: MAX_RELAX_VIEW_DEG,
  MIN_SECONDS: MIN_SECONDS,
  TARGET_SCORE: TARGET_SCORE,
  VK_BODY: VK_BODY,

  // 工具
  clamp: clamp,
  round1: round1,
  landmark: landmark,
  emptyLandmarks: emptyLandmarks,

  // 角度与分级
  equivalentLoad: equivalentLoad,
  classify: classify,
  fromTheta: fromTheta,
  bestSide: bestSide,
  noseThetaToForward: noseThetaToForward,
  measureDetail: measureDetail,
  measure: measure,
  estimateViewAngleDeg: estimateViewAngleDeg,
  analyze: analyze,

  // 坐姿分
  scoreOf: scoreOf,
  scoreGrade: scoreGrade,
  scoreGradeKey: scoreGradeKey,

  // 时间序列 / 状态机
  JitterTracker: JitterTracker,
  MedianWindow: MedianWindow,
  PostureStateMachine: PostureStateMachine,

  // VisionKit 适配
  fromVKBody: fromVKBody,
  analyzeVKBody: analyzeVKBody,
};

if (typeof module !== 'undefined' && module.exports) {
  module.exports = PostureAPI; // Node / 小程序
}
if (typeof window !== 'undefined') {
  window.Posture = PostureAPI; // 浏览器（网页版）
}
