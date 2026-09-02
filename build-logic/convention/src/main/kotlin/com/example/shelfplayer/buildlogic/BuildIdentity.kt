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
 * ### Why the pull request is *not* the code, which is a correction
 *
 * The first fix made the pull request number both the `versionName` suffix and the `versionCode`: pull
 * request 72 built as `0.9.5.72`, code `72`. The argument was that GitHub issues pull request numbers from
 * one monotonic per-repository counter, so unlike a timestamp they cannot go backwards.
 *
 * **That argument was about the repository and the installer does not care about the repository.** A phone
 * compares the code it has against the code it is offered, so the only order that matters is the order
 * somebody installs in — and a tester works through open pull requests in whatever order they are ready,
 * not in numeric order. Testing 70 and then 67 offers the installer a *lower* code and it refuses:
 * `INSTALL_FAILED_VERSION_DOWNGRADE`, reported by the package installer as a bare "App not installed" with
 * no reason given, and indistinguishable on screen from the signature mismatch R-68 was about. Reported
 * from a device on 2026-09-02, after exactly that sequence.
 *
 * The unnumbered case was worse and had the same cause: `main` after a merge fell back to code 40, below
 * every pull request number in the repository, so no post-merge build would install over any test build.
 *
 * ### What replaces it: one counter that only ever goes up
 *
 * `versionCode` is [BASE_VERSION_CODE] plus the **workflow run number** — GitHub's per-workflow counter,
 * which increments on every run of `apk.yml` regardless of branch and never decreases. Builds are therefore
 * ordered by *when they were built*, which is the order a device is asked to install them in. Nothing about
 * which branch or pull request a build came from enters the code at all.
 *
 * A re-run of the same workflow run keeps its run number and so produces the same code, which is correct:
 * a reinstall of the same build is not an upgrade.
 *
 * ### Which pull request a build came from, then
 *
 * The pull request and the branch are carried as `BuildConfig` fields and shown on their own row in
 * Settings → About: `PR 67 · fix/playback-session-renewal`. That is the field a tester reads, and it is
 * better than the version ever was — a branch name says what the build contains, where `0.9.5.67` only
 * said where to look it up.
 *
 * So the version name is a plain product version again, [VERSION_NAME], bumped by hand when the product
 * moves. It no longer has to encode anything, so it can no longer be wrong about anything.
 *
 * ### The two ways this can still go backwards, and the escape hatch for both
 *
 *  1. **A local build has no run number** and falls back to [BASE_VERSION_CODE] itself, which is below
 *     every build CI has produced. A locally built APK will not install over a CI one.
 *  2. **A workflow run number restarts at 1** if `apk.yml` is renamed or replaced, since GitHub counts per
 *     workflow file. Every code after that would be a downgrade.
 *
 * Both are fixed the same way, and it is the reason the floor is a round number with room above it: raise
 * [BASE_VERSION_CODE] past the highest code already installed. For a one-off build,
 * `-Pbookwave.versionCode=N` overrides the whole calculation.
 *
 * ### The Play constraint, which no scheme softens
 *
 * Play requires a strictly increasing code per upload and never permits a code to be reused for a package.
 * A monotonic counter satisfies that where the pull request number did not, but the floor still has to
 * clear whatever Play has already accepted — see the Versioning section of `docs/release.md`.
 *
 * ### Why nothing here runs `git`
 *
 * The commit and the branch are *given* to the build, never discovered by it. Shelling out to `git` during
 * configuration would read the working tree at configuration time, which the configuration cache is
 * entitled to reuse across commits — producing an APK that states a commit it was not built from. A wrong
 * answer here is worse than no answer, because the whole point is traceability.
 */
internal data class BuildIdentity(
    val versionCode: Int,
    val versionName: String,
    val commit: String,
    val branch: String,
    val pullRequest: String,
)

internal fun Project.buildIdentity(): BuildIdentity {
    val runNumber = intInput(RUN_NUMBER_PROPERTY, RUN_NUMBER_ENV)?.takeIf { it > 0 }
    val override = intInput(CODE_PROPERTY, CODE_ENV)?.takeIf { it > 0 }
    return BuildIdentity(
        versionCode = override ?: (BASE_VERSION_CODE + (runNumber ?: 0)),
        versionName = VERSION_NAME,
        commit = stringInput(COMMIT_PROPERTY, COMMIT_ENV)?.take(SHORT_SHA_LENGTH) ?: LOCAL_COMMIT,
        branch = stringInput(BRANCH_PROPERTY, BRANCH_ENV) ?: LOCAL_BRANCH,
        pullRequest = intInput(PR_PROPERTY, PR_ENV)?.takeIf { it > 0 }?.toString() ?: NO_PULL_REQUEST,
    )
}

private fun Project.stringInput(property: String, env: String): String? =
    (providers.gradleProperty(property).orNull ?: providers.environmentVariable(env).orNull)
        ?.trim()
        ?.takeIf(String::isNotEmpty)

private fun Project.intInput(property: String, env: String): Int? = stringInput(property, env)?.toIntOrNull()

/**
 * The product version, and the whole of it.
 *
 * Hand-bumped, and only when the *product* moves — it is what a human means by "the version". It encodes
 * nothing about the build any more, which is why it cannot go stale in the way R-04 describes: there is no
 * per-build fact in here to fall out of date. Which build this is comes from the code and the About tab's
 * **Source** row instead.
 */
private const val VERSION_NAME = "0.9.6.1"

/**
 * The floor every build's code clears, and the one number to raise when codes need to jump.
 *
 * `1000` was chosen to sit clearly above the codes the two earlier schemes emitted — 40 from the
 * hand-incremented one, and 67 to 72 from the pull-request one, which are installed on test devices. A
 * build from this scheme therefore installs over anything either of them produced.
 *
 * Raise it — to `2000`, say — if the run number ever restarts, or before a Play upload whose predecessor
 * used a higher code. It is deliberately round and deliberately sparse so that raising it is a one-digit
 * edit rather than an arithmetic problem.
 */
private const val BASE_VERSION_CODE = 1000

/** What a build with no pull request reports in `PULL_REQUEST`, and what the About row tests against. */
private const val NO_PULL_REQUEST = "none"

private const val LOCAL_BRANCH = "local"
private const val LOCAL_COMMIT = "unknown"
private const val SHORT_SHA_LENGTH = 12

/** CI's monotonic counter: `github.run_number`, passed as `BOOKWAVE_RUN_NUMBER` by `apk.yml`. */
private const val RUN_NUMBER_PROPERTY = "bookwave.runNumber"
private const val RUN_NUMBER_ENV = "BOOKWAVE_RUN_NUMBER"

/** The whole calculation, overridden. For a one-off build that has to install over something specific. */
private const val CODE_PROPERTY = "bookwave.versionCode"
private const val CODE_ENV = "BOOKWAVE_VERSION_CODE"

private const val PR_PROPERTY = "bookwave.pr"
private const val PR_ENV = "BOOKWAVE_PR"
private const val COMMIT_PROPERTY = "bookwave.commit"
private const val COMMIT_ENV = "BOOKWAVE_COMMIT"
private const val BRANCH_PROPERTY = "bookwave.branch"
private const val BRANCH_ENV = "BOOKWAVE_BRANCH"
