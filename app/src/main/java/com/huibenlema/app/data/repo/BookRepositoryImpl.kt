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
import com.huibenlema.app.data.log.AppLog
import com.huibenlema.app.data.security.CredentialStatus
import com.huibenlema.app.data.security.CredentialsManager
import com.huibenlema.app.data.sync.SyncManager
import com.huibenlema.app.data.sync.SyncProgress
import com.huibenlema.app.data.sync.SyncProgressTracker
import com.huibenlema.app.domain.calculator.PaybackCalculator
import com.huibenlema.app.data.local.entity.BookEntity
import com.huibenlema.app.data.local.entity.CostItemEntity
import com.huibenlema.app.domain.model.BackupBook
import com.huibenlema.app.domain.model.BackupCost
import com.huibenlema.app.domain.model.BackupFile
import com.huibenlema.app.domain.model.CostCategory
import com.huibenlema.app.domain.model.PriceSource
import com.huibenlema.app.domain.model.Book
import com.huibenlema.app.domain.model.BookDayStat
import com.huibenlema.app.domain.model.CostItem
import com.huibenlema.app.domain.model.DailyStat
import com.huibenlema.app.domain.model.PaybackSummary
import com.huibenlema.app.domain.repo.BatchPriceResult
import com.huibenlema.app.domain.repo.BatchProgressResult
import com.huibenlema.app.domain.repo.BookRepository
import com.huibenlema.app.domain.repo.ResyncPriceResult
import com.huibenlema.app.domain.repo.ResyncProgressResult
import com.huibenlema.app.domain.repo.SyncResult
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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

    /** 后台同步 scope：独立于页面 ViewModel，跨页面不取消 */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 后台同步请求去重：排队中/执行中都算已请求，双击与多入口重复触发只执行一次 */
    private val syncRequested = java.util.concurrent.atomic.AtomicBoolean(false)

    private val _syncing = MutableStateFlow(false)
    override val syncing: Flow<Boolean> = _syncing.asStateFlow()

    // replay=1：同步结果不再因订阅时机丢失（启动瞬间 Worker 完成的结果进入页面也能收到）
    private val _lastSyncResult = MutableSharedFlow<SyncResult>(replay = 1, extraBufferCapacity = 8)
    override val lastSyncResult: Flow<SyncResult> = _lastSyncResult.asSharedFlow()

    /**
     * 每日价值重建请求（conflated + 1.5s debounce）：
     * 所有本地写（改价/改进度/隐藏/删除等）都会影响价值，批量操作上百次写合并为一次重建；
     * 与同步共用 syncMutex 避免并发写库。
     */
    private val rebuildRequested = MutableStateFlow(0L)

    init {
        appScope.launch {
            rebuildRequested.collectLatest { ts ->
                if (ts <= 0) return@collectLatest
                delay(REBUILD_DEBOUNCE_MS)
                syncMutex.withLock { syncManager.rebuildDailyValuesNow() }
            }
        }
    }

    private fun requestValueRebuild() {
        rebuildRequested.value = System.currentTimeMillis()
    }

    override val syncProgress: Flow<SyncProgress> = progressTracker.state

    override fun syncInBackground() {
        // 原子去重：同步排队/执行期间重复触发（双击、引导页+首页自动同步并发）只执行一次
        if (!syncRequested.compareAndSet(false, true)) return
        appScope.launch {
            try {
                sync()
            } finally {
                syncRequested.set(false)
            }
        }
    }

    override fun observeShelfBooks(): Flow<List<Book>> =
        bookDao.observeShelfBooks().map { list -> list.map { it.toDomain() } }

    override fun observeReadBooks(): Flow<List<Book>> =
        bookDao.observeReadBooks()
            .distinctUntilChanged()
            .map { list -> list.map { it.toDomain() } }
            // 万本量级的转换/排序放 Default 线程，避免同步风暴时阻塞主线程
            .flowOn(Dispatchers.Default)

    override fun observeHiddenBooks(): Flow<List<Book>> =
        bookDao.observeHiddenBooks()
            .distinctUntilChanged()
            .map { list -> list.map { it.toDomain() } }
            .flowOn(Dispatchers.Default)

    /** 回本总价值：读过的书（含已移出书架的） */
    override fun observeSummary(): Flow<PaybackSummary> =
        combine(
            bookDao.observeReadBooks().distinctUntilChanged(),
            costItemDao.observeAll()
        ) { books, costs ->
            PaybackCalculator.compute(
                books = books.map { it.toDomain() },
                costs = costs.map { it.toDomain() }
            )
        }.flowOn(Dispatchers.Default)

    override fun observeDailyStats(days: Int): Flow<List<DailyStat>> =
        // 倒序取最新 N 条再反转为升序：查询窗口不冻结，跨零点后最新一天立即可见
        dailyStatDao.observeRecent(days).map { list -> list.asReversed().map { it.toDomain() } }

    override fun observeCostItems(): Flow<List<CostItem>> =
        costItemDao.observeAll().map { list -> list.map { it.toDomain() } }

    override fun observeLastSyncAt(): Flow<Long> = prefs.lastSyncAt

    /** 登录态 = 可解密（密文存在但密钥丢失时不再显示"已登录"） */
    override fun observeHasCredential(): Flow<Boolean> =
        credentials.status.map { it == CredentialStatus.OK }

    override fun credentialStatus(): Flow<CredentialStatus> = credentials.status

    override suspend fun addCostItem(item: CostItem): Long = costItemDao.insert(item.toEntity())

    override suspend fun updateCostItem(item: CostItem) = costItemDao.update(item.toEntity())

    override suspend fun deleteCostItem(id: Long) = costItemDao.delete(id)

    override suspend fun updatePriceManual(bookId: String, priceFen: Long) {
        bookDao.updatePriceManual(bookId, priceFen, System.currentTimeMillis())
        requestValueRebuild()
    }

    override suspend fun saveOfficialPrice(bookId: String, priceFen: Long) {
        bookDao.updatePriceForce(bookId, priceFen, PriceSource.WEREAD, System.currentTimeMillis())
        requestValueRebuild()
    }

    override suspend fun addCustomBook(title: String, author: String, priceFen: Long?, progressPct: Int) {
        val now = System.currentTimeMillis()
        val progress = (progressPct.coerceIn(0, 100)) / 100.0
        // CUSTOM_ 前缀 + 时间戳保证唯一；onShelf=false 使同步书架/进度/补价逻辑完全跳过本记录
        bookDao.upsertAll(
            listOf(
                BookEntity(
                    bookId = "CUSTOM_$now",
                    title = title,
                    author = author,
                    priceFen = priceFen ?: 0L,
                    priceSource = if (priceFen != null && priceFen > 0) PriceSource.MANUAL else PriceSource.NONE,
                    progress = progress,
                    finished = progress >= 1.0,
                    onShelf = false,
                    removed = true,
                    createdAt = now,
                    updatedAt = now
                )
            )
        )
        requestValueRebuild()
    }

    override suspend fun updateBookProgress(bookId: String, progressPct: Int) {
        bookDao.updateBookProgress(
            bookId = bookId,
            progress = progressPct.coerceIn(0, 100) / 100.0,
            finished = progressPct >= 100,
            ts = System.currentTimeMillis()
        )
        requestValueRebuild()
    }

    override suspend fun setBookHidden(bookId: String, hidden: Boolean) {
        bookDao.setHidden(bookId, hidden, System.currentTimeMillis())
        requestValueRebuild()
    }

    override suspend fun markBooksFinished(bookIds: List<String>) {
        // 分批执行：SQLite 单语句参数上限（老设备 999），万本全选也不越界
        val now = System.currentTimeMillis()
        bookIds.chunked(500).forEach { bookDao.markFinishedBatch(it, now) }
        requestValueRebuild()
    }

    override suspend fun setBooksHidden(bookIds: List<String>, hidden: Boolean) {
        val now = System.currentTimeMillis()
        bookIds.chunked(500).forEach { bookDao.setHiddenBatch(it, hidden, now) }
        requestValueRebuild()
    }

    override suspend fun deleteBooks(bookIds: List<String>) {
        bookIds.chunked(500).forEach { bookDao.deleteCustomBatch(it) }
        requestValueRebuild()
    }

    override suspend fun resyncBooksPrice(
        bookIds: List<String>,
        onProgress: (done: Int, total: Int) -> Unit
    ): BatchPriceResult {
        val key = credentials.apiKey()
        if (key == null) {
            val titles = bookDao.getTitlesByIds(bookIds)
            return BatchPriceResult(
                okCount = 0,
                failedCount = bookIds.size,
                failedBooks = bookIds.map { it to (titles[it] ?: "未知书名") }
            )
        }
        return syncManager.resyncBooksPrice(bookIds, key, credentials.cookie(), onProgress)
    }

    override suspend fun resyncBooksProgress(
        bookIds: List<String>,
        onProgress: (done: Int, total: Int) -> Unit
    ): BatchProgressResult {
        val key = credentials.apiKey()
        if (key == null) {
            val titles = bookDao.getTitlesByIds(bookIds)
            return BatchProgressResult(
                okCount = 0,
                failedCount = bookIds.size,
                failedBooks = bookIds.map { it to (titles[it] ?: "未知书名") }
            )
        }
        return syncManager.resyncBooksProgress(bookIds, key, onProgress)
    }

    override suspend fun deleteBook(bookId: String) {
        bookDao.deleteBook(bookId)
        requestValueRebuild()
    }

    override suspend fun clearAllBooks() {
        bookDao.deleteAllBooks()
        requestValueRebuild()
    }

    override suspend fun clearNonManualBooks() {
        bookDao.deleteNonManualBooks()
        requestValueRebuild()
    }

    override suspend fun clearManualBooks() {
        bookDao.deleteManualBooks()
        requestValueRebuild()
    }

    override suspend fun clearCostItems() = costItemDao.deleteAll()

    override suspend fun resyncOfficialPrice(bookId: String): ResyncPriceResult {
        val key = credentials.apiKey() ?: return ResyncPriceResult.NoCredential
        return syncManager.resyncBookPrice(bookId, key, credentials.cookie())
    }

    override suspend fun resyncBookProgress(bookId: String): ResyncProgressResult {
        val key = credentials.apiKey() ?: return ResyncProgressResult.NoCredential
        return syncManager.resyncBookProgress(bookId, key)
    }

    override suspend fun saveRestoredProgress(bookId: String, progressPct: Int) {
        syncManager.applyRestoredProgress(bookId, progressPct)
        requestValueRebuild()
    }

    override suspend fun getDailyBookStats(date: String): List<BookDayStat> =
        dailyBookStatDao.getForDate(date).map {
            BookDayStat(bookId = it.bookId, title = it.title ?: "未知书名", valueFen = it.valueFen)
        }

    override suspend fun sync(): SyncResult {
        AppLog.i("HBSync", "sync_enter waitingLock")
        val started = System.currentTimeMillis()
        return syncMutex.withLock {
            AppLog.i("HBSync", "sync_begin waited=${System.currentTimeMillis() - started}ms")
            _syncing.value = true
            try {
                doSync().also { _lastSyncResult.tryEmit(it) }
            } finally {
                _syncing.value = false
            }
        }
    }

    /** 本地重建每日价值（升级后启动强制重建；与同步共用互斥锁避免并发写库） */
    override suspend fun rebuildDailyValues() {
        AppLog.i("HBSync", "rebuild_manual_enter")
        syncMutex.withLock {
            syncManager.rebuildDailyValuesNow()
        }
    }

    private suspend fun doSync(): SyncResult {
        // 不再从 WebView CookieManager 读 Cookie（旧"自愈"逻辑已删除）：
        // 1) 同步（IO 线程）触碰 WebView API 会触发隐式初始化——实测在墨水屏设备上
        //    与主线程 WebView 创建互等，导致同步卡死（点击同步无反应）与登录页白屏；
        // 2) 与"退出登录后不复活"的目标冲突。Cookie 只来自加密存储，失效请重新扫码。
        val cookie = credentials.cookie()

        // 先拿 Key；拿不到时优先用可解密的 Cookie 从官方接口补取（扫码即全部授权）——
        // 顺序必须在"密文损坏判定"之前：扫码登录后 Cookie 密文必然存在，
        // 若先判 cipherPresent 会直接报「凭证已失效」，永远走不到补取步骤
        var key = credentials.apiKey()
        if (key == null && !cookie.isNullOrBlank() && credentials.fetchAndSaveApiKey(cookie)) {
            key = credentials.apiKey()
        }
        if (key == null) {
            // 补取失败才区分：密文在但解不开 = 凭证失效（请重新扫码）；无密文 = 未登录
            if (credentials.cipherPresent()) {
                AppLog.w("HBSync", "cipher_present_but_broken")
                return SyncResult.AuthFailed
            }
            return SyncResult.NoCredential
        }

        val result = syncManager.sync(key, cookie)
        // 无论成败都记录尝试时间：上次同步失败时 lastSyncAt 不更新，
        // 若无尝试时间兜底，每次打开 App 都会重新触发一整轮全量同步
        prefs.setLastSyncAttemptAt(System.currentTimeMillis())
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
                    removed = e.removed,
                    hidden = e.hidden,
                    progressManual = e.progressManual
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
            // 每日价值不随备份导出：由同步重建生成，导入也不做恢复
            val file = BackupFile(
                exportedAt = System.currentTimeMillis(),
                books = books,
                costs = costs
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
                    hidden = b.hidden,
                    progressManual = b.progressManual,
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
            // 每日价值不随备份导入（file.dailyStats 仅兼容解析旧文件，内容忽略）：
            // 图表只由同步重建绘制，导入不触碰 daily_stats，避免旧备份的坏数据覆盖本地正确结果
            true
        } catch (_: Exception) {
            false
        }
    }

    private companion object {
        /** 价值重建防抖：批量操作的上百次写合并为一次重建 */
        private const val REBUILD_DEBOUNCE_MS = 1_500L
    }
}
