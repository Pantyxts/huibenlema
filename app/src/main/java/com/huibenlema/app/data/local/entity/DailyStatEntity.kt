package com.huibenlema.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 每日阅读价值聚合（柱状图数据源）。
 * valueFen = Σ(Δ进度 × 定价)，同步时增量累加。
 */
@Entity(tableName = "daily_stats")
data class DailyStatEntity(
    @PrimaryKey val date: String,   // yyyy-MM-dd
    val valueFen: Long,
    val readSeconds: Long = 0,
    val bookCount: Int = 0
)
