# ADR-0009: Cleartext is a debug-build capability, and release builds keep the platform guarantee

- Status: accepted
- Date: 2026-08-07
- Resolves: `PRODUCT_SPEC` open decision **24.13** — "whether cleartext LAN servers can be enabled in
  release builds"
- Related: `PRODUCT_SPEC 15`, `AUTH-001`, P1-23

## Context

`PRODUCT_SPEC 15` says: *"Release build cleartext disabled unless the user explicitly enables a
per-server local-network exception in an advanced screen; show warning."*

Until this ADR, one `network_security_config.xml` covered both build types with
`cleartextTrafficPermitted="false"`. The effect was worse than either option on its own: **no build
could reach a LAN-only `http://` server**, including the debug APKs every acceptance run uses, while
the sign-in screen politely warned about cleartext for an address the HTTP stack would then refuse
outright. A warning about a thing that cannot happen, followed by an unexplained failure.

## The constraint that decides it

Android's network security configuration is **static**. `<domain-config>` takes literal domain names
fixed at build time; there is no CIDR form for "any private address", and no API adds a host to the
policy at runtime. A genuinely *per-server* exception, chosen by the user for the address they just
typed, cannot be expressed.

The only lever a release build has is `cleartextTrafficPermitted="true"` on the base config. That is
not a per-server exception — it removes the platform's cleartext guarantee for **every** host in the
app, permanently, in order to serve one LAN address. An in-app toggle layered on top would be the app
policing itself, which is a weaker thing wearing the same name: a bug in one interceptor, and the
guarantee the manifest used to make is simply gone.

## Decision

**Debug builds permit cleartext. Release builds do not, and no in-app toggle changes that.**

- `app/src/debug/res/xml/network_security_config.xml` — `cleartextTrafficPermitted="true"`.
- `app/src/main/res/xml/network_security_config.xml` — unchanged, `false`, and it is what ships.

The "advanced screen" `PRODUCT_SPEC 15` describes is **not built**, because on this platform it could
not do what its name promises. The sign-in screen instead tells the truth about what will happen:
a debug build says the connection is unencrypted, and a release build says it cannot be used.

## Consequences

- A user on a LAN-only HTTP server can use a debug build today. That is the acceptance-testing path and
  it was broken.
- A release build cannot reach such a server at all. The honest remedies are a reverse proxy with TLS,
  a self-signed certificate installed as a user CA, or a tunnel — all of which keep the transport
  encrypted, which is the actual goal.
- If this needs revisiting, the shape to revisit is *not* an in-app toggle. It is a separate build
  flavour with its own network security config, which keeps the guarantee a manifest-level fact.
