package com.huibenlema.app.data.local.entity

import androidx.room.Entity

/**
 * 单书进度快照（每次同步记录，用于计算 Δ进度 与回本趋势）。
 * 复合主键 (bookId, date)：同一天多次同步只保留最后一次快照（@Upsert 按主键覆盖）。
 */
@Entity(
    tableName = "read_history",
    primaryKeys = ["bookId", "date"]
)
data class ReadHistoryEntity(
    val bookId: String,
    /** yyyy-MM-dd（同步当天） */
    val date: String,
    val progress: Double,
    val readSecondsDelta: Long = 0
)
