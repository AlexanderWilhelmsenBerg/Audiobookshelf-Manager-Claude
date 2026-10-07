# Sign-in route Back — 2026-10-04

> **Historical source-scoped evidence.** Reconciled 2026-10-07: the relevant runtime PR is now merged. Original failures, draft build identities and NOT RUN statements below describe their recorded sources/dates. Later scoped results and current obligations are in the [verification register](roadmap-verification-register.md), [merge record](2026-10-05-merge-delivery.md) and [cross-repository audit](../reviews/2026-10-07-cross-repo-reconciliation.md).


**Classification:** Draft source/automated evidence; physical acceptance pending.
**Owner:** UI & Experience, implementation owner. Requirements: AUTH-001/004, PRODUCT_SPEC 6.1/16.2/17.2/21;
GitHub #176, U-03. Draft [PR #235](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/pull/235),
branch `fix/sign-in-navigation-back`, is stacked on author PR #234 / `fbbb69d3`.

## Implemented behavior

NavHost provides explicit nullable route-navigation capability via signInBackAction. Root onboarding
(previousBackStackEntry absent) supplies null; Add Profile, Sign In Again and other pushed entry points
supply navigateUp. SignInRoute requires the capability and forwards it to SignInScreen. The toolbar
shows a labelled Back action only when that capability exists. Its target is explicitly 48 dp.

The wizard's existing Use a different server callback remains separate and stays inside Sign in.
No ViewModel, draft, password retention, authentication/probe API, profile mutation or successful sign-in
policy is changed. Existing one-shot onSignedIn handling and inclusive popUpTo(SIGN_IN) cleanup remain.
The same NavController owns toolbar and ordinary system Back; physical predictive Back remains pending.

Native 320 dp / 200% text reproduction caught a clipped second title line in the fixed-height toolbar.
The toolbar now measures its localized title against available width and current typography/density,
retaining the standard minimum height and growing only when required. The arrow retains a 48 dp target.

## Automated verification and callers

Six added actual-screen/real-NavHost tests cover root arrow absence, pushed address and credential toolbar
return, system Back return, separate wizard callback, and 320 dp/200% title/target bounds. The scoped
run includes the existing SignInScreenTest and SignInViewModelTest coverage (31 cases total).
Removing navigation capability fails four pushed-screen guards; removing the explicit target fails
the measured-target guard. Reverting adaptive title height fails the rendered-title overflow guard. The initial bounds-only guard
failed to detect clipping; inspecting TextLayoutResult.hasVisualOverflow made it sensitive to the actual defect.
Production files are restored byte-for-byte before full verification.

Caller audit: NavHost root, Home Sign in, Profiles Add Profile and prefilled Sign In Again all resolve
the same SIGN_IN pattern → signInBackAction → required SignInRoute callback → SignInScreen →
SignInTopBar → navigateUp. Existing wizard Back binds only SignInViewModel.onBackToServer;
success binds existing NavHost inclusive credential-route removal. No new server/permission/schema
contract or direct UI API is introduced.

## Pending physical tests

All below are **NOT RUN for this implementation**. Record source/APK version/code/checksum, device/API,
language/font/theme, exact action/result and auth/profile state. Use disposable fixture accounts for
login/cancellation; do not store or export credentials/private server metadata in evidence.

| Case | Phone procedure and required result |
| --- | --- |
| U-03-01 | Fresh/root onboarding: no misleading toolbar Back; forward server/probe/credentials flow remains usable. Do not clear the owner's application to simulate a fresh install; use an isolated test package/emulator. |
| U-03-02 | Profiles → Add Profile, address and credential stages: toolbar/system/predictive Back return to Profiles, without a wizard-stage action or unintended profile change. |
| U-03-03 | Sign In Again: existing server/username prefill retained; cancelling returns to Profiles without modifying the stored profile. Cover idle and in-flight probe/login; observe cancellation boundaries. |
| U-03-04 | Use a different server: remains wizard navigation; fields/drafts follow the existing ViewModel contract. Rotate/recreate/background during each stage; passwords stay memory-only and are not restored after process death. |
| U-03-05 | Successful fresh/add/reauth login: correct destination, one-shot navigation and no credential screen on subsequent Back. Fixture permissions/offline/expired-token/errors remain truthful. |
| U-03-06 | Actual TalkBack: Back label/order and 48 dp target; keyboard/IME Back first dismisses IME as platform policy requires. Predictive gesture cancellation commits no navigation; completed gesture matches toolbar/system Back. |
| U-03-07 | 320/375/414/768 dp, fonts 1.0/1.3/2.0, EN/NB, portrait/landscape; light/dark/AMOLED/dynamic/artwork themes and reduced motion. Full toolbar title/form controls stay reachable, including busy/error/cleartext states. |

This branch includes browse and author runtime changes, but excludes performance/download sibling PRs
#231/#232. A combined verified candidate is needed before testing all lanes in one phone installation.

## Closeout

`ktlintFormat` and `verifyDebug -Pshelfplayer.warningsAsErrors=true` PASS (1m55s; 1,122 tasks),
including all restored Sign-in checks, lint/detekt, compilation and coverage. No classpath change requires
--rerun-tasks. Scoped Sign-in suite: 31 passing cases, including six new actual navigation/render cases.
Three temporary reversions produce four navigation failures, one target failure and one title-overflow
failure respectively; all production files are restored before this final green run.

[Native 320 dp / 200% title and Back target](evidence/sign-in-pushed-320dp-font2.png) uses the default
Material 3 palette and synthetic/empty form state; it does not accept actual phone theming or TalkBack.
CI and signed APK identity will be recorded on the draft PR. Physical U-03-01–07 are NOT RUN;
#176 remains open. No authentication/password/profile policy change or device acceptance is claimed.

Implementation source: `012291ff` (the PR head may include documentation-only closeout commits).

## 2026-10-05 corrected parent integration

Rebased onto the verified author observation/key correction and History timestamp test repair. Restored strict verifyDebug with warnings as errors passes in 2m52s (1,022 tasks). The author and Sign-in slices now contain 24 added cases. Current phone focus is existing PR acceptance; U-03 physical cases remain pending until the exact combined build is installed. Original implementation/artifact evidence above remains historical.
