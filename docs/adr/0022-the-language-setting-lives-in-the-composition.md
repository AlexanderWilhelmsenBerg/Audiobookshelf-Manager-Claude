# ADR-0022: The language setting lives in the composition, and the platform is told as well

- **Status:** Accepted
- **Date:** 2026-08-20
- **Requirements:** PRODUCT_SPEC SET-002 (Appearance/accessibility), 2.10 (accessibility), 3.3 (packaging)
- **Related:** ADR-0019 (BookWave keeps ShelfPlayer's `applicationId`)

## Context

SET-002 asks for a language setting. Android has one — `LocaleManager.setApplicationLocales`, the per-app
language API — and it is the right answer in every way except one: it arrived in API 33, and this app's
`minSdk` is 26.

That gap is not academic. A setting that works on a Pixel and silently does nothing on a four-year-old
phone is worse than no setting, because the user cannot tell which of the two they have. The owner's own
test devices are the reason `minSdk` is 26 in the first place.

The usual way to close the gap is `androidx.appcompat`'s backport, `AppCompatDelegate.setApplicationLocales`.
This app has no AppCompat dependency — it is Compose and `ComponentActivity` throughout — and adding one to
carry a locale would put an entire view-based UI toolkit, its themes and its manifest service on the
classpath for one setting.

## Decision

**The composition carries the language.** `AppLocale` provides a `Configuration` and a `Context` built from
the chosen locale, and every `stringResource` below it resolves against them. This works identically on API
26 and API 36, needs no dependency, and is what makes the setting real on the two thirds of the supported
range that have no platform feature.

**On API 33 and above, `LocaleManager` is told as well.** Not as a duplicate — it reaches somewhere the
composition cannot:

- Three strings are resolved outside any composition: the download notification's title, its progress line
  and its channel name, all read from a `Context` in a WorkManager worker. `LocaleManager` changes what the
  *process* thinks its locale is, so those follow the setting; the composition cannot touch them.
- Android's own Settings → Apps → Language then shows the choice, which is where a user who changed it here
  will later look for it. `res/xml/locales_config.xml` is what populates that list.

The two cannot disagree: once the platform accepts the choice, `LocalConfiguration` already carries it, and
providing the same locale again resolves the same resources.

## Consequences

**A language change recreates the activity on API 33+, and does not below it.** `setApplicationLocales` is a
configuration change. Playback is unaffected either way — the media session outlives the activity by
design (PRODUCT_SPEC PLAY-001, product priority 1) — but the two paths are visibly different: one blinks,
the other does not.

**The platform call has to be guarded, and the guard is load-bearing.** `setApplicationLocales` triggers
recreation, so calling it unconditionally from a composable would recreate the activity on every
recomposition — forever. `ApplyPlatformLocale` compares the primary subtag first, and compares tags rather
than `LocaleList` instances because the platform normalises what it stores: asked for `nb`, a device
reports `nb-NO`.

**Two lists of languages now have to agree.** `AppLanguage` is the in-app picker; `locales_config.xml` is
Android's. Nothing in the build connects them, and forgetting either when a translation is added is silent
in opposite directions — a missing XML entry hides the language from Android's picker, and a missing enum
entry means a language chosen through Android's Settings has no matching entry here. `LocalesConfigTest`
asserts the two sets are equal, and separately that each offered language resolves a string that is not the
English one, which is what catches a `values-xx` directory that was never created.

**A stored tag this build does not ship reads as "follow the system".** `AppLanguage.ofTag` is where that
is decided, and it is the only sensible answer: after a downgrade the `values` directory may be gone, and
falling back to the device's language is visible and harmless where a crash or a silent switch to English
would not be.

## Alternatives rejected

**`androidx.appcompat` for the backport.** An entire UI toolkit for one setting, in an app that has
deliberately never depended on it.

**API 33 and above only.** Rejected on the grounds above: a setting that does nothing on a supported device
is a bug that looks like a broken phone.

**Restarting the process ourselves on older releases.** `Runtime.exit` plus a relaunch would make the two
paths behave alike, and would also kill the playback service mid-book. Product priority 1 settles it.
