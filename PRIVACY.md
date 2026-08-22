# Privacy

BookWave is an unofficial client for an Audiobookshelf server **you** run. This describes what the app
does with your data, as of version 0.9.11.

## What leaves your device

The app talks to exactly one place: **the Audiobookshelf server you configure.** There is no BookWave
backend, no analytics service and no crash-reporting service. Your server address, credentials, library
contents and listening history stay between your device and your server.

What the app sends there, and nothing else:

- Your username and password, once, to sign in — over HTTPS, to the address you typed (`AUTH-001`).
- The access token on every subsequent request, as an `Authorization` header (`AUTH-003`).
- Your playback position and listening sessions, so progress follows you between devices
  (`PLAY-004`, `PLAY-005`).
- A **random per-install identifier** with each playback session, as `deviceInfo.deviceId`. It is a UUID
  generated on first use — never a hardware, advertising or Android ID — because your server uses it only
  to group one device's sessions, which a random value does exactly as well.
- Your bookmarks, and whether you marked a book finished.
- Metadata edits, cover uploads, scans, and account changes — but only when you ask for them and only if
  your account holds the permission (`EPIC MGR`, `EPIC USER`).

Two of the management features cause **your server** to reach a third party on your behalf: searching for
metadata matches queries the providers your server is configured with (Audible, Google Books and similar).
The app never contacts them directly, and the search terms it sends to your server are the ones you typed.

## What stays on your device

- Cached library metadata, so browsing works offline (`LIB-001`).
- Your playback positions, listening sessions, bookmarks and seek history.
- Downloaded audio, in app-private storage that other apps cannot read (`DL-003`). A download written to
  a removable volume is on that card, and a card can be read elsewhere — that is the trade a removable
  volume is.
- Settings, in Proto DataStore. Nothing sensitive is stored there.
- Authentication tokens, encrypted with a key held in the Android Keystore. **Passwords are never
  stored** (`AUTH-003`).
- A profile's passcode lock, if you set one — as a verifier rather than the passcode itself, described
  below (`AUTH-005`).

## The profile passcode

A profile can ask for a passcode, or for the phone's own fingerprint prompt where the phone offers one,
when you open the app (`PRODUCT_SPEC 3.2`, `AUTH-005`). What it protects is **which account this device
shows**: your library, your position in it, your bookmarks and your history, from somebody else holding
your unlocked phone. A locked account cannot be switched to from another one on the device either. It is
not a second sign-in and it changes nothing your server will accept from anybody — `AUTH-003` puts the lock
around profile selection and not around server authentication.

**The passcode itself is never stored.** What is written is a value derived from it — PBKDF2-HMAC-SHA256,
over a salt generated for that record alone — which can confirm a later guess and cannot produce the
original. That record is then encrypted under a key held in the Android Keystore, a separate key from the
one the tokens use, and neither key can leave this device.

That is worth stating precisely, because it is less than it sounds like. A six-digit passcode behind a key
derivation function is not a defence against somebody who has the file: the possibilities are few enough
that a graphics card gets through all of them. What the Keystore wrap buys is one thing, which is that
reading the record needs code running on this phone rather than a copy of a file taken off it. The lock is
a curtain in front of an account on a phone in somebody's hand, and that is the whole of what it is for.

Your fingerprint never reaches the app. The prompt belongs to Android, which answers yes or no, and the app
takes that answer as permission to open the account — so biometric unlock here is a rule the app follows
rather than something the encryption enforces.

Four things the lock does not cover, and the app names the same four on the screen that asks for the
passcode rather than leaving you to discover them:

- Play, pause and skip stay available from the media notification and from the phone's lock screen, and
  the book's title and cover stay visible there.
- A connected car can still browse and play that account's library.
- Downloaded audio stays ordinary, unencrypted files. The passcode does not encrypt them.
- The lock is no defence at all against somebody who can read this phone's files.

If you forget the passcode, signing in to that account again with its server password clears it. That is a
feature rather than a hole: the account password is a higher bar than six digits, and this lock was never
about server authentication in the first place. Its cost is on screen before you choose a passcode — it
needs your server to be reachable, so it does not work offline. The same route is the way back if the
record becomes unreadable, which a change to the phone's own lock screen can cause: an unreadable record
counts as locked rather than as unlocked, because the alternative is a lock that opens when a disk read
fails.

## Backup

Cloud backup and device-to-device transfer are switched off for this app. A restored token would be
either undecryptable or, worse, usable elsewhere, and your listening history is not something that should
travel silently.

The practical consequence is worth stating plainly: **moving to a new phone means signing in again and
re-downloading your books.** Nothing is recoverable from a backup, by design.

## Logs and diagnostics

Logs are redacted by default. The following never appear in a log line, in any build:

- tokens, cookies, passwords and authorization headers;
- your server's hostname or base URL;
- your username;
- book titles, subtitles and descriptions;
- filesystem paths.

Redacted values are replaced by a short non-reversible digest so that two log lines about the same item
can be recognised as related without naming it. Exception messages are not logged either — only the
exception type — because messages routinely contain the hostname or the media path.

Two opt-in settings can widen this (`SET-002`), both **off** by default:

- *Include server host in diagnostics*
- *Include media titles in diagnostics*

Neither can reveal a token, a password or a cookie. Those carry no value in the logging system at all, so
there is nothing to reveal.

### The debug console

Settings → About offers a **debug console**: one block of text describing this install, with a copy
button. Nothing sends it anywhere — it goes to your clipboard, and you decide where it goes from there
and can read it first.

It deliberately does **not** contain your server's address, your library names, any book title or any
device name. The server is described by its version and its confirmed capabilities; libraries appear as a
count. The event log's lines are included verbatim, and are safe for the reason above: nothing reaches
the log unredacted. `DiagnosticsReportTest` plants each of those private values in the state the report is
built from and fails if any of them comes out (`PRODUCT_SPEC 14.5`).

## Permissions

| Permission | Why |
| --- | --- |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Reaching your server, and knowing whether it is reachable. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Playback continues with the screen off and the app in the background (`PLAY-001`). |
| `FOREGROUND_SERVICE_DATA_SYNC` | A download continues when you leave the app (`DL-001`). |
| `POST_NOTIFICATIONS` | The media notification and the download progress notification. Refusing it costs the notifications, not the audio or the download — so the app does not treat a refusal as an error, and asks only when you first play something. |
| `WAKE_LOCK` | Held and released by Media3 while audio plays. Nothing in this app calls `PowerManager` directly. |

The app declares a query for one package — Android Auto — so that Settings can tell you whether it is
installed when explaining why the app might be missing from a car dashboard. That grants the ability to
ask whether it exists and nothing more.

**The app never scans for nearby devices**, and requests no location, contacts, calendar, camera,
microphone or Bluetooth-scanning permission. Output devices are recognised from the audio routes Android
already reports to a media app — a kind and an advertised name, never a hardware address
(`PRODUCT_SPEC 3.4`, `ROUTE-002`).
