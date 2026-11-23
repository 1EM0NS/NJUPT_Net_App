package com.example.myapplication

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 用 Android Keystore 里的 AES-GCM 密钥加密密码后再落盘。
 *
 * 旧版本是明文存 SharedPreferences，这里保留向后兼容：读到明文时自动加密迁移。
 */
class CredentialStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getStudentId(): String = prefs.getString(KEY_STUDENT_ID, "").orEmpty()

    fun setStudentId(value: String) {
        prefs.edit().putString(KEY_STUDENT_ID, value).apply()
    }

    fun getOperator(): String = prefs.getString(KEY_OPERATOR, "campus").orEmpty()

    fun setOperator(value: String) {
        prefs.edit().putString(KEY_OPERATOR, value).apply()
    }

    fun getPassword(): String {
        val encrypted = prefs.getString(KEY_PASSWORD_ENC, null)
        if (encrypted != null) {
            return runCatching { decrypt(encrypted) }.getOrDefault("")
        }

        // 兼容旧版明文密码
        val legacy = prefs.getString(KEY_PASSWORD, null) ?: return ""
        if (legacy.isEmpty()) {
            return ""
        }
        setPassword(legacy)
        prefs.edit().remove(KEY_PASSWORD).apply()
        return legacy
    }

    fun setPassword(value: String) {
        if (value.isEmpty()) {
            prefs.edit().remove(KEY_PASSWORD_ENC).apply()
            return
        }
        val payload = runCatching { encrypt(value) }.getOrNull() ?: return
        prefs.edit().putString(KEY_PASSWORD_ENC, payload).apply()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val body = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(body, Base64.NO_WRAP)
    }

    private fun decrypt(payload: String): String {
        val parts = payload.split(":")
        require(parts.size == 2) { "密文格式错误" }
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val body = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(body), Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }

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

    private companion object {
        const val PREFS = "config"
        const val KEY_STUDENT_ID = "studentId"
        const val KEY_OPERATOR = "operator"
        const val KEY_PASSWORD = "password"
        const val KEY_PASSWORD_ENC = "password_enc"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "njupt_net_password"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
