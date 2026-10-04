package com.huibenlema.app.domain.calculator

import com.huibenlema.app.domain.model.Book
import com.huibenlema.app.domain.model.CostItem
import com.huibenlema.app.domain.model.PaybackSummary
import com.huibenlema.app.domain.model.PriceSource
import kotlin.math.ceil

/**
 * 回本计算引擎（纯函数，无 Android 依赖，可直接 JUnit 单测）。
 *
 * 口径（方案 v2，已确认）：
 * - 已读总价值 = Σ(进度 × 定价)，未读完按进度折算
 * - 总成本 = Σ 成本台账（设备/配件/会员费/其他）
 * - 回本进度 = 总价值 / 总成本 × 100%
 * - 预计还需读 N 本 = ceil(差额 / 平均书价)，仅差额<0 时展示
 */
object PaybackCalculator {

    /** 汇总计算 */
    fun compute(books: List<Book>, costs: List<CostItem>): PaybackSummary {
        val totalValueFen = books.sumOf { it.contributedFen }
        val totalCostFen = costs.sumOf { it.priceFen }
        val diffFen = totalValueFen - totalCostFen

        // 平均书价只统计定价>0 且有进度的书（免费书/未定价书不参与）
        val pricedRead = books.filter { it.priceFen > 0 && it.progress > 0 }
        val avgPriceFen = if (pricedRead.isEmpty()) 0L
        else pricedRead.sumOf { it.priceFen } / pricedRead.size

        val booksToGo = if (diffFen < 0 && avgPriceFen > 0) {
            ceil(-diffFen.toDouble() / avgPriceFen).toLong()
        } else 0L

        return PaybackSummary(
            totalValueFen = totalValueFen,
            totalCostFen = totalCostFen,
            diffFen = diffFen,
            ratio = if (totalCostFen > 0) totalValueFen.toDouble() / totalCostFen
                    else Double.POSITIVE_INFINITY,
            avgPriceFen = avgPriceFen,
            booksToGo = booksToGo,
            pricedBookCount = books.count { it.priceSource != PriceSource.NONE },
            unpricedBookCount = books.count { it.priceSource == PriceSource.NONE },
            totalBooks = books.size
        )
    }

    /**
     * 单次同步的每日价值增量（分）= Σ(Δ进度 × 定价)。
     * v1 归因到同步当天；阶段 2 用官方 readdata 按天分桶平滑。
     * Δ进度为负（如重读导致进度重置）时钳制为 0，避免价值倒扣；
     * Δ进度大于 1（理论上限保护）时钳制为 1。
     */
    fun dailyValueDelta(
        progressDeltaByBook: Map<String, Double>,
        booksById: Map<String, Book>
    ): Long = progressDeltaByBook.entries.sumOf { (bookId, delta) ->
        val book = booksById[bookId] ?: return@sumOf 0L
        (delta.coerceIn(0.0, 1.0) * book.priceFen).toLong()
    }
}
