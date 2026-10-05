package com.huibenlema.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.huibenlema.app.data.local.entity.ReadHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadHistoryDao {

    /** 某书最近一次进度快照（Δ进度计算基准） */
    @Query("SELECT * FROM read_history WHERE bookId = :bookId ORDER BY date DESC LIMIT 1")
    suspend fun latest(bookId: String): ReadHistoryEntity?

    /** 某书全部进度快照（按日期升序，本地重建每日价值用） */
    @Query("SELECT * FROM read_history WHERE bookId = :bookId ORDER BY date ASC")
    suspend fun getAllForBook(bookId: String): List<ReadHistoryEntity>

    /** 全部有价书的进度快照（一次查询替代逐本查询，本地重建每日价值用） */
    @Query(
        "SELECT rh.* FROM read_history rh INNER JOIN books b ON rh.bookId = b.bookId " +
            "WHERE b.priceFen > 0 ORDER BY rh.bookId ASC, rh.date ASC"
    )
    suspend fun getAllValuedBooks(): List<ReadHistoryEntity>

    @Upsert
    suspend fun upsertAll(rows: List<ReadHistoryEntity>)

    @Query("SELECT * FROM read_history WHERE date BETWEEN :from AND :to ORDER BY date ASC")
    fun observeRange(from: String, to: String): Flow<List<ReadHistoryEntity>>
}
