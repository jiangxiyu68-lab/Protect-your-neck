# 颈椎坐姿守护 App — Android 集成指南

核心算法已就绪（`core/` 目录，可直接拷贝进 Android Studio 工程）：

| 文件 | 作用 | 对应 Python |
|------|------|------------|
| `PostureAnalyzer.kt` | 耳+肩中点 → CVA 角 → 等效负荷 → 三级分级 | `cva_core.py` |
| `PostureStateMachine.kt` | 危险持续 → 提醒 → 冷却 → 离席暂停 | `posture_state_machine.py` |

这两份代码不依赖任何 UI 框架，算法逻辑已经在 PC 端用真实坐姿图端到端验证通过，
下面是在 Android 上把它们接成「实时监控 + 后台运行」App 的步骤。

---

## 1. 工程依赖（build.gradle.kts）

```kotlin
dependencies {
    // 相机
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    // MediaPipe 姿态估计
    implementation("com.google.mediapipe:tasks-vision:0.10.14")
    // Jetpack Compose（UI）
    implementation("androidx.compose.ui:ui:1.6.8")
    // Room（坐姿日志，可选）
    implementation("androidx.room:room-runtime:2.6.1")
}
```

## 2. 权限（AndroidManifest.xml）

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-feature android:name="android.hardware.camera" android:required="false" />
<!-- 前台服务（后台常驻运行） -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CAMERA" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

## 3. 实时管线（CameraX 帧 → 分析 → 状态机 → 提醒）

核心串联逻辑（`PoseAnalyzer.kt` 实现 `ImageAnalysis.Analyzer`）：

```kotlin
class PoseAnalyzer(
    private val onAlert: (PostureResult) -> Unit,
) : ImageAnalysis.Analyzer {

    private val landmarker = PoseLandmarker.createFromOptions(context, options)
    private val stateMachine = PostureStateMachine(
        dangerHoldSeconds = 180.0,   // 低头 3 分钟才提醒
        cooldownSeconds = 600.0,     // 提醒后冷却 10 分钟
    )
    private val t0 = SystemClock.elapsedRealtime()

    override fun analyze(imageProxy: ImageProxy) {
        val mpImage = imageProxy.toMediaPipeImage()   // Bitmap 转 mp.Image
        val tsMs = SystemClock.elapsedRealtime() - t0
        val result = landmarker.detectForVideo(mpImage, tsMs)

        if (result.poseLandmarks().isNotEmpty()) {
            val lm = result.poseLandmarks()[0]        // 33 个 NormalizedLandmark
            val landmarks = (0 until 33).map {
                Landmark(lm[it].x(), lm[it].y())
            }
            val r = PostureAnalyzer.analyze(
                landmarks, imageProxy.width.toFloat(), imageProxy.height.toFloat()
            )
            val nowSec = tsMs / 1000.0
            val ev = stateMachine.update(nowSec, r.level, personDetected = true)
            if (ev != null) onAlert(r)                 // 触发提醒
        } else {
            stateMachine.update((SystemClock.elapsedRealtime() - t0) / 1000.0,
                Level.SAFE, personDetected = false)
        }
        imageProxy.close()
    }
}
```

## 4. 提醒通道（语音 + 震动 + 常驻通知）

> 早期这里放的是一段**设计草案**（含 `ToneGenerator` 提示音），**从未实现**，容易误导——现按真实代码重写。

实际只有两个主动通道 + 一条被动通道，都在 `PostureService` 里：

| 通道 | 实现 | 说明 |
|------|------|------|
| 语音播报 | `speak(r)`：`tts.speak("已低头 N 度，颈椎等效负荷 X 公斤，请抬头休息一下")` | 走 `TextToSpeech.QUEUE_FLUSH` |
| 震动 | `vibrateAlert()`：波形 `[0, 300, 200, 500]`（短震 300ms → 停 200ms → 长震 500ms） | **默认开**，可在「设置 → 危险时同时震动」关掉（`Thresholds.vibrateEnabled`） |
| 常驻通知 | `updateNotification()` 每秒更新文字（等级 + 角度 + 负荷） | 渠道 `IMPORTANCE_LOW`：**不响不震**，只做状态展示 |

- `AndroidManifest.xml` 需 `android.permission.VIBRATE`（普通权限，装机即授予）。
- API < 26 走 `vibrator.vibrate(long[], -1)`，≥ 26 走 `VibrationEffect.createWaveform(pattern, -1)`（minSdk 24，两条分支都要留）。
- `onDestroy` 里 `vibrator?.cancel()`，避免服务停止后还在震。
- **没有**提示音（`ToneGenerator`）——语音已覆盖听觉通道，再加蜂鸣只会更吵。

> ⚠️ **对话框里的文字别硬编码深色界面的配色**：设置对话框是**白底**（`Theme.MaterialComponents.DayNight` 的浅色对话框），
> 而 App 正文是深色。给「危险时同时震动」勾选框写 `setTextColor(@color/text = #ECF1F6)` 就是白字白底，几乎看不见（真机被用户指出）。
> 正确做法是**不设颜色**，让它跟随对话框主题（浅色模式深灰 / 深色模式浅色），与其余 `EditText` 标签一致。
> 反过来，图表/占比条那种自带 `#1B1B1B` 深色底的块，用浅色字是对的——**看底色决定字色，别跨语境复用颜色常量**。

### ⚠️ 提醒频率由状态机决定，别只看 cooldown

`PostureStateMachine.update()` 在**触发提醒后会把「危险起始时刻」重置为当前时刻**，
所以实际提醒间隔 = **max(hold, cooldown)**。不重置的话（原实现），
危险持续时 `now - dangerSince` 恒 ≥ hold，冷却一过就立刻再提醒：

| 设置 | 修复前 | 修复后 |
|------|--------|--------|
| hold=10s / cooldown=1s，持续危险 40 秒 | 提醒 31 次（**每秒一次**） | 4 次（每 10 秒） |
| hold=5s / cooldown=10s（当前真机设置） | 每 10 秒（cooldown 恰好兜住） | 每 10 秒（不变） |

真机证据：`dumpsys vibrator` 里本应用的波形记录为 `12:20:20 / 21 / 23 / 24 / 25 / 26 …`，与缺陷表现完全一致。
回归测试见 `m0_verify/posture_state_machine.py` 的 `regression_small_cooldown()`（内含 `LegacyStateMachine` 对照实现）。

## 5. 后台运行（前台服务 + 常驻通知）

Android 唯一能让摄像头在锁屏后继续工作的方式：

```kotlin
class PostureService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 常驻通知，保证进程优先级
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("坐姿守护中")
            .setSmallIcon(R.drawable.ic_posture)
            .build()
        startForeground(1, notification)

        // 在这里启动 CameraX + PoseAnalyzer（见第 3 节）
        startCameraAnalysis()
        return START_STICKY
    }
}
```

> 注：Android 14+ 需在 `onStartCommand` 前声明 `foregroundServiceType="camera"`。

## 6. 建议的里程碑顺序

- **M1（本阶段）**：前台运行，实时监测 + 分级 + 声音/震动提醒
- **M2**：Foreground Service 后台化（锁屏继续跑）+ 常驻通知
- **M3**：TTS 语音、Room 坐姿日志 → 日报周报、阈值设置页、节能降帧（5fps）

---

## 7. 实际落地记录（真机实测通过）

工程已按上述设计在 **华为 MRD-TL00（Android 9 / API 28 / 32 位 armeabi-v7a）** 上跑通。

**实际采用：前置摄像头**（`CameraSelector.DEFAULT_FRONT_CAMERA`），手机架在面前、可看屏幕反馈。

### 三个真机必踩的坑

1. **图像旋转（最关键）**：CameraX 的 `ImageProxy` 是传感器原始方向。**必须按 `imageInfo.rotationDegrees` 手动旋转 bitmap** 再喂 MediaPipe，否则前置拿到的是 640x480 横图，CVA 会算成 -142°/232° 这种离谱值。旋转代码：

   ```kotlin
   val original: Bitmap = image.toBitmap()
   val rotation = image.imageInfo.rotationDegrees   // 前置竖屏 = 270
   var bitmap = original
   if (rotation != 0) {
       val m = Matrix().apply { postRotate(rotation.toFloat()) }
       bitmap = Bitmap.createBitmap(original, 0, 0, original.width, original.height, m, true)
   }
   ```

2. **SDK license**：Gradle 报 "licences have not been accepted" 时，手写 `<SDK>/licenses/android-sdk-license`（内容为标准的许可 hash）。

3. **build-tools 版本**：AGP 8.7.3 默认要 build-tools 34.0.0，若只装了 35，需在 `app/build.gradle.kts` 里显式 `buildToolsVersion = "35.0.0"`。

### 构建命令（Windows / Git Bash）

```bash
cd cervical-guard/android
JAVA_HOME='D:\app\java' ANDROID_HOME='D:\Android\Sdk' \
  "D:/Android/gradle-8.9/bin/gradle.bat" assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
# 安装：D:/Android/Sdk/platform-tools/adb.exe install -r <apk>
```

### 实测结果

| 姿态 | CVA | 头前倾 | 等效负荷 | 判定 |
|------|-----|--------|----------|------|
| 坐正 | 75.7° | 14.3° | 11.7 kg | 安全 |
| 略低头 | 73.9° | 16.1° | 12.5 kg | 注意 |

判定随姿态实时变化，符合预期。

---

## 8. M3 落地：前台服务后台化（锁屏也能守护）

- `PostureService.kt`：前台服务承载 相机 + MediaPipe + 常驻通知 + TTS 语音。
- **关键技巧**：Service 里绑定 CameraX 需要一个 **恒为 RESUMED 的 LifecycleOwner**（`AlwaysAliveLifecycleOwner`，init 时把 lifecycle 推到 RESUMED）。若用 `ProcessLifecycleOwner`，应用切后台时它会进入 STOPPED，导致相机被解绑、后台监测失效。
- `MainActivity` 降级为控制面板：开始/停止守护 + 显示服务回传的实时数据（通过静态回调 `PostureService.onUpdate` 通信）。
- 验证：锁屏后（屏幕 `Asleep`）服务进程存活，`isForeground=true`，通知栏持续更新。
- 注意：Service 声明 `exported="false"`，调试时 `adb am start-foreground-service` 会被权限拒绝（需从 App UI 启动）。

---

## 9. M4 落地：坐姿日志 + 今日统计 + 阈值设置

- 数据层 `data/PostureDb.kt`：系统内置 SQLite，表 `logs(minute, danger_sec, warn_sec, safe_sec, max_forward)`。
  - **坑**：Android 9 的 SQLite 3.22 不支持 `INSERT ... ON CONFLICT ... DO UPDATE`（UPSERT），需用 `INSERT OR IGNORE` + `UPDATE` 两步。
- 服务内**内存聚合**：每秒只累加计数，分钟切换时才 flush 落库，避免高频写库拖慢老设备。
- `data/Thresholds.kt`：SharedPreferences 存阈值；`PostureAnalyzer.analyze()` 加阈值参数、`PostureStateMachine` 改为读它，实现动态阈值。
- 主界面新增「今日统计」（守护时长 + 三级占比 + 最大头前倾）与「设置」（改阈值对话框）。
- 实测：守护 2 分钟后统计为 注意 77% / 安全 22% / 危险 0%，最大头前倾 25.1°。

## 10. M5 落地：数据板块（日报 / 周报 / 月报，时间线可切换）

用户反馈「周报时间线太久」，于是做成一个**可切换时间线的「数据」板块**（底部按钮 `数据`）：

| 档位 | 桶粒度 | 桶数 | 图表标签 |
|------|--------|------|----------|
| 今日（日报） | 小时 | 24 | 每 3 小时一个，**当前小时高亮** |
| 近 7 天（周报） | 天 | 7 | 每天都标，`M/d`，今天高亮 |
| 近 30 天（月报） | 天 | 30 | 每 5 天一个，今天高亮 |

- `data/PostureDb.kt`：
  - `data class Bucket(startSec, dangerSec, warnSec, safeSec, maxForward)`——日报/周报共用一种桶。
  - `todayHourly(): List<Bucket>`、`lastDays(days): List<Bucket>`，都走私有 `bucketQuery()`。
  - **聚合在 SQL 侧做**：`SELECT (minute - ?) / spanSec AS b, SUM(...), MAX(...) ... GROUP BY b`，
    30 天也只返回 ≤30 行——不要把几万行分钟数据捞进 Kotlin 再分桶。
  - 缺失的桶在 Kotlin 里补零，保证图表槽位数量恒定。
  - 切桶用固定 `3600 / 86400` 秒：中国无夏令时，本地时区下正确。
  - 已删除早期的 `last7Days()` / `last7DaysDanger()`（被 `lastDays(n)` 取代）。
- `ui/DataChartView.kt`（**取代** 早期的 `WeeklyChartView.kt`）：**自绘**分级堆叠柱状图，`Mode.HOURLY / DAILY` 两种模式。
  - 工程是纯 XML + AppCompat（不是设计方案里写的 Compose），自绘保持零新依赖、dex 不膨胀。
  - 自带深色底：主题是 `Theme.MaterialComponents.DayNight`，浅色模式下对话框是白底，
    所以图表与文案块统一用 `#1B1B1B` + 浅色字，两种模式对比度都够。
  - 桶多时自动收窄柱子（`slot > 24dp` 才画柱顶数值标签，避免 24/30 桶时糊成一片）。
  - 纵轴上限 = 单桶最大总时长（下限 60 秒，避免柱子撑满失真）；全 0 时显示「该时段暂无坐姿数据」。
- `MainActivity.showData()`：`ScrollView` + 三档选择器（选中档用主色填充）+ 图表 + 汇总 + 免责声明。
  - 汇总随档位变口径：日报显示「最久时段 N 时（X分Y秒）」，周报/月报显示「日均危险」。
- 布局 `activity_main.xml`：底部按钮行第二颗由「周报」改为「数据」（`dataBtn`）。
- **真机实测（MRD-TL00）**：三档切换均正常——今日显示 24 个小时桶且当前小时高亮，
  近 7 天显示 10/6 与今天两根柱，近 30 天标 9/8…今天；汇总数字与数据库一致。

> ⚠️ **Kotlin 字符串模板与中文的坑**：`"$label暂无数据"` 会被解析成一个标识符 `label暂无数据`
> （中文是合法标识符字符），必须写 `"${label}暂无数据"`。同理 `"$unit有记录"` → `"${unit}有记录"`。

### 数据质量修复（2026-10-06，含实测）
起因：真机出现「最大头前倾 68.9°」而实时仅 5~10°。挖出四个独立缺陷，均已修：

1. **时长单位错（最严重）**：`PostureService.recordLog()` 原来每帧 `cnt++`，flush 时却写进 `danger_sec/warn_sec/safe_sec`。
   真机实测分析管线 **≈2.2 fps** → **所有时长虚高约 2.2 倍**（相机流 13.4 fps，瓶颈是 bitmap 旋转 + MediaPipe lite）。
   现改为按**壁钟 dt** 累加（Double 累加器，flush 时 `Math.round`），与帧率无关；单次采样封顶 5 秒。
2. **未按可见度选侧**：`PostureAnalyzer.pickVisibleSide()`——两侧都可见取中点（抗噪），只有一侧可见用该侧，
   都不可见返回 null → 上层按「未检出」处理（不计数/不落库/不提醒）。侧面拍摄时远侧耳被遮挡、坐标是模型猜的，
   取中点正是 68.9° 这类值的来源。
3. **无时间平滑**：加**10 秒时间窗滑动中位数**（按时间戳淘汰过期样本，无效帧不入窗）。
   ⚠️ 刻意不用设计文档的「30 秒窗」：30 秒在 2.2 fps 下约 66 样本、判定滞后十几秒；
   也不用「固定 5 帧」：那在 2.2 fps 下只覆盖 2.3 秒，尖峰照样漏过。
4. **无合理性约束**：`θ` 越出 `-15°..80°` 即丢帧（`THETA_MIN/THETA_MAX`）。

**验证证据**：修后数据库连续四个分钟桶为 **60/60/60/59 秒**（无一超过 60）；进程运行 4分34秒 → UI 显示今日守护 4分9秒。

**注意兼容性影响**：
- `PostureDb` 版本 1→2，`onUpgrade` 会 `DROP TABLE logs`（旧数据单位错误、无法换算还原）→ **用户日志清空一次**。
- `Landmark` 增加 `visibility` 字段；`PostureAnalyzer.analyze()` 返回值改为**可空**，
  管线实际走 `measure()` → 时间窗中位数 → `fromTheta()`。
- 因此与 `m0_verify/` 的 Python 原型**已非严格 1:1**（Python 侧无可见度选侧、无时间平滑、无 dt 计秒）。

---

## 11. M5 落地：CSV 数据导出

- `data/PostureDb.kt` 新增 `allLogs(): List<LogRow>`（全量、按时间升序）。
- `data/CsvExporter.kt`：写 `getExternalFilesDir(null)/posture_yyyyMMdd_HHmm.csv`（即 `Android/data/<包名>/files/`）。
  - **UTF-8 BOM + CRLF**：不写 BOM 的话 Windows Excel 打开中文表头会乱码，这是国内用户最常踩的坑。
  - 表头带单位：`时间,危险(秒),注意(秒),安全(秒),合计(秒),最大头前倾(度)`。
  - 文件保持「纯数据表」，不插免责声明行（免得 Excel/pandas 解析多出脏数据）；免责声明放在 App 对话框里。
  - 不需要任何存储权限；外部存储不可用时退回内部 `filesDir`。
- 分享：`AndroidManifest.xml` 注册 `androidx.core.content.FileProvider`
  （`authorities="${applicationId}.fileprovider"`）+ `res/xml/file_paths.xml`（`external-files-path` / `files-path` 根目录），
  `MainActivity.shareCsv()` 用 `ACTION_SEND` + `FLAG_GRANT_READ_URI_PERMISSION`。
- **UI 布局改动**：底部从「一行三按钮」改为「两行」——上行 `设置 | 数据 | 导出`，下行整宽主按钮 `开始守护/停止守护`。
  原因：4 颗中文按钮挤一行在 360dp 宽屏上会溢出（实测 720px 屏）。
- **真机实测（MRD-TL00）**：导出 28 条记录 → 与数据库**逐行比对 0 处不符**、合计 1521 秒一致；
  BOM = `EF BB BF`、29 个 CRLF、0 个裸 LF；分享面板（`HwChooserActivity`）正常弹出。

---

## 12. M5 落地：界面重做 + 摄像头画面

### 画面：服务独占相机 + Preview 用例（**不要**共享 bitmap）

需求是「在界面上显示摄像头画面」。注意 M3 起相机是**前台服务独占**的（这样锁屏才能守护），
所以界面不能自己去 bind CameraX。

- ❌ **走过的弯路（会崩）**：服务把分析用的 bitmap 缩一半回调给 Activity 显示。
  真机上分析线程必然 SIGSEGV：`#02 Java_com_google_mediapipe_framework_AndroidPacketCreator_nativeCreateRgbaImage`
  + `Cause: null pointer dereference`，进程每 17~37 秒重启一次。推测是 MediaPipe 已持有该 bitmap 的像素时，
  又在同一帧对它做原生缩放（锁像素冲突）。
- ✅ **现方案**：服务 `bindToLifecycle(AlwaysAliveLifecycleOwner, FRONT, preview, analysis)` 同时绑两个用例；
  `PostureService.previewUseCase` 暴露给界面，界面 `onResume` 里
  `preview.setSurfaceProvider(previewView.surfaceProvider)`、`onPause` 里 `setSurfaceProvider(null)`。
  界面**完全不经手 bitmap**，与 MediaPipe 的像素零交集；预览走 GPU 通路，画面是真·实时（不是 2.2fps 幻灯片）。
  - 服务启动相机是异步的，界面可能先就绪 → `onUpdate` 里补挂一次（用 `surfaceAttached` 去重）。
  - 用 `app:implementationMode="compatible"`（TextureView）：布局在 ScrollView 里，SurfaceView 会有裁剪/错位问题。
  - `Preview` 只有 `setSurfaceProvider`、没有 getter，**Kotlin 不会合成属性**，必须写方法调用。

### ⚠️ 绑 Preview 会改变 ImageAnalysis 的方向 → 必须显式 setTargetRotation

`ImageAnalysis.Builder().setTargetRotation(Surface.ROTATION_0)` 是**必需**的。不写时 `rotationDegrees`
会随相机流配置漂移；一旦拿到横幅帧，人的竖直轴落到图像 x 轴上，头前倾角会假性飙到 50~78°。

实测（同一台机、同一时段连续分钟桶的 `max_forward`）：

| 配置 | 每分钟最大头前倾 |
|------|------------------|
| 绑 Preview、未钉旋转 | 49.2 / 78.5 / 54.9 / 52.4 / 38.1 / 32.1 / 32.4 ° |
| 绑 Preview、钉 `ROTATION_0` | 15.6 / 18.7 / 16.8 / 29.1 / 29.0 °（恢复正常） |

### 界面设计

- 深色卡片仪表盘，一屏放下（720x1560 / 360x780dp 实测无需滚动）：
  标题 → 状态胶囊（按等级 tint：绿/橙/红）+ 说明 → **画面卡（左，3:4 竖幅不留黑边）+ 实时指标（右，三项竖排）**
  → 今日卡（守护时长 + 三级占比条 + 2×2 网格）→ 底部固定：`设置|数据|导出` + 整宽主按钮。
- 配色/样式集中在 `res/values/colors.xml`、`styles.xml`、`themes.xml`；圆角底在 `res/drawable/bg_*.xml`。
  - 主题 parent 仍是 `Theme.MaterialComponents.DayNight`——**故意**：这样对话框保持主题默认观感（浅色模式下白底），
    只把 App 正文与窗口底色固定成深色，两个模式下对比度都够。
  - 占比条用三个 `View` + 代码设 `layout_weight`；`clipToOutline="true"` 让子 View 被圆角裁剪。
  - 「0分13秒 · 0%」这种自相矛盾显示，改为不足 1% 时显示 `<1%`。
- 修了一个状态同步 bug：`startForegroundService()` 是异步的，刚点完 `isRunning` 还是 false，
  按钮文字会滞后一拍 → `updateToggle(running: Boolean)` 改为显式传状态。

### 真机验证（MRD-TL00）

- 预览正常显示、指标按等级上色；主按钮运行态变红「停止守护」。
- 浸泡 3 分钟：**同一 PID 00:17 → 03:03，`signal 11` 计 0 次**（换 CameraX Preview 之前是 17~37 秒崩一次）。
- 锁屏回归：息屏期间分钟桶仍记满 60 秒，前台服务保持（守护核心功能未受影响）。

### ⚠️ 关停顺序：先等分析线程停，再释放 MediaPipe（否则点「停止守护」必崩）

`PostureService.onDestroy()` 的顺序**不能随便写**。用户实报「点停止守护就回桌面」，真机 2/2 复现，
崩溃栈 `nativeCreateRgbaImage` ← `AndroidPacketCreator.createImage`，线程 `pool-1-thread-1`（分析线程）：
原因是先 `monitor.close()` 释放了 MediaPipe 原生资源，而分析线程此刻正卡在 `BitmapImageBuilder.build()` 里跑一帧 → use-after-free。

```kotlin
cameraProvider?.unbindAll()          // ① 停投递新帧
analysis?.clearAnalyzer()            // ② 摘分析器
executor.shutdown()
val stopped = executor.awaitTermination(3, TimeUnit.SECONDS)   // ③ 等分析线程真正结束
if (stopped) monitor?.close()        // ④ 才安全释放；没停就跳过（宁漏不崩）
```

验证：修后连续 10 轮启停 **0 崩溃**，进程 PID 保持不变；内存 155/147/163/112 MB 来回波动（非单调增长 → 无泄漏）。

> 同一个崩溃栈还有另一个触发源：界面共享 MediaPipe 正在用的 bitmap（运行中每 17~37 秒崩一次）。
> 现在画面走 CameraX `Preview`，已不共享 bitmap。两个触发源栈顶相同，排查要结合「崩在什么操作之后」。

### 停止后要「真的」回到初始界面

`PreviewView`（compatible / TextureView 模式）**会保留最后一帧的缓冲**，只 `setSurfaceProvider(null)` 画面不会消失，
看起来就像「定格 + 提示文字叠加」。所以停止时要把 `PreviewView` 整个 `visibility = INVISIBLE` 才干净，
恢复成初始的纯黑卡片 + 占位提示（布局里 `previewView` 默认就是 `invisible`）。

另外服务停止是异步的，销毁前还会再推一两帧，会把刚设好的「待机/守护已停止」覆盖成最后一帧的数值。
界面侧用 `expectRunning` 标志把残留帧挡掉，并把三个实时指标重置为 `--`：

```kotlin
stopGuard():  detachPreview() → stopService() → expectRunning = false
              → setChip("待机") → resetLiveMetrics() → showPreviewPlaceholder()
onUpdate:     if (!expectRunning) return@runOnUiThread   // 丢弃残留帧
onResume:     expectRunning = PostureService.isRunning
```

---

## 关键约束（务必读）

1. **必须侧面拍**：手机放支架、**斜侧对用户（前置摄像头侧放）**，正面对拍测不出头前伸。
2. **耗电发热**：持续摄像头 + 推理，建议手机架配充电；生产环境把采样降到 5–10fps。
3. **健康提醒定位**：算法是「头部力矩简化模型 + Hansraj 经验表」的趋势性评估，
   不是医学诊断，界面上要放免责声明。
4. **隐私**：关键点坐标本地处理，原始画面不落盘、不上传。
