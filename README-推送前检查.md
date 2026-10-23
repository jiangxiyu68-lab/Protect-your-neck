# 推送前检查（Push 前 5 分钟必看）

这个目录**已经 `git init` 并做好了首次 commit**，你现在只差「建远程仓库 + push」。
但在 push 之前，**先做完下面 5 项检查**，全部通过再推。

> 所有命令都在 **PowerShell** 里、在 `publish\repo-ready` 目录下执行。
> 先进目录：`cd C:\Users\16269\WorkBuddy\2026-10-02-21-07-03\publish\repo-ready`

---

## ✅ 检查 1：密钥没有进仓库（最重要）

```powershell
git ls-files | Select-String "keystore|\.jks|\.apk|local\.properties|/build/"
```

**预期输出：什么都没有**（光标直接回到下一行）。

只要看到任何一行，**立刻停下**，别 push。处理办法：

```powershell
# 如果只是被 add 了还没 commit
git rm --cached 那个文件名

# 如果已经 commit 了（还在本地没推）
git rm --cached 那个文件名
git commit --amend --no-edit
```

> 为什么这么严：`keystore` 泄露 = 别人能签出和你一模一样的包冒充你发版；
> `local.properties` 泄露 = 你电脑的目录结构被公开（小事，但不该进仓库）。

---

## ✅ 检查 2：仓库体积正常（应该 < 10 MB）

```powershell
git count-objects -vH
```

**预期**：`size-pack` 或 `size` 在 **10 MB 以内**（本项目实际约 9 MB，其中 5.6 MB 是姿态模型文件）。

如果明显偏大（比如 30 MB+），八成是那个 33 MB 的 APK 被误加了：

```powershell
# 看看哪些大文件被跟踪了
git ls-files | ForEach-Object { [PSCustomObject]@{ 文件=$_; MB=[math]::Round((Get-Item $_).Length/1MB,2) } } |
  Sort-Object MB -Descending | Select-Object -First 10
```

APK 正确做法是走 Releases 附件，见 `dist\README.md`。

---

## ✅ 检查 3：分支名是 `main`（不是 `master`）

```powershell
git branch
```

**预期输出**：
```
* main
```

如果显示 `* master`，改名：

```powershell
git branch -M main
```

> GitHub 新建仓库默认分支是 `main`，本地如果是 `master`，push 上去会变成两条并存的分支，以后容易搞混。

---

## ✅ 检查 4：还没配远程地址（配了但没推也算正常）

```powershell
git remote -v
```

**预期输出：什么都没有**（这个仓库我只做了本地 commit，没配任何远程）。

确认没问题后再加远程（`USERNAME` 换成你的 GitHub 用户名）：

```powershell
git remote add origin https://github.com/USERNAME/cervical-guard.git
git remote -v
```

**加完的预期输出**：
```
origin  https://github.com/USERNAME/cervical-guard.git (fetch)
origin  https://github.com/USERNAME/cervical-guard.git (push)
```

---

## ✅ 检查 5：PAT 准备好了（push 会要你输账号密码）

**GitHub 从 2021 年起不接受账号密码**，push 时弹窗要的「密码」必须是
**Personal Access Token（PAT）**，一串 `github_pat_xxxxxxxx` 的东西。

- **用户名**：你的 GitHub 用户名（不是邮箱）
- **密码**：粘贴 PAT（`Ctrl+V` 粘贴时**屏幕上不显示任何字符**，这是正常的，粘完直接回车）

没有 PAT 就先建一个，**逐步点击路径见教程第 4 节**（`..\github\教程-从零建仓.md`）。

> 第一次 push 成功后 Windows 会弹「Git Credential Manager」保存凭据，之后就不用再输了。
> 如果输错了想重来：控制面板 → 凭据管理器 → Windows 凭据 → 删掉 `git:https://github.com`。

---

## ⚠️ 另外两项（不是安全检查，但不做会出错）

### A. 把占位作者改成你自己

首次 commit 我用的是**占位身份** `CervicalGuard <dev@example.com>`（只写进了本仓库的 `.git/config`，没动你的全局配置）。
不改的话，GitHub 上会显示这个陌生名字。改成你自己的：

```powershell
git config user.name  "你的名字"
git config user.email "你的邮箱"

# 让已有的首次 commit 也换成新作者
git commit --amend --reset-author --no-edit
```

> **email 建议填你 GitHub 账号绑定的邮箱**（或 GitHub 提供的 `12345678+用户名@users.noreply.github.com` 隐私邮箱）。
> 填错的话提交不会关联到你的头像，GitHub 会显示成「未关联的提交」。

验证：

```powershell
git log -1 --format="作者: %an <%ae>%n提交: %h %s"
```

### B. 把 `USERNAME` 占位符替换成你的用户名

`README.md` 和 `docs\index.html` 里有 `USERNAME` 占位符（下载链接用），不换会 404：

```powershell
$u   = "你的GitHub用户名"
$enc = New-Object System.Text.UTF8Encoding $false   # $false = 不写 BOM，避免文件开头多个隐形字符

foreach ($f in "README.md", "docs\index.html") {
  $p = Join-Path (Get-Location) $f
  [System.IO.File]::WriteAllText($p, ([System.IO.File]::ReadAllText($p)).Replace('USERNAME', $u), $enc)
  Write-Host "已处理 $f"
}

# 验证：应该没有任何输出（有输出说明还有没替换的）
Select-String -Path README.md, docs\index.html -Pattern 'USERNAME'
```

> 一共 **5 处**：`README.md` 3 处、`docs\index.html` 2 处。
> 别用 `Set-Content -Encoding UTF8` —— Windows PowerShell 5.1 会在文件开头写 BOM（`EF BB BF`），
> 网页上可能多出一个隐形字符。

改完这两项后重新提交（会覆盖刚才的首次 commit，这很正常）：

```powershell
git add README.md docs\index.html
git commit --amend --no-edit
```

---

## 全部通过后，只剩两步

```powershell
# 1. 建远程仓库（网页操作，见教程第 4 步），然后配地址
git remote add origin https://github.com/你的用户名/cervical-guard.git

# 2. 推送
git push -u origin main
```

**成功时应该看到**：
```
Enumerating objects: 78, done.
...
To https://github.com/你的用户名/cervical-guard.git
 * [new branch]      main -> main
branch 'main' set up to track 'origin/main'.
```

推送后打开 `https://github.com/你的用户名/cervical-guard`，能看到 README 和截图就成功了。

---

## 当前仓库状态（我准备时的实测结果）

| 项目 | 值 |
|---|---|
| 首次 commit | 见 `git log --oneline` |
| 分支 | `main` |
| 文件数 | 见 `git ls-files | Measure-Object` |
| 仓库体积 | 约 9 MB |
| 密钥泄露检查 | 通过（无命中） |
| 远程地址 | **未配置**（等你加） |
| 大文件 | 33 MB APK **未入库**，走 Releases |

---

## 出问题了？

| 现象 | 原因 | 解决 |
|---|---|---|
| `fatal: not a git repository` | 不在仓库目录里 | `cd publish\repo-ready` |
| `remote origin already exists` | 之前配过 | `git remote set-url origin 新地址` |
| `Authentication failed` | PAT 错了 / 用了账号密码 | 重新建 PAT，删掉旧凭据重试 |
| `rejected ... non-fast-forward` | 远程有本地没有的提交（通常是建仓时勾了 Add README） | `git pull origin main --rebase` 再 push |
| `error: src refspec main does not match any` | 分支名不对或没有 commit | `git branch -M main` |
| `filename too long` | Windows 路径长度限制 | `git config --global core.longpaths true` |

更完整的报错对照表见教程第 8 节。
