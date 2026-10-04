package com.huibenlema.app.domain.model

/**
 * 回本进度汇总（计算引擎输出）。
 */
data class PaybackSummary(
    /** 已读总价值（分）= Σ(进度 × 定价) */
    val totalValueFen: Long,
    /** 总成本（分）= Σ 成本台账 */
    val totalCostFen: Long,
    /** 差额（分）= 总价值 - 总成本；负数表示还差多少 */
    val diffFen: Long,
    /** 回本进度 = 总价值 / 总成本；成本为 0 时为 +∞ */
    val ratio: Double,
    /** 平均书价（分），只统计定价>0 且有进度的书；无则为 0 */
    val avgPriceFen: Long,
    /** 预计还需读 N 本（仅差额<0 且平均书价>0 时有意义，否则 0） */
    val booksToGo: Long,
    val pricedBookCount: Int,
    val unpricedBookCount: Int,
    val totalBooks: Int
) {
    val hasCost: Boolean get() = totalCostFen > 0
}
