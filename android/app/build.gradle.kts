import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ── Release 签名（v1.6 发布准备）─────────────────────────────────────────────
// 凭据放在项目根的 keystore/keystore.properties（**不要提交到公开仓库**）。
// 该文件不存在时（例如别人 clone 下来）自动跳过签名配置，assembleDebug 不受影响，
// 这样构建脚本既能在本机出正式包，也不会因为缺密钥就整体编译失败。
//
// 注意：Kotlin DSL 里不能写 `java.util.Properties()` —— `java` 会被解析成 Gradle 的
// JavaPluginExtension，报 "Unresolved reference: util"。必须顶部 import 后用 `Properties()`。
// 目录层级：<项目>/keystore/ 与 <项目>/cervical-guard/android/（rootProject 在 android/）
val keystorePropsFile = rootProject.file("../../keystore/keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val releaseStoreFile = keystoreProps.getProperty("storeFile")?.let { File(it) }
val hasReleaseKey = releaseStoreFile != null && releaseStoreFile.exists()

android {
    namespace = "com.example.cervicalguard"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                // v1（JAR）+ v2 双签：v2 是 Android 7+ 的主力，v1 兼容老系统与部分国内商店审核要求。
                // v3/v4 不开：v3 需要密钥轮换场景，v4 是 Google Play 增量安装用，这里都用不到。
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    defaultConfig {
        applicationId = "com.example.cervicalguard"
        minSdk = 24
        targetSdk = 34
        // versionCode 必须递增，否则 Android 会拒绝覆盖安装（以后每次升级都要 +1）
        versionCode = 9
        versionName = "1.5.1"
        ndk {
            // 目标手机是 32 位，只需 armeabi-v7a（保留 arm64 便于换机）
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    buildTypes {
        release {
            // 正式包不混淆：项目是纯 Kotlin + 反射调用（衣橱页是按类名反射打开的），
            // 开混淆需要额外写 keep 规则并重测一轮，发布前不做无谓风险。
            isMinifyEnabled = false
            // 关键：debug 包会带 android:debuggable，正式包必须是 false（商店会查）
            isDebuggable = false
            if (hasReleaseKey) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    androidResources {
        noCompress += "task"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    implementation("com.google.mediapipe:tasks-vision:0.10.20")
}

// ── 关于 Google datatransport 遥测组件（v1.6 发布准备）──────────────────────────
// MediaPipe 的 tasks-core 传递依赖 com.google.android.datatransport（Google 遥测上报框架），
// 它会在合并 manifest 里带进 INTERNET / ACCESS_NETWORK_STATE 权限和几个后台组件。
//
// ⚠️ **实测结论：不能排除这个依赖。**
// 试过 `configurations.all { exclude(group = "com.google.android.datatransport") }`，
// MediaPipe 初始化直接崩：
//   `NoClassDefFoundError: Failed resolution of ...datatransport/runtime/TransportRuntime`
// （tasks-core 是**硬引用**它，不是反射，无法靠"类不在就跳过"绕过。真机装包实测 2 次崩溃。）
//
// 采用的方案：**保留依赖，但在 manifest 里 tools:node="remove" 掉 INTERNET 与
// ACCESS_NETWORK_STATE 两条权限**。效果：
//   · 遥测代码即使想上报也**没有网络权限**，物理上发不出去；
//   · MediaPipe 依赖完整，推理正常（真机实测 3.1fps、标注绘制正常、0 崩溃）；
//   · APK 权限清单只剩 CAMERA / FOREGROUND_SERVICE / FOREGROUND_SERVICE_CAMERA /
//     POST_NOTIFICATIONS / VIBRATE，商店隐私审核可逐条对照。
// 代价：logcat 会有一行 `TransportRuntime: Error scheduling event ... ACCESS_NETWORK_STATE`
// （上报被系统拒绝的告警），属预期行为，不产生任何网络流量。
//
// 文案口径因此必须写「本 App 不申请网络权限、不发起网络请求、画面与数据只留本机」，
// **不能**写"代码里完全没有遥测库"（那是假的）。

