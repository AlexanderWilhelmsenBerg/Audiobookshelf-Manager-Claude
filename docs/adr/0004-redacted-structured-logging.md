# ADR-0004: Redaction is a property of the field type

- **Status:** Accepted
- **Date:** 2026-08-04
- **Requirements:** PRODUCT_SPEC 14.5, AUTH-003, SET-002, 22.20

## Context

`PRODUCT_SPEC 14.5` requires that authorization headers, cookies, passwords, tokens in URLs,
filesystem paths, server hosts, usernames, media titles and descriptions are redacted **by default**.

The usual approach — a redacting formatter that pattern-matches known-sensitive substrings — fails in
the direction that matters. It cannot know that `"Marisol Holt/The Salt Harbour/part01.mp3"` is a
media path, and a developer writing `logger.d("loading $url")` bypasses it entirely. Timber with a
custom `Tree` has the same hole: the message is already a formatted string by the time the tree sees
it.

## Decision

Logging takes structured events, and the **type of each field** decides what survives:

```kotlin
logger.warn(
    LogCategory.Network,
    "Request failed",                       // constant string, never interpolated
    LogField.Secret("authorization"),       // no value is even carried
    LogField.ServerHost("host", baseUrl),   // digest unless opted in
    LogField.MediaTitle("title", book.title),
    LogField.Public("status", 503),         // renders verbatim
)
```

- `LogField.Secret` carries **no value at all**. There is nothing to leak even if the policy were
  widened, in any build, under any setting.
- `ServerHost` and `MediaTitle` are governed by `RedactionPolicy`, whose two flags map to the
  `SET-002` diagnostics opt-ins and default to off.
- `Username`, `FilePath`, `Url` and `Identifier` always redact. A path keeps only its extension; a
  URL keeps its scheme and the *shape* of its path, dropping userinfo, query and fragment.
- Redacted values render as a short stable digest rather than a constant `***`, so two events about
  the same book still correlate in a diagnostic bundle without naming the book.
- A `Throwable` renders as its exception-class chain only. `UnknownHostException.getMessage()` *is*
  the hostname and `FileNotFoundException.getMessage()` *is* the media path, so the message is never
  written.

`Logger` renders through `Redactor` into a `LogSink`. `:app` provides the only implementation that
touches `android.util.Log`. `:core:common` stays free of the Android framework, and tests assert on
exactly the text that would have reached logcat.

## Consequences

- A developer cannot accidentally log a media title: the only way to attach one is a type that
  redacts it.
- `RedactorTest` and `RedactingLoggerTest` assert on absence — "this string does not appear in the
  output" — which is the assertion that actually protects the user.
- Log lines are less immediately readable during debugging. The debug-build developer toggle from
  `PRODUCT_SPEC 14.5` is the sanctioned relief valve and lands with the diagnostics screen.
- Every log message must be a constant. Interpolating a value into the message would bypass the
  redactor, and reviewers should treat `"...$x..."` in a log call as a defect.
