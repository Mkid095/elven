package com.tickedge.auth

/** A Deriv-identified user: a stable id (and optional display name / open_id from the token). */
data class DerivUser(
    val id: String,
    val displayName: String? = null,
    val openId: String? = null
) {
    val stableKey: String
        get() = (openId ?: id).ifBlank { id }
}

/**
 * Explicit application authentication lifecycle states (used by navigation + UI).
 * Avoids scattered booleans for representing auth.
 */
sealed class AuthState {
    object Unauthenticated : AuthState()
    object Authenticating : AuthState()
    data class Authenticated(val user: DerivUser) : AuthState()
    object SessionExpired : AuthState()
    object Reconnecting : AuthState()
    data class AuthenticationError(val message: String) : AuthState()
}
