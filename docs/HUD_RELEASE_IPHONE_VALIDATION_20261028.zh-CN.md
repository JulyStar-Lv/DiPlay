# HUD Release 与 iPhone 实测准备：2026-10-28

## 当前状态

**启用 HUD 的 Mazda release 已编译、签名、覆盖安装并启动。真实 iPhone 呼出/呼入补测已完成，176 张 HUD 原图中已核对联系人、接通计时递增和挂断清除，当前连接路径的基本电话显示通过。原厂蓝牙电话也在并行发送数据，DiPlay 独立出图归因尚未隔离。音乐曲名/歌手的 HUD 显示仍未通过，真实导航尚待验证。**

## 构建产物

| 项目 | 记录 |
| --- | --- |
| APK | `build/outputs/hud/DiPlay-Mazda-HUD-release-0.2.21-20261028.apk` |
| 原始输出 | `mazda/build/outputs/apk/release/DiPlay-Mazda-release-0.2.21-20261028.apk` |
| 包名 | `com.thunder.carplay` |
| 版本 | `0.2.21`，versionCode `40` |
| 大小 | 18,792,890 字节 |
| SHA-256 | `f009abef86395ab8510d46ba4f02321e05b1253633184c5975bd6fe131e8cb9d` |
| 配置 | release，非 debuggable，`HUD_ENABLED=true` |
| 认证素材 | APK 中两项运行时认证文件已与明确选定的本地输入逐字节核对；未输出素材内容 |
| 签名 | `apksigner verify` 通过；使用已有本地 release 签名 |
| 包含边界 | 已包含 `vehicle-mzd` 及生产 CarPlay 数据适配器；DEX/Manifest 均不含 `hudbench`；APK 不含 Android 签名 keystore |

版本号沿用仓库配置，因此安装核对应使用上述 APK SHA-256；不能仅用 `0.2.21` 判断是否更新。

## 开发检查

- 完整 CI：`BUILD SUCCESSFUL in 36s`，407 个任务。
- 单元测试 3,379 项，失败/错误/跳过均为 0；Python 证据助手 3 项通过。
- 公共树、模块、默认 APK 及 release 台架入口排除检查通过。
- 带认证/HUD 的 release：`BUILD SUCCESSFUL in 8m 57s`，174 个任务；显式执行 `verifyStandaloneAuthentication`、`lintRelease`、`assembleRelease`。
- release APK 另经签名、Manifest、DEX、认证输入一致性检查，见 `build/hud-release/20261028/release-audit.json`。

构建采用现有 `.private/release-env.ps1` 的本地配置及 `-PmzdHudEnabled=true`。普通源码/CI 构建默认关闭 HUD，未修改默认值。

## 安装及后续

台架目标 `100.64.122.114:5555`，安装前可查询到旧的 `com.thunder.carplay`，另有 debug 模拟包 `com.thunder.carplay.mazdatest`。本次使用 `adb install -r`，保留现有应用数据；首次调用立即返回 `device offline`，未执行卸载或清数据。

用户恢复 ADB 后，重试覆盖安装成功，用时 **165.38 秒**。车机 `base.apk` SHA-256 为 `f009abef86395ab8510d46ba4f02321e05b1253633184c5975bd6fe131e8cb9d`，与交付 APK 一致。应用 `firstInstallTime` 仍为 `2026-10-19 13:22:57`，`lastUpdateTime` 更新为 `2026-10-28 14:36:39`，未卸载或清数据。

以后重新安装可执行：

```powershell
$adb = 'C:\Users\Shine\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb connect 100.64.122.114:5555
& $adb -s 100.64.122.114:5555 install -r build/outputs/hud/DiPlay-Mazda-HUD-release-0.2.21-20261028.apk
```

安装后已停止已完成任务的 debug 模拟 App，启动正常入口 `com.thunder.carplay/com.shilapi.xcertplay.DiPlayActivity`，`am start -W` 返回 `Status: ok`。启动后检查仍为同一进程，所采集日志中未见 FATAL、Fatal signal 或 ANR，前台为 release 的正常 Activity。

- [启动画面](../build/hud-release/20261028/release-startup.png)：已准备连接 iPhone，保留原来的车机热点连接设置。
- [HUD 连接前基线](../build/hud-release/20261028/release-hud-baseline.png)：与先前已清理的基线一致，SHA-256 为 `6e6a8cee215da740b324bd08bddbde2aa9ccb2554db1426d65b124668ff38086`；仅确认无旧测试残留，不代替真实手机出图验收。
- [安装文件核对](../build/hud-release/20261028/installed-release.json) 与 [启动日志检查摘要](../build/hud-release/20261028/startup-check.json)。

## iPhone 实测顺序

在正常的 DiPlay release 中按原有方式连接 iPhone，先确认 CarPlay 主画面及声音正常，再逐项观察 HUD。

| 顺序 | 操作 | 本期应验证的内容 |
| --- | --- | --- |
| 1 | 播放有曲名/歌手的歌曲，保持 20 秒以上 | 单行滚动元数据持续显示，不能约 5 秒后消失 |
| 2 | 换曲、暂停、恢复；选择含 emoji 的曲目 | 元数据更新、暂停隐藏、恢复出现；不支持的补充字符降级后其余文字仍显示 |
| 3 | 使用手机导航并触发真实 TBT 更新 | 转向图标、下一动作距离、剩余里程/时间；缺少必要数值时等待完整数据 |
| 4 | 结束导航后重新开始 | 不再只显示“现在”；到达/取消后清除 |
| 5 | 来电、接通、结束 | 姓名或号码、通话计时、结束清除；不通过自动化脚本拨打或接听电话 |
| 6 | 音乐/导航期间来电，结束后继续等待至少 16 秒 | 电话覆盖音乐，结束后恢复且持续显示，导航保持 |
| 7 | 断开 CarPlay，再重新连接 | 原内容清除，新会话重新接管，旧会话不覆盖 |

按真实场景保存 HUD 截图和对应时间的应用日志，结论与 [合成数据的 50 组复验](HUD_BENCH_FIXES_VERIFIED_20261028.zh-CN.md) 分开。音乐当前使用原厂兼容单行通道，不承诺原生 CarPlay 来源图标、封面或真实歌词；右侧路线地图也未纳入这批 release 的通过范围。

## 本机记录

### 真实 iPhone 首轮观察（进行中）

- 用户确认 CarPlay 已连接并播放音乐；release 进程 `12749` 持续收到真实会话更新，状态为 `navigation=NONE media=PLAYING calls=0`。播放状态本身不代表音乐元数据或 HUD 显示通过。
- `15:03:12` 的 [HUD 音乐原图](../build/hud-release/20261028/iphone-live/music-before-phone.png) 只有蓝牙图标和“暂无歌词”，未见曲名/歌手。后续连续采样仍为同一画面；本轮尚未确认 DiPlay 收到的曲名/歌手字段是否完整。
- 已找到可复现于本轮日志的焦点冲突：`14:55:58.132` DiPlay 请求媒体焦点，`.133` 获得焦点，`.383` 发送音乐事务 37，`.387` 原厂 `com.tinnove.mediacenter` 请求同一媒体焦点，`.388` DiPlay 收到失焦回调。该时间线保存在 `build/hud-release/20261028/iphone-live/music-focus-timeline.txt`。原厂音乐应用重新请求焦点与 DiPlay 失焦的先后关系已确认，但尚未完成修复及复验。
- 电话连续采集于 `15:03:56` 启动，目录为 `build/hud-release/20261028/iphone-live/phone-150356/`。电话由用户真实拨打/接听，脚本只读取日志和 HUD 原图。采集使用固定最长 10 分钟时限；采集完成不自动判定显示通过。
- 第一轮采集实际于 `15:13:57` 达到时限停止：389 张原图、无截屏失败，全部为同一个“暂无歌词”画面。用户随后完成的真实电话发生在 `15:31–15:33`，没有对应的通话过程截图，该第一轮记录仍未通过视觉验收。随后 `15:35:55–15:44:00` 的 `phone-153555/` 补测已捕获用户再次呼出和呼入的完整过程，176 张原图、无截屏失败。代理已自行审图核对基本电话显示，未要求用户人工确认。
- 第一轮电话日志、补测关键原图、原厂事务、音乐播放状态恢复及验证范围详见 [真实电话验证记录](HUD_IPHONE_REAL_CALL_VALIDATION_20261028.zh-CN.md)。代理已自行核对补测原图。
- 本轮保留当前 CarPlay 连接，没有重装、强杀或重启 release。上述实机发现与此前合成 50 组通过的限定结论分别记录。

`build/hud-release/20261028/` 保存本次 CI、release 构建日志、签名校验、APK 审计、安装前状态、安装失败/成功及重连记录、启动日志和截图、HUD 基线；不包含认证或签名私钥内容。
