package com.huibenlema.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 单书进度快照（每次同步记录，用于计算 Δ进度 与回本趋势）。
 */
@Entity(
    tableName = "read_history",
    indices = [Index(value = ["bookId", "date"], unique = true)]
)
data class ReadHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: String,
    /** yyyy-MM-dd（同步当天） */
    val date: String,
    val progress: Double,
    val readSecondsDelta: Long = 0
)
