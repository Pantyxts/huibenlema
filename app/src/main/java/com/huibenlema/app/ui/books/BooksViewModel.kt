package com.huibenlema.app.ui.books

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.huibenlema.app.domain.model.Book
import com.huibenlema.app.domain.repo.BatchPriceResult
import com.huibenlema.app.domain.repo.BatchProgressResult
import com.huibenlema.app.domain.repo.BookRepository
import com.huibenlema.app.domain.repo.ResyncPriceResult
import com.huibenlema.app.domain.repo.ResyncProgressResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class BookSort { VALUE, PROGRESS, PRICE }

/** 书架筛选：全部 / 在书架 / 不在书架（隐藏书入口在设置-数据） */
enum class ShelfFilter(val label: String) { ALL("全部"), ON_SHELF("在书架"), REMOVED("不在书架") }

/** 排序状态：再次点击同一维度切换升/降序 */
data class SortState(val key: BookSort, val ascending: Boolean)

@HiltViewModel
class BooksViewModel @Inject constructor(
    private val repo: BookRepository
) : ViewModel() {

    private val sort = MutableStateFlow(SortState(BookSort.VALUE, ascending = false))
    private val shelfFilter = MutableStateFlow(ShelfFilter.ALL)

    val books: StateFlow<List<Book>> =
        combine(repo.observeReadBooks(), sort, shelfFilter) { list, s, f ->
            val filtered = when (f) {
                ShelfFilter.ALL -> list
                ShelfFilter.ON_SHELF -> list.filter { !it.removed }
                ShelfFilter.REMOVED -> list.filter { it.removed }
            }
            val sorted = when (s.key) {
                BookSort.VALUE -> filtered.sortedBy { it.contributedFen }
                BookSort.PROGRESS -> filtered.sortedBy { it.progress }
                BookSort.PRICE -> filtered.sortedBy { it.priceFen }
            }
            if (s.ascending) sorted else sorted.reversed()
            // 万本量级排序放 Default 线程，避免卡主线程
        }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val sortState: StateFlow<SortState> = sort.asStateFlow()
    val shelfFilterState: StateFlow<ShelfFilter> = shelfFilter.asStateFlow()

    /** 书值总价值（全部书架书籍的贡献价值合计） */
    val totalValueFen: StateFlow<Long> = repo.observeSummary()
        .map { it.totalValueFen }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    /** 点击排序片：同维度切换升/降序，异维度切换为新维度（默认降序） */
    fun toggleSort(key: BookSort) {
        val cur = sort.value
        sort.value = if (cur.key == key) cur.copy(ascending = !cur.ascending)
        else SortState(key, ascending = false)
    }

    /** 点击筛选片：全部 → 在书架 → 不在书架 循环 */
    fun cycleFilter() {
        shelfFilter.value = when (shelfFilter.value) {
            ShelfFilter.ALL -> ShelfFilter.ON_SHELF
            ShelfFilter.ON_SHELF -> ShelfFilter.REMOVED
            ShelfFilter.REMOVED -> ShelfFilter.ALL
        }
    }

    /** 隐藏/恢复书籍（隐藏书不参与任何计算，仅「隐藏」筛选列表可见） */
    fun setBookHidden(book: Book, hidden: Boolean) {
        viewModelScope.launch { repo.setBookHidden(book.bookId, hidden) }
    }

    /** 批量标记已读完（进度 100%） */
    fun markBooksFinished(bookIds: List<String>) {
        viewModelScope.launch { repo.markBooksFinished(bookIds) }
    }

    /** 批量隐藏/恢复 */
    fun setBooksHidden(bookIds: List<String>, hidden: Boolean) {
        viewModelScope.launch { repo.setBooksHidden(bookIds, hidden) }
    }

    /** 批量删除自定义书籍（SQL 层限定 CUSTOM_ 前缀） */
    fun deleteBooks(bookIds: List<String>) {
        viewModelScope.launch { repo.deleteBooks(bookIds) }
    }

    /** 批量删除自定义书 + 隐藏微信读书书（选中混合集合时一键执行） */
    fun batchDeleteOrHide(books: List<Book>) {
        val customIds = books.filter { it.bookId.startsWith(CUSTOM_BOOK_PREFIX) }.map { it.bookId }
        val wereadIds = books.filter { !it.bookId.startsWith(CUSTOM_BOOK_PREFIX) }.map { it.bookId }
        viewModelScope.launch {
            if (customIds.isNotEmpty()) repo.deleteBooks(customIds)
            if (wereadIds.isNotEmpty()) repo.setBooksHidden(wereadIds, hidden = true)
        }
    }

    /** 批量获取微信读书官方定价，进度与结果经回调返回 */
    fun batchFetchPrice(
        bookIds: List<String>,
        onProgress: (Int, Int) -> Unit,
        onResult: (BatchPriceResult) -> Unit
    ) {
        viewModelScope.launch {
            val result = repo.resyncBooksPrice(bookIds) { done, total -> onProgress(done, total) }
            onResult(result)
        }
    }

    /** 批量恢复微信读书阅读进度（以远端为准），进度与结果经回调返回 */
    fun batchFetchProgress(
        bookIds: List<String>,
        onProgress: (Int, Int) -> Unit,
        onResult: (BatchProgressResult) -> Unit
    ) {
        viewModelScope.launch {
            val result = repo.resyncBooksProgress(bookIds) { done, total -> onProgress(done, total) }
            onResult(result)
        }
    }

    private companion object {
        const val CUSTOM_BOOK_PREFIX = "CUSTOM_"
    }

    /**
     * 保存定价：isOfficial = 官方同步价未修改 → WEREAD；
     * 否则按手动价处理（MANUAL 优先级最高，自动同步不覆盖）。
     */
    fun savePrice(book: Book, priceYuan: String, isOfficial: Boolean) {
        val fen = ((priceYuan.toDoubleOrNull() ?: return).times(100)).toLong()
        if (fen < 0) return
        viewModelScope.launch {
            if (isOfficial) repo.saveOfficialPrice(book.bookId, fen)
            else repo.updatePriceManual(book.bookId, fen)
        }
    }

    /** 重新同步微信读书官方价格（强制覆盖手动价），结果经回调返回 */
    fun resyncOfficialPrice(book: Book, onResult: (ResyncPriceResult) -> Unit) {
        viewModelScope.launch { onResult(repo.resyncOfficialPrice(book.bookId)) }
    }

    /** 单书重新同步微信读书阅读进度（以远端为准，立即生效），结果经回调返回 */
    fun resyncBookProgress(book: Book, onResult: (ResyncProgressResult) -> Unit) {
        viewModelScope.launch { onResult(repo.resyncBookProgress(book.bookId)) }
    }

    /** 添加自定义书籍：书名必填；定价可空（未定价，后续点击补录）；进度 0-100 可调 */
    fun addCustomBook(title: String, author: String, priceYuan: String, progressPct: Int) {
        val trimmed = title.trim()
        if (trimmed.isBlank()) return
        val fen = if (priceYuan.isBlank()) null
        else (priceYuan.toDoubleOrNull()?.times(100))?.toLong()
        if (!priceYuan.isBlank() && fen == null) return // 定价非法输入忽略
        viewModelScope.launch { repo.addCustomBook(trimmed, author.trim(), fen, progressPct) }
    }

    /** 手动调节任意书籍阅读进度（0-100）；同步时未读完的书取本地与远端较长者 */
    fun updateBookProgress(book: Book, progressPct: Int) {
        viewModelScope.launch { repo.updateBookProgress(book.bookId, progressPct) }
    }

    /** 保存单书进度：fromSync=true 表示保存的是「同步微信读书阅读进度」获取的值（不显示手动标记） */
    fun saveProgress(book: Book, progressPct: Int, fromSync: Boolean) {
        viewModelScope.launch {
            if (fromSync) repo.saveRestoredProgress(book.bookId, progressPct)
            else repo.updateBookProgress(book.bookId, progressPct)
        }
    }

    /** 删除自定义书籍 */
    fun deleteBook(book: Book) {
        viewModelScope.launch { repo.deleteBook(book.bookId) }
    }
}
