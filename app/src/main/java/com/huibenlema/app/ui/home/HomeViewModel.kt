package com.huibenlema.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.huibenlema.app.data.local.UserPrefs
import com.huibenlema.app.data.log.AppLog
import com.huibenlema.app.data.security.CredentialStatus
import com.huibenlema.app.domain.model.DailyStat
import com.huibenlema.app.domain.model.PaybackSummary
import com.huibenlema.app.domain.repo.BookRepository
import com.huibenlema.app.domain.repo.SyncResult
import com.huibenlema.app.data.sync.SyncProgress
import com.huibenlema.app.ui.components.PieSlice
import com.huibenlema.app.ui.components.formatFen
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repo: BookRepository,
    private val prefs: UserPrefs
) : ViewModel() {

    // 必须先于 init 块声明：lastSyncResult 带 replay 缓存，init 里的 collect 可能
    // 在构造期间同步重放（Main.immediate 立即执行），访问后声明的属性会 NPE 崩溃
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    // 消息 10 秒自动清除任务（新消息到达先取消旧任务；同样必须先于 init 声明）
    private var messageClearJob: Job? = null

    init {
        // 启动自动同步：开关开启 + 已登录（可解密）+ 距上次同步 ≥1 小时 → 自动同步一次。
        // 解决墨水屏系统后台冻结导致周期任务不触发的问题；频繁打开 App 不会重复同步。
        viewModelScope.launch {
            val autoSync = prefs.autoSync.first()
            // 凭证状态流冷启动瞬间是 UNKNOWN：等待首次解密判定完成（3s 超时兜底按未登录处理）
            val status = withTimeoutOrNull(3_000) {
                repo.credentialStatus().first { it != CredentialStatus.UNKNOWN }
            } ?: CredentialStatus.NONE
            if (autoSync && status == CredentialStatus.OK) {
                // 节流基准 = max(最后成功, 最后尝试)：同步失败时 lastSyncAt 不更新，
                // 仅看它会每次打开 App 都重跑一整轮全量同步
                val last = maxOf(prefs.lastSyncAt.first(), prefs.lastSyncAttemptAt.first())
                if (last <= 0 || System.currentTimeMillis() - last >= AUTO_SYNC_MIN_INTERVAL_MS) {
                    // 引导页启动的后台同步仍在进行时不再重复触发（Mutex 会串行，这里直接跳过）
                    if (!repo.syncing.first()) repo.syncInBackground()
                }
            }
        }
        // 同步结果统一反馈（手动/自动/后台/周期任务）；页面销毁不取消同步本身。
        // 首页消息显示 10 秒后自动消失（设置页同源消息保持常显，不受影响）
        viewModelScope.launch {
            repo.lastSyncResult.collect { r ->
                _message.value = when (r) {
                    is SyncResult.Success -> buildString {
                        append("共导入书籍 ${r.bookCount} 本，获取价格成功 ${r.pricedCount} 本")
                        r.priceWarning?.let { append("\n$it") }
                        append("\n自导入书籍无官方价格，显示为 0 元")
                    }
                    SyncResult.NoCredential -> "尚未登录，请到「设置 → 登录信息」扫码登录"
                    SyncResult.AuthFailed -> "凭证已失效，请重新扫码登录"
                    SyncResult.RateLimited -> "请求过于频繁，请稍后再试"
                    SyncResult.NetworkError -> "无网络连接，请检查网络后重试"
                    is SyncResult.Failure -> "同步失败：${r.message}"
                    is SyncResult.UpgradeRequired -> r.message
                }
                messageClearJob?.cancel()
                messageClearJob = launch {
                    delay(SYNC_MESSAGE_VISIBLE_MS)
                    _message.value = null
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
        // 万本量级排序放 Default 线程，避免同步时阻塞主线程
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selectedDay = MutableStateFlow<String?>(null)
    val selectedDay: StateFlow<String?> = _selectedDay.asStateFlow()

    /** 柱状图点选 */
    fun selectDay(date: String?) {
        _selectedDay.value = date
    }

    /** 同步进行中（全局状态：引导页后台同步、手动同步、自动同步均反映） */
    val syncing: StateFlow<Boolean> = repo.syncing
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

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
        /** 首页同步结果消息展示时长（设置页同源消息常显不受此限制） */
        const val SYNC_MESSAGE_VISIBLE_MS = 10_000L
    }

    /** 手动同步：后台执行（app 级 scope），进度与结果经全局流反馈 */
    fun sync() {
        AppLog.i("HBSync", "sync_click home syncing=${syncing.value}")
        if (syncing.value) return
        repo.syncInBackground()
    }
}
