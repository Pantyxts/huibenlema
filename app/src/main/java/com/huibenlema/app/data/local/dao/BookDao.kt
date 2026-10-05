package com.huibenlema.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.huibenlema.app.data.local.entity.BookEntity
import com.huibenlema.app.domain.model.PriceSource
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {

    /** 书架书籍（按贡献价值降序；隐藏书不参与计算） */
    @Query("SELECT * FROM books WHERE onShelf = 1 AND removed = 0 AND hidden = 0 ORDER BY progress * priceFen DESC")
    fun observeShelfBooks(): Flow<List<BookEntity>>

    /** 全量书架书籍（同步内部用，含隐藏书——upsert/下架标记不能丢隐藏状态） */
    @Query("SELECT * FROM books WHERE onShelf = 1 AND removed = 0")
    suspend fun getShelfBooksOnce(): List<BookEntity>

    /** 读过的书：书架 + 已移出书架但有进度记录的书 + 自定义书（价值统计口径；
     * 自定义书进度可设为 0%，仍保留在列表与统计口径中；隐藏书不参与任何计算） */
    @Query(
        "SELECT * FROM books WHERE ((onShelf = 1 AND removed = 0) OR (removed = 1 AND progress > 0) " +
            "OR substr(bookId, 1, 7) = 'CUSTOM_') AND hidden = 0 " +
            "ORDER BY progress * priceFen DESC"
    )
    fun observeReadBooks(): Flow<List<BookEntity>>

    @Query(
        "SELECT * FROM books WHERE ((onShelf = 1 AND removed = 0) OR (removed = 1 AND progress > 0) " +
            "OR substr(bookId, 1, 7) = 'CUSTOM_') AND hidden = 0"
    )
    suspend fun getReadBooksOnce(): List<BookEntity>

    /** 用户手动隐藏的书籍（书值页「隐藏」筛选入口展示，可恢复显示） */
    @Query("SELECT * FROM books WHERE hidden = 1 ORDER BY progress * priceFen DESC")
    fun observeHiddenBooks(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE bookId = :bookId")
    suspend fun getById(bookId: String): BookEntity?

    /** 全量导出用（含已下架/软删除） */
    @Query("SELECT * FROM books")
    suspend fun getAllOnce(): List<BookEntity>

    @Query("SELECT * FROM books WHERE bookId IN (:bookIds)")
    suspend fun getByIds(bookIds: List<String>): List<BookEntity>

    /**
     * 批量取 bookId → 书名映射（chunked 防 SQLite IN 参数上限：老设备 999，
     * 万本全选批量操作时直接 IN 会崩溃）。
     */
    suspend fun getTitlesByIds(bookIds: List<String>): Map<String, String> {
        val result = mutableMapOf<String, String>()
        bookIds.chunked(SQL_IN_CHUNK).forEach { chunk ->
            getByIds(chunk).forEach { result[it.bookId] = it.title }
        }
        return result
    }

    companion object {
        /** SQLite IN 参数安全上限（老设备 SQLITE_MAX_VARIABLE_NUMBER=999，取 500 留余量） */
        const val SQL_IN_CHUNK = 500
    }

    /** 未定价书籍（列表页置顶提醒补录；隐藏书不占补价配额） */
    @Query("SELECT * FROM books WHERE onShelf = 1 AND removed = 0 AND hidden = 0 AND priceSource = 'NONE' ORDER BY progress DESC")
    suspend fun getUnpriced(): List<BookEntity>

    /** 隐藏/恢复书籍（用户手动隐藏，不参与任何计算） */
    @Query("UPDATE books SET hidden = :hidden, updatedAt = :ts WHERE bookId = :bookId")
    suspend fun setHidden(bookId: String, hidden: Boolean, ts: Long)

    /** 批量标记已读完（进度 100% + 「手动」标记；单语句批量，避免万本量级逐本写风暴） */
    @Query(
        "UPDATE books SET progress = 1.0, finished = 1, progressManual = 1, " +
            "updatedAt = :ts WHERE bookId IN (:bookIds)"
    )
    suspend fun markFinishedBatch(bookIds: List<String>, ts: Long)

    /** 批量隐藏/恢复 */
    @Query("UPDATE books SET hidden = :hidden, updatedAt = :ts WHERE bookId IN (:bookIds)")
    suspend fun setHiddenBatch(bookIds: List<String>, hidden: Boolean, ts: Long)

    /** 批量删除自定义书籍（限定 CUSTOM_ 前缀，误传微信书也安全） */
    @Query("DELETE FROM books WHERE bookId IN (:bookIds) AND substr(bookId, 1, 7) = 'CUSTOM_'")
    suspend fun deleteCustomBatch(bookIds: List<String>)

    @Upsert
    suspend fun upsertAll(books: List<BookEntity>)

    /** 补作者（仅作者为空时写入；书架同步已有作者的书不受影响） */
    @Query("UPDATE books SET author = :author, updatedAt = :ts WHERE bookId = :bookId AND author = ''")
    suspend fun updateAuthorIfBlank(bookId: String, author: String, ts: Long)

    /** 手动/API 更新定价（MANUAL 优先，API 价不覆盖手动价） */
    @Query(
        "UPDATE books SET priceFen = :priceFen, priceSource = :source, priceUpdatedAt = :ts, updatedAt = :ts " +
            "WHERE bookId = :bookId AND priceSource != 'MANUAL'"
    )
    suspend fun updatePrice(bookId: String, priceFen: Long, source: PriceSource, ts: Long)

    /** 强制覆盖定价（用户主动"同步官方价格"时覆盖 MANUAL） */
    @Query(
        "UPDATE books SET priceFen = :priceFen, priceSource = :source, priceUpdatedAt = :ts, updatedAt = :ts " +
            "WHERE bookId = :bookId"
    )
    suspend fun updatePriceForce(bookId: String, priceFen: Long, source: PriceSource, ts: Long)

    @Query("UPDATE books SET priceFen = :priceFen, priceSource = 'MANUAL', priceUpdatedAt = :ts, updatedAt = :ts WHERE bookId = :bookId")
    suspend fun updatePriceManual(bookId: String, priceFen: Long, ts: Long)

    @Query(
        "UPDATE books SET progress = :progress, finished = :finished, totalReadSeconds = :totalReadSeconds, " +
            "lastReadAt = :lastReadAt, progressFetchedAt = :fetchedAt, updatedAt = :ts WHERE bookId = :bookId"
    )
    suspend fun updateProgress(bookId: String, progress: Double, finished: Boolean, totalReadSeconds: Long, lastReadAt: Long, ts: Long, fetchedAt: Long)

    /** 手动调节任意书籍进度（置「手动」标记，不动阅读时长等同步字段） */
    @Query(
        "UPDATE books SET progress = :progress, finished = :finished, progressManual = 1, " +
            "updatedAt = :ts WHERE bookId = :bookId"
    )
    suspend fun updateBookProgress(bookId: String, progress: Double, finished: Boolean, ts: Long)

    /** 远端进度覆盖手动进度时清除「手动」标记 */
    @Query("UPDATE books SET progressManual = 0 WHERE bookId = :bookId")
    suspend fun clearProgressManual(bookId: String)

    @Query("UPDATE books SET removed = 1, updatedAt = :ts WHERE bookId = :bookId")
    suspend fun markRemoved(bookId: String, ts: Long)

    @Query("UPDATE books SET onShelf = 1, removed = 0, updatedAt = :ts WHERE bookId IN (:bookIds)")
    suspend fun markOnShelf(bookIds: List<String>, ts: Long)

    /** 自愈：历史版本曾把 0 元误标为官方价（自导入/网文等无价书）→ 重置为未定价 */
    @Query("UPDATE books SET priceFen = 0, priceSource = 'NONE', priceUpdatedAt = 0, updatedAt = :ts " +
        "WHERE priceSource = 'WEREAD' AND priceFen = 0")
    suspend fun resetWereadZeroPrice(ts: Long)

    /** 删除单本（手动添加的自定义书籍） */
    @Query("DELETE FROM books WHERE bookId = :bookId")
    suspend fun deleteBook(bookId: String)

    /** 清除全部书值数据 */
    @Query("DELETE FROM books")
    suspend fun deleteAllBooks()

    /** 清除非手动书值数据（保留手动定价的书） */
    @Query("DELETE FROM books WHERE priceSource != 'MANUAL'")
    suspend fun deleteNonManualBooks()

    /** 清除手动书值数据（保留官方定价的书） */
    @Query("DELETE FROM books WHERE priceSource = 'MANUAL'")
    suspend fun deleteManualBooks()
}
