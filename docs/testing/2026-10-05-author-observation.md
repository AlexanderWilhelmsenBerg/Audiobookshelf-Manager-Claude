# Author query observation and stable keys — 2026-10-05

> **Historical source-scoped evidence.** Reconciled 2026-10-07: the relevant runtime PR is now merged. Original failures, draft build identities and NOT RUN statements below describe their recorded sources/dates. Later scoped results and current obligations are in the [verification register](roadmap-verification-register.md), [merge record](2026-10-05-merge-delivery.md) and [cross-repository audit](../reviews/2026-10-07-cross-repo-reconciliation.md).


**Classification:** Draft correction on author PR #234; physical U-08 acceptance remains pending.
Requirements: LIB-002/004, AUTH-002, PRODUCT_SPEC 5.2/17.2/21; PD-006.
Implementation source `617eaf47`, continuing [the original author slice](2026-10-04-author-details.md).

The final caller review found that the privacy-clearing null emitted before a Room query answered was
mapped by AuthorViewModel to “not available”. A pending first/profile-switch query was therefore treated
as a completed missing-author result. The local domain flow now carries AuthorShelfObservation with
separate shelf and loading state. Pending queries clear the shelf and remain loading; answered missing,
revoked or signed-out queries become unavailable. The ViewModel forwards both fields. Cached authorized
books/completion policy remain unchanged, and no direct UI/server call or new endpoint/schema is added.

The Series section's static key also collided with the item key for an opaque SeriesId("heading").
Section/header keys now use an author prefix distinct from all series/book item prefixes.

One real observer-to-AuthorViewModel test covers initial waiting, available data, a delayed profile switch,
and answered missing data. One actual AuthorScreen fixture renders the formerly colliding identity.
Both failed on the previous source; after implementation, temporary reversion of the ViewModel loading
mapping and section key makes exactly those two tests fail again. Source bytes are restored afterwards.
The existing use-case profile-switch guard now also requires loading while the incoming query waits.
The scoped domain/author/Sign-in run passes before the deliberate reversion.

Author coverage is now 18 added cases (initial 16 plus these two); the combined author/Sign-in slices have
24 added cases. Caller audit confirms ObserveAuthorUseCase → AuthorViewModel → AuthorRoute →
AuthorScreen preserves the loading flag and clears private content. Both detail entry points and genre
focus retain their earlier wiring.

Physical acceptance remains NOT RUN: under U-08-02 check stable identities/order; under U-08-06 verify a
slow initial/profile-switch query shows loading, clears outgoing private metadata/artwork, and shows
unavailable only after an answered missing/revoked result. Repeat authorized offline cache, lock,
sign-out and profile-switch cases against the final combined candidate. The original phone matrix and
source-specific historical APK evidence remain in the linked report; APK2187 does not contain this fix.

## Verification

The first restored full gate exposed a pre-existing HistoryRecoveryScreenTest ambiguity after calendar
rollover: the fixed 2026-10-03 event was now older than yesterday, so both day heading and timestamp
contained “2026”. The year-only single-node assertion found two nodes. It now identifies a timestamp
containing both date year and time separator; runtime History code is unchanged. This test-only correction
is required to clear the full gate and preserves the date/time obligation.

Restored strict verifyDebug with warnings as errors passes (1m 51s; 1,022 tasks). The scoped author/History regression run also passes after the timestamp matcher correction. CI on the updated PR head and physical acceptance remain pending; no issue closure claimed.

## Phone-discovered scroll regression and correction

On source `2d567b17` / signed APK2189, 06:01–06:03 UTC, the supplied SM-S928B/API36
showed the mixed author's final standalone row. Opening that Book, its credited Author, a Series,
and returning through the stack restored the original author page at the top. The briefly pending
Room query removed AuthorBooks and its locally remembered LazyColumn state. This is a failed
U-08-05 subcase; the old APK does not accept the correction below.

AuthorScreen now owns rememberLazyListState outside the loading/content branches and passes it
through AuthorBooks to LazyColumn. Private metadata still clears during pending/revoked queries.
The actual-screen guard scrolls through 40 standalone rows, clears the shelf while loading, and
requires the same final row visible when authorized data returns. It fails before the fix, passes
after it, and fails alone (eight other screen cases pass) when the fix is actually reverted.
Fixed source bytes are restored before verification. Caller audit reaches the actual LazyColumn state.

ktlintFormat and restored strict verifyDebug pass (2m28s; 1,023 tasks). Author coverage is now
19 added cases; combined with Sign-in it is 25. A new exact-source signed combined APK and physical
repeat of the same Book/Author/Series Back chain are required. Existing APK2189 results remain
source-specific; U-08-05 scroll restoration stays pending until that repeat passes.
