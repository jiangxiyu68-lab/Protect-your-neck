# 隐私政策 · Privacy Policy

**应用名称**：颈椎守护（Cervical Guard）
**开发者**：个人开发者
**最后更新**：2026-10-23
**适用版本**：1.5.1 及以上

---

## 一句话总结

**本应用不申请网络权限，所有数据都留在你的手机上，没有任何服务器。**

---

## 1. 我们收集什么

### 1.1 相机画面

- **用途**：通过前置摄像头拍摄你的侧脸与肩膀，在本机用 MediaPipe 姿态模型估算头部角度。
- **处理方式**：**仅在内存中逐帧处理，不写入任何文件、不上传、不留存**。每一帧处理完即丢弃。
- **例外**：如果你在设置里开启了「提醒时附上画面」，触发提醒时会截取**一张**当前画面，仅用于生成**本机通知**的缩略图，存在内存里、通知消失后即释放。**不会写入相册、不会上传。** 该开关默认关闭，可随时关闭。

### 1.2 坐姿数据

- 记录内容：每分钟的安全/注意/危险秒数、最大头前倾角度、未计分秒数。
- 存储位置：**你手机本地的 SQLite 数据库**（应用私有目录，其他应用无法访问）。
- 保留期限：直到你卸载应用或清除应用数据。

### 1.3 成长数据

- 等级、经验、积分、已拥有的小鹿时装、外观偏好、阈值设置。
- 存储位置：本机 SharedPreferences。

### 1.4 我们不收集

- ❌ 不收集账号、手机号、邮箱（本应用无账号系统）
- ❌ 不收集设备标识符（IMEI / Android ID / OAID / 广告 ID）
- ❌ 不收集位置信息
- ❌ 不收集通讯录、短信、通话记录
- ❌ 不收集其他应用列表
- ❌ **不申请 `INTERNET` 与 `ACCESS_NETWORK_STATE` 权限**（已在清单中显式移除）

## 2. 权限说明

| 权限 | 用途 | 是否必需 |
|---|---|---|
| `CAMERA` | 拍摄你的侧脸用于姿态估算 | 必需（否则无法检测） |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_CAMERA` | 让守护在锁屏 / 切到后台时继续运行，并在通知栏常驻提示 | 必需 |
| `POST_NOTIFICATIONS` | 低头超时后弹出提醒通知（Android 13+ 需授权） | 可选，拒绝后仅无通知 |
| `VIBRATE` | 提醒时震动 | 可选 |

**没有申请**：网络、位置、存储写入、通讯录、麦克风、后台定位等。

## 3. 数据如何离开本机

**唯一的途径是你主动导出 CSV。** 点击「导出」后：

1. 应用在你手机的缓存目录生成一个 CSV 文件；
2. 通过系统分享面板（`FileProvider`）交给你选择的应用（如微信、邮件、文件管理器）；
3. 之后数据流向由你选择的目标应用决定，**与本应用无关**。

除此外，应用**不会**通过任何方式向外传输数据。

## 4. 关于底层依赖的透明说明

本应用使用 Google MediaPipe 做姿态推理。MediaPipe 的 `tasks-core` 传递依赖了 Google 的
`datatransport` 遥测组件，该组件会在后台尝试调度上报事件。

**但由于本应用已移除网络权限**，这些上报请求会因缺少 `ACCESS_NETWORK_STATE` / `INTERNET`
被系统直接拒绝，**不产生任何网络流量、不发送任何数据**。这一点可以在 `app/build.gradle.kts`
的注释与 `AndroidManifest.xml` 的 `tools:node="remove"` 中核对，也可以自行用
`aapt dump permissions <apk>` 验证。

## 5. 人脸信息合规说明

根据《个人信息保护法》第 28、29 条，人脸信息属于**敏感个人信息**。本应用的处理方式：

- **不提取人脸特征**：不生成人脸特征向量、不做人脸识别、不做身份比对、不建立人脸库；
- **不做身份识别**：只提取 33 个身体关键点中的耳、鼻、肩位置，用于计算角度；
- **不外传**：画面不出本机；
- **不存储**：画面不落盘（除上文 1.1 的临时通知缩略图，且默认关闭）。

因此本应用不构成"人脸识别"应用，但为尊重你的知情权，首次开启守护前会明确告知相机的用途。

## 6. 未成年人

本应用不面向 14 周岁以下儿童。若未成年人使用，应在监护人指导下进行。

## 7. 你的权利

- **查看**：应用内「数据」页可查看全部统计；数据库文件位于应用私有目录。
- **删除**：卸载应用即删除全部数据；或在系统设置中「清除数据」。
- **导出**：随时可导出 CSV 带走自己的数据。
- **撤回同意**：随时可在系统设置中撤销相机权限，或关闭「开始守护」。

## 8. 变更

隐私政策如有变更，会在应用更新说明与本文件中标注。重大变更会重新征求同意。

## 9. 联系方式

- 问题反馈：请在仓库的 Issues 中提出
- 邮箱：（请开发者填写）

---

# Privacy Policy (English)

**App**: Cervical Guard
**Last updated**: 2026-10-23

**Short version: this app does not request network permissions. All data stays on your device. There is no server.**

- **Camera**: frames are processed in memory only, never written to disk, never uploaded.
  Optional "attach snapshot to alert" (off by default) creates one in-memory thumbnail for a local notification only.
- **Posture data**: stored in a local SQLite database inside the app's private directory; deleted on uninstall.
- **No collection of**: accounts, phone numbers, device identifiers (IMEI/Android ID/OAID/GAID), location, contacts, SMS, installed apps.
- **Data leaves the device only when you tap "Export"**, which generates a CSV and hands it to the system share sheet.
- **Face data**: we do NOT extract facial feature vectors, do NOT perform face recognition or identification, do NOT store images. Only ear/nose/shoulder landmark positions are used to compute angles.
- **Third-party note**: MediaPipe's `tasks-core` transitively depends on Google's `datatransport` telemetry library. Because this app removes `INTERNET` and `ACCESS_NETWORK_STATE` permissions, those upload attempts are rejected by the OS and produce no network traffic.

**Disclaimer**: This app is a posture reminder tool, **not a medical device or diagnostic tool**.
