# Privacy

ShelfPlayer is an unofficial client for a server **you** run. This describes what the app does with
your data.

## What leaves your device

Phase 0: **nothing**. The app makes no network requests. It opens a demo library bundled with the
build.

From Phase 1, the app talks to exactly one place: the Audiobookshelf server you configure. There is
no ShelfPlayer backend, no analytics service and no crash-reporting service. Your server address,
credentials, library contents and listening history stay between your device and your server.

## What stays on your device

- Cached library metadata, so browsing works offline (`LIB-001`).
- Your playback positions and listening sessions, synchronized to your server (`PLAY-004`,
  `PLAY-005`).
- Downloaded audio, in app-private storage that other apps cannot read (`DL-003`).
- Settings, in Proto DataStore. Nothing sensitive is stored there.
- Authentication tokens, encrypted with a key held in the Android Keystore. Passwords are never
  stored (`AUTH-003`).

## Backup

Cloud backup and device-to-device transfer are switched off for this app. A restored token would be
either undecryptable or, worse, usable elsewhere, and your listening history is not something that
should travel silently.

## Logs and diagnostics

Logs are redacted by default. The following never appear in a log line, in any build:

- tokens, cookies, passwords and authorization headers;
- your server's hostname or base URL;
- your username;
- book titles, subtitles and descriptions;
- filesystem paths.

Redacted values are replaced by a short non-reversible digest so that two log lines about the same
item can be recognised as related without naming it. Exception messages are not logged either — only
the exception type — because messages routinely contain the hostname or the media path.

Two opt-in settings can widen this (`SET-002`), both **off** by default:

- *Include server host in diagnostics*
- *Include media titles in diagnostics*

Neither can reveal a token, a password or a cookie. Those carry no value in the logging system at
all, so there is nothing to reveal.

A diagnostic export will be produced only when you ask for one, and will include a report of what was
redacted (`PRODUCT_SPEC 14.5`).

## Permissions

Phase 0 requests only `INTERNET` and `ACCESS_NETWORK_STATE`. Foreground-service, notification and
Bluetooth permissions arrive with the features that need them, so an install cannot request more than
the app can use. Bluetooth device selection will request the minimum Nearby Devices permission
required, and the app never scans for nearby devices in the background (`PRODUCT_SPEC 3.4`).
