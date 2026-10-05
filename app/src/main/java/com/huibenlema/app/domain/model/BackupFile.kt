package com.huibenlema.app.domain.model

import kotlinx.serialization.Serializable

/**
 * 数据导出/备份文件结构（formatVersion 1）。
 * 凭证（API Key / Cookie）一律不导出；金额一律分。
 */
@Serializable
data class BackupFile(
    val formatVersion: Int = 1,
    val exportedAt: Long,
    val books: List<BackupBook>,
    val costs: List<BackupCost>,
    val dailyStats: List<BackupDailyStat>
)

@Serializable
data class BackupBook(
    val bookId: String,
    val title: String,
    val author: String = "",
    val priceFen: Long = 0L,
    /** NONE / WEREAD / ISBN / MANUAL */
    val priceSource: String,
    val progress: Double = 0.0,
    val totalReadSeconds: Long = 0L,
    val onShelf: Boolean = true,
    val removed: Boolean = false,
    /** 用户手动隐藏（旧备份无此字段，默认 false） */
    val hidden: Boolean = false,
    /** 进度手动调节标记（旧备份无此字段，默认 false） */
    val progressManual: Boolean = false
)

@Serializable
data class BackupCost(
    val name: String,
    val priceFen: Long,
    /** DEVICE / ACCESSORY / MEMBERSHIP / OTHER */
    val category: String,
    val note: String = ""
)

@Serializable
data class BackupDailyStat(
    val date: String,   // yyyy-MM-dd
    val readSeconds: Long,
    val valueFen: Long,
    val bookCount: Int
)
