package com.huibenlema.app.domain.model

/**
 * 书籍领域模型。
 * 金额一律 Long（分）；进度为 0.0~1.0 的 Double。
 */
data class Book(
    val bookId: String,
    val title: String,
    val author: String = "",
    val translator: String = "",
    val publisher: String = "",
    val coverUrl: String = "",
    val isbn: String = "",
    val category: String = "",
    /** 定价（分）。0 = 免费或未知；未知由 [priceSource] = NONE 区分 */
    val priceFen: Long = 0L,
    val priceSource: PriceSource = PriceSource.NONE,
    val priceUpdatedAt: Long = 0L,
    /** 阅读进度 0.0 ~ 1.0 */
    val progress: Double = 0.0,
    val finished: Boolean = false,
    /** 累计阅读时长（秒） */
    val totalReadSeconds: Long = 0L,
    val lastReadAt: Long = 0L,
    /** 进度是否手动调节过（列表进度百分比后显示「（手动）」） */
    val progressManual: Boolean = false,
    val onShelf: Boolean = true,
    /** 已移出书架（读完移除/下架）；有进度的移出书仍计入价值 */
    val removed: Boolean = false,
    /** 用户手动隐藏：不参与价值/回本等一切计算，仅隐藏列表可见 */
    val hidden: Boolean = false
) {
    /** 本书贡献价值（分）= 进度 × 定价 */
    val contributedFen: Long
        get() = (progress.coerceIn(0.0, 1.0) * priceFen).toLong()

    /**
     * 是否为用户自导入书籍：上传文件 bookId 以 "33" 开头；
     * 导入后匹配到内容库的以 "CB_" 开头（用户确认同为自导入书，不写官方价）。
     */
    val isImported: Boolean
        get() = bookId.startsWith("33") || bookId.startsWith("CB_")
}
