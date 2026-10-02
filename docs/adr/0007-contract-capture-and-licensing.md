# ADR-0007: Contracts come from a running server, and no Audiobookshelf code is copied

- Status: accepted
- Date: 2026-08-05
- Supersedes: nothing
- Related: ADR-0005 (fake gateway and fixtures), `docs/api-compatibility.md`

## Context

Phase 1 needs authentication and library synchronization against a real Audiobookshelf server. Three
constraints meet here and pull in different directions.

`PRODUCT_SPEC 22.4` forbids inventing endpoints. `22.5` requires a captured contract fixture before
relying on a response shape. `23` warns that the public API reference "states that it is out of
date" — so the reference alone cannot discharge either rule.

That warning is not theoretical. The specification the project publishes as `docs/openapi.json`
documents 31 paths covering libraries, series, authors, podcasts and email, and contains **no
authentication endpoint whatsoever**. Login, token refresh, and the token-to-user exchange — the
entire subject of `AUTH-001` through `AUTH-004` — are absent from it.

Audiobookshelf is GPL-licensed. `PRODUCT_SPEC 22.13` forbids copying official app code without a
license review, and this project has not chosen a license yet (`PRODUCT_SPEC 24.2`).

## Decision

**Contracts are captured from a running Audiobookshelf server, and the capture is automated.**

`.github/workflows/maintenance.yml` (`contract-capture` task) starts the official server image, drives it with
`scripts/capture-contracts.sh`, and records what it actually answers. The fixtures committed under
`core/network/src/test/resources/contracts/` are that output. Capture is deliberately manual: when an API shape or supported server baseline changes, the task re-captures and fails if the committed fixtures no longer match, so drift is reviewed before the app relies on it.

Where the published specification and a captured response disagree, **the capture wins**, and the
disagreement is recorded in `docs/api-compatibility.md`.

**No Audiobookshelf source is copied into this repository.** The server's route definitions were read
to learn *which* endpoints exist and what they are called — the same facts a published API reference
would have carried had it been complete. What those endpoints return is established by observing
responses, not by transcribing implementation. No file, function, type, string table or asset from
Audiobookshelf appears in this repository, and none may be added without the license review
`PRODUCT_SPEC 22.13` requires.

## Consequences

Contract drift is detectable, which is the property `22.5` is actually asking for: a fixture that is
never re-verified decays into the same guess it was meant to replace.

Every captured fixture is scrubbed of tokens, passwords and cookies before it is written, and the
workflow fails if a credential-shaped value survives into one. These files are committed and the
secret scan runs over them; a token in git history is not retractable.

Ids and timestamps are replaced with stable placeholders. Without that, every capture would differ
from the last and the drift check would produce noise instead of signal — and a check that cries wolf
is one people learn to ignore.

The capture defaults to the verified Audiobookshelf 2.36.0 baseline. The `workflow_dispatch` input can deliberately select another image when a newer or older server contract needs to be investigated; changing that baseline is therefore an explicit evidence update rather than background drift.

The job cannot run in every environment. Container registry blob hosts are blocked by egress policy
in the authoring sandbox, so capture happens in CI. This is why the fixtures are committed rather
than generated on demand: a developer without registry access must still be able to run the contract
tests.
