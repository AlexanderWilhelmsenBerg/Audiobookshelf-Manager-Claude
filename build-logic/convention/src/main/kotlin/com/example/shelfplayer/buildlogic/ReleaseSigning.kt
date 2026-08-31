package com.example.shelfplayer.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.ApkSigningConfig
import org.gradle.api.GradleException
import org.gradle.api.Project
import java.io.File

/**
 * PRODUCT_SPEC 15 / 18, ADR-0024, `docs/risks.md` R-05 — how a release build gets signed without any key
 * material entering this repository.
 *
 * ### The problem
 *
 * The release variant declared no signing configuration at all, which is correct as a *default* and was
 * wrong as the only option. An unsigned APK cannot be installed on any device, so the one build that R8
 * actually shrinks had never been launched — `docs/release.md` says so in as many words — and there was no
 * supported way to produce an upload artefact for Play either. "The release build is not installable" is
 * the symptom; this is the missing half of the decision, not a reversal of it.
 *
 * ### The rule
 *
 * Signing is configured **only** from values supplied outside the repository, and the default with nothing
 * supplied is exactly today's behaviour: no signing configuration, an unsigned release, CI unchanged.
 *
 * Two sources, checked in this order, so a person and a runner each get the one that suits them:
 *
 *  - a **Gradle property**, which belongs in `~/.gradle/gradle.properties` — outside the checkout, outside
 *    every backup of it, and not something `git add -A` can catch;
 *  - an **environment variable**, for a protected CI environment.
 *
 * `local.properties` also works, because Gradle reads it as properties and it is gitignored, but the home
 * directory is the better place: `local.properties` sits inside the checkout, and a path inside the
 * checkout is the thing this file exists to keep key material out of.
 *
 * ### Three refusals, and why each one is a hard failure rather than a warning
 *
 *  1. **Partially configured is an error.** Supplying a keystore and forgetting the alias would otherwise
 *     produce a *silently unsigned* APK — a build that succeeds, uploads, and fails at install with a
 *     message about the package rather than about the alias. [missingSigningInputs] names what is absent.
 *  2. **A keystore inside the repository is an error**, even though `.gitignore` already lists `*.jks` and
 *     `*.keystore`. A gitignore is advisory: it is one `git add -f`, one rename to `upload.key`, or one
 *     archive of the working tree away from being bypassed. [rejectKeystoreInsideRepository] is the part
 *     that cannot be bypassed by accident, and R-05 is precisely the prediction that somebody in a hurry
 *     would put the key where the repository can see it.
 *  3. **A keystore that does not exist is an error.** AGP's own failure for this arrives late and reads as
 *     a signing-task stack trace; naming the path at configuration time is the difference between a
 *     typo found in two seconds and one found in four minutes.
 *
 * ### What this deliberately does not do
 *
 * It does not put a keystore anywhere, generate one, or add a workflow that signs on push. ADR-0024 chose
 * Play App Signing, so the key that matters is held by Google and what a maintainer needs locally is an
 * *upload* key. Signing in a push-triggered workflow stays forbidden (PRODUCT_SPEC 18); this configures the
 * build to accept key material, and says nothing about where a workflow would be allowed to get it.
 */
internal fun ApplicationExtension.configureReleaseSigning(project: Project) {
    val inputs = project.signingInputs()
    if (inputs == null) {
        project.warnWhenAssemblingAnUnsignedRelease()
        return
    }

    val store = project.resolveKeystore(inputs.storeFile)
    val supplied = signingConfigs.create("bookwave") { applyInputs(inputs, store) }
    buildTypes.named("release") { signingConfig = supplied }

    /*
     * **This deliberately does not touch the debug build**, and an earlier version of it did.
     *
     * It used to end `buildTypes.named("debug") { signingConfig = supplied }`, on the reasoning that a
     * stable key makes `adb install -r` an upgrade rather than a reinstall. The reasoning was right and the
     * placement was wrong: it made the debug signature depend on **whether four environment variables were
     * set in the shell that ran Gradle**. A release build in one terminal and a debug build in another
     * produced two differently-signed debug APKs, and every flip between them cost the tester an
     * uninstall — the profile, the passcode, the progress journal and every downloaded book.
     *
     * `DebugSigning.kt` now gives the debug build one key of its own that nothing else can change. Release
     * inputs configure the release build and stop there.
     */
    project.logger.lifecycle(
        "BookWave: release will be signed with the key supplied outside this repository.",
    )
}


/**
 * Says so — **when a release is actually being assembled**, and not before.
 *
 * An unsigned release APK cannot be installed, and until now nothing said that at build time. The build
 * succeeded, wrote an `.apk`, and the failure surfaced minutes later as `adb: failed to install` with a
 * message about the package rather than about the signature. A device session reported it as
 * *"the release apk is not installable"*, which is exactly what it looks like from outside.
 *
 * Attached to the task rather than logged at configuration time, because configuration runs on every
 * Gradle invocation — including the hundreds that never build a release — and a warning that prints when
 * it does not apply is a warning people learn to scroll past.
 */
private fun Project.warnWhenAssemblingAnUnsignedRelease() {
    tasks.matching { it.name == "assembleRelease" }.configureEach {
        doFirst {
            // Built line by line rather than as one `trimIndent`ed literal: `trimIndent` runs on the
            // *interpolated* string, so a multi-line value spliced into it drags the common indent to
            // zero and the whole message loses its shape. Observed, then fixed.
            logger.warn(
                buildString {
                    appendLine()
                    appendLine("BookWave: this release APK will be UNSIGNED and cannot be installed.")
                    appendLine()
                    appendLine("  No signing key was supplied, so the build produced an artefact for")
                    appendLine("  inspecting what R8 emitted. For an installable one, put four values in")
                    appendLine("  ~/.gradle/gradle.properties:")
                    appendLine()
                    SIGNING_INPUTS.forEach { appendLine("    ${it.property}=…") }
                    appendLine()
                    appendLine("  docs/release.md § Signing has the keytool command that generates the key.")
                },
            )
        }
    }
}

/** The four values a signing configuration needs, all of them present. */
internal data class SigningInputs(
    val storeFile: String,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String,
)

private fun ApkSigningConfig.applyInputs(inputs: SigningInputs, store: File) {
    storeFile = store
    storePassword = inputs.storePassword
    keyAlias = inputs.keyAlias
    keyPassword = inputs.keyPassword
    /*
     * **The signature schemes are deliberately left to AGP**, which picks them from `minSdk`.
     *
     * The first version of this set `enableV1Signing = true` on the reasoning that v1 (JAR signing) is
     * what an older install verifies. `apksigner verify` on the resulting APK reported
     * `Verified using v1 scheme (JAR signing): false` — AGP had ignored the flag, correctly: v2 is
     * verified from API 24 and this app's `minSdk` is 26, so no device it supports needs v1 and adding
     * it only slows installation. A flag that reads as a guarantee and is silently dropped is worse
     * than no flag, so it is gone rather than commented.
     */
}

/**
 * The four inputs, or `null` when none of them is supplied.
 *
 * `null` is the ordinary case and the one CI takes: no key material, no signing configuration, an unsigned
 * release exactly as before. A *partial* set throws instead, because it is always a mistake.
 */
private fun Project.signingInputs(): SigningInputs? {
    val supplied = SIGNING_INPUTS.associateWith { signingValue(it) }
    if (supplied.values.all { it == null }) return null

    val missing = missingSigningInputs(supplied)
    if (missing.isNotEmpty()) {
        throw GradleException(
            buildString {
                appendLine("Release signing is partly configured, which would produce an unsigned APK.")
                appendLine("Missing: ${missing.joinToString(", ") { it.property }}")
                appendLine()
                appendLine("Supply all four in ~/.gradle/gradle.properties (outside this repository):")
                SIGNING_INPUTS.forEach { appendLine("  ${it.property}=…") }
                appendLine()
                appendLine("or as environment variables: ${SIGNING_INPUTS.joinToString(", ") { it.environment }}")
            },
        )
    }

    return SigningInputs(
        storeFile = supplied.getValue(SigningInput.StoreFile)!!,
        storePassword = supplied.getValue(SigningInput.StorePassword)!!,
        keyAlias = supplied.getValue(SigningInput.KeyAlias)!!,
        keyPassword = supplied.getValue(SigningInput.KeyPassword)!!,
    )
}

/** Which of the four are absent, given what was supplied. Separated out so the rule reads as one line. */
internal fun missingSigningInputs(supplied: Map<SigningInput, String?>): List<SigningInput> =
    SIGNING_INPUTS.filter { supplied[it].isNullOrBlank() }

/**
 * Where a keystore may live, checked rather than trusted.
 *
 * A path inside the repository is refused outright — see refusal 2 above. A relative path is resolved
 * against the *user's home directory* rather than against the project, which is the same rule stated
 * positively: `bookwave-upload.jks` means the one in the home directory, never one in the checkout.
 */
private fun Project.resolveKeystore(path: String): File {
    val expanded = if (path.startsWith("~/")) {
        File(System.getProperty("user.home"), path.removePrefix("~/"))
    } else {
        File(path)
    }
    val resolved = if (expanded.isAbsolute) expanded else File(System.getProperty("user.home"), path)

    rejectKeystoreInsideRepository(resolved.canonicalFile, rootDir.canonicalFile)

    if (!resolved.isFile) {
        throw GradleException(
            "Release signing keystore not found: ${resolved.absolutePath}\n" +
                "Set ${SigningInput.StoreFile.property} to an absolute path outside this repository.",
        )
    }
    return resolved
}

/**
 * Refuses a keystore located inside [repositoryRoot].
 *
 * Takes both paths as parameters so the rule is a statement about two files rather than about this build —
 * the same shape as `ControllerTrust.accessFor` and `RecentsPrivacy.canSuppressThumbnail`, and for the same
 * reason: a rule that reads its own environment is a rule nothing else can check.
 */
internal fun rejectKeystoreInsideRepository(keystore: File, repositoryRoot: File) {
    val inside = generateSequence(keystore) { it.parentFile }.any { it == repositoryRoot }
    if (inside) {
        throw GradleException(
            "Release signing keystore is inside this repository: ${keystore.absolutePath}\n" +
                "PRODUCT_SPEC 15 keeps key material out of the checkout entirely — .gitignore is not\n" +
                "enough, because `git add -f` and a rename both defeat it. Move the keystore somewhere\n" +
                "like ~/.bookwave/upload.jks and point ${SigningInput.StoreFile.property} at it.",
        )
    }
}

/** One of the four signing inputs, with the two names it can arrive under. */
internal enum class SigningInput(val property: String, val environment: String) {
    StoreFile("bookwave.signing.storeFile", "BOOKWAVE_SIGNING_STORE_FILE"),
    StorePassword("bookwave.signing.storePassword", "BOOKWAVE_SIGNING_STORE_PASSWORD"),
    KeyAlias("bookwave.signing.keyAlias", "BOOKWAVE_SIGNING_KEY_ALIAS"),
    KeyPassword("bookwave.signing.keyPassword", "BOOKWAVE_SIGNING_KEY_PASSWORD"),
}

/**
 * `values().toList()` rather than `entries`: the `kotlin-dsl` plugin compiles `build-logic` against an
 * older language version than the application modules use, and `entries` is not available there.
 */
private val SIGNING_INPUTS = SigningInput.values().toList()

/** Gradle property first, environment variable second. Blank counts as absent, not as a password. */
private fun Project.signingValue(input: SigningInput): String? =
    (findProperty(input.property) as? String ?: System.getenv(input.environment))?.takeIf { it.isNotBlank() }
