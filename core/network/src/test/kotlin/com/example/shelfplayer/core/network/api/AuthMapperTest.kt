package com.example.shelfplayer.core.network.api

import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.ProfileRole
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PRODUCT_SPEC 22.5 — the response shapes below were observed on Audiobookshelf 2.36.0 on
 * 2026-08-05, not copied from a reference.
 *
 * The field set and nesting come from a real `POST /login`; token values are placeholders of the
 * observed lengths. `docs/api-compatibility.md` records the capture.
 */
class AuthMapperTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun parse(body: String) = json.decodeFromString<LoginResponseDto>(body)

    @Test
    fun `tokens are read from user, not from the top level`() {
        val response = parse(LOGIN_WITH_HEADER)

        val result = AuthMapper.toSession(response.user)

        assertIs<AppResult.Success<*>>(result)
        val session = (result as AppResult.Success).value
        assertEquals("claudecode", session.username)
        assertEquals("access-token-value", session.accessToken.value)
        assertEquals("refresh-token-value", session.refreshToken?.value)
        assertTrue(session.isRenewable)
    }

    /**
     * The behaviour the whole `x-return-tokens` header exists for. Verified against a real server:
     * without the header the body carries `refreshToken: null` and the value is set as an HttpOnly
     * cookie a native client cannot read.
     */
    @Test
    fun `a login without the tokens header yields a session that cannot be renewed`() {
        val result = AuthMapper.toSession(parse(LOGIN_WITHOUT_HEADER).user)

        val session = assertIs<AppResult.Success<*>>(result).run { (result as AppResult.Success).value }
        assertNull(session.refreshToken)
        assertFalse(session.isRenewable, "a null refresh token must not look renewable")
    }

    /**
     * PRODUCT_SPEC 10.4 — servers predating 2.26 return only `token`. Preferring it when both are
     * present would produce a session that expires and cannot refresh, because the legacy token is
     * not accepted by `/auth/refresh`.
     */
    @Test
    fun `accessToken wins over the legacy token, which is still accepted alone`() {
        val bothPresent = AuthMapper.toSession(parse(LOGIN_WITH_HEADER).user)
        assertEquals(
            "access-token-value",
            (bothPresent as AppResult.Success).value.accessToken.value,
        )

        val legacyOnly = AuthMapper.toSession(parse(LOGIN_LEGACY_ONLY).user)
        assertEquals(
            "legacy-token-value",
            (legacyOnly as AppResult.Success).value.accessToken.value,
        )
    }

    /**
     * PRODUCT_SPEC 5.2 — an empty `librariesAccessible` means "everything" when
     * `accessAllLibraries` is set. Reading the list alone would hide every library from an admin.
     */
    @Test
    fun `accessAllLibraries grants libraries the list does not name`() {
        val admin = (AuthMapper.toSession(parse(LOGIN_WITH_HEADER).user) as AppResult.Success).value

        assertTrue(admin.hasAllLibraryAccess)
        assertTrue(admin.canAccess(LibraryId("a-library-never-listed")))
        assertEquals(ProfileRole.Admin, admin.role)
    }

    @Test
    fun `a restricted account can reach only its granted libraries`() {
        val user = (AuthMapper.toSession(parse(LOGIN_RESTRICTED).user) as AppResult.Success).value

        assertEquals(ProfileRole.Listener, user.role)
        assertFalse(user.hasAllLibraryAccess)
        assertTrue(user.canAccess(LibraryId("lib-allowed")))
        assertFalse(user.canAccess(LibraryId("lib-forbidden")))
    }

    /** PRODUCT_SPEC 5.1 — an unknown server role must not be promoted. */
    @Test
    fun `an unrecognized account type is treated as the least privileged`() {
        val session = (AuthMapper.toSession(parse(LOGIN_UNKNOWN_TYPE).user) as AppResult.Success).value

        assertEquals(ProfileRole.Listener, session.role)
    }

    /** PRODUCT_SPEC SYNC-001 — a missing field is a typed error, never a crash. */
    @Test
    fun `a response without a token reports a compatibility error naming the field`() {
        val result = AuthMapper.toSession(parse(LOGIN_NO_TOKEN).user)

        val error = assertIs<AppResult.Failure>(result).error
        val compatibility = assertIs<AppError.ApiCompatibility>(error)
        assertEquals("user.accessToken", compatibility.missingField)
        assertFalse(compatibility.isRetryable)
    }

    @Test
    fun `a disabled account is refused even though the server returned a token`() {
        val result = AuthMapper.toSession(parse(LOGIN_DISABLED).user)

        assertIs<AppError.Authorization>(assertIs<AppResult.Failure>(result).error)
    }

    /** PRODUCT_SPEC 14.5 — a token must not be printable by accident. */
    @Test
    fun `a token never renders its value`() {
        val session = (AuthMapper.toSession(parse(LOGIN_WITH_HEADER).user) as AppResult.Success).value

        assertFalse(session.accessToken.toString().contains("access-token-value"))
        assertFalse("$session".contains("access-token-value"))
    }

    private companion object {
        // Field set and nesting as observed on 2.36.0.
        const val LOGIN_WITH_HEADER = """
            {"user":{"id":"5cf6f5d5-1f6c-4d0b-8d1a-2b3c4d5e6f70","username":"claudecode",
            "type":"admin","accessToken":"access-token-value","refreshToken":"refresh-token-value",
            "token":"legacy-token-value","isActive":true,"isLocked":false,
            "permissions":{"accessAllLibraries":true,"accessAllTags":true,"download":true,
            "update":true,"delete":false,"upload":true},"librariesAccessible":[],
            "mediaProgress":[],"bookmarks":[]},"userDefaultLibraryId":"lib-1"}
        """

        const val LOGIN_WITHOUT_HEADER = """
            {"user":{"username":"claudecode","type":"admin","accessToken":"access-token-value",
            "refreshToken":null,"token":"legacy-token-value","permissions":{"accessAllLibraries":true},
            "librariesAccessible":[]}}
        """

        const val LOGIN_LEGACY_ONLY = """
            {"user":{"username":"claudecode","type":"admin","token":"legacy-token-value",
            "permissions":{"accessAllLibraries":true},"librariesAccessible":[]}}
        """

        const val LOGIN_RESTRICTED = """
            {"user":{"username":"listener","type":"user","accessToken":"access-token-value",
            "permissions":{"accessAllLibraries":false},"librariesAccessible":["lib-allowed"]}}
        """

        const val LOGIN_UNKNOWN_TYPE = """
            {"user":{"username":"someone","type":"curator","accessToken":"access-token-value",
            "permissions":{"accessAllLibraries":false},"librariesAccessible":[]}}
        """

        const val LOGIN_NO_TOKEN = """
            {"user":{"username":"claudecode","type":"admin","permissions":{"accessAllLibraries":true}}}
        """

        const val LOGIN_DISABLED = """
            {"user":{"username":"claudecode","type":"user","accessToken":"access-token-value",
            "isActive":false,"permissions":{"accessAllLibraries":false},"librariesAccessible":[]}}
        """
    }
}
