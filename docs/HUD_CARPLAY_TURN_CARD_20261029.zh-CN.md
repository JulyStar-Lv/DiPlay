# CarPlay HUD右侧指示卡验证

后续方案：车道回到左侧原厂车道行，右侧使用路线地图；完整接入及多车道验证见 [左侧车道修复](HUD_CARPLAY_LANES_FIX_20261029.zh-CN.md)。本文保留此前 instruction card 探索记录，该配置可能与左侧车道信息重复。

日期：2026-10-29。适用当前J90K台架800×480 HUD、右侧 `[584,179,762,357)` 区域。

## 验证目标

取消右侧普通地图，先验证手机提供的转向指示卡。当前iPhone的辅助内容列表包含 `maps:/car/instrumentcluster/instructioncard`，但声明入口不等于高德已实际输出卡片。

- 第一阶段：iPhone指示卡进入原有辅助视频流，观察是否有图、178×178内的箭头/距离/文字是否可读、是否在直行时隐藏，以及导航结束/重新开始的行为。
- 如果高德不出卡片或布局不适合，再验证只使用真实TBT数据绘制的大箭头指示卡。它表示动作，不表示真实道路形状。
- 本轮不把TBT箭头卡当作真实路口放大图；当前结构化导航模型没有完整道路几何或独立路口图片。

## 代码边界

`display-android` 的通用画面请求新增 `MapStreamContent.INSTRUCTION_CARD`；CarPlay来源适配器将其转换为手机指示卡URL。OEM仅选择内容语义和本车几何，不包含CarPlay URL。默认仍是地图，普通构建仍关闭HUD路径。

指示卡申请256×256 / 15fps、全画面安全区域，不沿用地图的偏下车标安全区；输出仍是178×178、完整等比显示。左侧TBT继续走原厂Binder。未修改Linux布局/接收端或原厂媒体发送逻辑。

台架正式版参数：

```text
-PmzdHudEnabled=true -PmzdHudMapEnabled=true
-PmzdHudMapContent=instruction_card
-PmzdHudMediaSourceType=20 -PmzdHudAutomaticA2dpHandover=true
```

只安装正式包 `com.thunder.carplay`，不重新安装 `com.thunder.carplay.mazdatest`。

## 验收标准

1. 校验已安装包的内容配置与实际视频尺寸；手机入口声明、Binder返回、纹理更新均不能单独作为出图通过。
2. HUD原图中实际出现可读指示卡，确认没有普通地图残留、越界、异常拉伸或大幅裁切。
3. 分别记录真实直行、转向前场景。只有直行没有卡片，不能直接断言转弯前也不支持。
4. 用户在手机实际结束导航后，检查源端结束事件和HUD旧内容清除。再次开始不能显示旧卡片。
5. 自动采集的显示通过标记保持false；另附独立审图记录，不用协议成功替代实图验收。

## 当前记录

验证进行中，尚无指示卡出图结论。

首次构建检查误将显式启用HUD的参数同时传给了默认关闭断言用例，导致2项预期默认隔离检查失败。保留用例，改为默认参数运行源码CI、显式参数单独构建台架release；此失败不归类为卡片出图失败。
