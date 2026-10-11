# Android Auto #250 — artwork host correlation discriminator

## Test build scope

This change is **diagnostics only**. It does not change profile switching, image loading, capability lifetime, Media3 metadata, or browse invalidation. Previously observed author covers can render while Series and Series→books remain blank despite successful `fetchMode=network` provider deliveries.

## Event Log evidence

On **Android Auto** browse only, `Android Auto artwork card offered` is emitted for up to the first 12 returned local-artwork items per Media3 `onGetChildren` page. Each line contains:

- `surface=continue|series|authors|series-books|author-books|other`
- `itemKind=book|series|author|...`
- `index`: index in the returned page
- `artworkRef=#...`: existing `LogField.Identifier` redaction of the opaque local artwork capability's last path segment

Provider events `Android Auto artwork requested`, `Android Auto artwork served`, `Android Auto artwork failed`, and `Android Auto artwork refused` now include the **same redacted artworkRef**. Do not paste, export, or log the raw token. No title, source URL, profile ID, credential or content URI is logged. `artworkRef` is stable across a single diagnostic bundle, not an image identity for analytics.

## Controlled physical test

While **parked**, open original profile A and visit **Series**, one Series→books page and **Authors**. Switch A→B using the car Profiles tab. Repeat the same three surfaces without opening playback. Wait for image requests to finish; revisit Series→books. Optionally disconnect and reconnect Android Auto with B still active, then repeat once. Capture the paired `offered` / `requested` / `served` events plus screenshots and test times.

## How to decide the third correction

- A blank Series/Book card with an `offered` artworkRef but **no matching `requested`**, when logs cover the full test window: investigate host request behavior, negative caching and stable URI lifetime. Controlled URI-generation experiment is justified; do not assume it will render.
- A matching `requested` and `served` while the card stays blank: investigate host consumption/presentation (DHU, URI/provider contract) and whether a bounded artwork-ready refresh makes the host rerequest it.
- `requested` followed by `failed` / `refused`: investigate the named provider failure/authorization; do not change host invalidation first.
- `requested` with no terminal outcome: investigate callback completion and timeout/event collection.
- If covers show, record device acceptance and whether first-time loads differ from repeat loads.

A missing request line in an incomplete export is **not conclusive**. Physical host rendering has not been tested for this change. Do not close #250 based solely on JVM or CI results. URI revision and artwork-ready refresh are explicitly postponed until the correlated evidence selects a failure lane.
