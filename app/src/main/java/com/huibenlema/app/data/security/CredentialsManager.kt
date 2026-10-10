package com.huibenlema.app.data.security

import android.webkit.CookieManager
import com.huibenlema.app.data.local.UserPrefs
import com.huibenlema.app.data.log.AppLog
import com.huibenlema.app.data.remote.PrivateWereadApi
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** 凭证可用性（判定登录态的唯一依据：密文存在 ≠ 可解密） */
enum class CredentialStatus {
    /** 尚未完成首次判定（启动瞬间） */
    UNKNOWN,

    /** 无凭证 */
    NONE,

    /** 至少一项凭证可解密可用 */
    OK,

    /** 密文存在但均无法解密（Keystore 密钥丢失/密文损坏）——需要重新登录 */
    BROKEN
}

/**
 * 凭证管理器：明文只存在于调用瞬间的内存，落盘前经 Keystore 加密。
 */
@Singleton
class CredentialsManager @Inject constructor(
    private val keystore: KeystoreCredentialStore,
    private val prefs: UserPrefs,
    private val privateApi: PrivateWereadApi
) {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 失效计数器：密文不变但解密结果变化（密钥丢失/修复）时触发 status 重算 */
    private val invalidate = MutableStateFlow(0)

    /**
     * 凭证可用性状态：DataStore 密文 + 失效计数组合后实际解密判定。
     * OK = apiKey 可解密 或 cookie 可解密；BROKEN = 两者密文均在但都解不开；NONE = 无密文。
     */
    val status: StateFlow<CredentialStatus> =
        combine(prefs.apiKeyCipher, prefs.cookieCipher, invalidate) { apiCipher, cookieCipher, _ ->
            when {
                decryptOk(apiCipher) != null || decryptOk(cookieCipher) != null -> CredentialStatus.OK
                apiCipher != null || cookieCipher != null -> CredentialStatus.BROKEN
                else -> CredentialStatus.NONE
            }
        }.flowOn(Dispatchers.IO)
            .stateIn(appScope, SharingStarted.Eagerly, CredentialStatus.UNKNOWN)

    /** 网页版登录态：Cookie 密文可解密且含会话密钥 wr_skey（设置页/引导页/登录页统一数据源） */
    val cookieOk: StateFlow<Boolean> =
        combine(prefs.cookieCipher, invalidate) { cipher, _ ->
            decryptOk(cipher)?.let { it.contains("wr_skey") } == true
        }.flowOn(Dispatchers.IO)
            .stateIn(appScope, SharingStarted.Eagerly, false)

    private fun decryptOk(cipher: String?): String? {
        if (cipher.isNullOrBlank()) return null
        val plain = keystore.decrypt(cipher) ?: return null
        // 解密失败已由 KeystoreCredentialStore 记日志；此处不额外告警（每次组合都会触发）
        return plain
    }

    /** 密文存在（不判断可解性）：doSync 区分「密文损坏」与「无凭证」用 */
    suspend fun cipherPresent(): Boolean =
        prefs.apiKeyCipher.first() != null || prefs.cookieCipher.first() != null

    /** 保存官方 API Key（wrk-）；加密失败返回 false（调用方按"保存失败"处理） */
    suspend fun saveApiKey(plain: String): Boolean {
        val cipher = keystore.encrypt(plain.trim()) ?: return false
        prefs.setApiKeyCipher(cipher)
        prefs.setCredentialUpdatedAt(System.currentTimeMillis())
        invalidate.update { it + 1 }
        return true
    }

    suspend fun apiKey(): String? = prefs.apiKeyCipher.first()?.let { keystore.decrypt(it) }

    suspend fun hasApiKey(): Boolean = !apiKey().isNullOrBlank()

    /** 保存扫码登录获取的网页版 Cookie（定价补全通道），同时从 Cookie 提取用户 ID（wr_vid）；加密失败返回 false */
    suspend fun saveCookie(plain: String): Boolean {
        val cipher = keystore.encrypt(plain.trim()) ?: return false
        prefs.setCookieCipher(cipher)
        val vid = Regex("wr_vid=([0-9]+)").find(plain)?.groupValues?.get(1)
        prefs.setAccountVid(vid)
        prefs.setCredentialUpdatedAt(System.currentTimeMillis())
        invalidate.update { it + 1 }
        return true
    }

    suspend fun cookie(): String? = prefs.cookieCipher.first()?.let { keystore.decrypt(it) }

    suspend fun hasCookie(): Boolean = !cookie().isNullOrBlank()

    suspend fun clearCookie() {
        prefs.setCookieCipher(null)
        prefs.setAccountVid(null)
        invalidate.update { it + 1 }
    }

    /**
     * 用登录 Cookie 从官方接口自动获取 API Key（wrk-），成功保存并返回 true。
     * 官方文档确认：网页版登录后调用 api/skills/apikeyGet 即返回 Key。
     */
    suspend fun fetchAndSaveApiKey(cookie: String): Boolean {
        if (!cookie.contains("wr_skey")) return false
        return try {
            AppLog.i("HBCred", "apikey_get_begin")
            val body = privateApi.apiKeyGet(cookie).string()
            AppLog.i("HBCred", "apikey_get_body body=${body.take(300)}")
            val match = Regex("wrk-[A-Za-z0-9_\\-]+").find(body) ?: run {
                AppLog.w("HBCred", "apikey_no_match body=${body.take(200)}")
                return false
            }
            saveApiKey(match.value)
        } catch (e: Exception) {
            val detail = if (e is retrofit2.HttpException) "http=${e.code()}" else e.javaClass.simpleName
            AppLog.w("HBCred", "apikey_fetch_fail $detail")
            false
        }
    }

    suspend fun clearAll() {
        prefs.setApiKeyCipher(null)
        prefs.setCookieCipher(null)
        prefs.setAccountVid(null)
        prefs.setCredentialUpdatedAt(0L)
        invalidate.update { it + 1 }
    }

    /**
     * 清空 WebView 的 Cookie 罐（退出登录必须同时清：
     * 否则 doSync 自愈或登录页"已登录"判定会把旧登录态读回来）。
     * 写操作在 Main 线程（WebView 权威线程），仅登出时一次。
     */
    suspend fun clearWebViewCookies() {
        withContext(Dispatchers.Main) {
            runCatching {
                CookieManager.getInstance().removeAllCookies {
                    runCatching { CookieManager.getInstance().flush() }
                }
            }.onFailure { AppLog.e("HBWeb", "clear_cookies_fail", it) }
        }
    }
}
