package com.example.shelfplayer.core.model.auth

import com.example.shelfplayer.core.model.ServerId
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * PRODUCT_SPEC AUTH-002 — the identity rules that decide whether a returning user finds their library
 * or an empty screen.
 */
class SessionIdentityTest {

    private val server = SessionIdentity.serverIdFor("https://books.example")

    /**
     * The property the whole class exists for: reauthenticating must land on the same profile, or the
     * downloads and progress keyed to it are orphaned (product priority 2).
     */
    @Test
    fun `the same account on the same server always derives the same profile id`() {
        val first = SessionIdentity.profileIdFor(server, remoteUserId = "user-1", username = "ada")
        val second = SessionIdentity.profileIdFor(server, remoteUserId = "user-1", username = "ada")

        assertEquals(first, second)
    }

    /** PRODUCT_SPEC AUTH-002 — two users on one server are two profiles. */
    @Test
    fun `two accounts on one server derive different profile ids`() {
        val ada = SessionIdentity.profileIdFor(server, remoteUserId = "user-1", username = "ada")
        val grace = SessionIdentity.profileIdFor(server, remoteUserId = "user-2", username = "grace")

        assertNotEquals(ada, grace)
    }

    /** PRODUCT_SPEC 13.1 — a remote id is only unique within one server. */
    @Test
    fun `the same remote user id on two servers derives different profile ids`() {
        val other = SessionIdentity.serverIdFor("https://other.example")

        assertNotEquals(
            SessionIdentity.profileIdFor(server, remoteUserId = "user-1", username = "ada"),
            SessionIdentity.profileIdFor(other, remoteUserId = "user-1", username = "ada"),
        )
    }

    /**
     * PRODUCT_SPEC AUTH-002 — "a stable local ID independent of username".
     *
     * A rename on the server must not orphan the profile, which is only true while the server sent an
     * id to key on.
     */
    @Test
    fun `a renamed account keeps its profile id when the server sent a user id`() {
        assertEquals(
            SessionIdentity.profileIdFor(server, remoteUserId = "user-1", username = "ada"),
            SessionIdentity.profileIdFor(server, remoteUserId = "user-1", username = "ada.lovelace"),
        )
    }

    /**
     * The documented consequence of the fallback, asserted rather than left implicit: without a server
     * user id there is nothing else stable to key on, so a rename does produce a second profile.
     */
    @Test
    fun `without a server user id the username is what identity falls back to`() {
        val before = SessionIdentity.profileIdFor(server, remoteUserId = null, username = "ada")
        val afterRename = SessionIdentity.profileIdFor(server, remoteUserId = null, username = "ada.lovelace")

        assertNotEquals(before, afterRename)
        assertEquals(before, SessionIdentity.profileIdFor(server, remoteUserId = null, username = "ADA"))
    }

    /** A user id must never be able to collide with a username that happens to look like one. */
    @Test
    fun `an id-keyed profile differs from a name-keyed profile with the same value`() {
        assertNotEquals(
            SessionIdentity.profileIdFor(server, remoteUserId = "ada", username = "ignored"),
            SessionIdentity.profileIdFor(server, remoteUserId = null, username = "ada"),
        )
    }

    /** A trailing slash is not a different server; `ServerUrlNormalizer` and this must agree. */
    @Test
    fun `server identity is case-insensitive and derived from the normalized url`() {
        assertEquals(server, SessionIdentity.serverIdFor("HTTPS://Books.Example"))
        assertNotEquals(server, SessionIdentity.serverIdFor("https://books.example/abs"))
    }

    /**
     * PRODUCT_SPEC 14.5 — a profile id names the token file and appears in log fields, so it must not
     * carry the host or the username that produced it.
     */
    @Test
    fun `a derived id leaks neither the address nor the account name`() {
        val profile = SessionIdentity.profileIdFor(server, remoteUserId = null, username = "ada")

        assertFalse(server.value.contains("books.example"))
        assertFalse(profile.value.contains("ada"))
        assertTrue(profile.value.startsWith("prf_"))
        assertTrue(server.value.startsWith("srv_"))
    }

    /**
     * PRODUCT_SPEC 13.1 — `EntityKey` reserves ASCII Unit Separator and rejects an identifier
     * containing it. A derived id is used as a key component, so it has to be a legal one.
     */
    @Test
    fun `derived ids contain no character the database key format reserves`() {
        val profile = SessionIdentity.profileIdFor(server, remoteUserId = "user-1", username = "ada")

        assertTrue(profile.value.all { it.isLetterOrDigit() || it == '_' })
        assertTrue(server.value.all { it.isLetterOrDigit() || it == '_' })
    }

    @Test
    fun `a blank input is rejected rather than hashed`() {
        assertFailsWith<IllegalArgumentException> { SessionIdentity.serverIdFor("  ") }
        assertFailsWith<IllegalArgumentException> {
            SessionIdentity.profileIdFor(ServerId("srv_x"), remoteUserId = null, username = "  ")
        }
    }
}
