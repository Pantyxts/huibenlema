package com.huibenlema.app.ui.onboarding

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.huibenlema.app.data.local.UserPrefs
import com.huibenlema.app.data.log.AppLog
import com.huibenlema.app.data.security.CredentialsManager
import com.huibenlema.app.data.sync.AutoSyncScheduler
import com.huibenlema.app.domain.repo.BookRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val repo: BookRepository,
    private val prefs: UserPrefs,
    private val credentials: CredentialsManager,
    private val autoSyncScheduler: AutoSyncScheduler
) : ViewModel() {

    var working by mutableStateOf(false)

    /** 网页版登录态：Cookie 密文可解密且含 wr_skey（不再只看密文存在） */
    val hasCookie: StateFlow<Boolean> = credentials.cookieOk

    /**
     * 开始：立即进入首页，首次同步在后台执行（app 级 scope 不随页面取消）。
     * 同时默认开启自动同步（启动同步 + 24h 周期任务；可在设置页关闭）。
     * 首页顶部显示同步进行中，设置页可查看进度；结果统一经 repo.lastSyncResult 反馈。
     * repo.sync() 内部会自动兜底：WebView Cookie 仍在（如"清除数据"未清登录态）→ 自动补取 API Key。
     */
    fun start() {
        if (working) return
        working = true
        viewModelScope.launch {
            // 默认打开自动同步开关（用户决策：引导页「开始使用」即开启，可在设置关闭）。
            // WorkManager 首次初始化较重（读库建库），放 IO 线程且失败不阻塞进入首页
            prefs.setAutoSync(true)
            withContext(Dispatchers.IO) {
                runCatching { autoSyncScheduler.setEnabled(true) }
                    .onFailure { AppLog.e("HBApp", "autoSync_enable_fail", it) }
            }
            // 先触发后台同步（app 级 scope，页面切换不取消），再进首页；
            // 首页自动同步检查 syncing 状态，避免重复排队
            repo.syncInBackground()
            prefs.setOnboardingDone(true)
        }
    }

    /** 跳过授权：进入无凭证模式（首页横幅引导补授权） */
    fun skip() {
        viewModelScope.launch { prefs.setOnboardingDone(true) }
    }
}
