# 第三方组件与许可

本项目以 MIT 许可发布，但**依赖的第三方组件各有自己的许可**，分发时需一并遵守。

| 组件 | 版本 | 许可 | 说明 |
|---|---|---|---|
| MediaPipe tasks-vision / tasks-core | 0.10.20 | Apache-2.0 | 姿态关键点推理（含 native .so） |
| `pose_landmarker_lite.task` 模型 | 随 MediaPipe 发布 | Apache-2.0 | Google 提供的预训练模型 |
| CameraX (camera-core / camera2 / lifecycle / view) | 1.3.4 | Apache-2.0 | 相机采集 |
| AndroidX (core-ktx / appcompat / constraintlayout) | 见 build.gradle.kts | Apache-2.0 | 基础库 |
| Material Components for Android | 1.12.0 | Apache-2.0 | 主题与控件 |
| Google datatransport（MediaPipe 传递依赖） | 3.x | Apache-2.0 | 遥测框架。**本 App 已移除其网络权限，不会真正上报** |

## 关于模型文件

`app/src/main/assets/pose_landmarker_lite.task` 来自 Google MediaPipe 官方发布，遵循 Apache-2.0。
重新分发时请保留其原始许可声明；本项目未对该模型做任何修改。

## 关于算法依据

等效负荷模型引自：
Hansraj KK. *Assessment of stresses in the cervical spine caused by posture and position of the head.*
Surg Technol Int. 2014;25:277-279.

该文献用于**估算**颈椎负荷，不构成医学结论。本 App 的输出仅为趋势性参考。
