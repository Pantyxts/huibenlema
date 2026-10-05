package com.huibenlema.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.huibenlema.app.data.local.UserPrefs
import com.huibenlema.app.domain.model.DailyStat
import com.huibenlema.app.domain.model.PaybackSummary
import com.huibenlema.app.domain.repo.BookRepository
import com.huibenlema.app.domain.repo.SyncResult
import com.huibenlema.app.data.sync.SyncProgress
import com.huibenlema.app.ui.components.PieSlice
import com.huibenlema.app.ui.components.formatFen
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repo: BookRepository,
    private val prefs: UserPrefs
) : ViewModel() {

    init {
        // 启动自动同步：开关开启 + 已登录 + 距上次同步 ≥1 小时 → 自动同步一次。
        // 解决墨水屏系统后台冻结导致周期任务不触发的问题；频繁打开 App 不会重复同步。
        viewModelScope.launch {
            val autoSync = prefs.autoSync.first()
            val hasCredential = repo.observeHasCredential().first()
            if (autoSync && hasCredential) {
                val last = prefs.lastSyncAt.first()
                if (last <= 0 || System.currentTimeMillis() - last >= AUTO_SYNC_MIN_INTERVAL_MS) {
                    // 引导页启动的后台同步仍在进行时不再重复触发（Mutex 会串行，这里直接跳过）
                    if (!repo.syncing.first()) repo.syncInBackground()
                }
            }
        }
        // 同步结果统一反馈（手动/自动/后台/周期任务）；页面销毁不取消同步本身
        viewModelScope.launch {
            repo.lastSyncResult.collect { r ->
                _message.value = when (r) {
                    is SyncResult.Success -> buildString {
                        append("共导入书籍 ${r.bookCount} 本，获取价格成功 ${r.pricedCount} 本，" +
                            "获取价格失败 ${r.unpricedCount} 本")
                        append("\n（自导入书籍无官方价格，显示为 0 元）")
                        r.priceWarning?.let { append("\n$it") }
                    }
                    SyncResult.NoCredential -> "尚未登录，请到「设置 → 登录信息」扫码登录"
                    SyncResult.AuthFailed -> "凭证已失效，请重新扫码登录"
                    SyncResult.RateLimited -> "请求过于频繁，请稍后再试"
                    SyncResult.NetworkError -> "无网络连接，请检查网络后重试"
                    is SyncResult.Failure -> "同步失败：${r.message}"
                    is SyncResult.UpgradeRequired -> r.message
                }
            }
        }
    }

    private val days = MutableStateFlow(7)

    val summary: StateFlow<PaybackSummary?> = repo.observeSummary()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // 7/30 天两个查询同时预热：切换时间范围零延迟。
    // （若切换后才查询，旧数据会按新范围补齐显示，出现中间帧错位）
    val dailyStats: StateFlow<List<DailyStat>> = combine(
        repo.observeDailyStats(7),
        repo.observeDailyStats(30),
        days
    ) { d7, d30, d -> if (d == 7) d7 else d30 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val lastSyncAt: StateFlow<Long> = repo.observeLastSyncAt()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    /** 启动自动检查发现的新版本（首页横幅提示） */
    val pendingUpdateVersion: StateFlow<String?> = prefs.pendingUpdateVersion
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val hasCredential: StateFlow<Boolean> = repo.observeHasCredential()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val syncProgress: StateFlow<SyncProgress> = repo.syncProgress
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncProgress(0, ""))

    val selectedDays: StateFlow<Int> = days.asStateFlow()

    /** 成本占比饼图（按具体条目显示，如"Neo3 Ultra ¥1499"；最多 5 块：Top4 条目 + 其他聚合） */
    val costSlices: StateFlow<List<PieSlice>> = repo.observeCostItems().map { items ->
        val sorted = items.sortedByDescending { it.priceFen }
        val top = sorted.take(4).map { PieSlice(it.name, it.priceFen) }
        val rest = sorted.drop(4).sumOf { it.priceFen }
        if (rest > 0) top + PieSlice("其他成本", rest) else top
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 书值占比饼图（最多 5 块：Top4 + 其他书籍聚合；含已移出书架的书） */
    val bookSlices: StateFlow<List<PieSlice>> = repo.observeReadBooks().map { books ->
        val sorted = books.filter { it.contributedFen > 0 }
            .sortedByDescending { it.contributedFen }
        val top = sorted.take(4).map { PieSlice(it.title, it.contributedFen) }
        val rest = sorted.drop(4).sumOf { it.contributedFen }
        if (rest > 0) top + PieSlice("其他书籍", rest) else top
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selectedDay = MutableStateFlow<String?>(null)
    val selectedDay: StateFlow<String?> = _selectedDay.asStateFlow()

    /** 柱状图点选 */
    fun selectDay(date: String?) {
        _selectedDay.value = date
    }

    /** 平均价值效率（分/小时）= 已读总价值 ÷ 累计阅读时长（官方数据），用于每日价值估算。
     * 时长取 max(书籍累计时长, 每日时长汇总)，任一来源有数据即可，避免效率为 0。 */
    val efficiencyFenPerHour: StateFlow<Long> =
        combine(repo.observeSummary(), repo.observeShelfBooks(), repo.observeDailyStats(366)) { summary, books, daily ->
            val bookSec = books.sumOf { it.totalReadSeconds }
            val dailySec = daily.sumOf { it.readSeconds }
            val seconds = maxOf(bookSec, dailySec)
            if (summary != null && seconds > 0) summary.totalValueFen * 3600 / seconds else 0L
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    /** 同步进行中（全局状态：引导页后台同步、手动同步、自动同步均反映） */
    val syncing: StateFlow<Boolean> = repo.syncing
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun selectDays(d: Int) {
        days.value = d
        _selectedDay.value = null // 切换时间范围时关闭选中日明细框
    }

    fun consumeMessage() {
        _message.value = null
    }

    companion object {
        /** 启动自动同步节流：距上次同步超过 1 小时才触发 */
        const val AUTO_SYNC_MIN_INTERVAL_MS = 60 * 60 * 1000L
    }

    /** 手动同步：后台执行（app 级 scope），进度与结果经全局流反馈 */
    fun sync() {
        if (syncing.value) return
        repo.syncInBackground()
    }
}
