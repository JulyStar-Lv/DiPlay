# J90K 音乐 SourceType 调整

日期：2026-10-28。适用：当前 J90K 单行音乐 `SongNormal` 布局。

## 实现决策

- 默认继续使用 **27**；新增显式可选的 **29**。此前 DiPlay 已使用27，本次没有将默认值从其他来源切换为27。
- 两者均为无可见来源图标的文字兼容值，原厂业务身份未确认，不命名为原生 CarPlay。原厂 CarPlay 值30在当前接收映射中为内部0，不显示音乐。
- `MzdMediaSource` 限定这两个已验证的兼容选项，Mazda 构建参数选择后通过 Manifest 注入同一个 OEM 工厂。debug 台架与 release 使用相同配置路径。
- 来源固定于工厂生命周期；常规发送、有限心跳、电话结束恢复均使用选定值。清理始终为 `sourceType=0/sourceID=0`，歌词ID与状态也清零。
- 不因失焦、“暂无歌词”或歌词缺失而轮换来源。27/29不解决原厂媒体每秒覆盖、歌词数据缺失或焦点未授予的问题；这些结论仍见 [音乐与蓝牙/焦点验证](HUD_MUSIC_BT_FOCUS_COMPARISON_20261028.zh-CN.md)。
- 真实当前句可用时优先发送；缺失时保持“曲名 / 歌手”回退。**128字节UTF-8协议限制不变**，216像素不是协议字段容量。

改动限于 `vehicle-mzd` 与 `mazda` 的配置、装配和测试；`display-api`、手机输入适配器、共享界面不承载原厂来源值。未新增用户设置。

## 来源与显示范围

下表由原厂来源审计及已完成的布局试验得出，线上sourceType与HUD内部编号是两套编号。

| 线上 sourceType | HUD 内部编号 | 原厂表现 | 条件布局补丁后的单行文字容器 |
| --- | --- | --- | --- |
| **27** | 7 | 无图标，可显示自定义文字 | **x=38，宽216** |
| **29** | 5 | 无图标，可显示自定义文字 | **x=38，宽216** |
| 16 | 3 | 本地音乐图标；该探针仍显示“暂无歌词” | 原厂 x=78，宽176 |
| 18 | 4 | USB图标；该探针仍显示“暂无歌词” | 原厂 x=78，宽176 |
| 20 | 2 | 蓝牙图标，可显示自定义文字 | 原厂 x=78，宽176 |
| 28 | 6 | 网易云图标，可显示自定义文字 | 原厂 x=78，宽176 |
| 35 | 1 | 在线音乐图标，可显示自定义文字 | 原厂 x=78，宽176 |
| 30 | 0 | 当前通路无音乐内容 | 不因该布局补丁新增显示 |

其余5个有效来源保持原厂映射、图标和位置，不作为DiPlay文字兼容配置暴露。完整256值审计见 [原厂来源核验](../../HUDDemo/docs/HUD_SOURCE_TYPE_ICON_AUDIT_20261028.md)。

## Linux 布局依赖

**Android 单独选择27或29，仍然是原厂 x=78、宽176。** 左移40像素、宽度增加到216，需要另外部署匹配版本的 Linux `gui_main.kzb` 条件补丁。

- 仅对内部来源5/7调整 `SongNormal` 的text位置和宽度；其他内部来源显式恢复原厂值。y=229、高32、右边界x=254保持不变。
- 图标节点位置 x=38/y=230 不改；不新增 CarPlay 图标、封面或多行歌词。
- 1512项资源中只改 `/Prefabs/Basic` 与 `/State Managers/ArbLyricExist State Manager`，其他1510项内容不变。
- 原厂资源 SHA-256：`32de3f01f03da75d59f1bd7adab28beb8207fa62183060ff5a2b6746fee3721a`。
- 候选资源 SHA-256：`41cfc64b8b841a7a0199348023ef305f13a2b302771d41f96f6efdf55671c439`。

提供的台架试验结束后已恢复原厂文件；本次DiPlay代码调整没有部署Linux补丁或写入永久固件。未验证的 `SongScroll`、`gui_basic`、`gui_main_er/eu` 不套用本结论。

后续另做了 [CarPlay图标候选补丁的台架验证](../../HUDDemo/docs/HUD_CARPLAY_ICON_PATCH_20261028.md)：27显示CarPlay图标，文字恢复x=78/宽176；29继续无图标、x=38/宽216。正式38张对照图通过，原厂文件已恢复。这是独立于前述“27/29均加宽”方案的另一版Linux补丁，Android的来源配置不变。

## 构建选择

默认27，HUD仍需显式启用：

```powershell
.\gradlew.bat :mazda:assembleDebug -PmzdHudEnabled=true
```

选择29：

```powershell
.\gradlew.bat :mazda:assembleDebug -PmzdHudEnabled=true -PmzdHudMediaSourceType=29
```

release使用同一参数；独立连接iPhone仍需既有本地认证及签名流程。参数仅允许27/29，其他值在Gradle配置时拒绝。省略参数即回到27，不会自动重连或修改已经安装的应用。

## 验证依据与边界

用户提供并已核对的布局验收为**原厂19张＋候选19张，共38张**：

- 27/29短文字精确左移40px；16/18/20/28/35的短文本完整画面与原厂逐像素一致。
- 来源切换 `20→27→29→35→28→16→18→27→20` 后布局恢复正确。
- 27/29每来源各4帧长文均发生滚动，采样文字位于新裁剪范围 `[38,254)`；原厂为 `[78,254)`。
- 19对截图音乐区域外完全一致；清理后等于基线，试验后原厂资源恢复成功。

这是shell Binder合成数据的布局验收，不替代普通应用UID、真实iPhone、三行歌词、导航/电话并发或其他固件验收。本轮不将这38张计为新增采集。

本次代码验证覆盖：默认27、显式29、非法配置拒绝、29的心跳/电话后恢复/清理，以及两来源的128字节限制和真实歌词优先规则。

- `-PmzdHudMediaSourceType=29` 的 Mazda debug 构建及车型/Mazda单测通过，合并Manifest确认为29。
- `-PmzdHudMediaSourceType=30` 在Gradle配置阶段按预期拒绝，未混入其他构建错误。
- 按 `.github/workflows/android.yml` 的完整Gradle检查通过：`BUILD SUCCESSFUL in 59s`，407项任务，54执行、353 up-to-date。默认debug与release合并Manifest均确认为27。
- 单测报告合计 **3389项**，失败/错误/跳过均为0：`vehicle-mzd` 25、`shared` 1690、`common` 1646、`home` 6、`mazda` 22；`display-api` 当前无测试源。
- OEM源码与APK隔离、release不含debug台架类、公开树检查、3项台架助手测试及 `git diff --check` 均通过。

本轮未安装APK、未采集新的HUD图片，也未部署Linux资源补丁。构建日志及 [验证汇总](../build/reports/hud-source-type-20261028/verification-summary.json) 保存在 `build/reports/hud-source-type-20261028/`。

## 原始证据

- [38张截图对照](../../ApkDecompile/WT_SystemService_analysis/music-layout-20261028/layout-comparison.html)
- [逐项像素检查](../../ApkDecompile/WT_SystemService_analysis/music-layout-20261028/layout-verification.json)
- [资源修改清单](../../ApkDecompile/WT_SystemService_analysis/music-layout-20261028/conditional-layout-manifest.json)
- [布局补丁报告](../../HUDDemo/docs/HUD_MUSIC_ICON_SPACE_FIX_20261028.md)
- [补丁及证据ZIP](../../HUDDemo/dist/HUDDemo-HUD-music-layout-20261028.zip)
