package com.huibenlema.app.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_prefs")

/**
 * 用户配置（非敏感）+ 凭证密文（Keystore 加密后的 Base64）。
 */
@Singleton
class UserPrefs @Inject constructor(@ApplicationContext private val context: Context) {

    private object Keys {
        val API_KEY_CIPHER = stringPreferencesKey("api_key_cipher")
        val COOKIE_CIPHER = stringPreferencesKey("cookie_cipher")
        val CREDENTIAL_UPDATED_AT = longPreferencesKey("credential_updated_at")
        val LAST_SYNC_AT = longPreferencesKey("last_sync_at")
        val WIFI_ONLY = booleanPreferencesKey("wifi_only")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val AUTO_SYNC = booleanPreferencesKey("auto_sync")
        val REGIST_TIME = longPreferencesKey("regist_time")
        val ACCOUNT_NAME = stringPreferencesKey("account_name")
        val ACCOUNT_VID = stringPreferencesKey("account_vid")
        val COST_CATEGORIES = stringSetPreferencesKey("cost_categories")
    }

    val apiKeyCipher: Flow<String?> = context.dataStore.data.map { it[Keys.API_KEY_CIPHER] }
    val cookieCipher: Flow<String?> = context.dataStore.data.map { it[Keys.COOKIE_CIPHER] }
    val credentialUpdatedAt: Flow<Long> = context.dataStore.data.map { it[Keys.CREDENTIAL_UPDATED_AT] ?: 0L }
    val lastSyncAt: Flow<Long> = context.dataStore.data.map { it[Keys.LAST_SYNC_AT] ?: 0L }
    val wifiOnly: Flow<Boolean> = context.dataStore.data.map { it[Keys.WIFI_ONLY] ?: true }
    val onboardingDone: Flow<Boolean> = context.dataStore.data.map { it[Keys.ONBOARDING_DONE] ?: false }
    val autoSync: Flow<Boolean> = context.dataStore.data.map { it[Keys.AUTO_SYNC] ?: false }
    val registTime: Flow<Long> = context.dataStore.data.map { it[Keys.REGIST_TIME] ?: 0L }
    val accountName: Flow<String?> = context.dataStore.data.map { it[Keys.ACCOUNT_NAME] }
    val accountVid: Flow<String?> = context.dataStore.data.map { it[Keys.ACCOUNT_VID] }
    val customCostCategories: Flow<Set<String>> =
        context.dataStore.data.map { it[Keys.COST_CATEGORIES] ?: emptySet() }

    suspend fun setApiKeyCipher(value: String?) {
        context.dataStore.edit { p ->
            if (value == null) p.remove(Keys.API_KEY_CIPHER) else p[Keys.API_KEY_CIPHER] = value
        }
    }

    suspend fun setCookieCipher(value: String?) {
        context.dataStore.edit { p ->
            if (value == null) p.remove(Keys.COOKIE_CIPHER) else p[Keys.COOKIE_CIPHER] = value
        }
    }

    suspend fun setCredentialUpdatedAt(value: Long) {
        context.dataStore.edit { it[Keys.CREDENTIAL_UPDATED_AT] = value }
    }

    suspend fun setLastSyncAt(value: Long) {
        context.dataStore.edit { it[Keys.LAST_SYNC_AT] = value }
    }

    suspend fun setWifiOnly(value: Boolean) {
        context.dataStore.edit { it[Keys.WIFI_ONLY] = value }
    }

    suspend fun setOnboardingDone(value: Boolean) {
        context.dataStore.edit { it[Keys.ONBOARDING_DONE] = value }
    }

    suspend fun setAutoSync(value: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_SYNC] = value }
    }

    suspend fun setRegistTime(value: Long) {
        context.dataStore.edit { it[Keys.REGIST_TIME] = value }
    }

    suspend fun setAccountName(value: String?) {
        context.dataStore.edit { p ->
            if (value == null) p.remove(Keys.ACCOUNT_NAME) else p[Keys.ACCOUNT_NAME] = value
        }
    }

    suspend fun setAccountVid(value: String?) {
        context.dataStore.edit { p ->
            if (value == null) p.remove(Keys.ACCOUNT_VID) else p[Keys.ACCOUNT_VID] = value
        }
    }

    suspend fun addCostCategory(name: String) {
        context.dataStore.edit { p ->
            val cur = p[Keys.COST_CATEGORIES] ?: emptySet()
            p[Keys.COST_CATEGORIES] = cur + name
        }
    }

    suspend fun removeCostCategory(name: String) {
        context.dataStore.edit { p ->
            val cur = p[Keys.COST_CATEGORIES] ?: emptySet()
            p[Keys.COST_CATEGORIES] = cur - name
        }
    }
}
