package com.videodl.app.imagegen

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** API Key 只在本机保存；Android Keystore 的密钥不可导出。 */
object ImageApiKey {
    private const val ALIAS = "tokenrhythm-image-api"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    fun read(context: Context): String {
        val prefs = context.getSharedPreferences(ALIAS, 0)
        val encrypted = prefs.getString("encrypted", null) ?: return ""
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128,
                Base64.getDecoder().decode(prefs.getString("iv", ""))))
            String(cipher.doFinal(Base64.getDecoder().decode(encrypted)), Charsets.UTF_8)
        }.getOrElse { throw IllegalStateException("生图密钥无法解密，请重新设置 API Key") }
    }

    fun save(context: Context, value: String) {
        val clean = value.trim()
        require(clean.isNotEmpty() && clean.length <= 512 && clean.none { it.isWhitespace() }) { "请填写完整 API Key" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encoded = Base64.getEncoder()
        check(context.getSharedPreferences(ALIAS, 0).edit()
            .putString("iv", encoded.encodeToString(cipher.iv))
            .putString("encrypted", encoded.encodeToString(cipher.doFinal(clean.toByteArray())))
            .commit()) { "API Key 保存失败" }
    }

    fun clear(context: Context) {
        check(context.getSharedPreferences(ALIAS, 0).edit().clear().commit()) { "API Key 清除失败" }
    }
}
