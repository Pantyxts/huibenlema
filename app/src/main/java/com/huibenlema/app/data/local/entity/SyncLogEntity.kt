package com.huibenlema.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 同步记录。
 */
@Entity(tableName = "sync_log")
data class SyncLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val finishedAt: Long = 0L,
    /** RUNNING / SUCCESS / PARTIAL / FAILED */
    val status: String = "RUNNING",
    val bookCount: Int = 0,
    val errorMsg: String = ""
)
