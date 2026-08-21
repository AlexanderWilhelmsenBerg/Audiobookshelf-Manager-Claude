package com.example.shelfplayer.core.model.auth

import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileRole
import java.time.Instant
import kotlin.time.Duration

/**
 * PRODUCT_SPEC 5.2 — what the server currently says about an account, asked again later.
 *
 * ### Why this is not an [AuthSession]
 *
 * The two carry almost the same fields and must not be the same type, because one of them carries
 * credentials and this one deliberately does not.
 *
 * `POST /api/authorize` — the endpoint that answers this — returns `user.token` and neither
 * `user.accessToken` nor `user.refreshToken`; that is captured, not assumed (`contracts/authorize.json`).
 * `user.token` is the pre-2.26 credential, and `/auth/refresh` does not accept it. Mapping this response
 * into an [AuthSession] would therefore hand a permission refresh the power to overwrite a good,
 * renewable token with a legacy one that works until it expires and then cannot be renewed — a session
 * that dies quietly, days later, for a reason nobody would connect to a permission check.
 *
 * Leaving tokens off the type makes that impossible rather than merely discouraged.
 *
 * ### What it is for
 *
 * PRODUCT_SPEC 5.2 requires a `403` to invalidate the permission cache and refresh the current user, and
 * the grant a profile was signed in with is otherwise never revisited. Without this, access granted or
 * revoked on the server stays invisible until the user signs out and back in — which a device run found
 * as a library that had been revoked and would not go away.
 */
data class AccountState(
    /** The server's own id for this account, when it sent one. Never invented (PRODUCT_SPEC 22.4). */
    val userId: String?,
    val username: String,
    /** Re-read every time: an account promoted or demoted on the server changes which actions are offered. */
    val role: ProfileRole,
    /**
     * PRODUCT_SPEC 5.1 / USER-001 — the account type the server reports, verbatim.
     *
     * `root`, `admin`, `user` or `guest` in `contracts/me.json`. Kept as the server's own string rather than
     * collapsed into [ProfileRole] on the way in, because the mapping is this app's opinion and a type it
     * has never seen must be preservable — `ProfileRole.ofAccountType` maps an unknown one to the least
     * privileged bucket, and losing the original would make that undiagnosable.
     */
    val accountType: String = "",

    val access: LibraryAccess,
    /**
     * PRODUCT_SPEC LIB-001 — every position this account has, as the server has them.
     *
     * It rides along because it is already in the response. `POST /api/authorize` is the cold-start
     * exchange the app performs anyway, and `user.mediaProgress` is part of it — so reading progress
     * costs nothing beyond a call that was already being made. That is the whole of the fix for the
     * acceptance case where a book played on another device needed a manual refresh of the entire
     * library to show up: 491 requests replaced by zero extra ones.
     *
     * Empty means the account has no recorded positions, which is a real state. It does **not** mean
     * "unknown" — a server that answered has told us everything it has.
     */
    val progress: List<AccountProgress> = emptyList(),
    /**
     * PRODUCT_SPEC 11.1 — every bookmark this account has, across every book.
     *
     * Here for exactly the reason [progress] is: it is already in the response. `bookmarks` is a
     * top-level array on the user object in both `authorize.json` and `me.json`, so the app's existing
     * cold-start exchange and its existing permission refresh both carry the whole set — and there is no
     * per-book read route to add, because the server does not have one.
     *
     * Empty means the account has no bookmarks, which is a real state and not "unknown".
     */
    val bookmarks: List<AccountBookmark> = emptyList(),
)

/**
 * One bookmark, as the server reports it (`contracts/me-with-bookmark.json`).
 *
 * Deliberately not `Bookmark`: the difference is `at`. The wire carries **whole seconds** and this type
 * keeps them as the server sent them, because they are the bookmark's identity — the delete route is
 * addressed to the number. `Bookmark` presents a `Duration` for the rest of the app to work in, and the
 * repository is the single place that converts, for the same reason [AccountProgress] is not
 * `MediaProgress`.
 */
data class AccountBookmark(val bookId: LibraryItemId, val atSeconds: Long, val title: String, val createdAt: Instant)

/**
 * One position, as the server reports it (`contracts/media-progress.json`).
 *
 * Deliberately not `MediaProgress`: that type carries a `ProfileId` and a `hasUnsyncedChanges` flag,
 * which are local facts the network layer has no business inventing. The repository owns turning this
 * into the stored form, because only it knows which profile asked and what is already pending upload.
 */
data class AccountProgress(
    val bookId: LibraryItemId,
    val position: Duration,
    val duration: Duration,
    val isFinished: Boolean,
    val updatedAt: Instant,
)
