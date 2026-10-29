# 上游同步记录（2026-10-29）

## 同步范围

- 上游：`https://github.com/shihabal3amri/DiPlay` 的 `upstream/main`。
- 本次获取的最新提交：`615b32ce0b40cd0b99ce94ba23a512001553684f`，包含 `0.2.22`，版本号 `41`。
- 同步前本地提交：`d998b18cf25c83a1abd1f698876a21e5f976eeca`。
- 导入上游侧新增的 272 个提交；保留本地侧 21 个提交的历史。
- 合并方式：普通双亲 merge，双亲分别为 `d998b18c`、`615b32ce`；保留双方提交历史。

## 本地开发内容与恢复点

- 合并前创建分支 `codex/backup-before-upstream-20261029`。
- 合并前工作区备份：stash `0d18f2584f64de39deeed24faa05a30b772030b8`，描述为 `backup local HUD work before upstream 615b32ce 20261029`；恢复后保留该备份。
- 61 个未跟踪文件已逐文件核对 Git 内容哈希，全部恢复一致；HUD 代码及此前验证文档继续保留在工作区。
- 重复的 `0x5204` 接收声明、车道容量声明与相应测试采用上游实现；本地严格车道诊断采集继续保留。

## 冲突整合

1. CarPlay 页面恢复：同时保留 Mazda 返回画面与上游热点信息恢复。
2. 设置页重建：同时清理 Mazda 首页视图与上游新增的连接问题状态。
3. 仪表辅助画面：沿用上游主画面首帧后的启动时序，保留独立 HUD Surface 接入。
4. 音乐校时：接入上游的到达时间统计及目标水位测量，同时保留本地固定目标模型、重缓冲恢复、限速与拥塞期间禁止学习机制。
5. 上游新增音频测试的模型适配本地独立 PCM 队列及限流写入，保留原有断言。首次 CI 在该模型等待虚拟设备消费时中止，修复后重新执行。
6. 目标水位计入独立 PCM 队列；采用两个三秒测量窗口的平均值，消除短窗口选择单个到包相位造成的启动校时偏差。

车道协议接收及采集已整合；这不代表 Mazda HUD 左侧车道箭头已接通，后续仍需完成车道数据到原厂 Binder 的映射。

## 验证

已按工作区 `.github/workflows/android.yml` 执行完整 CI，最终结果为 `BUILD SUCCESSFUL`（3 分 11 秒）。本次同时覆盖工作区尚未提交的 HUD 模块。

```powershell
.\gradlew.bat :display-api:test :display-android:lintDebug :display-android:assembleDebug :vehicle-mzd:testDebugUnitTest :vehicle-mzd:lintDebug :vehicle-mzd:assembleDebug :shared:testDebugUnitTest :common:testDebugUnitTest :home:testDebugUnitTest :mazda:testDebugUnitTest :mobile:lintDebug :home:lintDebug :maphost:lintDebug :mobile:assembleDebug :home:assembleDebug :maphost:assembleDebug :mazda:lintDebug :mazda:assembleDebug :mazda:compileReleaseKotlin :mazda:processReleaseManifest --stacktrace --console=plain
```

| 模块 | 测试总数 | 通过 | 失败/错误 | 跳过 |
| --- | ---: | ---: | ---: | ---: |
| shared | 1892 | 1887 | 0 | 5 |
| common | 1890 | 1890 | 0 | 0 |
| vehicle-mzd | 47 | 47 | 0 | 0 |
| mazda | 23 | 23 | 0 | 0 |
| home | 11 | 11 | 0 | 0 |
| 合计 | 3863 | 3858 | 0 | 5 |

`:display-api:test` 当前没有测试源码，Gradle 报告 `NO-SOURCE`，未计入上述通过数量。

Windows 环境跳过 5 项：

- `AmbientWorkerOwnerLockSymlinkTest` 的 3 项：本机缺少创建符号链接的权限；仅对明确的 Windows 权限错误使用 JUnit assumption，其他错误仍失败。Linux CI 继续实际执行这 3 项。
- `BydCarPlayCallQuotingTest` 的 2 项：上游测试要求 `/bin/sh`，本机不满足其原有 assumption；本次未修改这些测试。Linux CI 继续执行。

其余检查：

- `python scripts/check_public_tree.py`：通过。
- `python scripts/check_vehicle_display_boundaries.py`：通过。
- `python -m unittest discover -s scripts -p 'test_hud*.py'`：11 项通过。
- `python scripts/check_vehicle_display_boundaries.py --apks --release`：通过；mobile、home、maphost 不含 Mazda 驱动、OEM metadata 或台架入口，Mazda Debug 包含所需集成，Mazda Release 类及 Manifest 不含台架入口。
- workflow 中所有 Lint、四个应用 Debug 构建、Mazda Release Kotlin 编译和 Manifest 处理：通过。
- 设置界面的紧凑、大屏及阿拉伯语大字体截图已检查，输出位于 `common/build/reports/swipe-menu-ui/`。
- `git diff --check` 和暂存区空白检查：通过。

本机缺少新增测试使用的 Android 6 Robolectric 运行库，原下载停滞；已补齐 `6.0.1_r3-robolectric-r1-i7`，SHA-512 与 Maven Central 元数据一致。补齐后共享层已完整重跑。

本地验证证据：

- 完整 CI 日志：`build/upstream-20261029/ci-final.log`。
- JUnit 汇总及具体跳过列表：`build/upstream-20261029/ci-summary.json`。
- 原未跟踪文件保留检查：`build/upstream-20261029/wip-preservation.json`（61 个文件，0 项差异）。

本次仅完成本地仓库同步，未推送远程或部署台架版本。
