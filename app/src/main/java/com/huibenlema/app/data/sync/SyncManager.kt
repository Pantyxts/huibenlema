package com.huibenlema.app.data.sync

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
import com.huibenlema.app.data.remote.OfficialGatewayClient
import com.huibenlema.app.data.remote.PrivateWereadApi
import com.huibenlema.app.data.remote.lng
import com.huibenlema.app.data.remote.priceFenOrNull
import com.huibenlema.app.data.remote.str
import com.huibenlema.app.domain.model.PriceSource
import com.huibenlema.app.domain.repo.ResyncPriceResult
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
        // 1. 书架
        val shelf = when (val r = client.shelfSync(key)) {
            is GatewayResult.Ok -> r.data
            is GatewayResult.Err -> return if (r.errcode == -1) SyncResult.NetworkError
            else SyncResult.Failure(r.message)
            is GatewayResult.UpgradeRequired -> return SyncResult.UpgradeRequired(r.message)
        }

        tracker.update(10, "书架已获取")

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

        // 3. 下架标记（保留历史与手动定价）
        val shelfIds = shelf.books.map { it.bookId }.toSet()
        existing.values.filter { it.bookId !in shelfIds }.forEach {
            bookDao.markRemoved(it.bookId, now)
        }

        // 3.5 自愈：历史版本曾把 0 元误标为官方价（自导入/网文等无价书），重置为未定价
        bookDao.resetWereadZeroPrice(now)

        // 4. 官方 notebooks 批量定价（不覆盖 MANUAL；仅 0 元与无价格字段的书跳过，保持未定价）。
        // 所有书都参与：33 开头的导入书在 notebooks 里同样有正版匹配价（实测 centPrice 2000-7799）
        var pricedCount = 0
        when (val r = client.notebooks(key)) {
            is GatewayResult.Ok -> r.data.forEach { p ->
                if (!p.hasPrice || p.priceFen <= 0) return@forEach
                bookDao.updatePrice(p.bookId, p.priceFen, PriceSource.WEREAD, now)
                cachePrice(p.bookId, p.priceFen, now)
                pricedCount++
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
                            bookDao.updatePrice(id, fen, PriceSource.WEREAD, now)
                            cachePrice(id, fen, now)
                            pricedCount++
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
                    tracker.update(15 + (idx + 1) * 35 / unpriced.size, "补价中 ${idx + 1}/${unpriced.size}")
                    if (idx > 0) delay(PRICE_FETCH_THROTTLE_MS)
                    val fen = fetchPriceFenViaPrivate(b.bookId, cookie)
                    if (fen == null) {
                        failed++
                        continue
                    }
                    bookDao.updatePrice(b.bookId, fen, PriceSource.WEREAD, now)
                    cachePrice(b.bookId, fen, now)
                    pricedCount++
                }
                if (failed > 0 && pricedCount == 0) {
                    priceWarning = "仍有 ${failed} 本未能获取官方价格，可手动补录"
                }
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
        tracker.update(50, "同步进度 0/${needList.size}")
        for ((idx, sb) in needList.withIndex()) {
            val local = booksAfter[sb.bookId] ?: continue

            val p = when (val r = client.getProgress(key, sb.bookId)) {
                is GatewayResult.Ok -> r.data
                is GatewayResult.Err -> continue // 单本失败跳过
                is GatewayResult.UpgradeRequired -> return SyncResult.UpgradeRequired(r.message)
            }
            tracker.update(50 + (idx + 1) * 40 / needList.size, "同步进度 ${idx + 1}/${needList.size}")

            bookDao.updateProgress(
                bookId = sb.bookId,
                progress = p.progressRatio,
                finished = p.finished || sb.finishReading || local.finished,
                totalReadSeconds = maxOf(local.totalReadSeconds, p.recordReadingTime),
                lastReadAt = maxOf(local.lastReadAt, p.updateTime),
                ts = now,
                fetchedAt = now
            )
            readHistoryDao.upsertAll(
                listOf(ReadHistoryEntity(bookId = sb.bookId, date = today.toString(), progress = p.progressRatio))
            )
        }

        // 6. readdata 回溯（提供按日权重）
        backfillReadSeconds(key, today)
        tracker.update(92, "统计回溯完成")

        // 7. 本地重建每日价值（含逐书明细；幂等，定价变化与历史数据都能正确反映）
        rebuildDailyValues(today)
        tracker.update(97, "价值重建完成")

        val booksFinal = bookDao.getShelfBooksOnce()
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
        return ResyncPriceResult.NotFound
    }

    /**
     * 私有通道单本取价（分）：网页版优先、子域兜底。
     * 单本异常/无价/0 元返回 null，不抛出——批量补价时单本失败不阻塞后续。
     */
    private suspend fun fetchPriceFenViaPrivate(bookId: String, cookie: String): Long? {
        val web = runCatching { privateApi.webBookInfo(bookId, cookie, REFERER) }.getOrNull()
        if (web != null && web.lng("errcode") == 0L && web.lng("errCode") == 0L) {
            web.priceFenOrNull()?.takeIf { it > 0 }?.let { return it }
        }
        val sub = runCatching { privateApi.bookInfo(bookId, cookie) }.getOrNull()
        if (sub != null && sub.lng("errcode") == 0L) {
            sub.priceFenOrNull()?.takeIf { it > 0 }?.let { return it }
        }
        return null
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
     * 最多回溯 12 个月；连续空月提前终止。
     */
    private suspend fun backfillReadSeconds(key: String, today: LocalDate) {
        val zone = ZoneId.systemDefault()
        val currentMonth = today.withDayOfMonth(1)
        for (offset in 0..BACKFILL_MONTHS) {
            val monthStart = currentMonth.minusMonths(offset.toLong())
            val prefix = monthStart.toString().take(7)
            if (offset > 0 && dailyStatDao.hasReadSecondsInMonth(prefix) > 0) continue

            val baseTime = monthStart.atStartOfDay(zone).toEpochSecond()
            val r = client.readMonthly(key, baseTime)
            if (r !is GatewayResult.Ok) {
                if (offset > 0) break
                continue
            }
            if (r.data.dailySeconds.isEmpty() && offset > 0) break
            // 注册时间（账号特性标识，随当前月份响应一起返回）
            if (r.data.registTime > 0 && prefs.registTime.first() == 0L) {
                prefs.setRegistTime(r.data.registTime)
            }
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
    }

    /**
     * 本地重建每日价值（幂等）：清空后按 read_history 进度快照逐段重放，
     * 每段 Δ价值按每日阅读秒数加权分布到各天，同时重建逐书明细。
     * 纯本地计算，无需网络。
     */
    private suspend fun rebuildDailyValues(today: LocalDate) {
        dailyBookStatDao.deleteAll()
        dailyStatDao.resetValues()
        val window = windowStart(today)
        for (b in bookDao.getShelfBooksOnce()) {
            if (b.priceFen <= 0) continue
            val rows = readHistoryDao.getAllForBook(b.bookId)
            var prevProgress = 0.0
            var prevDate = window
            for (row in rows) {
                val delta = row.progress - prevProgress
                if (delta > 0) {
                    distribute(
                        ValueDelta(b.bookId, (delta * b.priceFen).toLong(), maxOf(prevDate.plusDays(1), window)),
                        today
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
                    today
                )
            }
        }
    }

    /**
     * 把一本书的 Δ价值分布到 (fromDate, today]：
     * - 区间 ≤ 7 天：按每日阅读秒数加权分摊（频繁同步时平滑）
     * - 区间 > 7 天：整段归到区间最后一天（快照日），避免价值摊到没读该书的日期
     */
    private suspend fun distribute(v: ValueDelta, today: LocalDate) {
        val days = mutableListOf<LocalDate>()
        var d = v.fromDate
        while (!d.isAfter(today)) {
            days += d
            d = d.plusDays(1)
        }
        if (days.size > 7) {
            addDailyValue(days.last(), v.valueFen, 1, v.bookId)
            return
        }
        val weights = days.associateWith { dailyStatDao.get(it.toString())?.readSeconds ?: 0L }
        val totalSec = weights.values.sum()
        if (totalSec <= 0) {
            addDailyValue(today, v.valueFen, 1, v.bookId)
            return
        }
        var allocated = 0L
        for (day in days) {
            val sec = weights[day] ?: 0L
            if (sec <= 0) continue
            val part = v.valueFen * sec / totalSec
            if (part > 0) {
                addDailyValue(day, part, 1, v.bookId)
                allocated += part
            }
        }
        val remainder = v.valueFen - allocated
        if (remainder > 0) addDailyValue(today, remainder, 1, v.bookId)
    }

    private suspend fun addDailyValue(date: LocalDate, valueFen: Long, bookCount: Int, bookId: String) {
        val key = date.toString()
        val cur = dailyStatDao.get(key)
        dailyStatDao.upsert(
            DailyStatEntity(
                date = key,
                valueFen = (cur?.valueFen ?: 0L) + valueFen,
                readSeconds = cur?.readSeconds ?: 0L,
                bookCount = (cur?.bookCount ?: 0) + bookCount
            )
        )
        // 每日每书价值明细（柱状图点选展示）
        val bookCur = dailyBookStatDao.getValue(key, bookId) ?: 0L
        dailyBookStatDao.upsertAll(
            listOf(DailyBookStatEntity(date = key, bookId = bookId, valueFen = bookCur + valueFen))
        )
    }

    private data class ValueDelta(val bookId: String, val valueFen: Long, val fromDate: LocalDate)

    private fun windowStart(today: LocalDate): LocalDate = today.minusMonths(BACKFILL_MONTHS + 1L)

    companion object {
        /** 付费书定价缓存 TTL 24h */
        private const val TTL_PRICE_MS = 24 * 60 * 60 * 1000L
        /** 单次同步私有 API 补价上限（书架全部未定价书都尝试） */
        private const val MAX_PRICE_FETCH = 200
        /** 逐本补价节流（防微信读书接口限流） */
        private const val PRICE_FETCH_THROTTLE_MS = 400L
        /** 历史回溯月数 */
        private const val BACKFILL_MONTHS = 12
        /** 网页版接口 Referer */
        private const val REFERER = "https://weread.qq.com/"
    }
}
