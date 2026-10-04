package com.huibenlema.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.huibenlema.app.domain.model.PriceSource

/**
 * 定价缓存。cacheKey = bookId 或 "isbn:{isbn}"。
 * 价格 0 也是有效结果（免费书），TTL：免费书 7 天、付费书 24 小时。
 */
@Entity(tableName = "price_cache")
data class PriceCacheEntity(
    @PrimaryKey val cacheKey: String,
    val priceFen: Long,
    val source: PriceSource,
    val fetchedAt: Long,
    val expiresAt: Long
)
