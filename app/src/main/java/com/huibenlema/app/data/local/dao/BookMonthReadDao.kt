package com.huibenlema.app.data.local.dao

import androidx.room.Dao
import androidx.room.Upsert
import com.huibenlema.app.data.local.entity.BookMonthReadEntity

/**
 * 每书每月阅读时长表（官方 readdata readLongest 月榜数据）。
 * 表结构保留（DB v8 已发布）；起始月统计功能已按用户决策移除，本表当前闲置。
 */
@Dao
interface BookMonthReadDao {

    @Upsert
    suspend fun upsertAll(rows: List<BookMonthReadEntity>)
}
