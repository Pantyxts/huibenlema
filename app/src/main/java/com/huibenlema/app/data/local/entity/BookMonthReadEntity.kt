package com.huibenlema.app.data.local.entity

import androidx.room.Entity

/**
 * 每书每月阅读秒数（官方 readdata monthly 的 readLongest，月榜 top10）。
 * 用于分辨"统计起始日之后读了哪些书、各读了多少"，价值按时长比例折算。
 */
@Entity(tableName = "book_month_read", primaryKeys = ["bookId", "month"])
data class BookMonthReadEntity(
    val bookId: String,
    /** yyyy-MM */
    val month: String,
    val readSeconds: Long
)
