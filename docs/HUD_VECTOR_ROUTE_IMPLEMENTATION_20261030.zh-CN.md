# HUD 178×178 矢量路线绘制落地

日期：2026-10-30。范围：通用路线场景契约、Android 绘制、有限样本回放和现有 Mazda HUD 像素输出接口。**本轮完成离线实现，不等于获得了高德／Apple 地图的真实路线几何，也不等于 HUD 台架验收通过。**

后续调整：[按原厂分层方案实现](HUD_NATIVE_MAP_IMPLEMENTATION_20261030.zh-CN.md)。本文保留初版基线（青色路线、74% 车标、5Hz）的记录；当前 Mazda Debug 回放已使用后续的绿色路线、80% 车标、10fps 策略及独立栅格路口层。

## 已实现

| 部分 | 实现与边界 |
| --- | --- |
| 本地路线场景 | `display-api/RouteScene.kt`：局部米制坐标、路线折线、可选周边道路、位置、航向、缩放、会话与接收时间 |
| 输入约束 | 路线最多 256 点，周边道路最多 32 条，单条最多 256 点，总点数最多 2048；校验有限值、范围并防御性复制 |
| 投影 | 道路与路线共用车头朝上变换；车标位于窗口宽度 50%、高度 74%；逐段裁剪，视野外的折线不产生虚构连接 |
| 绘制 | `display-android/RouteSceneRenderer.kt`：纯黑背景、4px 灰色道路、10px 青色路线、黑色描边与白色固定车标；尺寸基于 178 像素设计稿同比缩放 |
| 时效与清除 | `RouteSceneStore`：会话、递增版本、单调接收时间；默认 3 秒未更新即不再返回旧场景，结束／到达／关闭后清除；拒绝未来时间和过期输入 |
| 可复用输出 | `RouteMapSession` 将 Canvas 画面提交给现有 `VehicleMapOutput`，使用独立渲染线程、5Hz 重绘；解绑完成后才允许调用方释放 Surface |
| 样本回放 | Debug 专属 `HudRouteReplayActivity`：8 个合成案例，各回放 6 秒，合计约 48 秒；默认仅主屏预览，离开页面／点击停止即清理 |
| 离线图像证据 | 使用 Android 原生图形测试输出 19 张 178×178 PNG：8 案例各 2 个位置，加过期、结束和关闭清屏 3 张；附 HTML 与 SHA-256 清单 |

当前为二维车头朝上视图。透视、渐隐、真实路口实景图片不在本轮实现范围内。右侧只画路线和道路，不重复左侧多车道箭头或距离文本。

## 分层与现有行为

```text
未来来源适配器：选中路线的真实几何、位置与航向
                     │
                     ▼
             display-api / RouteScene
            校验、会话、时效、投影
                     │
                     ▼
     display-android / RouteSceneRenderer + RouteMapSession
                     │ MapSurfaceConsumer / VehicleMapLease
                     ▼
            vehicle-mzd / VehicleMapOutput
           原有导航焦点门控和178×178窗口
                     │
                     ▼
              原厂虚拟屏 → Linux HUD
```

- 通用契约没有 Android、CarPlay、HiCar、CarLink、Android Auto 或 OEM 协议字段。后续来源适配器负责坐标投影、路线匹配与路线版本一致性。
- `RouteScene` 是完整局部窗口，路线、道路和车标坐标必须由来源放在同一坐标系中。换局部坐标原点时必须整窗更新；不能混用旧路网或另一条规划路线。
- 没有修改 `NavigationState`、原厂 Binder 的编码、Linux HUD 文件或媒体发送逻辑。左侧 TBT 与车道继续使用现有结构化路径。
- 生产应用继续走现有手机辅助视频。**没有把 TBT 自动切到样本路线，也没有无真实数据时自动显示假地图。**
- 同一个 `VehicleMapOutput` 的当前 lease 只能交给一个像素生产者。未来选择矢量来源时，由装配层关闭旧视频 lease，再连接矢量生产者，不能同时抢同一 Surface。
- Debug 回放复用同一个 `HudBenchOwnership`，真实投屏来源优先，并使用同一车型工厂。普通预览不打开车型驱动；物理 HUD 模拟需要显式参数和启用 HUD 的 Debug 构建。

## 场景输入约定

| 字段 | 来源侧要求 |
| --- | --- |
| `session` | 与本次导航源会话一致；换手机／换会话必须新建 |
| `revision` | 同一会话内严格递增，包含结束与重算更新 |
| `sourceRouteId` | 手机当前选中路线的标识；不能使用另一引擎另算路线冒充 |
| `route` / `roads` | 同一局部坐标原点下的东、北米制坐标；不使用车道箭头角度拼路网 |
| `position` / `headingDegrees` | 与这些道路对齐的导航位置和顺时针从北起算的航向 |
| `viewSpanMeters` | 来源决定的视野范围，沿窗口短边覆盖 20～5000 米；与具体屏幕像素尺寸无关 |
| `receivedAtMillis` | DiPlay 接收时的单调时钟；不传 iPhone 墙上时间 |
| `origin` | 真实导航或 `SAMPLE_REPLAY`；默认拒绝样本，样本通过时始终绘制 `SIM` 标记 |

没有经纬度解析或地图 SDK 依赖，数据源需先生成局部米制坐标。当前来源适配尚未给实时 CarPlay 构造 `RouteScene`。

## 合成回放与证据

8 个案例：直行、右转、左转、分叉、环岛、掉头、近距缩放、重算后左转。均为本仓库编写的非地理样本，不来自用户位置或真实路线。

入口与结果：

- [离线截图索引](../mazda/build/reports/route-replay/index.html)：每组同时显示 178 原尺寸与 2 倍预览。
- [图像清单](../mazda/build/reports/route-replay/manifest.json)：PNG 尺寸、散列与样本来源。
- [8 案例总览](../build/hud-vector-20261030/preview.png)及[离线证据 ZIP](../build/hud-vector-20261030/HUD-vector-route-offline-evidence-20261030.zip)。ZIP 解压后打开根目录 `index.html`；包内 `archive-manifest.json` 覆盖全部归档文件的大小和 SHA-256。
- `carPlayGeometryVerified=false`、`hudDisplayVerified=false` 保持不变。原生 Canvas 出图不能证明 HUD 视频链路或手机数据接入通过。

无需设备的针对性检查：

```powershell
.\gradlew.bat :display-api:test :display-android:testDebugUnitTest :mazda:testDebugUnitTest --tests '*Route*'
```

在已安装的 Debug 开发环境中，可显式启动本机预览；下列命令为后续台架操作说明，本轮未运行：

```powershell
adb shell am start -n com.thunder.carplay.mazdatest/com.shilapi.xcertplay.mazda.hudbench.HudRouteReplayActivity
```

台架 HUD 模拟还需 Debug 构建的 `-PmzdHudEnabled=true`，并在启动时增加 `--ez project_hud true`。该模式会发送模拟导航以打开原厂导航门控，**左侧数值同样为模拟值**，右侧保留 `SIM` 标记。实际导航会话存在时拒绝启动；会话接管、页面离开或 48 秒结束后释放本任务输出。相同设备不要同时运行其他 HUD 测试任务。

正式包仍是 `com.thunder.carplay`。本轮没有安装、重新引入 `.mazdatest` 或连接 ADB；上述 Debug 入口和样本均不包含在 Release 类或 Manifest 中。

## 真实 CarPlay 数据核查

| 当前路径 | 本地代码状态 | 本轮可得结论 |
| --- | --- | --- |
| `0x5201` | 解析导航阶段、路名、距离、剩余时间和活动车道索引 | 可用于 TBT；没有连续路线折线 |
| `0x5202` | 解析动作、路名与通行侧 | 当前适配未输出局部路线几何 |
| `0x5204` | 解析车道与角度；继续只给左侧车道行 | 不是道路中心线或周边路网 |
| 辅助视频 type 111 | 已有手机生成的地图画面，可继续使用 | 画面像素不是带拓扑与位置匹配的路线折线 |
| iAP2 文件接收 | 当前只接受音乐封面 | 尚无已确认路口放大图片入口 |

此前 RGI 源码研究中的 `0x5202` 路口类型、分支角度和出口角度可能支持**示意路口**，仍不能给出真实道路曲线、长度、周边道路和完整路线。它们在当前高德会话中是否实际发送、字段生命周期及方向约定，需要后续真实数据核验；本轮没有推定可用或擅自接入。

因此，目前可交付的是可运行的绘制与验证基础。实时矢量路线仍需取得与手机当前导航一致的局部折线、定位和航向；若手机只提供辅助视频，则继续使用视频。只出现 TBT 时保留左侧指引，不虚构右侧道路。

## 与参考项目的关系

参考 [moto-gps-waveshare 的协议与分层说明](https://github.com/mx3353672833-debug/moto-gps-waveshare/blob/2d29a00b4b19ed0b2660f7083bb9d1f6a1c9fcfe/shared/protocol/ble-navigation-v1.md) 中“手机准备局部窗口、显示端负责呈现”的职责划分。本轮使用自己的 Kotlin、Canvas 实现和合成样本，没有导入其 C++、BLE 协议、图片、地图资源或其他源码。

参考仓库采用 [PolyForm Noncommercial 1.0.0](https://github.com/mx3353672833-debug/moto-gps-waveshare/blob/2d29a00b4b19ed0b2660f7083bb9d1f6a1c9fcfe/LICENSE.md)。本轮不引入该项目依赖。参考版本和来源清单保存在本地 `build/hud-vector-20261030/research.json`。

## 验证状态

- 新增 11 项纯 JVM 几何／时效、Android 像素、Surface 生命周期和 Debug 回放测试全部通过。最终接口采用米制视野范围，已复验不同画布尺寸下的等比投影。
- Python 证据工具 16 项、模块边界、公开树检查与 `git diff --check` 通过。
- `.github/workflows/android.yml` 完整 Gradle CI 通过：476 个任务，耗时 18 分 58 秒。共 3892 项单测，3887 项通过、5 项跳过，失败与错误均为 0。5 项跳过均为已有 Windows 限制：3 项符号链接权限、2 项要求 `/bin/sh` 的引用测试，本轮未增加跳过。日志：`build/hud-vector-20261030/ci.log`；统计与完整跳过清单：`build/hud-vector-20261030/ci-summary.json`。
- `check_vehicle_display_boundaries.py --apks --release` 通过：OEM 实现仅进入 Mazda 应用；Release 类与 Manifest 无 Debug 回放入口。环境沿用已验证的 Java 25、Android SDK、Windows NDK 平台变量和 `DIPLAY_TEST_SHELL`。
- 中间一轮 CI 为修正通用契约中与 178 像素绑定的缩放字段而主动中止，日志保留为 `build/hud-vector-20261030/ci-before-neutral-span.log`；此日志不是最终 CI 通过凭据。
- 实时 CarPlay 几何接入、178×178 HUD 台架画质、动态导航一致性：未执行，不能归为通过。
