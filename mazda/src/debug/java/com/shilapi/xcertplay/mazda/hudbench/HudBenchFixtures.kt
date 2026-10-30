package com.shilapi.xcertplay.mazda.hudbench

import com.shilapi.display.api.*
import com.shilapi.xcertplay.iap2.message.Iap2Messages.buildRaw
import com.shilapi.xcertplay.iap2.wire.Iap2Frame

internal data class HudBenchValue(
    val navigation: NavigationState = NavigationState(),
    val media: MediaState = MediaState(),
    val calls: List<CallState> = emptyList(),
)
internal data class HudBenchStep(
    val atMs: Long, val label: String, val expected: HudBenchValue,
    val frames: List<Iap2Frame>, val reconnect: Boolean = false,
)
internal data class HudBenchCase(
    val id: String, val group: String, val description: String,
    val steps: List<HudBenchStep>, val durationMs: Long = 10_000,
    val extraSamples: List<Long> = emptyList(),
) {
    val samples: List<Long> get() = (listOf(2_000L, 7_000L) + extraSamples + steps.map { it.atMs + 1_000 })
        .distinct().sorted().filter { it < durationMs }
}

/** Fixed synthetic fixtures, no phone identifiers or data imported from a real session. */
internal object HudBenchFixtures {
    fun music(title: String?, artist: String?, playing: Boolean?): List<Iap2Frame> = listOf(buildRaw(0x5001) {
        group(0) { title?.let { string(1, it) }; artist?.let { string(12, it) } }
        playing?.let { group(1) { u8(0, if (it) 1 else 0) } }
    })
    private fun nav(type: Int, side: Int = 0, distance: Long? = 150, remaining: Long = 5000, seconds: Long = 120): List<Iap2Frame> = listOf(
        buildRaw(0x5202) { u16(1, 7); u8(3, type); u8(8, side); string(4, "测试下一道路") },
        buildRaw(0x5201) {
            u8(1, 1); u16List(13, listOf(7)); string(3, "测试当前道路")
            distance?.let { u32(10, it) }; u32(7, remaining); u64(6, seconds)
        })
    private fun route(kind: ManeuverKind, side: DrivingSide = DrivingSide.RIGHT, distance: Long? = 150, exit: Int? = null) =
        NavigationState(NavigationPhase.GUIDING, kind, side, exit, distance,
            "测试当前道路", "测试下一道路", 5000, 120_000)
    private fun laneBlock(index: Int, lanes: List<NavigationLane>) = buildRaw(0x5204) {
        u16(0, 42); u16(1, index)
        lanes.forEach { lane -> group(2) {
            u16(0, lane.index); u8(1, if (lane.recommended) 2 else 0)
            lane.anglesDegrees.forEach { i16(2, it) }
            lane.highlightedAngleDegrees?.let { i16(3, it) }
        } }
    }
    private fun laneSelection(index: Int, show: Boolean = true) = buildRaw(0x5201) {
        u16(16, index); u16(17, 8); u8(18, if (show) 1 else 0)
    }
    private fun phone(id: String, status: Int, name: String? = null, number: String? = null) = buildRaw(0x4155) {
        string(4, id); u8(2, status); name?.let { string(1, it) }; number?.let { string(0, it) }
    }
    private fun step(at: Long, label: String, value: HudBenchValue, vararg frames: Iap2Frame) = HudBenchStep(at, label, value, frames.toList())
    private fun turn(id: String, name: String, first: Pair<ManeuverKind, Int>, second: Pair<ManeuverKind, Int>) = HudBenchCase(
        id, "navigation", name, listOf(
            HudBenchStep(0, "150米", HudBenchValue(route(first.first)), nav(first.second)),
            HudBenchStep(4_000, "80米", HudBenchValue(route(second.first, distance = 80)), nav(second.second, distance = 80))))

    fun all(): List<HudBenchCase> {
        val left = route(ManeuverKind.LEFT)
        val song = MediaState("HUD测试歌曲A", "测试歌手A", playback = PlaybackState.PLAYING)
        val incoming = CallState("bench-a", CallPhase.RINGING, "测试联系人", "13800000000")
        val active = incoming.copy(phase = CallPhase.ACTIVE, connectedAtMillis = 3_000)
        val twoLanes = listOf(NavigationLane(0, listOf(-90), true), NavigationLane(1, listOf(0)))
        val fourLanes = listOf(NavigationLane(0, listOf(-90, 0)), NavigationLane(1, listOf(0)),
            NavigationLane(2, listOf(0)), NavigationLane(3, listOf(0), true, 90))
        val eightLanes = (0..7).map { lane -> NavigationLane(lane,
            listOf(if (lane < 2) -90 else if (lane == 7) 90 else 0), lane < 2) }
        fun withLanes(lanes: List<NavigationLane>) = HudBenchValue(left.copy(laneGuidance = NavigationLaneGuidance(lanes, 0)))
        return listOf(
            turn("N01", "直行与左右转", ManeuverKind.STRAIGHT to 3, ManeuverKind.LEFT to 1),
            turn("N02", "右转与距离变化", ManeuverKind.RIGHT to 2, ManeuverKind.RIGHT to 2),
            turn("N03", "轻微转向", ManeuverKind.SLIGHT_LEFT to 13, ManeuverKind.SLIGHT_RIGHT to 14),
            turn("N04", "急转弯", ManeuverKind.SHARP_LEFT to 47, ManeuverKind.SHARP_RIGHT to 48),
            HudBenchCase("N05", "navigation", "按驾驶侧掉头", listOf(
                HudBenchStep(0, "右侧行驶掉头", HudBenchValue(route(ManeuverKind.U_TURN_LEFT)), nav(4)),
                HudBenchStep(4_000, "左侧行驶掉头", HudBenchValue(route(ManeuverKind.U_TURN_RIGHT, DrivingSide.LEFT)), nav(4, 1)))),
            HudBenchCase("N06", "navigation", "环岛与出口", listOf(
                HudBenchStep(0, "右侧环岛第2出口", HudBenchValue(route(ManeuverKind.ROUNDABOUT_NUMBERED, exit = 2)), nav(29)),
                HudBenchStep(4_000, "左侧环岛第10出口", HudBenchValue(route(ManeuverKind.ROUNDABOUT_NUMBERED, DrivingSide.LEFT, exit = 10)), nav(37, 1)))),
            HudBenchCase("N07", "navigation", "未知转向不伪造直行", listOf(
                HudBenchStep(0, "未知转向", HudBenchValue(route(ManeuverKind.UNKNOWN)), nav(250)))),
            HudBenchCase("N08", "navigation", "缺失距离等待，增量补齐后显示", listOf(
                HudBenchStep(0, "缺少动作距离", HudBenchValue(left.copy(distanceMeters = null)), nav(1, distance = null)),
                step(4_000, "仅补距离", HudBenchValue(left), buildRaw(0x5201) { u32(10, 150) }))),
            HudBenchCase("N09", "navigation", "取消与到达分别清理", listOf(
                HudBenchStep(0, "引导", HudBenchValue(left), nav(1)),
                step(3_000, "到达", HudBenchValue(NavigationState(NavigationPhase.ARRIVED)), buildRaw(0x5201) { u8(1, 2) }),
                step(6_000, "取消", HudBenchValue(NavigationState(NavigationPhase.ENDED)), buildRaw(0x5201) { u8(1, 0) }))),
            HudBenchCase("N10", "navigation", "停止更新90秒后隐藏", listOf(
                HudBenchStep(0, "保持同一快照，不发心跳", HudBenchValue(left), nav(1))),
                durationMs = 94_000, extraSamples = listOf(32_000, 60_000, 89_000, 92_000)),
            HudBenchCase("N11", "navigation", "59秒稀疏更新持续显示", listOf(
                HudBenchStep(0, "首个快照", HudBenchValue(left), nav(1)),
                HudBenchStep(59_000, "真实输入刷新时间戳", HudBenchValue(left), nav(1))),
                durationMs = 94_000, extraSamples = listOf(32_000, 60_000, 89_000, 92_000)),
            HudBenchCase("N12", "navigation", "长路线剩余距离独立于转向距离", listOf(
                HudBenchStep(0, "131071米剩余距离", HudBenchValue(left.copy(remainingMeters = 131071, remainingMillis = 37_680_000)), nav(1, remaining = 131071, seconds = 37680)),
                HudBenchStep(4_000, "1018公里", HudBenchValue(left.copy(remainingMeters = 1_018_000, remainingMillis = 37_680_000)), nav(1, remaining = 1_018_000, seconds = 37680)),
                HudBenchStep(8_000, "9999公里", HudBenchValue(left.copy(remainingMeters = 9_999_000, remainingMillis = 37_680_000)), nav(1, remaining = 9_999_000, seconds = 37680))), durationMs = 12_000),
            HudBenchCase("N13", "navigation", "左侧双车道及隐藏恢复剩余信息", listOf(
                HudBenchStep(0, "两车道，左转推荐白色", withLanes(twoLanes),
                    nav(1) + laneBlock(3, twoLanes) + laneSelection(3)),
                step(4_000, "隐藏车道，恢复剩余信息", HudBenchValue(left), laneSelection(3, false)))),
            HudBenchCase("N14", "navigation", "左侧四车道复合分支及未来车道组", listOf(
                HudBenchStep(0, "四车道，最右直行加右转推荐", withLanes(fourLanes),
                    nav(1) + laneBlock(3, fourLanes) + laneSelection(3)),
                step(3_000, "预载下一路口，不覆盖当前车道", withLanes(fourLanes), laneBlock(4, twoLanes)),
                step(6_000, "切换当前组后才显示新车道", withLanes(twoLanes), laneSelection(4)))),
            HudBenchCase("N15", "navigation", "左侧八车道接近设计图并结束清除", listOf(
                HudBenchStep(0, "八车道，左侧两条推荐白色", withLanes(eightLanes),
                    nav(1) + laneBlock(0, eightLanes) + laneSelection(0)),
                step(6_000, "结束导航清除整排车道", HudBenchValue(NavigationState(NavigationPhase.ENDED)),
                    buildRaw(0x5201) { u8(1, 0) }))),
            HudBenchCase("M01", "music", "歌名歌手及换曲", listOf(
                HudBenchStep(0, "中文曲目", HudBenchValue(media = song), music(song.title, song.artist, true)),
                HudBenchStep(4_000, "英文曲目", HudBenchValue(media = MediaState("Bench Song B", "Bench Artist", playback = PlaybackState.PLAYING)), music("Bench Song B", "Bench Artist", true)))),
            HudBenchCase("M02", "music", "暂停与恢复保留元数据", listOf(
                HudBenchStep(0, "播放", HudBenchValue(media = song), music(song.title, song.artist, true)),
                HudBenchStep(3_000, "仅暂停状态", HudBenchValue(media = song.copy(playback = PlaybackState.PAUSED)), music(null, null, false)),
                HudBenchStep(6_000, "恢复", HudBenchValue(media = song), music(null, null, true)))),
            HudBenchCase("M03", "music", "长文本及UTF-8边界", listOf(
                HudBenchStep(0, "长中文与emoji", HudBenchValue(media = song.copy(title = "测试😀".repeat(40))), music("测试😀".repeat(40), song.artist, true))),
                durationMs = 18_000, extraSamples = listOf(12_000, 16_000)),
            HudBenchCase("M04", "music", "省略保留，显式空值清除", listOf(
                HudBenchStep(0, "完整元数据", HudBenchValue(media = song), music(song.title, song.artist, true)),
                HudBenchStep(3_000, "仅改歌手", HudBenchValue(media = song.copy(artist = "新歌手")), music(null, "新歌手", null)),
                HudBenchStep(6_000, "清空文字", HudBenchValue(media = MediaState(playback = PlaybackState.PLAYING)), music("", "", null)))),
            HudBenchCase("M05", "music", "短文本含emoji对照", listOf(
                HudBenchStep(0, "短中文与emoji", HudBenchValue(media = song.copy(title = "测试😀短曲")), music("测试😀短曲", song.artist, true)))),
            HudBenchCase("M06", "music", "长纯中文对照", listOf(
                HudBenchStep(0, "长纯中文", HudBenchValue(media = song.copy(title = "长中文".repeat(40))), music("长中文".repeat(40), song.artist, true))),
                durationMs = 18_000, extraSamples = listOf(12_000, 16_000)),
            HudBenchCase("M07", "music", "127字节ASCII对照", listOf(
                HudBenchStep(0, "127字节", HudBenchValue(media = song.copy(title = "A".repeat(127))), music("A".repeat(127), song.artist, true)))),
            HudBenchCase("M08", "music", "128字节ASCII对照", listOf(
                HudBenchStep(0, "128字节", HudBenchValue(media = song.copy(title = "B".repeat(128))), music("B".repeat(128), song.artist, true)))),
            HudBenchCase("P01", "phone", "姓名来电、接通与结束", listOf(
                step(0, "来电", HudBenchValue(calls = listOf(incoming)), phone(incoming.id, 2, incoming.name, incoming.number)),
                step(3_000, "接通并计时", HudBenchValue(calls = listOf(active)), phone(incoming.id, 4)),
                step(7_000, "结束", HudBenchValue(), phone(incoming.id, 0)))),
            HudBenchCase("P02", "phone", "无姓名回退号码", listOf(
                step(0, "号码来电", HudBenchValue(calls = listOf(incoming.copy(name = null))), phone(incoming.id, 2, "", incoming.number)))),
            HudBenchCase("P03", "phone", "呼出、连接与通话", listOf(
                step(0, "呼出", HudBenchValue(calls = listOf(incoming.copy(phase = CallPhase.DIALING))), phone(incoming.id, 1, incoming.name, incoming.number)),
                step(3_000, "连接", HudBenchValue(calls = listOf(incoming.copy(phase = CallPhase.CONNECTING))), phone(incoming.id, 3)),
                step(6_000, "通话", HudBenchValue(calls = listOf(active.copy(connectedAtMillis = 6_000))), phone(incoming.id, 4)))),
            HudBenchCase("P04", "phone", "保持、来电等待及一路结束", listOf(
                step(0, "活动通话", HudBenchValue(calls = listOf(active.copy(connectedAtMillis = 0))), phone(incoming.id, 4, incoming.name, incoming.number)),
                step(3_000, "保持并有第二路来电", HudBenchValue(calls = listOf(active.copy(phase = CallPhase.HELD, connectedAtMillis = 0), incoming.copy(id = "bench-b", name = "第二联系人"))), phone(incoming.id, 5), phone("bench-b", 2, "第二联系人", incoming.number)),
                step(6_000, "第二路结束，恢复原通话", HudBenchValue(calls = listOf(active.copy(connectedAtMillis = 0))), phone("bench-b", 0), phone(incoming.id, 4)))),
            HudBenchCase("P05", "phone", "姓名清空后回退，号码继续保留", listOf(
                step(0, "姓名和号码", HudBenchValue(calls = listOf(incoming)), phone(incoming.id, 2, incoming.name, incoming.number)),
                step(4_000, "显式清空姓名", HudBenchValue(calls = listOf(incoming.copy(name = null))), phone(incoming.id, 2, "")))),
            HudBenchCase("L01", "lifecycle", "会话替换，延迟旧事件不得覆盖", listOf(
                HudBenchStep(0, "旧会话歌曲", HudBenchValue(media = song), music(song.title, song.artist, true)),
                HudBenchStep(4_000, "新会话和旧事件干扰", HudBenchValue(media = MediaState("NEW-SESSION", "新会话", playback = PlaybackState.PLAYING)), music("NEW-SESSION", "新会话", true), reconnect = true))),
            HudBenchCase("C01", "coexistence", "导航音乐来电共存及结束恢复", listOf(
                HudBenchStep(0, "导航和音乐", HudBenchValue(left, song), nav(1) + music(song.title, song.artist, true)),
                step(3_000, "来电覆盖", HudBenchValue(left, song, listOf(incoming)), phone(incoming.id, 2, incoming.name, incoming.number)),
                step(6_000, "电话结束保留导航音乐", HudBenchValue(left, song), phone(incoming.id, 0))),
                durationMs = 18_000, extraSamples = listOf(9_000, 12_000, 16_000))
        )
    }
    fun select(group: String) = all().also { require(group in setOf("all", "navigation", "music", "phone", "lifecycle", "coexistence", "regression")) }
        .filter { group == "all" || it.group == group || group == "regression" && it.id in setOf("N08", "N09", "N10", "N11", "N12", "N13", "N14", "N15", "M03", "M05", "M06", "M07", "M08", "C01") }
}
