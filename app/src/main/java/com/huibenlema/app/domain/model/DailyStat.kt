package com.huibenlema.app.domain.model

/**
 * 每日阅读价值统计（柱状图数据源）。
 */
data class DailyStat(
    /** yyyy-MM-dd */
    val date: String,
    /** 当日阅读价值（分）= Σ(Δ进度 × 定价) */
    val valueFen: Long,
    /** 当日阅读时长（秒，若数据源提供；官方 readdata 按天分桶可得） */
    val readSeconds: Long,
    /** 当日有进度增量的书籍数 */
    val bookCount: Int
)
