/* ============================================================================
 * js/config.js — 资源地址与缓存版本（只服务加载层，不改界面 / 算法）
 * ----------------------------------------------------------------------------
 * 用法：
 *   默认：GitHub Pages 镜像优先（gh-proxy → jsDelivr → ghproxy → 同源兜底）；
 *         其它主机同源优先（assets/ → gh-proxy → jsDelivr → ghproxy）。
 *   8 秒超时按序回退；慢速时按剩余时间估算，预计省 ≥8 秒才切回 Pages。
 *   换源：在引用本文件之前设置
 *       window.ASSET_BASE  = 'https://your-cdn.example.com/web/assets/';
 *     或给候选数组（按顺序尝试）：
 *       window.ASSET_BASES = ['assets/', 'https://cdn.example.com/assets/'];
 *   发版：资源内容变了就改 window.CG_ASSET_VERSION，旧缓存自然失效。
 *
 * 所有 wasm / 模型 URL 都由这里拼；默认值与改造前完全一致
 * （assets/wasm、assets/pose_landmarker_lite.task）。
 * ============================================================================ */
(function () {
  'use strict';

  // 仓库里的另一份同内容资源（GitHub 仓库 main 分支 docs/web/assets）。
  var REPO_ASSETS =
    'https://raw.githubusercontent.com/jiangxiyu68-lab/Protect-your-neck/main/docs/web/assets/';

  // 默认候选源：每一项都是「assets 目录」的基地址，末尾带 /。
  // 2026-11-11 真实完整文件 ×3（上海电信；wasm / 模型，详见 改动说明.md 5.7）：
  //   gh-proxy.com  12.36 / 6.59 MB/s，identity（15.7MB，不压缩）
  //   ghproxy.net    2.64 / 2.06 MB/s，identity（15.7MB，不压缩）
  //   jsDelivr GH    3.78 / 11.12 MB/s，br（7.6MB）
  //   Pages 同源     3.60 / 4.43 MB/s，gzip（8.0MB）
  //   raw.github     不稳定（HEAD 1s、全量 GET 曾 15s 超时），不进默认列表
  //   fastly / moeyy 不可用或 >8s，已删除
  var MIRROR_BASES = [
    'https://gh-proxy.com/' + REPO_ASSETS,
    // jsDelivr 是 br 压缩（7.6MB），实测吞吐与 Pages 相当或更好；
    // ghproxy.net 不压缩（15.7MB）且本机只有 Pages 的 0.5–0.7 倍，降为第三。
    'https://cdn.jsdelivr.net/gh/jiangxiyu68-lab/Protect-your-neck@main/docs/web/assets/',
    'https://ghproxy.net/' + REPO_ASSETS,
  ];
  // 其它主机（本地 http.server、自有域名 / CDN）：同源优先，保持原行为。
  var DEFAULT_BASES = ['assets/'].concat(MIRROR_BASES);
  // GitHub Pages：同源就是 GitHub 本身，慢的时候排第一永远切不走，所以镜像优先，
  // 同源只作最后兜底。
  var GITHUB_PAGES_BASES = MIRROR_BASES.concat(['assets/']);

  // 页面是否部署在 GitHub Pages 上。
  // 测试开关（默认不生效）：
  //   URL 加 ?cgsrc=github        —— 本地也能走「镜像优先」分支；
  //   window.CG_FORCE_GHOST = true —— 在 config.js 之前设置，同上。
  function isGitHubPages() {
    if (window.CG_FORCE_GHOST === true) return true;
    try {
      var q = new URLSearchParams(window.location.search);
      if (q.get('cgsrc') === 'github') return true;
    } catch (e) { /* 老浏览器没有 URLSearchParams 时忽略测试开关 */ }
    var host = String(window.location.hostname || '').toLowerCase();
    return host === 'github.io' || host.slice(-10) === '.github.io';
  }

  var TASKS_VISION_VERSION = '0.10.20';
  var NPM_WASM_CDN = 'https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@' +
    TASKS_VISION_VERSION + '/wasm/';

  // 单文件官方兜底：仓库路径以后变了，wasm / 模型仍能从官方 CDN 拿到。
  // 顺序在候选源之后，只有前面全部失败才会用到。
  var FILE_FALLBACKS = {
    'wasm/vision_wasm_internal.js': [
      NPM_WASM_CDN + 'vision_wasm_internal.js',
      'https://unpkg.com/@mediapipe/tasks-vision@' + TASKS_VISION_VERSION + '/wasm/vision_wasm_internal.js',
    ],
    'wasm/vision_wasm_internal.wasm': [
      NPM_WASM_CDN + 'vision_wasm_internal.wasm',
      'https://unpkg.com/@mediapipe/tasks-vision@' + TASKS_VISION_VERSION + '/wasm/vision_wasm_internal.wasm',
    ],
    'wasm/vision_wasm_nosimd_internal.js': [
      NPM_WASM_CDN + 'vision_wasm_nosimd_internal.js',
      'https://unpkg.com/@mediapipe/tasks-vision@' + TASKS_VISION_VERSION + '/wasm/vision_wasm_nosimd_internal.js',
    ],
    'wasm/vision_wasm_nosimd_internal.wasm': [
      NPM_WASM_CDN + 'vision_wasm_nosimd_internal.wasm',
      'https://unpkg.com/@mediapipe/tasks-vision@' + TASKS_VISION_VERSION + '/wasm/vision_wasm_nosimd_internal.wasm',
    ],
    'pose_landmarker_lite.task': [
      'https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task',
    ],
  };

  // 文件清单（size 是未压缩字节数，用来算进度百分比；不用 content-length，
  // 因为 GitHub Pages / jsDelivr 会 gzip/br，content-length 是压缩后的长度）。
  var FILES = {
    module: { rel: 'vendor/vision_bundle.js', size: 138755, type: 'text/javascript' },
    wasmJs: { rel: 'wasm/vision_wasm_internal.js', size: 204284, type: 'text/javascript' },
    wasmBin: { rel: 'wasm/vision_wasm_internal.wasm', size: 9574032, type: 'application/wasm' },
    wasmJsNoSimd: { rel: 'wasm/vision_wasm_nosimd_internal.js', size: 204137, type: 'text/javascript' },
    wasmBinNoSimd: { rel: 'wasm/vision_wasm_nosimd_internal.wasm', size: 9448638, type: 'application/wasm' },
    model: { rel: 'pose_landmarker_lite.task', size: 5777746, type: 'application/octet-stream' },
  };

  // GitHub Pages 同源资源是 gzip 的；下面是 2026-11-11 实测的线路字节
  // （Content-Length，非解码后大小）。用来估算「留在镜像」与「切回 Pages」
  // 哪个剩余时间更短——只看固定 KB/s 会把压缩优势算丢。
  // 未实测 nosimd 版本，因此 nosimd 不参与按时间切换。
  var GITHUB_PAGES_WIRE_BYTES = {
    module: 40633,
    wasmJs: 50194,
    wasmBin: 2945420,
    model: 4957076,
  };

  var customBases = Array.isArray(window.ASSET_BASES)
    ? window.ASSET_BASES.filter(function (b) { return typeof b === 'string' && b; })
    : null;
  var singleBase = typeof window.ASSET_BASE === 'string' && window.ASSET_BASE
    ? window.ASSET_BASE
    : 'assets/';

  window.ASSET_BASE = singleBase;
  window.CG_ASSET_VERSION = window.CG_ASSET_VERSION || 'v1-20261111';

  window.CG_ASSET_CONFIG = {
    version: window.CG_ASSET_VERSION,
    // 页面默认基地址（保持改造前行为）
    base: singleBase,
    // 实际使用的候选源数组：显式 window.ASSET_BASES 优先；
    // 否则 GitHub Pages 用镜像优先，其它主机保持同源优先。
    bases: customBases && customBases.length
      ? customBases
      : (isGitHubPages() ? GITHUB_PAGES_BASES.slice() : DEFAULT_BASES.slice()),
    // 当前页面是否被判定为 GitHub Pages（测试开关也会置为 true）
    githubPages: isGitHubPages(),
    // 自定义候选源时是否仍保留官方单文件兜底
    allowFallbacks: true,
    timeoutMs: 8000,        // 单源拿到响应头的上限（需求：8s 回退）
    stallMs: 20000,         // 已开始下载但 20s 没有新数据，判为卡死并换源
    // 慢速切源（2026-11-11）：连得上、只是慢时，不能无限等同一个源。
    speedWindowMs: 4000,    // 每 4s 计算一次平均吞吐
    minKBps: 150,           // 非 Pages 主机：低于 150 KB/s 判为慢速（保留旧兜底）
    minElapsedMs: 1000,     // 收到首块数据后至少等 1s 再开始统计
    nearDoneBytes: 2097152, // 剩余不足 2MB 就让它下完，不为几秒重下
    // GitHub Pages 专用判据：按「当前吞吐估算剩余时间」，只有切回同源
    // 预计能省 >= switchSaveSec 秒才切。镜像不压缩（15.7MB），Pages gzip（8.0MB），
    // 固定 150 KB/s 在 300 KB/s 这种「不慢但也不快」的链路上会白等约 25 秒。
    githubPagesWireBytes: GITHUB_PAGES_WIRE_BYTES,
    switchSaveSec: 8,
    switchPenaltySec: 1,    // 换源额外 TTFB 的保守估计
    files: FILES,
    fallbacks: FILE_FALLBACKS,
    paths: {
      module: 'vendor/vision_bundle.js',
      wasmBase: 'wasm',
      model: 'pose_landmarker_lite.task',
    },
  };

  // index.html 只加一行 config.js；由这里把加载器挂上去，保持页面结构不动。
  // 若 assets-loader.js 缺失，app.js 会自动退回改造前的按需加载逻辑。
  (function loadImpl() {
    if (window.CGLoader) return;
    try {
      var self = document.currentScript;
      var src = self && self.src
        ? new URL('assets-loader.js', self.src).href
        : new URL('js/assets-loader.js', document.baseURI).href;
      var s = document.createElement('script');
      s.src = src;
      s.async = false;
      document.head.appendChild(s);
    } catch (e) {
      // 加载器挂不上不影响页面本身
      if (window.console && console.warn) console.warn('[config] assets-loader 注入失败', e);
    }
  })();
})();
