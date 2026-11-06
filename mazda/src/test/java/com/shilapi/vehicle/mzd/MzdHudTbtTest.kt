package com.shilapi.vehicle.mzd

import android.app.Presentation
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Looper
import android.view.Display
import android.view.Surface
import android.widget.FrameLayout
import com.shilapi.display.api.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowBuild
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.util.ReflectionHelpers
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MzdHudTbtTest {
    private val state = NavigationState(NavigationPhase.GUIDING, ManeuverKind.LEFT,
        distanceMeters = 375, nextRoad = "苏源大道", remainingMeters = 2700,
        remainingMillis = 360000, receivedAtMillis = 1000)

    private fun image(value: NavigationState?) = Bitmap.createBitmap(178, 178, Bitmap.Config.ARGB_8888).also {
        MzdHudTbtRenderer(RuntimeEnvironment.getApplication()).draw(Canvas(it), value)
    }
    private fun pixels(image: Bitmap, top: Int, bottom: Int, color: Int? = null) =
        (top until bottom).sumOf { y -> (0 until image.width).count { x ->
            val value = image.getPixel(x, y)
            if (color == null) value != Color.BLACK else value == color
        } }

    @Test fun localCardHasTbtAndNumbersButNoInventedLanesOrOutOfBoundsPixels() {
        val larger = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        larger.eraseColor(Color.MAGENTA)
        MzdHudTbtRenderer(RuntimeEnvironment.getApplication()).draw(Canvas(larger), state)
        assertTrue(pixels(larger, 10, 100, Color.WHITE) > 100)
        assertTrue(pixels(larger, 145, 178, Color.WHITE) > 50)
        assertEquals(0, pixels(image(state), 99, 129))
        for (x in 178 until 200) for (y in 0 until 200) assertEquals(Color.MAGENTA, larger.getPixel(x, y))
        for (y in 178 until 200) for (x in 0 until 178) assertEquals(Color.MAGENTA, larger.getPixel(x, y))
        assertEquals(0, pixels(image(state.copy(phase = NavigationPhase.ENDED)), 0, 178))
        assertEquals(0, pixels(image(null), 0, 178))
    }

    @Test fun laneRowsKeepPhysicalOrderAndOnlyHighlightProvidedDirections() {
        val lanes = listOf(NavigationLane(0, listOf(-90), true), NavigationLane(1, listOf(0)),
            NavigationLane(2, listOf(0, 90), true, 90))
        assertEquals(lanes, MzdHudTbtRenderer.lanes(NavigationLaneGuidance(lanes.reversed(), 0)))
        val value = image(state.copy(laneGuidance = NavigationLaneGuidance(lanes, 0)))
        assertTrue(pixels(value, 99, 129, Color.WHITE) > 10)
        assertTrue(pixels(value, 99, 129, MzdHudTbtRenderer.OTHER_LANE) > 10)
        val withoutLanes = image(state)
        for (y in 0 until 99) for (x in 0 until 178) {
            assertEquals("TBT and road must not move when lanes appear", withoutLanes.getPixel(x, y), value.getPixel(x, y))
        }
        assertTrue(MzdHudTbtRenderer.lanes(NavigationLaneGuidance(lanes + lanes.first(), 0)).isEmpty())
        assertTrue(MzdHudTbtRenderer.lanes(NavigationLaneGuidance(listOf(NavigationLane(1, listOf(0))), 0)).isEmpty())
        assertTrue(MzdHudTbtRenderer.lanes(NavigationLaneGuidance(listOf(NavigationLane(0, listOf(181))), 0)).isEmpty())
        assertTrue(MzdHudTbtRenderer.lanes(NavigationLaneGuidance((0..12).map { NavigationLane(it, listOf(0)) }, 0)).isEmpty())
        assertTrue(MzdHudTbtRenderer.lanes(null).isEmpty())
    }

    @Test fun nineThroughTwelveLanesRetainEveryPhysicalLaneBelowRoadAndClearTogether() {
        for (count in 9..12) {
            val lanes = (0 until count).map { NavigationLane(it,
                listOf(if (it == 0) -90 else if (it == count - 1) 90 else 0), it == 0 || it == count - 1) }
            val guidance = NavigationLaneGuidance(lanes.reversed(), 0)
            assertEquals(lanes, MzdHudTbtRenderer.lanes(guidance))
            val value = image(state.copy(laneGuidance = guidance))
            for (index in lanes.indices) {
                val left = (8f + 162f / count * index).toInt()
                val right = (8f + 162f / count * (index + 1)).toInt()
                assertTrue("$count lanes: missing physical lane $index", (99 until 129).any { y ->
                    (left until right).any { x -> value.getPixel(x, y) != Color.BLACK }
                })
            }
            assertTrue(pixels(value, 99, 129, Color.WHITE) > 0)
            assertTrue(pixels(value, 99, 129, MzdHudTbtRenderer.OTHER_LANE) > 0)
            assertEquals(0, pixels(image(state.copy(phase = NavigationPhase.ENDED, laneGuidance = guidance)), 0, 178))
        }
        assertTrue(pixels(image(state), 70, 96) > 0) // road between TBT and lanes
    }

    @Test fun unknownManeuverDoesNotBecomeStraightAndLongValuesFitTheCard() {
        val value = image(state.copy(maneuver = ManeuverKind.UNKNOWN, distanceMeters = null))
        assertEquals(0, pixels(value, 0, 70))
        assertEquals("999m", MzdHudTbtRenderer.distance(999))
        assertEquals("1km", MzdHudTbtRenderer.distance(1000))
        assertEquals("2.7km", MzdHudTbtRenderer.distance(2700))
        assertEquals("1分", MzdHudTbtRenderer.duration(1))
        assertEquals("1时1分", MzdHudTbtRenderer.duration(3600001))
        image(state.copy(distanceMeters = 131070, nextRoad = "很长的道路名称".repeat(20),
            remainingMeters = 9999000, remainingMillis = 65534000))
    }

    @Test fun startRouteHasItsOwnLocalSymbolAndDoesNotInventAnOemStraightArrow() {
        val value = state.copy(maneuver = ManeuverKind.START_ROUTE)
        val start = image(value)
        val straight = image(value.copy(maneuver = ManeuverKind.STRAIGHT))
        assertFalse(start.sameAs(straight))
        assertTrue(pixels(start, 15, 65, Color.WHITE) > 100)
        assertEquals(0, MzdHudMapping.icon(value))
        for (y in 66 until 178) for (x in 0 until 178)
            assertEquals(straight.getPixel(x, y), start.getPixel(x, y))
    }

    @Test fun everySpecialSymbolFitsItsColumnAndLeavesRoadLanesAndRemainingValuesUntouched() {
        val folder = File("build/reports/hud-tbt/symbols").apply { mkdirs() }
        val base = state.copy(distanceMeters = null, roundaboutExit = 19,
            laneGuidance = NavigationLaneGuidance((0 until 12).map { NavigationLane(it, listOf(0), it < 2) }, 0))
        val blank = image(base.copy(maneuver = ManeuverKind.UNKNOWN))
        val frames = ManeuverKind.entries.associateWith { image(base.copy(maneuver = it)) }
        frames.forEach { (kind, frame) ->
            if (kind != ManeuverKind.UNKNOWN) assertTrue("$kind is empty", pixels(frame, 0, 66, Color.WHITE) > 20)
            for (y in 0 until 178) for (x in 0 until 178) if (y >= 66 || x !in 8 until 55)
                assertEquals("$kind escaped icon column at $x,$y", blank.getPixel(x, y), frame.getPixel(x, y))
            File(folder, "${kind.name}.png").outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        for ((special, ordinary) in listOf(
            ManeuverKind.KEEP_LEFT to ManeuverKind.SLIGHT_LEFT,
            ManeuverKind.OFF_RAMP_LEFT to ManeuverKind.SLIGHT_LEFT,
            ManeuverKind.CHANGE_HIGHWAY_LEFT to ManeuverKind.SLIGHT_LEFT,
            ManeuverKind.START_ROUTE_U_TURN_LEFT to ManeuverKind.U_TURN_LEFT,
            ManeuverKind.ROUNDABOUT_U_TURN to ManeuverKind.U_TURN_LEFT,
            ManeuverKind.AT_END_LEFT to ManeuverKind.LEFT,
            ManeuverKind.DESTINATION_LEFT to ManeuverKind.DESTINATION,
            ManeuverKind.FERRY_ENTER to ManeuverKind.FERRY_EXIT,
            ManeuverKind.ON_RAMP to ManeuverKind.STRAIGHT,
        )) assertFalse("$special collapsed into $ordinary", frames.getValue(special).sameAs(frames.getValue(ordinary)))
        val rightTraffic = image(base.copy(maneuver = ManeuverKind.ROUNDABOUT_NUMBERED, drivingSide = DrivingSide.RIGHT))
        val leftTraffic = image(base.copy(maneuver = ManeuverKind.ROUNDABOUT_NUMBERED, drivingSide = DrivingSide.LEFT))
        for (side in DrivingSide.entries) for (kind in listOf(ManeuverKind.ROUNDABOUT_ENTER,
            ManeuverKind.ROUNDABOUT_EXIT, ManeuverKind.ROUNDABOUT_NUMBERED, ManeuverKind.ROUNDABOUT_U_TURN)) {
            File(folder, "${kind.name}-${side.name}.png").outputStream().use {
                image(base.copy(maneuver = kind, drivingSide = side)).compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        assertFalse(rightTraffic.sameAs(leftTraffic))
        // Mirroring circulation must not mirror the exit number in the centre.
        for (y in 34 until 45) for (x in 24 until 39)
            assertEquals(rightTraffic.getPixel(x, y), leftTraffic.getPixel(x, y))
    }

    @Test fun maneuverSymbolsHaveNoDimDetachedContextLines() {
        for (kind in ManeuverKind.entries) {
            // Factory textures include their original antialiasing; their exact pixels are checked separately.
            if (MzdHudMapping.icon(state.copy(maneuver = kind)) != 0) continue
            val frame = image(state.copy(maneuver = kind, distanceMeters = null))
            val factoryPart = Bitmap.createBitmap(178, 178, Bitmap.Config.ARGB_8888)
            Canvas(factoryPart).apply {
                drawColor(Color.BLACK); translate(8f, 18f); scale(.85f, .85f)
                val id = when (kind) {
                    ManeuverKind.DESTINATION_LEFT, ManeuverKind.DESTINATION_RIGHT -> {
                        if (kind == ManeuverKind.DESTINATION_RIGHT) { translate(54f, 0f); scale(-1f, 1f) }
                        translate(8f, 5f); scale(.65f, .65f); translate(-11f, -6f)
                        3
                    }
                    ManeuverKind.FERRY_ENTER, ManeuverKind.FERRY_EXIT, ManeuverKind.FERRY_CHANGE -> {
                        translate(0f, 5f); scale(.78f, .78f)
                        69
                    }
                    else -> null
                }
                if (id != null) RuntimeEnvironment.getApplication().assets.open("hud-icons/tbt/$id.png").use {
                    drawBitmap(BitmapFactory.decodeStream(it)!!, 0f, 0f,
                        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                }
            }
            for (y in 0 until 66) for (x in 0 until 56) {
                if (Color.red(frame.getPixel(x, y)) <= 24) continue
                // Preserve original flag/boat edge pixels, while still checking every added stroke.
                if (frame.getPixel(x, y) == factoryPart.getPixel(x, y)) continue
                val nearWhite = ((y - 2).coerceAtLeast(0)..(y + 2).coerceAtMost(65)).any { row ->
                    ((x - 2).coerceAtLeast(0)..(x + 2).coerceAtMost(55)).any { col ->
                        Color.red(frame.getPixel(col, row)) >= 220
                    }
                }
                assertTrue("$kind: dim context line detached from the white glyph at $x,$y", nearWhite)
            }
        }
    }

    @Test fun availableOemSymbolsMatchTheFactoryImagesForBothTrafficSides() {
        val common = listOf(ManeuverKind.DESTINATION to 3, ManeuverKind.STRAIGHT to 16,
            ManeuverKind.SLIGHT_RIGHT to 17, ManeuverKind.SLIGHT_LEFT to 18,
            ManeuverKind.LEFT to 19, ManeuverKind.RIGHT to 20,
            ManeuverKind.SHARP_LEFT to 21, ManeuverKind.SHARP_RIGHT to 22,
            ManeuverKind.U_TURN_LEFT to 23, ManeuverKind.U_TURN_RIGHT to 24)
            .map { (kind, id) -> state.copy(maneuver = kind, distanceMeters = null) to id }
        val cases = common + listOf(DrivingSide.RIGHT, DrivingSide.LEFT).flatMap { side ->
            listOf(state.copy(maneuver = ManeuverKind.ROUNDABOUT_ENTER, drivingSide = side,
                distanceMeters = null) to if (side == DrivingSide.RIGHT) 53 else 70,
                state.copy(maneuver = ManeuverKind.ROUNDABOUT_EXIT, drivingSide = side,
                    distanceMeters = null) to if (side == DrivingSide.RIGHT) 63 else 68,
                state.copy(maneuver = ManeuverKind.ROUNDABOUT_U_TURN, drivingSide = side,
                    distanceMeters = null) to if (side == DrivingSide.RIGHT) 62 else 67) +
                (1..10).map { exit -> state.copy(maneuver = ManeuverKind.ROUNDABOUT_NUMBERED,
                    drivingSide = side, roundaboutExit = exit, distanceMeters = null) to
                    (if (side == DrivingSide.RIGHT) 41 else 73) + exit }
        }
        val folder = File("build/reports/hud-tbt/oem-comparison").apply { mkdirs() }
        val index = mutableListOf<String>()
        for ((value, id) in cases) {
            val expected = Bitmap.createBitmap(178, 178, Bitmap.Config.ARGB_8888)
            Canvas(expected).apply {
                drawColor(Color.BLACK)
                translate(8f, 18f); scale(.85f, .85f)
                val icon = RuntimeEnvironment.getApplication().assets.open("hud-icons/tbt/$id.png").use {
                    BitmapFactory.decodeStream(it)!!
                }
                drawBitmap(icon, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            }
            val actual = image(value)
            val name = "${value.maneuver}-${value.drivingSide}-$id"
            File(folder, "$name.png").outputStream().use { actual.compress(Bitmap.CompressFormat.PNG, 100, it) }
            index += "$name\t$id"
            for (y in 0 until 66) for (x in 0 until 178)
                assertEquals("$name differs from factory image at $x,$y", expected.getPixel(x, y), actual.getPixel(x, y))
        }
        File(folder, "cases.tsv").writeText(index.joinToString("\n") + "\n")
    }

    @Test fun roundaboutEntryAndExitRemainDistinctWithoutTrafficSideAndDirectionMarksDoNotCoverFerry() {
        val neutral = state.copy(distanceMeters = null, drivingSide = DrivingSide.UNKNOWN)
        val entering = image(neutral.copy(maneuver = ManeuverKind.ROUNDABOUT_ENTER))
        val leaving = image(neutral.copy(maneuver = ManeuverKind.ROUNDABOUT_EXIT))
        val turningBack = image(neutral.copy(maneuver = ManeuverKind.ROUNDABOUT_U_TURN))
        assertFalse(entering.sameAs(leaving))
        assertFalse(turningBack.sameAs(leaving))
        val ferries = listOf(ManeuverKind.FERRY_ENTER, ManeuverKind.FERRY_EXIT, ManeuverKind.FERRY_CHANGE).map {
            image(neutral.copy(maneuver = it))
        }
        val factoryBoat = Bitmap.createBitmap(178, 178, Bitmap.Config.ARGB_8888)
        Canvas(factoryBoat).apply {
            drawColor(Color.BLACK); translate(8f, 18f); scale(.85f, .85f)
            translate(0f, 5f); scale(.78f, .78f)
            RuntimeEnvironment.getApplication().assets.open("hud-icons/tbt/69.png").use {
                drawBitmap(BitmapFactory.decodeStream(it)!!, 0f, 0f,
                    Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            }
        }
        // Direction marks stay in the right column; the same boat remains legible in all variants.
        for (frame in ferries) for (y in 18 until 62) for (x in 8 until 40)
            assertEquals("ferry direction overlaps the factory hull at $x,$y", factoryBoat.getPixel(x, y), frame.getPixel(x, y))
    }

    @Test fun arrowsRemainConnectedCenteredAndStableAcrossManeuverSwitches() {
        val renderer = MzdHudTbtRenderer(RuntimeEnvironment.getApplication())
        val connected = setOf(ManeuverKind.STRAIGHT, ManeuverKind.LEFT, ManeuverKind.RIGHT,
            ManeuverKind.SLIGHT_LEFT, ManeuverKind.SLIGHT_RIGHT, ManeuverKind.SHARP_LEFT, ManeuverKind.SHARP_RIGHT,
            ManeuverKind.KEEP_LEFT, ManeuverKind.KEEP_RIGHT, ManeuverKind.OFF_RAMP_LEFT, ManeuverKind.OFF_RAMP_RIGHT,
            ManeuverKind.CHANGE_HIGHWAY_LEFT, ManeuverKind.CHANGE_HIGHWAY_RIGHT, ManeuverKind.FOLLOW_ROAD,
            ManeuverKind.U_TURN_LEFT, ManeuverKind.U_TURN_RIGHT, ManeuverKind.AT_END_LEFT, ManeuverKind.AT_END_RIGHT)
        for (kind in ManeuverKind.entries) {
            val value = state.copy(maneuver = kind, distanceMeters = null, roundaboutExit = 19)
            val frame = Bitmap.createBitmap(178, 178, Bitmap.Config.ARGB_8888)
            renderer.draw(Canvas(frame), value)
            assertTrue("$kind depends on the previous paint state", image(value).sameAs(frame))
            if (kind !in connected) continue
            val points = (0 until 66).flatMap { y -> (0 until 56).map { x -> y * 178 + x } }
                .filter { Color.red(frame.getPixel(it % 178, it / 178)) >= 64 }.toMutableSet()
            assertTrue(points.isNotEmpty())
            val left = points.minOf { it % 178 }; val right = points.maxOf { it % 178 }
            assertTrue("$kind is off centre: bounds $left..$right", kotlin.math.abs((left + right) / 2f - 31f) <= 2f)
            val queue = ArrayDeque<Int>().apply { add(points.first()) }
            points.remove(queue.first())
            while (queue.isNotEmpty()) {
                val point = queue.removeFirst()
                for (dy in -1..1) for (dx in -1..1) {
                    val neighbour = point + dy * 178 + dx
                    if (points.remove(neighbour)) queue.add(neighbour)
                }
            }
            assertTrue("$kind has a detached arrow fragment", points.isEmpty())
        }
    }

    @Test fun firstTwoEndRoadIconsHaveNoDetachedBarOrProtrudingLine() {
        fun bounds(frame: Bitmap): android.graphics.Rect {
            val points = (0 until 66).flatMap { y -> (0 until 56).map { x -> x to y } }
                .filter { (x, y) -> Color.red(frame.getPixel(x, y)) >= 64 }
            return android.graphics.Rect(points.minOf { it.first }, points.minOf { it.second },
                points.maxOf { it.first } + 1, points.maxOf { it.second } + 1)
        }
        val left = image(state.copy(maneuver = ManeuverKind.AT_END_LEFT, distanceMeters = null))
        val right = image(state.copy(maneuver = ManeuverKind.AT_END_RIGHT, distanceMeters = null))
        assertEquals(bounds(image(state.copy(maneuver = ManeuverKind.LEFT, distanceMeters = null))), bounds(left))
        assertEquals(bounds(image(state.copy(maneuver = ManeuverKind.RIGHT, distanceMeters = null))), bounds(right))
        // 54 design pixels scaled by 0.85 around x=30.95: mirrored raster edges may differ by one pixel.
        for (y in 0 until 66) for (x in 8 until 54) {
            if (Color.red(left.getPixel(x, y)) < 220) continue
            assertTrue("end-road pair is not mirrored at $x,$y", ((60 - x)..(62 - x)).any { col ->
                col in 0 until 56 && Color.red(right.getPixel(col, y)) >= 64
            })
        }
    }

    @Test fun laneRowIsVerticallyCenteredBetweenRoadAndRemainingValues() {
        for (count in listOf(2, 4, 8, 12)) for (value in listOf(state, state.copy(
            nextRoad = "很长的道路名称用于检查间距", remainingMeters = 9999000, remainingMillis = 65534000))) {
            val image = image(value.copy(laneGuidance = NavigationLaneGuidance((0 until count).map {
                NavigationLane(it, listOf(if (it == 0) -90 else 0), it == 0)
            }, 0)))
            val roadBottom = (70 until 99).last { pixels(image, it, it + 1) > 0 }
            val laneTop = (99 until 141).first { pixels(image, it, it + 1) > 0 }
            val laneBottom = (99 until 141).last { pixels(image, it, it + 1) > 0 }
            val remainingTop = (141 until 178).first { pixels(image, it, it + 1) > 0 }
            val above = laneTop - roadBottom - 1
            val below = remainingTop - laneBottom - 1
            assertTrue("$count lanes: gaps above=$above below=$below", kotlin.math.abs(above - below) <= 2)
        }
    }

    @Test fun shortRoadNamesRemainStationaryWithoutRequestingAnimation() {
        var now = 0L
        val renderer = MzdHudTbtRenderer(RuntimeEnvironment.getApplication()) { now }
        fun frame() = Bitmap.createBitmap(178, 178, Bitmap.Config.ARGB_8888).also {
            assertFalse(renderer.draw(Canvas(it), state))
        }
        val first = frame()
        now = 5000
        assertTrue(first.sameAs(frame()))
    }

    @Test fun longRoadScrollsAfterReadingPauseWithoutMovingOtherRowsOrRestartingOnProgress() {
        var now = 0L
        val renderer = MzdHudTbtRenderer(RuntimeEnvironment.getApplication()) { now }
        val value = state.copy(nextRoad = "Century Avenue / Pudong South Road (Shanghai)",
            laneGuidance = NavigationLaneGuidance((0..3).map { NavigationLane(it, listOf(0), it == 0) }, 0))
        fun frame(snapshot: NavigationState = value) = Bitmap.createBitmap(178, 178, Bitmap.Config.ARGB_8888).also {
            assertTrue(renderer.draw(Canvas(it), snapshot))
        }
        val first = frame()
        now = 1000
        assertTrue(first.sameAs(frame()))
        now = 3000
        val scrolling = frame()
        assertFalse(first.sameAs(scrolling))
        for (y in 0 until 178) for (x in 0 until 178) {
            if (y !in 66 until 98 || x !in 8 until 170)
                assertEquals("scrolling moved another row or escaped its clip at $x,$y",
                    first.getPixel(x, y), scrolling.getPixel(x, y))
        }
        val updated = frame(value.copy(distanceMeters = 250, receivedAtMillis = 4000))
        for (y in 66 until 98) for (x in 0 until 178)
            assertEquals(scrolling.getPixel(x, y), updated.getPixel(x, y))
    }

    @Test fun roadChangesAndNavigationEndResetScrolling() {
        var now = 0L
        val renderer = MzdHudTbtRenderer(RuntimeEnvironment.getApplication()) { now }
        val value = state.copy(nextRoad = "Century Avenue / Pudong South Road (Shanghai)")
        fun frame(value: NavigationState?, animated: Boolean) = Bitmap.createBitmap(178, 178, Bitmap.Config.ARGB_8888).also {
            assertEquals(animated, renderer.draw(Canvas(it), value))
        }
        val first = frame(value, true)
        now = 4000
        assertFalse(first.sameAs(frame(value, true)))
        val changed = value.copy(nextRoad = "Pudong South Road / Century Avenue (Shanghai)")
        assertTrue(image(changed).sameAs(frame(changed, true)))
        assertTrue(first.sameAs(frame(value, true)))
        now = 8000
        frame(value, true)
        assertEquals(0, pixels(frame(value.copy(phase = NavigationPhase.ENDED), false), 0, 178))
        assertTrue(first.sameAs(frame(value, true)))
        assertEquals(0, pixels(frame(null, false), 0, 178))
        frame(state, false)
        frame(value.copy(nextRoad = null), false)
    }

    @Test fun saveRoadMarqueeFrames() {
        val folder = File("build/reports/hud-tbt/marquee").apply { mkdirs() }
        var now = 0L
        val renderer = MzdHudTbtRenderer(RuntimeEnvironment.getApplication()) { now }
        val value = state.copy(nextRoad = "上海市浦东新区世纪大道（陆家嘴方向）",
            laneGuidance = NavigationLaneGuidance((0..3).map {
                NavigationLane(it, if (it == 0) listOf(-90) else listOf(0, 90), it == 0, if (it == 0) -90 else null)
            }, 0))
        for (index in 0..104) {
            now = index * 125L
            val frame = Bitmap.createBitmap(178, 178, Bitmap.Config.ARGB_8888)
            assertTrue(renderer.draw(Canvas(frame), value))
            File(folder, "%03d.png".format(index)).outputStream().use {
                frame.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    @Test fun physicalDisplayProfileRejectsMainRotatedUnknownAndThreeDimensionalDisplays() {
        for (name in listOf("MeterDisplay#0", "HDMI 屏幕")) {
            for (type in listOf(null, 2)) {
                assertTrue(MzdHudProfile.accepts(name, 800, 480, 0, 7, true, hudType = type))
                assertFalse(MzdHudProfile.accepts(name, 800, 480, 0, 0, true, hudType = type))
                assertFalse(MzdHudProfile.accepts(name, 800, 400, 0, 7, true, hudType = type))
                assertFalse(MzdHudProfile.accepts(name, 4032, 845, 0, 7, true, hudType = type))
                assertFalse(MzdHudProfile.accepts(name, 800, 480, Surface.ROTATION_90, 7, true, hudType = type))
                assertFalse(MzdHudProfile.accepts(name, 800, 480, 0, 7, false, hudType = type))
            }
            for (type in listOf(0, 1, 3)) {
                assertFalse(MzdHudProfile.accepts(name, 800, 480, 0, 7, true, hudType = type))
            }
        }
        assertFalse(MzdHudProfile.accepts("other", 800, 480, 0, 7, true))
    }

    @Test fun ordinaryHudWindowDoesNotDependOnModelOrDisplayName() {
        val originalModel = Build.MODEL
        val context = RuntimeEnvironment.getApplication()
        val id = ShadowDisplayManager.addDisplay("w800dp-h480dp-mdpi", 5)
        val other = ShadowDisplayManager.addDisplay("w4032dp-h845dp-mdpi", 5)
        val manager = context.getSystemService(DisplayManager::class.java)
        shadowOf(manager.getDisplay(other)).apply {
            setName("HDMI 屏幕"); setFlags(Display.FLAG_PRESENTATION); setRotation(0)
        }
        var type: Int? = 2
        val output = MzdHudTbtOutput(context, {}, hudType = { type })
        fun idle() = shadowOf(Looper.getMainLooper()).idle()
        fun window(): Presentation? = ReflectionHelpers.getField(output, "presentation")
        try {
            for (name in listOf("MeterDisplay#0", "HDMI 屏幕")) {
                shadowOf(manager.getDisplay(id)).apply {
                    setName(name); setFlags(Display.FLAG_PRESENTATION); setRotation(0)
                }
                ShadowBuild.setModel("head-unit-a"); type = 2
                output.submit(state); idle(); assertEquals(id, window()!!.display.displayId)
                ShadowBuild.setModel("head-unit-b")
                output.submit(state.copy(distanceMeters = 300)); idle(); assertNotNull(window())
                type = 3
                output.submit(state.copy(distanceMeters = 250)); idle(); assertNull(window())
                type = null
                output.submit(state.copy(distanceMeters = 200)); idle(); assertNotNull(window())
                type = 2
                output.submit(state); idle(); assertNotNull(window())
                output.submit(null); idle(); assertNull(window())
            }
        } finally {
            output.submit(null); idle(); ShadowDisplayManager.removeDisplay(id)
            ShadowDisplayManager.removeDisplay(other)
            ShadowBuild.setModel(originalModel)
        }
    }

    @Test fun windowIsLocalConfinedAndClearsWithoutLateSubmitResurrectingIt() {
        val context = RuntimeEnvironment.getApplication()
        val id = ShadowDisplayManager.addDisplay("w800dp-h480dp-mdpi", 5)
        val manager = context.getSystemService(DisplayManager::class.java)
        shadowOf(manager.getDisplay(id)).apply {
            setName("MeterDisplay#0"); setFlags(Display.FLAG_PRESENTATION); setRotation(0)
        }
        val output = MzdHudTbtOutput(context, {})
        fun idle() = shadowOf(Looper.getMainLooper()).idle()
        fun window(): Presentation? = ReflectionHelpers.getField(output, "presentation")
        try {
            output.submit(state); output.submit(null); idle()
            assertNull(window())
            output.submit(state); idle()
            val root = window()!!.findViewById<FrameLayout>(android.R.id.content).getChildAt(0) as FrameLayout
            assertEquals(1, root.childCount)
            val params = root.getChildAt(0).layoutParams as FrameLayout.LayoutParams
            assertEquals(listOf(584, 179, 178, 178), listOf(params.leftMargin, params.topMargin, params.width, params.height))
            val duplicate = ShadowDisplayManager.addDisplay("w800dp-h480dp-mdpi", 5)
            shadowOf(manager.getDisplay(duplicate)).apply {
                setName("HDMI 屏幕"); setFlags(Display.FLAG_PRESENTATION); setRotation(0)
            }
            idle(); assertNull(window())
            ShadowDisplayManager.removeDisplay(duplicate); idle(); assertNotNull(window())
            output.submit(null); idle(); assertNull(window())
            assertFalse(ReflectionHelpers.getField(output, "listening"))
        } finally { output.submit(null); idle(); ShadowDisplayManager.removeDisplay(id) }
    }

    @Test fun saveReviewedLayoutCases() {
        val folder = File("build/reports/hud-tbt").apply { mkdirs() }
        val variants = mapOf(
            "no-lanes" to state,
            "start-route" to state.copy(maneuver = ManeuverKind.START_ROUTE, distanceMeters = 3000,
                nextRoad = "请行驶到西南门", remainingMeters = 19100, remainingMillis = 1740000),
            "oem-font-weight" to state.copy(maneuver = ManeuverKind.START_ROUTE, distanceMeters = 323,
                nextRoad = "请行驶到西南门", remainingMeters = 7900, remainingMillis = 1440000),
            "four-lanes" to state.copy(laneGuidance = NavigationLaneGuidance((0..3).map {
                NavigationLane(it, if (it == 0) listOf(-90) else listOf(0, 90), it == 0, if (it == 0) -90 else null)
            }, 0)),
            "eight-lanes" to state.copy(laneGuidance = NavigationLaneGuidance((0..7).map {
                NavigationLane(it, listOf(if (it < 2) -90 else 0), it < 2)
            }, 0)),
            "long-values" to state.copy(distanceMeters = 131070, remainingMeters = 9999000,
                remainingMillis = 65534000, nextRoad = "很长的道路名称用于检查边界"),
            "mixed-road" to state.copy(nextRoad = "G1503 上海绕城高速（南向）"),
            "hour-minutes" to state.copy(remainingMillis = 3660000),
            "roundabout" to state.copy(maneuver = ManeuverKind.ROUNDABOUT_NUMBERED, roundaboutExit = 3),
        ) + (9..12).associate { count ->
            "$count-lanes" to state.copy(laneGuidance = NavigationLaneGuidance((0 until count).map {
                NavigationLane(it, listOf(if (it < 2) -90 else if (it == count - 1) 90 else 0), it < 2)
            }, 0))
        }
        variants.forEach { (name, value) -> File(folder, "$name.png").outputStream().use {
            image(value).compress(Bitmap.CompressFormat.PNG, 100, it)
        } }
    }
}
