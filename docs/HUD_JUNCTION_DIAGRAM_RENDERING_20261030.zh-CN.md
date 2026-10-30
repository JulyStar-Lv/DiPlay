# HUD 右侧路口角度示意图接入

日期：2026-10-30。适用范围：当前 Mazda/J90K `MeterDisplay#0` 普通布局，右侧 `(584,179,178,178)`。

## 1. 接入内容

已将 [Apple 地图实采路口角度](HUD_CARPLAY_JUNCTION_FIELDS_20261030.zh-CN.md) 接入右侧本地绘制链路：

```text
CarPlay 0x5202（按 maneuver 索引缓存）
    → NavigationState.junction（当前选中的路口）
    → Mazda 导航有效性、焦点、模式就绪门控
    → JunctionDiagramView / JunctionDiagramRenderer
    → 178×178 独立图层
    → 现有 Presentation → MeterDisplay#0 → 原厂帧通道 → Linux HUD
```

本次接入到 App 的生产代码及原厂虚拟屏窗口，不更改原厂 Binder ABI 或 Linux 资源。
本轮验证为离线 Android 原生图形及窗口合成；**未安装新版、未验证本轮绘制在台架上的实际画面**。

## 2. 输入与显示策略

| 输入状态 | 右侧行为 |
| --- | --- |
| 当前路口为 `INTERSECTION`，其他道路角度列表和出口角度均存在 | 用本地路口示意图覆盖手机视频 |
| 其他道路列表明确为空，出口角度存在 | 仅绘制来源提供的出口及进入方向 |
| 缺少任一角度字段、类型未知、环岛类型或角度非法 | 隐藏自绘层，沿用现有手机视频；没有活动视频时关闭窗口 |
| 完整路口数据存在，但辅助视频尚无活动帧 | 当前 lease 与导航门控有效时仍可显示示意图 |
| 导航结束、到达、超时、失去原厂导航焦点、来源会话关闭或替换 | 隐藏并清除对应窗口，不保留旧路口 |

该绘制器不从转向类型、路名或车道箭头补造道路。环岛先保留视频回退，待真实环岛角度及行驶方向策略验证后扩展。
同样的通用字段对其他手机来源也适用；本次仅 Apple 地图角度获得实采确认，高德角度仍待补测。

## 3. 视觉与坐标

- 纯黑背景，灰色其他道路、绿色 `#00ffa4` 出口路线、白色固定车标。
- 178×178 时道路宽 4px、路线宽 10px、黑色描边总宽 14px，配色与尺寸由现有 Mazda presentation 策略提供。
- 直行为 0°，右为正、左为负；使用 `x=cx+sin(angle)*r`、`y=cy-cos(angle)*r` 投影。
- 其他道路与所选出口分开绘制。车标至路口的进入段用于表明行驶方向，所有射线为固定示意长度，不代表道路长度或真实地图比例。
- 窗口保持 `(584,179,178,178)`，整张 Presentation 仍为 800×480 透明画布。
- 自绘层不绘制车道条、路名或距离；左侧继续使用现有结构化 Binder 输出。缺失字段时的手机视频内容仍由手机决定。

本次修复了窗口 Canvas 尺寸与 View 尺寸不同导致的黑屏：View 显式以自身 `width/height` 调用绘制器，不能使用底层整张 800×480 Canvas 的默认尺寸。

## 4. 模块分离及 Surface 所有权

- [JunctionDiagramRenderer / View](../display-android/src/main/java/com/shilapi/display/android/JunctionDiagramRenderer.kt)：仅使用通用路口数据及传入的样式，不依赖手机协议、OEM Binder 或 HUD 坐标。
- [MzdVehicleDisplayFactory](../vehicle-mzd/src/main/java/com/shilapi/vehicle/mzd/MzdVehicleDisplayFactory.kt)：复制角度列表，并通过与左侧导航相同的有效性／焦点／90 秒期限门控传递当前路口。
- [MzdHudMapOutput](../vehicle-mzd/src/main/java/com/shilapi/vehicle/mzd/MzdHudMapOutput.kt)：负责目标屏、位置、窗口和导航 lease；角度变化仅更新图层，不重建已有视频窗口。
- 图层作为手机视频容器的兄弟 View 叠放，避开指示卡色调过滤，确保绿色路线不被转为灰色。
- 手机解码器仍独占其 TextureView Surface；自绘直接使用 Android View Canvas，没有第二个 Surface 写入者，也不停止或重新协商视频流。
- 门控状态以完整快照通过主线程刷新；切到无角度路口时立即移除自绘层。新 lease 替换旧 lease 后，旧 lease 的迟到 close 不能关闭后继窗口。

本次不新增设置项，不改变导航会话协商及现有默认音频行为。

## 5. 离线证据及验证

实采数值回放：

| 样本 | 其他道路角度 | 所选出口 |
| --- | --- | --- |
| Apple 左转 | `[94,11]` | `-81` |
| Apple 右转 | `[2,94,-122]` | `68` |

以下为 Android 原生图形测试输出，**不是台架截图**：

![Apple 左转角度回放，178×178](../build/hud-junction-drawing-side-20261030-115436/images/mzd-left-178.png)
![Apple 右转角度回放，178×178](../build/hud-junction-drawing-side-20261030-115436/images/mzd-right-178.png)

[原尺寸及 2 倍对照](../build/hud-junction-drawing-side-20261030-115436/index.html) · [图像散列清单](../build/hud-junction-drawing-side-20261030-115436/image-manifest.json)

测试覆盖：

- 左右转方向、全部灰色分支、负角度、路线宽度、车标及窗口边界。
- 缺少字段、非法角度、未知／环岛类型不启用示意图；明示空列表可用。
- 没有活动辅助视频时显示完整路口数据；缺失几何回退视频。
- 图层与视频色调滤镜隔离；方向切换无需替换视频窗口。
- 导航焦点丢失、超时、结束、来源替换、旧 lease 迟到关闭、列表防御性复制。
- 新增测试：[绘制器](../display-android/src/test/java/com/shilapi/display/android/JunctionDiagramRendererTest.kt)、[窗口合成](../vehicle-mzd/src/test/java/com/shilapi/vehicle/mzd/MzdHudJunctionWindowTest.kt)、[导航门控](../vehicle-mzd/src/test/java/com/shilapi/vehicle/mzd/MzdHudMapTest.kt)。

验证结果：

- 本轮新增 9 项针对性测试通过：绘制器 4 项、窗口合成 4 项、导航门控 1 项。
- 按 `.github/workflows/android.yml` 运行完整任务列表，`BUILD SUCCESSFUL`，耗时 34 分 41 秒；476 个任务，100 个执行、376 个 up-to-date。
- 单元测试总计 3932 项，3927 通过、0 失败、0 错误、5 项为已有 Windows 环境跳过项；未增加跳过规则。
- 17 项 Python 证据工具测试、公开源码检查、模块边界及 APK 隔离检查通过。
- Mobile、Home、Maphost 未引入车型驱动；Mazda release 无 debug bench 类或 Manifest 入口。
- 左转和右转的 178×178 原生图形回放已自行审图，方向、支路及颜色与样本一致；没有要求用户确认截图。
- 测试结果和截图目录使用 ignored 目录下的临时 Gradle init script 隔离，未修改仓库构建配置、未停止其他构建。

- [CI 日志](../build/hud-junction-drawing-side-20261030-115436/ci.log)
- [针对性测试日志](../build/hud-junction-drawing-side-20261030-115436/targeted-fixed.log)
- [验证汇总](../build/hud-junction-drawing-side-20261030-115436/verification.json)
- [APK 隔离检查](../build/hud-junction-drawing-side-20261030-115436/apk-boundaries.log)

## 6. 台架验收范围

安装包含本次修改的版本后，需用 Apple 地图跨越至少一个携带几何的路口，检查右侧方向及灰色分支、无几何路口的视频回退、导航结束／重连清理，以及左侧多车道输出。
本轮不把离线合成结果当作已通过 Linux HUD 像素验收，也不宣称能恢复真实道路曲线、连续路线或独立路口实景图。
