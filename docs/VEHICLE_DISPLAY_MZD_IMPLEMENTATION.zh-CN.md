# vehicle-mzd 首期实现记录

开发与验证：2026-10-27～2026-10-28。基于 [架构方案第 12 节](VEHICLE_DISPLAY_ARCHITECTURE.zh-CN.md#12-模块落地与迁移) 开始实施 M0/M1。模块名按用户要求使用 `vehicle-mzd`，当前原厂 ABI 和兼容规则来自此前 J90K 台架研究，不能泛化为全部 Mazda 车型/固件。

## 1. 已加入的链路

```text
CarPlayController（原连接/音频/后台所有权）
  → shared/projection/carplay/CarPlayDisplaySource
  → display-api 完整领域快照（带会话代次与 revision）
  → vehicle-mzd 的独立串行 worker
  → 同一原厂双屏 Binder，导航/音乐/电话分别持有焦点

Mazda MzdHudApplication 提供工厂 → common 仅通过 VehicleDisplayProvider 注入
```

| 范围 | 首期实现 |
| --- | --- |
| 公共契约 | 纯 Kotlin/JVM `display-api`；导航、音乐、电话、会话、端点能力、非阻塞提交与关闭接口 |
| CarPlay 导航 | 0x5201/0x5202 增量归并、转向缓存、明确取消/到达、未知转向与缺失数值；Apple 编码在来源适配器内转换为语义 |
| CarPlay 音乐 | 旁路接收 Controller 已归并的元数据；现有播放/元数据/封面 listener 保持原入口；不额外请求元数据 |
| CarPlay 电话 | 0x4155 按 UUID 保存多通电话；姓名和号码分别保留，省略保留、显式空清除；接通时间采用单调时钟 |
| Mazda 驱动 | 服务注册表发现、描述符检查、原厂 Parcel、回调和 Binder 死亡处理；三类焦点独立，失焦不循环抢回 |
| 会话所有权 | 每个活动来源绑定端点；原 Controller 的结束/替换/关闭清理；同一 worker 先退旧端点再启用新端点，旧 close 不清新输出 |
| 故障隔离 | Binder 调用在车型 worker；同一端点完整快照合并为最新值；发现失败最多三次尝试，领域失败单独禁用，重连建立新端点 |
| 依赖隔离 | 只有 `mazda` 依赖 `vehicle-mzd`；`shared/common` 不 import 车型模块；车型模块不依赖 CarPlay 或宿主 |

首期未引入通用 `display-core`、动态插件、额外进程/网络总线，也没有迁移 BYD/FCE、MediaSession、Glance 或应用 Runtime。原连接、音频路由、地图 Surface 与设置入口继续使用既有实现。

## 2. 当前显示策略及限制

### 导航

- 支持基本转向、掉头、按驾驶侧显示环岛及 1～10 出口，未知类型不伪造直行。真实 Apple 类型到图标的对应仍需手机验证。
- 当前固件没有已验证的“数值为空”表示；必须同时取得有效的下一动作距离、剩余距离和剩余时间后才进入原生导航模式，缺失时保持等待，避免显示错误零值。
- 路线最后更新超过 90 秒后隐藏并释放导航焦点（2026-10-29 从 30 秒调整：真实静止手机约每 59 秒更新一次）。取消、到达与断连均立即退出当前结构化输出；首期不触发有定时残留的上方到达旗动画。
- 下一动作距离仍限 0..131070 m；剩余距离独立限 0..9999000 m，剩余时间按秒限 0..65534。剩余信息使用两个 Int32 字段，不能套用动作距离的 131071 空值边界；本次 Binder 原图验证了 1018/9999 km。9999 km 是当前驱动保守上限，不代表硬件极限；缺值、负值与超界值继续拒绝，不截断或编造。
- 原厂 TBT 接收器缓存并跳过相同载荷。首次获得导航焦点后，先清除旧内容缓存再提交导航模式，至少 350ms 后提交有效内容；退出模式后也清除缓存，避免相同路线重新进入时只显示“现在”。这些清理写入均需持有自己的焦点。活动导航中每秒最多一次周期刷新，并允许新状态在 worker 节拍更新。固定等待并不代表原厂已经确认显示。
- 道路名/ETA 保留在模型；此前固件未显示通过的文字字段没有作为首期显示承诺。车道、摄像头、路口图及路线几何未加入本次接入。

### 音乐

- 当前固件默认继续使用兼容 `sourceType=27`，可在 Mazda 构建时选择无图标的 `29`。来源选择仅在 `vehicle-mzd` 与 Mazda 装配层，公共模型中没有该字段；真实歌词缺失时将“歌名 / 歌手”组合为原厂当前文字行。
- 依据 2026-10-28 的 38 张截图布局验证，27/29 配合 Linux `gui_main.kzb` 的 `SongNormal` 条件补丁可将文字左移 40px、宽度 176→216px；原厂资源仍为 x=78、宽176。Android 来源选择不会安装补丁，台架在该试验后已恢复原厂。其余有效来源16/18/20/28/35保持原厂布局，16/18的“暂无歌词”不算自定义文字通过。完整来源及构建说明见 [SourceType 调整记录](HUD_MUSIC_SOURCE_TYPE_20261028.zh-CN.md)。
- 原厂 CarPlay 来源 30 在此前台架没有出字，因此当前兼容方案不能称为来源 30 原生显示通过。
- 保留暂停/播放状态与元数据，不操作音频；丢失媒体焦点停止写入，收到原厂恢复授予后应用最新快照。
- 尚未收到播放状态时保持未知并等待，不将第一条曲名元数据的默认 false 当作已暂停。
- 当前 J90K 音乐接收器遇到四字节补充字符会整行不显示。车型边界将这类字符及无效代理项替为空格，保留可显示的 BMP 文字，按 128 字节 UTF-8 边界限制；源快照不改写。组合行给歌手保留空间，避免长曲名完全挤掉歌手。不把换行作为多行显示接口。
- 原厂在线音乐存在 5 秒无消息超时。播放期间每 2 秒发送一次相同媒体载荷；原厂在载荷去重前更新时间戳，预期能维持显示且避免重启滚动。暂停、失去焦点或端点关闭后停止此心跳。
- 最后一通电话结束时，先按自己的媒体焦点清除显示缓存，再由同一 worker 等待 500ms 后提交最新音乐快照。期间的换曲、暂停、失焦或会话替换均按最新状态处理；不新建脱离端点的恢复定时器。
- 当前 CarPlay 已解析元数据没有独立歌词字段，适配器不将动态 title 认定为歌词。公共模型预留当前句及支持级别，未来 ICCOA 可对接真实载荷。
- 首期不传封面文件、不承诺三行歌词、独立封面卡片或进度条。`artwork` 保持 false；`auxiliaryMap` 默认 false，2026-10-29 新增显式构建开关开启的地图通道，见[右侧地图接入记录](HUD_CARPLAY_RIGHT_MAP_20261029.zh-CN.md)。该能力声明不代表真实手机或 HUD 出图已经验收。

### 电话

- 来电优先，其次活动、保持和呼出。姓名与号码分别传入原厂 JSON；原厂决定可见布局，不承诺同时显示所有字段。
- 该固件 `callTimeMs` 实际按秒显示，驱动从通用单调毫秒换算；按实测 16 位限制有界处理。长通话 UI 边界仍待台架验证。
- 保持状态降级为通话中，保留原接通时间。结束最后一通电话时清理并归还焦点，其他通话仍在时显示下一通。
- 当前从首次观察到 ACTIVE/HELD 时建立本地计时起点；若连接时电话已在通话中，尚不能恢复此前已通话时长。真实手机、通话中重连及远端计时字段需要补充验证，不能把该估算当作已恢复原始接通时间。
- 仅显示；原厂按键回调不触发接听、挂断或音频操作，`callControl=false`。

### 尚需验收的边界

当前代码不会使用 shell UID、请求隐藏 API 豁免或修改原厂服务。普通 App 是否获准服务发现、注册、焦点和写入，需要在台架上用正式包名验证。描述符相同也不能证明所有固件行为相同。

进程被强杀时没有可靠 Java 清理回调；当前代码通过普通结束/close 清理自己的资源，但尚未实现新的外部 watcher/journal。强杀残留、系统服务重启与恢复、真实 iPhone 数据及 HUD 实图属于 M1 待验收项。此版本默认不启用该实验路径。

右侧地图、无导航保持导航显示模式、素材仓库和反向控制属于后续里程碑；本次没有实现或宣称这些能力。

## 3. 启用与构建

默认所有构建 `mzdHudEnabled=false`。Mazda Application 返回空工厂，不查询原厂服务。明确验证该台架配置时构建：

```powershell
.\gradlew.bat :mazda:assembleDebug -PmzdHudEnabled=true
```

默认音乐 `sourceType=27`。需要验证兼容来源29时，追加 `-PmzdHudMediaSourceType=29`；当前允许20/27/29，不自动轮换。20为原厂蓝牙外观兼容。该选项同时用于 debug 台架和 release 的 OEM 工厂，不改变原厂服务的来源映射或 Linux 布局。

2026-10-29 新增默认关闭的 `-PmzdHudAutomaticA2dpHandover=true`：仅在 `vehicle-mzd` 内按会话分离/恢复 A2DP，保留 HFP，并通过恢复位处理下次启动。它会改变蓝牙音乐连接状态，原先“不操作连接”和“没有 journal”的描述仅适用于未启用此开关的历史路径。已安装 release 的实际验证与限制见 [自动交接报告](HUD_AUTOMATIC_MEDIA_HANDOVER_20261029.zh-CN.md)。

APK：`mazda/build/outputs/apk/debug/mazda-debug.apk`，包名 `com.thunder.carplay.mazdatest`。这是现有仓库的 source-only debug 构建；如果需要独立连接手机，按既有 `assembleStandaloneDebug` 认证资产流程构建，不能把普通 debug APK 当作已具备认证资产的独立车测包。

本次保留一份启用 HUD 的 source-only APK：`build/outputs/hud/DiPlay-Mazda-HUD-source-debug.apk`。已用 aapt2 检查其合并 Manifest 中 `HUD_ENABLED=true`。SHA-256：`48498ebbcc2590a07fee5f93c424a820965a58747587592be62eb6a6b271f7c6`。默认输出路径会在完整 CI 重建时恢复关闭配置，保留的 APK 独立存放。

启用标记只影响 Mazda 应用合并 Manifest 的 `com.shilapi.vehicle.mzd.HUD_ENABLED`，不修改持久设置。恢复默认时移除 Gradle 属性重新构建。没有新增用户可见设置，因此没有本次设置 UI 或翻译变更。

观察日志标签 `DiPlay-MzdHUD`。默认只记录事务编号、回调状态及异常类，不记录联系人、号码、曲名或歌词。`accepted; display unverified` 仅表示 Binder 接受，不表示 HUD 已显示。

## 4. 验证

后续新增的 debug 两层模拟入口、当前 50 组计划及电脑截图助手见 [台架自动验证说明](HUD_BENCH_AUTOMATION.zh-CN.md)。下列统计是第一期结构化接入的历史验证；首次 42 组实测见 [完整台架报告](HUD_BENCH_VALIDATION_20261028.zh-CN.md)，修复措施见 [修复记录](HUD_BENCH_FIXES_20261028.zh-CN.md)，最终包两层 50 组截图审阅符合的结果见 [复验报告](HUD_BENCH_FIXES_VERIFIED_20261028.zh-CN.md)。这批结果不包含真实手机、光学 HUD 或右侧路线地图验收。

- 定向用例覆盖增量/清空、未知类型、取消与到达、会话替换、多通电话、歌词缺失、非阻塞提交、延迟导航内容、焦点丢失、有限重试、故障领域隔离及 Binder Parcel/描述符/注销。
- 最新定向检查通过：来源适配器 6 项、车型状态/所有权/慢 Binder 8 项、原厂 Parcel 接口 4 项，共 18 项；启用配置的 Mazda APK 构建通过。日志：`build/reports/vehicle-display-architecture/enabled-mzd.log`。
- `scripts/check_vehicle_display_boundaries.py` 检查公共 API、来源、驱动与应用依赖边界；已加入 CI。
- `.github/workflows/android.yml` 增加 `display-api:test`、`vehicle-mzd` 的测试/lint/build，继续运行现有所有检查。
- 完整 CI 已通过：`BUILD SUCCESSFUL in 13m 35s`，365 个任务中 74 个执行、291 个 up-to-date。包含现有完整检查和新增模块检查；日志：`build/reports/vehicle-display-architecture/ci-mzd-verified.log`。
- 测试报告合计 3,367 项，失败/错误/跳过均为 0：`shared` 1,690、`common` 1,646、`home` 6、`mazda` 13、`vehicle-mzd` 12。新增 CarPlay 的 6 项包含在 `shared` 中。汇总：`build/reports/vehicle-display-architecture/test-summary.json`。
- Windows 首次完整回归的 4 项失败来自已有 USB 设置脚本测试找不到 `sh`。通过其已有环境变量 `DIPLAY_TEST_SHELL=C:\Software\Git\bin\sh.exe` 后重跑通过；未修改测试或跳过检查。完整运行同时设置现有 JDK 25、`ANDROID_HOME`、`HOST_OS=windows` 和截图输出目录。
- `python scripts/check_public_tree.py` 及 `python scripts/check_vehicle_display_boundaries.py --apks` 均通过。Mobile / Home / MapHost APK 不含 `vehicle-mzd` 类和 OEM 元数据，Mazda APK 包含两者。
- aapt2 确认完整 CI 产出的默认 Mazda APK 中 `HUD_ENABLED=false`，另存的启用版 APK 为 `true`，且其 SHA-256 与上节一致。

这些结果验证离线行为、构建及依赖边界；真实普通 App 权限和手机到 HUD 的显示效果仍需台架验收，本轮未安装 APK 或采集新的 HUD 实图。
