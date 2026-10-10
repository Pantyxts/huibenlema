package com.huibenlema.app.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.huibenlema.app.data.log.AppLog
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 凭证加密：Android Keystore AES-256-GCM。
 * 密钥不可导出（硬件/TEE 保护），密文 Base64 后由 [com.huibenlema.app.data.local.UserPrefs] 持久化。
 * minSdk 23 保证 Keystore AES 可用。
 */
@Singleton
class KeystoreCredentialStore @Inject constructor() {

    /** 已有密钥（可能为 null：系统重置/厂商换机工具搬走密文但没搬密钥时） */
    private fun existingKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        return (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
    }

    /** 密钥条目是否存在（解密前置检查：alias 丢失时不再静默新建密钥后 AEAD 失败） */
    fun keyExists(): Boolean = existingKey() != null

    private fun getOrCreateKey(): SecretKey {
        existingKey()?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    /** 加密失败（Keystore 异常等）返回 null，调用方按"保存失败"处理并提示用户 */
    fun encrypt(plainText: String): String? = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        // iv(12) + 密文 拼接后 Base64
        Base64.encodeToString(cipher.iv + cipherText, Base64.NO_WRAP)
    } catch (e: Exception) {
        AppLog.e("HBCred", "encrypt_fail", e)
        null
    }

    /** 密钥失效/密文损坏时返回 null（调用方按"凭证已失效"处理） */
    fun decrypt(encoded: String): String? {
        // alias 丢失：旧密文与新密钥不匹配，必然解不开——明确上报，不再静默新建密钥
        if (!keyExists()) {
            AppLog.e("HBCred", "keystore_alias_missing cipherLen=${encoded.length}")
            return null
        }
        return try {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            val iv = bytes.copyOfRange(0, IV_LENGTH)
            val cipherText = bytes.copyOfRange(IV_LENGTH, bytes.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, existingKey(), GCMParameterSpec(TAG_LENGTH, iv))
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (e: Exception) {
            AppLog.e("HBCred", "decrypt_fail cipherLen=${encoded.length}", e)
            null
        }
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "huibenlema.credential.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_LENGTH = 128
    }
}
