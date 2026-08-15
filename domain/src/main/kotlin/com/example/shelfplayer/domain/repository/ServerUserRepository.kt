package com.example.shelfplayer.domain.repository

import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.NewServerUser
import com.example.shelfplayer.core.model.ServerUser

/**
 * PRODUCT_SPEC EPIC USER — the server's own accounts.
 *
 * ### Why there is no `observe`
 *
 * Every other repository in this app exposes a `Flow` from Room, because Room is the source of truth for
 * what the UI shows. This one deliberately does not, and USER-001 is why: *"user list is not cached for
 * offline viewing by default"*.
 *
 * That is a security property rather than a preference. The list carries who exists on somebody's private
 * server, what each account may do, and which libraries each can see — and it is read by an administrator
 * on a device that other household members also use. Not storing it means there is nothing to leak, nothing
 * to go stale, and nothing to show to whoever picks the phone up next.
 *
 * The consequence is that these screens do not work offline, which is correct: an administrator cannot
 * change an account without a server anyway.
 */
interface ServerUserRepository {
    /** PRODUCT_SPEC USER-001 — a fresh read, every time. Admin or root only; the server enforces it too. */
    suspend fun list(): AppResult<List<ServerUser>>

    /**
     * PRODUCT_SPEC USER-002 — create an account.
     *
     * The password travels through this call and is not retained anywhere afterwards: it exists in the
     * request body and in whatever transient state the caller held, which USER-002 requires be cleared.
     */
    suspend fun create(user: NewServerUser): AppResult<ServerUser>

    /**
     * PRODUCT_SPEC USER-003 — disable or re-enable an account.
     *
     * Deleting a user is deliberately not here. USER-003 puts it in later scope "unless thoroughly
     * contract-tested", disabling is what it prefers where the server supports it, and this server does.
     */
    suspend fun setActive(userId: String, isActive: Boolean): AppResult<Unit>
}
