package com.huibenlema.app.domain.model

/** 某天某本书的价值明细 */
data class BookDayStat(
    val bookId: String,
    val title: String,
    val valueFen: Long
)
