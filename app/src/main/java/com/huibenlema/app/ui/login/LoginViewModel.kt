package com.huibenlema.app.ui.login

import android.content.Context
import android.webkit.CookieManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.huibenlema.app.data.log.AppLog
import com.huibenlema.app.data.security.CredentialsManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** 登录页 UI 状态 */
sealed interface LoginUiState {
    data object Loading : LoginUiState
    data class Qr(
        val hasQr: Boolean,
        val scanned: Boolean,
        val expired: Boolean,
        val refreshing: Boolean
    ) : LoginUiState

    data object Saving : LoginUiState

    /** 登录成功（apiKeyPending = API Key 仍在后台补取中） */
    data class Success(val apiKeyPending: Boolean) : LoginUiState

    data class Error(val message: String) : LoginUiState
}

/** 二维码刷新动作（App 下发，JS 永不自行点击） */
enum class QrRefreshAction { CLICK, RELOAD }

/**
 * 扫码登录状态机：
 * - Cookie 轮询（1s，IO 线程读 CookieManager）检测 wr_skey → saveAndDone
 * - JS 状态上报驱动二维码 UI 状态；「已扫描」锁定后永不自动刷新（核心：不顶掉已确认 ticket）
 * - 自动刷新有冷却与上限；点不到页面刷新按钮时整页 reload 兜底
 * - saveAndDone 幂等；API Key 后台补取不阻塞登录完成
 */
@HiltViewModel
class LoginViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val credentials: CredentialsManager
) : ViewModel() {

    private val _state = MutableStateFlow<LoginUiState>(LoginUiState.Loading)
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    private val _refreshRequests = MutableSharedFlow<QrRefreshAction>(extraBufferCapacity = 4)
    val refreshRequests: SharedFlow<QrRefreshAction> = _refreshRequests.asSharedFlow()

    /** 已登录状态页判定：Cookie 密文可解密且含 wr_skey（不再读 WebView Cookie 库） */
    val alreadyLogged: StateFlow<Boolean> = credentials.cookieOk

    private var pollJob: Job? = null
    private var saving = false
    private var scannedLocked = false
    private var autoRefreshCount = 0
    private var lastAutoRefreshAt = 0L

    /** 进入登录页：重置状态。Cookie 轮询延后到 [onWebViewReady]（见下） */
    fun enter() {
        AppLog.i("HBLogin", "enter")
        saving = false
        scannedLocked = false
        autoRefreshCount = 0
        lastAutoRefreshAt = 0L
        _state.value = LoginUiState.Loading
        pollJob?.cancel()
        pollJob = null
        // 总超时：180 秒未成功则提示手动刷新（页面不关闭）
        viewModelScope.launch {
            delay(LOGIN_TIMEOUT_MS)
            val s = _state.value
            if (s is LoginUiState.Qr || s is LoginUiState.Loading) {
                AppLog.w("HBLogin", "login_timeout")
                _state.value = LoginUiState.Qr(hasQr = false, scanned = false, expired = true, refreshing = false)
            }
        }
    }

    /**
     * WebView 创建完成后启动 Cookie 轮询。
     * 必须等 WebView 就绪再读 CookieManager：过早读取（无论哪个线程）会触发 WebView 隐式初始化，
     * 与主线程的 WebView 创建互等导致卡死（白屏/同步无响应的最大嫌疑路径）。
     * 就绪后读取放主线程：初始化已完成，同步 IPC 极快（毫秒级）。
     */
    fun onWebViewReady() {
        if (pollJob?.isActive == true) return
        AppLog.i("HBLogin", "poll_start")
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(1_000)
                val cookie = withContext(Dispatchers.Main) { readWereadCookie(context) }
                if (cookie.contains("wr_skey")) {
                    saveAndDone(cookie)
                    break
                }
            }
        }
    }

    /** JS 状态上报（1s 一次；只在二维码阶段生效） */
    fun onQrReport(hasQr: Boolean, expired: Boolean, scanned: Boolean) {
        val cur = _state.value
        if (saving || cur is LoginUiState.Success || cur is LoginUiState.Error) return
        if (scanned) scannedLocked = true
        if (scannedLocked) {
            // 已扫描锁定：永久停自动刷新，等手机确认（不顶掉可能已确认的 ticket）
            val qr = cur as? LoginUiState.Qr
            if (qr == null || !qr.scanned) {
                _state.value = LoginUiState.Qr(hasQr = hasQr, scanned = true, expired = false, refreshing = false)
            }
            return
        }
        _state.value = LoginUiState.Qr(hasQr = hasQr, scanned = false, expired = expired, refreshing = false)
        if (expired) {
            val now = System.currentTimeMillis()
            if (autoRefreshCount >= AUTO_REFRESH_MAX || now - lastAutoRefreshAt < AUTO_REFRESH_COOLDOWN_MS) return
            autoRefreshCount++
            lastAutoRefreshAt = now
            AppLog.i("HBLogin", "qr_refresh_auto attempt=$autoRefreshCount")
            _refreshRequests.tryEmit(QrRefreshAction.CLICK)
        }
    }

    /** 页面刷新按钮点击结果：点不到（页面结构变化）→ 整页 reload 兜底（真·新二维码） */
    fun onRefreshClickResult(clicked: Boolean) {
        if (!clicked) {
            AppLog.i("HBLogin", "qr_click_miss -> reload")
            _refreshRequests.tryEmit(QrRefreshAction.RELOAD)
        }
    }

    /** 「我已扫码」手动确认：用户扫码后点按锁定（兜底跨域 iframe 文案检测不到的情况） */
    fun onMarkScanned() {
        scannedLocked = true
        _state.value = LoginUiState.Qr(hasQr = true, scanned = true, expired = false, refreshing = false)
        AppLog.i("HBLogin", "scanned_marked_manual")
    }

    /** 手动刷新：重置锁定与计数，整页 reload（真正的新二维码 + 新 ticket） */
    fun manualRefresh() {
        scannedLocked = false
        autoRefreshCount = 0
        lastAutoRefreshAt = 0L
        _state.value = LoginUiState.Qr(hasQr = false, scanned = false, expired = false, refreshing = true)
        AppLog.i("HBLogin", "qr_refresh_manual")
        _refreshRequests.tryEmit(QrRefreshAction.RELOAD)
    }

    /** WebView 创建失败（provider 缺失/系统更新中）：可见错误页而非白屏 */
    fun onWebViewCreateFailed(reason: String) {
        AppLog.e("HBLogin", "webview_create_fail reason=$reason")
        pollJob?.cancel()
        _state.value = LoginUiState.Error("无法启动网页组件，请更新系统 WebView 后重试")
    }

    /** 渲染进程死亡：返回 true 自处理（不杀 App），显示错误页 + 重试 */
    fun onRenderProcessGone(didCrash: Boolean) {
        AppLog.e("HBLogin", "render_gone didCrash=$didCrash")
        pollJob?.cancel()
        _state.value = LoginUiState.Error("登录页渲染异常，请点「重试」")
    }

    /** 主框架加载失败（断网/DNS/超时）→ 错误页 */
    fun onPageError(code: Int, desc: String?, url: String?) {
        AppLog.w("HBLogin", "page_error code=$code desc=$desc url=$url")
        if (code == android.webkit.WebViewClient.ERROR_HOST_LOOKUP ||
            code == android.webkit.WebViewClient.ERROR_CONNECT ||
            code == android.webkit.WebViewClient.ERROR_TIMEOUT
        ) {
            _state.value = LoginUiState.Error("页面加载失败，请检查网络后重试")
        }
    }

    /** 已登录状态页的「退出并重新登录」：清 DataStore 凭证 + WebView Cookie，再进入扫码流程 */
    fun relogin() {
        viewModelScope.launch {
            credentials.clearAll()
            credentials.clearWebViewCookies()
            AppLog.i("HBLogin", "relogin_cleared")
            enter()
        }
    }

    /** 离开登录页（关闭/成功/放弃）：停止 Cookie 轮询，避免页面已关仍空转 */
    fun onExit() {
        AppLog.i("HBLogin", "exit")
        pollJob?.cancel()
        pollJob = null
    }

    /** 幂等保存 + API Key 后台补取（不阻塞登录完成；失败下次同步 doSync 会自动补） */
    private fun saveAndDone(cookie: String) {
        if (saving || _state.value is LoginUiState.Success) return
        saving = true
        pollJob?.cancel()
        viewModelScope.launch {
            _state.value = LoginUiState.Saving
            AppLog.i("HBLogin", "cookie_detected wr_skey=1")
            val saved = credentials.saveCookie(cookie)
            if (!saved) {
                AppLog.e("HBLogin", "save_cookie_fail")
                _state.value = LoginUiState.Error("登录信息保存失败（系统密钥库异常），请重试")
                saving = false
                return@launch
            }
            // 登录成功即持久化 WebView Cookie 罐（默认仅退出时 flush）
            withContext(Dispatchers.IO) { runCatching { CookieManager.getInstance().flush() } }
            _state.value = LoginUiState.Success(apiKeyPending = true)
            AppLog.i("HBLogin", "save_cookie_ok")
            // Key 后台补取：20s 超时保护；失败不阻塞（下次同步 doSync 自动补）
            val ok = withTimeoutOrNull(20_000) { credentials.fetchAndSaveApiKey(cookie) } ?: false
            AppLog.i("HBLogin", if (ok) "apikey_fetch_ok" else "apikey_fetch_fail")
            _state.value = LoginUiState.Success(apiKeyPending = !ok)
        }
    }

    companion object {
        /** 严格校验：必须含会话密钥 wr_skey（仅 wr_vid 不足以调用定价接口） */
        fun isLoggedInCookie(cookie: String): Boolean = cookie.contains("wr_skey")

        /** 自动刷新：冷却 5s、最多 3 次，之后提示手动刷新 */
        private const val AUTO_REFRESH_COOLDOWN_MS = 5_000L
        private const val AUTO_REFRESH_MAX = 3

        /** 登录总超时 */
        private const val LOGIN_TIMEOUT_MS = 180_000L
    }
}

/**
 * CookieManager 读取。仅允许在 WebView 创建完成后调用（[onWebViewReady] 之后）：
 * 提前调用会触发 WebView 隐式初始化，与主线程 WebView 创建互等导致卡死。
 * 初始化完成后该调用是快速同步 IPC，放主线程即可。
 */
private fun readWereadCookie(context: Context): String = try {
    CookieManager.getInstance().getCookie("https://weread.qq.com/") ?: ""
} catch (_: Exception) {
    ""
}
