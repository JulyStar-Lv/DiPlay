# DiPlay HUD 两层台架自动验证

2026-10-28 初版，2026-10-29 更新导航策略用例。仅 Mazda **debug** 构建，包名 `com.thunder.carplay.mazdatest`。无需连接 iPhone，不需要 CarPlay 认证资产；模拟事件来自固定测试数据。原厂 Binder 调用在 DiPlay 普通 App UID 下执行，电脑 ADB 助手仅启动任务、采集既有 Linux 画面和传输证据。

## 1. 两层范围

| 层 | 实际路径 | 可验证的内容 |
| --- | --- | --- |
| snapshot | 固定通用快照 → 同一 `vehicle-mzd` → 原厂双屏 Binder → HUD | App 权限、接口、焦点、单位/图标/文字映射、超时和清除 |
| replay | 合成 iAP2 0x5201/0x5202/0x5001/0x4155 → 现有 `CarPlayDisplaySource` / `CarPlayPlaybackStatus` → 同一驱动 → HUD | 增量归并、显式清空、电话状态、会话替换；同时检查最终通用快照与独立预期一致 |

回放沿用 `CarPlayController.onRouteFrame` 的发布顺序与生产解析器，保留原始合成 iAP2 编码。它不经过 USB/无线连接、认证及真实手机协议收包，也不代替真实 iPhone 的事件供给和连接验收。

## 2. 构建与安装

仓库根目录 PowerShell：

```powershell
.\gradlew.bat :mazda:assembleDebug -PmzdHudEnabled=true
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb devices -l
& $adb install -r .\mazda\build\outputs\apk\debug\mazda-debug.apk
```

必须启用 `mzdHudEnabled`。默认 CI 构建仍为 false；默认包收到台架任务时会导出明确的失败报告，不查询原厂服务。

本期另存的台架包路径为 `build/outputs/hud/DiPlay-Mazda-HUD-bench-debug.apk`，它是 source-only 诊断包，适合无手机模拟。不能据此声称已经完成独立 CarPlay 认证配置。

## 3. 一条命令运行并归档

台架当前无真实投屏会话，退出其他正在写 HUD 的测试工具。运行：

```powershell
python scripts/hud_bench.py --mode both --suite all
```

不在 PATH 中的 ADB 或有多台设备时：

```powershell
python scripts/hud_bench.py --adb "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" --serial 设备序列号 --mode both --suite all
```

选择部分范围或某一层：

```powershell
python scripts/hud_bench.py --mode snapshot --suite phone
python scripts/hud_bench.py --mode replay --suite music
python scripts/hud_bench.py --mode both --suite navigation
```

`--suite` 支持 `all/navigation/music/phone/lifecycle/coexistence/regression`；`regression` 选择 N08–N12、M03、M05–M08、C01。2026-10-29 新增 N11/N12，覆盖真实手机稀疏更新与长剩余里程。`--linux-host` 默认 `192.168.100.1`；`--output` 指定电脑证据目录；`--timeout` 默认 1800 秒。

脚本自动打开 debug Activity，应用逐项执行并等待每次截图完成；脚本持续处理截图请求、下载最终 ZIP、校验清单和 SHA-256，并生成本地图片索引。无需逐项操作或人工填写显示通过。

## 4. 当前测试计划

当前每层 27 组，两层共 54 组。N10/N11 为 94 秒，N12 为 12 秒，M03/M06/C01 为 18 秒，其余为 10 秒；每层计划发送时长 464 秒，两层 15 分 28 秒，另加基线、截图、IPC 与清除等待时间。navigation 每层 12 组、290 秒。截图慢会延长实际运行时间，报告保留计划时间、实际时间及迟到量。此前 42/50 组报告为增加用例前的历史计划。

| 分组 | 用例 | 内容 |
| --- | --- | --- |
| navigation | N01–N04 | 直行、左右转、轻微/急转弯及距离变化 |
| navigation | N05–N06 | 按驾驶侧掉头、环岛第 2/10 出口 |
| navigation | N07–N08 | 未知转向、缺失距离等待、增量补齐 |
| navigation | N09–N10 | 到达/取消清理、90 秒停止更新后隐藏 |
| navigation | N11–N12 | 59 秒稀疏更新保持导航；剩余 131071 m、1018 km、9999 km |
| music | M01–M04 | 中英文歌名/歌手、换曲、暂停/恢复、长文本/emoji、省略保留与显式清空 |
| music | M05–M08 | 短 emoji、长纯中文、127/128 字节 ASCII 对照；同时检查同曲目是否被原厂超时隐藏 |
| phone | P01–P05 | 姓名来电、号码回退、呼出/连接/接通/结束、保持/来电等待/一路结束、姓名清空 |
| lifecycle | L01 | 新会话替换旧会话，延迟旧事件及旧 close 不得覆盖新输出 |
| coexistence | C01 | 导航/音乐共存，来电及结束后的原内容 |

未纳入本期自动验收：封面、真实歌词、右侧地图、电话控制、外部原厂焦点抢占注入、强杀恢复及服务重启。报告将它们列为 unsupported；并非固件永久不支持。保持和等待按当前驱动既有降级策略验证。电话计时从首次观察到接通开始，不模拟恢复连接前的通话时长。

## 5. 采集与判定

每批先采集基线；每项默认第 2/7 秒及每个状态切换后的第 1 秒采集，去重。N10/N11 另在第 32/60/89/92 秒采集，分别核对超时与真实更新续期；M03/M06 另在第 12/16 秒采集；C01 另在第 9/12/16 秒采集，检查电话结束后的恢复及持续显示。每项结束先 close 端点、等待同一驱动 worker 执行到屏障，再延迟 500ms 并保留清除后画面。屏障只说明此前调用已返回，异步原厂显示是否清除仍需图片检查。

应用通过自己的外部文件目录发布带 runId/captureId 的请求。电脑助手仅执行固定命令：

```text
wtdbclient -y root@192.168.100.1 'LayerManagerControl dump screen 0 to /var/update/hdcap-<captureId>.png'
```

助手从 Android `/data/nfs` 读取对应文件，并清理本次唯一临时截图；不借 shell UID 写导航、音乐、电话。应用再次检查响应身份、PNG 分块 CRC、完整解码、尺寸、大小与 SHA-256。响应超时、身份错误、缺图或坏图均记录失败，不生成占位图。

PNG 回传的单次 `adb push` 允许 8 秒，修正此前 4 秒造成的偶发中断；应用总体截图等待仍为 20 秒。慢链路超过应用等待期限仍应判为采集失败。

来源快照不一致、驱动错误、截图失败或清除调用错误会在清理当前项后停止后续项目。原厂焦点未授予且没有内容事务时，报告显示 `no_output_observed`，批次标为 `completed_with_inconclusive_transport`。Binder 返回接受、快照匹配或截图成功均不会将 `displayVerified` 改为 true。

报告图片由 Codex 按基线、测试文字/图标/数值、各阶段及清除结果批量审阅，输出符合预期、未显示、异常或证据不足。该流程无需用户人工逐项确认，也不冒充 App 内已有图像识别。

## 6. 证据位置

电脑默认目录：

```text
DiPlay/build/hud-bench/<runId>/report.zip
DiPlay/build/hud-bench/<runId>/review/index.html
```

台架目录（应用外部文件，卸载会删除）：

```text
/sdcard/Android/data/com.thunder.carplay.mazdatest/files/hud-bench/<runId>/report.zip
```

ZIP 包含 `report.json`、`events.ndjson`、`screenshots/*.png` 与逐文件 SHA-256 的 `manifest.json`。事件包含合成输入、iAP2 编码、会话/revision、实际通用快照及原厂事务/焦点日志。`finished.json` 在归档完成后发布，提供整包 SHA-256。全部图片及日志上限由应用控制，证据达到 256MiB 时停止继续采集并保留现有文件。

运行后可手动补取：

```powershell
adb pull /sdcard/Android/data/com.thunder.carplay.mazdatest/files/hud-bench/实际runId/report.zip .
```

## 7. 停止与恢复

脚本中按一次 Ctrl+C，或点击 Activity 的“停止并清理”。应用结束当前端点并归档，脚本等待清理后取包，不使用 force-stop。再次 Ctrl+C 退出电脑助手，应用最多等待截图响应超时后清理。

已有真实投屏会话时拒绝启动台架写入；运行中真实投屏到来时取消台架任务，让真实会话接管同一 worker。旧台架端点 close 不清理新会话。Activity 销毁会停止任务，不自动重放。

强杀和断电没有可靠 Java 清理回调，本期未增加外部 watcher；应单列验证。中断前的原始文件留在对应 runId 目录，不将没有 finished 标记的批次当作完整成功包。

可直接打开 debug Activity 查看入口：

```powershell
adb shell am start -n com.thunder.carplay.mazdatest/com.shilapi.xcertplay.mazda.hudbench.HudBenchActivity
```

页面按钮运行无截图模拟；电脑命令也可使用 `--no-capture`。这些模式保留输入与 IPC 记录，不能作为显示证据。

## 8. 开发验证记录

- 完整 `.github/workflows/android.yml` Gradle 检查通过：`BUILD SUCCESSFUL in 48s`，407 个任务中 30 个执行、377 个 up-to-date。日志：`build/reports/vehicle-display-architecture/bench-ci.log`。
- JVM/Android 单元报告共 3,374 项，失败/错误/跳过均为 0；其中新加入台架测试 6 项覆盖全部 42 组的快照契约、电话计时、所有权接管、截图响应身份/超时/完整 PNG/归档散列，以及默认关闭时的失败报告。车型新增 worker 屏障测试 1 项。汇总：`build/reports/vehicle-display-architecture/bench-test-summary.json`。
- Python 助手 3 项测试通过，覆盖 ZIP/散列/运行身份校验、危险路径拒绝及截图请求不能指定任意命令；已加入 CI。
- 默认 APK 的 DEX/Manifest 边界检查通过：仅 Mazda 包包含车型驱动与 debug 入口；`compileReleaseKotlin` / `processReleaseManifest` 及 release 排除检查通过，release 中没有台架类或组件。默认构建仍关闭 HUD。
- 回归发现并修复 Windows 上报告更新后仍保留旧 `running` 内容的问题，采用可报告失败的文件替换；测试同时核对报告终态、ZIP 与完成标记。

启用版构建通过：`BUILD SUCCESSFUL in 9s`，日志 `build/reports/vehicle-display-architecture/bench-enabled.log`。已另存 `build/outputs/hud/DiPlay-Mazda-HUD-bench-debug.apk`，SHA-256：`2c9992e5070b092e14a8c4e1a606e859a98cb25f4b27b373fbbb271709dbe7a3`。aapt2 确认该包使用 debug 台架 Application/Activity，且 `HUD_ENABLED=true`。默认输出路径已重新构建为关闭 HUD，避免混用。

开发阶段首次检查时 ADB 设备列表为空；随后已连接台架并完成安装与出图验证，见下节。开发检查通过不代替显示验收。

## 9. 2026-10-28 台架执行结果

启用包已安装，普通 App UID 为 `10117`。两层共 42 组均已有完整执行与截图证据；首批回放 P03 遇到一次 ADB 截图回传超时，随后通过分组补跑完成缺失项，原始失败记录保留。

Codex 已按报告截图逐项检查：**34 组符合、6 组部分符合、2 组音乐文字未显示**。发现相同 TBT 重入后错显“现在”、长中文/emoji 组合不显示、电话结束后采样未恢复音乐三类问题。不能将 42 组执行完成写为 42 组显示通过。

共保存 221 张有效 HUD 截图，45 张清除后画面及最终画面均与基线逐像素一致。最新完整用例的 90 个步骤快照契约全部匹配。原始 `displayVerified=false` 保留，另用审阅记录标注实际显示结论。

详见 [完整台架验证报告](HUD_BENCH_VALIDATION_20261028.zh-CN.md)；本机图片索引为 `build/hud-bench/bench-20261028/review.html`，综合证据包为 `build/hud-bench/bench-20261028/DiPlay-HUD-bench-20261028-evidence.zip`。

### 修复后复验

最终修复包已安装，2026-10-28 12:31–12:43 两层各 25 组、共 50 组一次执行完成，无采集失败。Codex 已逐项审阅 253 张有效截图，50 组均符合本批预期；98 个快照步骤匹配，50 张清除图及最终图均与基线一致。该结果限于合成输入及 Linux HUD 画面，不扩展为真实手机、右侧地图、封面或真实歌词验收。

详见 [修复包复验报告](HUD_BENCH_FIXES_VERIFIED_20261028.zh-CN.md)。当前台架安装 APK 为 `build/outputs/hud/DiPlay-Mazda-HUD-bench-fixed-debug.apk`，SHA-256 为 `4508b11bcd93a27f8460f687fa9b66a2b39addb8752aac9d4b9c372fbbaec267`；默认 CI 包仍关闭 HUD。

## 10. 真实 iPhone 导航采集

Apple 地图的静止开始/取消与 Windows GPX 连续移动验证见 [iPhone 导航验证说明](HUD_IPHONE_NAVIGATION_VALIDATION_20261029.zh-CN.md)。新工具 `scripts/hud_navigation_capture.py` 只观察真实 release 会话并采集 HUD，不注入合成导航；与本文 debug 两层测试分别记录。右侧地图与实际到达仍需各自验收。
