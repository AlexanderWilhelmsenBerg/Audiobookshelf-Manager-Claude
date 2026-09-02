package com.example.shelfplayer.buildlogic

import org.gradle.api.Project

/**
 * What this particular build calls itself.
 *
 * ### The defect this exists for
 *
 * `versionName` and `versionCode` were hand-written constants, and they went stale the way hand-written
 * constants do: the name sat at `0.9.6-auto-shelves` for nine builds once, and by the time this was written
 * it read `0.9.14-browse-and-genres` at code 40 across dozens of pull requests. Every device-test result
 * recorded in such a window names the wrong build, and a tester holding two APKs cannot tell which is
 * which — which is the whole reason the version is printed in Settings and pasted into a report.
 *
 * ### The pull request is the counter
 *
 * `docs/release.md` and ADR-0024 rejected a *derived* version, and the reasons given were that a timestamp
 * or a commit count "can go backwards or collide across branches". **A pull request number is neither.**
 * GitHub issues it from one monotonic per-repository counter, so it cannot collide between branches and
 * cannot decrease. The objection that settled the original decision does not reach this scheme, which is
 * why this is an amendment rather than a reversal — see the Versioning section of `docs/release.md`.
 *
 * So a build of pull request 72 calls itself **`0.9.5.72`, code `72`**: one number, in both fields, that
 * names the branch a tester is holding.
 *
 * ### What happens with no pull request
 *
 * A local build, or a build of `main` after a merge, has no number. It falls back to [BASE_VERSION_CODE]
 * and marks the name so nobody mistakes it for a numbered build. **A Play upload must therefore come from
 * a pull request**, or [BASE_VERSION_CODE] must first be raised past the last code Play accepted — Play's
 * refusal of a reused code is permanent, and that constraint is the one part of the original decision this
 * amendment does not soften.
 *
 * ### Why nothing here runs `git`
 *
 * The commit is *given* to the build, never discovered by it. Shelling out to `git` during configuration
 * would read the working tree at configuration time, which the configuration cache is entitled to reuse
 * across commits — producing an APK that states a commit it was not built from. A wrong answer here is
 * worse than no answer, because the whole point is traceability.
 */
internal data class BuildIdentity(
    val versionCode: Int,
    val versionName: String,
    val commit: String,
    val pullRequest: String,
)

internal fun Project.buildIdentity(): BuildIdentity {
    val pullRequest = intInput(PR_PROPERTY, PR_ENV)?.takeIf { it > 0 }
    val commit = stringInput(COMMIT_PROPERTY, COMMIT_ENV)?.take(SHORT_SHA_LENGTH) ?: LOCAL_COMMIT
    return BuildIdentity(
        versionCode = pullRequest ?: BASE_VERSION_CODE,
        versionName = pullRequest?.let { "$BASE_VERSION_NAME.$it" } ?: "$BASE_VERSION_NAME.$LOCAL_SUFFIX",
        commit = commit,
        pullRequest = pullRequest?.toString() ?: LOCAL_SUFFIX,
    )
}

private fun Project.stringInput(property: String, env: String): String? =
    (providers.gradleProperty(property).orNull ?: providers.environmentVariable(env).orNull)
        ?.trim()
        ?.takeIf(String::isNotEmpty)

private fun Project.intInput(property: String, env: String): Int? = stringInput(property, env)?.toIntOrNull()

/**
 * The `0.9.5` in `0.9.5.72`.
 *
 * Bumped by hand, and only when the *product* moves — it is the part a human means by "the version". The
 * pull request supplies the rest, so this no longer has to be touched per build and can no longer go stale.
 */
private const val BASE_VERSION_NAME = "0.9.5"

/**
 * The code an unnumbered build gets, and the floor every numbered build clears.
 *
 * `40` is the last code the hand-incremented scheme reached. Every pull request number in this repository
 * is already past it, so a numbered build always sorts above an unnumbered one.
 */
private const val BASE_VERSION_CODE = 40

/** What a build with no pull request calls itself, in both the name and the `PULL_REQUEST` field. */
private const val LOCAL_SUFFIX = "local"

private const val LOCAL_COMMIT = "unknown"
private const val SHORT_SHA_LENGTH = 12

private const val PR_PROPERTY = "bookwave.pr"
private const val PR_ENV = "BOOKWAVE_PR"
private const val COMMIT_PROPERTY = "bookwave.commit"
private const val COMMIT_ENV = "BOOKWAVE_COMMIT"
