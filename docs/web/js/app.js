/* ============================================================================
 * js/app.js — 颈椎守护 · 网页版主逻辑
 * ----------------------------------------------------------------------------
 * 浏览器原生 ES2020（无构建、无框架），职责：
 *   1. getUserMedia 拿摄像头 / 演示模式的合成关键点
 *   2. MediaPipe PoseLandmarker（浏览器 WASM/GPU）逐帧推理
 *   3. 复用 js/posture.js 的 Kotlin 翻译算法（CVA / 头前倾 / 等效负荷 / 状态机）
 *   4. canvas 标注、三级判定、提醒（页面 + 声音 + 震动）
 *   5. localStorage 统计（今日 + 近 7 天）、深/浅主题、设置
 *
 * 现实约束（README 有更详细的说明）：
 *   · file:// 打开拿不到摄像头（浏览器安全上下文限制），必须 localhost 或 https
 *   · 手机用局域网 http://192.168.x.x 同样拿不到 → https / adb reverse / Chrome 启动参数
 *   · 标签页切到后台会暂停检测（浏览器节流），回来会重置状态机
 * ============================================================================ */
(function () {
  'use strict';

  // ─────────────────────────── 依赖自检 ───────────────────────────
  var P = window.Posture;
  if (!P) {
    document.body.innerHTML =
      '<div style="padding:40px;font:16px/1.7 sans-serif;color:#fff;background:#0f1216;min-height:100vh">' +
      '<h1>缺少 js/posture.js</h1><p>请从项目根目录启动静态服务器，不要只复制 index.html。</p></div>';
    return;
  }

  var $ = function (id) { return document.getElementById(id); };

  // ─────────────────────────── 常量 ───────────────────────────
  var APP_VERSION = '1.0.0';
  var LS = {
    theme: 'cervicalguard.web.theme.v1',
    settings: 'cervicalguard.web.settings.v1',
    stats: 'cervicalguard.web.stats.v1',
    privacy: 'cervicalguard.web.privacyAck.v1',
  };
  var TASKS_VISION_VERSION = '0.10.20';
  // 注意：动态 import() 的相对路径按「脚本所在目录」解析（/js/assets/...），
  // 所以这里用 document.baseURI 拼出页面目录下的绝对地址。
  var LOCAL_MODULE_URL = new URL('assets/vendor/vision_bundle.js', document.baseURI).href;
  var MODULE_URLS = [
    // 完全离线部署：把 CDN 的 vision_bundle.mjs 另存为 assets/vendor/vision_bundle.js
    // 放在第一位；文件不存在时会自动回落到下面的 CDN。
    LOCAL_MODULE_URL,
    'https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@' + TASKS_VISION_VERSION + '/vision_bundle.mjs',
    'https://unpkg.com/@mediapipe/tasks-vision@' + TASKS_VISION_VERSION + '/vision_bundle.mjs',
    'https://fastly.jsdelivr.net/npm/@mediapipe/tasks-vision@' + TASKS_VISION_VERSION + '/vision_bundle.mjs',
  ];
  var WASM_BASES = [
    'https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@' + TASKS_VISION_VERSION + '/wasm',
    'https://unpkg.com/@mediapipe/tasks-vision@' + TASKS_VISION_VERSION + '/wasm',
    'https://fastly.jsdelivr.net/npm/@mediapipe/tasks-vision@' + TASKS_VISION_VERSION + '/wasm',
  ];
  var LOCAL_WASM_BASE = 'assets/wasm';
  var LOCAL_MODEL = 'assets/pose_landmarker_lite.task';
  var CDN_MODEL =
    'https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task';

  var DEFAULT_SETTINGS = {
    warnDeg: 15,
    dangerDeg: 30,
    holdSec: 180,
    maxFps: 15,
    sound: true,
    vibrate: true,
  };
  var MAX_ALERT_VIEW = P.MAX_ALERT_VIEW_DEG; // 50°
  var SESSION_RESET_HIDDEN_MS = 30000;
  var DEMO_W = 720;
  var DEMO_H = 960;
  var WEEK_SCALE_MIN_SEC = 600; // 柱状图纵轴下限，避免几十秒数据撑满

  var STATUS_TEXT = {
    none: '未检出',
    safe: '坐姿端正',
    warn: '轻度头前伸',
    danger: '明显头前伸',
    unreliable: '角度不可信',
    loading: '启动中…',
    error: '需要处理',
  };
  var LEVEL_TEXT_SHORT = { SAFE: '安全', WARN: '注意', DANGER: '危险' };

  // ─────────────────────────── DOM ───────────────────────────
  var el = {
    statusPill: $('statusPill'),
    statusText: $('statusText'),
    themeBtn: $('themeBtn'),
    themeIcon: $('themeIcon'),
    settingsBtn: $('settingsBtn'),
    privacyBtn: $('privacyBtn'),
    footerPrivacy: $('footerPrivacy'),

    stage: $('stage'),
    video: $('video'),
    overlay: $('overlay'),
    stageEmpty: $('stageEmpty'),
    secureHint: $('secureHint'),
    startBtn: $('startBtn'),
    demoBtn: $('demoBtn'),
    demoBadge: $('demoBadge'),
    demoToggleBtn: $('demoToggleBtn'),
    switchCamBtn: $('switchCamBtn'),
    stopBtn: $('stopBtn'),
    camState: $('camState'),
    viewChip: $('viewChip'),
    fpsChip: $('fpsChip'),
    roCva: $('roCva'),
    roTheta: $('roTheta'),
    roView: $('roView'),

    cvaCard: $('cvaCard'),
    thetaCard: $('thetaCard'),
    loadCard: $('loadCard'),
    cvaValue: $('cvaValue'),
    thetaValue: $('thetaValue'),
    loadValue: $('loadValue'),
    cvaHint: $('cvaHint'),
    thetaHint: $('thetaHint'),
    loadHint: $('loadHint'),

    todayDate: $('todayDate'),
    todayScore: $('todayScore'),
    todayGrade: $('todayGrade'),
    todaySafe: $('todaySafe'),
    todayWarn: $('todayWarn'),
    todayDanger: $('todayDanger'),
    todayTotal: $('todayTotal'),
    todayUnreliable: $('todayUnreliable'),
    todayAlerts: $('todayAlerts'),

    distSafe: $('distSafe'),
    distWarn: $('distWarn'),
    distDanger: $('distDanger'),
    distSafeText: $('distSafeText'),
    distWarnText: $('distWarnText'),
    distDangerText: $('distDangerText'),
    distEmpty: $('distEmpty'),
    weekChart: $('weekChart'),

    alertBanner: $('alertBanner'),
    alertReason: $('alertReason'),
    alertOk: $('alertOk'),
    toast: $('toast'),

    privacyModal: $('privacyModal'),
    privacyOk: $('privacyOk'),
    settingsModal: $('settingsModal'),
    settingsClose: $('settingsClose'),
    warnRange: $('warnRange'),
    dangerRange: $('dangerRange'),
    holdRange: $('holdRange'),
    fpsRange: $('fpsRange'),
    warnLabel: $('warnLabel'),
    dangerLabel: $('dangerLabel'),
    holdLabel: $('holdLabel'),
    fpsLabel: $('fpsLabel'),
    soundToggle: $('soundToggle'),
    vibrateToggle: $('vibrateToggle'),
    clearToday: $('clearToday'),
    clearAll: $('clearAll'),
    runInfo: $('runInfo'),
  };

  // ─────────────────────────── 工具 ───────────────────────────
  function clamp(v, lo, hi) { return Math.min(hi, Math.max(lo, v)); }
  function pad2(n) { return (n < 10 ? '0' : '') + n; }
  function dayKey(d) {
    return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate());
  }
  function todayKey() { return dayKey(new Date()); }
  function dayAt(offset) {
    var d = new Date();
    d.setHours(12, 0, 0, 0); // 中午避免夏令时/跨天边界
    d.setDate(d.getDate() + offset);
    return d;
  }
  function weekdayCN(d) {
    var names = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'];
    return names[d.getDay()];
  }
  function dateCN(d) {
    return (d.getMonth() + 1) + '月' + d.getDate() + '日 ' + weekdayCN(d);
  }
  function fmtDuration(sec, opts) {
    var compact = opts && opts.compact;
    var s = Math.max(0, Math.round(sec || 0));
    var h = Math.floor(s / 3600);
    var m = Math.floor((s % 3600) / 60);
    var r = s % 60;
    if (compact && s > 0 && s < 60) return s + ' 秒';
    if (h > 0) return h + ' 小时' + (compact && m === 0 ? '' : ' ' + m + ' 分');
    if (m > 0) return m + ' 分' + (r && !compact ? ' ' + r + ' 秒' : '');
    return s + ' 秒';
  }
  function shortUrl(u) {
    if (!u) return '--';
    try {
      var p = new URL(u, location.href);
      var file = p.pathname.split('/').pop() || p.host;
      return (p.origin === location.origin ? '本地 ' : p.host + ' · ') + decodeURIComponent(file);
    } catch (e) {
      return u.slice(0, 60);
    }
  }
  function withTimeout(promise, ms, label) {
    return new Promise(function (resolve, reject) {
      var timer = setTimeout(function () {
        reject(new Error((label || '操作') + '超时（' + Math.round(ms / 1000) + ' 秒）'));
      }, ms);
      promise.then(
        function (v) { clearTimeout(timer); resolve(v); },
        function (e) { clearTimeout(timer); reject(e); }
      );
    });
  }
  function safeGet(key) {
    try { return localStorage.getItem(key); } catch (e) { return null; }
  }
  function safeSet(key, val) {
    try { localStorage.setItem(key, val); } catch (e) { /* 隐私模式可能禁止 */ }
  }
  function safeRemove(key) {
    try { localStorage.removeItem(key); } catch (e) { /* ignore */ }
  }

  // ─────────────────────────── 设置 ───────────────────────────
  var settings = (function () {
    var s = Object.assign({}, DEFAULT_SETTINGS);
    try {
      var raw = safeGet(LS.settings);
      if (raw) {
        var o = JSON.parse(raw);
        if (o && typeof o === 'object') {
          Object.keys(DEFAULT_SETTINGS).forEach(function (k) {
            if (typeof o[k] === typeof DEFAULT_SETTINGS[k]) s[k] = o[k];
          });
        }
      }
    } catch (e) { /* 坏数据回落到默认 */ }
    s.warnDeg = clamp(s.warnDeg, 5, 45);
    s.dangerDeg = clamp(s.dangerDeg, s.warnDeg + 1, 60);
    s.holdSec = clamp(s.holdSec, 30, 600);
    s.maxFps = clamp(s.maxFps, 5, 30);
    return s;
  })();

  function saveSettings() {
    safeSet(LS.settings, JSON.stringify(settings));
  }

  // ─────────────────────────── 统计（localStorage） ───────────────────────────
  var stats = (function () {
    try {
      var raw = safeGet(LS.stats);
      if (raw) {
        var o = JSON.parse(raw);
        if (o && o.days && typeof o.days === 'object') return o;
      }
    } catch (e) { /* 坏数据重建 */ }
    return { v: 1, days: {} };
  })();
  var pending = { safe: 0, warn: 0, danger: 0, unreliable: 0, alerts: 0 };

  function emptyTotals() {
    return { safe: 0, warn: 0, danger: 0, unreliable: 0, alerts: 0 };
  }
  function ensureDay(key) {
    var d = stats.days[key];
    if (!d) {
      d = stats.days[key] = emptyTotals();
    }
    ['safe', 'warn', 'danger', 'unreliable', 'alerts'].forEach(function (k) {
      if (typeof d[k] !== 'number' || !isFinite(d[k])) d[k] = 0;
    });
    return d;
  }

  function flushStats() {
    var total = pending.safe + pending.warn + pending.danger + pending.unreliable;
    if (total <= 0 && pending.alerts === 0) return;
    var d = ensureDay(todayKey());
    d.safe += pending.safe;
    d.warn += pending.warn;
    d.danger += pending.danger;
    d.unreliable += pending.unreliable;
    d.alerts += pending.alerts;
    pending = emptyTotals();
    // 只保留最近 60 天，避免 localStorage 无限膨胀
    var keys = Object.keys(stats.days).sort();
    while (keys.length > 60) delete stats.days[keys.shift()];
    safeSet(LS.stats, JSON.stringify(stats));
  }

  function currentTotals() {
    if (state.mode === 'demo' && state.demo) return state.demo;
    var base = stats.days[todayKey()] || emptyTotals();
    return {
      safe: base.safe + pending.safe,
      warn: base.warn + pending.warn,
      danger: base.danger + pending.danger,
      unreliable: base.unreliable + pending.unreliable,
      alerts: base.alerts + pending.alerts,
    };
  }

  function accrue(kind, dt) {
    if (!(dt > 0)) return;
    var bucket = state.mode === 'demo' && state.demo ? state.demo : pending;
    if (kind === 'unreliable') bucket.unreliable += dt;
    else if (kind === 'safe') bucket.safe += dt;
    else if (kind === 'warn') bucket.warn += dt;
    else if (kind === 'danger') bucket.danger += dt;
  }

  function accrueAlert() {
    if (state.mode === 'demo' && state.demo) state.demo.alerts += 1;
    else pending.alerts += 1;
  }

  // ─────────────────────────── 主题 ───────────────────────────
  var THEME_LABEL = { auto: '跟随系统', light: '浅色', dark: '深色' };
  var THEME_ORDER = ['auto', 'light', 'dark'];
  var prefersDark = window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null;
  var themePref = safeGet(LS.theme);
  if (THEME_ORDER.indexOf(themePref) < 0) themePref = 'auto';

  function themeIsDark(pref) {
    return pref === 'dark' || (pref === 'auto' && prefersDark && prefersDark.matches);
  }

  function applyTheme(pref, persist) {
    if (THEME_ORDER.indexOf(pref) < 0) pref = 'auto';
    if (persist !== false) {
      themePref = pref;
      safeSet(LS.theme, pref);
    }
    var dark = themeIsDark(pref);
    document.documentElement.setAttribute('data-theme', dark ? 'dark' : 'light');
    if (el.themeIcon) el.themeIcon.textContent = dark ? '🌙' : '☀️';
    if (el.themeBtn) {
      el.themeBtn.title = '当前主题：' + THEME_LABEL[pref] + '（点击切换）';
      el.themeBtn.setAttribute('aria-label', '当前主题：' + THEME_LABEL[pref] + '，点击切换');
    }
    var meta = document.querySelector('meta[name="theme-color"]');
    if (meta) meta.setAttribute('content', dark ? '#0F1216' : '#F2F5F9');
  }

  // ─────────────────────────── 运行状态 ───────────────────────────
  var state = {
    mode: 'idle', // idle | camera | demo
    running: false,
    starting: false, // 摄像头 + 模型正在启动（失败后模型可能还在后台下载，别覆盖错误提示）
    stream: null,
    facing: 'user',
    mirror: true,
    module: null, // @mediapipe/tasks-vision 模块
    landmarker: null,
    modelPromise: null,
    backend: { module: null, wasm: null, model: null, delegate: null, error: null },

    raf: 0,
    sessionStartMs: 0,
    lastDetectAt: 0,
    lastFrameAt: 0,
    sessionFrames: 0,
    hiddenAt: 0,
    wakeLock: null,

    jitter: new P.JitterTracker(),
    median: new P.MedianWindow(P.SMOOTH_WINDOW_MS),
    sm: null,

    lastLandmarks: null,
    lastDetail: null,
    lastResult: null,
    lastTheta: null,
    lastViewAngle: null,
    frameW: 0,
    frameH: 0,

    demo: null,
    demoThetaFixed: null,
    demoPhase: 0,

    fps: { frames: 0, since: 0, value: 0, latency: 0 },
    alertTimer: 0,
    toastTimer: 0,
    pendingAfterPrivacy: null,
    audioCtx: null,
  };

  function makeStateMachine() {
    return new P.PostureStateMachine({
      dangerHoldSeconds: settings.holdSec,
      cooldownSeconds: 600,
      cumulativeWindowSec: 900,
      cumulativeLimitSec: 300,
      fatigueAfterSec: 3600,
      fatigueMinFactor: 0.6,
    });
  }
  state.sm = makeStateMachine();

  // ─────────────────────────── 状态胶囊 / 读数 ───────────────────────────
  function setStatus(name) {
    if (!STATUS_TEXT[name]) name = 'none';
    el.statusPill.setAttribute('data-state', name);
    el.statusText.textContent = STATUS_TEXT[name];
  }

  function hintFor(kind) {
    if (kind === 'cva') return '安全 ≥ ' + (90 - settings.warnDeg) + '°';
    if (kind === 'theta') return '安全 < ' + settings.warnDeg + '°';
    if (kind === 'load') return '安全 < ' + P.round1(P.equivalentLoad(settings.warnDeg)) + ' kg';
    return '';
  }

  function resetReadingHints() {
    el.cvaHint.textContent = hintFor('cva');
    el.thetaHint.textContent = hintFor('theta');
    el.loadHint.textContent = hintFor('load');
  }

  function renderReadings(result, viewBad) {
    var cards = [el.cvaCard, el.thetaCard, el.loadCard];
    if (!result) {
      cards.forEach(function (c) { c.setAttribute('data-state', 'none'); });
      el.cvaValue.textContent = '--';
      el.thetaValue.textContent = '--';
      el.loadValue.textContent = '--';
      resetReadingHints();
      return;
    }
    if (viewBad) {
      cards.forEach(function (c) { c.setAttribute('data-state', 'unreliable'); });
      el.cvaValue.textContent = '--';
      el.thetaValue.textContent = '--';
      el.loadValue.textContent = '--';
      el.cvaHint.textContent = '请侧转身子，让镜头看到侧面';
      el.thetaHint.textContent = '视角 > 50°，数值不可信';
      el.loadHint.textContent = '此时不计入统计';
      return;
    }
    var level = String(result.level).toLowerCase();
    cards.forEach(function (c) { c.setAttribute('data-state', level); });
    el.cvaValue.textContent = String(Math.round(result.cvaDeg));
    el.thetaValue.textContent = result.forwardHeadDeg.toFixed(1);
    el.loadValue.textContent = result.equivLoadKg.toFixed(1);
    resetReadingHints();
  }

  function updateReadout(detail, viewBad, theta, viewAngle) {
    if (!detail) {
      el.roCva.textContent = 'CVA --';
      el.roCva.className = 'ro-item';
      el.roTheta.textContent = 'θ --';
      el.roTheta.className = 'ro-item';
      el.roView.textContent = '视角 --';
      el.roView.className = 'ro-item';
      return;
    }
    if (viewBad) {
      el.roCva.textContent = 'CVA --';
      el.roCva.className = 'ro-item danger';
      el.roTheta.textContent = 'θ --';
      el.roTheta.className = 'ro-item danger';
      el.roView.textContent = '请侧身';
      el.roView.className = 'ro-item danger';
      return;
    }
    var lvl = theta < settings.warnDeg ? 'safe' : theta < settings.dangerDeg ? 'warn' : 'danger';
    el.roCva.textContent = 'CVA ' + Math.round(90 - theta) + '°';
    el.roCva.className = 'ro-item accent';
    el.roTheta.textContent = 'θ ' + (theta >= 0 ? '+' : '') + theta.toFixed(1) + '°';
    el.roTheta.className = 'ro-item ' + lvl;
    if (detail.viaNose) {
      el.roView.textContent = '鼻测 ' + (viewAngle == null ? '--' : Math.round(viewAngle) + '°');
      el.roView.className = 'ro-item warn';
    } else if (viewAngle == null) {
      el.roView.textContent = '视角 --';
      el.roView.className = 'ro-item';
    } else {
      el.roView.textContent = '视角 ' + Math.round(viewAngle) + '°';
      el.roView.className = 'ro-item ' + (viewAngle <= 30 ? 'safe' : viewAngle <= MAX_ALERT_VIEW ? 'warn' : 'danger');
    }
  }

  function updateViewChip(viewAngle, detail) {
    if (viewAngle == null) {
      el.viewChip.textContent = '视角 --';
      el.viewChip.className = 'chip';
    } else {
      el.viewChip.textContent = (detail && detail.viaNose ? '鼻测 ' : '视角 ') + Math.round(viewAngle) + '°';
      el.viewChip.className = 'chip' + (viewAngle > MAX_ALERT_VIEW ? ' bad' : '');
    }
  }

  // ─────────────────────────── 统计渲染 ───────────────────────────
  function renderStats() {
    var t = currentTotals();
    var safe = Math.max(0, t.safe);
    var warn = Math.max(0, t.warn);
    var danger = Math.max(0, t.danger);
    var unreliable = Math.max(0, t.unreliable);
    var scored = safe + warn + danger;

    var score = P.scoreOf(safe, warn, danger);
    if (score === null) {
      el.todayScore.textContent = '--';
      el.todayScore.removeAttribute('data-grade');
      el.todayGrade.textContent = '数据不足 60 秒';
    } else {
      el.todayScore.textContent = String(score);
      el.todayScore.setAttribute('data-grade', P.scoreGradeKey(score));
      el.todayGrade.textContent = P.scoreGrade(score);
    }

    el.todaySafe.textContent = fmtDuration(safe);
    el.todayWarn.textContent = fmtDuration(warn);
    el.todayDanger.textContent = fmtDuration(danger);
    el.todayTotal.textContent = '守护 ' + fmtDuration(safe + warn + danger + unreliable, { compact: true });
    el.todayUnreliable.textContent = '角度不可信 ' + fmtDuration(unreliable);
    el.todayAlerts.textContent = '提醒 ' + Math.round(t.alerts) + ' 次';
    el.todayDate.textContent = state.mode === 'demo' ? '演示数据' : dateCN(new Date());

    // 三级分布条
    var pct = function (v) { return scored > 0 ? (v / scored) * 100 : 0; };
    var ps = pct(safe), pw = pct(warn), pd = pct(danger);
    el.distSafe.style.width = ps.toFixed(1) + '%';
    el.distWarn.style.width = pw.toFixed(1) + '%';
    el.distDanger.style.width = pd.toFixed(1) + '%';
    el.distSafeText.textContent = scored > 0 ? Math.round(ps) + '%' : '--';
    el.distWarnText.textContent = scored > 0 ? Math.round(pw) + '%' : '--';
    el.distDangerText.textContent = scored > 0 ? Math.round(pd) + '%' : '--';
    el.distEmpty.style.display = scored > 0 ? 'none' : '';

    renderWeekChart(safe, warn, danger, unreliable);
  }

  function renderWeekChart(todaySafe, todayWarn, todayDanger, todayUnreliable) {
    var days = [];
    for (var i = -6; i <= 0; i++) {
      var d = dayAt(i);
      var key = dayKey(d);
      var row;
      if (i === 0) {
        row = { safe: todaySafe, warn: todayWarn, danger: todayDanger, unreliable: todayUnreliable };
      } else {
        row = stats.days[key] || emptyTotals();
      }
      days.push({ key: key, date: d, row: row, isToday: i === 0 });
    }
    var maxTotal = WEEK_SCALE_MIN_SEC;
    days.forEach(function (d) {
      var total = d.row.safe + d.row.warn + d.row.danger + d.row.unreliable;
      if (total > maxTotal) maxTotal = total;
    });

    var html = '';
    days.forEach(function (d) {
      var r = d.row;
      var hs = clamp((r.safe / maxTotal) * 100, 0, 100);
      var hw = clamp((r.warn / maxTotal) * 100, 0, 100);
      var hd = clamp((r.danger / maxTotal) * 100, 0, 100);
      var hu = clamp((r.unreliable / maxTotal) * 100, 0, 100);
      // 极小值给 1.5% 的最小可见高度，否则 5 秒的柱子会完全消失
      var vis = function (h) { return h > 0 ? Math.max(h, 1.2) : 0; };
      var bottom = 0;
      var segments = '';
      // 从下往上：安全 → 注意 → 危险 → 不可信（灰色，不计分）
      if (hs > 0) {
        segments += '<span class="wseg safe" style="bottom:' + bottom + '%;height:' + vis(hs) + '%"></span>';
        bottom += hs;
      }
      if (hw > 0) {
        segments += '<span class="wseg warn" style="bottom:' + bottom + '%;height:' + vis(hw) + '%"></span>';
        bottom += hw;
      }
      if (hd > 0) {
        segments += '<span class="wseg danger" style="bottom:' + bottom + '%;height:' + vis(hd) + '%"></span>';
        bottom += hd;
      }
      if (hu > 0) {
        segments += '<span class="wseg unreliable" style="bottom:' + bottom + '%;height:' + vis(hu) + '%"></span>';
      }
      var total = r.safe + r.warn + r.danger + r.unreliable;
      var title = d.key + ' · 安全 ' + fmtDuration(r.safe) + ' · 注意 ' + fmtDuration(r.warn) +
        ' · 危险 ' + fmtDuration(r.danger) + (r.unreliable > 0 ? ' · 角度不可信 ' + fmtDuration(r.unreliable) : '');
      html += '<div class="week-col' + (d.isToday ? ' today' : '') + '" title="' + title + '">' +
        '<div class="week-stack">' + segments +
        (total <= 0 ? '<span class="week-zero"></span>' : '') +
        '</div><span class="week-label">' + (d.isToday ? '今天' : weekdayCN(d.date)) + '</span></div>';
    });
    el.weekChart.innerHTML = html;
  }

  // ─────────────────────────── Toast / 提醒 ───────────────────────────
  function toast(msg, kind, ms) {
    el.toast.textContent = msg;
    el.toast.setAttribute('data-kind', kind || 'info');
    el.toast.hidden = false;
    clearTimeout(state.toastTimer);
    state.toastTimer = setTimeout(function () { el.toast.hidden = true; }, ms || 3500);
  }

  function triggerAlert(reason) {
    el.alertReason.textContent = reason;
    el.alertBanner.hidden = false;
    clearTimeout(state.alertTimer);
    state.alertTimer = setTimeout(function () { el.alertBanner.hidden = true; }, 20000);
    if (settings.sound) beep();
    if (settings.vibrate && navigator.vibrate) {
      try { navigator.vibrate([180, 90, 180]); } catch (e) { /* ignore */ }
    }
  }

  // 提示音：WebAudio 双音，不依赖任何音频文件
  function ensureAudio() {
    if (state.audioCtx) return;
    var Ctx = window.AudioContext || window.webkitAudioContext;
    if (!Ctx) return;
    try { state.audioCtx = new Ctx(); } catch (e) { state.audioCtx = null; }
  }
  function beep() {
    var ctx = state.audioCtx;
    if (!ctx) return;
    try {
      if (ctx.state === 'suspended') ctx.resume();
      var t0 = ctx.currentTime;
      [[880, 0.00, 0.16], [660, 0.18, 0.26]].forEach(function (n) {
        var osc = ctx.createOscillator();
        var gain = ctx.createGain();
        osc.type = 'sine';
        osc.frequency.value = n[0];
        gain.gain.setValueAtTime(0.0001, t0 + n[1]);
        gain.gain.exponentialRampToValueAtTime(0.18, t0 + n[1] + 0.02);
        gain.gain.exponentialRampToValueAtTime(0.0001, t0 + n[1] + n[2]);
        osc.connect(gain);
        gain.connect(ctx.destination);
        osc.start(t0 + n[1]);
        osc.stop(t0 + n[1] + n[2] + 0.03);
      });
    } catch (e) { /* 音频不可用时静默 */ }
  }

  // ─────────────────────────── 安全上下文 ───────────────────────────
  function secureHintText() {
    if (location.protocol === 'file:') {
      return '⚠ 你现在是直接双击打开的（file://），浏览器禁止网页访问摄像头。请运行目录里的 serve.ps1，然后用 http://localhost:8000 打开。';
    }
    if (!window.isSecureContext) {
      return '⚠ 当前地址不是安全上下文（http:// 局域网 IP）。浏览器会拒绝摄像头。请改用 https、本机 http://localhost，或见 README「手机上怎么用」。';
    }
    return '';
  }

  function cameraErrorText(err) {
    if (!err) return '启动失败，请重试。';
    var name = err.name || '';
    if (err.code === 'NO_API') {
      return '这个浏览器不支持 getUserMedia（摄像头接口）。请用较新的 Chrome / Edge / Safari。';
    }
    if (err.code === 'INSECURE' || name === 'SecurityError') {
      return secureHintText() || '浏览器判定当前不是安全上下文，拒绝摄像头。';
    }
    if (name === 'NotAllowedError' || name === 'PermissionDeniedError') {
      return '摄像头权限被拒绝。点地址栏左侧的相机/锁图标 → 允许摄像头，然后刷新页面重试。';
    }
    if (name === 'NotFoundError' || name === 'DevicesNotFoundError') {
      return '没有找到摄像头。请确认设备有摄像头（外接摄像头要在系统里启用）。';
    }
    if (name === 'NotReadableError' || name === 'TrackStartError') {
      return '摄像头被其它程序占用（会议软件 / 其它标签页）。关掉它们再试。';
    }
    if (name === 'OverconstrainedError') {
      return '摄像头不支持请求的参数，已尝试降级仍失败。刷新页面重试。';
    }
    if (err.code === 'MODEL') {
      return '姿态模型加载失败：' + (err.detail || err.message) +
        '。请检查网络（需要从 CDN 拉 MediaPipe 的 JS/WASM）；完全离线部署见 README「离线部署」。';
    }
    if (err.code === 'TIMEOUT') {
      return err.message + '。CDN 可能不通，可先用「演示」看界面。';
    }
    return (err.message || String(err)) + '（可先用「演示」看界面）';
  }

  // ─────────────────────────── MediaPipe 加载 ───────────────────────────
  function loadModule() {
    var index = 0;
    function tryNext() {
      if (index >= MODULE_URLS.length) {
        var err = new Error('MediaPipe 推理库加载失败');
        err.code = 'MODEL';
        err.detail = '所有 CDN 都不可用';
        return Promise.reject(err);
      }
      var url = MODULE_URLS[index++];
      if (state.starting) {
        setStatus('loading');
        el.camState.textContent = '加载推理库（CDN）…';
      }
      return withTimeout(import(url), 20000, '推理库加载').then(
        function (mod) {
          state.backend.module = url;
          state.module = mod;
          return mod;
        },
        function (e) {
          console.warn('[MediaPipe] 模块加载失败：' + url, e);
          return tryNext();
        }
      );
    }
    return tryNext();
  }

  function localWasmAvailable() {
    return fetch(LOCAL_WASM_BASE + '/vision_wasm_internal.js', { method: 'HEAD', cache: 'no-store' })
      .then(function (r) { return !!r.ok; })
      .catch(function () { return false; });
  }

  function localModelAvailable() {
    return fetch(LOCAL_MODEL, { method: 'HEAD', cache: 'no-store' })
      .then(function (r) { return !!r.ok; })
      .catch(function () { return false; });
  }

  function createLandmarker(mod, wasmBase, modelUrl, delegate) {
    return mod.FilesetResolver.forVisionTasks(wasmBase).then(function (fileset) {
      return mod.PoseLandmarker.createFromOptions(fileset, {
        baseOptions: { modelAssetPath: modelUrl, delegate: delegate },
        runningMode: 'VIDEO',
        numPoses: 1,
        minPoseDetectionConfidence: 0.5,
        minPosePresenceConfidence: 0.5,
        minTrackingConfidence: 0.5,
      });
    });
  }

  function ensureModel() {
    if (state.landmarker) return Promise.resolve(state.landmarker);
    if (state.modelPromise) return state.modelPromise;
    state.modelPromise = loadModule()
      .then(function (mod) {
        return localWasmAvailable().then(function (hasLocal) {
          var bases = hasLocal ? [LOCAL_WASM_BASE].concat(WASM_BASES) : [WASM_BASES[0]];
          return localModelAvailable().then(function (hasModel) {
            var modelUrl = hasModel ? LOCAL_MODEL : CDN_MODEL;
            var attempt = function (i, delegate) {
              if (i >= bases.length) {
                var err = new Error('MediaPipe 初始化失败');
                err.code = 'MODEL';
                err.detail = 'WASM 或模型文件不可用';
                throw err;
              }
              var base = bases[i];
              state.backend.wasm = base;
              state.backend.model = modelUrl;
              if (state.starting) {
                el.camState.textContent = '加载姿态模型（' + (hasModel ? '本地 5.5MB' : 'CDN') + '）…';
              }
              return createLandmarker(mod, base, modelUrl, delegate).catch(function (e) {
                console.warn('[MediaPipe] 初始化失败 base=' + base + ' delegate=' + delegate, e);
                if (delegate === 'GPU') return attempt(i, 'CPU'); // 同一 base 先降级 CPU
                return attempt(i + 1, 'GPU');
              });
            };
            return attempt(0, 'GPU');
          });
        });
      })
      .then(function (lm) {
        state.landmarker = lm;
        updateRunInfo();
        return lm;
      })
      .catch(function (e) {
        state.modelPromise = null;
        if (!e.code) e.code = 'MODEL';
        throw e;
      });
    return state.modelPromise;
  }

  // 记录真正生效的 delegate（attempt 递归里不好回传，这里根据 state 观察）
  var _origCreate = createLandmarker;
  createLandmarker = function (mod, wasmBase, modelUrl, delegate) {
    return _origCreate(mod, wasmBase, modelUrl, delegate).then(function (lm) {
      state.backend.delegate = delegate;
      state.backend.wasm = wasmBase;
      state.backend.model = modelUrl;
      return lm;
    });
  };

  // ─────────────────────────── 摄像头 ───────────────────────────
  function openCamera(facing) {
    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
      var e0 = new Error('浏览器不支持摄像头');
      e0.code = 'NO_API';
      return Promise.reject(e0);
    }
    // Chrome 把 file:// 视为「可信」但依然禁止摄像头；先拦掉，给出可执行的提示
    if (location.protocol === 'file:') {
      var ef = new Error('file:// 禁止摄像头');
      ef.code = 'INSECURE';
      return Promise.reject(ef);
    }
    if (!window.isSecureContext) {
      var e1 = new Error('非安全上下文');
      e1.code = 'INSECURE';
      return Promise.reject(e1);
    }
    var constraints = {
      audio: false,
      video: {
        facingMode: facing || 'user',
        width: { ideal: 1280 },
        height: { ideal: 720 },
      },
    };
    return navigator.mediaDevices.getUserMedia(constraints).catch(function (err) {
      if (err && err.name === 'OverconstrainedError') {
        return navigator.mediaDevices.getUserMedia({ audio: false, video: true });
      }
      throw err;
    }).then(function (stream) {
      state.stream = stream;
      var track = stream.getVideoTracks()[0];
      var gotFacing = '';
      try {
        var s = track && track.getSettings ? track.getSettings() : null;
        if (s && s.facingMode) gotFacing = s.facingMode;
      } catch (e) { /* ignore */ }
      state.facing = gotFacing || facing || 'user';
      state.mirror = state.facing !== 'environment';
      var v = el.video;
      v.srcObject = stream;
      v.classList.add('active');
      v.classList.toggle('mirrored', state.mirror);
      var playP = v.play ? v.play().catch(function () {}) : Promise.resolve();
      return playP.then(function () {
        if (v.videoWidth > 0) return;
        return new Promise(function (resolve) {
          var done = false;
          var finish = function () { if (done) return; done = true; resolve(); };
          v.addEventListener('loadedmetadata', finish, { once: true });
          setTimeout(finish, 3000);
        });
      }).then(function () {
        state.frameW = v.videoWidth || 1280;
        state.frameH = v.videoHeight || 720;
        if (track && track.addEventListener) {
          track.addEventListener('ended', function () {
            if (state.mode === 'camera') {
              toast('摄像头被系统或其它程序断开，已停止监测', 'error', 6000);
              stop();
            }
          });
        }
        return stream;
      });
    });
  }

  function stopCamera() {
    if (state.stream) {
      try { state.stream.getTracks().forEach(function (t) { t.stop(); }); } catch (e) { /* ignore */ }
      state.stream = null;
    }
    el.video.srcObject = null;
    el.video.classList.remove('active');
  }

  // ─────────────────────────── 会话控制 ───────────────────────────
  function beginSession(mode) {
    state.mode = mode;
    state.running = true;
    state.starting = false;
    state.sessionStartMs = performance.now();
    state.lastFrameAt = 0;
    state.lastDetectAt = 0;
    state.sessionFrames = 0;
    state.fps = { frames: 0, since: 0, value: 0, latency: 0 };
    state.jitter = new P.JitterTracker();
    state.median = new P.MedianWindow(P.SMOOTH_WINDOW_MS);
    state.sm = makeStateMachine();
    state.lastLandmarks = null;
    state.lastDetail = null;
    state.lastResult = null;
    state.lastTheta = null;
    state.lastViewAngle = null;

    if (mode === 'demo') {
      el.demoBadge.hidden = false;
      // 演示数据：让界面看起来像真的用过（明确标注「演示数据」，不写 localStorage）
      state.demo = { safe: 8 * 60, warn: 2 * 60 + 20, danger: 45, unreliable: 30, alerts: 2 };
      el.camState.textContent = '演示模式 · 合成关键点，不占摄像头';
      el.video.classList.remove('active');
    } else {
      el.demoBadge.hidden = true;
      state.demo = null;
      el.camState.textContent = '摄像头运行中 · 画面只在本机处理';
      requestWakeLock();
    }

    el.stageEmpty.hidden = true;
    el.stopBtn.disabled = false;
    el.demoToggleBtn.textContent = mode === 'demo' ? '退出演示' : '演示';
    el.startBtn.textContent = '重新开始';
    setStatus('loading');
    renderStats();
    startRaf();
  }

  function stop() {
    if (!state.running && state.mode === 'idle') return;
    state.running = false;
    state.starting = false;
    state.mode = 'idle';
    cancelAnimationFrame(state.raf);
    state.raf = 0;
    flushStats();
    stopCamera();
    releaseWakeLock();
    state.demo = null;
    state.lastLandmarks = null;
    state.lastDetail = null;
    state.lastResult = null;
    state.lastTheta = null;
    state.lastViewAngle = null;

    el.demoBadge.hidden = true;
    el.stageEmpty.hidden = false;
    el.stopBtn.disabled = true;
    el.demoToggleBtn.textContent = '演示';
    el.startBtn.textContent = '开启摄像头';
    el.camState.textContent = '摄像头未开启';
    el.secureHint.textContent = secureHintText();
    setStatus('none');
    renderReadings(null, false);
    updateReadout(null, false, 0, null);
    updateViewChip(null, null);
    el.fpsChip.textContent = '-- fps';
    el.fpsChip.className = 'chip';
    clearOverlay();
    renderStats();
  }

  function startCamera() {
    if (state.running && state.mode === 'camera') {
      toast('已经在监测中', 'ok', 2000);
      return;
    }
    if (!privacyAcked()) {
      state.pendingAfterPrivacy = startCamera;
      showPrivacyModal();
      return;
    }
    if (state.running) stop();
    ensureAudio();
    state.starting = true;
    el.startBtn.disabled = true;
    el.camState.textContent = '正在请求摄像头权限…';
    setStatus('loading');
    var modelP = ensureModel();
    // 摄像头先失败时，模型加载可能仍在后台进行；挂一个 catch 避免未处理的 rejection
    modelP.catch(function (e) { console.warn('[模型加载]', e); });
    var camP = openCamera(state.facing || 'user');
    camP.then(function () {
      el.camState.textContent = '正在等待姿态模型…';
      return modelP;
    }).then(function () {
      // 用户可能在等权限/模型时点了「演示」或「停止」：不要再把摄像头会话接回去
      if (!state.starting) {
        stopCamera();
        return;
      }
      beginSession('camera');
      toast('已开始守护：把镜头对准侧脸和肩膀', 'ok', 4500);
    }).catch(function (err) {
      console.error('[启动失败]', err);
      stopCamera();
      setStatus('error');
      el.camState.textContent = '启动失败，摄像头未开启';
      var msg = cameraErrorText(err);
      el.secureHint.textContent = msg;
      toast(msg, 'error', 9000);
      updateRunInfo();
    }).then(function () {
      state.starting = false;
      el.startBtn.disabled = false;
    });
  }

  function startDemo(keepFixedTheta) {
    if (state.running) stop();
    // URL 带 ?theta= 时固定角度，方便截图/演示；手动点「演示」则恢复动画
    if (!keepFixedTheta) state.demoThetaFixed = null;
    ensureAudio();
    beginSession('demo');
    toast('演示模式：画面是合成的，用于预览界面；真实检测请开启摄像头', 'ok', 6500);
  }

  function requestWakeLock() {
    if (!('wakeLock' in navigator) || state.wakeLock) return;
    try {
      navigator.wakeLock.request('screen').then(function (lock) {
        state.wakeLock = lock;
        lock.addEventListener && lock.addEventListener('release', function () { state.wakeLock = null; });
      }).catch(function () { /* 不支持就算了 */ });
    } catch (e) { /* ignore */ }
  }
  function releaseWakeLock() {
    if (state.wakeLock) {
      try { state.wakeLock.release(); } catch (e) { /* ignore */ }
      state.wakeLock = null;
    }
  }

  // ─────────────────────────── 主循环 ───────────────────────────
  function startRaf() {
    cancelAnimationFrame(state.raf);
    var tick = function () {
      if (!state.running) return;
      state.raf = requestAnimationFrame(tick);
      var now = performance.now();
      var interval = 1000 / settings.maxFps;
      if (now - state.lastDetectAt < interval) return;
      state.lastDetectAt = now;
      if (state.mode === 'demo') runDemoFrame(now);
      else runCameraFrame(now);
    };
    state.raf = requestAnimationFrame(tick);
  }

  function runCameraFrame(now) {
    var v = el.video;
    if (!state.landmarker || v.readyState < 2 || !v.videoWidth) return;
    var t0 = performance.now();
    var res = null;
    try {
      res = state.landmarker.detectForVideo(v, now);
    } catch (e) {
      console.warn('[detectForVideo]', e);
      return;
    }
    var inferMs = performance.now() - t0;
    state.fps.latency = state.fps.latency ? state.fps.latency * 0.8 + inferMs * 0.2 : inferMs;
    var lms = res && res.landmarks && res.landmarks.length ? res.landmarks[0] : null;
    ingest(lms, v.videoWidth, v.videoHeight, now);
  }

  function runDemoFrame(now) {
    var theta;
    if (state.demoThetaFixed !== null) {
      theta = state.demoThetaFixed;
    } else {
      var t = (now - state.sessionStartMs) / 1000;
      // 缓慢起伏：2° → 38° → 2°，覆盖三档，方便看界面变化
      theta = 20 + 15 * Math.sin(t * 0.42) + 3 * Math.sin(t * 1.6);
    }
    var demo = demoLandmarks(theta);
    ingest(demo.lms, DEMO_W, DEMO_H, now, demo);
  }

  /** 合成一组「正面朝右」的关键点：调 theta 就能改变头前倾角。 */
  function demoLandmarks(thetaDeg) {
    var sh = { x: 0.43, y: 0.62 };
    var earY = 0.40;
    var dyPx = (sh.y - earY) * DEMO_H;
    var rad = ((90 - thetaDeg) * Math.PI) / 180;
    var dxPx = dyPx / Math.tan(rad);
    var ex = sh.x + dxPx / DEMO_W;
    var lms = P.emptyLandmarks();
    lms[0] = P.landmark(ex + 0.052, 0.375, 1); // 鼻
    lms[7] = P.landmark(ex, earY, 1); // 近侧耳（朝前一侧）
    lms[8] = P.landmark(ex - 0.043, earY + 0.006, 1); // 远侧耳
    lms[11] = P.landmark(sh.x, sh.y, 1); // 近侧肩
    lms[12] = P.landmark(sh.x - 0.085, sh.y + 0.004, 1); // 远侧肩
    return { lms: lms, W: DEMO_W, H: DEMO_H };
  }

  // ─────────────────────────── 单帧处理（相机 / 演示共用） ───────────────────────────
  function ingest(lms, w, h, now) {
    state.sessionFrames++;
    updateFps(now);

    var dt = state.lastFrameAt ? clamp((now - state.lastFrameAt) / 1000, 0, 3) : 0;
    state.lastFrameAt = now;
    var nowSec = (now - state.sessionStartMs) / 1000;

    var viewAngle = null;
    var detail = null;
    var thetaRaw = null;

    if (lms && lms.length > P.LM.RIGHT_SHOULDER) {
      try {
        viewAngle = P.estimateViewAngleDeg(lms);
        var earJittery = state.jitter.update(lms);
        detail = P.measureDetail(lms, w, h, earJittery);
        thetaRaw = detail ? detail.theta : null;
      } catch (e) {
        console.warn('[posture.js] 单帧计算异常', e);
        detail = null;
        thetaRaw = null;
      }
    }

    state.lastLandmarks = lms;
    state.lastViewAngle = viewAngle;

    if (thetaRaw === null) {
      // 未检出：不计数、不推进状态机（离席连续计时清零），与 Android PoseMonitor 一致
      state.sm.update(nowSec, P.LEVEL.SAFE, false, dt);
      state.lastDetail = null;
      state.lastResult = null;
      setStatus('none');
      renderReadings(null, false);
      updateReadout(null, false, 0, null);
      updateViewChip(null, null);
      drawOverlay();
      return;
    }

    var theta = state.median.push(thetaRaw, now); // 10 秒时间窗中位数，抗误检
    var result = P.fromTheta(theta, settings.warnDeg, settings.dangerDeg);
    var viewBad = viewAngle !== null && viewAngle > MAX_ALERT_VIEW;

    state.lastDetail = detail;
    state.lastResult = result;
    state.lastTheta = theta;

    // 统计：视角过正的时间单独记「不可信」，不计入三级分布（对齐 Android）
    if (viewBad) accrue('unreliable', dt);
    else accrue(String(result.level).toLowerCase(), dt);

    // 状态机：连续低头 / 累计低头 / 疲劳
    var ev = state.sm.update(nowSec, result.level, true, dt);
    if (ev && !viewBad) {
      accrueAlert();
      triggerAlert(ev.reason);
    }

    setStatus(viewBad ? 'unreliable' : String(result.level).toLowerCase());
    renderReadings(result, viewBad);
    updateReadout(detail, viewBad, theta, viewAngle);
    updateViewChip(viewAngle, detail);
    drawOverlay();
  }

  function updateFps(now) {
    var f = state.fps;
    f.frames++;
    if (!f.since) f.since = now;
    if (now - f.since >= 1000) {
      f.value = (f.frames * 1000) / (now - f.since);
      f.frames = 0;
      f.since = now;
      var lat = f.latency ? Math.round(f.latency) + 'ms' : '--';
      if (state.mode === 'demo') {
        el.fpsChip.textContent = '演示 ' + f.value.toFixed(0) + ' fps';
      } else {
        el.fpsChip.textContent = '推理 ' + f.value.toFixed(0) + ' fps · ' + lat;
      }
      el.fpsChip.className = 'chip';
    }
  }

  // ─────────────────────────── Canvas 标注 ───────────────────────────
  function clearOverlay() {
    var c = el.overlay;
    if (!c) return;
    var ctx = c.getContext('2d');
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.clearRect(0, 0, c.width, c.height);
  }

  function roundRectPath(ctx, x, y, w, h, r) {
    r = Math.min(r, Math.abs(w) / 2, Math.abs(h) / 2);
    ctx.beginPath();
    ctx.moveTo(x + r, y);
    ctx.arcTo(x + w, y, x + w, y + h, r);
    ctx.arcTo(x + w, y + h, x, y + h, r);
    ctx.arcTo(x, y + h, x, y, r);
    ctx.arcTo(x, y, x + w, y, r);
    ctx.closePath();
  }

  function drawOverlay() {
    var stage = el.stage;
    var c = el.overlay;
    if (!stage || !c) return;
    var cssW = stage.clientWidth;
    var cssH = stage.clientHeight;
    if (!cssW || !cssH) return;
    var dpr = Math.min(window.devicePixelRatio || 1, 2);
    var pxW = Math.round(cssW * dpr);
    var pxH = Math.round(cssH * dpr);
    if (c.width !== pxW || c.height !== pxH) {
      c.width = pxW;
      c.height = pxH;
    }
    var ctx = c.getContext('2d');
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, cssW, cssH);

    var isDemo = state.mode === 'demo';
    var frameW = isDemo ? DEMO_W : (el.video.videoWidth || state.frameW || 0);
    var frameH = isDemo ? DEMO_H : (el.video.videoHeight || state.frameH || 0);
    if (!frameW || !frameH) return;

    // 与 CSS object-fit: cover 完全一致的映射（fitCenter 会留黑边，cover 更接近 App）
    var scale = Math.max(cssW / frameW, cssH / frameH);
    var dw = frameW * scale;
    var dh = frameH * scale;
    var ox = (cssW - dw) / 2;
    var oy = (cssH - dh) / 2;
    var mirror = !isDemo && state.mirror;
    var mapX = function (x) { return ox + (mirror ? 1 - x : x) * dw; };
    var mapY = function (y) { return oy + y * dh; };

    if (isDemo) drawDemoScene(ctx, cssW, cssH, mapX, mapY);

    var detail = state.lastDetail;
    var lms = state.lastLandmarks;
    if (!detail || !lms) return;

    var ex = mapX(detail.ear.x);
    var ey = mapY(detail.ear.y);
    var sx = mapX(detail.shoulder.x);
    var sy = mapY(detail.shoulder.y);
    var viaNose = detail.viaNose;
    var accent = viaNose ? '#FFB020' : '#2E7DF6';
    var safe = '#34C759';
    var warn = '#FFB020';
    var danger = '#FF453A';
    var white = 'rgba(255,255,255,0.92)';

    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';

    // ① 水平参考线（虚线）
    ctx.save();
    ctx.setLineDash([5, 4]);
    ctx.strokeStyle = 'rgba(255,255,255,0.55)';
    ctx.lineWidth = 1;
    ctx.beginPath();
    ctx.moveTo(ex, ey);
    ctx.lineTo(ox + dw, ey);
    ctx.stroke();
    ctx.restore();

    // ② 参考点到肩的连线（CVA 依据；备用模式是鼻→肩）
    ctx.strokeStyle = viaNose ? warn : white;
    ctx.lineWidth = 2;
    ctx.beginPath();
    ctx.moveTo(ex, ey);
    ctx.lineTo(sx, sy);
    ctx.stroke();

    // ③ 夹角弧
    var ang = Math.atan2(sy - ey, sx - ex);
    var r = Math.min(34, Math.max(20, Math.min(cssW, cssH) * 0.055));
    ctx.strokeStyle = accent;
    ctx.lineWidth = 2.5;
    ctx.beginPath();
    ctx.arc(ex, ey, r, 0, ang, false);
    ctx.stroke();

    // ④ 角标：CVA 角度
    var label = Math.round(detail.cvaDeg) + '°';
    var mid = ang / 2;
    var lx = ex + (r + 18) * Math.cos(mid);
    var ly = ey + (r + 18) * Math.sin(mid);
    drawLabel(ctx, label, lx, ly, accent);

    // ⑤ 鼻 → 双耳中线（视角估计的依据）
    var le = lms[P.LM.LEFT_EAR];
    var re = lms[P.LM.RIGHT_EAR];
    var nose = lms[P.LM.NOSE];
    if (nose && le && re && nose.visibility > 0.4) {
      var nx = mapX(nose.x);
      var ny = mapY(nose.y);
      var mx = mapX((le.x + re.x) / 2);
      var my = mapY((le.y + re.y) / 2);
      ctx.save();
      ctx.setLineDash([4, 4]);
      ctx.strokeStyle = 'rgba(255,176,32,0.8)';
      ctx.lineWidth = 1.4;
      ctx.beginPath();
      ctx.moveTo(nx, ny);
      ctx.lineTo(mx, my);
      ctx.stroke();
      ctx.restore();
      ctx.fillStyle = 'rgba(255,176,32,0.85)';
      ctx.beginPath();
      ctx.arc(nx, ny, 2.6, 0, Math.PI * 2);
      ctx.fill();
      ctx.beginPath();
      ctx.arc(mx, my, 2.2, 0, Math.PI * 2);
      ctx.fill();
    }

    // ⑥ 另一侧的点画成淡色空心圈：让用户看出「有两只耳 / 两只肩，算法选中了实心那只」
    var farEar = detail.nearLeft ? lms[P.LM.RIGHT_EAR] : lms[P.LM.LEFT_EAR];
    var farShoulder = detail.nearLeft ? lms[P.LM.RIGHT_SHOULDER] : lms[P.LM.LEFT_SHOULDER];
    if (farEar) {
      ctx.strokeStyle = 'rgba(255,255,255,0.38)';
      ctx.lineWidth = 1;
      ctx.beginPath();
      ctx.arc(mapX(farEar.x), mapY(farEar.y), 5, 0, Math.PI * 2);
      ctx.stroke();
    }
    if (farShoulder) {
      ctx.strokeStyle = 'rgba(255,255,255,0.38)';
      ctx.lineWidth = 1;
      ctx.beginPath();
      ctx.arc(mapX(farShoulder.x), mapY(farShoulder.y), 5, 0, Math.PI * 2);
      ctx.stroke();
    }

    // ⑦ 选中的耳（蓝）/ 肩（绿），带白圈保证亮背景可见
    [
      { x: ex, y: ey, color: accent },
      { x: sx, y: sy, color: safe },
    ].forEach(function (p) {
      ctx.fillStyle = 'rgba(255,255,255,0.95)';
      ctx.beginPath();
      ctx.arc(p.x, p.y, 7, 0, Math.PI * 2);
      ctx.fill();
      ctx.fillStyle = p.color;
      ctx.beginPath();
      ctx.arc(p.x, p.y, 4.6, 0, Math.PI * 2);
      ctx.fill();
    });

    // ⑧ 耳-肩竖直距离太小 → 肩点被猜到了脖子上，角度会偏大
    if (!viaNose && Math.abs(detail.shoulder.y - detail.ear.y) < 0.16) {
      drawLabel(ctx, '角度会偏大', mapX(0.08), mapY(0.1) + 14, danger, true);
    }

    // ⑨ 底部读数条由 HTML 负责（见 index.html .stage-readout），canvas 不再重复画
  }

  function drawLabel(ctx, text, cx, cy, color, alignLeft) {
    ctx.save();
    ctx.font = '600 11px -apple-system, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif';
    ctx.textAlign = alignLeft ? 'left' : 'center';
    ctx.textBaseline = 'middle';
    var w = ctx.measureText(text).width;
    var left = alignLeft ? cx : cx - w / 2;
    var padX = 4;
    var h = 16;
    ctx.fillStyle = 'rgba(9,12,16,0.72)';
    roundRectPath(ctx, left - padX, cy - h / 2, w + padX * 2, h, 4);
    ctx.fill();
    ctx.fillStyle = color;
    ctx.fillText(text, left, cy + 0.5);
    ctx.restore();
  }

  /** 演示模式的合成画面：一个人侧坐在椅子上的剪影。 */
  function drawDemoScene(ctx, cssW, cssH, mapX, mapY) {
    var grad = ctx.createLinearGradient(0, 0, 0, cssH);
    grad.addColorStop(0, '#18212b');
    grad.addColorStop(0.55, '#0f151b');
    grad.addColorStop(1, '#080b0f');
    ctx.fillStyle = grad;
    ctx.fillRect(0, 0, cssW, cssH);

    // 背景网格，让画面不那么空
    ctx.strokeStyle = 'rgba(120,150,180,0.06)';
    ctx.lineWidth = 1;
    var stepX = cssW / 8;
    for (var x = stepX; x < cssW; x += stepX) {
      ctx.beginPath(); ctx.moveTo(x, 0); ctx.lineTo(x, cssH); ctx.stroke();
    }
    var stepY = cssH / 10;
    for (var y = stepY; y < cssH; y += stepY) {
      ctx.beginPath(); ctx.moveTo(0, y); ctx.lineTo(cssW, y); ctx.stroke();
    }

    // 桌面 / 地板
    ctx.fillStyle = '#141b22';
    ctx.fillRect(0, mapY(0.78), cssW, cssH - mapY(0.78));
    ctx.strokeStyle = '#2c3540';
    ctx.lineWidth = 1.5;
    ctx.beginPath();
    ctx.moveTo(0, mapY(0.78));
    ctx.lineTo(cssW, mapY(0.78));
    ctx.stroke();

    var lms = state.lastLandmarks;
    if (!lms) return;
    var le = lms[P.LM.LEFT_EAR];
    var re = lms[P.LM.RIGHT_EAR];
    var nose = lms[P.LM.NOSE];
    var sh = lms[P.LM.LEFT_SHOULDER];
    var shFar = lms[P.LM.RIGHT_SHOULDER];
    if (!le || !re || !sh) return;

    // 椅背（压暗，只当背景，不抢人物）
    ctx.fillStyle = '#121920';
    ctx.strokeStyle = '#222c36';
    ctx.lineWidth = 1.2;
    roundRectPath(ctx, mapX(0.08), mapY(0.52), mapX(0.64) - mapX(0.08), mapY(1.02) - mapY(0.52), 16);
    ctx.fill();
    ctx.stroke();

    // 桌上的笔记本（放在人物前方，暗示「正在工作」）
    ctx.fillStyle = '#0d1319';
    ctx.strokeStyle = '#2c3540';
    ctx.lineWidth = 1.4;
    roundRectPath(ctx, mapX(0.70), mapY(0.545), mapX(0.95) - mapX(0.70), mapY(0.755) - mapY(0.545), 7);
    ctx.fill();
    ctx.stroke();
    var screenGrad = ctx.createLinearGradient(0, mapY(0.545), 0, mapY(0.755));
    screenGrad.addColorStop(0, 'rgba(46,125,246,0.22)');
    screenGrad.addColorStop(1, 'rgba(46,125,246,0.03)');
    ctx.fillStyle = screenGrad;
    ctx.fillRect(mapX(0.712), mapY(0.558), mapX(0.938) - mapX(0.712), mapY(0.742) - mapY(0.558));
    ctx.strokeStyle = 'rgba(140,180,240,0.35)';
    ctx.lineWidth = 1;
    [0.60, 0.64, 0.68, 0.72].forEach(function (yy, i) {
      ctx.beginPath();
      ctx.moveTo(mapX(0.73), mapY(yy));
      ctx.lineTo(mapX(i === 3 ? 0.86 : 0.90), mapY(yy));
      ctx.stroke();
    });
    // 笔记本底座
    ctx.fillStyle = '#1a222b';
    ctx.strokeStyle = '#2c3540';
    ctx.beginPath();
    ctx.moveTo(mapX(0.70), mapY(0.755));
    ctx.lineTo(mapX(0.95), mapY(0.755));
    ctx.lineTo(mapX(0.99), mapY(0.795));
    ctx.lineTo(mapX(0.65), mapY(0.795));
    ctx.closePath();
    ctx.fill();
    ctx.stroke();

    var facing = nose && nose.x >= (le.x + re.x) / 2 ? 1 : -1;
    var headR = 0.075 * (mapY(1) - mapY(0));
    var headCx = mapX(le.x + facing * 0.042);
    var headCy = mapY(le.y - 0.012);
    var shCx = (mapX(sh.x) + mapX(shFar.x)) / 2;
    var shCy = (mapY(sh.y) + mapY(shFar.y)) / 2;

    // 躯干（含肩线，梯形 + 圆角肩）
    var shoulderL = mapX(0.16);
    var shoulderR = mapX(0.68);
    var torsoTop = mapY(0.615);
    var torsoBottom = cssH + 10;
    ctx.fillStyle = '#232d38';
    ctx.strokeStyle = '#384657';
    ctx.lineWidth = 2;
    ctx.beginPath();
    ctx.moveTo(shoulderL + 16, torsoTop + 20);
    ctx.quadraticCurveTo(shoulderL + 6, torsoTop + 2, shoulderL - 4, torsoTop + 44);
    ctx.lineTo(shoulderL - 12, torsoBottom);
    ctx.lineTo(shoulderR + 12, torsoBottom);
    ctx.lineTo(shoulderR + 4, torsoTop + 38);
    ctx.quadraticCurveTo(shoulderR - 6, torsoTop + 2, shoulderR - 18, torsoTop + 20);
    ctx.closePath();
    ctx.fill();
    ctx.stroke();

    // 近侧手臂（从肩到桌面，一条粗线即可示意）
    ctx.strokeStyle = '#26303b';
    ctx.lineWidth = headR * 0.9;
    ctx.lineCap = 'round';
    ctx.beginPath();
    ctx.moveTo(mapX(0.60), mapY(0.655));
    ctx.quadraticCurveTo(mapX(0.69), mapY(0.72), mapX(0.72), mapY(0.785));
    ctx.stroke();
    ctx.fillStyle = '#33404e';
    ctx.beginPath();
    ctx.arc(mapX(0.72), mapY(0.788), headR * 0.28, 0, Math.PI * 2);
    ctx.fill();

    // 脖子
    ctx.fillStyle = '#26313d';
    ctx.strokeStyle = '#384657';
    ctx.lineWidth = 1.6;
    ctx.beginPath();
    ctx.moveTo(headCx - headR * 0.44, headCy + headR * 0.5);
    ctx.lineTo(headCx + headR * 0.44, headCy + headR * 0.5);
    ctx.lineTo(shCx + headR * 0.66, shCy + 6);
    ctx.lineTo(shCx - headR * 0.66, shCy + 6);
    ctx.closePath();
    ctx.fill();
    ctx.stroke();

    // 头
    ctx.fillStyle = '#303b49';
    ctx.strokeStyle = '#4a5a6d';
    ctx.lineWidth = 2;
    ctx.beginPath();
    ctx.arc(headCx, headCy, headR, 0, Math.PI * 2);
    ctx.fill();
    ctx.stroke();

    // 头发（上半圈，像一顶帽子；不追求写实，够示意侧脸朝向）
    ctx.fillStyle = '#1b232d';
    ctx.beginPath();
    ctx.arc(headCx, headCy, headR * 1.01, Math.PI, Math.PI * 2, false);
    ctx.closePath();
    ctx.fill();

    // 耳朵（靠近镜头那只，与算法画点位置对应）
    ctx.fillStyle = '#3d4b5c';
    ctx.beginPath();
    ctx.arc(mapX(le.x), mapY(le.y), headR * 0.17, 0, Math.PI * 2);
    ctx.fill();

    // 鼻子（提示朝向）
    if (nose) {
      ctx.fillStyle = '#44546a';
      ctx.beginPath();
      ctx.arc(mapX(nose.x), mapY(nose.y), headR * 0.11, 0, Math.PI * 2);
      ctx.fill();
    }

    // 演示模式提示条
    ctx.save();
    ctx.font = '600 11px -apple-system, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif';
    ctx.fillStyle = 'rgba(255,176,32,0.85)';
    ctx.textAlign = 'center';
    ctx.fillText('合成画面 · 仅用于预览界面', cssW / 2, cssH - 44);
    ctx.restore();
  }

  // ─────────────────────────── 隐私弹窗 ───────────────────────────
  function privacyAcked() {
    return safeGet(LS.privacy) === '1';
  }
  function showPrivacyModal() {
    el.privacyModal.hidden = false;
  }
  function hidePrivacyModal() {
    el.privacyModal.hidden = true;
  }
  function ackPrivacy() {
    safeSet(LS.privacy, '1');
    hidePrivacyModal();
    var action = state.pendingAfterPrivacy;
    state.pendingAfterPrivacy = null;
    if (action) setTimeout(action, 80);
  }

  // ─────────────────────────── 设置界面 ───────────────────────────
  function syncSettingsUI() {
    el.warnRange.value = String(settings.warnDeg);
    el.dangerRange.value = String(settings.dangerDeg);
    el.holdRange.value = String(settings.holdSec);
    el.fpsRange.value = String(settings.maxFps);
    el.warnLabel.textContent = settings.warnDeg + '°';
    el.dangerLabel.textContent = settings.dangerDeg + '°';
    el.holdLabel.textContent = settings.holdSec + ' 秒';
    el.fpsLabel.textContent = settings.maxFps + ' fps';
    el.soundToggle.checked = !!settings.sound;
    el.vibrateToggle.checked = !!settings.vibrate;
    resetReadingHints();
  }

  function updateRunInfo() {
    var b = state.backend;
    var browser = '当前浏览器';
    try {
      var ua = navigator.userAgent;
      if (/Edg\//.test(ua)) browser = 'Edge';
      else if (/Chrome\//.test(ua)) browser = 'Chrome/Chromium';
      else if (/Firefox\//.test(ua)) browser = 'Firefox';
      else if (/Safari\//.test(ua)) browser = 'Safari';
      browser += ' · ' + (window.isSecureContext ? '安全上下文' : '非安全上下文');
    } catch (e) { /* ignore */ }
    var lines = [
      '网页版 v' + APP_VERSION + ' · 算法与 Android 版同源（71 项 Node 自测）',
      '推理库：' + (b.module ? shortUrl(b.module) : '未加载（点「开启摄像头」时加载）'),
      'WASM：' + (b.wasm ? shortUrl(b.wasm) : '未加载') +
        ' · 模型：' + (b.model ? shortUrl(b.model) : '未加载'),
      '后端：' + (b.delegate ? b.delegate : '未初始化') +
        ' · 分辨率：' + (state.frameW ? state.frameW + '×' + state.frameH : '--') +
        ' · 帧率上限：' + settings.maxFps + ' fps',
      '浏览器：' + browser,
    ];
    el.runInfo.innerHTML = lines.map(function (s) { return s.replace(/&/g, '&amp;').replace(/</g, '&lt;'); }).join('<br>');
  }

  function setRangeValue(input, key, min, max) {
    var v = clamp(parseInt(input.value, 10) || min, min, max);
    settings[key] = v;
    // 保证 注意 < 危险
    if (key === 'warnDeg' && settings.warnDeg >= settings.dangerDeg) {
      settings.dangerDeg = Math.min(60, settings.warnDeg + 5);
    }
    if (key === 'dangerDeg' && settings.dangerDeg <= settings.warnDeg) {
      settings.warnDeg = Math.max(5, settings.dangerDeg - 5);
    }
    if (key === 'holdSec') {
      state.sm.dangerHoldSeconds = settings.holdSec;
    }
    saveSettings();
    syncSettingsUI();
    renderStats();
    if (state.running) renderReadings(state.lastResult, state.lastViewAngle !== null && state.lastViewAngle > MAX_ALERT_VIEW);
  }

  // ─────────────────────────── 事件绑定 ───────────────────────────
  function bindEvents() {
    el.themeBtn.addEventListener('click', function () {
      var idx = THEME_ORDER.indexOf(themePref);
      var next = THEME_ORDER[(idx + 1) % THEME_ORDER.length];
      applyTheme(next);
      toast('主题：' + THEME_LABEL[next], 'ok', 1600);
    });

    el.settingsBtn.addEventListener('click', function () {
      syncSettingsUI();
      updateRunInfo();
      el.settingsModal.hidden = false;
    });
    el.settingsClose.addEventListener('click', function () { el.settingsModal.hidden = true; });
    el.settingsModal.addEventListener('click', function (e) {
      if (e.target === el.settingsModal) el.settingsModal.hidden = true;
    });

    el.privacyBtn.addEventListener('click', showPrivacyModal);
    el.footerPrivacy.addEventListener('click', function (e) {
      e.preventDefault();
      showPrivacyModal();
    });
    el.privacyOk.addEventListener('click', ackPrivacy);
    el.privacyModal.addEventListener('click', function (e) {
      if (e.target === el.privacyModal) hidePrivacyModal();
    });

    el.startBtn.addEventListener('click', startCamera);
    el.demoBtn.addEventListener('click', startDemo);
    el.stopBtn.addEventListener('click', function () { stop(); toast('已停止监测', 'ok', 2000); });
    el.demoToggleBtn.addEventListener('click', function () {
      if (state.mode === 'demo') { stop(); }
      else { startDemo(); }
    });
    el.switchCamBtn.addEventListener('click', switchCamera);
    el.alertOk.addEventListener('click', function () { el.alertBanner.hidden = true; });

    [el.warnRange, el.dangerRange, el.holdRange, el.fpsRange].forEach(function (input) {
      input.addEventListener('input', function () {
        var map = { warnRange: ['warnDeg', 5, 45], dangerRange: ['dangerDeg', 15, 60], holdRange: ['holdSec', 30, 600], fpsRange: ['maxFps', 5, 30] };
        var m = map[input.id];
        setRangeValue(input, m[0], m[1], m[2]);
      });
    });
    el.soundToggle.addEventListener('change', function () {
      settings.sound = el.soundToggle.checked;
      if (settings.sound) ensureAudio();
      saveSettings();
    });
    el.vibrateToggle.addEventListener('change', function () {
      settings.vibrate = el.vibrateToggle.checked;
      saveSettings();
    });

    el.clearToday.addEventListener('click', function () {
      if (!window.confirm('清除今天的坐姿统计？此操作不可撤销。')) return;
      delete stats.days[todayKey()];
      pending = emptyTotals();
      if (state.demo) state.demo = emptyTotals();
      safeSet(LS.stats, JSON.stringify(stats));
      renderStats();
      toast('已清除今日统计', 'ok', 2500);
    });
    el.clearAll.addEventListener('click', function () {
      if (!window.confirm('清空全部本地数据（统计 + 设置 + 隐私确认）？此操作不可撤销。')) return;
      safeRemove(LS.stats);
      safeRemove(LS.settings);
      safeRemove(LS.privacy);
      stats = { v: 1, days: {} };
      pending = emptyTotals();
      if (state.demo) state.demo = emptyTotals();
      Object.keys(DEFAULT_SETTINGS).forEach(function (k) { settings[k] = DEFAULT_SETTINGS[k]; });
      state.sm.dangerHoldSeconds = settings.holdSec;
      syncSettingsUI();
      renderStats();
      updateRunInfo();
      toast('已清空全部本地数据', 'ok', 3000);
    });

    document.addEventListener('keydown', function (e) {
      if (e.key === 'Escape') {
        el.settingsModal.hidden = true;
        el.privacyModal.hidden = true;
        el.alertBanner.hidden = true;
      }
    });

    document.addEventListener('visibilitychange', onVisibility);
    window.addEventListener('beforeunload', function () { flushStats(); });
    window.addEventListener('pagehide', function () { flushStats(); });

    if (prefersDark && prefersDark.addEventListener) {
      prefersDark.addEventListener('change', function () {
        if (themePref === 'auto') applyTheme('auto', false);
      });
    }
  }

  function switchCamera() {
    if (!state.running || state.mode !== 'camera') {
      toast('先开启摄像头再切换', 'error', 2500);
      return;
    }
    var next = state.facing === 'environment' ? 'user' : 'environment';
    el.switchCamBtn.disabled = true;
    stopCamera();
    openCamera(next).then(function () {
      state.sessionStartMs = performance.now();
      state.lastFrameAt = 0;
      state.lastDetectAt = 0;
      state.sm = makeStateMachine();
      state.median.reset();
      state.jitter = new P.JitterTracker();
      state.lastDetail = null;
      state.lastResult = null;
      clearOverlay();
      toast('已切换到' + (next === 'environment' ? '后置' : '前置') + '摄像头', 'ok', 2500);
    }).catch(function (e) {
      toast(cameraErrorText(e), 'error', 8000);
      stop();
    }).then(function () {
      el.switchCamBtn.disabled = false;
    });
  }

  function onVisibility() {
    if (document.hidden) {
      state.hiddenAt = performance.now();
      flushStats();
      return;
    }
    var away = state.hiddenAt ? performance.now() - state.hiddenAt : 0;
    state.hiddenAt = 0;
    if (!state.running) return;
    state.lastFrameAt = 0;
    if (away > SESSION_RESET_HIDDEN_MS) {
      // 页面隐藏期间浏览器会暂停 rAF，回来时 now 会跳变；
      // 若不重置，状态机会把「离开的 10 分钟」当成连续低头，一回来就误报。
      state.sm = makeStateMachine();
      state.median.reset();
      state.jitter = new P.JitterTracker();
      toast('已恢复监测（离开页面期间暂停计数）', 'ok', 3500);
    }
    if (state.mode === 'camera') requestWakeLock();
  }

  // ─────────────────────────── 启动 ───────────────────────────
  function init() {
    // 主题：URL ?theme=light|dark 可强制（方便截图/演示），不写 localStorage
    var params = new URLSearchParams(location.search);
    var forced = params.get('theme');
    if (forced === 'light' || forced === 'dark') {
      themePref = forced;
      applyTheme(forced, false);
    } else {
      applyTheme(themePref, false);
    }

    bindEvents();
    syncSettingsUI();
    resetReadingHints();
    renderStats();
    updateRunInfo();
    el.secureHint.textContent = secureHintText();

    // 演示模式的固定角度（?demo=1&theta=18），方便截图对照
    var fixedTheta = parseFloat(params.get('theta'));
    if (isFinite(fixedTheta)) state.demoThetaFixed = clamp(fixedTheta, -10, 70);

    // 统计UI每秒刷新一次（帧率太高，不必每帧刷 DOM）
    setInterval(renderStats, 1000);
    // 每 20 秒落一次盘，避免浏览器崩溃/意外关闭时丢失最近的数据
    setInterval(function () {
      if (state.running && state.mode === 'camera') flushStats();
    }, 20000);

    if (params.get('demo') === '1') {
      setTimeout(function () {
        if (!state.running) startDemo(true);
      }, 250);
    }

    if (!privacyAcked()) {
      setTimeout(showPrivacyModal, 500);
    }
  }

  // 供自动化/排查用（README 有说明）
  window.__cg = {
    version: APP_VERSION,
    startCamera: startCamera,
    startDemo: startDemo,
    stop: stop,
    flushStats: flushStats,
    get diag() { return state.backend; },
    get summary() {
      return {
        mode: state.mode,
        running: state.running,
        status: el.statusText.textContent,
        theta: state.lastTheta,
        viewAngle: state.lastViewAngle,
        fps: state.fps.value,
        backend: state.backend,
        frames: state.sessionFrames,
      };
    },
    selfTest: function () {
      return ensureModel().then(function (lm) {
        // 用一张空白 canvas 跑一次真实推理：证明 WASM + 模型 + 推理管线都活着
        var cv = document.createElement('canvas');
        cv.width = 320;
        cv.height = 240;
        var ctx = cv.getContext('2d');
        ctx.fillStyle = '#223';
        ctx.fillRect(0, 0, 320, 240);
        var t0 = performance.now();
        var res = lm.detectForVideo(cv, performance.now());
        var ms = performance.now() - t0;
        return {
          ok: true,
          module: state.backend.module,
          wasm: state.backend.wasm,
          model: state.backend.model,
          delegate: state.backend.delegate,
          inferenceMs: Math.round(ms * 10) / 10,
          landmarks: res && res.landmarks ? res.landmarks.length : 0,
        };
      }).catch(function (e) {
        return { ok: false, error: String(e), diag: state.backend };
      });
    },
  };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
