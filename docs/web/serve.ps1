#Requires -Version 5.1
<#
.SYNOPSIS
  颈椎守护 · 网页版 —— 一键启动本地静态服务器。

.DESCRIPTION
  为什么不能直接双击 index.html？
    浏览器只在「安全上下文」里允许网页访问摄像头：
      · http://localhost / http://127.0.0.1  → 算安全上下文 ✅
      · file://（双击打开）                  → 不算，getUserMedia 会被拒绝 ❌
      · http://192.168.x.x（手机局域网）      → 也不算，同样会被拒绝 ❌
  所以必须起一个本地服务器，再用 http://localhost:端口 打开。

  脚本优先用 Python 3（python -m http.server），没有 Python 就用 Node.js 内置 http 模块。
  两种方式都不需要联网、不需要安装任何依赖。

.PARAMETER Port
  监听端口，默认 8000。

.PARAMETER Lan
  绑定 0.0.0.0，让同一局域网的手机也能打开页面。
  注意：手机用 http://192.168.x.x 打开时仍然拿不到摄像头（非安全上下文），
  详见 README「手机上怎么用」。

.PARAMETER NoBrowser
  不自动打开浏览器。

.PARAMETER Node
  强制使用 Node.js 起服务（默认优先 Python）。

.EXAMPLE
  .\serve.ps1
  .\serve.ps1 -Port 8080
  .\serve.ps1 -Lan
#>
[CmdletBinding()]
param(
    [int]$Port = 8000,
    [switch]$Lan,
    [switch]$NoBrowser,
    [switch]$Node
)

$ErrorActionPreference = 'Stop'
$Root = $PSScriptRoot
$Bind = if ($Lan) { '0.0.0.0' } else { '127.0.0.1' }
$Url = "http://localhost:$Port/"

if (-not (Test-Path (Join-Path $Root 'index.html'))) {
    throw "当前目录不是网页版目录（找不到 index.html）：$Root"
}

# ── 端口占用检查 ─────────────────────────────────────────────────────────────
try {
    $occupied = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
    if ($occupied) {
        throw "端口 $Port 已被占用。换一个端口试试：.\serve.ps1 -Port 8080"
    }
} catch [System.Management.Automation.CommandNotFoundException] {
    # 极老系统没有 Get-NetTCPConnection，跳过检查
}

# ── 找一个可用的 Python 3 ────────────────────────────────────────────────────
function Find-Python3 {
    foreach ($name in @('python', 'py')) {
        $cmd = Get-Command $name -ErrorAction SilentlyContinue
        if (-not $cmd) { continue }
        try {
            $ver = & $name -c "import sys; print(sys.version_info[0])" 2>$null
            if ($LASTEXITCODE -eq 0 -and ("$ver").Trim() -eq '3') { return $name }
        } catch {
            # Windows 商店的 python 别名会抛错，继续试下一个
        }
    }
    return $null
}

$py = $null
if (-not $Node) { $py = Find-Python3 }

# 注意：变量名不能叫 $node —— PowerShell 变量名不区分大小写，会覆盖 [switch]$Node
$nodeExe = Get-Command node -ErrorAction SilentlyContinue
if (-not $py -and -not $nodeExe) {
    throw '没有找到 Python 3 或 Node.js。装任意一个即可：https://www.python.org/downloads/ 或 https://nodejs.org/'
}

# ── 打印使用说明 ─────────────────────────────────────────────────────────────
Write-Host ''
Write-Host '══════════════════════════════════════════════════════════' -ForegroundColor Cyan
Write-Host '  颈椎守护 · 网页版' -ForegroundColor Cyan
Write-Host '══════════════════════════════════════════════════════════' -ForegroundColor Cyan
Write-Host "  打开地址： $Url" -ForegroundColor Green
Write-Host "  网站目录： $Root"
Write-Host "  服务程序： $(if ($py) { "Python ($py -m http.server)" } else { 'Node.js (内置 http)' })"
Write-Host '  停止服务： Ctrl+C'
Write-Host ''
Write-Host '  必须用上面这个 localhost 地址打开：' -ForegroundColor Yellow
Write-Host '  · 直接双击 index.html（file://）浏览器不会给摄像头' -ForegroundColor Yellow
Write-Host '  · 手机用 http://192.168.x.x 也拿不到摄像头，见 README「手机上怎么用」' -ForegroundColor Yellow

if ($Lan) {
    Write-Host ''
    Write-Host '  已开启 -Lan（监听 0.0.0.0），局域网内可用以下地址打开页面：' -ForegroundColor Yellow
    try {
        $ips = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
            Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' } |
            Select-Object -ExpandProperty IPAddress -Unique
        foreach ($ip in $ips) { Write-Host "    http://$ip`:$Port/" }
    } catch {
        Write-Host '    （无法枚举本机 IP，可用 ipconfig 查看）'
    }
    Write-Host '  提醒：这些 http 地址不是安全上下文，摄像头仍会被拒绝。' -ForegroundColor Red
    Write-Host '  推荐：USB 连接手机后执行 adb reverse tcp:8000 tcp:8000，' -ForegroundColor Yellow
    Write-Host "        再在手机浏览器打开 $Url（手机上的 localhost 会转发到电脑）" -ForegroundColor Yellow
}
Write-Host ''
Write-Host '══════════════════════════════════════════════════════════' -ForegroundColor Cyan
Write-Host ''

# ── 自动打开浏览器（延迟一点，等服务器起来）────────────────────────────────
$openJob = $null
if (-not $NoBrowser) {
    $openJob = Start-Job -ScriptBlock {
        param($u)
        Start-Sleep -Milliseconds 900
        Start-Process $u
    } -ArgumentList $Url
}

try {
    if ($py) {
        & $py -m http.server $Port --bind $Bind --directory $Root
    } else {
        # 没有 Python：把一个小型静态服务器写到临时文件，用 Node 跑
        $serverJs = @'
'use strict';
const http = require('http');
const fs = require('fs');
const path = require('path');

const port = Number(process.argv[2]);
const host = process.argv[3];
const root = path.resolve(process.argv[4]);

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.htm': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.wasm': 'application/wasm',
  '.task': 'application/octet-stream',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
  '.map': 'application/json; charset=utf-8',
  '.txt': 'text/plain; charset=utf-8',
  '.md': 'text/markdown; charset=utf-8'
};

const server = http.createServer((req, res) => {
  let rel;
  try {
    const urlPath = decodeURIComponent((req.url || '/').split('?')[0].split('#')[0]);
    rel = urlPath === '/' ? 'index.html' : urlPath.replace(/^\/+/, '');
  } catch (e) {
    res.writeHead(400, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end('400 Bad Request');
    return;
  }
  let file = path.resolve(root, rel);
  if (file !== root && !file.startsWith(root + path.sep)) {
    res.writeHead(403, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end('403 Forbidden');
    return;
  }
  fs.stat(file, (err, st) => {
    if (err) {
      res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end('404 Not Found: /' + rel);
      return;
    }
    if (st.isDirectory()) file = path.join(file, 'index.html');
    fs.readFile(file, (e2, buf) => {
      if (e2) {
        res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
        res.end('404 Not Found: /' + rel);
        return;
      }
      const ext = path.extname(file).toLowerCase();
      res.writeHead(200, {
        'Content-Type': MIME[ext] || 'application/octet-stream',
        'Content-Length': buf.length,
        'Cache-Control': 'no-cache'
      });
      res.end(buf);
      const size = buf.length > 1048576 ? (buf.length / 1048576).toFixed(1) + ' MB' : buf.length + ' B';
      console.log('  ' + new Date().toLocaleTimeString() + '  ' + req.method + ' /' + rel + '  ' + size);
    });
  });
});

server.on('error', (e) => {
  console.error('服务器启动失败：' + e.message);
  process.exit(1);
});

server.listen(port, host, () => {
  const shown = host === '0.0.0.0' ? 'localhost' : host;
  console.log('  Node 静态服务器已启动： http://' + shown + ':' + port + '/');
  console.log('  网站目录： ' + root);
  console.log('  按 Ctrl+C 停止');
});
'@
        $tmp = Join-Path ([System.IO.Path]::GetTempPath()) 'cervical-guard-serve.js'
        [System.IO.File]::WriteAllText($tmp, $serverJs, (New-Object System.Text.UTF8Encoding($false)))
        & node $tmp $Port $Bind $Root
        Remove-Item $tmp -Force -ErrorAction SilentlyContinue
    }
} finally {
    if ($openJob) {
        Remove-Job $openJob -Force -ErrorAction SilentlyContinue
    }
}
