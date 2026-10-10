package com.huibenlema.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.huibenlema.app.data.local.entity.DailyStatEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DailyStatDao {

    @Query("SELECT * FROM daily_stats WHERE date = :date")
    suspend fun get(date: String): DailyStatEntity?

    /** 全量导出用 */
    @Query("SELECT * FROM daily_stats ORDER BY date ASC")
    suspend fun getAllOnce(): List<DailyStatEntity>

    @Upsert
    suspend fun upsert(stat: DailyStatEntity)

    /** 导入备份用 */
    @Upsert
    suspend fun upsertAll(stats: List<DailyStatEntity>)

    /** 导入备份前清空每日统计 */
    @Query("DELETE FROM daily_stats")
    suspend fun deleteAll()

    /**
     * 最近 N 天（倒序取最新 N 条，调用方按需反转）。
     * 不传起始日期：避免查询窗口在 Flow 创建时冻结、跨零点后最新一天被 LIMIT 截断。
     */
    @Query("SELECT * FROM daily_stats ORDER BY date DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<DailyStatEntity>>

    /** 该月是否已有阅读时长数据（历史回溯去重用），prefix 形如 yyyy-MM */
    @Query("SELECT COUNT(*) FROM daily_stats WHERE date LIKE :prefix || '%' AND readSeconds > 0")
    suspend fun hasReadSecondsInMonth(prefix: String): Int

    /** 该年是否已有阅读时长数据（年粒度预取去重用），prefix 形如 yyyy */
    @Query("SELECT COUNT(*) FROM daily_stats WHERE date LIKE :prefix || '%' AND readSeconds > 0")
    suspend fun hasReadSecondsInYear(prefix: String): Int

    /** 清空每日价值（保留阅读时长），本地重建前调用 */
    @Query("UPDATE daily_stats SET valueFen = 0, bookCount = 0")
    suspend fun resetValues()
}
