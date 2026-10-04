package com.huibenlema.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.huibenlema.app.data.local.entity.DailyBookStatEntity

/** 每日每书价值查询结果（LEFT JOIN books 带书名） */
data class DailyBookWithTitle(
    val bookId: String,
    val valueFen: Long,
    val title: String?
)

@Dao
interface DailyBookStatDao {

    @Upsert
    suspend fun upsertAll(rows: List<DailyBookStatEntity>)

    @Query("DELETE FROM daily_book_stats")
    suspend fun deleteAll()

    @Query("SELECT valueFen FROM daily_book_stats WHERE date = :date AND bookId = :bookId")
    suspend fun getValue(date: String, bookId: String): Long?

    @Query(
        "SELECT dbs.bookId, dbs.valueFen, b.title FROM daily_book_stats dbs " +
            "LEFT JOIN books b ON dbs.bookId = b.bookId " +
            "WHERE dbs.date = :date ORDER BY dbs.valueFen DESC"
    )
    suspend fun getForDate(date: String): List<DailyBookWithTitle>
}
