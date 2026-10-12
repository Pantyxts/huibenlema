package com.huibenlema.app.domain.calculator

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyValueDistributorTest {

    @Test
    fun `仅有单条快照的长段按阅读秒数摊薄`() {
        assertTrue(DailyValueDistributor.shouldSpreadBySeconds(snapshotCount = 1, isFirstValue = true))

        val allocations = distribute(
            fromDate = LocalDate.of(2026, 9, 1),
            endDate = LocalDate.of(2026, 9, 30),
            valueFen = 1_001L,
            dailySecMap = mapOf(
                "2026-09-01" to 60L,
                "2026-09-02" to 40L
            )
        )

        assertEquals(600L, allocations.getValue(LocalDate.of(2026, 9, 1)))
        assertEquals(400L, allocations.getValue(LocalDate.of(2026, 9, 2)))
        assertEquals(1L, allocations.getValue(LocalDate.of(2026, 9, 30)))
        assertEquals(1_001L, allocations.values.sum())
    }

    @Test
    fun `首个历史价值段即使已有后续快照也必须摊薄`() {
        // 回归：第二条快照出现后，最早快照前的首段不能改成全额归最早快照日。
        assertTrue(DailyValueDistributor.shouldSpreadBySeconds(snapshotCount = 2, isFirstValue = true))
    }

    @Test
    fun `后续长段有多条快照时不摊薄到其他日期`() {
        assertFalse(DailyValueDistributor.shouldSpreadBySeconds(snapshotCount = 2, isFirstValue = false))

        val endDate = LocalDate.of(2026, 9, 30)
        val allocations = distribute(
            fromDate = LocalDate.of(2026, 9, 1),
            endDate = endDate,
            valueFen = 1_001L,
            dailySecMap = mapOf("2026-09-01" to 60L, "2026-09-02" to 40L),
            spreadBySeconds = false
        )

        assertEquals(mapOf(endDate to 1_001L), allocations)
    }

    @Test
    fun `短段按秒数加权且整数除法余数归快照日`() {
        val allocations = distribute(
            fromDate = LocalDate.of(2026, 9, 1),
            endDate = LocalDate.of(2026, 9, 2),
            valueFen = 100L,
            dailySecMap = mapOf("2026-09-01" to 1L, "2026-09-02" to 2L)
        )

        assertEquals(33L, allocations.getValue(LocalDate.of(2026, 9, 1)))
        assertEquals(67L, allocations.getValue(LocalDate.of(2026, 9, 2)))
        assertEquals(100L, allocations.values.sum())
    }

    @Test
    fun `无阅读秒数时整段归快照日且总价值不丢失`() {
        val endDate = LocalDate.of(2026, 9, 8)
        val allocations = distribute(
            fromDate = LocalDate.of(2026, 9, 1),
            endDate = endDate,
            valueFen = 999L,
            dailySecMap = emptyMap()
        )

        assertEquals(mapOf(endDate to 999L), allocations)
    }

    private fun distribute(
        fromDate: LocalDate,
        endDate: LocalDate,
        valueFen: Long,
        dailySecMap: Map<String, Long>,
        spreadBySeconds: Boolean = true
    ): Map<LocalDate, Long> {
        val result = mutableMapOf<LocalDate, Long>()
        DailyValueDistributor.distribute(
            value = DailyValueDelta(
                bookId = "book",
                valueFen = valueFen,
                fromDate = fromDate,
                endDate = endDate
            ),
            dailySecMap = dailySecMap,
            add = { date, value, _ -> result[date] = (result[date] ?: 0L) + value },
            spreadBySeconds = spreadBySeconds
        )
        return result
    }
}
