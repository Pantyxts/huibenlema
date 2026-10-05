package com.huibenlema.app.data.sync

import androidx.room.withTransaction
import com.huibenlema.app.data.local.AppDatabase
import com.huibenlema.app.data.local.UserPrefs
import com.huibenlema.app.data.local.dao.BookDao
import com.huibenlema.app.data.local.dao.DailyBookStatDao
import com.huibenlema.app.data.local.dao.DailyStatDao
import com.huibenlema.app.data.local.dao.PriceCacheDao
import com.huibenlema.app.data.local.dao.ReadHistoryDao
import com.huibenlema.app.data.local.dao.SyncLogDao
import com.huibenlema.app.data.local.entity.BookEntity
import com.huibenlema.app.data.local.entity.DailyBookStatEntity
import com.huibenlema.app.data.local.entity.DailyStatEntity
import com.huibenlema.app.data.local.entity.PriceCacheEntity
import com.huibenlema.app.data.local.entity.ReadHistoryEntity
import com.huibenlema.app.data.local.entity.SyncLogEntity
import com.huibenlema.app.data.remote.GatewayResult
import com.huibenlema.app.data.remote.LongestBookDto
import com.huibenlema.app.data.remote.OfficialGatewayClient
import com.huibenlema.app.data.remote.PrivateWereadApi
import com.huibenlema.app.data.remote.ProgressDto
import com.huibenlema.app.data.remote.ShelfSyncDto
import com.huibenlema.app.data.remote.lng
import com.huibenlema.app.data.remote.priceFenOrNull
import com.huibenlema.app.data.remote.str
import com.huibenlema.app.domain.model.PriceSource
import com.huibenlema.app.domain.repo.BatchPriceResult
import com.huibenlema.app.domain.repo.BatchProgressResult
import com.huibenlema.app.domain.repo.ResyncPriceResult
import com.huibenlema.app.domain.repo.ResyncProgressResult
import com.huibenlema.app.domain.repo.SyncResult
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * 同步引擎：
 * 书架 → 官方 notebooks 批量定价 → 增量进度 →
 * readdata 历史回溯（按天分桶）→ 每日价值按阅读时长加权分布。
 * 单本/单项失败不阻塞整体。
 */
@Singleton
class SyncManager @Inject constructor(
    private val db: AppDatabase,
    private val client: OfficialGatewayClient,
    private val privateApi: PrivateWereadApi,
    private val bookDao: BookDao,
    private val readHistoryDao: ReadHistoryDao,
    private val dailyStatDao: DailyStatDao,
    private val dailyBookStatDao: DailyBookStatDao,
    private val priceCacheDao: PriceCacheDao,
    private val syncLogDao: SyncLogDao,
    private val prefs: UserPrefs,
    private val tracker: SyncProgressTracker
) {

    suspend fun sync(key: String, cookie: String? = null): SyncResult {
        val startedAt = System.currentTimeMillis()
        val logId = syncLogDao.insert(SyncLogEntity(startedAt = startedAt))
        tracker.update(0, "准备同步")
        return try {
            doSync(key, cookie, startedAt).also { result ->
                tracker.update(100, "同步完成")
                when (result) {
                    is SyncResult.Success ->
                        syncLogDao.finish(logId, System.currentTimeMillis(), "SUCCESS", result.bookCount, "")
                    is SyncResult.Failure ->
                        syncLogDao.finish(logId, System.currentTimeMillis(), "FAILED", 0, result.message)
                    else ->
                        syncLogDao.finish(logId, System.currentTimeMillis(), "FAILED", 0, result.toString())
                }
            }
        } catch (e: Exception) {
            syncLogDao.finish(logId, System.currentTimeMillis(), "FAILED", 0, e.message ?: "同步异常")
            SyncResult.Failure(e.message ?: "同步异常")
        }
    }

    private suspend fun doSync(key: String, cookie: String?, now: Long): SyncResult {
        // 1. 书架（限流时退避重试，避免上千本书同步被风控时整次失败）
        val shelf = when (val r = shelfSyncWithRetry(key)) {
            is GatewayResult.Ok -> r.data
            is GatewayResult.Err -> return when {
                r.errcode == -1 -> SyncResult.NetworkError
                isRateLimited(r.errcode) -> SyncResult.RateLimited
                else -> SyncResult.Failure(r.message)
            }
            is GatewayResult.UpgradeRequired -> return SyncResult.UpgradeRequired(r.message)
        }

        tracker.update(10, "获取书架完成")

        // 2. upsert 书架（保留已有价格/进度/快照时间）
        val existing = bookDao.getShelfBooksOnce().associateBy { it.bookId }
        val upserts = shelf.books.map { sb ->
            val e = existing[sb.bookId]
            if (e == null) {
                BookEntity(
                    bookId = sb.bookId,
                    title = sb.title,
                    author = sb.author,
                    coverUrl = sb.cover,
                    category = sb.category,
                    createdAt = now,
                    updatedAt = now
                )
            } else {
                e.copy(
                    title = sb.title,
                    author = sb.author,
                    coverUrl = sb.cover,
                    category = sb.category,
                    onShelf = true,
                    removed = false,
                    updatedAt = now
                )
            }
        }
        bookDao.upsertAll(upserts)

        // 3. 下架标记（保留历史与手动定价；单事务避免逐本写触发数据库通知风暴）
        val shelfIds = shelf.books.map { it.bookId }.toSet()
        val removedIds = existing.values.filter { it.bookId !in shelfIds }.map { it.bookId }
        if (removedIds.isNotEmpty()) {
            db.withTransaction {
                removedIds.forEach { bookDao.markRemoved(it, now) }
            }
        }

        // 3.5 自愈：历史版本曾把 0 元误标为官方价（自导入/网文等无价书），重置为未定价
        bookDao.resetWereadZeroPrice(now)

        // 4. 官方 notebooks 批量定价（不覆盖 MANUAL；仅 0 元与无价格字段的书跳过，保持未定价）。
        // 所有书都参与：33 开头的导入书在 notebooks 里同样有正版匹配价（实测 centPrice 2000-7799）
        var pricedCount = 0
        when (val r = client.notebooks(key)) {
            is GatewayResult.Ok -> db.withTransaction {
                r.data.forEach { p ->
                    if (!p.hasPrice || p.priceFen <= 0) return@forEach
                    bookDao.updatePrice(p.bookId, p.priceFen, PriceSource.WEREAD, now)
                    cachePrice(p.bookId, p.priceFen, now)
                    pricedCount++
                }
            }
            is GatewayResult.Err -> { /* 定价失败不阻塞同步 */ }
            is GatewayResult.UpgradeRequired -> return SyncResult.UpgradeRequired(r.message)
        }

        // 4.5 扫码登录 Cookie 通道补全定价（防御式：仅显式含 centPrice 字段的响应才写入；
        // 自导入书籍跳过——系统内无定价数据，保持未定价由用户手动处理）
        var priceWarning: String? = null
        if (!cookie.isNullOrBlank()) {
            val unpriced = bookDao.getUnpriced().take(MAX_PRICE_FETCH)
            val unpricedIds = unpriced.map { it.bookId }.toSet()
            var batchHandled = false
            val pendingPrices = mutableListOf<Pair<String, Long>>()

            // 通道 A：网页版书架同步（可能一次携带全量价格）
            try {
                val shelfResp = privateApi.webShelfSync(cookie, REFERER)
                if (shelfResp.lng("errcode") == 0L && shelfResp.lng("errCode") == 0L) {
                    val arr = shelfResp["books"] as? JsonArray
                    var found = 0
                    if (arr != null) {
                        arr.forEach { el ->
                            val b = el as? JsonObject ?: return@forEach
                            val id = b.str("bookId")
                            if (id.isBlank() || id !in unpricedIds) return@forEach
                            val fen = (b["centPrice"] as? JsonPrimitive)?.longOrNull ?: return@forEach
                            pendingPrices += id to fen
                            found++
                        }
                    }
                    // 实测网页书架接口不带 centPrice 字段：仅批量命中时才跳过逐本通道，否则继续通道 B 兜底
                    batchHandled = found > 0
                }
            } catch (_: Exception) {
                // 通道 A 不可用，尝试通道 B
            }

            // 通道 B：逐本（与单书补价完全同一逻辑；400ms 节流防限流；单本失败跳过，不阻塞整批）
            if (!batchHandled) {
                var failed = 0
                for ((idx, b) in unpriced.withIndex()) {
                    tracker.update(15 + (idx + 1) * 35 / unpriced.size, "获取书籍价格 ${idx + 1}/${unpriced.size}")
                    if (idx > 0) delay(PRICE_FETCH_THROTTLE_MS)
                    // 每批之间额外休息，降低连续请求触发风控的概率
                    if (idx > 0 && idx % PRICE_BATCH_SIZE == 0) delay(PRICE_BATCH_PAUSE_MS)
                    val fen = fetchPriceFenViaPrivate(b.bookId, cookie)
                    if (fen == null) {
                        failed++
                        continue
                    }
                    pendingPrices += b.bookId to fen
                }
                if (failed > 0 && pendingPrices.isEmpty()) {
                    priceWarning = "仍有 ${failed} 本未能获取官方价格，可手动补录"
                }
            }
            // 批量落库（单事务，避免逐本写触发数据库通知风暴）
            if (pendingPrices.isNotEmpty()) {
                db.withTransaction {
                    pendingPrices.forEach { (id, fen) ->
                        bookDao.updatePrice(id, fen, PriceSource.WEREAD, now)
                        cachePrice(id, fen, now)
                    }
                }
                pricedCount += pendingPrices.size
            }
            refreshNickname(cookie)
        }

        // 5. 增量进度（记录快照，价值由第 7 步本地重建）
        val booksAfter = bookDao.getShelfBooksOnce().associateBy { it.bookId }
        val today = LocalDate.now()
        val needList = shelf.books.filter { sb ->
            val local = booksAfter[sb.bookId] ?: return@filter true
            !(local.progressFetchedAt > 0 && local.progressFetchedAt >= sb.readUpdateTime)
        }
        tracker.update(50, "获取阅读进度 0/${needList.size}")

        // 批量落库缓冲：逐本写会触发数据库通知风暴（万本量级时 UI 卡死），
        // 每 PROGRESS_BATCH_SIZE 本在一个事务中写入一次
        val progressBatch = mutableListOf<Pair<BookEntity, ProgressDto>>()
        suspend fun flushProgressBatch() {
            if (progressBatch.isEmpty()) return
            db.withTransaction {
                progressBatch.forEach { (local, p) ->
                    // 已读完（finishTime>0 或书架 finishReading 标记）：
                    // 远端进度 ≥99 或 =0（部分读完书微信读书不返回进度数据）都按 100% 计，
                    // 修复"已读完显示 99%/0%"；进度明显回落（如重读）按实际进度计
                    val finishedNow = p.finished || local.finished
                    val remoteEff = when {
                        finishedNow && p.progressRatio >= 0.99 -> 1.0
                        finishedNow && p.progressRatio <= 0.0 -> 1.0
                        else -> p.progressRatio
                    }
                    // 手动调节过的进度与远端取较长者：手动进度不会被同步覆盖回退；
                    // 读完（remoteEff=1.0）时 max 天然回到 100%
                    val eff = maxOf(local.progress, remoteEff)
                    // 远端进度覆盖了手动进度时清除「手动」标记
                    if (remoteEff > local.progress) bookDao.clearProgressManual(local.bookId)
                    bookDao.updateProgress(
                        bookId = local.bookId,
                        progress = eff,
                        finished = finishedNow,
                        totalReadSeconds = maxOf(local.totalReadSeconds, p.recordReadingTime),
                        lastReadAt = maxOf(local.lastReadAt, p.updateTime),
                        ts = now,
                        fetchedAt = now
                    )
                    readHistoryDao.upsertAll(
                        listOf(ReadHistoryEntity(bookId = local.bookId, date = today.toString(), progress = eff))
                    )
                }
            }
            progressBatch.clear()
        }

        var rateLimitedStreak = 0
        var done = 0
        try {
            for (sb in needList) {
                val local = booksAfter[sb.bookId] ?: continue
                val fetch = fetchProgressResilient(key, sb.bookId)
                val p = fetch.progress
                if (p == null) {
                    if (fetch.rateLimited) {
                        rateLimitedStreak++
                        if (rateLimitedStreak >= RATE_LIMIT_STREAK_BREAK) {
                            // 风控持续触发：中断本阶段，剩余进度下次同步补
                            tracker.update(89, "接口持续限流，剩余进度下次同步补充")
                            break
                        }
                    }
                    continue
                }
                rateLimitedStreak = 0
                done++
                // 书架标记已读完的书同样按读完处理（本地 finished 标记在批量落库时合并）
                val localForBatch = if (sb.finishReading && !local.finished) local.copy(finished = true) else local
                progressBatch += localForBatch to p
                if (progressBatch.size >= PROGRESS_BATCH_SIZE) {
                    flushProgressBatch()
                    delay(PROGRESS_BATCH_PAUSE_MS) // 分批休息，避免连续请求触发风控
                }
                // 进度条每 5 本更新一次：万本量级时避免墨水屏频繁重绘
                if (done % 5 == 0 || done == needList.size) {
                    tracker.update(50 + done * 40 / needList.size, "获取阅读进度 $done/${needList.size}")
                }
            }
        } catch (e: UpgradeException) {
            flushProgressBatch() // 已获取的进度仍落库
            return SyncResult.UpgradeRequired(e.message ?: "微信读书 Skill 版本已升级")
        }
        flushProgressBatch()

        // 6. readdata 回溯（提供按日权重；顺带收集时长榜书单）
        val longestBooks = backfillReadSeconds(key, today)
        tracker.update(91, "阅读统计获取完成")

        // 6.5 补捞读完移出书架的书（readLongest 与书架状态无关）
        try {
            fetchLongestBooks(key, cookie, longestBooks, now)
        } catch (e: UpgradeException) {
            return SyncResult.UpgradeRequired(e.message ?: "微信读书 Skill 版本已升级")
        }
        tracker.update(92, "已移出书架书籍获取完成")

        // 7. 本地重建每日价值（含逐书明细；幂等，定价变化与历史数据都能正确反映）
        rebuildDailyValues(today)
        tracker.update(97, "计算回本价值完成")

        // 同步统计口径排除隐藏书（隐藏书数据仍同步保持新鲜，但不计入价值统计）
        val booksFinal = bookDao.getShelfBooksOnce().filter { !it.hidden }
        val totalValueFen = booksFinal.sumOf { (it.progress * it.priceFen).toLong() }
        // 0 元的书（无论来源）归入"获取价格失败"
        val pricedCountFinal = booksFinal.count { it.priceSource != PriceSource.NONE && it.priceFen > 0 }
        val unpricedCount = booksFinal.size - pricedCountFinal

        return SyncResult.Success(
            bookCount = booksFinal.size,
            pricedCount = pricedCountFinal,
            unpricedCount = unpricedCount,
            totalValueFen = totalValueFen,
            priceWarning = priceWarning
        )
    }

    /** 书架同步：限流(403/429)时退避重试，风控常在上一轮大量请求后发生 */
    private suspend fun shelfSyncWithRetry(key: String): GatewayResult<ShelfSyncDto> {
        var attempt = 0
        while (true) {
            when (val r = client.shelfSync(key)) {
                is GatewayResult.Err -> {
                    if (isRateLimited(r.errcode) && attempt < SHELF_RETRY_MAX) {
                        attempt++
                        tracker.updateLabel("接口限流，稍候重试 $attempt/$SHELF_RETRY_MAX")
                        delay(RATE_LIMIT_BACKOFF_MS * attempt)
                        continue
                    }
                    return r
                }
                else -> return r
            }
        }
    }

    /**
     * 拉取单本进度（限流友好）：403/429 退避等待后重试一次；
     * 仍失败返回 null（该本跳过不阻塞整体）；Skill 升级提示向上抛出。
     * [rateLimited] 标记失败原因是否为限流（普通网络失败不计入限流中断计数）。
     */
    private suspend fun fetchProgressResilient(key: String, bookId: String): ProgressFetch {
        val first = client.getProgress(key, bookId)
        if (first is GatewayResult.UpgradeRequired) throw UpgradeException(first.message)
        if (first !is GatewayResult.Err || !isRateLimited(first.errcode)) {
            return ProgressFetch((first as? GatewayResult.Ok)?.data, rateLimited = false)
        }
        tracker.updateLabel("接口限流，暂停 ${RATE_LIMIT_BACKOFF_MS / 1000} 秒后继续")
        delay(RATE_LIMIT_BACKOFF_MS)
        return when (val retry = client.getProgress(key, bookId)) {
            is GatewayResult.Ok -> ProgressFetch(retry.data, rateLimited = false)
            is GatewayResult.UpgradeRequired -> throw UpgradeException(retry.message)
            else -> ProgressFetch(null, rateLimited = true)
        }
    }

    /** 单本进度拉取结果（null = 失败跳过；rateLimited 区分限流与普通失败） */
    private class ProgressFetch(val progress: ProgressDto?, val rateLimited: Boolean)

    private fun isRateLimited(code: Int): Boolean = code == 403 || code == 429

    /** 进度拉取途中收到 Skill 版本升级提示（必须中止同步并提示用户） */
    private class UpgradeException(message: String?) : Exception(message)

    /**
     * 单书重新同步微信读书阅读进度：**纯查询**（编辑弹窗点「保存」时才落库），
     * 读完判定与全量同步一致（finishTime>0 且远端进度 ≥99 或 =0 按 100%）。
     */
    suspend fun resyncBookProgress(bookId: String, key: String): ResyncProgressResult {
        val r = client.getProgress(key, bookId)
        if (r !is GatewayResult.Ok) {
            return when (r) {
                is GatewayResult.Err -> ResyncProgressResult.Failed(
                    if (r.errcode == -1) "无网络连接，请检查网络后重试"
                    else "获取进度失败：${r.message}"
                )
                is GatewayResult.UpgradeRequired -> ResyncProgressResult.Failed(r.message)
                is GatewayResult.Ok -> error("unreachable")
            }
        }
        val p = r.data
        // 已读完：远端进度 ≥99 或 =0（部分读完书不返回进度数据）都按 100% 计
        val eff = when {
            p.finished && p.progressRatio >= 0.99 -> 1.0
            p.finished && p.progressRatio <= 0.0 -> 1.0
            else -> p.progressRatio
        }
        return ResyncProgressResult.Success((eff * 100).toInt(), p.finished)
    }

    /**
     * 保存单书同步获取的阅读进度（编辑弹窗点「保存」时调用）：
     * 以微信读书进度写入、清除「手动」标记并记录当天进度快照。
     */
    suspend fun applyRestoredProgress(bookId: String, progressPct: Int) {
        val now = System.currentTimeMillis()
        val progress = progressPct.coerceIn(0, 100) / 100.0
        bookDao.updateBookProgress(bookId, progress, progressPct >= 100, now)
        bookDao.clearProgressManual(bookId)
        readHistoryDao.upsertAll(
            listOf(ReadHistoryEntity(bookId = bookId, date = LocalDate.now().toString(), progress = progress))
        )
    }

    /**
     * 批量恢复微信读书阅读进度：逐本以远端为准写库（读完规则与全量同步一致），
     * 覆盖后清除「手动」标记；限流退避与批量落库同全量同步；结果供 UI 反馈。
     */
    suspend fun resyncBooksProgress(
        bookIds: List<String>,
        key: String,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): BatchProgressResult {
        val now = System.currentTimeMillis()
        val today = LocalDate.now()
        val titlesById = bookDao.getTitlesByIds(bookIds)
        val failedBooks = mutableListOf<Pair<String, String>>()
        var ok = 0
        var notice: String? = null
        val total = bookIds.size
        val pending = mutableListOf<Pair<BookEntity, ProgressDto>>()

        suspend fun flush() {
            if (pending.isEmpty()) return
            db.withTransaction {
                pending.forEach { (local, p) ->
                    // 已读完：远端进度 ≥99 或 =0 都按 100% 计
                    val eff = when {
                        p.finished && p.progressRatio >= 0.99 -> 1.0
                        p.finished && p.progressRatio <= 0.0 -> 1.0
                        else -> p.progressRatio
                    }
                    bookDao.updateProgress(
                        bookId = local.bookId,
                        progress = eff,
                        finished = p.finished,
                        totalReadSeconds = maxOf(local.totalReadSeconds, p.recordReadingTime),
                        lastReadAt = maxOf(local.lastReadAt, p.updateTime),
                        ts = now,
                        fetchedAt = now
                    )
                    bookDao.clearProgressManual(local.bookId)
                    readHistoryDao.upsertAll(
                        listOf(ReadHistoryEntity(bookId = local.bookId, date = today.toString(), progress = eff))
                    )
                }
            }
            pending.clear()
        }

        try {
            for (bookId in bookIds) {
                val local = bookDao.getById(bookId)
                if (local == null) {
                    failedBooks += bookId to (titlesById[bookId] ?: "未知书名")
                    onProgress(ok + failedBooks.size, total)
                    continue
                }
                val p = fetchProgressResilient(key, bookId).progress
                if (p == null) {
                    failedBooks += bookId to (titlesById[bookId] ?: "未知书名")
                    onProgress(ok + failedBooks.size, total)
                    continue
                }
                ok++
                pending += local to p
                onProgress(ok + failedBooks.size, total)
                if (pending.size >= PROGRESS_BATCH_SIZE) {
                    flush()
                    delay(PROGRESS_BATCH_PAUSE_MS)
                }
            }
        } catch (e: UpgradeException) {
            // Skill 版本升级：剩余书全部计失败，提示用户更新
            notice = e.message ?: "微信读书 Skill 版本已升级"
            bookIds.drop(ok + failedBooks.size).forEach { id ->
                failedBooks += id to (titlesById[id] ?: "未知书名")
            }
        }
        flush()
        return BatchProgressResult(ok, failedBooks.size, failedBooks, notice)
    }

    /**
     * 批量获取选中书籍的微信读书官方定价（直接写库，强制覆盖手动价、来源恢复微信读书）。
     * 通道：官方 notebooks 一次批量 → 私有单书接口逐本兜底（400ms 节流）；
     * 无 Cookie 时私有通道跳过（对应书计失败）。返回成功/失败数量供 UI 反馈；
     * [onProgress] 每处理一本回调（已处理数, 总数），供 UI 显示 1/N 进度。
     */
    suspend fun resyncBooksPrice(
        bookIds: List<String>,
        key: String,
        cookie: String?,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): BatchPriceResult {
        val now = System.currentTimeMillis()
        val total = bookIds.size
        var ok = 0
        var failed = 0
        var done = 0
        val titlesById = bookDao.getTitlesByIds(bookIds)
        val failedBooks = mutableListOf<Pair<String, String>>()
        fun recordFailed(id: String) {
            failed++
            done++
            failedBooks += id to (titlesById[id] ?: "未知书名")
            onProgress(done, total)
        }

        // 通道 1：官方 notebooks 一次请求批量命中（与全量同步定价同源）
        val notebookPrices = mutableMapOf<String, Long>()
        when (val r = client.notebooks(key)) {
            is GatewayResult.Ok -> r.data.forEach { p ->
                if (p.hasPrice && p.priceFen > 0) notebookPrices[p.bookId] = p.priceFen
            }
            else -> { /* notebooks 失败不阻塞，转私有通道 */ }
        }
        val notebookHits = mutableListOf<Pair<String, Long>>()
        val pending = mutableListOf<String>()
        for (id in bookIds) {
            val fen = notebookPrices[id]
            if (fen != null) notebookHits += id to fen else pending += id
        }
        if (notebookHits.isNotEmpty()) {
            db.withTransaction {
                notebookHits.forEach { (id, fen) ->
                    // 恢复定价：强制覆盖手动价，来源恢复为微信读书
                    bookDao.updatePriceForce(id, fen, PriceSource.WEREAD, now)
                    cachePrice(id, fen, now)
                }
            }
            ok += notebookHits.size
            done += notebookHits.size
            onProgress(done, total)
        }

        // 通道 2：私有单书接口逐本兜底（400ms 节流防限流）
        if (!cookie.isNullOrBlank() && pending.isNotEmpty()) {
            val privateHits = mutableListOf<Pair<String, Long>>()
            for ((idx, id) in pending.withIndex()) {
                if (idx > 0) delay(PRICE_FETCH_THROTTLE_MS)
                val fen = fetchPriceFenViaPrivate(id, cookie)
                if (fen == null) {
                    recordFailed(id)
                    continue
                }
                done++
                onProgress(done, total)
                privateHits += id to fen
            }
            if (privateHits.isNotEmpty()) {
                db.withTransaction {
                    privateHits.forEach { (id, fen) ->
                        // 恢复定价：强制覆盖手动价，来源恢复为微信读书
                        bookDao.updatePriceForce(id, fen, PriceSource.WEREAD, now)
                        cachePrice(id, fen, now)
                    }
                }
                ok += privateHits.size
            }
        } else {
            pending.forEach { recordFailed(it) }
        }
        return BatchPriceResult(ok, failed, failedBooks)
    }

    /**
     * 单书查询微信读书官方价格（纯查询，不落库——由编辑页「保存」时才写入）。
     * 通道：官方 notebooks → 私有单书接口（与全量补价完全同一逻辑）；全部未命中返回 NotFound。
     */
    suspend fun resyncBookPrice(bookId: String, key: String, cookie: String?): ResyncPriceResult {
        // 通道 1：官方 notebooks（有笔记的书；0 元不算官方价）
        try {
            val r = client.notebooks(key)
            if (r is GatewayResult.Ok) {
                r.data.find { it.bookId == bookId }?.let { p ->
                    if (p.hasPrice && p.priceFen > 0) {
                        return ResyncPriceResult.Success(p.priceFen)
                    }
                }
            }
        } catch (_: Exception) { /* 下一通道 */ }

        // 通道 2：私有单书接口（网页版优先、子域兜底）
        if (!cookie.isNullOrBlank()) {
            val fen = fetchPriceFenViaPrivate(bookId, cookie)
            if (fen != null) {
                return ResyncPriceResult.Success(fen)
            }
        }
        // 自导入书（33/CB_ 开头）系统内无定价数据，给出明确提示而非笼统的"未找到"
        return if (isImportedBookId(bookId)) {
            ResyncPriceResult.NotFound("自导入书籍无官方价格，可手动录入价格")
        } else {
            ResyncPriceResult.NotFound()
        }
    }

    /** 是否自导入书：上传文件 bookId 以 33 开头，导入匹配内容库以 CB_ 开头（与 Book.isImported 同口径） */
    private fun isImportedBookId(bookId: String): Boolean =
        bookId.startsWith("33") || bookId.startsWith("CB_")

    /**
     * 私有通道单本取价（分）：网页版优先、子域兜底。
     * 单本异常/无价/0 元返回 null，不抛出——批量补价时单本失败不阻塞后续。
     */
    private suspend fun fetchPriceFenViaPrivate(bookId: String, cookie: String): Long? =
        fetchPrivateMeta(bookId, cookie).priceFen

    /** 私有单书接口查询（网页版优先、子域兜底）：价格 + 作者，一次请求两者同取 */
    private suspend fun fetchPrivateMeta(bookId: String, cookie: String): PrivateBookMeta {
        val web = runCatching { privateApi.webBookInfo(bookId, cookie, REFERER) }.getOrNull()
        if (web != null && web.lng("errcode") == 0L && web.lng("errCode") == 0L) {
            return PrivateBookMeta(web.priceFenOrNull()?.takeIf { it > 0 }, web.pickAuthor())
        }
        val sub = runCatching { privateApi.bookInfo(bookId, cookie) }.getOrNull()
        if (sub != null && sub.lng("errcode") == 0L) {
            return PrivateBookMeta(sub.priceFenOrNull()?.takeIf { it > 0 }, sub.pickAuthor())
        }
        return PrivateBookMeta(null, "")
    }

    /** 作者字段可能在顶层，也可能包在 data 层（网页版/子域结构不完全一致） */
    private fun JsonObject.pickAuthor(): String {
        str("author").takeIf { it.isNotBlank() }?.let { return it }
        return (this["data"] as? JsonObject)?.str("author") ?: ""
    }

    /** 尽力获取登录账号昵称（私有接口，失败不影响同步） */
    private suspend fun refreshNickname(cookie: String) {
        try {
            val info = privateApi.userInfo(cookie)
            val ui = info["userInfo"] as? JsonObject
            val name = ui?.str("name")?.takeIf { it.isNotBlank() }
                ?: ui?.str("nickname")?.takeIf { it.isNotBlank() }
                ?: info.str("name").takeIf { it.isNotBlank() }
            if (!name.isNullOrBlank()) prefs.setAccountName(name)
        } catch (_: Exception) {
            // 昵称获取失败不影响同步
        }
    }

    private suspend fun cachePrice(bookId: String, priceFen: Long, now: Long) {
        priceCacheDao.upsert(
            PriceCacheEntity(
                cacheKey = bookId,
                priceFen = priceFen,
                source = PriceSource.WEREAD,
                fetchedAt = now,
                expiresAt = now + TTL_PRICE_MS
            )
        )
    }

    /**
     * readdata 历史回溯：本月总是刷新；历史月份有数据则跳过。
     * 最多回溯 144 个月（12 年，覆盖微信读书 2015 年上线以来全部历史）；
     * 连续 6 个空月提前终止（用户长期未读才停止，中途断档不影响）；
     * 注册时间之前的月份直接停止。
     */
    /** @return readLongest 时长榜书单（去重，供补捞读完移出书架的书） */
    private suspend fun backfillReadSeconds(key: String, today: LocalDate): List<LongestBookDto> {
        val zone = ZoneId.systemDefault()
        val currentMonth = today.withDayOfMonth(1)
        val longest = mutableMapOf<String, LongestBookDto>()
        var emptyStreak = 0
        for (offset in 0..BACKFILL_MONTHS) {
            val monthStart = currentMonth.minusMonths(offset.toLong())
            val prefix = monthStart.toString().take(7)
            if (offset > 0 && dailyStatDao.hasReadSecondsInMonth(prefix) > 0) {
                emptyStreak = 0 // 该月有本地阅读数据，非空月
                continue
            }
            // 注册时间之前的整月无阅读数据，直接停止（避免为空历史浪费请求）
            val regist = prefs.registTime.first()
            if (regist > 0 && monthStart.plusMonths(1).atStartOfDay(zone).toEpochSecond() <= regist) break
            tracker.update(90, "获取阅读统计 ${offset + 1}/${BACKFILL_MONTHS + 1}")

            val baseTime = monthStart.atStartOfDay(zone).toEpochSecond()
            val r = client.readMonthly(key, baseTime)
            if (r !is GatewayResult.Ok) {
                if (offset > 0) break
                continue
            }
            if (r.data.dailySeconds.isEmpty() && offset > 0) {
                emptyStreak++
                if (emptyStreak >= EMPTY_MONTHS_LIMIT) break
                continue
            }
            emptyStreak = 0
            // 注册时间（账号特性标识，随当前月份响应一起返回）
            if (r.data.registTime > 0 && prefs.registTime.first() == 0L) {
                prefs.setRegistTime(r.data.registTime)
            }
            r.data.longestBooks.forEach { longest.putIfAbsent(it.bookId, it) }
            r.data.dailySeconds.forEach { (date, seconds) ->
                if (seconds <= 0) return@forEach
                val cur = dailyStatDao.get(date)
                dailyStatDao.upsert(
                    DailyStatEntity(
                        date = date,
                        valueFen = cur?.valueFen ?: 0L,
                        readSeconds = seconds,
                        bookCount = cur?.bookCount ?: 0
                    )
                )
            }
        }
        return longest.values.toList()
    }

    /**
     * 补捞时长榜书单：不在本地 books 表的书（读完即移出书架、从未在书架同步过）
     * 补建记录 + 补进度（官方 getprogress 按 bookId 查询，不限书架）+ 补价。
     * 补建后 removed=1 且 progress>0，进入价值统计口径（observeReadBooks）。
     * 网络逐本获取，落库批量执行（万本量级时逐本写会阻塞 UI）。
     */
    private suspend fun fetchLongestBooks(
        key: String,
        cookie: String?,
        longest: List<LongestBookDto>,
        now: Long
    ) {
        val pending = mutableListOf<PendingLongestBook>()
        suspend fun flushPending() {
            if (pending.isEmpty()) return
            db.withTransaction {
                pending.forEach { pl ->
                    bookDao.upsertAll(listOf(pl.entity))
                    pl.progress?.let { p ->
                        bookDao.updateProgress(
                            bookId = pl.entity.bookId,
                            progress = p.effectiveRatio,
                            finished = p.finished,
                            totalReadSeconds = p.recordReadingTime,
                            lastReadAt = p.updateTime,
                            ts = now,
                            fetchedAt = now
                        )
                    }
                    if (pl.author.isNotBlank()) {
                        bookDao.updateAuthorIfBlank(pl.entity.bookId, pl.author, now)
                    }
                    pl.priceFen?.let { fen ->
                        bookDao.updatePrice(pl.entity.bookId, fen, PriceSource.WEREAD, now)
                        cachePrice(pl.entity.bookId, fen, now)
                    }
                }
            }
            pending.clear()
        }

        for ((idx, lb) in longest.withIndex()) {
            if (idx > 0) delay(PRICE_FETCH_THROTTLE_MS) // 补捞书多时节流防限流
            // 补捞逐本进度（含补进度与补价，避免进度条冻结在"统计回溯完成"；每 5 本更新防重绘）
            if (idx % 5 == 0 || idx == longest.size - 1) {
                tracker.update(91 + (idx + 1) / longest.size, "获取已移出书架书籍信息 ${idx + 1}/${longest.size}")
            }
            if (bookDao.getById(lb.bookId) != null) continue
            val entity = BookEntity(
                bookId = lb.bookId,
                title = lb.title.ifBlank { "未知书名" },
                onShelf = false,
                removed = true,
                createdAt = now,
                updatedAt = now
            )
            // 限流时退避重试一次；单本失败不影响其他；UpgradeRequired 向上抛出
            val progress = fetchProgressResilient(key, lb.bookId).progress
            var author = ""
            var priceFen: Long? = null
            if (!cookie.isNullOrBlank()) {
                val meta = fetchPrivateMeta(lb.bookId, cookie)
                // 补捞书补作者（书值页正常显示作者，替代「已移出书架」标注）
                author = meta.author
                priceFen = meta.priceFen
            }
            pending += PendingLongestBook(entity, progress, author, priceFen)
            if (pending.size >= PROGRESS_BATCH_SIZE) {
                flushPending()
                delay(PROGRESS_BATCH_PAUSE_MS)
            }
        }
        flushPending()
    }

    /** 补捞书待落库数据（网络逐本获取，落库批量执行） */
    private class PendingLongestBook(
        val entity: BookEntity,
        val progress: ProgressDto?,
        val author: String,
        val priceFen: Long?
    )

    /**
     * 本地重建每日价值（幂等）：清空后按 read_history 进度快照逐段重放，
     * 每段 Δ价值按每日阅读秒数加权分布到各天，同时重建逐书明细。
     * 纯本地计算，无需网络。
     * 实现：全量数据一次性载入内存聚合，最后单事务落库——
     * 万本量级时避免逐本查询/逐条写造成的分钟级卡顿。
     */
    private suspend fun rebuildDailyValues(today: LocalDate) {
        val dailySecMap = dailyStatDao.getAllOnce().associate { it.date to it.readSeconds }
        val rowsByBook = readHistoryDao.getAllValuedBooks().groupBy { it.bookId }
        val window = windowStart(today)

        // 内存聚合（valueFen/bookCount 从零开始累加，等价于先 resetValues 再逐条累加）
        val dayValue = mutableMapOf<String, Long>()
        val dayCount = mutableMapOf<String, Int>()
        val dayBookValue = mutableMapOf<Pair<String, String>, Long>()
        fun add(date: LocalDate, valueFen: Long, bookId: String) {
            val key = date.toString()
            dayValue[key] = (dayValue[key] ?: 0L) + valueFen
            dayCount[key] = (dayCount[key] ?: 0) + 1
            val bookKey = key to bookId
            dayBookValue[bookKey] = (dayBookValue[bookKey] ?: 0L) + valueFen
        }

        for (b in bookDao.getReadBooksOnce()) {
            if (b.priceFen <= 0) continue
            val rows = rowsByBook[b.bookId].orEmpty()
            var prevProgress = 0.0
            var prevDate = window
            for (row in rows) {
                val delta = row.progress - prevProgress
                if (delta > 0) {
                    distribute(
                        ValueDelta(b.bookId, (delta * b.priceFen).toLong(), maxOf(prevDate.plusDays(1), window)),
                        today, dailySecMap, ::add
                    )
                }
                prevProgress = row.progress
                prevDate = LocalDate.parse(row.date)
            }
            // 尾段：最后一次快照到当前最新进度
            val tail = b.progress - prevProgress
            if (tail > 0) {
                distribute(
                    ValueDelta(b.bookId, (tail * b.priceFen).toLong(), maxOf(prevDate.plusDays(1), window)),
                    today, dailySecMap, ::add
                )
            }
        }

        // 单事务落库：清空明细 + 重置价值 + 批量写入聚合结果
        db.withTransaction {
            dailyBookStatDao.deleteAll()
            dailyStatDao.resetValues()
            dailyStatDao.upsertAll(
                dayValue.map { (date, v) ->
                    DailyStatEntity(
                        date = date,
                        valueFen = v,
                        readSeconds = dailySecMap[date] ?: 0L,
                        bookCount = dayCount[date] ?: 0
                    )
                }
            )
            dailyBookStatDao.upsertAll(
                dayBookValue.map { (key, v) ->
                    DailyBookStatEntity(date = key.first, bookId = key.second, valueFen = v)
                }
            )
        }
    }

    /**
     * 把一本书的 Δ价值分布到 (fromDate, today]：
     * - 区间 ≤ 7 天：按每日阅读秒数加权分摊（频繁同步时平滑）
     * - 区间 > 7 天：整段归到区间最后一天（快照日），避免价值摊到没读该书的日期
     * 纯函数：读秒数查内存映射，结果经 [add] 回调聚合（不再逐条访问数据库）。
     */
    private fun distribute(
        v: ValueDelta,
        today: LocalDate,
        dailySecMap: Map<String, Long>,
        add: (LocalDate, Long, String) -> Unit
    ) {
        val days = mutableListOf<LocalDate>()
        var d = v.fromDate
        while (!d.isAfter(today)) {
            days += d
            d = d.plusDays(1)
        }
        if (days.size > 7) {
            add(days.last(), v.valueFen, v.bookId)
            return
        }
        val totalSec = days.sumOf { dailySecMap[it.toString()] ?: 0L }
        if (totalSec <= 0) {
            add(today, v.valueFen, v.bookId)
            return
        }
        var allocated = 0L
        for (day in days) {
            val sec = dailySecMap[day.toString()] ?: 0L
            if (sec <= 0) continue
            val part = v.valueFen * sec / totalSec
            if (part > 0) {
                add(day, part, v.bookId)
                allocated += part
            }
        }
        val remainder = v.valueFen - allocated
        if (remainder > 0) add(today, remainder, v.bookId)
    }

    private data class ValueDelta(val bookId: String, val valueFen: Long, val fromDate: LocalDate)

    private data class PrivateBookMeta(val priceFen: Long?, val author: String)

    private fun windowStart(today: LocalDate): LocalDate = today.minusMonths(BACKFILL_MONTHS + 1L)

    companion object {
        /** 付费书定价缓存 TTL 24h */
        private const val TTL_PRICE_MS = 24 * 60 * 60 * 1000L
        /** 单次同步私有 API 补价上限（书架全部未定价书都尝试） */
        private const val MAX_PRICE_FETCH = 200
        /** 逐本补价节流（防微信读书接口限流） */
        private const val PRICE_FETCH_THROTTLE_MS = 400L
        /** 私有通道补价分批大小：每批之间额外休息，降低连续请求触发风控概率 */
        private const val PRICE_BATCH_SIZE = 50
        /** 私有通道补价分批休息间隔 */
        private const val PRICE_BATCH_PAUSE_MS = 5_000L
        /** 进度逐本拉取分批大小：每批落库一次并额外休息（防限流 + 防数据库通知风暴） */
        private const val PROGRESS_BATCH_SIZE = 50
        /** 进度分批休息间隔 */
        private const val PROGRESS_BATCH_PAUSE_MS = 10_000L
        /** 限流(403/429)退避等待 */
        private const val RATE_LIMIT_BACKOFF_MS = 30_000L
        /** 连续限流本数达到该值则中断进度拉取，剩余下次同步补 */
        private const val RATE_LIMIT_STREAK_BREAK = 5
        /** 书架同步限流重试次数 */
        private const val SHELF_RETRY_MAX = 2
        /** 历史回溯月数（12 年：覆盖微信读书 2015 年上线至今的全部历史；
         * 注册时间与连续 6 个空月会提前终止，近期用户不会跑满） */
        private const val BACKFILL_MONTHS = 144
        /** 连续空月终止阈值：连续 6 个月无阅读数据才停止向前回溯 */
        private const val EMPTY_MONTHS_LIMIT = 6
        /** 网页版接口 Referer */
        private const val REFERER = "https://weread.qq.com/"
    }
}
