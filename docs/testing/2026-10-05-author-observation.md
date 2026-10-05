# Author query observation and stable keys — 2026-10-05

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
