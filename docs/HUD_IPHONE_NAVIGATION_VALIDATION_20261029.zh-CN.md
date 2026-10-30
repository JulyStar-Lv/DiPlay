# iPhone 导航 → DiPlay → HUD：Windows 台架验证

日期：2026-10-29。目标是用真实 iPhone 导航检验既有 TBT 接入。公共导航模型保持中立，J90K Binder/图标/数值边界留在 `vehicle-mzd`。

## 1. 先验证静止导航

不需要模拟 GPS 即可开始第一轮：

1. 打开 DiPlay，确认 iPhone 已连接 CarPlay；iPhone 联网并开启地图定位。
2. 启动下方采集工具，等终端出现 `Capture ready`。
3. iPhone 打开 **Apple 地图**，选附近 1–3 km 的目的地，选择驾车路线，点 **出发**；保持约 60 秒。
4. 结束导航，保持约 15 秒；再开始同一路线，保持约 30 秒，再结束。
5. 采集工具自动结束后，代理检查原图与数值事件。无需人工确认截图。

Apple 的实际 CarPlay 导航入口见 [Apple 支持](https://support.apple.com/zh-cn/guide/iphone/iph215b053f6/ios)。静止启动适合观察首个导航项目与取消；地图是否给出完整字段需以收包为准。预览路线不等于开始逐步导航；没有移动不能据此验收连续经过路口、重算或到达。

## 2. 自动采集命令

需要本轮包含导航诊断的新 Mazda release，HUD 开关启用。先连接真实 iPhone，再运行：

```powershell
Set-Location C:\WorkSpace\CodeSpace\DiPlay
$python = 'C:\Users\Shine\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'
& $python scripts/hud_navigation_capture.py --serial 100.64.122.114:5555 --duration 180 --interval 5
```

输出在 `build/hud-navigation/<run UUID>/`：

- `events.json`：白名单数值事件，不保存原始 logcat、道路、目的地、经纬度、联系人或媒体正文。
- `hud/*.png`：Linux screen 0 原图，保留实际 800×400 或 800×480 尺寸。
- `report.json`：导航帧、有效引导、缺失字段、焦点回调、写入、截图统计。
- `index.html`：离线审图索引。
- `evidence.zip`：上述文件与包含大小、SHA-256 的 manifest；生成后自动核验身份和摘要。

采集仅观察 release 进程日志并请求原厂截图，不注入导航、申请焦点、重启 App 或停止原厂进程。Ctrl+C 仍生成部分证据。ADB 或日志流中断保留已有证据；App 重启/PID 变化后需重新启动采集。日志起点使用设备时钟并跳过第一个不完整秒，避免把历史帧算进本轮；若设备时间跳变，重新采集。

`displayVerified` 始终为 `false`，代表等待代理审图，不能把成功 IPC 当作出图通过。截图仍可能含 HUD 自身显示的歌曲或来电文字，分享前检查。原始证据保留在 ignored `build/`。

### 诊断层次

| 层次 | 事件 / 含义 | 不能据此声称 |
| --- | --- | --- |
| iPhone 输入 | `stage=source`，5201/5202，valid/changed/active、phase、maneuver、距离/剩余距离/秒数 | 地图界面显示就一定有 TBT；缓存未变等于解析失败 |
| 车型门控 | `stage=gate`，ready、missing_distance/remaining/time、stale、各字段 range | 缺值可编造为 0；超过范围可截断后算正确 |
| 原厂授权 | `stage=focus granted=true/false`，仅记录当前注册有效的焦点回调 | 请求返回成功就已授权 |
| 实际写入 | `stage=output action=mode_on/sent/clear`；sent 在转向与剩余信息两次 Binder 调用返回后记录 | 接收端一定画出了相同内容 |
| HUD 原图 | 逐张检查转向、距离、剩余信息、取消清空及恢复 | 离散截图覆盖每一帧、证明声音或右侧地图 |

本固件调整后的数值门控：下一动作距离 0..131070 m、剩余距离 0..9999000 m；剩余时间换算为秒后 0..65534，负毫秒拒绝；5201 状态超过 90 秒过期。收到不完整/超界数据会等待或清除，日志说明原因。旧版曾将剩余距离也限为 131070 m、超时设为 30 秒；真实手机发现的问题及修正见第 6 节。取消/到达/会话关闭不等待超时。

## 3. Windows 上连续模拟路线（需 USB，当前不采用）

用户已确认 iPhone **不能通过 USB 连接这台 Windows 电脑**。本轮执行第 1 节真实静止导航，以下开发定位流程仅保留为其他具备 USB 条件的环境参考，不要求为本轮安装驱动或开启手机开发者模式。

建议拓扑：**iPhone USB 接 Windows；无线 CarPlay 接台架；Windows 通过 ADB 采 HUD。** 这是待实测的组合，最终以 Apple 地图蓝点移动、5201/5202 变化和 HUD 原图共同确认。

工具采用 [pymobiledevice3](https://github.com/doronz88/pymobiledevice3)，本地独立环境固定为 **11.47.0**，位于 ignored `build/iphone-tools/`。不修改全局 Python 环境。

需要 iPhone 信任电脑、[开启开发者模式](https://developer.apple.com/documentation/xcode/enabling-developer-mode-on-a-device)，以及匹配系统版本的开发者镜像（DDI）。Windows 还需要可用的 Apple USB 设备驱动，可先按 [Apple 设备使用手册](https://support.apple.com/zh-cn/guide/devices-windows/welcome/windows)配置并确认电脑能识别手机。先确认 iOS 版本，再选服务路径；不能把下面 iOS 17+ 命令套到 iOS 16。

```powershell
Set-Location C:\WorkSpace\CodeSpace\DiPlay
$iosPython = '.\build\iphone-tools\Scripts\python.exe'
# 只枚举连接与版本；输出含设备身份，勿直接加入分享包。
& $iosPython -m pymobiledevice3 usbmux list
& $iosPython -m pymobiledevice3 lockdown info
# 按当前 iOS 下载/挂载开发者镜像；开发者模式及手机确认需先完成。
& $iosPython -m pymobiledevice3 mounter auto-mount
```

按项目当前 [隧道说明](https://github.com/doronz88/pymobiledevice3/blob/master/docs/guides/ios17-tunnels.md)，iOS 17.4+ USB 可自动建立进程内隧道，通常无需管理员；iOS 17.0–17.3.1 在 Windows 需要额外驱动和有权限的 `tunneld`。若本地固定版本行为不同，以该版本 `--help` 与实机错误为准。

### 路线准备及回放

1. 准备一条实际道路的 **WGS84 GPX track**，使用 `trk/trkseg/trkpt`，每个点带递增的 `<time>`；建议 1 秒一个点，按约 20–40 km/h 安排时差，包含左右转。不要把国内地图显示坐标直接当 WGS84。
2. 不使用 Xcode 的 `wpt` 样例替代 track：pymobiledevice3 解析的是 track points。没有合法时标会使点连续跳跃，不能验收距离递减。实现依据见 [路线解析与时间回放源码](https://github.com/doronz88/pymobiledevice3/blob/master/pymobiledevice3/services/dvt/instruments/location_simulation_base.py)。
3. 用起点经纬度设置位置，确认 Apple 地图蓝点已到起点；在 Apple 地图选择与 GPX 对应的目的地并点“出发”。
4. 在另一个终端启动 HUD 采集，然后回放 GPX，观察蓝点是否沿预期道路移动。
5. 结束后明确执行 `clear`，确认手机恢复真实定位。不要仅凭命令退出判断已恢复。

iOS 17+ 对应命令（`LATITUDE`、`LONGITUDE` 和路线文件需替换为真实测试输入）：

```powershell
& $iosPython -m pymobiledevice3 developer dvt simulate-location set -- LATITUDE LONGITUDE
# set 命令可能等待回车；转入路线前按工具提示结束，然后执行：
& $iosPython -m pymobiledevice3 developer dvt simulate-location play '.\build\hud-navigation\route.gpx'
# 不使用 --disable-sleep；测试结束后执行：
& $iosPython -m pymobiledevice3 developer dvt simulate-location clear
```

命令依据见 [定位 CLI 源码](https://github.com/doronz88/pymobiledevice3/blob/master/pymobiledevice3/cli/developer/dvt/simulate_location.py)。模拟定位能否被 Apple 地图采用，以及是否继续输出真实 CarPlay TBT，均需台架实测。手机上没有通用、已确认可替代此流程的 Apple 地图“模拟驾驶”开关。

### 无 USB 的后续候选

仓库已有 `AndroidCarPlayLocationProvider` → `Iap2LocationReporter` → iAP2 LocationInformation 的车机位置转发链路：读取 Android GPS/fused/network 的最新位置，编码为 GGA/RMC，收到手机位置订阅后发送。可研究用独立台架定位工具在 Android 侧回放路线，再经现有通道传给无线 CarPlay。

这只是基于现有代码的接入候选，**本轮没有新增定位注入入口，也没有证明 Apple 地图采用该路线**。后续需逐层验证车机位置更新、手机订阅、发送、地图蓝点移动及真实 TBT；不能把合成的 HUD 导航快照当作手机已接受模拟位置。测试定位应放在独立台架工具，继续保持 release 无 debug 台架入口和公共导航模型的分离。

## 4. 真实 iPhone 验收矩阵

| 项目 | 触发方式 | 核对内容 |
| --- | --- | --- |
| 开始引导 | 静止点出发 | phase、首个转向、数值完整性、焦点与原图 |
| 转向距离变化 | GPX 接近路口 | HUD 与源事件距离一致、单位/临界值 |
| 左右/轻微/急转/掉头 | 选择实际包含该动作的路线 | iPhone maneuver → 车型图标 → 原图 |
| 环岛 | 选择有环岛的路线 | 行驶侧、出口映射；未遇到则保留未验证 |
| 剩余距离/时间 | 持续回放 | 原始数值变化与 HUD 对照 |
| 取消与重新开始 | 结束，再开启同一路线 | ENDED、清空、相同值重新绘制 |
| 到达 | 回放至目的地 | ARRIVED/ENDED 与实际清空行为 |
| 重算 | 沿另一条有效道路偏离原路线 | 新转向、旧距离清理、缺值等待 |
| 音乐共存/退出恢复 | 导航期间播放音乐，结束导航 | TBT、歌曲区域与恢复，无原厂覆盖 |
| 电话共存 | 另行人工真实通话 | 本轮不自动拨电话，保留单独验收 |
| 右侧路线地图 | 另行开展视频管线接入 | 当前辅助地图能力仍关闭；TBT 通过不能算右图通过 |

已有 `hud_bench.py --mode both --suite navigation` 是快照与协议合成回放两层，继续用于全转向/边界回归；不能把它当作真实 iPhone 动态路线已验收。

## 5. 本轮执行记录

实现：新增来源诊断与具体车型门控原因、有效焦点回调与写入日志、真实会话只读采集工具及证据测试。本轮没有自动开启手机开发者模式或修改手机定位；动态测试仍取决于 iOS/USB/DDI 与实际 GPX 路线。

- 完整 CI：`BUILD SUCCESSFUL in 17m 15s`，407 任务，89 执行、318 最新；日志 `build/hud-navigation-ci-20261029-r2.log`。
- 当前单元报告 3405 项，失败/错误/跳过均 0：vehicle-mzd 39、shared 1692、common 1646、mazda 22、home 6。本轮新增 3 项覆盖脱敏诊断、诊断异常不影响数据/退出、门控原因与输出一致。
- Python 证据测试 9 项通过；公共树、OEM 源码与各 APK 隔离、release 排除 debug 台架入口检查通过。
- 带原有认证及签名的 release 构建通过，`1m 53s`、169 任务。沿用 `mzdHudEnabled=true`、来源 20、自动 A2DP 交接。普通构建默认值保持原配置。
- 新 APK：`build/hud-navigation/DiPlay-Mazda-HUD-Navigation-20261029.apk`；SHA-256 `ea1bb21fdc7a8e37c35131ea65a3465d6b88b1e3ffa447c3e239765423edacfc`。与原始构建输出 `mazda/build/outputs/apk/release/DiPlay-Mazda-release-0.2.21-20261029.apk` 内容相同。
- 安装前版本已保存为 `build/hud-navigation/before-navigation-private-20261029.apk`，SHA-256 `57b9d392b7fe41217c53b48f4832ee411188180997d093060be564ce6467ef18`。安装状态单独记录，构建成功不能当作已安装。
- **新导航 release 已安装并启动，设备内 APK SHA-256 与本地新包一致。** 前三次标准覆盖安装曾返回 `INSTALL_FAILED_INVALID_APK: Vecentek failed to verify apk file name`；用户恢复开发安装通道后，以原始 release 文件名流式覆盖安装返回 `Success`，启动 Activity 返回 `Status: ok`。本轮没有改动原厂安装器或跳过验证，未卸载或清除原应用数据。状态记录：`build/hud-navigation/install-status-20261029.json`。
- 新工具安装前基线采集 `build/hud-navigation/preinstall-baseline-r2/c23b7a3d-1aeb-4dff-be51-76a2782c2aa4/`：3 张 800×480 HUD 原图，代理检查首张为基础车速与续航画面，没有导航；未收到新诊断事件，结果 `no_source_data`，未计导航通过。首次固定为 800×400 导致的采集失败保留在 `preinstall-baseline/`，尺寸已修正。
- Windows 独立环境 pymobiledevice3 11.47.0 已安装；本地 `simulate-location` 及 `play --help` 均通过。此前 USB 枚举返回 `Failed to connect to usbmuxd socket`，未发现 Apple Mobile Device 服务。用户随后明确无法 USB 连接本机，因此本轮不继续配置该路径；未执行定位 set/play/clear，没有真实 GPX 动态导航结果。
- 安装后 10 分钟真实导航采集已正常结束，PID 19904，目录 `build/hud-navigation/iphone-static-20261029/431501a9-aaba-4e04-bccf-e0356f758406/`。107 张 800×480 HUD 原图均已通过联系图审阅，并逐张打开关键短路线原图核对；本轮实际地图 App、结果与限制见下节。原始 `report.json` 的 `displayVerified=false` 保留，审图结论另存，避免把采集完成自动等同显示通过。

## 6. 真实手机结果与问题修正

### 实际测试来源

原先建议使用 Apple 地图，但车机主屏私有截图明确为 **CarPlay 内的高德地图**，底部带高德标识。本轮应记录为「iPhone 高德 → 无线 CarPlay → DiPlay → 原厂 Binder → HUD」，不能计为 Apple 自带地图验收。主屏包含位置与手机界面，仅保留在 ignored `build/`，不加入分享证据包。

正式采集收到 39 个来源事件、无无效帧，其中 29 个有效 GUIDING 事件；10 次导航写入、1 次焦点授予，107 张原图。额外读取的 `post-completion-events.json` 保留采集窗口后的白名单事件；与正式窗口分开记录。

| 项目 | 本轮证据 | 结论 |
| --- | --- | --- |
| 短路线 TBT | 源为 STRAIGHT / 375 m / 2200 m / 300 s；592.3 s、597.9 s 原图显示直行、375 m、2.2 km、5 分 | 真实手机端到端显示通过 |
| 音乐共存 | 同两张原图有蓝牙来源图标与滚动的曲名/歌手 | 文字共存通过；截图不证明音频与实体键行为 |
| 长路线输入 | 源为 3200 m / 1018000 m / 37680 s，与高德主屏 3.2 km / 1018 km 一致 | 单位解析正确；旧版被 `remaining_range` 拦截，未算端到端显示通过 |
| 静止持续引导 | 5201 稳定约每 59 秒更新；旧版每次在第 30 秒进入 stale 并清除，下一帧才恢复 | 已定位驱动超时过短 |
| 取消/到达 | 长路线退出收到 ENDED；短路线额外日志仍为 GUIDING，期间发生 stale | 未验收真实短路线取消清屏；不能以 stale 清除冒充用户取消 |
| 动态转向、重算、到达 | 本轮静止，未形成动态位置轨迹 | 未验证 |
| 右侧路线地图 | 未接入视频/几何管线 | 未验证；TBT 成功不代表右图成功 |

### 长剩余里程：独立 Binder 验证

先暂停 DiPlay，隔离真实会话与合成探针的 NAV 焦点；探针结束后清除、释放并注销回调，重新打开 DiPlay。目录：`build/hud-navigation/long-remaining-probe/feb0d458-76e0-459b-8404-8636f76f7039/`。

- 原厂 Android `HUDDataTransfer.sendNavigateRemainInfo` 将剩余距离和秒数各写为小端 Int32，无 131070 门控。该编码事实本身不证明 Linux 显示边界。
- 实际截图确认 1018000 m 显示 **1018 km**、9999000 m 显示 **9999 km**；37680 s 显示 **10 时 28 分**。131071 m 也可作为正常剩余距离显示；动作距离仍保留原来的空值边界。
- 9999001 m 在该布局同样显示 9999 km。本次保守采用恰好 9999000 m 为驱动上限，不将其宣称为硬件极限，也不直接放开全部 Int32。
- 这是「shell 合成 Binder → HUD」证明，与真实手机端到端结果分别记录。

### 本次代码调整

改动限定于 `vehicle-mzd` 策略及 Mazda debug 用例，不改公共导航模型、其他车型、原厂媒体或 Linux 文件。

1. 将剩余距离门控与动作距离分开，前者允许至本次验证的 9999 km，缺值、负值和超界继续拒绝。
2. 有界更新窗口调整为 90 秒，容纳本轮约 59 秒的真实静止更新间隔；不伪造来源接收时间，不以本地发送心跳延长来源寿命。ENDED/ARRIVED/端点关闭立即清理。
3. 负的剩余毫秒在换算前拒绝，避免 -1..-999 ms 整除后误变成 0 s。
4. 新增边界、稀疏更新续期及取消/关闭即时清理单测；N10 更新为 90 秒静默过期，新增 N11（59 秒更新）和 N12（长剩余里程），两层计划增至 27+27 项。

### 修正后的回归状态

完整 CI 已通过：`BUILD SUCCESSFUL in 1m 20s`，407 任务（57 执行、350 最新），日志 `build/hud-navigation-ci-20261029-r3.log`。当前 debug 单测 3408 项，失败/错误/跳过均 0：vehicle-mzd 42、shared 1692、common 1646、mazda 22、home 6。统计只读取本次 CI 对应 `testDebugUnitTest`，不混入仓库留存的历史失败目录。Python 证据测试 9 项通过；公开树、OEM 源码/APK 隔离与 release 无台架入口检查通过。

带原有认证和签名的修正版 release 构建通过，30 秒、169 任务；继续使用 HUD 开启、sourceType 20、自动 A2DP 交接。新包为 `build/hud-navigation/DiPlay-Mazda-HUD-Navigation-r3-20261029.apk`，SHA-256 `e535555a898f58c079c4740d3cfb551b7daff5440c701a16af890780c48a2e1b`。安装与修正后真实手机验收单独记录，不能由构建结论代替。

两层台架导航回归已完成并审图：run `564a34fa-6ab7-410d-ba5a-700f7596fe19`，`snapshot` 12 项 + 合成 iAP2 `replay` 12 项，共 24 项执行成功、133 张 HUD 原图、截图失败 0。来源预期均匹配，没有驱动/清除错误；六张联系图覆盖全部项目截图，基线单独打开，关键超时/续期原图另按原尺寸打开。代理结论保存在该目录的 `agent-review.json`，原始自动报告仍保留 `displayVerified=false`。

- 两层 N10：32/60/89 秒仍显示，92 秒已清除。
- 两层 N11：59 秒输入更新后，92 秒仍显示；关闭端点立即移除。
- 两层 N12：131 km、1018 km、9999 km 及 10 时 28 分均显示；退出清除。
- N01–N09：基本转向、掉头、环岛出口、未知转向、缺值补齐、到达/取消及各项退出清除符合固定输入。该结果仍属于合成测试，不替代真实手机动态导航。

修正版 release 已覆盖安装并启动：标准 `adb install -r` 返回 `Success`，设备 `/data/app/.../base.apk` SHA-256 与上面的 r3 新包一致，当前 PID 8103。状态：`build/hud-navigation/install-r3-status-20261029.json`。未卸载、未清除原有数据；两层 debug 工具已停止，避免竞争 HUD 焦点。

修正版真实手机复测已完成，目录 `build/hud-navigation/iphone-r3-20261029/e0aeb45b-8504-4bb0-82eb-2cd522cb4813/`。用户报告完成后，代理仅结束本轮自有的电脑端 logcat 子进程，采集器正常归档；实际窗口 571.42 秒，原始完成原因保留为 `log_stream_ended`，操作记录为 `capture-end-request.json`。DiPlay PID 8103 保持运行，未停止手机会话。复测结论见第 7 节；Apple 自带地图仍需另轮验收。

可分享证据：`build/hud-navigation/HUD-navigation-review-20261029.zip`，离线入口 `build/hud-navigation/review-20261029/index.html`。包内仅含白名单 HUD 原图、脱敏数值、独立 Binder 探针、两层合成报告、代理审图结论及校验摘要；不含主屏位置截图、原始手机帧、APK 或认证签名材料。

## 7. 修正版真实 iPhone 复测结论

**高德 CarPlay 的静止持续引导、明确结束清屏、音乐文字共存通过。** 本轮只做只读验收和原厂资源分析，没有再次修改/安装 App，也未改写 Linux 资源。

### 持续引导与结束

| 项目 | 证据 | 结论 |
| --- | --- | --- |
| 输入与画面 | STRAIGHT / 375 m / 2200 m / 360 s；HUD 为直行 375 m、2.2 km、6 分钟 | 数值与单位一致 |
| 稀疏更新 | 来源间隔包含 59.286 s；首个完整 GUIDING 至 ENDED 为 110.171 s；108 次输出，最大相邻间隔 1.022 s | 跨过原 30 秒及 90 秒时点，无 stale；不是停止更新 90 秒后仍无限保留 |
| 结束清除 | 明确 ENDED：1793260626.220；清除调用：1793260626.450；退出后的原图及约 15 秒后原图均无 TBT | 清除正确，音乐文字继续显示 |
| 审图 | 102 张 HUD 原图，5 张联系图全部审阅，首屏、超过 90 秒、退出及轮播图另按原尺寸打开 | 无采集失败；自动报告的 `displayVerified=false` 不改写，代理结论另存 |

实际有效引导约 **110 秒**，不写作“满 120 秒”。230 ms 是 ENDED 到清除 Binder 调用返回的日志间隔，**不是像素清除延迟**；约 5.6 秒一张的离散截图无法测量毫秒级出图延迟。只有静止直行得到真实手机验证，动态路口/到达/重算未覆盖。

关键原图（相对本轮目录）：

- `hud/400bc07d-cb1b-4ec3-bc29-6dca13421c91.png`：316.8 s，375 m / 2.2 km / 6 分钟及音乐文字。
- `hud/0098e32b-ef08-4c0a-ae03-73cd8e652123.png`：401.3 s，引导约 92 秒时仍显示。
- `hud/74527162-ae96-4d3a-81b6-d75d2a5e847c.png`：423.9 s，明确结束后清屏。
- `hud/50b97084-a397-48e2-9265-eb5bb40ff042.png`：435.1 s，结束约 15 秒后仍无 TBT、音乐保持。

### 剩余信息短暂切成续航：原厂轮播

373.2 s 原图出现一次“2.2 km / 6 分钟”被续航 `-- km` 替代，下一张恢复；该时刻转向箭头和 375 m 始终可见，日志没有 stale、失焦或清除。进一步读取原厂资源，确认这是布局动画：

- 当前台架 `/usr/bin/gui_main.kzb` SHA-256 为 `32de3f01f03da75d59f1bd7adab28beb8207fa62183060ff5a2b6746fee3721a`，与离线原厂资源完全一致。
- `/Animation Clips/Languide_ETA_Endurance` 周期原始值为 65000。
- `Animation_Turn_ET_show` 绑定 `TBTInfo/ETA` 的 `Node.Opacity`：60000 时为 1，60001/65000 时为 0。
- `Animation_turn_Endurance_show` 绑定 `gui_basic.endurance.iShow`：同三个时点为 0/1/1。
- 对照实图约为 **60 秒导航剩余信息 + 5 秒车辆续航**。373.2 s 图对应进入导航后约 64 秒，与该时间窗吻合。这一轮播不应误判为 DiPlay 整体导航过期；产品仍沿用原厂剩余信息轮播，未改成常显。

资源条目、偏移、摘要和原始关键帧保存在本轮 `native-footer-animation.json`，时间分析为 `timing-analysis.json`。

台架随后又收到新的真实导航，代理追加 90 秒只读观察：`build/hud-navigation/iphone-r3-additional-20261029/72125ae9-6466-4da7-b83c-4a3c84c6f2e9/`，25 张原图全部审阅。源与画面为 **直行 323 m、24.8 km、28 分钟**。采集第 51.0/54.7 秒切成续航、第 58.3 秒恢复，匹配该条导航的第 60～65 秒轮播相位；两次来源事件均有效，88 次输出，转向及音乐持续可见。仅观察已有真实导航，没有注入测试导航、申请合成焦点或停止 DiPlay；采集结束后保持用户会话运行。

### 当前可交付范围

- 已验证真实高德静止 TBT 出图、59 秒级稀疏更新续期、明确结束后清除和音乐文字共存。
- 1018/9999 km 长里程调整已通过独立 Binder 和 DiPlay 两层合成测试；修正版真实手机超过 131.07 km 的路线尚未重测。
- Apple 自带地图、动态左右转/重算/到达、右侧路线地图，以及音频/实体按键行为仍为各自独立的待验收项。
- 本轮证据新增 102 + 25 张真实 HUD 原图；主屏位置截图留在 ignored `build/`，不放入分享 ZIP。
