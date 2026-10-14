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
 *
 * Also usable as a generic encrypted key/value store by passing a different [prefsName] — the
 * FIDScript session does this so its credential lives in its own encrypted file rather than
 * sharing one blob with Deriv tokens.
 */
class SecureTokenStore(
    context: Context,
    private val prefsName: String = "deriv_oauth_secure"
) {
    private val keyAlias = "deriv_oauth_enc_key"
    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

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
        expiresAtEpochMs: Long,
        idToken: String? = null
    ) {
        // stableKey is the authoritative user identifier used for database userId scoping.
        // It is openId when available (OIDC subject), otherwise the id token subject.
        val stableKey = openId?.ifBlank { null } ?: userId?.ifBlank { null } ?: ""
        val json = JSONObject()
            .put("access_token", accessToken)
            .put("refresh_token", refreshToken.orEmpty())
            .put("user_id", userId.orEmpty())
            .put("open_id", openId.orEmpty())
            .put("stable_key", stableKey)
            // The OIDC ID token. Only present when the `openid` scope was granted; exchanged
            // for a FIDScript session via POST /runtime/oauth/id_token. Null on refresh flows.
            .put("id_token", idToken.orEmpty())
        prefs.edit()
            .putString("blob", encrypt(json.toString()))
            .putLong("expires_at", expiresAtEpochMs)
            .apply()
    }

    /** The OIDC ID token used to establish a FIDScript session. Null when unavailable. */
    fun idToken(): String? = field("id_token")

    /** The stable user key used for database userId scoping. */
    fun stableKey(): String? = field("stable_key")

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

    /** Persist the pending PKCE verifier + OAuth state for the current login attempt. */
    fun savePendingAuth(verifier: String, state: String, createdAtEpochMs: Long) {
        prefs.edit()
            .putString("pending_verifier", verifier)
            .putString("pending_state", state)
            .putLong("pending_created_at", createdAtEpochMs)
            .apply()
    }

    /** Retrieve the pending auth data. Returns null if none or expired (15-minute window). */
    fun getPendingAuth(): PendingAuth? {
        val verifier = prefs.getString("pending_verifier", null) ?: return null
        val state = prefs.getString("pending_state", null) ?: return null
        val createdAt = prefs.getLong("pending_created_at", 0L)
        if (createdAt == 0L) return null
        // 15-minute expiry
        if (System.currentTimeMillis() - createdAt > 15 * 60 * 1000L) {
            clearPendingAuth()
            return null
        }
        return PendingAuth(verifier, state)
    }

    /** Clear only the pending auth data (verifier/state). Tokens remain. */
    fun clearPendingAuth() {
        prefs.edit()
            .remove("pending_verifier")
            .remove("pending_state")
            .remove("pending_created_at")
            .apply()
    }

    /** Clear everything including tokens. */
    fun clear() {
        prefs.edit().clear().apply()
    }

    /**
     * Store an arbitrary string in its own AES-GCM encrypted entry.
     * Used by non-Deriv consumers (e.g. the FIDScript session) that want to share this
     * keystore-backed crypto but keep a separate, independently clearable credential file.
     */
    fun putEncrypted(name: String, value: String) {
        prefs.edit().putString(name, encrypt(JSONObject().put("v", value).toString())).apply()
    }

    /** Read a value stored with [putEncrypted]. Null when absent or undecryptable. */
    fun getEncrypted(name: String): String? =
        decrypt(prefs.getString(name, null))?.optString("v")?.takeIf { it.isNotBlank() }

    data class PendingAuth(val verifier: String, val state: String)
}
