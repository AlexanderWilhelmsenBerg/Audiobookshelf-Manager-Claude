# Delta test — build 0.1.2-phase1

Only what is **new or newly fixed** since the last device run. `docs/phase-1-acceptance.md` remains the
full regression script; this does not replace it.

**Report only what still fails.** A case that passes needs no note.

## Before you start

Two accounts on one server, as before:

- **A** — unrestricted (root, or any account that sees everything).
- **B** — restricted, ideally **by tag inside a library A can also see**. That specific shape is what
  the biggest fix targets; an account merely restricted to different *libraries* will not exercise it.

**Expected on first launch after upgrading:** every profile's shelf is **empty** until its automatic
sync finishes, and the sync starts on its own. That is the database migration, not a regression — the
app cannot know which account was shown which books, so it withdraws the claim and re-establishes it.
Only report this if the shelf is still empty *after* the progress bar finishes.

---

## 1. Previously failing, should now pass

| ID | Was | Steps | Expected |
| --- | --- | --- | --- |
| **D-01** | TC-09, TC-34 | Sign in A, let it sync. Switch to B, let it sync. Look at B's shelf. | B sees **only** its own books. A's restricted books are absent even though they are cached on the device and even though B can see the library they are in. |
| **D-02** | TC-37c | As A, note **Settings → Storage → Books stored** and **Removed on the server**. Switch to B, let it sync, switch back to A. Look again. | Both numbers unchanged. **Visible to this profile** differs between A and B — that pair is the whole proof. |
| **D-03** | TC-43 | With both accounts synced, turn on aeroplane mode. Switch B → A → B. | Each account's own books, offline. B still does not see A's restricted books. |
| **D-04** | TC-08b | Switch to an account that has synced before, on this launch or an earlier one. | A sync starts **by itself** — progress bar at the top. Previously nothing happened. |
| **D-05** | TC-37 | On the server, **revoke** one of B's libraries. In the app, switch away from B and back (do **not** sign out). | The library and its books are gone from B's shelf and from Settings → Libraries. |
| **D-06** | new | On the server, **grant** B a library it did not have. Switch away from B and back, let it sync. | The new library and its books appear. |
| **D-07** | TC-10 | Play a book on the web interface (move the position noticeably). Return to the app and **leave and re-enter the shelf** — do not press refresh. | The new position is shown, and the book moves to the top under "Last played". No manual refresh, no full library sync. |
| **D-08** | TC-45 | **Disable** B on the server. Open the app as B. | B is marked "needs to sign in again". *Changing a password is still expected **not** to do this — Audiobookshelf does not invalidate tokens on a password change. Only report the disable case.* |

## 2. New in this build

| ID | Steps | Expected |
| --- | --- | --- |
| **D-09** | Look at the top bar next to "ShelfPlayer". | A small dot. **Green/primary** when the server is answering. |
| **D-10** | Turn on aeroplane mode and look at the dot. | **Grey**, not red. With no network the app has learned nothing about the server, so it does not blame it. |
| **D-11** | Keep the network on but make the server unreachable — stop it, or disconnect the VPN if it is LAN-only. Pull to refresh. | Dot turns **red**. This is the case a grey dot must not cover: the phone is online and the server is not there. |
| **D-12** | With TalkBack on, focus the dot. | It is announced — "Server reachable" / "Server not reachable" / "Device offline, server status unknown". A colour-only dot would be silent. |
| **D-13** | Aeroplane mode on a **fresh** account with nothing cached. | "No connection" with its own wording, and **no Refresh button** — there is nothing to refresh over. Distinct from the "Could not load your books" error surface. |
| **D-14** | With a cached library, turn aeroplane mode on and look at the shelf. | Books still listed, with the caption **"Offline — showing your cached library"**. The shelf is not replaced. |
| **D-15** | Offline, pull to refresh, then turn the network back on. **Do not touch anything.** | The app refreshes on its own within a few seconds and the error clears. |
| **D-16** | Turn Wi-Fi off and on again while the library is healthy and synced. | **Nothing happens** — no progress bar, no re-sync. The radio changing is not news about the library. |

## 3. Worth a glance

| ID | Steps | Expected |
| --- | --- | --- |
| **D-17** | Switch profiles and count to two. | The switch itself is immediate. A sync may follow; the *switch* must not wait for it. |
| **D-18** | Anywhere the app writes a log, and the diagnostics screen. | No server address, username, book title or token anywhere. |

---

## Known gaps — please do not report these

- **No cover art.** Nothing has ever rendered an image (P1-14).
- **No series grouping.** "Series order" still sorts flat rather than grouping into series with their
  own screen (TC-16, P1-15).
- **No browse by recently added / continue listening / downloaded / author / genre** (P1-16).
- **Sort order is not remembered** between visits (P1-17).
- **Book details are missing** genres, tags, publisher, year, language and download size (P1-18).
- **No websocket.** Progress now arrives on resume and on profile switch rather than instantly while
  you watch the screen. Instant delivery needs the socket transport (P1-07/P1-08), which is not built.
- **Search on a large library still feels slow** (TC-17, P1-27).
- **No settings beyond libraries and storage** (P1-17, P1-22, P1-23).
- **`http://` servers cannot be reached at all** in any build (P1-23).
