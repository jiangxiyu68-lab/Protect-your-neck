# dist/ —— 安装包为什么不在仓库里

**这里没有 APK，是故意的。**

## APK 去哪了

安装包通过 **GitHub Releases** 分发，不放进 git 仓库。

打开 Releases 页面下载最新版：

```
https://github.com/USERNAME/cervical-guard/releases
```

> 把 `USERNAME` 换成你的 GitHub 用户名。

## 为什么不放进仓库

| 原因 | 说明 |
|---|---|
| **体积** | 这个 APK 有 33 MB。git 会为每个版本永久保存一份完整副本，发 10 个版本就是 330 MB，clone 一次要等很久 |
| **删不掉** | 一旦提交进历史，即使之后 `git rm`，文件仍留在历史里。想彻底清除要改写全部提交哈希（`git filter-repo`），对新手是灾难 |
| **重复** | Releases 本来就是专门放二进制附件的功能，免费、有下载统计、有版本说明 |
| **易误操作** | `.gitignore` 里已加 `dist/*.apk`，即使你手滑 `git add .` 也不会被带上 |

## 怎么发新版本

1. 本地构建出 APK
2. 仓库页右侧 **Releases** → **Draft a new release**
3. Tag 填 `v1.5.1`（要和 `CHANGELOG.md` 对得上）
4. 把 APK 拖进 **Attach binaries**
5. 点 **Publish release**

详细步骤见根目录 `README-推送前检查.md` 和 `教程-从零建仓.md`。
