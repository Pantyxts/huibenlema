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
}
