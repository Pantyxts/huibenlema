package com.huibenlema.app.ui.login

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.huibenlema.app.data.log.AppLog
import com.huibenlema.app.ui.components.EinkButton
import com.huibenlema.app.ui.theme.GrayDark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** 扫码登录：WebView 加载微信读书网页，登录成功后自动提取 Cookie 并加密保存 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(
    vm: LoginViewModel = hiltViewModel(),
    onClose: () -> Unit,
    onLoginSuccess: () -> Unit = {}
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val alreadyLoggedState by vm.alreadyLogged.collectAsStateWithLifecycle()
    var forceRelogin by remember { mutableStateOf(false) }
    var webViewKey by remember { mutableStateOf(0) }

    // 进入时重置上次的登录成功状态（防止残留导致闪退循环）
    LaunchedEffect(Unit) { vm.enter() }

    // 离开登录页：停止 Cookie 轮询（页面已关不再空转）
    DisposableEffect(Unit) {
        onDispose { vm.onExit() }
    }

    // 登录成功 → 自动同步一次并关闭（本次会话内只处理一次）
    var successHandled by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        if (state is LoginUiState.Success && !successHandled) {
            successHandled = true
            onLoginSuccess()
            delay(800) // 给用户看到"登录成功"的瞬间
            onClose()
        }
    }

    if (!forceRelogin && alreadyLoggedState) {
        // 已登录状态页（判定数据源 = 加密存储的 Cookie 可解密，不再读 WebView Cookie 库）
        Column(
            Modifier
                .fillMaxSize()
                .background(Color.White)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("当前已处于微信读书登录状态", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                "同步时会自动补全书价，无需重复登录",
                style = MaterialTheme.typography.bodyMedium,
                color = GrayDark
            )
            Spacer(Modifier.height(20.dp))
            EinkButton(
                text = "退出并重新登录",
                onClick = {
                    forceRelogin = true
                    vm.relogin()
                },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("返回") }
        }
        return
    }

    Column(Modifier.fillMaxSize().background(Color.White)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onClose) { Text("返回") }
            Text("微信扫码登录微信读书", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            if (state is LoginUiState.Qr) {
                TextButton(onClick = vm::manualRefresh) { Text("刷新二维码", color = GrayDark) }
            }
        }
        Text(
            "页面加载后会自动弹出登录二维码。微信扫码后请点「我已扫码」，然后尽快在手机上确认；" +
                "过期会自动刷新，手动刷新会使旧码作废。",
            style = MaterialTheme.typography.bodySmall,
            color = GrayDark,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        // 状态行（白屏问题的根治之一：任何异常都有可见反馈，不再是纯白页面）
        val statusLine = when (val s = state) {
            is LoginUiState.Loading -> "页面加载中…"
            is LoginUiState.Qr -> when {
                s.scanned -> "已扫描，请在手机上确认"
                s.refreshing -> "正在刷新二维码…"
                s.expired -> "二维码已失效，正在自动刷新…"
                s.hasQr -> "二维码已显示，请用微信扫码"
                else -> "等待二维码显示…"
            }
            is LoginUiState.Saving -> "正在保存登录信息…"
            is LoginUiState.Success -> "登录成功"
            is LoginUiState.Error -> s.message
        }
        Text(
            statusLine,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        when (state) {
            is LoginUiState.Error -> {
                // 错误页：可重试（重建 WebView），不再是无响应的白屏
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Spacer(Modifier.height(12.dp))
                    EinkButton(
                        text = "重试",
                        onClick = {
                            webViewKey++
                            vm.enter()
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            else -> {
                Box(Modifier.weight(1f)) {
                    WebViewArea(vm, webViewKey)
                }
            }
        }

        // 操作区：「我已扫码」锁定 + 手动刷新（Qr 且未扫描时）
        val qr = state as? LoginUiState.Qr
        if (qr != null && !qr.scanned && state !is LoginUiState.Error) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                EinkButton(
                    text = "我已扫码",
                    onClick = vm::onMarkScanned,
                    modifier = Modifier.weight(1f)
                )
                EinkButton(
                    text = "刷新二维码",
                    onClick = vm::manualRefresh,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** WebView 区域：创建防护 + 渲染进程死亡处理 + 销毁回收 + JS 桥（状态上报轮询） */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebViewArea(vm: LoginViewModel, webViewKey: Int) {
    val context = LocalContext.current
    val webView = remember(webViewKey) {
        AppLog.i("HBLogin", "webview_create_begin")
        val t0 = System.currentTimeMillis()
        runCatching {
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = QrLoginJs.MOBILE_UA
                // 扫码登录依赖微信跨站 iframe 的 Cookie，必须开启第三方 Cookie
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        AppLog.i("HBLogin", "page_finished url=$url")
                        // runCatching：destroy 后迟到的回调在此注入会抛异常
                        view?.let { runCatching { it.evaluateJavascript(QrLoginJs.INIT, null) } }
                    }

                    // 渲染进程死亡：返回 true 自处理（默认 false 会连带杀整个 App）
                    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                        AppLog.e("HBLogin", "render_gone didCrash=${detail?.didCrash()}")
                        vm.onRenderProcessGone(detail?.didCrash() == true)
                        return true
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?
                    ) {
                        if (request?.isForMainFrame == true) {
                            vm.onPageError(
                                error?.errorCode ?: -1,
                                error?.description?.toString(),
                                request.url?.toString()
                            )
                        }
                    }

                    override fun onReceivedHttpError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        errorResponse: android.webkit.WebResourceResponse?
                    ) {
                        AppLog.w("HBLogin", "http_error code=${errorResponse?.statusCode} url=${request?.url}")
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(cm: android.webkit.ConsoleMessage?): Boolean {
                        cm?.let { AppLog.i("HBLogin", "console ${it.message()}") }
                        return true
                    }
                }
                loadUrl("https://weread.qq.com/")
            }.also {
                AppLog.i("HBLogin", "webview_create_ok in ${System.currentTimeMillis() - t0}ms")
            }
        }.onFailure { e ->
            AppLog.e("HBLogin", "webview_create_fail in ${System.currentTimeMillis() - t0}ms", e)
            vm.onWebViewCreateFailed(e.javaClass.simpleName)
        }.getOrNull()
    }

    if (webView == null) {
        // 创建失败：错误页由 vm state 渲染（此分支只占位）
        return
    }

    // WebView 就绪 → 启动 Cookie 轮询（就绪前触碰 CookieManager 会与 WebView 创建互等）
    LaunchedEffect(webView) { vm.onWebViewReady() }

    // JS 状态轮询（1s）：页面重载丢 window.__hb 时重新注入。
    // 全部 JS 调用 runCatching：登录页关闭（WebView destroy）与轮询存在竞态，
    // 在已销毁的 WebView 上 evaluateJavascript 会抛异常 → 未捕获直接崩溃进程
    LaunchedEffect(webView) {
        while (isActive) {
            delay(1_000)
            val raw = withContext(Dispatchers.Main) {
                runCatching {
                    suspendCancellableCoroutine { cont ->
                        webView.evaluateJavascript(QrLoginJs.REPORT) { value -> cont.resume(value) }
                    }
                }.getOrNull()
            } ?: continue
            if (raw.isNullOrBlank() || raw == "null") {
                withContext(Dispatchers.Main) {
                    runCatching { webView.evaluateJavascript(QrLoginJs.INIT, null) }
                }
                continue
            }
            val parts = raw.trim('"').split(',')
            if (parts.size == 4) {
                vm.onQrReport(
                    hasQr = parts[1] == "1",
                    expired = parts[2] == "1",
                    scanned = parts[3] == "1"
                )
            }
        }
    }

    // App 下发的二维码刷新动作（JS 永不自行点击）
    LaunchedEffect(webView) {
        vm.refreshRequests.collect { action ->
            when (action) {
                QrRefreshAction.CLICK -> {
                    val clicked = withContext(Dispatchers.Main) {
                        runCatching {
                            suspendCancellableCoroutine { cont ->
                                webView.evaluateJavascript(QrLoginJs.CLICK_REFRESH) { v ->
                                    cont.resume(v.trim('"') == "true")
                                }
                            }
                        }.getOrDefault(false)
                    }
                    vm.onRefreshClickResult(clicked)
                }
                QrRefreshAction.RELOAD -> {
                    withContext(Dispatchers.Main) {
                        runCatching {
                            webView.evaluateJavascript(QrLoginJs.INIT, null)
                            webView.reload()
                        }
                    }
                    AppLog.i("HBLogin", "qr_reload_done")
                }
            }
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { webView },
        onRelease = {
            AppLog.i("HBWeb", "destroy")
            it.stopLoading()
            it.destroy()
        }
    )
}
