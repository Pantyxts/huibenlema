package com.huibenlema.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.huibenlema.app.domain.model.CostCategory

/**
 * 成本台账：设备、配件、会员费等，用户自定义。
 */
@Entity(tableName = "cost_items")
data class CostItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val priceFen: Long,
    val category: CostCategory,
    /** 购买日期（epoch millis） */
    val boughtAt: Long = 0L,
    val note: String = ""
)
