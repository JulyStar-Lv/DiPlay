package com.shilapi.vehicle.mzd

import com.shilapi.display.api.NavigationLane
import com.shilapi.display.api.NavigationLaneGuidance
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class MzdLaneMappingTest {
    private fun row(vararg lanes: NavigationLane) = NavigationLaneGuidance(lanes.toList(), 1000)
    private fun lane(index: Int, vararg angles: Int, recommended: Boolean = false, highlight: Int? = null) =
        NavigationLane(index, angles.toList(), recommended, highlight)

    @Test fun designLikeEightLaneRowKeepsBothRecommendedLeftLanesAndAllGreyNeighbours() {
        val source = row(lane(0, -90, recommended = true), lane(1, -90, recommended = true),
            lane(2, 0), lane(3, 0), lane(4, 0), lane(5, 0), lane(6, 0), lane(7, 90))
        assertEquals(listOf(16, 16, 0, 0, 0, 0, 0, 3), MzdLaneMapping.lanes(source, 1000))
        val json = JSONObject(MzdLaneMapping.json(MzdLaneMapping.lanes(source, 1000)))
        assertEquals(8, json.getInt("laneNum"))
        assertEquals(16, json.getInt("lane1")); assertEquals(3, json.getInt("lane8"))
    }

    @Test fun capturedAppleMapsSampleAddsHighlightedRightBranchToTheStraightLane() {
        // Upstream's real sample: [-90], [0], [0 + highlight 90, status 2].
        val source = row(lane(0, -90), lane(1, 0), lane(2, 0, recommended = true, highlight = 90))
        assertEquals(listOf(1, 0, 33), MzdLaneMapping.lanes(source, 1000))
    }

    @Test fun compoundBranchesKeepTheirGeometryAndOnlyTheChosenBranchIsWhite() {
        assertEquals(listOf(31, 32, 38, 44), MzdLaneMapping.lanes(row(
            lane(0, -90, 0, recommended = true, highlight = -90),
            lane(1, 0, 90, recommended = true, highlight = 0),
            lane(2, -90, 0, 90, recommended = true, highlight = 90),
            lane(3, -180, -90, recommended = true, highlight = -180)), 1000))
        // No chosen branch in a compound lane: keep both grey, never guess one white.
        assertEquals(listOf(2), MzdLaneMapping.lanes(row(lane(0, -90, 0, recommended = true)), 1000))
    }

    @Test fun sortingKeepsPhysicalLaneSlotsButGapsDuplicatesOverflowAndUnknownShapesHideWholeRow() {
        assertEquals(listOf(1, 18), MzdLaneMapping.lanes(row(lane(1, 90, recommended = true), lane(0, -90)), 1000))
        val invalid = listOf(row(lane(0, 0), lane(2, 90)), row(lane(0, 0), lane(0, 90)),
            row(lane(0, 0), lane(1, 45)), row(lane(0)), row(lane(0, -180, 180)),
            NavigationLaneGuidance((0..8).map { lane(it, 0) }, 1000))
        invalid.forEach { assertTrue(MzdLaneMapping.lanes(it, 1000).isEmpty()) }
    }

    @Test fun absenceEmptyExpiredAndFutureTimestampsExplicitlyClearAndValidDataHasBoundedLease() {
        val source = row(lane(0, 0, recommended = true))
        assertTrue(MzdLaneMapping.lanes(null, 1000).isEmpty())
        assertTrue(MzdLaneMapping.lanes(row(), 1000).isEmpty())
        assertEquals(listOf(15), MzdLaneMapping.lanes(source, 91_000))
        assertTrue(MzdLaneMapping.lanes(source, 91_001).isEmpty())
        assertTrue(MzdLaneMapping.lanes(source, 999).isEmpty())
        assertEquals(0, JSONObject(MzdLaneMapping.json(emptyList())).getInt("laneNum"))
    }
}
