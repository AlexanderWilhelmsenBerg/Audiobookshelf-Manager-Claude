# Isolated Sign-in success and draft checks — 2026-10-05

Requirement AUTH-001/004, spec6.1/16.2/17.2/21, issue176/PR235, U-03.
Device SM-S928B/API36, Norwegian/dark; portrait/landscape and font1.0/1.3 as recorded below.

## Exact scope

Disposable package `org.homebord.bookwave.phoneacceptance20261005.debug`, local debug code2000,
source `2d567b17b36e85cc63ce3750082408d65b9bceb5`; APK SHA256
`4a719e96a884eb82cdbd2cb071fe6651cb97c62d1b3fe168f96b6cf82b9f7281`.
This is a local isolated artifact, not the signed owner APK2192. git diff confirms onboarding,
SignInNavigation and ShelfPlayerNavHost runtime source matches2192 (`4f2edb36`). Other runtime
changes are not accepted by these clone tests. No owner account/profile/media was used or cleared.

The two loopback fixture ports replay captured status/login/authorize/me/auth-refresh/libraries
contract bodies, using the same synthetic account and empty libraries. No endpoint/field/TLS
verification change is introduced. Accounts have no owner titles, downloads or credentials.

## Results

| Case subset | UTC/configuration | Expected and observed | Result |
| --- | --- | --- | --- |
| U-03-05 successful Add |08:08–08:10, font1.0 |One new profile/server (1→2), Home with truthful empty catalogue; system Back returns Profiles, then Home, without a credential screen. Existing profile identity retained. |PASS |
| U-03-03 reauth prefill |08:13 and10:32, font1.0 |Expired active account is marked for reauth; stored username is prefilled, password empty before entry. |PASS |
| U-03-04/07 credentials/configuration |08:14:50–08:15:03, font1.3, landscape→portrait |Entered username/password fields retain contents; original font1.0, rotation0, automatic rotation1 restored/read back. Rotation is manifest-handled; explicit Activity recreation lifecycle was not captured. |Selected draft/configuration PASS |
| U-03-06 keyboard first Back |08:13:20 |Back dismisses keyboard while credential form and entered password remain. |PASS |
| U-03-05 successful reauth |10:32:58–10:33:01, font1.0 |Home opens, same two profile IDs remain; requiresReauthentication1→0. Subsequent Back returns Profiles with no credential screen. |PASS |
| U-03-06 predictive Back |Prepared10:33:36, pushed Add Profile address |Cancelled edge swipe must stay; full swipe must return Profiles. |Owner PASS: cancellation stays; full Back returns Profiles |

Private captures/databases/request hashes and configuration logs remain ignored local evidence.
An earlier automatic approval review could not run because of a usage limit; that login command
was not executed. After resumption, missing local servers/tunnels caused the probe to remain on
Address. Restoring both fixtures/tunnels and repeating the fresh flow passes. These setup/driver
failures are retained and do not supply app-failure or successful-login evidence before the retry.

Remaining matrix: address-stage drafts/background, explicit lifecycle recreation, permissions and
controlled authentication errors/offline cases, full widths/themes/languages/large-text states,
predictive credential/busy paths. Manual TalkBack is excluded from this session at the owner's
request after its selected-tab/count check passed; dedicated unperformed speech cases are not
falsely claimed as run. No PR is marked ready/merged and no issue is closed by these subsets.

Owner predictive Back finding: “Pass — cancellation stays, full Back returns to Profiles”.
A later capture at10:42:55UTC showed Home, so the driver stopped before its expected-Profile
assertion and database snapshot. That later navigation does not invalidate the owner observation;
no post-gesture database audit or independently captured final Profiles screen is claimed.

U-03-07 owner Address layout at simulated320dp width/fontScale2.0: PASS — full title readable,
Back usable, server field/Continue reachable. Native size/font1.0 restored and read back.


## Additional in-flight toolbar Back — 2026-10-05

U-03-02/03, isolated source2d567b17/code2000, API36/Norwegian/font1.0:
at10:53:45UTC both credential fields are disabled during a12-second loopback login delay.
Toolbar Back returns Profiles at10:53:48; after the delayed response, Profiles remains at10:54:04.
All11 table counts and fingerprints and settings hash match the pre-login snapshot: **PASS**.
Fixture delay is restored to0. No real owner account or library was contacted.

The subsequent Credentials gesture preparation initially sent an unnecessary system Back after
Continue had already closed the keyboard. The driver correctly stopped its credential assertion
on Profiles; removing that redundant action prepares the expected empty Credentials screen.
This setup error supplies no app-failure or predictive-gesture acceptance. Owner Credentials-stage
predictive Back cancellation/completion is pending separately.

U-03-02/06 Credentials predictive Back: owner PASS — cancellation stays on Credentials; completed
Back returns directly Profiles. Future functional gesture tests will be automated per owner correction.
