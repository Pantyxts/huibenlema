package com.huibenlema.app.data.remote

/** 官方网关调用结果 */
sealed class GatewayResult<out T> {
    data class Ok<T>(val data: T) : GatewayResult<T>()
    data class Err(val errcode: Int, val message: String) : GatewayResult<Nothing>()
    /** 官方 skill 版本升级提示（SKILL.md 要求必须暂停并提示用户） */
    data class UpgradeRequired(val message: String) : GatewayResult<Nothing>()
}

/** /shelf/sync 书籍条目 */
data class ShelfBookDto(
    val bookId: String,
    val title: String = "",
    val author: String = "",
    val cover: String = "",
    val category: String = "",
    val readUpdateTime: Long = 0L,
    val finishReading: Boolean = false
)

data class ShelfSyncDto(val books: List<ShelfBookDto>)

/** /book/getprogress 进度 */
data class ProgressDto(
    val bookId: String,
    /** 0-100 整数；100 + finishTime 才算读完 */
    val progress: Int,
    /** 累计阅读时长（秒） */
    val recordReadingTime: Long,
    val updateTime: Long,
    val finishTime: Long = 0L,
    val isStartReading: Boolean = false
) {
    val progressRatio: Double get() = progress.coerceIn(0, 100) / 100.0
    val finished: Boolean get() = progress >= 100 && finishTime > 0
}

/** /user/notebooks 中的书籍价格（官方批量定价通道） */
data class BookPriceDto(
    val bookId: String,
    /** 定价（分） */
    val priceFen: Long,
    /** 响应中是否显式含价格字段（centPrice/originalPrice/price）；全缺失 = 系统内无定价数据，不应写入 */
    val hasPrice: Boolean,
    /** centPrice=0 且 bookStatus=1 → 免费书（0 元是有效结果） */
    val isFree: Boolean
)

/** /readdata/detail (monthly) 阅读统计 */
data class ReadDataDto(
    /** 周期总阅读时长（秒） */
    val totalReadTime: Long,
    /** date(yyyy-MM-dd) -> 当日阅读秒数（readTimes 按天分桶） */
    val dailySeconds: Map<String, Long>,
    /** 账号注册时间（Unix 秒，官方接口唯一的账号特性字段） */
    val registTime: Long = 0L
)
