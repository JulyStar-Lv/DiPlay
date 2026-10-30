# HUD 右侧地图按原厂分层方案调整

日期：2026-10-30。适用范围：当前 J90K 普通 HUD 布局（178×178）。依据：[原厂代码分析](HUD_NATIVE_RIGHT_VIEWPORT_20261030.zh-CN.md)。

后续台架结果见[实机验证报告](HUD_NATIVE_MAP_BENCH_VALIDATION_20261030.zh-CN.md)：SIM 本地地图及栅格层已在 Linux HUD 实际显示，正式版已恢复并补查真实 iPhone 会话；手机真实路线几何与独立路口图仍未验收。下文保留本次实现阶段的离线验证记录。

## 1. 本轮交付范围

已将本地路线绘制路径调整为“专用 HUD 小地图 + 独立路口栅格图层”，并接入现有虚拟屏输出接口。**本轮验收为代码、离线 Android 原生图形回放及构建检查；没有连接 ADB、安装应用或新增台架实图结论。**

生产 CarPlay 仍接收 type 111 辅助视频。尚未取得手机当前选中路线的真实几何，也未确认手机提供独立路口图片与显隐事件。本轮不会据 TBT 自动生成真实路网，不会将 SIM 样本接入真实导航。

## 2. 与原厂对齐的部分

| 项目 | 本轮实现 | 边界 |
| --- | --- | --- |
| 专用绘制 | 本地生产者通过 `RouteMapSession` 按 178×178 生成画面 | 不从中控画面裁切地图 |
| 输出位置 | 复用 `(584,179,178,178)` 容器及 800×480 透明 `Presentation` | 沿用已支持的普通布局；其他模式另行适配 |
| 样式 | 黑底、灰色道路、10px `#00ffa4` 路线、白色车标 | 没有复制原厂资源或引擎；黑色描边保留本地总宽 14px，原厂 border 参数语义未确认 |
| 车标位置 | 窗口宽度 50%、高度 80% | 本地实现的明确屏幕位置；不声称复刻原厂车标资源 |
| 距离分档 | ≤300m / 301–600m / >600m 对应策略级别 19 / 18 / 17 | 本地相对视野为基准的 1/4、1/2、1，未校准原厂 SDK 的绝对视野 |
| 重绘 | 本地绘制线程按 10fps 调度 | 不等于跨 Android/Linux 的实测帧率；未复制 24fps 动画及平滑 zoom |
| 普通地图层 | 路口图出现期间仍更新路线与位置 | 隐藏路口图后恢复最新地图，不恢复显示前的缓存截图 |
| 路口图层 | 道路图与可选透明箭头图分开解码并叠加，完整窗口等比容纳 | 当前支持 BitmapFactory 可解码的栅格文件；不解析原厂矢量或 3D 二进制 |
| 左侧信息 | 继续使用现有结构化导航 Binder | 不在右侧重复车道行、距离与路名 |

距离未知时保留来源基准视野；没有从转向类型或车道角度推算地图。回放的基准视野为 712m，供展示相对缩放，不能作为实际导航的固定比例。

手机视频中的路宽、配色、车标和 zoom 由手机决定。上述本地样式只对选用该绘制器的场景生效，不能直接修改已解码视频里的图层。本轮没有更改已验证的手机视频协商参数（256×256 / 15fps）。

## 3. 代码分离

```text
来源适配器（真实数据接入待补齐）
  ├─ RouteScene：完整局部路线、道路、位置、航向、下一路口距离
  └─ JunctionImageEvent + RasterJunctionImage：图片与独立显隐事件
                      ↓
display-android：HudMapLayers → HudMapRenderer → RouteMapSession
                      ↑
vehicle-mzd：MzdHudRoutePresentation（像素尺寸、配色、距离策略）
                      ↓
已有 VehicleMapOutput → Presentation → MeterDisplay#0 → Linux HUD
```

- `display-api/RouteScene.kt`：新增可选 `distanceToManeuverMeters`；投影器接受调用方的视野及车标位置，旧默认保持不变。
- `display-android/RouteMapPresentation.kt`：中立的绘制策略接口，无 OEM 名称、坐标及手机协议。
- `display-android/HudMapLayers.kt`：栅格解码、独立事件契约、图层状态与合成器。
- `display-android/RouteMapSession.kt`：同一 Surface 生产者渲染地图及路口图；新增 `showJunction()` / `hideJunction()`。
- `vehicle-mzd/MzdHudRoutePresentation.kt`：仅该模块持有原厂样式及距离分档。
- `mazda/src/debug/.../hudbench/`：装配此 OEM 策略并提供有限 SIM 回放；Release 无回放类或入口。

同一 `VehicleMapOutput` 只允许一个像素生产者占用当前 lease。未来接入本地真实地图时，装配层须先停止手机视频生产者，再交给 `RouteMapSession`，不能同时向 Surface 写入。当前独立栅格合成接口属于本地地图生产者，尚未实现将其叠到手机视频生产者的共享合成管线。

本轮绘制及栅格样本由仓库内代码生成，没有移入此前 GitHub 非商业许可项目的代码，也未打包原厂地图资源。

## 4. 路口图生命周期与输入约束

1. 来源先提交当前有效 `GUIDING` 地图，再提交同会话、同路线、同来源类型的图片事件。
2. 地图 `revision` 与路口事件 `revision` 各自严格递增；事件序号在同一来源会话内全局递增，不能在每个路口重新从零计数。来源在收到事件时分配版本与接收时间，再异步解码；解码完成后不得把旧 show 重新编号为新事件，否则会破坏 hide 对迟到图片的拦截。
3. show/hide 均含 `junctionId`；旧路口的迟到 hide 不会关闭新路口。先收到 hide 时记录版本，拒绝随后迟到的旧 show。
4. 路口图显示期间，底图持续接收更新；hide 后从当前地图绘制下一帧。
5. 路线替换、导航结束、来源类型变化、会话关闭或地图失效均清图。即使超时后直接收到新底图、期间未调用绘制，也不会复用旧放大图。
6. 地图默认 3 秒过期；路口事件默认 15 秒过期，来源若需要持续显示应定期发送新版本。路口事件不会延长底图期限。过期路口在仍有效地图上退回地图；过期地图清为黑屏。
7. 时间使用本进程单调接收时间，拒绝未来或过期事件。不能把原厂状态 `sendNavigateCrossRoad(1)` 当图片或自动触发空白放大图。
8. encoded 总量≤4MiB，单边≤2048，road/arrow 尺寸必须相同，合计解码像素≤2Mi；解码在来源工作线程执行，尺寸检查与解码使用同一份输入副本，避免来源复用缓冲区产生不一致。未知格式、尺寸不匹配或超限返回拒绝。
9. 栅格层按原始宽高比 contain，剩余区域填黑；不额外绘制接收端车标。样本始终显示 `SIM`，不会被不透明图片盖掉。

收到原厂引擎的栅格原始缓冲时，也必须先确认编码、像素格式与授权接口，不能因其名为 raster 就直接作为 PNG 传入。

## 5. 自动验证与图像证据

新增针对性测试覆盖距离边界、绿色路线宽度、车标位置、原尺寸输出、Surface 解绑、路口层显隐、底图持续更新、身份匹配、版本乱序、过期清除、解码限制、透明箭头合成、等比显示、裁剪与 SIM 水印。

新增 18 张 Android 原生图形测试输出的 178×178 PNG：

- 8 个原有路线案例使用 Mazda 策略绘制。
- 同一路线分别在 900 / 450 / 150m 距离档显示。
- 路口显示前、显示中、底图更新后、隐藏恢复最新地图共 4 张。
- 数据过期、导航结束、会话关闭 3 张黑屏。

测试逐像素确认：放大图覆盖期间底图更新不会穿透图层；隐藏后画面等于最新底图，且不同于放大图出现前的底图。

- [18 张总览](../build/hud-native-map-20261030/preview.png)
- [原尺寸与 2 倍预览](../mazda/build/reports/hud-native-map/index.html)
- [图像散列清单](../mazda/build/reports/hud-native-map/manifest.json)
- [完整 CI 日志](../build/hud-native-map-20261030/ci.log)
- [CI 汇总](../build/hud-native-map-20261030/ci-summary.json)
- [证据包](../build/hud-native-map-20261030/HUD-native-map-offline-evidence-20261030.zip)

本地 Debug 回放默认只预览，不取得 HUD 输出所有权；8 案例各 6 秒，右转案例中演示一次合成栅格图显隐，其余案例仅显示对应路线。显式 `project_hud` 才申请原厂门控并复用输出链路；本轮未执行该设备模式，未安装 `.mazdatest`。

最终验证结果：

- 按 `.github/workflows/android.yml` 执行完整命令，`BUILD SUCCESSFUL`，耗时 15 分 59 秒；476 项任务，87 项执行、389 项为最新状态。包含单元测试、lint、Debug 构建及 Mazda Release 源码／Manifest 编译。
- 单测总计 3906 项：3901 通过、0 失败、0 错误、5 项已有 Windows 环境跳过（三项符号链接权限、两项依赖 `/bin/sh` 的测试）。本轮新增 14 项测试，没有增加跳过项或修改跳过条件。
- Python 证据工具测试 16 项通过；公共源码检查、模块边界、APK 包含检查和 `git diff --check` 通过。
- OEM 驱动只进入 Mazda 包；mobile / home / maphost 未引入车型驱动；Mazda Release 源码及 Manifest 不包含 Debug 回放入口。未构建或安装带认证素材的实机 Release APK。
- 18 张原尺寸 PNG 已自行审图，未要求人工确认。图层隐藏后逐像素等于最新底图，结束／过期／关闭均全黑。图像尺寸与 SHA-256 由清单和证据打包脚本复核。

以上图片的 `carPlayGeometryVerified`、`carPlayJunctionImageVerified`、`hudDisplayVerified` 均为 `false`，不能作为真实手机或台架验收凭据。

## 6. 下一次真实设备验收

先确认 CarPlay 导航源实际提供的是“已经合成的辅助视频”，还是“独立路口图片 + 显隐事件”。前者沿现有视频通路检查，后者完成来源适配后再使用本轮图层接口。真实数据应与手机当前选中路线严格一致，并验证开始、跨路口、重算、结束及断连清屏。

仅取得 TBT、车道角度、状态 Binder 或连续视频帧，仍不足以确认真实道路几何／路口放大图接入成功。本轮不宣称已实现原厂引擎的 3D 或摄影式实景图。
