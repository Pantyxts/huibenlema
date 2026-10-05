package com.huibenlema.app.domain.repo

import android.net.Uri
import com.huibenlema.app.data.sync.SyncProgress
import com.huibenlema.app.domain.model.Book
import com.huibenlema.app.domain.model.BookDayStat
import com.huibenlema.app.domain.model.CostItem
import com.huibenlema.app.domain.model.DailyStat
import com.huibenlema.app.domain.model.PaybackSummary
import kotlinx.coroutines.flow.Flow

/** 单书官方价格重同步结果 */
sealed class ResyncPriceResult {
    data class Success(val priceFen: Long) : ResyncPriceResult()
    data object NoCredential : ResyncPriceResult()
    data object NotFound : ResyncPriceResult()
}

/** 同步结果 */
sealed class SyncResult {
    data class Success(
        /** 共导入书籍 */
        val bookCount: Int,
        /** 获取价格成功 */
        val pricedCount: Int,
        /** 获取价格失败（含自导入书籍） */
        val unpricedCount: Int,
        /** 本次同步后的已读总价值（分） */
        val totalValueFen: Long,
        /** 警告（登录态失效等），null 表示正常 */
        val priceWarning: String? = null
    ) : SyncResult()

    /** 尚未配置凭证 */
    data object NoCredential : SyncResult()

    /** 凭证无效/过期 */
    data object AuthFailed : SyncResult()

    data object RateLimited : SyncResult()

    /** 无网络连接 */
    data object NetworkError : SyncResult()

    data class Failure(val message: String) : SyncResult()

    data class UpgradeRequired(val message: String) : SyncResult()
}

interface BookRepository {

    fun observeShelfBooks(): Flow<List<Book>>

    /** 读过的书（价值统计口径）：书架 + 已移出书架但有进度记录的书 */
    fun observeReadBooks(): Flow<List<Book>>

    fun observeSummary(): Flow<PaybackSummary>

    /** 近 days 天每日价值（含今天，无数据的日期由 UI 补零） */
    fun observeDailyStats(days: Int): Flow<List<DailyStat>>

    fun observeCostItems(): Flow<List<CostItem>>

    fun observeLastSyncAt(): Flow<Long>

    fun observeHasCredential(): Flow<Boolean>

    /** 同步进度（0-100 + 阶段说明） */
    val syncProgress: Flow<SyncProgress>

    /** 同步进行中（任何入口触发的同步，含引导页启动的后台同步） */
    val syncing: Flow<Boolean>

    /** 每次同步完成后的结果（手动/自动/后台统一经此反馈，无回放） */
    val lastSyncResult: Flow<SyncResult>

    /** 后台同步：app 级 scope 执行，跨页面不取消 */
    fun syncInBackground()

    suspend fun addCostItem(item: CostItem): Long

    suspend fun updateCostItem(item: CostItem)

    suspend fun deleteCostItem(id: Long)

    suspend fun updatePriceManual(bookId: String, priceFen: Long)

    /** 保存官方同步价（编辑页「同步官方价格」后点保存；WEREAD 来源） */
    suspend fun saveOfficialPrice(bookId: String, priceFen: Long)

    /**
     * 添加自定义书籍（手动录入，不在微信读书书架）：
     * 视为已读完（progress=1.0）全额计入价值；定价可空（未定价）；
     * priceFen>0 时来源 MANUAL，自动同步不覆盖。
     */
    suspend fun addCustomBook(title: String, author: String, priceFen: Long?)

    /** 删除单本（仅手动添加的自定义书籍入口使用） */
    suspend fun deleteBook(bookId: String)

    /** 单书重新同步微信读书官方价格（覆盖手动价） */
    suspend fun resyncOfficialPrice(bookId: String): ResyncPriceResult

    /** 某天的书籍价值明细（柱状图点选展示） */
    suspend fun getDailyBookStats(date: String): List<BookDayStat>

    /** 手动同步（主路径） */
    suspend fun sync(): SyncResult

    /** 数据导出：书籍 + 成本台账 + 每日统计 → JSON 写入目标 Uri；凭证不导出 */
    suspend fun exportData(uri: Uri): Boolean

    /** 数据导入：从备份 JSON 恢复。书籍按 bookId 合并；成本台账与每日统计替换 */
    suspend fun importData(uri: Uri): Boolean

    /** 清除全部书值数据 */
    suspend fun clearAllBooks()

    /** 清除非手动书值数据（保留手动定价的书） */
    suspend fun clearNonManualBooks()

    /** 清除手动书值数据（保留官方定价的书） */
    suspend fun clearManualBooks()

    /** 清除所有成本栏目 */
    suspend fun clearCostItems()
}
