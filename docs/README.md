# BookWave documentation

This directory contains BookWave's current contracts, active plans, architectural decisions, risks, testing guidance, experiments, and historical engineering evidence.

The repository has accumulated detailed investigation over time. Detail does **not** by itself make a document current. Use the classification and authority rules below before treating a document as implementation guidance.

## Start here

| Question | Current authority |
| --- | --- |
| What is BookWave and what does the baseline product require? | [`../README.md`](../README.md) and [`../PRODUCT_SPEC.md`](../PRODUCT_SPEC.md) |
| What should be worked on next? | [`roadmap.md`](roadmap.md) |
| What architecture is current? | [`architecture/`](architecture/) and accepted ADRs in [`adr/`](adr/) |
| What playback correctness rules are current? | [`architecture/playback.md`](architecture/playback.md), accepted ADRs, and the active roadmap while pending PRs are not yet on `main` |
| What risks remain open? | [`risks.md`](risks.md) |
| How should the repository be tested now? | [`testing.md`](testing.md) |
| What dependency-upgrade work is active? | [`latest-stable-upgrade-plan.md`](latest-stable-upgrade-plan.md) with the dated [`dependency-compatibility-inventory.md`](dependency-compatibility-inventory.md), sequenced by the roadmap |
| What Android Auto ideas still need experiments? | [`android-auto-player-opportunities.md`](android-auto-player-opportunities.md) |
| What did older phases investigate or prove? | [`archive/`](archive/), [`bugs/`](bugs/), and [`reviews/`](reviews/) |

## Document classifications

Every planning or investigation document should be readable as one of these states:

- **Current contract** — behavior or architecture that implementation must preserve unless deliberately superseded.
- **Active plan** — approved work that may be implemented now, subject to its dependencies and issue/PR scope.
- **Candidate / future** — worthwhile direction that is not yet committed implementation work.
- **Superseded / historical** — useful evidence from an older state of the repository; not current instructions.
- **Completed** — the work has landed; lasting rules belong in current product/architecture documentation.
- **Open risk** — shipped/current behavior has a known uncertainty or failure mode recorded in `risks.md`.
- **Experiment / research** — a question to measure or investigate before making a product commitment.

## Authority rules

1. **`main` wins over prose about implementation state.** If a document says code exists or does not exist, verify against `main` before relying on the claim.
2. **Accepted ADRs win over older baseline wording where they explicitly supersede it.** Preserve the older requirement and reasoning; do not silently rewrite history.
3. **`roadmap.md` is the only canonical "what next" list.** Child plans can describe one area in depth, but they do not independently nominate priority.
4. **`risks.md` owns live risks.** A review or bug report may explain a risk, but a risk ID referenced as current must be registered there.
5. **Dated reviews and bug reports are evidence snapshots.** They can contain excellent reasoning even after their findings are fixed. Their unresolved findings must be promoted into the roadmap or risk register rather than leaving the investigation itself as a hidden backlog.
6. **Device evidence remains device evidence.** JVM/Robolectric tests can prove BookWave's decisions and metadata construction; they cannot prove how a real head unit, launcher, OS surface, removable volume, or system prompt renders or behaves.

## When work completes

- Mark the plan or issue complete.
- Move lasting product/architecture rules into the current contract documents or an ADR.
- Close risks with the reason and PR/ADR that closed them.
- Keep useful investigation/history, but mark it historical/completed and stop presenting it as future work.
- If an ADR is superseded, preserve it and explicitly name the superseding ADR.

## Current committed implementation chain

The current near-term implementation chain is tracked in [`roadmap.md`](roadmap.md). At the time this index was created it is:

1. PR #78 — Android Auto/routing finalization
2. PR #93 — unified resume freshness
3. PR #86 — Appearance inline controls
4. PR #98 — Playback settings UI refresh

Those PRs are committed near-term work, not invitations for competing implementations in planning branches.
