package com.tickedge.auth

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * One signed-in Deriv account.
 *
 * "Account" here means one Deriv *login* — a set of credentials, an identity, and the trade
 * history that belongs to it. Not one Deriv virtual account: a single login already exposes a
 * demo and a real account, and the app already switches between those.
 */
data class DerivSlot(
    /** Stable id. Also the name of the encrypted preferences file holding this slot's tokens. */
    val id: String,
    /** What the user calls it. Defaults to the Deriv account number once it is known. */
    val label: String = "",
    /**
     * The Deriv account number — the thing the user recognises as their account.
     *
     * Empty until the accounts endpoint answers. It is the *identity* the user asked for: history
     * is filed under this account, so two accounts never share a record.
     */
    val accountNumber: String = ""
) {
    /** What to show in the account list. */
    val displayLabel: String
        get() = when {
            label.isNotBlank() -> label
            accountNumber.isNotBlank() -> accountNumber
            else -> "Account"
        }
}

/**
 * The Deriv accounts this device is signed in to, and which one is active.
 *
 * The app supports up to [MAX_ACCOUNTS] of them. Each has its own encrypted token store, its own
 * identity, and — because the data layer is scoped by the slot's identity — its own trade
 * history. Switching accounts therefore switches the history with it, exactly as switching between
 * demo and live already does.
 *
 * The *metadata* (which slots exist, which is active, what they are called) is not secret and lives
 * in plain preferences. The *tokens* never do — each slot's tokens live in its own AES-GCM
 * encrypted blob, addressed by its slot id.
 */
class AccountSlotRegistry(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Every slot, in the order they were added. */
    fun slots(): List<DerivSlot> {
        val raw = prefs.getString(KEY_SLOTS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val o = array.optJSONObject(index) ?: return@mapNotNull null
                val id = o.optString("id").ifBlank { return@mapNotNull null }
                DerivSlot(
                    id = id,
                    label = o.optString("label"),
                    accountNumber = o.optString("accountNumber")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** The active slot's id, or null when no slot exists. */
    fun activeSlotId(): String? = prefs.getString(KEY_ACTIVE, null)?.takeIf { it.isNotBlank() }

    /**
     * Makes [slotId] the active one.
     *
     * Returns false when the slot is not known — switching to an account that was removed would
     * leave the app pointing at credentials that no longer exist.
     */
    fun setActive(slotId: String): Boolean {
        if (slots().none { it.id == slotId }) return false
        prefs.edit().putString(KEY_ACTIVE, slotId).apply()
        return true
    }

    /**
     * Records a new slot and makes it active.
     *
     * Refuses past [MAX_ACCOUNTS]: the limit is a deliberate cap, not a soft target, and a fourth
     * login would mean a fourth set of credentials on the device with no way to tell them apart.
     */
    fun addSlot(slot: DerivSlot): Boolean {
        val current = slots().toMutableList()
        if (current.size >= MAX_ACCOUNTS) return false
        if (current.none { it.id == slot.id }) current.add(slot)
        writeSlots(current)
        prefs.edit().putString(KEY_ACTIVE, slot.id).apply()
        return true
    }

    /** Updates a slot's label and account number. */
    fun updateSlot(slot: DerivSlot): Boolean {
        val current = slots().toMutableList()
        val index = current.indexOfFirst { it.id == slot.id }
        if (index < 0) return false
        current[index] = slot
        writeSlots(current)
        return true
    }

    /**
     * Forgets a slot.
     *
     * Only the metadata. The tokens are cleared separately by the caller, because only it knows
     * which encrypted file holds them — and leaving a token behind for an account the user removed
     * would be the wrong kind of forgetful.
     */
    fun removeSlot(slotId: String): Boolean {
        val current = slots().toMutableList()
        val removed = current.removeAll { it.id == slotId }
        if (!removed) return false
        writeSlots(current)
        if (activeSlotId() == slotId) {
            // Fall back to whatever is left, so removing the active account does not leave the
            // app with no active one.
            prefs.edit().putString(KEY_ACTIVE, current.firstOrNull()?.id.orEmpty()).apply()
        }
        return true
    }

    /**
     * Forgets every slot and the active pointer.
     *
     * The sign-out-of-everything path. The caller clears the token files separately — the
     * registry only ever owns the metadata, and it is the caller that knows which encrypted
     * files hold the real credentials.
     */
    fun clearAll() {
        writeSlots(emptyList())
        prefs.edit().remove(KEY_ACTIVE).remove(KEY_PENDING).apply()
    }

    private fun writeSlots(slots: List<DerivSlot>) {
        val array = JSONArray()
        slots.forEach { slot ->
            array.put(
                JSONObject()
                    .put("id", slot.id)
                    .put("label", slot.label)
                    .put("accountNumber", slot.accountNumber)
            )
        }
        prefs.edit().putString(KEY_SLOTS, array.toString()).apply()
    }

    /**
     * The slot a login is currently in progress for.
     *
     * The OAuth redirect comes back as a bare URL with no indication of which account it belongs
     * to, so the slot has to be remembered across the browser round trip. It is not secret — it is
     * an id — so it lives in plain preferences alongside the other slot metadata.
     */
    fun pendingSlotId(): String? =
        prefs.getString(KEY_PENDING, null)?.takeIf { it.isNotBlank() }

    /** Records that a login is in progress for [slotId]. */
    fun setPendingSlot(slotId: String) {
        prefs.edit().putString(KEY_PENDING, slotId).apply()
    }

    /** Clears the pending-login slot, if any. */
    fun clearPendingSlot() {
        prefs.edit().remove(KEY_PENDING).apply()
    }

    /**
     * Whether the reduced-scope fallback is currently in force.
     *
     * Deriv rejects the whole authorization request when the app asks for a scope its
     * registration does not allow, so asking for `openid` before the Deriv dashboard allows it
     * would leave the user unable to sign in at all.
     *
     * The fallback **expires**, so the app keeps noticing when the dashboard is fixed. A permanent
     * flag would mean the app never asked for `openid` again, and cross-device sync would stay
     * off long after the thing that blocked it had been corrected.
     */
    fun scopeFallbackActive(now: Long = System.currentTimeMillis()): Boolean {
        val at = prefs.getLong(KEY_SCOPE_FALLBACK_AT, 0L)
        if (at <= 0L) return false
        return now - at < SCOPE_FALLBACK_WINDOW_MS
    }

    /** Records that the reduced-scope fallback has been used. */
    fun setScopeFallbackUsed(now: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_SCOPE_FALLBACK_AT, now).apply()
    }

    /**
     * Forgets the reduced-scope fallback.
     *
     * Called when the user signs out, so the next sign-in asks for the full scope set again.
     * That is the moment to retry: the user has just been told to enable `openid` on the Deriv
     * dashboard, and signing out and back in is exactly how they would do it.
     */
    fun clearScopeFallback() {
        prefs.edit().remove(KEY_SCOPE_FALLBACK_AT).apply()
    }

    companion object {
        /** The cap. Three accounts is enough for a trader and few enough to stay recognisable. */
        const val MAX_ACCOUNTS = 3

        /** The slot id used by the original single-session store, so existing sign-ins survive. */
        const val DEFAULT_SLOT_ID = "default"

        private const val PREFS_NAME = "deriv_account_slots"
        private const val KEY_SLOTS = "slots"
        private const val KEY_ACTIVE = "active_slot"
        private const val KEY_PENDING = "pending_slot"
        private const val KEY_SCOPE_FALLBACK_AT = "scope_fallback_at"

        /**
         * How long the reduced-scope fallback stays in force.
         *
         * Long enough that the user is not bounced through the browser twice on every sign-in,
         * short enough that a fixed Deriv dashboard is picked up the same day.
         */
        const val SCOPE_FALLBACK_WINDOW_MS = 12L * 60L * 60L * 1000L

        /**
         * The encrypted preferences file for a slot's tokens.
         *
         * The default slot keeps the original file name, so a user who was already signed in is
         * still signed in after this change — their tokens are read from exactly where they were
         * written.
         */
        fun prefsNameFor(slotId: String): String =
            if (slotId == DEFAULT_SLOT_ID) "deriv_oauth_secure"
            else "deriv_oauth_secure_$slotId"
    }
}
