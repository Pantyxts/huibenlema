package com.huibenlema.app.ui.onboarding

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.huibenlema.app.data.local.UserPrefs
import com.huibenlema.app.domain.repo.BookRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val repo: BookRepository,
    private val prefs: UserPrefs
) : ViewModel() {

    var working by mutableStateOf(false)

    val hasCookie: StateFlow<Boolean> = prefs.cookieCipher.map { !it.isNullOrBlank() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * 开始：立即进入首页，首次同步在后台执行（app 级 scope 不随页面取消）。
     * 首页顶部显示同步进行中，设置页可查看进度；结果统一经 repo.lastSyncResult 反馈。
     * repo.sync() 内部会自动兜底：WebView Cookie 仍在（如"清除数据"未清登录态）→ 自动补取 API Key。
     */
    fun start() {
        if (working) return
        working = true
        viewModelScope.launch {
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
