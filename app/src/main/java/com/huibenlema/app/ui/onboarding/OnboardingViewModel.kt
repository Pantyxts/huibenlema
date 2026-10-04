package com.huibenlema.app.ui.onboarding

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.huibenlema.app.data.local.UserPrefs
import com.huibenlema.app.data.security.CredentialsManager
import com.huibenlema.app.domain.repo.BookRepository
import com.huibenlema.app.domain.repo.SyncResult
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
    private val credentials: CredentialsManager,
    private val prefs: UserPrefs
) : ViewModel() {

    var error by mutableStateOf<String?>(null)
    var working by mutableStateOf(false)

    val hasCookie: StateFlow<Boolean> = prefs.cookieCipher.map { !it.isNullOrBlank() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val hasApiKey: StateFlow<Boolean> = prefs.apiKeyCipher.map { !it.isNullOrBlank() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * 开始：直接尝试同步。repo.sync() 内部会自动兜底：
     * WebView Cookie 仍在（如"清除数据"未清登录态）→ 自动补取 API Key → 同步成功直接进入。
     */
    fun start() {
        if (working) return
        viewModelScope.launch {
            working = true
            error = null
            try {
                when (val r = repo.sync()) {
                    is SyncResult.Success -> prefs.setOnboardingDone(true)
                    is SyncResult.UpgradeRequired -> error = r.message
                    SyncResult.NoCredential -> error = "请先扫码登录"
                    SyncResult.AuthFailed -> {
                        credentials.clearAll()
                        error = "授权失败，请重新扫码登录"
                    }
                    else -> error = "同步失败，请重试"
                }
            } finally {
                working = false
            }
        }
    }

    /** 跳过授权：进入无凭证模式（首页横幅引导补授权） */
    fun skip() {
        viewModelScope.launch { prefs.setOnboardingDone(true) }
    }
}
