package com.huibenlema.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.huibenlema.app.data.local.entity.SyncLogEntity

@Dao
interface SyncLogDao {

    @Insert
    suspend fun insert(log: SyncLogEntity): Long

    @Query(
        "UPDATE sync_log SET finishedAt = :finishedAt, status = :status, bookCount = :bookCount, " +
            "errorMsg = :errorMsg WHERE id = :id"
    )
    suspend fun finish(id: Long, finishedAt: Long, status: String, bookCount: Int, errorMsg: String)

    @Query("SELECT * FROM sync_log ORDER BY id DESC LIMIT 1")
    suspend fun latest(): SyncLogEntity?
}
