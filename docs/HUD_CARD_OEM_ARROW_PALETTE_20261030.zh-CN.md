# HUD 右侧指示卡：与原厂左侧箭头配色一致

日期：2026-10-30。范围为当前 J90K 的右侧 178×178 指示卡；对应前版 [黑底指示卡](HUD_CARD_BLACK_BACKGROUND_20261030.zh-CN.md) 的后续调整。

## 配色规则

| 手机指示卡主题 | HUD 背景 | 推荐箭头 | 非推荐箭头 |
| --- | --- | --- | --- |
| 日间浅色卡 | `#000000` | `#FFFFFF` | `#666666` |
| 夜间深色卡 | `#000000` | `#FFFFFF` | `#666666` |

日夜只影响手机原图的识别和转换，不给右侧箭头另设夜间灰白值。保持原厂箭头像素配色，才能随同一 HUD 的整体亮度变化。此前提出的夜间 `#D0D0D0` 不作为箭头目标色；本次不另加文字区域蒙版，也不擅自调整原厂整屏亮度。

这张表记录软件输出配色。Apple 地图日间实机画面已核验；真实 HUD 夜间亮度、日夜切换时的左右一致性仍需台架验证。

## 原厂依据

已检查本地 `WT_SystemService_analysis/music-layout-20261028/gui_main.kzb`：

- `/Resource Files/Images/laneguide/15.png` 的推荐直行箭头核心像素为白色。
- `/Resource Files/Images/laneguide/0.png` 的非推荐箭头核心像素为灰色 102；资源带预乘 Alpha，不能按普通直通 Alpha 再乘一次来推断最终灰度。
- 复合图标 `laneguide/31.png` 同时含白色推荐分支和灰色非推荐分支。
- 历史原厂截图 `device-20261027-navigation/remain-and-lane-counts/lane-count-8.png` 的车道区域含 `#FFFFFF` 和 `#666666` 实际像素，验证了目标色。
- 当前资源索引未发现独立的日／夜车道图标组；不把 `Color Mode/Snow` 的雪地主题当成夜间模式。

资源、截图摘要和 SHA-256 见 [原厂配色证据](../build/hud-card-oem-palette-20261030/native-palette-evidence.json)。没有把原厂图标复制进 App。

## 实现

- [配色策略](../vehicle-mzd/src/main/java/com/shilapi/vehicle/mzd/MzdHudCardTone.kt)：识别浅底／深底卡片，寻找显著的中间灰阶，将背景、灰色和高亮三个色阶分别映射到 0、102、255。
- [窗口装配](../vehicle-mzd/src/main/java/com/shilapi/vehicle/mzd/MzdHudMapOutput.kt)：保留原始解码纹理，在透明硬件图层上分两步做颜色转换；黑底容器不经过反色，留黑边保持黑色。
- 最多每 500ms 读取一次 178×178 原始纹理。扩大抽样是为保留细箭头的实心灰色像素；没有在 CPU 上逐帧处理视频。
- `MAP` 和 `MAP_WITH_INSTRUCTION_CARD` 保留原色，未知彩色画面透传。滤镜不修改推荐状态、箭头形状和文字布局。

这里是像素配色转换，并不识别箭头语义。中间灰阶用于适配当前纯文字／箭头卡片；其他导航 App 的复杂图片需单独核验，不能将这次结果外推到所有图片。

## 自动验证

针对性测试已通过，含 9 项图形测试和 2 项窗口测试：日夜卡片、不同原始灰度的目标色、连续灰阶、透明留黑边、未知图片透传、178×178 位置及释放。

完整 `.github/workflows/android.yml` 命令已通过，476 项任务；3,925 项测试中 3,920 项通过、5 项既有跳过，0 失败。代码按固定快照构建，主工作区并行任务的路口图修改保留；配色文件与测试文件同步，其他差异记录在验证汇总中。

- [合成配色对照](../build/hud-card-oem-palette-20261030/preview.png)
- [验证汇总](../build/hud-card-oem-palette-20261030/validation.json)
- [完整 CI](../build/hud-card-oem-palette-20261030/ci-final.log)
- [针对性测试](../build/hud-card-oem-palette-20261030/targeted.log)

## 安装与 Apple 地图实机验证

正式版已安装到台架 `100.64.122.114:5555`，包名 `com.thunder.carplay`，版本 `0.2.22 / 41`。安装后设备 APK 的 SHA-256 与本地包一致：

`bfa15733c66d875f2e43eff3348d651157761cdf755035788be5796ee56e026d`

Release 构建、`lintRelease`、签名一致性及认证素材检查通过；设备仅保留正式包，没有 `com.thunder.carplay.mazdatest`。见 [安装记录](../build/hud-card-oem-palette-20261030/deployment.json)。

用户启动 Apple 地图导航后，代理直接检查 Android 主屏和 Linux HUD 截图，并执行像素核对：

| 验证项 | 结果 |
| --- | --- |
| 真实 Apple 地图导航输入 | 已确认，HUD 显示路线入口箭头及“前往这条路线” |
| 右侧 178×178 黑底 | 画面通过，窗口边缘像素均为 `#000000` |
| 左右白色引导箭头 | 画面通过，左右箭头实心像素均达到 `#FFFFFF` |
| 间隔采样 | 间隔 158 秒的两次 HUD 截图完全相同；只证明这两个采样时刻的输出一致 |
| 非推荐灰色车道箭头 | 自动图形测试通过；本次实际画面未出现，尚未实机验收 |
| 夜间及日夜切换 | 自动图形测试通过；尚未实机验收，未做投影亮度标定 |

此次筛选的应用日志没有认证加载错误和 `FATAL EXCEPTION`。没有将两个静态截图等同于连续无闪烁测试。

- [实机 HUD 截图](../build/hud-card-oem-palette-20261030/apple-in-progress/hud/dd21a9bb-4488-4ef1-a99b-e239c9d76278.png)
- [实机审图与像素检查](../build/hud-card-oem-palette-20261030/image-review.json)
- [Release APK](../build/hud-card-oem-palette-20261030/DiPlay-Mazda-HUD-OemPalette-0.2.22-20261030.apk)

状态：代码、完整 CI、Release 构建及安装完成；Apple 地图日间黑底与白色引导已实机通过。非推荐箭头、夜间和日夜切换仍需真实画面补验。合成对照单独记录为自动图形验证。
