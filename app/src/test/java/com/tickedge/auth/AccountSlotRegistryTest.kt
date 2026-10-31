package com.tickedge.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The registry of Deriv accounts.
 *
 * The rules it has to hold: at most [AccountSlotRegistry.MAX_ACCOUNTS] accounts, exactly one
 * active, and removing the active account must leave the app pointing at something real rather
 * than at nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AccountSlotRegistryTest {

    private lateinit var registry: AccountSlotRegistry

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // A clean slate per test: the registry is backed by shared preferences, and a leftover
        // slot from an earlier test would make these assertions pass for the wrong reason.
        context.getSharedPreferences("deriv_account_slots", Context.MODE_PRIVATE).edit().clear().commit()
        registry = AccountSlotRegistry(context)
    }

    @Test
    fun `a new registry has no accounts`() {
        assertTrue(registry.slots().isEmpty())
        assertNull(registry.activeSlotId())
    }

    @Test
    fun `adding an account makes it the active one`() {
        assertTrue(registry.addSlot(DerivSlot(id = "a1", label = "First")))
        assertEquals(1, registry.slots().size)
        assertEquals("a1", registry.activeSlotId())
    }

    @Test
    fun `three accounts can be held`() {
        registry.addSlot(DerivSlot(id = "a1"))
        registry.addSlot(DerivSlot(id = "a2"))
        assertTrue(registry.addSlot(DerivSlot(id = "a3")))
        assertEquals(3, registry.slots().size)
    }

    @Test
    fun `a fourth account is refused`() {
        registry.addSlot(DerivSlot(id = "a1"))
        registry.addSlot(DerivSlot(id = "a2"))
        registry.addSlot(DerivSlot(id = "a3"))
        assertFalse(registry.addSlot(DerivSlot(id = "a4")))
        assertEquals(3, registry.slots().size)
    }

    @Test
    fun `switching changes the active account`() {
        registry.addSlot(DerivSlot(id = "a1"))
        registry.addSlot(DerivSlot(id = "a2"))
        assertTrue(registry.setActive("a2"))
        assertEquals("a2", registry.activeSlotId())
    }

    @Test
    fun `switching to an unknown account is refused`() {
        registry.addSlot(DerivSlot(id = "a1"))
        assertFalse(registry.setActive("nope"))
        assertEquals("a1", registry.activeSlotId())
    }

    @Test
    fun `removing the active account falls back to one that is left`() {
        registry.addSlot(DerivSlot(id = "a1"))
        registry.addSlot(DerivSlot(id = "a2"))
        assertTrue(registry.removeSlot("a2"))
        assertEquals("a1", registry.activeSlotId())
    }

    @Test
    fun `removing the only account leaves nothing active`() {
        registry.addSlot(DerivSlot(id = "a1"))
        assertTrue(registry.removeSlot("a1"))
        assertTrue(registry.slots().isEmpty())
        assertNull(registry.activeSlotId())
    }

    @Test
    fun `the account number is kept alongside the label`() {
        registry.addSlot(DerivSlot(id = "a1", label = "Main", accountNumber = "CR12345"))
        val slot = registry.slots().first()
        assertEquals("CR12345", slot.accountNumber)
        assertEquals("Main", slot.displayLabel)
    }

    @Test
    fun `the account number is used when there is no label`() {
        val slot = DerivSlot(id = "a1", label = "", accountNumber = "CR12345")
        assertEquals("CR12345", slot.displayLabel)
    }

    @Test
    fun `the default slot keeps the original token file`() {
        // The original single-account store. Changing it would sign every existing user out.
        assertEquals(
            "deriv_oauth_secure",
            AccountSlotRegistry.prefsNameFor(AccountSlotRegistry.DEFAULT_SLOT_ID)
        )
    }

    @Test
    fun `other slots get their own token file`() {
        assertEquals(
            "deriv_oauth_secure_a1",
            AccountSlotRegistry.prefsNameFor("a1")
        )
    }

    @Test
    fun `the pending login slot is remembered across the browser round trip`() {
        registry.setPendingSlot("a2")
        assertEquals("a2", registry.pendingSlotId())
        registry.clearPendingSlot()
        assertNull(registry.pendingSlotId())
    }

    @Test
    fun `there is no scope fallback until Deriv rejects the scopes`() {
        assertFalse(registry.scopeFallbackActive())
    }

    @Test
    fun `the scope fallback is in force after a rejection`() {
        registry.setScopeFallbackUsed()
        assertTrue(registry.scopeFallbackActive())
    }

    @Test
    fun `the scope fallback expires so a fixed Deriv dashboard is picked up`() {
        // A permanent flag would mean the app never asked for `openid` again, and cross-device
        // sync would stay off long after the dashboard had been corrected.
        val now = 1_700_000_000_000L
        registry.setScopeFallbackUsed(now)
        assertTrue(
            "the fallback must still be in force inside its window",
            registry.scopeFallbackActive(now)
        )
        assertFalse(
            "the fallback must expire so the app tries the full scope set again",
            registry.scopeFallbackActive(now + AccountSlotRegistry.SCOPE_FALLBACK_WINDOW_MS + 1)
        )
    }

    @Test
    fun `signing out forgets the fallback so the next sign-in tries the full scope set`() {
        registry.setScopeFallbackUsed()
        assertTrue(registry.scopeFallbackActive())
        registry.clearScopeFallback()
        assertFalse(registry.scopeFallbackActive())
    }
}
