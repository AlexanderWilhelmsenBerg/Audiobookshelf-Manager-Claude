package com.example.shelfplayer.buildlogic

import com.android.build.api.dsl.Lint

/**
 * PRODUCT_SPEC 16.3: Android Lint is a blocking gate, not an advisory report.
 *
 * There is deliberately no lint baseline: PRODUCT_SPEC 16.3 forbids baselines for new code, and a
 * baseline file created now would silently absorb every defect Phase 1 introduces.
 */
internal fun Lint.applyShelfPlayerLintRules() {
    abortOnError = true
    warningsAsErrors = true
    checkDependencies = true
    checkReleaseBuilds = true
    checkTestSources = true
    explainIssues = true
    htmlReport = true
    xmlReport = true
    sarifReport = true

    // Gradle prints only "Lint found N errors. First failure: ..." on the console, so a CI log shows
    // one of six problems and the rest live in an HTML report nobody downloads. The text report goes
    // to build/reports/lint-results-<variant>.txt, which the workflow prints when lint fails.
    textReport = true

    // PRODUCT_SPEC 15: cleartext traffic and TLS bypasses must never reach a release build.
    fatal += listOf(
        "AllowBackup",
        "ExportedContentProvider",
        "ExportedReceiver",
        "ExportedService",
        "TrustAllX509TrustManager",
        "UnsafeProtectedBroadcastReceiver",
    )

    // Vendor/tooling noise that says nothing about ShelfPlayer's own correctness.
    disable += listOf(
        // Dependency freshness is governed by the version catalog and dependency locking
        // (PRODUCT_SPEC 16.1), not by a lint check that would fire on every pinned version.
        "GradleDependency",
        "NewerVersionAvailable",
        "AndroidGradlePluginVersion",
        "ObsoleteLintCustomCheck",
        // Fires whenever a newer API level exists than the pinned targetSdk, so it turns a Google
        // release into a red build with no change on our side — the same failure mode as the
        // dependency-freshness checks above. The SDK levels are pinned in the version catalog and
        // moved deliberately, with the compatibility testing that a targetSdk bump requires.
        "OldTargetApi",
        // minSdk is 26, so the adaptive icon in `mipmap-anydpi-v26` is the only icon that can be
        // used. Density-specific PNG fallbacks would be dead weight.
        "IconMissingDensityFolder",
        "IconLauncherShape",
    )
}
