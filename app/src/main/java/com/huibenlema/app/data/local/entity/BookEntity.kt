package com.huibenlema.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.huibenlema.app.domain.model.PriceSource

/** 书籍主表 */
@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val bookId: String,
    val title: String,
    val author: String = "",
    val translator: String = "",
    val publisher: String = "",
    val coverUrl: String = "",
    val isbn: String = "",
    val category: String = "",
    /** 定价（分）；0 = 免费或未知 */
    val priceFen: Long = 0L,
    val priceSource: PriceSource = PriceSource.NONE,
    val priceUpdatedAt: Long = 0L,
    /** 阅读进度 0.0 ~ 1.0 */
    val progress: Double = 0.0,
    val finished: Boolean = false,
    /** 累计阅读时长（秒） */
    val totalReadSeconds: Long = 0L,
    val lastReadAt: Long = 0L,
    /** 最近一次拉取进度的时间（增量同步：远端 readUpdateTime 超过它才重新拉） */
    val progressFetchedAt: Long = 0L,
    val onShelf: Boolean = true,
    /** 软删除（下架/移出书架） */
    val removed: Boolean = false,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)
