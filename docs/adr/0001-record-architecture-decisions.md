# ADR-0001: Record architecture decisions

- **Status:** Accepted
- **Date:** 2026-08-04

## Context

`PRODUCT_SPEC 18` requires an ADR for every architecture change, `PRODUCT_SPEC 16.2` requires one
before changing a formatter rule, and `PRODUCT_SPEC 24` states that the defaults it chooses stay in
force until an ADR changes them. Those references only mean something if there is a place to put one.

## Decision

Architecture decisions live in `docs/adr/NNNN-title.md`, numbered sequentially and never renumbered.
Each records context, the decision, and its consequences — including what it makes harder.

A decision is superseded rather than edited: the old ADR gains a `Superseded by ADR-NNNN` status and
keeps its text, so a future reader can see what was believed at the time.

An ADR is required for: changing a module boundary, changing a formatter or static-analysis rule,
adopting or dropping a dependency that affects the architecture, changing an error or result
contract, and any deviation from a `PRODUCT_SPEC` default.

## Consequences

Reviewers can ask "which ADR covers this?" instead of relitigating a decision. The cost is one file
per decision, which is cheaper than the argument it replaces.
