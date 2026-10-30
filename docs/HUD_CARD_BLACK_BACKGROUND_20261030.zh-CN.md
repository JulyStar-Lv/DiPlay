# HUD 右侧指示卡：黑底与白色引导

日期：2026-10-30。适用当前 J90K 右侧 `(584,179,178,178)` 指示卡输出。

本报告记录初版。后续箭头配色按原厂左侧统一，见 [原厂箭头配色调整](HUD_CARD_OEM_ARROW_PALETTE_20261030.zh-CN.md)。

## 修改范围

在 `vehicle-mzd` 的指示卡显示层增加局部配色处理：

- 日间白底黑字转为纯黑背景、白色文字及当前引导箭头。
- 夜间蓝灰底白字压为纯黑背景，保留白色引导。
- 非推荐箭头保留灰度差异，抗锯齿边缘继续使用连续灰阶。
- 维持完整画面的比例、尺寸、文字换行和箭头位置。

仅 `INSTRUCTION_CARD` 模式启用，`MAP` 和 `MAP_WITH_INSTRUCTION_CARD` 模式保持源配色。没有增加设置项，没有更改公共数据接口、CarPlay 主题请求、左侧 Binder、原厂服务或 Linux HUD 资源。

## 实现

- [配色策略及控制器](../vehicle-mzd/src/main/java/com/shilapi/vehicle/mzd/MzdHudCardTone.kt)
- [虚拟屏装配](../vehicle-mzd/src/main/java/com/shilapi/vehicle/mzd/MzdHudMapOutput.kt)

每次收到新帧后，最多每 500ms 读取一次 16×16 的原始纹理缩略图。读回通过独立 UI 回调执行，避免在绘制回调中同步读图。显示阶段使用 GPU ColorMatrix 滤镜，没有在 CPU 上逐帧重新绘制视频。

滤镜加在 178×178 容器的硬件图层上，原始 TextureView 不加滤镜。因此后续抽样不会再次读到已转换结果，避免“反色 → 再反色”的反馈。

背景采用边缘亮度中位数判断：亮度 ≥208 为日间卡、≤64 为夜间卡；背景量化到 8 级步长，并按实测背景设置压黑点。边缘不平整、大面积彩色或中间亮度的未知画面保留原色。首次有效抽样前保持黑色遮盖，停止显示时移除抽样回调并释放缩略图。

这是一项针对当前纯文字／箭头卡片的配色策略。像素抽样不等同于路口图片语义识别；以后在纯卡片视频内接入真实路口图时，应提供明确的内容状态，不能依靠灰度特征保证识别所有图片。

Android 官方说明 TextureView 支持图层 Paint 的颜色滤镜；AOSP 读回实现也会使用纹理自身的图层 Paint，因而本实现将滤镜放在父容器：

- [TextureView 图层 Paint](https://developer.android.com/reference/android/view/TextureView#setLayerPaint(android.graphics.Paint))
- [AOSP LayerDrawable](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/platform-tools-34.0.5/libs/hwui/pipeline/skia/LayerDrawable.cpp)

## 自动验证

[图形测试](../vehicle-mzd/src/test/java/com/shilapi/vehicle/mzd/MzdHudCardToneTest.kt)覆盖日间／夜间卡、背景深浅变化、白色前景、灰色非推荐箭头、连续灰阶、空读回及彩色画面透传；窗口测试覆盖启用范围、178×178 位置与关闭释放。证据工具只允许固定配色枚举进入诊断记录，日志不能代替截图验收。

- [离线图形预览](../build/hud-card-monochrome-20261030/preview.png)
- [完整 CI 日志](../build/hud-card-monochrome-20261030/ci-final.log)
- [验证汇总](../build/hud-card-monochrome-20261030/validation.json)

完整 `.github/workflows/android.yml` 检查已通过，476 项任务；3,923 项测试中 3,918 项通过、5 项既有跳过，0 失败。Python 工具测试 17 项通过，OEM 模块边界和 APK 包含检查通过。

首轮完整运行中，`BufferedAudioStreamTest.tinyAuthenticatedFramesCannotGrowAnUnboundedObjectQueue` 和 `AmbientWorkerOwnerLockTest.anotherProcessCannotEnterUntilOwnerReleases` 等待超时；分别重跑以及完整 CI 重跑均通过。完整重跑限制为两个 Gradle worker，未改测试断言或生产音频逻辑。

为避免并行任务修改 `MzdHudMapOutput.kt` 影响包内容，本次 CI 与正式安装包使用 `DiPlay-card-ci-20261030-111844` 固定源码快照，保留主工作区后续路口图修改。`source-state.json` 记录本次配色源码指纹，`validation.json` 记录后续工作区差异。正式包需校验签名与已安装包一致、认证资源完整，并且不含 debug 回放类。

## 正式包及台架状态

- 正式包：[DiPlay-Mazda-HUD-BlackCard-0.2.22-20261030.apk](../build/hud-card-monochrome-20261030/DiPlay-Mazda-HUD-BlackCard-0.2.22-20261030.apk)，包名 `com.thunder.carplay`。
- 固定快照的 Release 构建及 `lintRelease` 已通过，210 项任务。
- 已校验签名与备份的原安装包一致，CarPlay 认证资源完整，正式包不含 debug 台架回放类。
- ADB 恢复后已安装此初版，校验设备 APK SHA-256 与构建包一致，台架仅保留 `com.thunder.carplay`。
- [初版真实导航截图](../build/hud-card-monochrome-20261030/palette-baseline/hud/446fbf24-79d5-497d-8213-a07c9b0dda1e.png) 已看到黑底白色引导；该帧没有非推荐车道箭头，不能据此验收灰色配色或夜间亮度。

[打包校验记录](../build/hud-card-monochrome-20261030/package-validation.json) · [Release 日志](../build/hud-card-monochrome-20261030/release.log)
