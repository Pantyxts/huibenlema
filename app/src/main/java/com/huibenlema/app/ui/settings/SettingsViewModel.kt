package com.huibenlema.app.ui.settings

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.huibenlema.app.data.local.AppDatabase
import com.huibenlema.app.data.local.UserPrefs
import com.huibenlema.app.data.security.CredentialsManager
import com.huibenlema.app.data.sync.AutoSyncScheduler
import com.huibenlema.app.data.sync.SyncProgress
import com.huibenlema.app.data.update.UpdateManager
import com.huibenlema.app.domain.model.Book
import com.huibenlema.app.domain.model.CostCategory
import com.huibenlema.app.domain.model.CostItem
import com.huibenlema.app.domain.repo.BookRepository
import com.huibenlema.app.domain.repo.SyncResult
import com.huibenlema.app.ui.components.formatFen
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: BookRepository,
    private val credentials: CredentialsManager,
    private val prefs: UserPrefs,
    private val db: AppDatabase,
    private val autoSyncScheduler: AutoSyncScheduler,
    private val updateManager: UpdateManager
) : ViewModel() {

    init {
        // 同步结果统一反馈（手动/自动/后台/周期任务）
        viewModelScope.launch {
            repo.lastSyncResult.collect { r ->
                message = when (r) {
                    is SyncResult.Success -> buildString {
                        append("共导入书籍 ${r.bookCount} 本，获取价格成功 ${r.pricedCount} 本")
                        r.priceWarning?.let { append("\n$it") }
                        append("\n自导入书籍无官方价格，显示为 0 元")
                    }
                    SyncResult.NoCredential -> "尚未登录，请先扫码登录"
                    SyncResult.AuthFailed -> "凭证已失效，请重新扫码登录"
                    SyncResult.RateLimited -> "请求过于频繁，请稍后再试"
                    SyncResult.NetworkError -> "无网络连接，请检查网络后重试"
                    is SyncResult.Failure -> "同步失败：${r.message}"
                    is SyncResult.UpgradeRequired -> r.message
                }
            }
        }
    }

    // ---- 自动更新 ----

    val updateState = updateManager.state

    fun checkUpdate() {
        viewModelScope.launch { updateManager.checkUpdate() }
    }

    fun downloadUpdate() {
        val st = updateState.value as? UpdateManager.UpdateState.Available ?: return
        viewModelScope.launch { updateManager.download(st.info) }
    }

    fun installUpdate() {
        val st = updateState.value
        val file = when (st) {
            is UpdateManager.UpdateState.Downloaded -> st.file
            is UpdateManager.UpdateState.NeedInstallPermission -> st.file
            else -> return
        }
        updateManager.install(file)
    }

    /** 跳转系统设置开启「安装未知应用」权限 */
    fun openInstallPermissionSettings() {
        updateManager.openInstallPermissionSettings()
    }

    /** 是否已获得「安装未知应用」权限（从系统设置返回后自动重试安装用） */
    fun canInstallPackages(): Boolean = updateManager.canInstallPackages()

    fun dismissUpdate() {
        updateManager.reset()
    }

    /** 忽略此版本：该版本不再通知（含首页横幅与后续检查），更高版本正常通知 */
    fun ignoreUpdate() {
        val st = updateState.value as? UpdateManager.UpdateState.Available ?: return
        viewModelScope.launch {
            prefs.setIgnoredUpdateVersion(st.info.versionName)
            prefs.setPendingUpdateVersion(null)
            updateManager.reset()
            message = "已忽略 v${st.info.versionName} 的更新"
        }
    }

    /** 进入设置页后清除首页"发现新版本"横幅 */
    fun clearPendingUpdate() {
        viewModelScope.launch { prefs.setPendingUpdateVersion(null) }
    }

    val costItems: StateFlow<List<CostItem>> = repo.observeCostItems()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val hasCredential: StateFlow<Boolean> = repo.observeHasCredential()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val hasCookie: StateFlow<Boolean> = prefs.cookieCipher.map { !it.isNullOrBlank() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val syncProgress: StateFlow<SyncProgress> = repo.syncProgress
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncProgress(0, ""))

    val accountName: StateFlow<String?> = prefs.accountName
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val accountVid: StateFlow<String?> = prefs.accountVid
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 用户自定义的常用成本分类 */
    val customCategories: StateFlow<Set<String>> = prefs.customCostCategories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val lastSyncAt: StateFlow<Long> = repo.observeLastSyncAt()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    /** 用户手动隐藏的书籍（设置-数据-隐藏书籍入口展示） */
    val hiddenBooks: StateFlow<List<Book>> = repo.observeHiddenBooks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 隐藏书籍移出隐藏（恢复显示，重新参与价值计算） */
    fun restoreHidden(bookIds: List<String>) {
        viewModelScope.launch { repo.setBooksHidden(bookIds, hidden = false) }
    }

    val autoSync: StateFlow<Boolean> = prefs.autoSync
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // ---- 对话框状态 ----
    var editingCost by mutableStateOf<CostItem?>(null)
        private set
    var addCostVisible by mutableStateOf(false)
        private set
    var showClearConfirm by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)

    /** 同步进行中（全局状态：引导页后台同步、手动同步、自动同步均反映） */
    val syncing: StateFlow<Boolean> = repo.syncing
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    var showLogin by mutableStateOf(false)
        private set

    /** 待确认删除的成本条目 */
    var pendingDelete by mutableStateOf<CostItem?>(null)
        private set

    fun openAddCost() {
        editingCost = null
        addCostVisible = true
    }

    fun openEditCost(item: CostItem) {
        editingCost = item
        addCostVisible = true
    }

    fun dismissCostDialog() {
        addCostVisible = false
        editingCost = null
    }

    /** 编辑中的条目请求删除（先确认） */
    fun requestDeleteCost() {
        pendingDelete = editingCost
        dismissCostDialog()
    }

    fun confirmDeleteCost() {
        val item = pendingDelete ?: return
        pendingDelete = null
        viewModelScope.launch { repo.deleteCostItem(item.id) }
    }

    fun cancelDeleteCost() {
        pendingDelete = null
    }

    fun openClearConfirm() {
        showClearConfirm = true
    }

    // ---- 分级清除数据 ----

    /** 清除操作类型（选项菜单 → 二次确认 → 执行） */
    enum class ClearAction(val confirmText: String) {
        ALL_BOOKS("确认清除全部书值数据？\n将删除所有书籍与定价，重新同步后恢复官方数据。"),
        NON_MANUAL("确认清除非手动书值数据？\n手动定价的书籍将保留。"),
        MANUAL("确认清除手动书值数据？\n手动定价的书籍重新同步后恢复官方价格。"),
        COSTS("确认清除所有成本栏目？\n回本进度计算将重置。"),
        EVERYTHING("确认清除应用全部数据？\n将删除书籍、定价、成本台账与凭证，回到首次启动状态。")
    }

    /** 清除选项菜单 */
    var showClearMenu by mutableStateOf(false)
        private set

    /** 待确认执行的清除操作 */
    var pendingClearAction by mutableStateOf<ClearAction?>(null)
        private set

    fun openClearMenu() {
        showClearMenu = true
    }

    fun dismissClearMenu() {
        showClearMenu = false
    }

    /** 选项菜单点击 → 关闭菜单，弹出二次确认 */
    fun requestClear(action: ClearAction) {
        showClearMenu = false
        pendingClearAction = action
        showClearConfirm = true
    }

    fun dismissClearConfirm() {
        showClearConfirm = false
        pendingClearAction = null
    }

    fun confirmClear() {
        val action = pendingClearAction ?: return
        pendingClearAction = null
        showClearConfirm = false
        viewModelScope.launch(Dispatchers.IO) {
            when (action) {
                ClearAction.ALL_BOOKS -> repo.clearAllBooks()
                ClearAction.NON_MANUAL -> repo.clearNonManualBooks()
                ClearAction.MANUAL -> repo.clearManualBooks()
                ClearAction.COSTS -> repo.clearCostItems()
                ClearAction.EVERYTHING -> {
                    db.clearAllTables()
                    credentials.clearAll()
                    prefs.setOnboardingDone(false)
                    prefs.setLastSyncAt(0L)
                }
            }
        }
    }

    fun openLogin() {
        showLogin = true
    }

    fun closeLogin() {
        showLogin = false
    }

    /** 退出登录确认框 */
    var showLogoutConfirm by mutableStateOf(false)
        private set

    fun openLogoutConfirm() {
        showLogoutConfirm = true
    }

    fun dismissLogoutConfirm() {
        showLogoutConfirm = false
    }

    fun confirmLogout() {
        showLogoutConfirm = false
        viewModelScope.launch {
            credentials.clearCookie()
            message = "已退出微信读书登录"
        }
    }

    companion object

    // ---- 操作 ----

    fun saveCost(name: String, priceYuan: String, category: CostCategory, customName: String = "") {
        val fen = ((priceYuan.toDoubleOrNull() ?: return).times(100)).toLong()
        if (name.isBlank() || fen < 0) return
        viewModelScope.launch {
            val current = editingCost
            if (current == null) {
                repo.addCostItem(
                    CostItem(
                        name = name,
                        priceFen = fen,
                        category = category,
                        boughtAt = System.currentTimeMillis(),
                        note = customName
                    )
                )
            } else {
                repo.updateCostItem(
                    current.copy(name = name, priceFen = fen, category = category, note = customName)
                )
            }
            dismissCostDialog()
        }
    }

    fun deleteCost(item: CostItem) {
        viewModelScope.launch { repo.deleteCostItem(item.id) }
    }

    /** 保存为常用分类 */
    fun saveCustomCategory(label: String) {
        val trimmed = label.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch { prefs.addCostCategory(trimmed) }
    }

    /** 删除常用分类 */
    fun deleteCustomCategory(label: String) {
        viewModelScope.launch { prefs.removeCostCategory(label) }
    }

    fun clearCredential() {
        viewModelScope.launch {
            credentials.clearAll()
            message = "凭证已清除"
        }
    }

    fun setAutoSync(enabled: Boolean) {
        viewModelScope.launch {
            prefs.setAutoSync(enabled)
            autoSyncScheduler.setEnabled(enabled)
        }
    }

    /** 手动同步：后台执行（app 级 scope），进度与结果经全局流反馈 */
    fun sync() {
        if (syncing.value) return
        repo.syncInBackground()
    }

    /** 导出前确认框（进入系统保存框前提供明确的取消入口） */
    var showExportConfirm by mutableStateOf(false)
        private set

    fun openExportConfirm() {
        showExportConfirm = true
    }

    fun dismissExportConfirm() {
        showExportConfirm = false
    }

    /** 数据导出：书籍 + 成本台账 + 每日统计 → JSON（凭证不导出）。成功静默，仅失败提示 */
    fun exportData(uri: Uri) {
        viewModelScope.launch {
            if (!repo.exportData(uri)) message = "导出失败"
        }
    }

    /** 待确认导入的备份文件 */
    var showImportConfirm by mutableStateOf(false)
        private set
    private var pendingImportUri: Uri? = null

    fun requestImport(uri: Uri) {
        pendingImportUri = uri
        showImportConfirm = true
    }

    fun dismissImportConfirm() {
        showImportConfirm = false
        pendingImportUri = null
    }

    fun confirmImport() {
        val uri = pendingImportUri ?: return
        showImportConfirm = false
        pendingImportUri = null
        viewModelScope.launch {
            // 成功静默，仅失败提示
            if (!repo.importData(uri)) message = "导入失败"
        }
    }

}
