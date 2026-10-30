# DiPlay 自动音乐交接：集成与台架复验

日期：2026-10-29；台架 J90K，普通应用 `com.thunder.carplay`，release 0.2.21 / versionCode 40 / targetSdk 37。

## 结论

**已集成到 DiPlay，并在真实 iPhone CarPlay 会话中通过本轮音乐显示、换曲、暂停恢复、正常断开归还 A2DP、再次连接和应用重启恢复验证。**

HUD 使用原厂蓝牙图标及“曲名 / 歌手”单行文字，长文本沿用原厂滚动。当前没有取得 Apple Music 逐句歌词。显示内容来自实时 CarPlay 适配器，不是固定元数据探针。

本轮没有停止原厂媒体进程、修改原厂 APK、申请 Android 音频焦点或部署 Linux 布局补丁。原厂媒体进程 PID 3571 在采样中保持存在。测试通过 ADB 安装应用、操作正常媒体键/DiPlay 按钮及采集证据；A2DP 分离、HUD 写入和恢复由 DiPlay 自己执行。

## 实现范围

- `vehicle-mzd`：增加按来源会话管理的 A2DP 租约和持久恢复位；Binder 重连不重新开始分离，不循环抢焦点。电话或权限/状态不明确时不切换连接。
- 真实播放后，确认蓝牙 ON、HFP/A2DP 已连接、原厂稳定来源为 BT 或空来源，再记录待恢复位并单次调用 `disconnectA2dp()`。
- 等待 A2DP 查询退出已连接、原厂来源稳定及至少 2 秒过渡期，再申请一次 MEDIA 显示焦点。收到真实授予回调后发送最新曲名和歌手；沿用 2 秒保活。
- 如果已知 BT 来源在最初 3 秒内自动恢复 QQMusic，允许正常暂停一次，并重新确认暂停/稳定状态。只适用于该初始过渡；其他来源、后续主动播放或失焦均让出并安排恢复。**本轮台架未触发 QQ 分支，其实机行为仍待验证。**
- 正常会话结束、媒体域失败或交接失败后，由同一 worker 调用 `connectA2dp()`，释放原厂自动拒绝连接状态。即使物理连接已回来，也不能跳过此恢复调用。
- 进程强杀没有可靠退出回调，恢复位保留；下次应用启动在新会话前先恢复。应用处于强停/未运行期间不会自行恢复，本轮没有增加外部守护进程。
- Mazda 装配增加默认关闭的 `mzdHudAutomaticA2dpHandover` 构建开关；增加来源 20 的显式支持。公共显示模型、CarPlay 元数据适配器、其他车型与应用的默认行为未改。

主要代码：`MzdA2dpHandover.kt`、`MzdA2dpAndroid.kt`、`MzdBluetoothMedia.kt`、`MzdVehicleDisplayFactory.kt`，均位于 `vehicle-mzd`；应用启动恢复及构建参数位于 `mazda`。

## 状态接口校正

第一版台架尝试未执行分离：把原厂播放态误判为不满足条件，截图仍为“暂无歌词”。第二版已经修正并补充测试：

| 接口 | 已核对的返回值 |
| --- | --- |
| 原厂 MusicService 事务 15 | Android PlaybackState：3 播放、2 暂停、6 缓冲。不同于发给 HUD 的 `playStatus`，也不是内部 `PlayState` 枚举序号。 |
| BluetoothMusic 事务 1 | 已连接设备列表非空返回 2；列表为空或 profile 不可用返回 -1。不能将 0 当作本固件唯一断开值。 |
| BluetoothMusic 事务 2 | `isA2dpConnected()`；结合前一连接状态、稳定来源及等待窗口判断。-1 本身不能区分空列表与 profile 不可用。 |
| BluetoothManager 事务 54 / 53 | `disconnectA2dp()` / `connectA2dp()`；不关闭总蓝牙，不修改 HFP 或配对。 |
| PhoneManager 事务 27 / 26 | 只读 HFP 连接 / 是否通话，避免仅依赖 CarPlay 电话事件的到达时序。 |

## 实测结果

| 项目 | 本轮结果 |
| --- | --- |
| 自动分离与授权 | 第二版设备时间 11:13:49.630 请求分离；11:13:51.726 确认原厂 source=0/state=0 稳定；11:13:51.729 收到 MEDIA 授予回调。 |
| 持续显示 | `automatic-r2` 共 16 张原图，约 73.84 秒观察。00 为原厂“暂无歌词”；01 为启动过渡空白；02–15 共 14 张稳定阶段图片均为蓝牙图标与 Cornelia Street / Taylor Swift 的滚动文字，没有回到“暂无歌词”。截图为离散采样，非逐帧录像。 |
| 实时换曲 | 正常媒体 Next 键后，4 张截图均显示“东风破 / 周杰伦”，与 DiPlay MediaSession 实时元数据一致。 |
| 暂停 / 恢复 | Pause 后 3 张图为空白；Play 后 4 张图恢复正确文字。A2DP 保持分离，HFP 保持连接，没有因暂停反复连接。 |
| 正常断开 | 点击 DiPlay 首页“断开连接”；11:16:54.465 记录恢复接受。7 次采样 A2DP 全部 Connected，后续 6 次 AVRCP 也为 Connected；HUD 清空，无活动 CarPlay MediaSession。 |
| 再次连接 | 直接打开 Host Activity 没有启动新会话，未算通过；随后点击首页“连接手机”，成功连接并自动交接。9 张图含 2 张过渡空白，02–08 共 7 张均显示正确中文文字。 |
| 强停 / 下次启动 | 仅强停 DiPlay：停止时 A2DP 仍分离，约 5 秒后 HUD 超时清空。重启后 11:19:41.108 先执行恢复，首张采样 A2DP Connected；随后新会话于 11:19:44.414 再分离、11:19:46.523 获得焦点，01–09 共 9 张显示正确文字。 |
| HFP / 蓝牙开关 | 上述全部状态采样中蓝牙 ON、HFP Connected；不是实际呼入/呼出或 SCO 声音验收。 |
| 软件音频 | 稳定播放记录继续接收 AAC、向 AudioTrack 写 PCM，观察到约 5 秒窗口 234–235 包、约 24 万帧，writeErrors=0。暂停/换曲边界按事件结束/新建音轨，未据此声称实际扬声器通过。 |
| 原厂布局 | `gui_main.kzb` SHA-256 仍为 `32de3f01f03da75d59f1bd7adab28beb8207fa62183060ff5a2b6746fee3721a`，`J90K.service` active。 |

截图由代理审阅：长文本逐张查看裁剪索引，重复中文/空白画面通过原图 SHA-256 对照已检查的完整原图。原图保持不变，裁剪索引仅用于审图辅助。

## 构建与安装

普通构建保持 HUD 和自动 A2DP 开关关闭、默认来源 27。台架版使用：

```powershell
.\gradlew.bat :mazda:verifyStandaloneAuthentication :mazda:assembleRelease `
  -PmzdHudEnabled=true -PmzdHudMediaSourceType=20 -PmzdHudAutomaticA2dpHandover=true
```

独立连接手机仍需要已有本地认证及 release 签名输入；认证材料不写入源码或证据 ZIP。来源参数允许 20/27/29，20 表示蓝牙外观兼容，并非原厂 CarPlay 来源 30。

- 已安装 APK：`mazda/build/outputs/apk/release/DiPlay-Mazda-release-0.2.21-20261029.apk`。
- SHA-256：`57b9d392b7fe41217c53b48f4832ee411188180997d093060be564ce6467ef18`；与安装后设备内 APK 的摘要一致。
- 安装前原版已保存到 ignored 目录以便回退，没有卸载或清除应用数据。
- 测试结束保持新 DiPlay 会话和音乐显示运行；A2DP 由该会话持有，正常断开会恢复。

## 本地回归

按 AGENTS.md 和 `.github/workflows/android.yml` 跑完整 CI。第二版 `BUILD SUCCESSFUL in 59s`，407 个任务，53 执行、354 为最新。

单测 **3402 项**，失败/错误/跳过均为 0：vehicle-mzd 38、shared 1690、common 1646、mazda 22、home 6。新增 13 项包括分离和恢复状态机、失败/权限/通话边界、重启恢复、真实 ABI 的 -1 返回、只调用 A2DP 控制，以及实时媒体接入时序。

OEM 源码与 APK 隔离、release 无 debug 台架入口、公开树检查及 3 项 HUD 证据助手测试通过。未增加设置 UI。

## 证据与剩余范围

原始材料保存在 ignored `build/hud-release/20261029/automatic-handover/`。可分享的白名单证据包只含 HUD 原图、审图索引、状态/音频数字统计、仅 HUD 驱动事件及本报告；不包含认证、联系人、手机画面或原始全量日志。

- 证据目录：`build/hud-release/20261029/automatic-handover/evidence/index.html`。
- ZIP：`build/hud-release/20261029/DiPlay-HUD-Automatic-Handover-20261029-evidence.zip`。

当前结果限定于本台架、原厂 source=0/BT 的交接过程。QQ 自动恢复分支、其他原厂音源的主动切换、实机电话/SCO/实体音量键、实际扬声器、蓝牙关闭或 HFP 未连接的初始条件、其他固件均未在新路径下完成实测。初始条件不满足时保持等待/让出，不能宣称这些条件已全部支持。

没有取得真实歌词。曲名/歌手回退显示通过，不等于歌词获取通过。
