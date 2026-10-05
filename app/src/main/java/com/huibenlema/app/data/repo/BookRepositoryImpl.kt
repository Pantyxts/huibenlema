package com.huibenlema.app.data.repo

import android.content.Context
import android.net.Uri
import com.huibenlema.app.data.local.UserPrefs
import com.huibenlema.app.data.local.dao.BookDao
import com.huibenlema.app.data.local.dao.CostItemDao
import com.huibenlema.app.data.local.dao.DailyBookStatDao
import com.huibenlema.app.data.local.dao.DailyStatDao
import com.huibenlema.app.data.local.toDomain
import com.huibenlema.app.data.local.toEntity
import com.huibenlema.app.data.security.CredentialsManager
import com.huibenlema.app.data.sync.SyncManager
import com.huibenlema.app.data.sync.SyncProgress
import com.huibenlema.app.data.sync.SyncProgressTracker
import com.huibenlema.app.domain.calculator.PaybackCalculator
import com.huibenlema.app.data.local.entity.BookEntity
import com.huibenlema.app.data.local.entity.CostItemEntity
import com.huibenlema.app.data.local.entity.DailyStatEntity
import com.huibenlema.app.domain.model.BackupBook
import com.huibenlema.app.domain.model.BackupCost
import com.huibenlema.app.domain.model.BackupDailyStat
import com.huibenlema.app.domain.model.BackupFile
import com.huibenlema.app.domain.model.CostCategory
import com.huibenlema.app.domain.model.PriceSource
import com.huibenlema.app.domain.model.Book
import com.huibenlema.app.domain.model.BookDayStat
import com.huibenlema.app.domain.model.CostItem
import com.huibenlema.app.domain.model.DailyStat
import com.huibenlema.app.domain.model.PaybackSummary
import com.huibenlema.app.domain.repo.BookRepository
import com.huibenlema.app.domain.repo.ResyncPriceResult
import com.huibenlema.app.domain.repo.SyncResult
import java.time.LocalDate
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Singleton
class BookRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bookDao: BookDao,
    private val costItemDao: CostItemDao,
    private val dailyStatDao: DailyStatDao,
    private val dailyBookStatDao: DailyBookStatDao,
    private val syncManager: SyncManager,
    private val credentials: CredentialsManager,
    private val prefs: UserPrefs,
    private val progressTracker: SyncProgressTracker
) : BookRepository {

    /** 同步互斥锁：手动同步 / 启动自动同步 / 周期任务并发时串行执行 */
    private val syncMutex = Mutex()

    override val syncProgress: Flow<SyncProgress> = progressTracker.state

    override fun observeShelfBooks(): Flow<List<Book>> =
        bookDao.observeShelfBooks().map { list -> list.map { it.toDomain() } }

    override fun observeReadBooks(): Flow<List<Book>> =
        bookDao.observeReadBooks().map { list -> list.map { it.toDomain() } }

    /** 回本总价值：读过的书（含已移出书架的） */
    override fun observeSummary(): Flow<PaybackSummary> =
        combine(bookDao.observeReadBooks(), costItemDao.observeAll()) { books, costs ->
            PaybackCalculator.compute(
                books = books.map { it.toDomain() },
                costs = costs.map { it.toDomain() }
            )
        }

    override fun observeDailyStats(days: Int): Flow<List<DailyStat>> {
        val from = LocalDate.now().minusDays(days - 1L).toString()
        return dailyStatDao.observeFrom(from, days).map { list -> list.map { it.toDomain() } }
    }

    override fun observeCostItems(): Flow<List<CostItem>> =
        costItemDao.observeAll().map { list -> list.map { it.toDomain() } }

    override fun observeLastSyncAt(): Flow<Long> = prefs.lastSyncAt

    override fun observeHasCredential(): Flow<Boolean> =
        prefs.apiKeyCipher.map { !it.isNullOrBlank() }

    override suspend fun addCostItem(item: CostItem): Long = costItemDao.insert(item.toEntity())

    override suspend fun updateCostItem(item: CostItem) = costItemDao.update(item.toEntity())

    override suspend fun deleteCostItem(id: Long) = costItemDao.delete(id)

    override suspend fun updatePriceManual(bookId: String, priceFen: Long) =
        bookDao.updatePriceManual(bookId, priceFen, System.currentTimeMillis())

    override suspend fun saveOfficialPrice(bookId: String, priceFen: Long) =
        bookDao.updatePriceForce(bookId, priceFen, PriceSource.WEREAD, System.currentTimeMillis())

    override suspend fun clearAllBooks() = bookDao.deleteAllBooks()

    override suspend fun clearNonManualBooks() = bookDao.deleteNonManualBooks()

    override suspend fun clearManualBooks() = bookDao.deleteManualBooks()

    override suspend fun clearCostItems() = costItemDao.deleteAll()

    override suspend fun resyncOfficialPrice(bookId: String): ResyncPriceResult {
        val key = credentials.apiKey() ?: return ResyncPriceResult.NoCredential
        return syncManager.resyncBookPrice(bookId, key, credentials.cookie())
    }

    override suspend fun getDailyBookStats(date: String): List<BookDayStat> =
        dailyBookStatDao.getForDate(date).map {
            BookDayStat(bookId = it.bookId, title = it.title ?: "未知书名", valueFen = it.valueFen)
        }

    override suspend fun sync(): SyncResult = syncMutex.withLock {
        // 自愈：已保存 Cookie 缺会话密钥时，从 WebView Cookie 存储补取最新值
        val saved = credentials.cookie()
        val cookie = if (saved.isNullOrBlank() || !saved.contains("wr_skey")) {
            val fresh = runCatching {
                android.webkit.CookieManager.getInstance().getCookie("https://weread.qq.com/")
            }.getOrNull()
            if (fresh != null && fresh.contains("wr_skey")) {
                credentials.saveCookie(fresh)
                fresh
            } else {
                saved
            }
        } else {
            saved
        }

        // Key 缺失但有登录态 → 自动从官方接口获取 API Key（扫码即全部授权）
        var key = credentials.apiKey()
        if (key == null && !cookie.isNullOrBlank()) {
            if (credentials.fetchAndSaveApiKey(cookie)) {
                key = credentials.apiKey()
            }
        }
        if (key == null) return SyncResult.NoCredential

        val result = syncManager.sync(key, cookie)
        if (result is SyncResult.Success) {
            prefs.setLastSyncAt(System.currentTimeMillis())
        }
        return result
    }

    override suspend fun exportData(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val books = bookDao.getAllOnce().map { e ->
                BackupBook(
                    bookId = e.bookId,
                    title = e.title,
                    author = e.author,
                    priceFen = e.priceFen,
                    priceSource = e.priceSource.name,
                    progress = e.progress,
                    totalReadSeconds = e.totalReadSeconds,
                    onShelf = e.onShelf,
                    removed = e.removed
                )
            }
            val costs = costItemDao.getAllOnce().map { e ->
                BackupCost(
                    name = e.name,
                    priceFen = e.priceFen,
                    category = e.category.name,
                    note = e.note
                )
            }
            val daily = dailyStatDao.getAllOnce().map { e ->
                BackupDailyStat(
                    date = e.date,
                    readSeconds = e.readSeconds,
                    valueFen = e.valueFen,
                    bookCount = e.bookCount
                )
            }
            val file = BackupFile(
                exportedAt = System.currentTimeMillis(),
                books = books,
                costs = costs,
                dailyStats = daily
            )
            val json = Json { prettyPrint = true }.encodeToString(file)
            val out = context.contentResolver.openOutputStream(uri, "wt")
                ?: return@withContext false
            out.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            true
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun importData(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val json = context.contentResolver.openInputStream(uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            } ?: return@withContext false
            val file = Json.decodeFromString(BackupFile.serializer(), json)
            val now = System.currentTimeMillis()
            // 书籍按 bookId 合并（元数据缺失可在下次同步恢复，不删现有数据）
            bookDao.upsertAll(file.books.map { b ->
                BookEntity(
                    bookId = b.bookId,
                    title = b.title,
                    author = b.author,
                    priceFen = b.priceFen,
                    priceSource = runCatching { PriceSource.valueOf(b.priceSource) }.getOrDefault(PriceSource.NONE),
                    progress = b.progress,
                    totalReadSeconds = b.totalReadSeconds,
                    onShelf = b.onShelf,
                    removed = b.removed,
                    createdAt = now,
                    updatedAt = now
                )
            })
            // 成本台账替换（用户手动录入的核心资产，恢复语义）
            costItemDao.deleteAll()
            costItemDao.insertAll(file.costs.map { c ->
                CostItemEntity(
                    name = c.name,
                    priceFen = c.priceFen,
                    category = runCatching { CostCategory.valueOf(c.category) }.getOrDefault(CostCategory.OTHER),
                    note = c.note
                )
            })
            // 每日统计替换
            dailyStatDao.deleteAll()
            dailyStatDao.upsertAll(file.dailyStats.map { d ->
                DailyStatEntity(
                    date = d.date,
                    valueFen = d.valueFen,
                    readSeconds = d.readSeconds,
                    bookCount = d.bookCount
                )
            })
            true
        } catch (_: Exception) {
            false
        }
    }
}
