package com.tickedge.auth

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/** PKCE (RFC 7636) helpers for a public-client native app. */
object Pkce {
    private val secureRandom = SecureRandom()
    private val flags = Base64.NO_PADDING or Base64.URL_SAFE or Base64.NO_WRAP

    /** 43-char base64url verifier (from 32 random bytes; within the 43-128 allowed range). */
    fun newVerifier(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return Base64.encodeToString(bytes, flags)
    }

    /** S256 challenge = base64url(sha256(verifier)). */
    fun challengeFor(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(digest, flags)
    }

    /** Cryptographically random `state` / nonce value. */
    fun newNonce(): String {
        val bytes = ByteArray(16)
        secureRandom.nextBytes(bytes)
        return Base64.encodeToString(bytes, flags)
    }
}
