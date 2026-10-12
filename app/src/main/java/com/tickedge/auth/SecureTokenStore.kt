package com.tickedge.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/**
 * Stores the Deriv OAuth access/refresh tokens + identity in an AES-GCM encrypted blob, with the
 * key held in Android Keystore. No plain tokens, no third-party secure-storage library.
 */
class SecureTokenStore(context: Context) {
    private val keyAlias = "deriv_oauth_enc_key"
    private val prefs = context.getSharedPreferences("deriv_oauth_secure", Context.MODE_PRIVATE)

    private companion object {
        const val GCM_IV_LENGTH = 12
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun getOrCreateKey(): SecretKey {
        keyStore().getKey(keyAlias, null)?.let { return it as SecretKey }
        val generator = KeyGenerator.getInstance("AES", "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(data: String?): JSONObject? {
        if (data.isNullOrBlank()) return null
        val combined = Base64.decode(data, Base64.NO_WRAP)
        if (combined.size <= GCM_IV_LENGTH) return null
        val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
        val body = combined.copyOfRange(GCM_IV_LENGTH, combined.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return try {
            JSONObject(String(cipher.doFinal(body), Charsets.UTF_8))
        } catch (e: Exception) {
            null
        }
    }

    fun saveTokens(
        accessToken: String,
        refreshToken: String?,
        userId: String?,
        openId: String?,
        expiresAtEpochMs: Long
    ) {
        val json = JSONObject()
            .put("access_token", accessToken)
            .put("refresh_token", refreshToken.orEmpty())
            .put("user_id", userId.orEmpty())
            .put("open_id", openId.orEmpty())
        prefs.edit()
            .putString("blob", encrypt(json.toString()))
            .putLong("expires_at", expiresAtEpochMs)
            .apply()
    }

    private fun field(name: String): String? {
        val value = decrypt(prefs.getString("blob", null))?.optString(name)
        return if (value.isNullOrBlank()) null else value
    }

    fun accessToken(): String? = field("access_token")
    fun refreshToken(): String? = field("refresh_token")
    fun userId(): String? = field("user_id")
    fun openId(): String? = field("open_id")

    fun expiresAtEpochMs(): Long = prefs.getLong("expires_at", 0L)

    /** True when a token exists and its lifetime has lapsed. */
    fun isExpired(): Boolean {
        val exp = expiresAtEpochMs()
        return exp > 0 && System.currentTimeMillis() >= exp
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
