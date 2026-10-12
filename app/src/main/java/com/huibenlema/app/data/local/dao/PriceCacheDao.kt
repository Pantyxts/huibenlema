package com.huibenlema.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.huibenlema.app.data.local.entity.PriceCacheEntity

@Dao
interface PriceCacheDao {

    @Query("SELECT * FROM price_cache WHERE cacheKey = :key AND expiresAt > :now")
    suspend fun getValid(key: String, now: Long): PriceCacheEntity?

    @Upsert
    suspend fun upsert(cache: PriceCacheEntity)

    @Query("DELETE FROM price_cache WHERE expiresAt <= :now")
    suspend fun deleteExpired(now: Long)

    /** 升级时清理历史“失败负缓存”：旧版本无法区分接口失败与确认无价 */
    @Query("DELETE FROM price_cache WHERE priceFen <= 0")
    suspend fun deleteZeroPrices()
}
