# CarPlay 音乐 HUD：蓝牙与音频焦点对照（2026-10-28）

## 已确认的结论

**本台架上，CarPlay 已通过 Wi-Fi 建立会话后，车机蓝牙关闭，音乐播放状态和 HUD 曲名/歌手可以持续更新；DiPlay 无须取得 Android 音频焦点。最终版已安装并完成原图复验。** 这解决了本轮观察到的原厂蓝牙媒体空歌词覆盖，但不能据此宣称“蓝牙音乐同时连接”也已修复。

首次建立/重新建立无线连接仍需开启车机蓝牙。关闭蓝牙时执行重新连接，本版本会在 `BluetoothRadioCheck` 拦截并提示开启蓝牙。这与已经建立的 Wi-Fi 会话能否继续是两个阶段。

台架地址：`100.64.122.114:5555`。全部 HUD 结论由代理检查原图得出，无须用户人工确认。没有自动拨打电话。

## 三组实际对照

| 音频行为及车机蓝牙 | 实际结果 | 判断 |
| --- | --- | --- |
| 保留原设置，原厂蓝牙媒体仍播放 | 原厂约每秒发送空歌词；DiPlay 每两秒补发仍被覆盖。sourceType 27 和沿用原厂 20 的两种补发都曾短暂出字、随后回到“暂无歌词” | 未通过；不能用反复补发交付 |
| 临时允许 DiPlay 申请媒体按键音频焦点 | DiPlay 获得 Android 焦点，原厂暂停且周期空歌词停止；但原厂暂停也传播到 iPhone。再次播放仍出现 `PLAYING → PAUSED`，单次 HUD 显示焦点交接虽收到授予回调，也不能持续播放 | 未通过；已恢复原设置并移除失败实验代码 |
| 保留原设置，已连接 CarPlay，车机蓝牙 OFF | CarPlay 画面和播放状态继续更新；原厂蓝牙媒体报告音频已断开。HUD 持续显示滚动的“曲名 / 歌手”；换曲、暂停清除、继续播放恢复均有截图 | 本次条件下通过；仍需区分实际声音和实体键验证 |

### 音频设置恢复

- “禁用媒体按键音频焦点（实验性）”：恢复为 **开启/true**，并已通过 UI 的“立即重新连接”应用。
- 独立“音频焦点”开关：仍为 **关闭/false**。
- 正常播放日志：`media keys active focusDisabled=true focusGranted=false`。
- `dumpsys audio` 的当前 external focus owners 为空；没有以接管音频焦点来保住 HUD。
- 没有测量扬声器声音，也没有把 ADB 媒体控制等同于实体音量键验收。

## 已审阅的真实画面

证据根目录：`build/hud-release/20261028/music-fix/`。

| 项目 | 证据 | 结果 |
| --- | --- | --- |
| 蓝牙关闭时持续播放 | `bt-off-playing/hud-000.png` 至 `hud-013.png`，17:37:29–17:38:41，约 73 秒、14 张；`music-review-contact-sheet.png` 为媒体区域审图索引 | 14 张均为滚动曲名/歌手，没有“暂无歌词”；长文字在固定窗口内滚动，单张仅显示其中一段 |
| 换曲 | `bt-off-next/hud-000.png`、`hud-002.png` 等 | 更新为新歌与新歌手，包含中文文本 |
| 暂停 | `bt-off-pause/hud-000.png` 至 `hud-002.png` | 音乐区清除，不残留旧文字 |
| 继续播放 | `bt-off-play-again/hud-000.png`、`hud-001.png` | 曲名/歌手恢复 |
| Android CarPlay 画面 | `bt-reconnected-ui.png` | Wi-Fi CarPlay 画面正常，播放键呈暂停图标；旁边原厂媒体卡片无蓝牙曲目 |

这些图采于实验候选 4。其单次交接代码在此轮蓝牙关闭会话中未触发；MEDIA 首次请求即获焦点。随后删除了“原厂暂停即可交接”的失败实验，编译清理版再复验，发现下述旧焦点未归还的边界。不能用候选 4 的画面代替后续版本验收。

### 最终版独立原图验收

最终安装进程 `6544`；设备 APK SHA-256 与最终产物一致。18:05:51 发出关闭蓝牙命令，18:05:54.149 原厂只读返回稳定的无音源/无播放状态 0/0，18:05:54.150 执行一次显示焦点交接，18:05:54.151 收到真实 MEDIA 授予回调。

| 项目 | 最终版原图 | 判断 |
| --- | --- | --- |
| 关闭蓝牙前的原厂覆盖 | `handover-r3-before-bt-off/hud-001.png` | 蓝牙图标和“暂无歌词”；未算通过 |
| 关闭过程 | `handover-r3-bt-off-playing/hud-000.png`，18:05:51 | 仍是原厂“暂无歌词”，处于交接前，保留过渡失败帧 |
| 交接后稳定显示 | `handover-r3-bt-off-playing/hud-001.png` 至 `hud-015.png`，18:05:57–18:07:15 | 约 78 秒内连续 15 张采样均有滚动曲名/歌手，没有回退到占位或空白；审图索引 `review-contact-sheet.png` |
| 换曲更新 | `handover-r3-next/hud-000.png` 至 `hud-003.png` | 从上一首更换为新歌/歌手，4 张均显示新文字 |
| 暂停清除 | `handover-r3-paused/hud-000.png` 至 `hud-002.png` | 3 张音乐区均为空；哈希与已审阅空白基线一致 |
| 恢复播放 | `handover-r3-resumed/hud-000.png` 至 `hud-004.png` | 5 张均恢复新歌/歌手，没有旧曲残留 |

`handover-r3-controls-review.png` 合并展示以上换曲/暂停/恢复共 12 张媒体区域，原 PNG 均保留。截图为离散采样，不将其描述为逐帧录像或精确显示延迟测量。当前场景未见应用崩溃或 ANR。

结束时车机蓝牙确认 OFF，CarPlay 媒体状态 PLAYING，Android 当前音频焦点所有者列表为空。原设置仍为禁用媒体按键音频焦点 true、独立音频焦点 false；没有临时测试模式残留。

## 抓包交叉核对

| 目录 | 捕获情况 | 音乐消息 |
| --- | --- | --- |
| `wire-bt20` | 175 包，内核丢包 0 | `3/6` 和 `3/8` 各 22 帧；原厂空歌词约每 1.007 秒，DiPlay 补发约每 2.007 秒，互相覆盖 |
| `wire-audio-focus` | 28 包，内核丢包 0 | 15 秒观察窗口没有音乐/歌词帧；结合播放状态说明原厂暂停，不能算显示修复 |
| `wire-focus-candidate4` | 见本地 tcpdump 日志 | 记录播放到暂停：原厂 `sourceType=20`，`playStatus=2 → 7`，空歌词；后续没有持续媒体流 |
| `wire-bt-off-playing` | 56 包，内核丢包 0 | `3/6`、`3/8` 各 7 帧，均来自本轮 DiPlay `sourceType=27/sourceID=1`，`lyricStatus=1`，文本 44 字节；约每 2 秒保持一次，无原厂空歌词帧 |
| `wire-clean-bt-off` | 30 包，内核丢包 0 | 清理版在先被原厂抢走显示焦点后关闭蓝牙：没有音乐帧，HUD 空白；说明停止覆盖并不保证旧显示焦点自动归还 |
| `wire-handover-r3-bt-off` | 56 包，内核丢包 0 | 最终版交接后 `3/6`、`3/8` 各 7 帧；均为 `sourceType=27/sourceID=1`、有效文本 27 字节、状态 1；没有原厂空歌词帧 |

只将 `wire-redacted.json` 和捕获计数用于报告。原始 PCAP/日志留在本地 ignored build 目录；Binder accepted、抓包均不单独代替实际 HUD 图片。

## 原厂代码原因与修复边界

原厂 `WT_MultiMediaCenter.apk`：`2.3.0.256_beta / 20300256`。

- `DSIManager` 的 `SEND_MEDIA=101` 在 `isMediaCenterFocus()` 为真时执行 `sendMediaSource()`，再安排 1000 ms 后执行。
- `isMediaCenterFocus()` 依据原厂播放器的 Android 音频焦点，而非 DiPlay 的 HUD 焦点。
- `fillSongBeanLyric(btLyric)` 在非在线音乐路径清空歌词；没有 BT 歌词时不回退到 `songName/singerName`。
- 原厂双屏服务 `DoubleMediaManager.sendMediaSource()` 不按显示焦点阻止其他客户端发送，因此本端持有显示焦点也不足以排除原厂周期覆盖。
- 已检查导出的 MusicService：只读事务 14/15/17 能读取音源、状态、歌曲。通用操作 83 的分支包含打开歌词页等控制，未找到可单独关闭 DSI 周期发送或写入 HUD 回退歌词的接口；没有试发未知控制码。

若必须保持原厂蓝牙音频并存，建议由原厂在 BT 无歌词分支使用经长度限制的曲名/歌手回退，同时将 `lyricObj.sourceID` 与歌曲 ID 对齐。这是原厂补丁方向，**本轮未安装修改后的原厂 APK，不能标为修复通过**。

原厂源码最小改法可放在非在线音乐的 BT 分支，保持原有音频焦点、播放控制和周期发送不变：

```kotlin
// 在 fillSongBean 已设置 songName/singerName 后处理；示意，不是可安装补丁。
lyricObj.clearLyricData()
val actualLyric = btLyric?.takeIf { it.isNotBlank() }
val metadataLine = listOf(songBean.songName, songBean.singerName)
    .filterNotNull().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" / ")
val line = actualLyric ?: metadataLine
if (line.isNotBlank()) {
    lyricObj.sourceID = songBean.sourceID
    lyricObj.lyricStatus = 1
    lyricObj.curLyric = boundForHudUtf8(line, 128)
}
songBean.lyricObj = lyricObj
```

`boundForHudUtf8` 需复用本台 HUD 的 UTF-8 边界和补充字符兼容处理；保留真实歌词优先，不把元数据称为真实歌词。原厂补丁验收还需检查切歌、空元数据、实际歌词、电话结束恢复及与 DiPlay 并发；只有取得原厂认可的更新包/签名后才能做原厂 APK 实机验收。

安装条件已只读核查：原厂应用位于 `/system/app/WT_MultiMediaCenter/WT_MultiMediaCenter.apk`，系统挂载为只读 erofs；原厂签名与 DiPlay 签名不同。没有本次可用的原厂签名更新流程，因此没有重签覆盖系统应用、改系统分区或停止原厂媒体服务。

## 当前 HUD 产品能力

- 当前实现使用无来源图标的 `sourceType=27`，将曲名/歌手合成单行映射到原厂歌词显示字段；这不是新增两行布局。
- 当前真实会话未获得真实逐句歌词。看到的“曲名 / 歌手”是元数据回退，不能据此宣称已获取歌曲歌词。
- 没有新增封面显示能力；原厂 HUD 接收和布局的既有封面限制不变。
- 本轮不重新声明电话、导航或实体按键验收通过。此前真实电话报告的音乐失败结论保留，不回写为通过。
- 此版本不自动开关蓝牙、不改音频设置。关闭蓝牙后的显示交接在每次原厂 Binder 连接内最多一次；正常再次失焦不反复抢占，服务连接故障仍由原有有限重连策略处理。车机蓝牙关闭会影响原厂蓝牙电话/音乐功能；CarPlay 电话在这一模式下仍需单独补齐画面验收。

## 关闭蓝牙后的显示交接修复

清理版（`music-bt-off.apk`，SHA-256 `09e005e5481ab06e6ca0b429504845454eb558b471729a7a56a5b466b1d3c81a`）已安装并核对设备 APK 哈希。17:46:01 原厂撤销 DiPlay 的 MEDIA 焦点，17:46:57 关闭车机蓝牙后，CarPlay 持续 `PLAYING`，但 HUD 的 14 张原图全为空白。此版不算最终通过版。

新增修复仅在 `vehicle-mzd` 内：

- 只读 Android 蓝牙状态和原厂 MusicService 事务 14/15，不读取歌曲、联系人，不调用原厂播放控制。
- 必须有蓝牙读取权限，蓝牙状态明确为 `STATE_OFF`；前后两次原厂读取一致，当前音源是 `SourceType.BT` 且状态为 NONE/STOPPED/PAUSED/ERROR，或原厂明确无音源且无播放状态（实测 0/0）。其他未知组合、其他音源和过渡状态拒绝交接。
- DiPlay 当前投屏源仍在播放且无电话，本会话曾实际获授 MEDIA 焦点后又被撤销，才允许重新注册并申请一次 MEDIA 显示焦点。
- 等待真实焦点授予回调后才发送文字；再次失焦不再申请。原厂播放器仍播放、蓝牙开启、其他音源、权限/服务/协议异常时均不交接。
- 不启动原本未运行的原厂 MusicService，不申请 Android 音频焦点，不把蓝牙开关作为应用策略自动执行。

修复版 APK：`build/outputs/hud/DiPlay-Mazda-HUD-release-0.2.21-20261028-music-bt-handover.apk`。

SHA-256：`bb2788425ff64ad1bf76e87805aaea4833021299b1cc9ae8eb08f7e27889e814`。

此修复版已安装、核对设备哈希并完成上文原图验收；当前台架保持该版运行。普通源码/CI 构建的 HUD 开关仍默认关闭；此实机版使用 `-PmzdHudEnabled=true` 构建并带认证，不含 debug bench。

前一版 `7b1b5bcc…3010bc4` 仅接受残留 BT 音源，未覆盖本固件断连后返回 0/0 的情况，HUD 14 张截图为空；原图保留于 `handover-bt-off-playing/`，不回写为通过。补充 0/0 后的版本使用新哈希和独立截图目录验收。

## 仓库检查

按 `.github/workflows/android.yml` 执行完整 Gradle CI，包含 display-api/vehicle-mzd/shared/common/home/mazda 单测，vehicle-mzd/mobile/home/maphost/mazda lint、debug 构建及 Mazda release 编译/manifest。最终修复源码 `BUILD SUCCESSFUL in 46s`，407 个任务（36 执行、371 为最新），3384 项测试失败/错误/跳过均为 0，其中 OEM 23 项。`display-api:test` 无测试，结果为 NO-SOURCE。

模块边界检查 `scripts/check_vehicle_display_boundaries.py --apks --release` 通过；`scripts/test_hud_bench.py` 3 项通过。发布 APK 含运行认证材料且没有 debug bench；认证材料不放入证据 ZIP。完整单测计数与产物核查写入本地 `verification-ci-summary.json` 和 `artifact.json`。

## 交付文件

- [实机 release APK](../build/outputs/hud/DiPlay-Mazda-HUD-release-0.2.21-20261028-music-bt-handover.apk)，已安装到台架。
- [音乐蓝牙/焦点对照证据包](../build/hud-release/20261028/DiPlay-HUD-Music-Bluetooth-Comparison-20261028-evidence.zip)：包含成功和失败候选的 HUD 原图、截图时间/哈希、脱敏状态日志、脱敏媒体帧摘要、最终 CI 和本报告。每个文件都有 SHA-256 清单，并已校验压缩包及清单。
- [最终版 HUD 原图索引](../build/hud-release/20261028/music-fix/review-final.html)。证据 ZIP 解压后，可打开同一路径的 HTML 审阅原图。

证据包使用白名单文件打包，不包含 APK、认证素材、原始 iAP2/无线日志、PCAP、联系人或号码。原始诊断文件仅留在本地 ignored build 目录。
