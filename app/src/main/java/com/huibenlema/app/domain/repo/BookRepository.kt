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
    data class NotFound(val message: String = "未找到官方价格") : ResyncPriceResult()
}

/** 批量恢复阅读进度结果 */
data class BatchProgressResult(
    /** 成功恢复进度的书籍数 */
    val okCount: Int,
    /** 恢复失败的书籍数（限流/网络失败等） */
    val failedCount: Int,
    /** 恢复失败的书（bookId to 书名，供「查看失败书籍」列表展示） */
    val failedBooks: List<Pair<String, String>> = emptyList(),
    /** 需要用户知晓的提示（如 Skill 版本升级），null 表示无 */
    val notice: String? = null
)

/** 单书阅读进度重同步结果 */
sealed class ResyncProgressResult {
    /** progressPct 0-100（已读完且 ≥99 按 100 计） */
    data class Success(val progressPct: Int, val finished: Boolean) : ResyncProgressResult()
    data object NoCredential : ResyncProgressResult()
    data class Failed(val message: String) : ResyncProgressResult()
}

/** 批量获取官方定价结果 */
data class BatchPriceResult(
    /** 成功写入定价的书籍数 */
    val okCount: Int,
    /** 未能获取定价的书籍数（无官方价/无 Cookie/网络失败） */
    val failedCount: Int,
    /** 未能获取定价的书（bookId to 书名，供「查看失败书籍」列表展示） */
    val failedBooks: List<Pair<String, String>> = emptyList()
)

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

    /** 读过的书（价值统计口径）：书架 + 已移出书架但有进度记录的书（不含隐藏书） */
    fun observeReadBooks(): Flow<List<Book>>

    /** 用户手动隐藏的书籍（书值页「隐藏」列表入口；不参与任何计算） */
    fun observeHiddenBooks(): Flow<List<Book>>

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
     * 阅读进度 0-100 可调（默认 100），按进度折算计入价值；定价可空（未定价）；
     * priceFen>0 时来源 MANUAL，自动同步不覆盖。
     */
    suspend fun addCustomBook(title: String, author: String, priceFen: Long?, progressPct: Int = 100)

    /**
     * 手动调节任意书籍阅读进度（0-100），按进度折算计入价值。
     * 同步合并规则：未读完的书取本地与远端进度较长者（手动进度不会被同步覆盖回退）；
     * 远端已读完（≥99）按读完规则计 100%。
     */
    suspend fun updateBookProgress(bookId: String, progressPct: Int)

    /** 隐藏/恢复书籍（隐藏书不参与任何计算，仅隐藏列表可见） */
    suspend fun setBookHidden(bookId: String, hidden: Boolean)

    /** 批量标记已读完（进度 100%，按 100% 计入价值） */
    suspend fun markBooksFinished(bookIds: List<String>)

    /** 批量隐藏/恢复 */
    suspend fun setBooksHidden(bookIds: List<String>, hidden: Boolean)

    /** 批量删除自定义书籍（微信读书书请用隐藏；SQL 层限定 CUSTOM_ 前缀） */
    suspend fun deleteBooks(bookIds: List<String>)

    /**
     * 批量获取选中书籍的微信读书官方定价（强制覆盖手动价，结果经返回值反馈）。
     * [onProgress] 每处理一本回调（已处理数, 总数），供 UI 显示 1/N 进度。
     */
    suspend fun resyncBooksPrice(
        bookIds: List<String>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): BatchPriceResult

    /**
     * 批量恢复选中书籍的微信读书阅读进度（以远端为准，覆盖后清除「手动」标记）。
     * [onProgress] 每处理一本回调（已处理数, 总数），供 UI 显示 1/N 进度。
     */
    suspend fun resyncBooksProgress(
        bookIds: List<String>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): BatchProgressResult

    /** 删除单本（仅手动添加的自定义书籍入口使用） */
    suspend fun deleteBook(bookId: String)

    /** 单书重新同步微信读书官方价格（覆盖手动价） */
    suspend fun resyncOfficialPrice(bookId: String): ResyncPriceResult

    /** 单书重新同步微信读书阅读进度（纯查询，编辑弹窗点「保存」时才落库） */
    suspend fun resyncBookProgress(bookId: String): ResyncProgressResult

    /** 保存单书同步获取的阅读进度（以微信读书为准，不显示「手动」标记） */
    suspend fun saveRestoredProgress(bookId: String, progressPct: Int)

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
