package com.huibenlema.app.domain.model

/** 成本条目分类 */
enum class CostCategory { DEVICE, ACCESSORY, MEMBERSHIP, OTHER }

/** 分类展示名（OTHER 若有自定义名称，展示时优先用自定义名称） */
val CostCategory.displayName: String
    get() = when (this) {
        CostCategory.DEVICE -> "设备"
        CostCategory.ACCESSORY -> "配件"
        CostCategory.MEMBERSHIP -> "会员费"
        CostCategory.OTHER -> "其他"
    }

/**
 * 成本台账条目：设备、配件、会员费等，用户自定义名称与价格。
 */
data class CostItem(
    val id: Long = 0L,
    val name: String,
    val priceFen: Long,
    val category: CostCategory,
    /** 购买日期（epoch millis；未来可做日均摊薄统计） */
    val boughtAt: Long = 0L,
    val note: String = ""
)
