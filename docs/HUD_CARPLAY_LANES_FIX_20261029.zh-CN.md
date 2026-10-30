# CarPlay 左侧车道引导修复（2026-10-29）

## 目标与当前状态

按 HUD 设计图，车道引导应显示在左下方原厂导航信息行。应是一整排车道，包含灰色非推荐车道、白色推荐车道和单条车道内的多个分支；不能将“单个转向动作”当成整排车道。

本轮完成接收、当前车道组选择、通用数据模型及原厂 Binder 事务 25 的代码接入。右侧安装包使用路线地图配置，不再申请会包含车道箭头的手机 instruction card。右侧手机视频中的具体道路元素仍由导航 App 决定，DiPlay 不修改或裁剪手机视频中的车道。

**本轮台架验证按用户安排推迟至 2026-10-30。当前不标记真实 iPhone / HUD 出图通过。** 本地验证与安装包结果见本文末尾。

## 数据链路

```text
iPhone 0x5204（可提前发送多组路口车道）
    → 严格解析每条车道的顺序、全部角度、推荐状态及高亮分支
    → 按车道组索引缓存，最多 8 组，保留当前显示组
iPhone 0x5201（16 当前车道组索引 / 17 总组数 / 18 是否显示）
    → 只选择当前且被允许显示的一整组车道
    → display-api.NavigationLaneGuidance（不含 OEM 图标编号）
    → vehicle-mzd 原厂图标转换及有效期检查
    → 同一导航焦点下发送事务 24 / 27 / 25
    → 原厂左侧车道行
```

- 提前发送的下一路口车道不会覆盖当前车道。
- 高亮方向有时不在基础角度列表中：例如基础角度为直行 `0`，高亮为右转 `90`，实际需显示“灰色直行 + 白色右转”的复合图标。
- 推荐状态为 2 才请求白色；多分支没有明确高亮时，不猜测应突出哪一条分支。
- 缺失、非法角度不会补成直行。重复角度保留到通用模型，原厂图标选择时按方向集合匹配。
- 原厂事务 25 使用 `laneNum`、`lane1…lane8`。必须在共享的剩余信息行更新之后发送，车道隐藏时发送 `laneNum=0`。
- 隐藏、车道总组数归零、导航结束、到达、会话关闭、数据超时均清除；失去原厂焦点后不写入或清除新所有者的内容。

协议字段依据：本地 `CarPlayLaneInfo` / `CarPlayRouteInfo`，上游真实采样测试 `Iap2LaneGuidanceTest`，以及原作者对 [RouteGuidance 字段的固件分析](https://github.com/lvalen91/CPC200-CCPA_resources/blob/main/documentation/02_Protocol_Reference/iap2_message_catalog.md)。OEM 图标依据原厂 `CommonLaneLine`、`t8.a.b` 以及 2026-10-27 的逐码 HUD 截图。

## 显示范围和边界

- 一次支持 1–8 条连续编号的物理车道，按从左到右的顺序输出。
- 覆盖直行、左右转、左右掉头及这些方向的 18 种原厂形状组合，共 53 种灰色/高亮图标。
- 当前只转换原厂已有准确表示的 `0 / -90 / 90 / -180 / 180` 度方向。其他角度、未支持组合、编号缺口、重复编号或超过 8 车道时，隐藏整排；不会删掉其中一条并错移其他车道。通用模型仍保留原始角度供后续扩展。
- 一次导航授权的显示有效期为 90 秒。车道几何可以预载较长时间，由真实当前导航更新续期，后台心跳不续期。
- 只收到了车道列表、手机没有发送当前组及显示授权时，保持隐藏；不能据视频中有箭头就伪造结构化车道数据。
- 这是当前 J90K 原厂接口适配，未修改原厂媒体服务、Linux HUD 资源或接收逻辑。

## 多车道验证

旧逐码图鉴每张只发送一个车道，用来确认编号与图形，不代表产品只能显示单车道。原厂既有多车道历史证据见 `ApkDecompile/WT_SystemService_analysis/device-20261027-navigation/remain-and-lane-counts/`。

本轮在两层自动化（完整快照及 iAP2 消息回放）各增加以下台架用例；总用例数由 27 增至 30：

| 用例 | 输入与观察目标 |
| --- | --- |
| N13 | 两车道：左转白色、直行灰色；隐藏后恢复剩余信息 |
| N14 | 四车道：保留复合方向和右转高亮；预载下一组不覆盖当前组，切换索引后才更新 |
| N15 | 八车道：左侧两条白色左转、五条灰色直行、一条灰色右转；结束后清除整排 |

设计近似八车道的预期原厂编号为 `[16,16,0,0,0,0,0,3]`。这是测试输入，尚不是本轮台架截图。

明天安装正式 Release 后，先用真实高德/Apple 地图选择具有明确多车道引导的路口，采集手机是否提供 `0x5204` 及对应的 `0x5201` 显示控制。然后核对左侧整排位置、数量、推荐分支、切换及结束清除；截图由代理检查。若需要合成台架用例，使用 Debug 工具包需另行安排，不自动安装 `.mazdatest`。

## 安装包配置

```text
-PmzdHudEnabled=true -PmzdHudMapEnabled=true -PmzdHudMapContent=map
-PmzdHudMediaSourceType=20 -PmzdHudAutomaticA2dpHandover=true
```

正式包名为 `com.thunder.carplay`，使用现有本地签名和认证素材。认证素材不写入源代码或报告。

## 本地验证与交付

完整执行 `.github/workflows/android.yml` 中的 Gradle CI，结果为 `BUILD SUCCESSFUL`，耗时 18 分 21 秒，460 个任务。源代码与默认关闭的 HUD 配置下运行，未将台架启用参数传给默认配置测试。

| 模块 | 测试数 | 失败/错误 | 跳过 |
| --- | ---: | ---: | ---: |
| shared | 1901 | 0 | 5 |
| common | 1890 | 0 | 0 |
| vehicle-mzd | 55 | 0 | 0 |
| mazda | 23 | 0 | 0 |
| home | 11 | 0 | 0 |
| 合计 | 3880 | 0 | 5 |

共 3875 项通过。5 项跳过均为已有 Windows 环境限制：3 项符号链接权限、2 项要求 `/bin/sh` 的命令引用测试；本轮未增加跳过。`:display-api:test` 为 `NO-SOURCE`。

其他检查均通过：12 项 Python 工具测试、公开源代码凭据检查、OEM 模块边界检查、APK OEM 隔离检查（普通包不含 Mazda 驱动；Mazda Release 不含台架入口）、所有 CI Lint、Debug 构建和 Release Kotlin 编译。

验证证据保存在 `build/hud-lanes/ci.log`、`ci-summary.json`、`targeted.log`；真实出图状态仍为待台架验证，不能用这些本地结果替代。

Release 构建及 `:mazda:lintRelease` 通过（5 分 23 秒）。已校验 APK v2 签名、正式包名、HUD 开关及右侧 `map` 配置，确认两项认证素材均已打包、车道实现存在、台架 Activity 不存在。

- 安装包：[DiPlay-Mazda-HUD-Lanes-0.2.22-20261029.apk](../build/hud-lanes/DiPlay-Mazda-HUD-Lanes-0.2.22-20261029.apk)。
- 包名：`com.thunder.carplay`；版本：`0.2.22 / 41`；大小：19,164,122 字节。
- SHA-256：`cc828b68607517f6900a57886bfb58a0a1423a10120e8d6d6fdd2129d9834c30`。
- 构建日志：`build/hud-lanes/release.log`；配置、凭据存在性及包内容检查：`build/hud-lanes/release-audit.json`。不包含认证素材内容。
- 本轮未安装、未操作车机、未新增 HUD 实图。明天需先覆盖安装此包，重连 iPhone 后再采集；只有真实手机输入及左侧截图均符合，才将出图状态改为通过。

在设备恢复后，从仓库根目录运行以下采集命令（若 ADB 地址变化，请使用恢复后的地址）：

```powershell
python scripts/hud_navigation_capture.py --serial 100.64.122.114:5555 --duration 900 --interval 5 --output build/hud-lanes/iphone-validation
```

该工具要求 DiPlay 已运行且连接 iPhone，保留 15 分钟采集，输出截图、来源/原厂事件、索引与 ZIP；不会把 Binder 返回或截图成功自动标为显示通过。
