package com.example.shelfplayer.core.model

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PRODUCT_SPEC 24.4 / ADR-0024 — the version floor, and the direction its uncertainty resolves in.
 *
 * The interesting tests here are the ones about *not* refusing. A version gate that fails closed locks
 * somebody out of their own working server over a build string nobody anticipated, and that is a worse
 * outcome than letting an unrecognised version through — which is the opposite of how the profile lock
 * resolves its own uncertainty, for reasons the two classes each state.
 */
class ServerVersionTest {

    @Test
    fun `an ordinary version parses`() {
        assertEquals(ServerVersion(2, 36, 0), ServerVersion.parse("2.36.0"))
        assertEquals(ServerVersion(2, 26, 0), ServerVersion.parse("2.26.0"))
    }

    /** Audiobookshelf's own tags carry a `v`. */
    @Test
    fun `a leading v is tolerated`() {
        assertEquals(ServerVersion(2, 36, 0), ServerVersion.parse("v2.36.0"))
    }

    /** A two-component version is a version. The missing patch is zero, not a parse failure. */
    @Test
    fun `a missing patch component reads as zero`() {
        assertEquals(ServerVersion(2, 26, 0), ServerVersion.parse("2.26"))
    }

    /** A pre-release or build suffix must not make the whole string unreadable. */
    @Test
    fun `a suffix is ignored rather than fatal`() {
        assertEquals(ServerVersion(2, 36, 0), ServerVersion.parse("2.36.0-beta.1"))
        assertEquals(ServerVersion(2, 36, 0), ServerVersion.parse("2.36.0+build7"))
    }

    @Test
    fun `nonsense does not parse`() {
        assertNull(ServerVersion.parse(null))
        assertNull(ServerVersion.parse(""))
        assertNull(ServerVersion.parse("   "))
        assertNull(ServerVersion.parse("unknown"))
    }

    @Test
    fun `ordering compares each component in turn`() {
        assertTrue(ServerVersion(2, 36, 0) > ServerVersion(2, 26, 0))
        assertTrue(ServerVersion(2, 26, 1) > ServerVersion(2, 26, 0))
        assertTrue(ServerVersion(3, 0, 0) > ServerVersion(2, 99, 99))
        assertEquals(0, ServerVersion(2, 26, 0).compareTo(ServerVersion(2, 26, 0)))
    }

    /**
     * The floor itself. 2.25 is where the refreshable token does not yet exist, so AUTH-004's silent
     * renewal would fail hours later on a device and read as a random sign-out.
     */
    @Test
    fun `a server below the floor is refused and the floor itself is accepted`() {
        assertFalse(ServerVersion.isSupported("2.25.0"))
        assertFalse(ServerVersion.isSupported("2.1.0"))
        assertFalse(ServerVersion.isSupported("1.99.99"))

        assertTrue(ServerVersion.isSupported("2.26.0"))
        assertTrue(ServerVersion.isSupported("2.36.0"))
        assertTrue(ServerVersion.isSupported("3.0.0"))
    }

    /**
     * **The direction that matters.**
     *
     * An unparseable version is allowed through. A self-hosted server reporting something unexpected is far
     * more likely to be a working server with an unusual build string than a genuinely ancient one, and
     * refusing it would strand its owner with no way to argue. The gate refuses only what it positively
     * recognises as too old.
     */
    @Test
    fun `an unrecognised version is allowed rather than refused`() {
        assertTrue(ServerVersion.isSupported(null), "a server that reported no version is not thereby old")
        assertTrue(ServerVersion.isSupported("unknown"))
        assertTrue(ServerVersion.isSupported("nightly"))
        assertTrue(ServerVersion.isSupported(""))
    }

    /** The floor is stated once, so the check and the message shown to the user cannot drift. */
    @Test
    fun `the floor is the documented one`() {
        assertEquals(ServerVersion(2, 26, 0), ServerVersion.Minimum)
        assertEquals("2.26.0", ServerVersion.Minimum.toString())
    }
}
