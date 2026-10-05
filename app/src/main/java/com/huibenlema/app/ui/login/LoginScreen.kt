package com.huibenlema.app.ui.login

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.huibenlema.app.data.security.CredentialsManager
import com.huibenlema.app.ui.components.EinkButton
import com.huibenlema.app.ui.theme.GrayDark
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 扫码登录：WebView 加载微信读书网页，登录成功后自动提取 Cookie 并加密保存 */
@HiltViewModel
class LoginViewModel @Inject constructor(
    private val credentials: CredentialsManager
) : ViewModel() {

    var loggedIn by mutableStateOf(false)
        private set

    /** 进入登录页时重置状态：ViewModel 是单例，防止上次成功态残留导致页面闪退 */
    fun enter() {
        loggedIn = false
    }

    fun onPageFinished(cookie: String) {
        if (!loggedIn && isLoggedInCookie(cookie)) saveAndDone(cookie)
    }

    fun pollCookie(cookie: String) {
        if (!loggedIn && isLoggedInCookie(cookie)) saveAndDone(cookie)
    }

    private fun saveAndDone(cookie: String) {
        viewModelScope.launch {
            credentials.saveCookie(cookie)
            // 扫码登录后自动获取官方 API Key（api/skills/apikeyGet），一步完成全部授权
            credentials.fetchAndSaveApiKey(cookie)
            loggedIn = true
        }
    }

    companion object {
        /** 严格校验：必须含会话密钥 wr_skey（仅 wr_vid 不足以调用定价接口） */
        fun isLoggedInCookie(cookie: String): Boolean = cookie.contains("wr_skey")
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(
    vm: LoginViewModel = hiltViewModel(),
    onClose: () -> Unit,
    onLoginSuccess: () -> Unit = {}
) {
    val context = LocalContext.current
    var forceRelogin by remember { mutableStateOf(false) }
    var sessionReady by remember { mutableStateOf(false) }
    // 设备 Cookie 存储已有登录态（曾扫码成功）→ 展示状态页而非闪退
    val alreadyLogged = !forceRelogin && getWereadCookie(context).contains("wr_skey")

    // 进入时重置上次的登录成功状态（防止残留导致闪退循环）
    LaunchedEffect(Unit) {
        vm.enter()
        sessionReady = true
    }
    // 登录成功 → 自动同步一次并关闭（仅在本次会话内响应）
    LaunchedEffect(sessionReady, vm.loggedIn) {
        if (sessionReady && vm.loggedIn) {
            onLoginSuccess()
            onClose()
        }
    }
    // 轮询登录态（仅未登录时）
    LaunchedEffect(alreadyLogged) {
        if (alreadyLogged) return@LaunchedEffect
        while (isActive) {
            vm.pollCookie(getWereadCookie(context))
            if (vm.loggedIn) break
            delay(1500)
        }
    }

    if (alreadyLogged) {
        // 已登录状态页
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
                    CookieManager.getInstance().removeAllCookies(null)
                    CookieManager.getInstance().flush()
                    forceRelogin = true
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
        }
        Text(
            "页面加载后会自动弹出登录二维码（过期会自动刷新）。若未弹出，请点击网页「登录」；" +
                "微信扫码后请尽快确认，确认成功自动完成登录。",
            style = MaterialTheme.typography.bodySmall,
            color = GrayDark,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        AndroidView(
            modifier = Modifier.weight(1f),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = MOBILE_UA
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            view?.let {
                                injectAutoLoginClick(it)
                                // 二维码过期时自动点击「刷新二维码」，避免用户卡在失效页面
                                injectAutoRefreshQr(it)
                            }
                            vm.onPageFinished(getWereadCookie(ctx))
                        }
                    }
                    loadUrl("https://weread.qq.com/")
                }
            }
        )
    }
}

private fun getWereadCookie(context: Context): String = try {
    CookieManager.getInstance().getCookie("https://weread.qq.com/") ?: ""
} catch (_: Exception) {
    ""
}

/** 页面加载后自动点击「登录」按钮，直接弹出二维码弹窗（重试多次等待 SPA 渲染完成） */
private fun injectAutoLoginClick(view: WebView) {
    val js = """
        (function() {
          var tries = 0;
          var done = false;
          // 登录弹窗已打开（存在微信二维码 iframe）则不再点击「登录」，
          // 避免重复点击导致二维码被刷新、用户扫到旧码
          function qrOpened() {
            var iframes = document.querySelectorAll('iframe');
            for (var i = 0; i < iframes.length; i++) {
              if ((iframes[i].src || '').indexOf('open.weixin.qq.com') >= 0) return true;
            }
            return false;
          }
          function clickLogin() {
            tries++;
            if (tries > 15 || done) return;
            if (qrOpened()) { done = true; return; }
            var els = document.querySelectorAll('a,button,div,span');
            for (var i = 0; i < els.length; i++) {
              var t = (els[i].textContent || '').trim();
              if (t === '登录' && els[i].offsetParent !== null) {
                els[i].click();
                done = true;
                return;
              }
            }
            setTimeout(clickLogin, 800);
          }
          setTimeout(clickLogin, 800);
        })();
    """.trimIndent()
    view.evaluateJavascript(js, null)
}

/**
 * 二维码有效期短（用户扫码确认稍慢就会过期），定期检测失效提示
 * （「点击刷新二维码」/「二维码已失效」）并自动点击刷新，保证随时扫到的都是有效二维码。
 */
private fun injectAutoRefreshQr(view: WebView) {
    val js = """
        (function() {
          if (window.__hbRefreshQr) return; // onPageFinished 可能多次触发，防止重复注入
          window.__hbRefreshQr = true;
          setInterval(function() {
            var els = document.querySelectorAll('a,button,div,span');
            for (var i = 0; i < els.length; i++) {
              var t = (els[i].textContent || '').trim();
              if ((t.indexOf('点击刷新二维码') >= 0 || t.indexOf('二维码已失效') >= 0) &&
                  els[i].offsetParent !== null) {
                els[i].click();
                break;
              }
            }
          }, 1500);
        })();
    """.trimIndent()
    view.evaluateJavascript(js, null)
}

/** 桌面版 UA：确保展示网页版（含「登录」入口与扫码弹窗），手机 UA 会跳到无登录入口的手机版首页 */
private const val MOBILE_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
