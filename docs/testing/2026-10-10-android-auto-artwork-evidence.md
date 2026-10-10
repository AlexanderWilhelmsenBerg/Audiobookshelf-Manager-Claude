# Android Auto artwork evidence — #250 / title metadata — #251

Source: GitHub `main` at `6cff01c9`; working PR is diagnostics + metadata correction, **not yet a proven cover fix**.

## Purpose

The owner reports that Android Auto book covers vanish in the browse library after profile switching, while opening the same book displays its cover; returning to the library does **not** recover it. The opened player and browse rows use different artwork-URI paths. Avoid inferring that the profile library cache is empty simply because the car thumbnail is blank.

## Event Log

All diagnostics use BookWave's existing redacted Playback event logging:

- `Android Auto browse artwork offered`: `parentKind`, returned-page count, count of rows carrying artwork URIs, and count carrying `content://` artwork URIs. Logged after Media3's `onGetChildren` builds the returned page.
- `The Android Auto browse tree was invalidated`: existing profile-boundary versus shape-change trace.
- `Android Auto artwork requested`: Android's content provider received an `openFile` call.
- `Android Auto artwork refused`: invalid-mode / invalid-capability / expired-capability / profile-mismatch.
- `Android Auto artwork failed`: cache-unavailable / read-failed.
- `Android Auto artwork served`: served-cached / served-materialized.

No raw URI, source, title, item ID, profile ID or exception message is included. A provider call only exists if the host actually requests the browse artwork.

## Physical test after installing the PR APK

While parked, start with profile A in Android Auto. Open Continue, Series and an author with covers, then switch to profile B through Android Auto Profiles. Visit the same destinations and observe missing covers. Open one B book whose library artwork is blank, confirm its player cover, then return to its library row. Repeat after disconnect/reconnect with B already active; then switch back to A. Capture the approximate test window, screenshots and redacted BookWave Event Log lines.

## Diagnose without guessing

1. `withArtworkUri=0` for expected illustrated books: investigate `AutoLibrary` projection/cover source.
2. `withContentArtworkUri>0`, no provider `requested` event: determine host cache/URI-handling behavior (absence in captured logs is not conclusive without a complete trace).
3. Provider `refused`: investigate the named authorization/capability stage; preserve the security boundary.
4. Provider `failed`: investigate cache availability/decode/open and materialization.
5. Provider `served` but host shows placeholder: investigate car host rendering/refresh; run DHU/head-unit acceptance.

## Evidence classification

The event log proves which callbacks and file reads BookWave observed. Source/JVM tests do not prove whether the Android Auto host renders two title lines or shows an image. #250 remains open until the real artwork defect is fixed and accepted. #251 has distinct Media3 title/subtitle/artist fields, but exact visual hierarchy is host-owned and requires device/DHU acceptance.
