# CarPlay 路口类型及道路角度：ADB 验证与通用模型接入

日期：2026-10-30。设备：J90K，ADB `100.64.122.114:5555`。

后续已接入右侧路口角度绘制，详见[绘制接入报告](HUD_JUNCTION_DIAGRAM_RENDERING_20261030.zh-CN.md)。下文保留字段验证阶段的结果与范围。

## 1. 实际观察

通过已运行 DiPlay 的只读 logcat，检查 iAP2 `0x5202`。本轮没有安装、停止应用或改变手机会话。
`0x5202` 自身没有地图来源名称，以下来源依据紧邻的 `0x5201` 参数 19 关联；保留了关联消息时间。

| 日志来源标识 | 样本数 | 参数 0x09：类型 | 参数 0x0A：其他道路角度 | 参数 0x0B：出口角度 |
| --- | --- | --- | --- | --- |
| Apple Maps | 10 | 10 条，值均为 0 | 2 条提供 | 2 条提供 |
| 高德地图，早先采集 | 3 | 3 条，值均为 0 | 本批未出现 | 本批未出现 |

**用户已确认本轮使用 Apple 地图，实际手机链路会发送道路角度，可以接入模型。高德的三个旧样本不足以认定其不支持；用户计划稍后补测高德，目前标记为待补测。**

两个有效样本如下，均为交叉路口类型 0：

| maneuver 索引 | 转向类型 | 其他道路角度 | 所选出口角度 |
| --- | --- | --- | --- |
| 2 | 1，左转 | `[94, 11]` | `-81` |
| 3 | 2，右转 | `[2, 94, -122]` | `68` |

道路角度为重复的 BE signed i16 TLV。`ff af` 应解为 `-81`，`ff 86` 应解为 `-122`，不能使用日志格式器展示的无符号数 65455、65414。
本轮左右转样本与“直行为 0、右为正、左为负”的模型约定吻合；尚未覆盖环岛及所有方向边界。
参数 0x09 的单字节 `00` 被现有通用日志格式器显示为 `""`，它是数值 0，不是缺失。

原始道路名称、位置和目的地不写入本轮证据文件。数字字段及来源关联记录位于：

- [Apple Maps 样本](../build/hud-junction-fields-side-20261030-082844/simulated-navigation-observations.jsonl)
- [Apple Maps 统计](../build/hud-junction-fields-side-20261030-082844/simulated-navigation-summary.json)
- [早先高德样本](../build/hud-junction-fields-side-20261030-082844/observations.jsonl)

## 2. 代码接入

通用模块 `display-api` 增加 `NavigationState.junction`：

```kotlin
enum class JunctionKind { UNKNOWN, INTERSECTION, ROUNDABOUT }

data class NavigationJunction(
    val kind: JunctionKind = JunctionKind.UNKNOWN,
    val branchAnglesDegrees: List<Int>? = null,
    val exitAngleDegrees: Int? = null,
)
```

- `branchAnglesDegrees` 表示其他道路，所选出口单独保留。不能当作车道列表。
- `null` 表示尚未提供；明确的空角度 TLV 转为空列表。缺失出口不生成 0 度或 OEM 默认值 1000。
- CarPlay 解析按 maneuver 索引缓存、稀疏合并，`0x5201` 选择当前索引后才发布对应路口。
- 切换到无几何的索引或空索引列表，清除上一处路口；结束、到达及会话重连清缓存。
- 兼容重复 TLV 和单 TLV 内打包的 i16 数组；本轮实际观察到的是重复 TLV。
- 角度限定 `-180..180`，其他道路至多 32 个；畸形字段整帧拒绝，缓存不会部分更新。
- 已发布的角度列表不可修改；缓存达到上限时保留当前路口。
- 白名单诊断追加当前模型的 `junction`、`junctionBranches`、`junctionExit`。

代码位置：

- [通用模型](../display-api/src/main/kotlin/com/shilapi/display/api/VehicleDisplay.kt)
- [CarPlay reducer](../shared/src/main/java/com/shilapi/xcertplay/projection/carplay/CarPlayNavigationReducer.kt)
- [模型输出与诊断](../shared/src/main/java/com/shilapi/xcertplay/projection/carplay/CarPlayDisplaySource.kt)
- [数字样本及边界测试](../shared/src/test/java/com/shilapi/xcertplay/projection/carplay/CarPlayJunctionNavigationTest.kt)

## 3. 验证范围

本轮交付范围为手机实际字段验证及通用模型接入。未开发道路分支绘制器，也未宣称右侧 HUD 已显示这些分支。
现有左侧车道通路、OEM Binder 和 Linux HUD 布局未改动。

新增测试覆盖两个真实数字样本、仅类型／缺失数据、重复及 packed 编码、负角度、空列表、稀疏更新、索引切换、无效帧原子拒绝、缓存限额、结束／到达和会话重连。

完整 CI 按当前 [Android workflow](../.github/workflows/android.yml) 执行，包括 display-api、display-android、vehicle-mzd、shared、common、home、mazda 单元测试，以及 lint、debug 构建、Mazda release 源码编译和 Manifest 检查。

验证结果：

- 新增 9 项路口测试全部通过，0 失败、0 错误。
- 16 项证据工具测试、公开源码及模块隔离检查通过。
- 完整 CI 重跑通过，耗时 26 分 15 秒，476 个任务中执行 38 个，其余 438 个为 up-to-date。
- APK 隔离检查通过：车型模块只进入 Mazda；release 不包含 debug bench 类或 Manifest 入口。

首次完整 CI 在 `common:testDebugUnitTest` 生成 HTML 报告时遇到 `EOFException / Buffer underflow`，不是路口测试断言失败。
当时还存在另一进程运行同一仓库的 common 测试，因此共用结果文件是一个可能原因，尚未据此修改项目构建配置。
重跑使用 ignored 目录下的临时 init script 为 common 指定独立 binary 结果目录，完整任务列表保持不变，并关闭本次运行的 configuration cache。
未终止其他构建，也没有删除共用结果文件。

- [首次 CI 日志](../build/hud-junction-fields-side-20261030-082844/ci.log)
- [重跑 CI 日志](../build/hud-junction-fields-side-20261030-082844/ci-retry.log)

## 4. 对后续右侧示意图的意义

这些字段能够作为“其他道路分支＋高亮出口”的真实输入，仍不能还原道路曲线、相邻路口、道路宽度或路口实景图。
只有类型、缺失角度的路口，应保持缺失或沿用已有指示卡，不能从单一转向类型或车道角度虚构道路几何。

Apple 的 [junctionElementAngles](https://developer.apple.com/documentation/carplay/cpmaneuver/junctionelementangles) 明确将其他道路与 [junctionExitAngle](https://developer.apple.com/documentation/carplay/cpmaneuver/junctionexitangle) 分开。
公开解析项目的 [maneuver TLV 实现](https://github.com/luka-dev/mib2q-carplay-rgi/blob/main/hook/routeguidance/rgd_tlv.c) 作为字段映射交叉核对；本次接入依据包含本台设备实际收到的数值，没有采用其 OEM 默认出口补值策略。
