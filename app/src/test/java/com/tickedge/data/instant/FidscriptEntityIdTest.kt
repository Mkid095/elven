package com.tickedge.data.instant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.UUID

/**
 * The entity ids the cross-device sync rests on.
 *
 * These are pure functions over a user id and a contract id, which is the only reason the sync can
 * work at all: a device that has been wiped has no stored id to read back, so push and pull must
 * arrive at the same value by calculation alone.
 */
class FidscriptEntityIdTest {

    /**
     * The property everything else depends on. If this fails, a push writes one row and the pull
     * looks for another, and settings silently never come back.
     */
    @Test
    fun `settings id is derived, not random`() {
        assertEquals(
            InstantDbClient.settingsEntityId("user-abc"),
            InstantDbClient.settingsEntityId("user-abc")
        )
    }

    @Test
    fun `different users never share a settings row`() {
        assertNotEquals(
            InstantDbClient.settingsEntityId("user-abc"),
            InstantDbClient.settingsEntityId("user-xyz")
        )
    }

    /**
     * Trades and settings are keyed on different prefixes, so a trade can never collide with the
     * settings row it happens to share a user with.
     */
    @Test
    fun `a trade id never collides with a settings id for the same input`() {
        assertNotEquals(
            InstantDbClient.settingsEntityId("contract-1"),
            InstantDbClient.tradeEntityId("contract-1")
        )
    }

    /**
     * Re-uploading a trade must land on its own row rather than a second one — that is what makes
     * the outbox safe to retry after a failed or unknown-outcome write.
     */
    @Test
    fun `a trade id is stable across retries`() {
        assertEquals(
            InstantDbClient.tradeEntityId("16894505799"),
            InstantDbClient.tradeEntityId("16894505799")
        )
    }

    @Test
    fun `different contracts get different rows`() {
        assertNotEquals(
            InstantDbClient.tradeEntityId("16894505799"),
            InstantDbClient.tradeEntityId("16894514219")
        )
    }

    /** The wire format requires a bare UUID with no braces or dashes-in-the-wrong-place. */
    @Test
    fun `ids are in the canonical uuid form the server expects`() {
        val settings = InstantDbClient.settingsEntityId("user-abc")
        val trade = InstantDbClient.tradeEntityId("contract-1")

        listOf(settings, trade).forEach { id ->
            assertEquals("not a bare uuid: $id", id, UUID.fromString(id).toString())
        }
    }

    /**
     * Not a property of the format but of the inputs: a blank user id still has to produce a
     * stable id, because `settingsEntityId` is called before anything checks whether a session
     * actually exists.
     */
    @Test
    fun `a blank id still produces a usable uuid`() {
        val id = InstantDbClient.settingsEntityId("")
        assertEquals(id, InstantDbClient.settingsEntityId(""))
        assertEquals(id, UUID.fromString(id).toString())
    }
}