/* ============================================================================
 * js/assets-loader.js — 首屏资源预取 / 进度 / 持久缓存（只动加载层）
 * ----------------------------------------------------------------------------
 * 职责（对应本次需求四条）：
 *   1. DOMContentLoaded 后立刻后台下载 wasm + 模型，不等用户点「开启摄像头」；
 *   2. 显示「首次加载 15.7MB · 已下载 x.x MB / xx%」，失败给「重试」；
 *   3. Cache Storage 按版本号缓存；第二次打开 0 网络字节直接命中；
 *   4. 所有 wasm / 模型 URL 由 js/config.js 的候选源拼出，8s 超时按序回退。
 *
 * 不注册 Service Worker，不接管页面导航；缓存只由本文件读写。
 * 下载完成后把 Blob 交给 app.js，MediaPipe 从 blob: URL 取 wasm / 模型，
 * 所以第二次打开即使完全断网也不会再发请求。
 * ============================================================================ */
(function () {
  'use strict';

  var CFG = window.CG_ASSET_CONFIG;
  if (!CFG) return;

  var cacheSupported = (typeof caches !== 'undefined' && caches && typeof caches.open === 'function');
  var CACHE_NAME = 'cg-assets-' + CFG.version;
  var KEY_ROOT = new URL('__cg_assets__/' + encodeURIComponent(CFG.version) + '/', document.baseURI).href;

  var state = {
    phase: 'idle',          // idle | loading | ready | error
    promise: null,
    entries: null,          // { moduleUrl, wasmJsUrl, wasmBinUrl, modelUrl, ... }
    detail: [],
    downloaded: 0,      // 本次真正走网络的字节
    cachedBytes: 0,     // 从 Cache Storage 命中的字节
    total: 0,
    startedAt: 0,
    finishedAt: 0,
    fromCache: false,
    noStream: false,
    error: null,
    retryAt: 0,
    activeEntries: null,   // 本次加载的文件列表（用于按当前尝试重算总进度）
    switches: [],          // 每次换源记录，供 stats 查看
  };

  // ─────────────────────────── 工具 ───────────────────────────
  function mb(bytes) {
    return (bytes / 1000000).toFixed(1);
  }
  function pct(done, total) {
    if (!total) return null;
    return Math.max(0, Math.min(100, Math.round(done / total * 100)));
  }
  // 进度基准：downloaded 始终等于「各文件当前这次尝试已收字节」之和。
  // 换源时把作废尝试的 bytes 归零再重算，绝不出现超过 100% 或负数。
  function recomputeDownloaded() {
    var list = state.activeEntries || [];
    var sum = 0;
    for (var i = 0; i < list.length; i++) {
      sum += Math.max(0, Number(list[i].bytes) || 0);
    }
    state.downloaded = sum;
  }
  function joinUrl(base, rel) {
    if (!base) return rel;
    return base.charAt(base.length - 1) === '/' ? base + rel : base + '/' + rel;
  }
  function keyFor(entry) {
    return KEY_ROOT + encodeURIComponent(entry.rel.replace(/\//g, '__'));
  }
  function supportsSimd() {
    // 与 vision_bundle 内部使用的检测字节完全一致
    try {
      return WebAssembly.validate(new Uint8Array([
        0, 97, 115, 109, 1, 0, 0, 0, 1, 5, 1, 96, 0, 1, 123, 3, 2, 1, 0,
        10, 10, 1, 8, 0, 65, 0, 253, 15, 253, 98, 11,
      ]));
    } catch (e) {
      return false;
    }
  }

  function buildEntries() {
    var f = CFG.files;
    var simd = supportsSimd();
    return [
      { key: 'module', rel: f.module.rel, size: f.module.size, type: f.module.type },
      { key: 'wasmJs', rel: (simd ? f.wasmJs : f.wasmJsNoSimd).rel, size: (simd ? f.wasmJs : f.wasmJsNoSimd).size, type: 'text/javascript' },
      { key: 'wasmBin', rel: (simd ? f.wasmBin : f.wasmBinNoSimd).rel, size: (simd ? f.wasmBin : f.wasmBinNoSimd).size, type: 'application/wasm' },
      { key: 'model', rel: f.model.rel, size: f.model.size, type: f.model.type },
    ];
  }

  function candidatesFor(entry) {
    var list = CFG.bases.map(function (base) { return joinUrl(base, entry.rel); });
    if (CFG.allowFallbacks !== false && CFG.fallbacks && CFG.fallbacks[entry.rel]) {
      list = list.concat(CFG.fallbacks[entry.rel]);
    }
    return list.filter(function (u, i, a) { return u && a.indexOf(u) === i; });
  }

  // 同源 assets/ 就是 GitHub Pages 自己的静态资源（也是唯一有 gzip 的候选）。
  function isSameOriginAsset(url) {
    try {
      var u = new URL(url, location.href);
      return u.origin === location.origin && /\/assets\//.test(u.pathname);
    } catch (e) {
      return false;
    }
  }

  // ─────────────────────────── 进度 UI（JS 注入，不改原页面版式） ───────────────────────────
  var ui = null;
  var uiTimer = 0;

  function ensureUI() {
    if (ui || !document.body) return ui;
    var bar = document.createElement('div');
    bar.id = 'cgLoadBar';
    bar.setAttribute('role', 'status');
    bar.setAttribute('aria-live', 'polite');
    bar.style.cssText = [
      'position:fixed', 'left:50%', 'bottom:18px', 'transform:translateX(-50%)',
      'z-index:10000', 'width:min(92vw,460px)', 'box-sizing:border-box',
      'padding:10px 12px 9px', 'border-radius:14px',
      'background:var(--card,#171c22)', 'color:var(--text,#ecf1f6)',
      'border:1px solid var(--line,#2c3540)', 'box-shadow:var(--shadow-soft,0 8px 24px rgba(0,0,0,.22))',
      'font:13px/1.45 -apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC","Microsoft YaHei",sans-serif',
      'display:none',
    ].join(';');

    var row = document.createElement('div');
    row.style.cssText = 'display:flex;align-items:center;gap:10px';

    var text = document.createElement('div');
    text.id = 'cgLoadText';
    text.style.cssText = 'flex:1;min-width:0;white-space:nowrap;overflow:hidden;text-overflow:ellipsis';
    text.textContent = '准备加载…';

    var retry = document.createElement('button');
    retry.id = 'cgLoadRetry';
    retry.type = 'button';
    retry.textContent = '重试';
    retry.hidden = true;
    retry.style.cssText = [
      'flex:none', 'height:26px', 'padding:0 12px', 'border-radius:8px', 'cursor:pointer',
      'border:1px solid var(--primary,#2e7df6)', 'background:var(--primary-soft,rgba(46,125,246,.14))',
      'color:var(--primary-strong,#4b90ff)', 'font:12px/1 inherit',
    ].join(';');
    retry.addEventListener('click', function () { retryLoad(); });

    var track = document.createElement('div');
    track.style.cssText = 'margin-top:7px;height:3px;border-radius:2px;background:var(--line-soft,#232b34);overflow:hidden';
    var fill = document.createElement('i');
    fill.id = 'cgLoadFill';
    fill.style.cssText = 'display:block;height:100%;width:0%;border-radius:2px;background:var(--primary,#2e7df6);transition:width .18s linear';
    track.appendChild(fill);

    row.appendChild(text);
    row.appendChild(retry);
    bar.appendChild(row);
    bar.appendChild(track);
    document.body.appendChild(bar);

    ui = { bar: bar, text: text, retry: retry, fill: fill };
    return ui;
  }

  function showUI() {
    var u = ensureUI();
    if (!u) return;
    u.bar.style.display = 'block';
    u.retry.hidden = true;
  }

  function render() {
    var u = ensureUI();
    if (!u) return;
    var total = state.total;
    var done = Math.min(state.downloaded + state.cachedBytes, total || (state.downloaded + state.cachedBytes));
    var p = pct(done, total);

    if (state.phase === 'error') {
      u.text.textContent = '资源加载失败 · 已下载 ' + mb(done) + ' MB';
      u.retry.hidden = false;
      u.fill.style.background = 'var(--danger,#ff453a)';
      u.fill.style.width = (p === null ? 100 : p) + '%';
      return;
    }
    if (state.phase === 'ready') {
      u.text.textContent = state.fromCache
        ? '资源已缓存 · 0 MB 下载，直接启动'
        : '首次加载完成 · ' + mb(total) + 'MB 已缓存';
      u.retry.hidden = true;
      u.fill.style.background = 'var(--safe,#34c759)';
      u.fill.style.width = '100%';
      return;
    }

    u.retry.hidden = true;
    u.fill.style.background = 'var(--primary,#2e7df6)';
    if (state.noStream) {
      u.text.textContent = '首次加载 ' + mb(total) + 'MB · 加载中…';
      u.fill.style.width = '35%';
      return;
    }
    if (p === null) {
      u.text.textContent = '首次加载 · 已下载 ' + mb(done) + ' MB';
      u.fill.style.width = '35%';
      return;
    }
    u.text.textContent = '首次加载 ' + mb(total) + 'MB · 已下载 ' + mb(done) + ' MB / ' + p + '%';
    u.fill.style.width = p + '%';
  }

  function scheduleRender() {
    if (uiTimer) return;
    uiTimer = setTimeout(function () {
      uiTimer = 0;
      render();
    }, 80);
  }

  function hideUISoon(ms) {
    setTimeout(function () {
      if (state.phase === 'ready' && ui) ui.bar.style.display = 'none';
    }, ms || 1200);
  }

  // ─────────────────────────── 缓存 ───────────────────────────
  function readCache(entry) {
    if (!cacheSupported) return Promise.resolve(null);
    return caches.open(CACHE_NAME).then(function (cache) {
      return cache.match(keyFor(entry));
    }).then(function (res) {
      return res ? res.blob() : null;
    }).catch(function (e) {
      console.warn('[CGLoader] 读缓存失败', e);
      return null;
    });
  }

  function writeCache(entry, blob, source) {
    if (!cacheSupported) return Promise.resolve();
    return caches.open(CACHE_NAME).then(function (cache) {
      return cache.put(keyFor(entry), new Response(blob, {
        headers: {
          'Content-Type': entry.type,
          'Content-Length': String(blob.size),
          'X-CG-Source': source || '',
        },
      }));
    }).catch(function (e) {
      console.warn('[CGLoader] 写缓存失败（本次仍可用）', e);
    });
  }

  function clearCache() {
    if (!cacheSupported) return Promise.resolve(false);
    return caches.delete(CACHE_NAME);
  }

  // ─────────────────────────── 下载 ───────────────────────────
  function fetchWithStream(url, entry, opts) {
    opts = opts || {};
    var pagesWire = opts.pagesWireBytes || 0;
    var hasFallback = opts.hasFallback !== false;
    var ctrl = new AbortController();
    var settled = false;
    var stallTimer = 0;
    var speedTimer = 0;
    var firstCheckTimer = 0;
    var firstChunkAt = 0;
    var watchdogStarted = false;
    var abortReason = '';
    var abortKBps = 0;
    var abortSavings = 0;
    var received = 0;
    var expectedBytes = entry.size; // 解码后的总字节；进度和剩余时间都用它
    var wireTotal = entry.size;     // 线路字节（gzip/br 时小于 expectedBytes）
    var windowStartBytes = 0;
    var windowStartAt = 0;

    function clearTimers() {
      clearTimeout(headerTimer);
      clearTimeout(stallTimer);
      clearTimeout(firstCheckTimer);
      clearInterval(speedTimer);
    }
    function abort(reason) {
      if (reason && !abortReason) abortReason = reason;
      try { ctrl.abort(); } catch (e) { /* ignore */ }
    }
    var headerTimer = setTimeout(function () { abort('timeout'); }, CFG.timeoutMs);

    // 慢速切源：收到首块数据后等 minElapsedMs，之后每 speedWindowMs
    // 用窗口内收到的线路字节 / 实际窗口时长 算平均吞吐。
    function startSpeedWatch() {
      if (watchdogStarted) return;
      watchdogStarted = true;
      firstCheckTimer = setTimeout(function () {
        windowStartBytes = received;
        windowStartAt = performance.now();
        speedTimer = setInterval(checkSpeed, CFG.speedWindowMs);
      }, CFG.minElapsedMs);
    }

    function checkSpeed() {
      if (settled || abortReason || !windowStartAt) return;
      var now = performance.now();
      var dt = now - windowStartAt;
      if (dt < 1000) return;
      var delta = received - windowStartBytes;
      var avgKBps = delta * 1000 / dt / 1024;
      var decodedSpeed = delta * 1000 / dt; // B/s，解码后
      windowStartBytes = received;
      windowStartAt = now;
      var remain = expectedBytes - received;
      if (remain <= CFG.nearDoneBytes || decodedSpeed <= 0) return;

      // GitHub Pages：按当前吞吐估算「留在镜像」vs「切回同源」的剩余时间。
      // 镜像通常不压缩（15.7MB），同源 gzip（约 8.0MB）；只有预计能省
      // switchSaveSec 秒才切，避免 300 KB/s 这种「不慢但也不快」白等。
      if (pagesWire > 0) {
        var wireSpeed = decodedSpeed * (wireTotal / entry.size); // B/s，线路
        if (wireSpeed > 0) {
          var currentSec = remain / decodedSpeed;
          var pagesSec = pagesWire / wireSpeed + (CFG.switchPenaltySec || 0);
          if (currentSec - pagesSec >= CFG.switchSaveSec) {
            abortKBps = avgKBps;
            abortSavings = Math.round(currentSec - pagesSec);
            console.warn('[CGLoader] 预计切回 Pages 可省 ' + abortSavings +
              's（当前 ' + avgKBps.toFixed(1) + ' KB/s），换源：' + url);
            abort('pages-savings');
            return;
          }
        }
      }

      // 非 Pages 主机保留原固定阈值兜底；GitHub Pages 已用上面的剩余时间判据，
      // 不再用固定 150 KB/s（否则会把压缩镜像误判成慢、切到更慢的源）。
      // 最后一个候选不再中断，避免快下完还重来。
      if (hasFallback && !(CFG.githubPages === true && pagesWire > 0) &&
          avgKBps < CFG.minKBps) {
        abortKBps = avgKBps;
        console.warn('[CGLoader] 下载过慢 ' + avgKBps.toFixed(1) + ' KB/s，换源：' + url);
        abort('slow');
      }
    }

    return fetch(url, {
      signal: ctrl.signal,
      mode: 'cors',
      credentials: 'omit',
      cache: 'no-store',
    }).then(function (res) {
      clearTimeout(headerTimer);
      if (!res.ok) {
        abort('error');
        throw new Error('HTTP ' + res.status);
      }

      // Content-Length 是线路字节（gzip/br 时更小），只用来估算线路吞吐；
      // 进度和剩余时间仍用解码后的 expectedBytes。
      var cl = parseInt(res.headers.get('content-length'), 10);
      if (cl > 0) wireTotal = cl;

      function armStall() {
        clearTimeout(stallTimer);
        stallTimer = setTimeout(function () {
          console.warn('[CGLoader] 下载停滞，换源：' + url);
          abort('stall');
        }, CFG.stallMs);
      }
      armStall();

      var reader = null;
      try {
        if (res.body && typeof res.body.getReader === 'function') reader = res.body.getReader();
      } catch (e) {
        reader = null;
      }
      if (!reader) {
        // Safari 老版本 / 无流式读取：降级成「加载中…」，拿到 blob 再算总量
        state.noStream = true;
        scheduleRender();
        return res.blob().then(function (blob) {
          clearTimers();
          entry.bytes = blob.size;
          recomputeDownloaded();
          scheduleRender();
          return blob;
        });
      }

      var chunks = [];
      entry.bytes = 0;
      recomputeDownloaded();

      function pump() {
        return reader.read().then(function (r) {
          if (r.done) {
            clearTimers();
            return new Blob(chunks, { type: entry.type });
          }
          chunks.push(r.value);
          received += r.value.length;
          entry.bytes = received;
          recomputeDownloaded();
          scheduleRender();
          armStall();
          if (!firstChunkAt) {
            firstChunkAt = performance.now();
            var fb = Math.round(firstChunkAt - state.startedAt);
            if (entry.firstByteMs == null) entry.firstByteMs = fb;
            entry.lastFirstByteMs = fb;
            startSpeedWatch();
          }
          return pump();
        });
      }
      return pump();
    }).then(function (blob) {
      settled = true;
      clearTimers();
      // 代理返回 200 的错误页 / 半截文件：体积明显不对就换下一个源
      if (blob.size < entry.size * 0.5) {
        throw new Error('文件体积异常（' + blob.size + ' < ' + entry.size + '）');
      }
      return blob;
    }).catch(function (err) {
      clearTimers();
      if (settled) throw err;
      var out = err instanceof Error ? err : new Error(String(err));
      if (abortReason) {
        out = new Error('源中断（' + abortReason + '）：' + url);
        out.cgReason = abortReason;
        out.avgKBps = abortKBps;
        out.savingsSec = abortSavings;
        out.cause = err;
      } else if (!out.cgReason) {
        out.cgReason = 'error';
        out.avgKBps = 0;
      }
      throw out;
    });
  }

  function fetchEntry(entry) {
    var candidates = candidatesFor(entry);
    var index = 0;
    // 同源 assets/ 的位置：GitHub Pages 上它是 gzip 的，也是按时间估算时
    // 要比较的「切回同源」目标。
    var pagesIndex = -1;
    for (var pi = 0; pi < candidates.length; pi++) {
      if (isSameOriginAsset(candidates[pi])) { pagesIndex = pi; break; }
    }

    function next(lastErr) {
      if (index >= candidates.length) {
        var err = new Error(entry.rel + ' 所有资源源都不可用');
        err.code = 'ASSET';
        err.detail = lastErr ? String(lastErr.message || lastErr) : '';
        throw err;
      }
      var url = candidates[index++];
      var currentIndex = index - 1;
      var pagesJump = !!(CFG.githubPages === true && pagesIndex > currentIndex &&
        !isSameOriginAsset(url) && !/nosimd/.test(entry.rel) && CFG.githubPagesWireBytes &&
        CFG.githubPagesWireBytes[entry.key] > 0);
      var t0 = performance.now();
      entry.attempts = (entry.attempts || 0) + 1;
      entry.bytes = 0;
      recomputeDownloaded();
      return fetchWithStream(url, entry, {
        pagesWireBytes: pagesJump ? CFG.githubPagesWireBytes[entry.key] : 0,
        hasFallback: index < candidates.length,
      }).then(function (blob) {
        entry.source = url;
        entry.ms = Math.round(performance.now() - t0);
        return blob;
      }).catch(function (err) {
        // 失败源的半截字节要从总进度里扣掉，避免百分比虚高
        entry.bytes = 0;
        recomputeDownloaded();
        scheduleRender();
        var reason = err && err.cgReason ? err.cgReason : 'error';
        var avg = err && typeof err.avgKBps === 'number' ? err.avgKBps : 0;
        var savings = err && typeof err.savingsSec === 'number' ? err.savingsSec : 0;
        // 预计省得够多时，直接跳到同源 Pages，不在其它镜像上继续耗 5 秒
        if (reason === 'pages-savings' && pagesIndex >= 0) {
          index = pagesIndex;
        }
        var nextUrl = candidates[index] || null;
        if (nextUrl) {
          state.switches.push({
            file: entry.rel,
            from: url,
            to: nextUrl,
            reason: reason,
            avgKBps: Math.round(avg * 10) / 10,
            savingsSec: savings || null,
            atMs: Math.round(performance.now() - state.startedAt),
          });
        }
        console.warn('[CGLoader] 源失败，换下一个：' + url + '（' + reason + '）', err && err.message);
        return next(err);
      });
    }
    return next(null);
  }

  // ─────────────────────────── 主流程 ───────────────────────────
  function ensure() {
    if (state.entries) return Promise.resolve(state.entries);
    if (state.promise) return state.promise;

    var entries = buildEntries();
    state.activeEntries = entries;
    state.switches = [];
    state.phase = 'loading';
    state.error = null;
    state.fromCache = false;
    state.noStream = false;
    state.detail = [];
    state.downloaded = 0;
    state.cachedBytes = 0;
    state.total = entries.reduce(function (s, e) { return s + e.size; }, 0);
    state.startedAt = performance.now();
    showUI();
    render();

    var results = {};
    var jobs = entries.map(function (entry) {
      entry.startedAt = performance.now();
      return readCache(entry).then(function (blob) {
        if (blob && blob.size >= entry.size * 0.5) {
          entry.cached = true;
          entry.source = 'cache';
          entry.ms = Math.round(performance.now() - entry.startedAt);
          state.cachedBytes += blob.size;
          results[entry.key] = blob;
          state.detail.push({ rel: entry.rel, size: blob.size, cached: true, source: 'cache', ms: entry.ms });
          scheduleRender();
          return null;
        }
        entry.cached = false;
        return fetchEntry(entry).then(function (fresh) {
          results[entry.key] = fresh;
          state.detail.push({
            rel: entry.rel,
            size: fresh.size,
            cached: false,
            source: entry.source,
            ms: entry.ms,
            attempts: entry.attempts || 1,
            firstByteMs: entry.firstByteMs || null,
            lastFirstByteMs: entry.lastFirstByteMs || null,
          });
          return writeCache(entry, fresh, entry.source);
        });
      });
    });

    var p = Promise.all(jobs).then(function () {
      var out = { fromCache: true, bytes: 0, durationMs: 0, version: CFG.version };
      entries.forEach(function (entry) {
        var blob = results[entry.key];
        out[entry.key + 'Url'] = URL.createObjectURL(blob);
        out[entry.key + 'Size'] = blob.size;
        out[entry.key + 'Cached'] = !!entry.cached;
        out.bytes += blob.size;
        if (!entry.cached) out.fromCache = false;
      });
      out.detail = state.detail.slice();
      out.durationMs = Math.round(performance.now() - state.startedAt);
      state.entries = out;
      state.fromCache = out.fromCache;
      state.phase = 'ready';
      state.finishedAt = performance.now();
      render();
      hideUISoon(1200);
      console.info('[CGLoader] ready', {
        fromCache: out.fromCache,
        bytes: out.bytes,
        durationMs: out.durationMs,
        detail: out.detail,
      });
      return out;
    }).catch(function (err) {
      state.phase = 'error';
      state.error = err;
      console.error('[CGLoader] 加载失败', err);
      render();
      throw err;
    });

    state.promise = p;
    p.then(function () { state.promise = null; }, function () { state.promise = null; });
    return p;
  }

  // 模型初始化失败（下载已完成）时，由 app.js 调用，界面切到「失败 + 重试」
  function showError(message) {
    if (state.phase === 'loading') return;
    state.phase = 'error';
    state.error = new Error(message || '加载失败');
    showUI();
    render();
  }

  function retryLoad() {
    if (state.phase === 'loading') return Promise.resolve();
    // 释放上一轮 Blob URL，避免重试时累积内存
    if (state.entries) {
      ['moduleUrl', 'wasmJsUrl', 'wasmBinUrl', 'modelUrl'].forEach(function (k) {
        if (state.entries[k]) {
          try { URL.revokeObjectURL(state.entries[k]); } catch (e) { /* ignore */ }
        }
      });
    }
    state.phase = 'idle';
    state.promise = null;
    state.entries = null;
    state.error = null;
    state.retryAt = performance.now();
    showUI();
    var u = ensureUI();
    if (u) {
      u.text.textContent = '正在重试…';
      u.retry.hidden = true;
      u.fill.style.width = '0%';
    }
    var p = ensure();
    p.then(function () {
      if (typeof window.CGLoader.onRetry === 'function') {
        try { window.CGLoader.onRetry(); } catch (e) { console.warn('[CGLoader] onRetry 回调失败', e); }
      }
    }, function () { /* 错误 UI 已显示 */ });
    return p;
  }

  function prefetch() {
    if (state.phase === 'ready' || state.phase === 'loading') return state.promise || Promise.resolve(state.entries);
    return ensure().catch(function (e) {
      // 预取失败不抛到全局；用户点开始时 ensure() 会再试，并显示「重试」
      console.warn('[CGLoader] 预取未完成', e && e.message);
    });
  }

  // ─────────────────────────── 对外接口 ───────────────────────────
  window.CGLoader = {
    ensure: ensure,
    prefetch: prefetch,
    retry: retryLoad,
    clearCache: clearCache,
    showError: showError,
    onRetry: null,
    get ready() { return !!state.entries; },
    get stats() {
      return {
        phase: state.phase,
        version: CFG.version,
        cacheName: CACHE_NAME,
        cacheSupported: cacheSupported,
        totalBytes: state.total,
        downloadedBytes: state.downloaded,
        cachedBytes: state.cachedBytes,
        fromCache: state.fromCache,
        startedAtMs: state.startedAt || null,
        finishedAtMs: state.finishedAt || null,
        durationMs: state.phase === 'ready'
          ? Math.round(state.finishedAt - state.startedAt)
          : (state.startedAt ? Math.round(performance.now() - state.startedAt) : 0),
        detail: state.detail.slice(),
        sources: {
          mode: CFG.githubPages ? 'github-pages-mirror-first' : 'same-origin-first',
          bases: CFG.bases.slice(),
          final: state.detail.map(function (d) {
            return {
              file: d.rel,
              source: d.source,
              cached: !!d.cached,
              ms: d.ms,
              attempts: d.attempts || (d.cached ? 0 : 1),
              firstByteMs: d.firstByteMs || null,
              lastFirstByteMs: d.lastFirstByteMs || null,
            };
          }),          switches: state.switches.slice(),
        },
        switches: state.switches.slice(),
        error: state.error ? String(state.error.message || state.error) : null,
      };
    },
  };

  // 页面一打开就预取：DOMContentLoaded 后立刻开始，不等用户点「开启摄像头」
  function start() {
    prefetch();
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', start, { once: true });
  } else {
    setTimeout(start, 0);
  }
})();

