# CarPlay 真实路口放大图接入与验证准备

日期：2026-10-29；本地 CI 复跑完成于 2026-10-30。适用当前 J90K 普通 HUD 布局：`MeterDisplay#0` 为 800×480，右侧显示区域为 `(584,179,178,178)`。正式应用包名为 `com.thunder.carplay`。

## 当前结论

目标是用户附件中高德提供的真实道路放大图：出现时优先显示到 HUD 右侧，消失后恢复路线地图；导航结束、断流、失焦时清除旧画面。左侧 TBT 和多车道箭头继续使用原厂 Binder 车道行。

**目前已补齐内容请求模式和取证工具，尚未验证高德真实路口图进入辅助屏，尚未实现并验收上述自动切换。** 附件证明 iPhone 本机可以出现此图，不能证明高德会把同一张图提供给 CarPlay 主屏或仪表辅助屏。

本轮遵照此前安排跳过台架，没有连接 ADB、安装应用或中断 CarPlay。此前普通地图出图通过的记录继续有效，但不作为真实路口图通过的证据。

## 图像来源核对

| 路径 | 已确认的事实 | 不能由此推定的能力 |
| --- | --- | --- |
| 当前 CarPlay 辅助视频 | DiPlay 已有 type 111 辅助流和 HUD 右侧输出链路；本轮增加组合内容请求 | 不能保证高德在该流内发送真实放大图 |
| 高德 iOS SDK | 官方示例通过 `driveManager:showCrossImage:` / `driveManagerHideCrossImage:` 显示、隐藏 `UIImage` | 这是 iOS App 内部回调，Android DiPlay 无法直接调用；旧示例也不代表当前正式高德 App 的输出行为 |
| Apple CarPlay API | `CPManeuver.junctionImage` 和 `dashboardJunctionImage` 提供路口图片语义 | 不等于接收端有独立图片下载接口，也不保证该图出现在 instrument-cluster 视频 |
| 当前 iAP2 导航适配 | 已接导航状态、动作、距离和车道数据，没有已确认的独立路口图入口 | 转向动作与车道角度不足以还原真实道路、桥梁和建筑 |
| 当前 iAP2 文件接收器 | `Iap2FileTransferReceiver` 仅接受 NowPlayingArtworkData | 不能将音乐封面接收能力视为路口图传输能力 |

Apple 文档中的 140×100 是 **points**，不是本车 178×178 像素输出协议。仪表画面可以由手机 App 在独立窗口中绘制，不能假定它与手机或 CarPlay 主屏镜像一致。

一手参考：

- [高德官方 CarPlay 示例固定版本](https://github.com/amap-demo/iOS-HelloCarPlay/blob/077390a4cbced0b96f5a2e5980b7a1bba19ddfbb/HelloCarPlay/MainViewController.m)，提交于 2020-08-21；本地研究副本位于 `build/hud-junction-research-20261029/AMap-MainViewController.m`。
- [Apple junctionImage](https://developer.apple.com/documentation/carplay/cpmaneuver/junctionimage)。
- [Apple dashboardJunctionImage](https://developer.apple.com/documentation/carplay/cpmaneuver/dashboardjunctionimage)。
- [Apple CPInstrumentClusterController](https://developer.apple.com/documentation/carplay/cpinstrumentclustercontroller)。
- [Apple WWDC 2022：What's new in CarPlay](https://developer.apple.com/videos/play/wwdc2022/10016/)。

## 本轮代码改动

### 三种构建内容模式

| `mzdHudMapContent` | 通用内容请求 | CarPlay 来源适配的 URL | 用途 |
| --- | --- | --- | --- |
| `map`，默认 | `MAP` | `maps:/car/instrumentcluster/map` | 普通辅助地图基线 |
| `instruction_card` | `INSTRUCTION_CARD` | `maps:/car/instrumentcluster/instructioncard` | 观察手机指示卡是否在真实路口携带图片 |
| `map_with_instruction_card`，本轮新增 | `MAP_WITH_INSTRUCTION_CARD` | `maps:/car/instrumentcluster` | 请求组合内容，观察是否可以由手机完成地图与真实路口图的切换 |

内容名表示请求意图，**不表示手机已经接受请求或图中必然包含真实路口图**。组合内容也可能只有地图、箭头和文字，甚至无有效图像。

辅助画面继续申请 256×256 / 15fps，HUD 输出完整等比适配到 178×178。普通地图沿用已实现的车标安全区；手机卡片和组合内容使用全画面安全区，避免把卡片当作车标定位区域。内容选择写入 `hudmap action=attached ... content=...` 诊断日志。

职责保持分离：

- `display-android` 定义内容语义和 Surface 契约。
- `shared/.../projection/carplay/CarPlayAuxiliaryMap.kt` 将语义转换为 CarPlay URL。
- `vehicle-mzd/MzdHudMapOutput.kt` 负责本车显示器、178×178 几何和导航门控。
- `mazda` 负责构建参数与 OEM 装配。

本轮未新增 Settings 控件，也未修改原厂媒体发送逻辑或 Linux HUD 接收逻辑。普通构建继续默认关闭 HUD；此次没有引入固定区域裁切，也没有将自绘箭头图作为真实道路放大图。

### HUD 与车机主屏成对取证

`scripts/hud_navigation_capture.py` 新增 `--capture-primary`：每轮依次保存 Linux HUD 原图和 Android 默认屏幕 PNG，并记录 `surface`、`pairId`、各自命令开始/结束的相对时间以及 SHA-256。主屏截图纳入原有 ZIP 和 manifest 散列核验。

限制：

- 这是**顺序截图，不是同一帧同步截图**。短暂图片可能落在两次截图之间；不能据此测量帧延迟或排除所有短时闪烁。
- Android 默认屏幕必须经审图确认实际显示的是 CarPlay。该截图不是 iPhone 本机截图。
- 某一侧失败不阻止另一侧保存；只有 HUD 与主屏均成功才计入 `completeScreenshotPairs`。主屏截图成功不能满足 HUD 截图要求。
- `requestedMapContents` 仅来自本次采集时间窗内的 attach 日志。若连接早于采集开始，列表可以为空；不能据空列表断言没有请求内容。
- `junctionSourceStatus` 保持 `not_determined`，`junctionDisplayVerified` 保持 `false`。视频帧、Binder 返回、完整截图对或内容请求均不自动产生显示通过结论，需另存代理审图记录。

## 下一轮台架执行方案

设备验证恢复后，依次用三种内容构建做同一路线对照。构建参数模板如下；认证和签名沿用本地正式构建流程，默认 CI 产物不能代替可用的正式安装包：

```text
-PmzdHudEnabled=true -PmzdHudMapEnabled=true
-PmzdHudMapContent=map_with_instruction_card
-PmzdHudMediaSourceType=20 -PmzdHudAutomaticA2dpHandover=true
```

内容在应用构建和新 CarPlay 会话中生效。只覆盖安装正式包 `com.thunder.carplay`，不安装 `com.thunder.carplay.mazdatest`。

取证命令模板：

```powershell
python scripts/hud_navigation_capture.py --serial 100.64.122.114:5555 --duration 600 --interval 3 --capture-primary --output build/hud-junction/bench/combined
```

执行时使用当时实际在线的序列号；本轮没有运行该命令。每种模式先打开 DiPlay 使进程运行，启动采集后再建立新的 CarPlay 会话，以保留本轮 attach 内容日志。记录 iOS、高德版本和实际触发场景。`map`、`instruction_card` 分别使用独立输出目录和独立运行记录，避免混淆不同构建的证据。

### 验收场景

1. **普通地图基线**：导航开始后，确认真实地图与左侧 TBT、多车道行正常，没有上次会话残留。
2. **路口图出现**：选择确实能触发真实放大图的路线和场景；由代理检查 CarPlay 主屏与 HUD 原图。请求 URL、收到转向数据、出现普通指示卡均不算此项通过。
3. **路口图消失**：在同一会话中确认右侧恢复当前路线地图，没有旧路口图片或重复车道叠加。
4. **生命周期**：覆盖结束导航、再次开始、断开重连、原厂导航抢焦点；检查旧图清除和新会话恢复。现有门控单测只能证明状态行为，不能代替本轮原图验收。
5. **分辨率与可读性**：完整等比显示；核对关键道路分支和引导路线在 178×178 中可辨认，无异常拉伸或遮挡左侧车道。

静止台架未触发真实路口图时，记录“场景未覆盖”，不能记录为支持或不支持。用户已经要求由代理审图，后续无需用户人工确认截图。

### 根据证据选择后续实现

| 观察结果 | 后续处理 |
| --- | --- |
| 辅助视频本身包含真实路口图，且会自动恢复地图 | 优先沿用手机合成画面，验收现有视频链路与生命周期；无需额外构造图片通道 |
| 只有 CarPlay 主屏出现该图 | 另行评估可靠的出现/消失信号、取图与区域定位；尚不能采用固定坐标裁切作为成品方案 |
| 只有 iPhone 本机有图 | 当前 DiPlay 输入不足；继续查找该 App 的可用输出或合作接口，接收端不能凭空生成原图 |
| 以后确认有独立图片及生命周期消息 | 在来源适配层接入，使用中立像素输出契约；OEM 层仅处理尺寸、门控和清屏，不包含高德或 CarPlay 特定协议 |

## 本地验证记录

- Python 证据工具：16 项测试通过，覆盖单侧失败、时间范围、PNG 拒绝、归档散列，以及请求/截图不能升级为真实路口图通过。
- 本轮相关的 `CarPlayAuxiliaryMapTest`、`MzdHudMapTest`、`MzdHudSourceConfigTest` 三组共 11 项测试通过。
- `check_public_tree.py`、`check_vehicle_display_boundaries.py` 和 `git diff --check` 通过。
- `.github/workflows/android.yml` 的完整 Gradle 命令通过：460 个任务，最终复跑耗时 13 分 46 秒。单测共 3881 项，3876 项通过、5 项跳过、失败和错误均为 0；`:display-api:test` 为 `NO-SOURCE`。5 项跳过均为已有 Windows 环境限制：3 项符号链接权限、2 项要求 `/bin/sh` 的引用测试，本轮未增加跳过。日志：`build/hud-junction/ci.log`；逐模块统计及跳过清单：`build/hud-junction/ci-summary.json`。
- `check_vehicle_display_boundaries.py --apks --release` 通过：OEM 类及元数据仅包含在 Mazda 应用中；正式版无 debug bench 类或组件。
- 组合模式的 `:mazda:processReleaseManifest` 单独构建通过，确认正式包名 `com.thunder.carplay`、HUD 开关及 `map_with_instruction_card` 元数据正确。日志：`build/hud-junction/combined-manifest.log`；核验：`build/hud-junction/combined-manifest-check.json`。随后已恢复默认参数的正式版 Manifest 构建。此检查不等于已构建、安装带认证和签名的台架 APK。
- 首次 CI 因构建进程缺少 Windows 平台环境而失败；仅为本次进程补齐 `OS=Windows_NT`、`HOST_OS=windows` 后重跑，未修改 SDK/NDK 或仓库业务代码来绕过。失败日志保留为 `build/hud-junction/ci-ndk-env-failed.log`。
- 本机 Git 位于 `C:/Software/Git`。`UsbPermissionSetupTest` 默认的 Git 路径不存在，导致 4 项测试无法启动 shell；使用该测试现有的 `DIPLAY_TEST_SHELL=C:/Software/Git/bin/sh.exe` 环境入口后完整重跑通过，不修改或排除测试。中间失败日志：`build/hud-junction/ci-shell-env-failed.log`。
- 台架、真实放大图显示及消失恢复地图：本轮未执行，待下一次设备验证。
