# ADR-0034 — Service activation uses one guarded command boundary

**Status:** Implemented candidate; strict full software verification passes (2,675 tests). Device acceptance pending.
**Requirements:** PRODUCT_SPEC PLAY-001/002/004/009, ROUTE-002, SYNC-002.

## Context

Guarding local and adopted resumes alone left other production activation paths able to call raw Media3
`prepare()` or `play()`. Controller first-Play exemptions, retained Play intent, deferred retry/install,
post-connect restoration and car continuity must obey the same output protection. Media3 can retain
`playWhenReady=true` after Stop or error, so Prepare can activate audio without a separate Play.

## Decision

One service-owned `PlaybackCommandEffects` implements the narrow `PlaybackCommands` contract. It uses the
existing player attachment, item generation and caller authorization; it creates no player, coroutine scope,
counter, durable state or focus owner. The router checks output protection before activation and the command
boundary checks authorization again after synchronous callbacks. Refusal cannot fall back to raw transport.

Every loaded controller Play enters the service. A direct fresh-session token skips only the redundant server
freshness decision; it still reaches sleep intent and the output guard. Duplicate Play keeps existing intent
without consuming that token. Empty-session delivery retains Media3's normal resumption machinery. Controller
Prepare uses the same command boundary and captures the existing controller generation.

Deferred retry, accepted queue installation, post-connect restoration and continuity use this same instance.
Installation checks the originating command/profile before setting media, captures the observed installed item
generation, and holds it stable through Prepare/Play. It does not assume a generation increment or adopt a newer
command from a callback. Arm-only installation first pauses inherited Play intent with scoped safety provenance;
this does not manufacture listener Pause history. Adopted preparation can likewise remain paused until its seek.

Continuity registers its exact awaited target immediately before an authorized raw Play. Registering afterward
could resurrect a target that a synchronous newer Stop already cleared. Refusal clears only that target; a raw
Play callback may cancel its own recovery job without erasing valid subsequent playing confirmation. No callback
may register the old target again after transport has returned.

## Consequences

- Callers share output protection without copying route policy into controller, retry or restore adapters.
- Raw Play call counts and retained-intent Prepare are tested through the actual service and pinned Media3;
  a PlayWhenReady transition alone is insufficient evidence that no extra Play was issued.
- These checks establish software ordering and refusal, not physical input attribution or audible output.
- Scoped muted-position compensation, progress/history protection and explicit verified transfer/rollback remain
  required parts of #277. This command boundary alone does not complete that issue or the execution plan.
