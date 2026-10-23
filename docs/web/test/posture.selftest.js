/**
 * test/posture.selftest.js
 * ----------------------------------------------------------------------------
 * js/posture.js 的自测：直接 `node test/posture.selftest.js` 跑（在 publish/web 下）。
 *
 * 目的不是"覆盖率"，而是证明**Kotlin 算法确实被逐条翻译过来了**：
 * 每个断言都手算了期望值，并在注释里写出对应的 Kotlin 行为。
 * 这份测试与小程序版同一份（只有 require 路径不同），保证网页版复用算法没有走样。
 * ----------------------------------------------------------------------------
 */

'use strict';

const P = require('../js/posture.js');

let pass = 0;
let fail = 0;
const failures = [];

function eq(name, actual, expected, tol) {
  let ok;
  if (typeof expected === 'number' && typeof tol === 'number') {
    ok = typeof actual === 'number' && Math.abs(actual - expected) <= tol;
  } else {
    ok = actual === expected;
  }
  if (ok) {
    pass++;
    console.log('  PASS  ' + name + '  => ' + fmt(actual));
  } else {
    fail++;
    failures.push(name + '  expected ' + fmt(expected) + ' got ' + fmt(actual));
    console.log('  FAIL  ' + name + '  expected ' + fmt(expected) + ' got ' + fmt(actual));
  }
}

function fmt(v) {
  if (typeof v === 'number') return Number.isInteger(v) ? String(v) : v.toFixed(4);
  if (typeof v === 'object') return JSON.stringify(v);
  return String(v);
}

function section(title) {
  console.log('\n=== ' + title + ' ===');
}

// 常用测试点：正视侧身（耳在肩上方偏前）
function makeLandmarks(opts) {
  const o = opts || {};
  const lms = P.emptyLandmarks();
  lms[0] = P.landmark(o.noseX === undefined ? 0.6 : o.noseX, o.noseY === undefined ? 0.38 : o.noseY, 1);
  lms[7] = P.landmark(o.earLx === undefined ? 0.55 : o.earLx, o.earLy === undefined ? 0.4 : o.earLy, 1);
  lms[8] = P.landmark(o.earRx === undefined ? 0.45 : o.earRx, o.earRy === undefined ? 0.4 : o.earRy, 1);
  lms[11] = P.landmark(o.shLx === undefined ? 0.5 : o.shLx, o.shLy === undefined ? 0.6 : o.shLy, 1);
  lms[12] = P.landmark(o.shRx === undefined ? 0.4 : o.shRx, o.shRy === undefined ? 0.6 : o.shRy, 1);
  return lms;
}

// ─────────────────────── 1. 等效负荷（Hansraj 表） ───────────────────────
section('equivalentLoad —— PostureAnalyzer.equivalentLoad');
eq('θ=0  → 5kg（表首项）', P.equivalentLoad(0), 5);
eq('θ=15 → 12kg', P.equivalentLoad(15), 12);
eq('θ=30 → 18kg', P.equivalentLoad(30), 18);
eq('θ=45 → 22kg', P.equivalentLoad(45), 22);
eq('θ=60 → 27kg', P.equivalentLoad(60), 27);
eq('θ=75 → 27kg（超出表尾，截断）', P.equivalentLoad(75), 27);
eq('θ=-10 → 5kg（负角按 0 截断）', P.equivalentLoad(-10), 5);
eq('θ=7.5 → 8.5kg（0~15 线性插值）', P.equivalentLoad(7.5), 8.5, 1e-9);

// ─────────────────────── 2. 三级判定 ───────────────────────
section('classify —— PostureAnalyzer.classify');
eq('14.9 → SAFE', P.classify(14.9).level, P.LEVEL.SAFE);
eq('15.0 → WARN（边界）', P.classify(15).level, P.LEVEL.WARN);
eq('29.9 → WARN', P.classify(29.9).level, P.LEVEL.WARN);
eq('30.0 → DANGER（边界）', P.classify(30).level, P.LEVEL.DANGER);
eq('WARN 文案', P.classify(20).desc, '轻度头前伸');

// ─────────────────────── 3. 单帧几何 → 头前倾角 ───────────────────────
section('measureDetail —— PostureAnalyzer.measureDetail');
const W = 720;
const H = 1280;

// 手算：dy=(0.60-0.40)*1280=256；dx=(0.55-0.50)*720=36
// cva=atan2(256,36)=81.9865° → θ=8.0135°
const d1 = P.measureDetail(makeLandmarks(), W, H, false);
eq('检出结果非空', !!d1, true);
eq('θ≈8.0135°（手算）', d1 && d1.theta, 8.0135, 0.01);
eq('CVA≈81.99°', d1 && d1.cvaDeg, 81.9865, 0.01);
// 左右对称的默认点位上，两侧 slant 都是 0.25，浮点误差决定选哪侧（Kotlin 用 Float 同理），
// 这里只断言"选出了一侧且角度正确"，不断言具体左右。
eq('选出了一侧（nearLeft 为布尔）', typeof (d1 && d1.nearLeft), 'boolean');
eq('未走鼻-肩降级', d1 && d1.viaNose, false);

// 朝向归一化：同一姿势、人朝左（鼻子在双耳中线左侧）
// dx 原始为负，归一化后仍得 +36 → θ 相同
const d2 = P.measureDetail(
  makeLandmarks({ noseX: 0.4, earLx: 0.45, earRx: 0.55, shLx: 0.5, shRx: 0.6 }),
  W,
  H,
  false
);
eq('人朝左时 θ 与朝右一致（朝向归一化生效）', d2 && d2.theta, 8.0135, 0.01);

// 肩在耳上方 → 坏帧，丢弃
const d3 = P.measureDetail(makeLandmarks({ shLy: 0.3, shRy: 0.3 }), W, H, false);
eq('肩在耳上方 → 返回 null（坏帧丢弃）', d3, null);

// θ 超出物理合理区间（-15~80）→ 丢弃
// ear 远在肩左侧：dxForward 为负 → cva>90 → θ 很负
const d4 = P.measureDetail(
  makeLandmarks({ earLx: 0.1, earRx: 0.05, noseX: 0.6, shLx: 0.5, shRx: 0.4 }),
  W,
  H,
  false
);
eq('θ 超出 -15~80 → 返回 null（误检丢弃）', d4, null);

// 可见度不足 → 直接放弃该帧
const lowVis = makeLandmarks();
lowVis[7].visibility = 0.4;
eq('耳点 visibility<0.5 → 返回 null', P.measureDetail(lowVis, W, H, false), null);

// 鼻-肩降级路径（耳机场景）：earJittery=true
// 用"左侧明显更贴合"的点位，确保选中左肩 (0.50,0.60)：
//   slant(左)=|0.55-0.50|/0.20=0.25，slant(右)=|0.50-0.30|/0.20=1.00 → 选左
// 手算：dyN=(0.60-0.38)*1280=281.6；dxN=(0.60-0.50)*720=72
// cvaNose=atan2(281.6,72)=75.66° → raw=14.34° → θ=14.34-13=1.34°
const d5 = P.measureDetail(
  makeLandmarks({ earLx: 0.55, shLx: 0.5, earRx: 0.5, shRx: 0.3 }),
  W,
  H,
  true
);
eq('耳点抖动 → 走鼻-肩降级', d5 && d5.viaNose, true);
eq('降级 θ≈1.34°（含 -13° 经验偏移）', d5 && d5.theta, 1.34, 0.02);

// ─────────────────────── 4. 选边策略 ───────────────────────
section('bestSide —— 用几何关系选边，不用 visibility');
// 左配对贴合（0.05/0.20=0.25），右配对错位（0.24/0.20=1.2）
const side = P.bestSide(
  makeLandmarks({ earLx: 0.55, earRx: 0.59, shLx: 0.5, shRx: 0.35 })
);
eq('选出几何更贴合的左侧', side && side.isLeft, true);
// 四个点 visibility 都 1.00 时仍然靠几何选出正确一侧（复现 Android 真机 bug 场景）
const allVis = makeLandmarks({ earLx: 0.72, earRx: 0.59, shLx: 0.78, shRx: 0.35 });
[0, 7, 8, 11, 12].forEach((i) => (allVis[i].visibility = 1.0));
const side2 = P.bestSide(allVis);
eq('visibility 全 1.00 时仍选左（不被可见度误导）', side2 && side2.isLeft, true);

// ─────────────────────── 5. 拍摄视角估计 ───────────────────────
section('estimateViewAngleDeg —— PostureAnalyzer.estimateViewAngleDeg');
eq('双耳重合 → 0°（正侧面）', P.estimateViewAngleDeg(makeLandmarks({ earLx: 0.5, earRx: 0.5 })), 0);
eq(
  '鼻居中(0.5)、耳距0.2 → 约 89.9°（正对镜头）',
  P.estimateViewAngleDeg(makeLandmarks({ earLx: 0.4, earRx: 0.6, noseX: 0.5 })),
  89.93,
  0.2
);
eq(
  '鼻偏移0.16、耳距0.2 → 45°（斜 45°）',
  P.estimateViewAngleDeg(makeLandmarks({ earLx: 0.4, earRx: 0.6, noseX: 0.66 })),
  45.0,
  0.01
);

// ─────────────────────── 6. 由 θ 生成完整结果 ───────────────────────
section('fromTheta —— PostureAnalyzer.fromTheta');
const r20 = P.fromTheta(20);
eq('θ=20 → CVA 70', r20.cvaDeg, 70);
eq('θ=20 → 头前倾 20', r20.forwardHeadDeg, 20);
eq('θ=20 → 等效负荷 14.0', r20.equivLoadKg, 14.0);
eq('θ=20 → WARN', r20.level, P.LEVEL.WARN);
const r0 = P.fromTheta(0);
eq('θ=0 → SAFE / 5.0kg', r0.level + '/' + r0.equivLoadKg, 'SAFE/5');
const r30 = P.fromTheta(30);
eq('θ=30 → DANGER / 18.0kg', r30.level + '/' + r30.equivLoadKg, 'DANGER/18');

// ─────────────────────── 7. 坐姿分 ───────────────────────
section('scoreOf / grade —— PostureScore.kt');
eq('全安全 → 100', P.scoreOf(60, 0, 0), 100);
eq('一半安全一半注意 → 75', P.scoreOf(30, 30, 0), 75);
eq('全注意 → 50', P.scoreOf(0, 60, 0), 50);
eq('全危险 → 0', P.scoreOf(0, 0, 60), 0);
eq('样本不足 60 秒 → null', P.scoreOf(10, 10, 10), null);
eq('自定义 minSeconds=10 时给分', P.scoreOf(5, 5, 0, 10), 75);
eq('grade(90)=很棒', P.scoreGrade(90), '很棒');
eq('grade(75)=良好', P.scoreGrade(75), '良好');
eq('grade(60)=一般', P.scoreGrade(60), '一般');
eq('grade(59)=需注意', P.scoreGrade(59), '需注意');
eq('gradeKey(80)=safe', P.scoreGradeKey(80), 'safe');

// ─────────────────────── 8. 时间窗中位数 ───────────────────────
section('MedianWindow —— PoseMonitor.medianTheta（10 秒窗）');
const mw = new P.MedianWindow(10000);
eq('首样本即中位数', mw.push(30, 0), 30);
// Kotlin 是 sorted[size / 2]：偶数个样本取**上**中位数，所以 [30,100] → 100
eq('第二样本 100 → 中位数 100（偶数取上中位数）', mw.push(100, 1000), 100);
eq('第三样本 12 → 中位数 30', mw.push(12, 2000), 30);
const mw2 = new P.MedianWindow(10000);
mw2.push(100, 0);
eq('超出 10 秒窗的旧样本被淘汰', mw2.push(10, 11000), 10);

// ─────────────────────── 9. 耳点抖动跟踪 ───────────────────────
section('JitterTracker —— PoseMonitor.updateJitter');
const jt = new P.JitterTracker();
let last = false;
for (let i = 0; i < 12; i++) {
  const alt = i % 2 === 0 ? 0.05 : -0.05;
  const lms = makeLandmarks({ earLx: 0.5 + alt, earRx: 0.5 + alt, noseX: 0.5 });
  last = jt.update(lms);
}
eq('耳朵逐帧乱跳、鼻子稳定 → earJittery=true', last, true);
const jt2 = new P.JitterTracker();
for (let i = 0; i < 12; i++) {
  jt2.update(makeLandmarks()); // 完全静止
}
eq('画面静止 → earJittery=false', jt2.earJittery, false);

// ─────────────────────── 10. 提醒状态机 ───────────────────────
section('PostureStateMachine —— 连续低头 / 冷却');
const sm = new P.PostureStateMachine({ dangerHoldSeconds: 180, cooldownSeconds: 600 });
eq('t=0 刚进入危险：不提醒', sm.update(0, P.LEVEL.DANGER, true, 1), null);
eq('t=179 未到 180 秒：不提醒', sm.update(179, P.LEVEL.DANGER, true, 1), null);
const ev1 = sm.update(180, P.LEVEL.DANGER, true, 1);
eq('t=180 触发提醒', !!ev1, true);
eq('提醒文案', ev1 && ev1.reason, '持续低头超过 180 秒');
eq('t=181 冷却期内：不提醒', sm.update(181, P.LEVEL.DANGER, true, 1), null);
eq('t=779 仍在冷却（780-180=600 才到期）', sm.update(779, P.LEVEL.DANGER, true, 1), null);
eq('t=780 冷却结束且仍危险：再次提醒', !!sm.update(780, P.LEVEL.DANGER, true, 1), true);

section('PostureStateMachine —— 累计低头（治「每 10 秒抬一下头」）');
const sm2 = new P.PostureStateMachine({
  dangerHoldSeconds: 99999, // 关掉连续判定，只测累计
  cooldownSeconds: 600,
  cumulativeWindowSec: 900,
  cumulativeLimitSec: 300,
});
let cumulativeAlert = null;
for (let i = 0; i < 400 && !cumulativeAlert; i++) {
  const tDanger = i * 2;
  // 注意：提醒可能在 DANGER 这一帧就触发，两个返回值都要检查
  const evDanger = sm2.update(tDanger, P.LEVEL.DANGER, true, 1); // 危险 1 秒
  if (evDanger) {
    cumulativeAlert = evDanger;
    break;
  }
  const tSafe = i * 2 + 1;
  const evSafe = sm2.update(tSafe, P.LEVEL.SAFE, true, 1); // 立刻抬头 1 秒（连续计时清零）
  if (evSafe) cumulativeAlert = evSafe;
}
eq('反复低头累计 300 秒后触发提醒', !!cumulativeAlert, true);
eq(
  '累计提醒文案',
  cumulativeAlert && cumulativeAlert.reason,
  '15 分钟内累计低头 300 秒'
);

section('PostureStateMachine —— 疲劳模型（工作越久越敏感）');
const sm3 = new P.PostureStateMachine({
  dangerHoldSeconds: 180,
  cooldownSeconds: 600,
  fatigueAfterSec: 3600,
  fatigueMinFactor: 0.6,
});
sm3.update(0, P.LEVEL.SAFE, true, 0); // 建立 sessionStart
eq('工作 30 分钟：hold 仍 180 秒', sm3.effectiveHoldSeconds(1800), 180);
eq('工作 90 分钟（刚过疲劳线一半）：hold=180*0.8=144', sm3.effectiveHoldSeconds(5400), 144);
eq('工作 120 分钟（疲劳到顶）：hold=180*0.6=108', sm3.effectiveHoldSeconds(7200), 108);
eq('离席时连续计时清零', sm3.update(7300, P.LEVEL.DANGER, false, 0), null);

// ─────────────────────── 11. VisionKit 23 点适配 ───────────────────────
section('fromVKBody / analyzeVKBody —— VisionKit Body(23点) → 算法');
const vkPoints = [];
for (let i = 0; i < 23; i++) vkPoints.push({ x: 0, y: 0 });
vkPoints[0] = { x: 0.6 * W, y: 0.38 * H }; // 0 鼻
vkPoints[3] = { x: 0.55 * W, y: 0.4 * H }; // 3 耳A
vkPoints[4] = { x: 0.45 * W, y: 0.4 * H }; // 4 耳B
vkPoints[5] = { x: 0.5 * W, y: 0.6 * H }; // 5 肩A
vkPoints[6] = { x: 0.4 * W, y: 0.6 * H }; // 6 肩B

const vk = P.analyzeVKBody(vkPoints, W, H, {});
eq('VK 23 点能算出 θ≈8.01°', vk.theta, 8.0135, 0.01);
eq('VK 结果分级 SAFE', vk.result && vk.result.level, P.LEVEL.SAFE);
eq('VK 结果 CVA≈82.0', vk.result && vk.result.cvaDeg, 82.0);
eq('VK 结果等效负荷≈8.7kg', vk.result && vk.result.equivLoadKg, 8.7);

// 镜像（前置摄像头预览镜像）后角度应保持不变
const vkMirror = P.analyzeVKBody(vkPoints, W, H, { mirrored: true });
eq('镜像后 θ 不变（x→1-x 后仍 ≈8.01°）', vkMirror.theta, 8.0135, 0.01);

// 关键点缺失 → 安全返回 null，不抛异常
eq('全零关键点 → 返回 null（不抛异常）', P.analyzeVKBody([], W, H, {}).result, null);

// ─────────────────────── 汇总 ───────────────────────
console.log('\n──────────────────────────────────────────');
console.log('通过 ' + pass + ' 项，失败 ' + fail + ' 项');
if (fail) {
  console.log('\n失败明细：');
  failures.forEach((f) => console.log('  - ' + f));
  process.exitCode = 1;
} else {
  console.log('结论：js/posture.js 与 Kotlin 算法口径一致。');
}
