package com.huibenlema.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.huibenlema.app.data.local.entity.BookEntity
import com.huibenlema.app.domain.model.PriceSource
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {

    /** 书架书籍（按贡献价值降序） */
    @Query("SELECT * FROM books WHERE onShelf = 1 AND removed = 0 ORDER BY progress * priceFen DESC")
    fun observeShelfBooks(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE onShelf = 1 AND removed = 0")
    suspend fun getShelfBooksOnce(): List<BookEntity>

    /** 读过的书：书架 + 已移出书架但有进度记录的书（价值统计口径） */
    @Query(
        "SELECT * FROM books WHERE (onShelf = 1 AND removed = 0) OR (removed = 1 AND progress > 0) " +
            "ORDER BY progress * priceFen DESC"
    )
    fun observeReadBooks(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE (onShelf = 1 AND removed = 0) OR (removed = 1 AND progress > 0)")
    suspend fun getReadBooksOnce(): List<BookEntity>

    @Query("SELECT * FROM books WHERE bookId = :bookId")
    suspend fun getById(bookId: String): BookEntity?

    /** 全量导出用（含已下架/软删除） */
    @Query("SELECT * FROM books")
    suspend fun getAllOnce(): List<BookEntity>

    @Query("SELECT * FROM books WHERE bookId IN (:bookIds)")
    suspend fun getByIds(bookIds: List<String>): List<BookEntity>

    /** 未定价书籍（列表页置顶提醒补录） */
    @Query("SELECT * FROM books WHERE onShelf = 1 AND removed = 0 AND priceSource = 'NONE' ORDER BY progress DESC")
    suspend fun getUnpriced(): List<BookEntity>

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
