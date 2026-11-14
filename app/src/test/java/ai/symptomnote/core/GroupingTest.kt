package ai.symptomnote.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupingTest {
    @Test fun 시연_증상에_실제_기록은_막음() {
        assertEquals(Grouping.MIX_ERROR, Grouping.mixError(targetDemo = true, recordDemo = listOf(false, false)))
        assertEquals(Grouping.MIX_ERROR, Grouping.mixError(targetDemo = false, recordDemo = listOf(true)))
        assertEquals(Grouping.MIX_ERROR, Grouping.mixError(targetDemo = null, recordDemo = listOf(true, false))) // 새 증상에 섞어서
        assertNull(Grouping.mixError(true, listOf(true)))
        assertNull(Grouping.mixError(false, listOf(false, false)))
        assertNull(Grouping.mixError(null, listOf(false)))
    }

    @Test fun 수리_날짜가_있을_때만_묻는다() {
        assertTrue(Grouping.needsRepairChoice(1000L))
        assertFalse(Grouping.needsRepairChoice(null))
    }

    @Test fun 수리_후_판단은_고른_값이_우선() {
        val repaired = 10_000L
        assertTrue(Grouping.isAfterRepair(20_000, repaired, null))   // 시각으로: 수리 뒤
        assertFalse(Grouping.isAfterRepair(5_000, repaired, null))   // 시각으로: 수리 전
        assertFalse(Grouping.isAfterRepair(20_000, repaired, false)) // 수리 뒤 기록이지만 "수리 전 기록으로 추가"
        assertTrue(Grouping.isAfterRepair(5_000, repaired, true))
        assertFalse(Grouping.isAfterRepair(20_000, null, true))      // 수리 날짜가 없으면 항상 수리 전
    }
}
