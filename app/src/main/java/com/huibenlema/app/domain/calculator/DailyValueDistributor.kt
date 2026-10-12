package com.huibenlema.app.domain.calculator

import java.time.LocalDate

/** 一段待分布的价值增量。(fromDate, endDate] 内按阅读秒数加权。 */
data class DailyValueDelta(
    val bookId: String,
    val valueFen: Long,
    val fromDate: LocalDate,
    /** 该价值段对应的快照日；无法定位的整段与余数归到这一天。 */
    val endDate: LocalDate
)

/** 每日价值分布纯函数，供同步重建与单元测试共用。 */
object DailyValueDistributor {

    /** 频繁同步段按天摊薄的上限；超过后需明确选择是否按秒数回溯。 */
    const val SHORT_SEGMENT_DAYS = 7

    /** 稀疏/首段历史价值按账号级阅读秒数回溯的最近天数（图表可见范围）。 */
    const val SPREAD_DAYS = 30

    /**
     * 是否需要把长段按阅读秒数摊薄：
     * - 历史快照 ≤1 行时无法定位进度段；
     * - 首个正向 Δ 段产生于最早快照之前，也没有快照能定位。
     */
    fun shouldSpreadBySeconds(snapshotCount: Int, isFirstValue: Boolean): Boolean =
        snapshotCount <= 1 || isFirstValue

    /**
     * 把一本书的 Δ价值分布到 (fromDate, endDate]：
     * - 区间 ≤ 7 天：按每日阅读秒数加权分摊；
     * - 区间 > 7 天且 spreadBySeconds=false：整段归 endDate；
     * - 区间 > 7 天且 spreadBySeconds=true：最近 30 天按秒数加权，更早部分归 endDate；
     * - 无阅读秒数或整数除法余数：归 endDate，保证总价值不丢失。
     */
    fun distribute(
        value: DailyValueDelta,
        dailySecMap: Map<String, Long>,
        add: (LocalDate, Long, String) -> Unit,
        spreadBySeconds: Boolean = false
    ) {
        val days = buildList {
            var day = value.fromDate
            while (!day.isAfter(value.endDate)) {
                add(day)
                day = day.plusDays(1)
            }
        }
        if (days.size > SHORT_SEGMENT_DAYS && !spreadBySeconds) {
            add(value.endDate, value.valueFen, value.bookId)
            return
        }

        val weightedDays = if (days.size > SHORT_SEGMENT_DAYS) days.takeLast(SPREAD_DAYS) else days
        val totalSec = weightedDays.sumOf { dailySecMap[it.toString()] ?: 0L }
        if (totalSec <= 0) {
            add(value.endDate, value.valueFen, value.bookId)
            return
        }

        var allocated = 0L
        for (day in weightedDays) {
            val sec = dailySecMap[day.toString()] ?: 0L
            if (sec <= 0) continue
            val part = value.valueFen * sec / totalSec
            if (part > 0) {
                add(day, part, value.bookId)
                allocated += part
            }
        }
        val remainder = value.valueFen - allocated
        if (remainder > 0) add(value.endDate, remainder, value.bookId)
    }
}
