# Phase 1 acceptance test plan

What a human with an APK, a real Audiobookshelf server and about forty minutes has to do before Phase 1
can be called closed.

Every case here exists because **no automated test in this repository can perform it**. The unit and
contract tests prove the code does what it was written to do; these prove it does what the requirement
asked for, against a real server, on a real device. Where a case duplicates something a test already
covers, it is because the test covers it against a fake and the requirement is about reality.

Requirement identifiers refer to `PRODUCT_SPEC.md`. **Exit** marks the three Phase 1 exit criteria — those
three are the ones that decide whether Phase 1 closes.

---

## Before you start

### What you need

| | |
| --- | --- |
| Device | Any Android 8.0 (API 26) or newer. `adb` reachable. |
| Server | A real Audiobookshelf instance with at least one library holding several books. |
| Accounts | **Two** on that server. Call them **A** (full access) and **B** (restricted). |
| Account B | Must be granted **a strict subset** of the libraries — set this up in Audiobookshelf under *Settings → Users → B → Libraries*. If your server has only one library, create a second one (it can hold a single file) so that "restricted" means something. |
| Progress | Before you start, play a few minutes of two or three different books **as account A** in the Audiobookshelf web player, at different times. TC-14 needs several books with different last-played timestamps, and the app cannot create them yet — there is no player. |
| Network | A way to disconnect the device from the server: aeroplane mode is enough. |

### Install

```bash
adb install -r app-debug.apk
```

The debug build's application id is **`com.example.shelfplayer.debug`**, which is what every `adb` command
below uses. Debug builds allow `run-as`, so the on-device database and files can be inspected without root.

### Recording results

Fill in **Pass / Fail / Not run** and a note for anything that is not a clean pass. A case that is
*partly* right is a fail with a note — this list is the evidence for closing a phase, and a soft pass is
worth nothing later.

---

## 1. Sign-in and server identity (AUTH-001, PRODUCT_SPEC 6.1, 15)

| ID | Requirement | Steps | Expected | Result |
| --- | --- | --- | --- | --- |
| **TC-01** | AUTH-001, 6.1 | Launch on a clean install. | The sign-in screen appears, asking for a **server address only**. There is no password field yet. | |
| **TC-02** | 6.1 | Type your server's host **without a scheme** (e.g. `books.example.com`) and continue. | The address is accepted, and the confirmed address shown back to you has `https://` in front of it. A line says HTTPS was assumed. | |
| **TC-03** | AUTH-001 | Now type a host that does not exist (e.g. `not-a-real-host.invalid`) and continue. | A clear network/DNS error **on the address stage**. No password field appears. Nothing was sent. | |
| **TC-04** | AUTH-001 | Point at a server with a self-signed or otherwise untrusted certificate, if you have one. | The error identifies a **certificate/security** problem, not "wrong password" and not a generic failure. *If you have no such server, mark Not run — do not guess.* | |
| **TC-05** | 6.1, 15 | Enter the real server address and continue. | Before any password field is usable you can see: the **detected Audiobookshelf version**, and a line saying the connection **is encrypted**. | |
| **TC-06** | 15 | If (and only if) you have an `http://` server, enter it. | An explicit warning that the connection is **not encrypted** and that the password would cross the network in the clear. | |
| **TC-07** | AUTH-001 | Enter account A's username with a **wrong** password. | Rejected with an authentication error. The **username is still filled in**; the password field is cleared. | |
| **TC-08** | AUTH-001 | Enter the correct password. | Sign-in succeeds and you land on the app's main screen. | |

## 2. The shelf (LIB-001, LIB-002)

| ID | Requirement | Steps | Expected | Result |
| --- | --- | --- | --- | --- |
| **TC-09** | LIB-001 | Watch the screen immediately after TC-08. | A sync starts **by itself** — a thin progress bar at the top. You are not asked to trigger it. | |
| **TC-10** | LIB-001 | Wait for it to finish. | Books appear. The progress bar goes away. A line reports the number of books. | |
| **TC-11** | LIB-001 | Force-stop the app and reopen it. | Books are on screen **immediately**, before any sync completes. No full-screen spinner in front of them. | |
| **TC-12** | LIB-001 | Pull down / tap refresh while books are on screen. | The progress bar appears **above** the list; the books stay visible and scrollable throughout. | |
| **TC-13** | LIB-001 | Turn on aeroplane mode and tap refresh. | An error is reported **and the books stay on screen**. The library is never blanked by a failed refresh. | |
| **TC-14** | **LIB-002** | Turn the network back on, refresh, and look at the order of the list. | The books you played in the web player are **at the top, most recently played first**. Books you have never opened come after them, in title order. | |
| **TC-15** | LIB-002 | Look at a book you partly played. | It shows a percentage and a progress bar; the percentage roughly matches where you stopped in the web player. | |
| **TC-16** | LIB-002 | Tap the "Last played" chip through to "Title (A–Z)", "Author", "Series order". | The list reorders immediately for each. Series order puts `2` before `10` — **not** lexicographically. | |
| **TC-17** | LIB-002 | Type part of a book title into the search field. | Results narrow. The **typed text keeps up with your keyboard** — the field never lags. | |
| **TC-18** | LIB-002 | Search for an author's name, then a narrator's name, then a series name. | Each matches. | |
| **TC-19** | LIB-002 | Search for something in no book at all. | A distinct "no matches" message — not the same message as an empty library. | |
| **TC-20** | LIB-004 | Clear the search and open any book. | Title, author, narrator, duration, description and progress are shown, and the progress matches the list. **Cover art is expected to be absent** — see *Known gaps*. | |

## 3. Settings (SET-001, SET-002)

| ID | Requirement | Steps | Expected | Result |
| --- | --- | --- | --- | --- |
| **TC-21** | SET-002 | Open Settings from the top bar. Turn on **Open on libraries**. | Going back, the main screen now lists **libraries**, with a book count on each. | |
| **TC-22** | SET-002 | Tap a library. | Its books open, with their own search and sort. | |
| **TC-23** | SET-001 | Force-stop the app and reopen it. | It still opens on libraries. The setting survived the process dying. | |
| **TC-24** | SET-002 | Turn the setting back off. | The main screen is the shelf of books again. | |

## 4. Two accounts, one server — **Exit criterion 1** (AUTH-002, PRODUCT_SPEC 6.5)

| ID | Requirement | Steps | Expected | Result |
| --- | --- | --- | --- | --- |
| **TC-25** | AUTH-002 | Open the profile switcher, choose *Add a server*, and sign in as account **B** on the **same** server. | Sign-in succeeds. | |
| **TC-26** | AUTH-002 | Open the switcher. | **Both** A and B are listed. B is marked as the one in use. | |
| **TC-27** | **Exit 1** | Switch back to A, then to B, then to A again. | Each switch lands on that account's own library and its own progress. **A's progress never shows under B**, and vice versa. | |
| **TC-28** | 6.5 | Confirm the server was not duplicated: `adb shell run-as com.example.shelfplayer.debug sqlite3 databases/shelfplayer.db "select count(*) from servers; select profileId, username from profiles;"` | **One** server row, **two** profile rows. | |
| **TC-29** | AUTH-002 | Sign **out** of B (not remove). | B stays in the list, marked as needing to sign in again. Its cached library is still browsable when selected. | |
| **TC-30** | AUTH-003 | With B signed out, check its stored credential is gone: `adb shell run-as com.example.shelfplayer.debug ls files/sessions/` | Fewer files than before — B's `.bin` files are gone. A's remain. | |
| **TC-31** | AUTH-002 | Sign B back in from the switcher. | It returns to the **same profile** — B is not duplicated in the list, and its progress is still there. | |
| **TC-32** | AUTH-002, 21 | Tap *Remove* on B and read the dialog **before** confirming. | It says removal deletes B's session, progress and downloads **from this device**, deletes nothing on the server, and does not affect other profiles. | |
| **TC-33** | AUTH-002 | Confirm the removal. | B is gone from the list. **A is untouched** — still signed in, still has its library and its progress. | |

*(Re-add B before section 5.)*

## 5. Restricted access — **Exit criterion 2** (PRODUCT_SPEC 5.2)

This is the criterion the UI cannot prove on its own. The requirement is that unauthorized libraries are
**never written**, not that they are hidden — so the database check is the actual test and the screen is
the sanity check.

| ID | Requirement | Steps | Expected | Result |
| --- | --- | --- | --- | --- |
| **TC-34** | 5.2 | Switch to account **B** (restricted) and let it sync. | Only B's granted libraries and their books appear. | |
| **TC-35** | **Exit 2** | With B active, dump what is on disk: `adb shell run-as com.example.shelfplayer.debug sqlite3 databases/shelfplayer.db "select remoteId, name from libraries;"` | Rows exist for A's libraries (A synced them) but **no row was added for a library only A can see as a result of B's sync**. Compare against the count from before TC-34 — B's sync must not have added any. | |
| **TC-36** | 5.2 | Turn on **Open on libraries** while B is active. | The library list shows **only** B's granted libraries. | |
| **TC-37** | 5.2 | On the server, **revoke** one of B's libraries. Back in the app, refresh as B. | The revoked library and its books disappear from B's shelf, its library list, and search. | |
| **TC-38** | 5.2 | Switch to A and refresh. | A still sees everything. Revoking B's access changed nothing for A. | |

## 6. Offline — **Exit criterion 3** (LIB-001)

| ID | Requirement | Steps | Expected | Result |
| --- | --- | --- | --- | --- |
| **TC-39** | **Exit 3** | With A active and synced, enable aeroplane mode. **Force-stop the app.** Reopen it. | The shelf is fully browsable: books, authors, series, progress. Nothing is blank and nothing hangs. | |
| **TC-40** | Exit 3 | Still offline, search and change sort order. | Both work — they read the cache, not the network. | |
| **TC-41** | Exit 3 | Still offline, open a book. | Its details render from the cache. | |
| **TC-42** | Exit 3 | Still offline, tap refresh. | A clear network error. **The library stays on screen.** | |
| **TC-43** | Exit 3 | Still offline, switch to profile B and back. | Both accounts' cached libraries are browsable offline, each with its own progress. | |
| **TC-44** | LIB-001 | Turn the network back on and refresh. | The sync succeeds and the error clears without restarting the app. | |

## 7. Session expiry (AUTH-004)

Not an exit criterion, but the requirement most likely to be wrong in a way tests cannot catch.

| ID | Requirement | Steps | Expected | Result |
| --- | --- | --- | --- | --- |
| **TC-45** | AUTH-004 | On the server, revoke account A's session (Audiobookshelf: change A's password, or delete its API token). Then refresh in the app. | The profile is **marked as needing to sign in again** — a banner above the shelf and a line on the profile card. It is **not** silently signed out. | |
| **TC-46** | AUTH-004 | With A marked, look at the shelf. | The cached library is still there and still browsable. | |
| **TC-47** | AUTH-004 | Tap refresh several times while marked. | The app does **not** loop login requests. Check the server's logs if you can. | |
| **TC-48** | AUTH-004 | Sign A back in. | The mark clears, the sync resumes, and **the same profile** is reused — progress and settings survive. | |

## 8. Robustness and accessibility (PRODUCT_SPEC 3.1, 3.2, 21)

| ID | Steps | Expected | Result |
| --- | --- | --- | --- |
| **TC-49** | Rotate the device on each screen: sign-in, shelf, settings, book details, switcher. | Nothing is lost — a half-typed search survives, no crash. *A half-typed **password** is expected to be lost: it is deliberately never written to saved state (AUTH-003).* | |
| **TC-50** | Set the system font to its largest size and revisit each screen. | Nothing is clipped, and the sign-in submit button is reachable by scrolling. | |
| **TC-51** | Turn on dark theme. | Every screen is legible. | |
| **TC-52** | Turn on **TalkBack** and walk the shelf, the settings switch and the sign-in form. | Icon buttons are announced by name; the settings row announces its label **and** its on/off state as one control; the sync progress and any error are announced when they appear. | |
| **TC-53** | With TalkBack still on, sign out and in. | No control is unreachable or unlabelled. | |

---

## Known gaps — expected to fail, do not raise as defects

These are **built-and-missing**, not broken. They are listed so a tester does not spend time on them and so
nobody reads a clean run of the table above as "Phase 1 is complete".

| Gap | Requirement | Status |
| --- | --- | --- |
| **No cover art anywhere** | LIB-001, LIB-004 | **In Phase 1's scope and not built.** `coverPath` is synced and stored; the missing piece is the UI plus an authenticated image loader, since a cover URL needs the profile's credential. Deferred by the owner. |
| Thin book metadata | LIB-004 | Genres, tags, publisher, language, publication data and file count are stored but not all shown. |
| No playback of any kind | PLAY-* | Phase 2. Book details say so. |
| No downloads, no offline media | DL-* | Phase 3. "Offline" in TC-39…TC-44 means **browsing cached metadata**, which is all Phase 1 promises. |
| No server name in the switcher | AUTH-002 | `Profile` carries a server id but not the server's name. Showing the wrong one would be worse than showing none. Open item. |
| Settings has one switch | SET-002 | Only the setting something honours is offered. |
| Per-server cleartext opt-in | 15 | The sign-in screen warns; the opt-in is a SET-002 item that does not exist. |
| Search does not match ISBN or ASIN | LIB-002 | Those fields are not synced yet, so matching them would be a promise with nothing behind it. |
| Sort order is not remembered per library | LIB-002 | Persisting it is the remainder of LIB-002. |

## The decision this list feeds

Phase 1's exit criteria are exactly three:

1. **Two accounts on one server can switch** → TC-27, supported by TC-25…TC-33.
2. **Unauthorized libraries never appear** → TC-35, supported by TC-34…TC-38.
3. **Offline cached browse works** → TC-39, supported by TC-40…TC-44.

If those three pass, the criteria are met **and covers are still missing** — so the honest close is
*"Phase 1 exit criteria demonstrated; LIB-001/LIB-004 cover art carried forward."* Record it that way
rather than as "Phase 1 complete", so the carried-forward work is visible when Phase 2 is planned.

If any of the three fails, it is a Phase 1 defect and Phase 2 should not open — `PRODUCT_SPEC 20`'s phases
are sequential for the reason that a playback layer built on a broken profile boundary is worse than no
playback layer.

## Useful commands

```bash
# Install
adb install -r app-debug.apk

# Watch the app's own logs (no tokens are ever logged; PRODUCT_SPEC 14.5)
adb logcat --pid=$(adb shell pidof -s com.example.shelfplayer.debug)

# Inspect the database in place
adb shell run-as com.example.shelfplayer.debug sqlite3 databases/shelfplayer.db ".tables"
adb shell run-as com.example.shelfplayer.debug sqlite3 databases/shelfplayer.db \
  "select remoteId, name from libraries; select count(*) from books;"

# What credentials exist on disk (file names are hashed on purpose)
adb shell run-as com.example.shelfplayer.debug ls -l files/sessions/

# Start completely clean between runs
adb shell pm clear com.example.shelfplayer.debug
```

If `sqlite3` is missing on the device, pull the database instead:

```bash
adb exec-out run-as com.example.shelfplayer.debug cat databases/shelfplayer.db > shelfplayer.db
```

**One caution before pulling anything off the device:** the database holds your server's library — real
titles, real listening history. `PRODUCT_SPEC 14.5` keeps that out of logs and reports; keep it out of
issue attachments too.
