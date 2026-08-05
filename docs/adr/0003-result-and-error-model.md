# ADR-0003: Typed result and error model

- **Status:** Accepted
- **Date:** 2026-08-04
- **Requirements:** PRODUCT_SPEC 14.1, 14.2, 14.3, 14.4, 22

## Context

`PRODUCT_SPEC 14.2` prescribes a sealed `AppResult` and `14.1` a sealed `AppError`. Kotlin's built-in
`Result<T>` was the obvious alternative and is rejected: it carries a `Throwable`, which pushes every
call site into `when (exception)` over platform types, and it swallows `CancellationException` unless
each site remembers to rethrow — exactly the failure `PRODUCT_SPEC 22` calls out.

## Decision

`AppResult<out T>` with `Success` and `Failure(AppError)`, plus `map`, `flatMap`, `mapError`,
`getOrElse`, `onSuccess`, `onFailure`.

`AppError` is a sealed interface with the fourteen cases `PRODUCT_SPEC 14.1` lists. Every case
carries three things:

- `summary` — plain language, safe to show *and* safe to log. It never embeds a host, a title, a
  path or a token, so an error can be reported without a second redaction pass.
- `code` — a stable machine-readable string for the "optional technical code" of
  `PRODUCT_SPEC 14.4`.
- `isRetryable` — the retry policy of `PRODUCT_SPEC 14.3` encoded on the error rather than
  re-derived at each call site. `401` and `403` are never retryable; `5xx`, timeouts and network
  failures are.

`resultOf { }` is the single sanctioned exception boundary. It catches `Throwable`, rethrows
`CancellationException` first, and maps everything else through a caller-supplied function. It is the
only place in the repository with a broad catch, and it carries the matching detekt suppression.

## Consequences

- A `when` over `AppError` is exhaustive, and detekt's `ElseCaseInsteadOfExhaustiveWhen` keeps it
  that way: adding a case makes every incomplete handler a compile error.
- Cancellation propagates correctly by construction rather than by discipline.
- Repositories must translate at their boundary; a leaked `IOException` is a defect, and
  `NetworkErrorMapper` is where the translation lives for HTTP.
- `AppError` cannot round-trip losslessly through the database: `SyncStateEntity` stores the code and
  the summary, and reading it back yields a conservative `AppError.Server`. Reconstructing the
  original type from two columns would be a guess, and a wrong `isRetryable` is worse than a
  cautious one.
