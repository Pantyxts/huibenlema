package com.huibenlema.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 每日每书价值明细（柱状图点选展示：某天读了哪些书、各贡献多少价值）。
 */
@Entity(
    tableName = "daily_book_stats",
    indices = [Index(value = ["date", "bookId"], unique = true)]
)
data class DailyBookStatEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,   // yyyy-MM-dd
    val bookId: String,
    val valueFen: Long
)
