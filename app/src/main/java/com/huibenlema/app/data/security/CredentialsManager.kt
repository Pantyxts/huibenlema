package com.huibenlema.app.data.security

import com.huibenlema.app.data.local.UserPrefs
import com.huibenlema.app.data.remote.PrivateWereadApi
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * 凭证管理器：明文只存在于调用瞬间的内存，落盘前经 Keystore 加密。
 */
@Singleton
class CredentialsManager @Inject constructor(
    private val keystore: KeystoreCredentialStore,
    private val prefs: UserPrefs,
    private val privateApi: PrivateWereadApi
) {

    /** 保存官方 API Key（wrk-） */
    suspend fun saveApiKey(plain: String) {
        prefs.setApiKeyCipher(keystore.encrypt(plain.trim()))
        prefs.setCredentialUpdatedAt(System.currentTimeMillis())
    }

    suspend fun apiKey(): String? = prefs.apiKeyCipher.first()?.let { keystore.decrypt(it) }

    suspend fun hasApiKey(): Boolean = !apiKey().isNullOrBlank()

    /** 保存扫码登录获取的网页版 Cookie（定价补全通道），同时从 Cookie 提取用户 ID（wr_vid） */
    suspend fun saveCookie(plain: String) {
        prefs.setCookieCipher(keystore.encrypt(plain.trim()))
        val vid = Regex("wr_vid=([0-9]+)").find(plain)?.groupValues?.get(1)
        prefs.setAccountVid(vid)
        prefs.setCredentialUpdatedAt(System.currentTimeMillis())
    }

    suspend fun cookie(): String? = prefs.cookieCipher.first()?.let { keystore.decrypt(it) }

    suspend fun hasCookie(): Boolean = !cookie().isNullOrBlank()

    suspend fun clearCookie() {
        prefs.setCookieCipher(null)
        prefs.setAccountVid(null)
    }

    /**
     * 用登录 Cookie 从官方接口自动获取 API Key（wrk-），成功保存并返回 true。
     * 官方文档确认：网页版登录后调用 api/skills/apikeyGet 即返回 Key。
     */
    suspend fun fetchAndSaveApiKey(cookie: String): Boolean {
        if (!cookie.contains("wr_skey")) return false
        return try {
            val body = privateApi.apiKeyGet(cookie).string()
            val match = Regex("wrk-[A-Za-z0-9_\\-]+").find(body) ?: return false
            saveApiKey(match.value)
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun clearAll() {
        prefs.setApiKeyCipher(null)
        prefs.setCookieCipher(null)
        prefs.setAccountVid(null)
        prefs.setCredentialUpdatedAt(0L)
    }
}
