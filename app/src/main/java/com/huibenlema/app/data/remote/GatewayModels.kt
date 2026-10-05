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
    /** 0-100 整数；实测部分已读完书返回 99，读完判定以 finishTime 为准 */
    val progress: Int,
    /** 累计阅读时长（秒） */
    val recordReadingTime: Long,
    val updateTime: Long,
    val finishTime: Long = 0L,
    val isStartReading: Boolean = false
) {
    val progressRatio: Double get() = progress.coerceIn(0, 100) / 100.0
    /** 已读完：finishTime 仅读完的书才有（progress 字段对读完书可能返回 99） */
    val finished: Boolean get() = finishTime > 0

    /**
     * 计入价值的有效进度：已读完且进度接近读完（≥99）时强制 100%（修复"已读完显示 99%"）；
     * 已读完但远端进度为 0（部分读完书微信读书不返回进度数据）同样按 100% 计；
     * 进度明显回落（如重读，0 < progress < 99）时按实际进度计。
     */
    val effectiveRatio: Double
        get() = when {
            finished && progressRatio >= 0.99 -> 1.0
            finished && progressRatio <= 0.0 -> 1.0
            else -> progressRatio
        }
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
    val registTime: Long = 0L,
    /** 累计时长榜书单（与书架状态无关，用于补捞读完移出书架的书） */
    val longestBooks: List<LongestBookDto> = emptyList()
)

/** readLongest 榜单条目 */
data class LongestBookDto(val bookId: String, val title: String)
