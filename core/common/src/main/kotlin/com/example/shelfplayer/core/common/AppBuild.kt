package com.example.shelfplayer.core.common

/**
 * What this build calls itself, for the few places that have to say so out loud.
 *
 * `BuildConfig` is generated per module, so `:app`'s version name is not visible from a library module.
 * Rather than each module growing its own `BuildConfig` — which would give the same fact several
 * spellings — `:app` provides this once and everything else injects it.
 *
 * Nothing here identifies a device, an installation or a user (PRODUCT_SPEC 14.5). It is the app's own
 * name and version, which a self-hosted server already sees in the `User-Agent` header.
 *
 * Deliberately a `data class` rather than a value class, for the reason recorded on `UserAgent`: a
 * Kotlin value class in an injected constructor mangles the JVM signature Dagger's generated Java
 * factory calls.
 */
data class AppBuild(val clientName: String, val version: String)
