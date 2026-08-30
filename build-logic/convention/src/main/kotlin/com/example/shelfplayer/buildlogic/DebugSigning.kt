package com.example.shelfplayer.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.GradleException
import org.gradle.api.Project
import java.io.File

/**
 * PRODUCT_SPEC 15 / `docs/risks.md` R-68 — one stable key for the debug build, decided by nothing else.
 *
 * ### The defect this fixes
 *
 * A tester reported having to **uninstall the app before every debug build**, not merely update it. That is
 * `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, and it has exactly one cause: the APK's signature changed. Two
 * separate mechanisms were changing it.
 *
 *  1. **Debug inherited the *release* key when release signing happened to be configured.**
 *     `configureReleaseSigning` used to end with `buildTypes.named("debug") { signingConfig = supplied }`,
 *     so whether a debug APK was signed with the upload key or with AGP's generated one depended on
 *     whether four environment variables were set **in the shell that ran Gradle**. Run a release build in
 *     one terminal and a debug build in another and the debug signature flips — every flip costing an
 *     uninstall, and nothing on screen explaining why. That coupling is gone; release inputs no longer
 *     touch the debug build at all.
 *  2. **AGP's fallback is `~/.android/debug.keystore`, which it *generates* when absent.** Stable on a
 *     developer's own machine, and freshly random on every CI runner — so every APK the workflow produced
 *     was signed with a different key.
 *
 * ### What replaces them
 *
 * One keystore at a fixed path outside the repository, used by the `debug` signing config unconditionally.
 * Because the *config* is mutated rather than the build type, everything already pointing at
 * `signingConfigs.getByName("debug")` — the benchmark variant included — follows without knowing.
 *
 * **It adopts `~/.android/debug.keystore` when one exists**, copying it rather than generating something
 * new. That is what makes this change free for a developer who already has installs on a device: the
 * signature they have been using is the signature they keep, and no uninstall is needed. A fresh machine or
 * a CI runner has none to adopt, so one is generated with the same well-known debug credentials Android
 * has always used — which is also why the adoption works at all.
 *
 * ### Why generating at configuration time is acceptable here
 *
 * It runs `keytool` in a subprocess, which the configuration cache would flag. `org.gradle.configuration-cache`
 * is `false` for this build, and the call is guarded by the file's absence, so it happens once per machine
 * ever. If the configuration cache is ever turned on, this is the thing to move into a task.
 *
 * ### This is not release key material
 *
 * The credentials below are Android's public, documented debug-keystore constants — the same three strings
 * in every Android SDK installation on earth. They protect nothing and are not a secret. PRODUCT_SPEC 15 is
 * about the **upload** key, which `ReleaseSigning.kt` still refuses to let anywhere near the checkout, and
 * this file adds no key material to the repository: the keystore lives in the user's home directory.
 */
internal fun ApplicationExtension.configureDebugSigning(project: Project) {
    val store = project.stableDebugKeystore() ?: return
    signingConfigs.named("debug") {
        storeFile = store
        storePassword = project.debugValue(DEBUG_STORE_PASSWORD_PROPERTY, DEBUG_STORE_PASSWORD_ENV, DEBUG_STORE_PASSWORD)
        keyAlias = project.debugValue(DEBUG_KEY_ALIAS_PROPERTY, DEBUG_KEY_ALIAS_ENV, DEBUG_KEY_ALIAS)
        keyPassword = project.debugValue(DEBUG_KEY_PASSWORD_PROPERTY, DEBUG_KEY_PASSWORD_ENV, DEBUG_KEY_PASSWORD)
    }
}

/**
 * One credential, with Android's public debug value as the fallback.
 *
 * These exist so **one keystore can sign both variants if that is what the owner wants** — point
 * `bookwave.signing.debug.keystore` at the upload keystore and supply its three credentials, and the debug
 * build uses it. That is a deliberate, written-down choice, which is the difference from the arrangement
 * this file replaced: that one signed debug with the upload key *whenever four unrelated environment
 * variables happened to be set*, and flipped back the moment they were not.
 *
 * Worth knowing before choosing it: a debug APK is the one that gets passed around, and signing it with the
 * upload key puts that certificate on more artefacts than it needs to be on. Play App Signing means the
 * upload key is not what end users verify against (ADR-0024), so the cost is small — but it is not zero,
 * and the separate default exists because separate is the better default.
 */
private fun Project.debugValue(property: String, environment: String, fallback: String): String =
    (findProperty(property) as? String ?: System.getenv(environment))?.takeIf { it.isNotBlank() } ?: fallback

/**
 * The keystore to sign debug builds with, creating it if this machine has none.
 *
 * `null` means *leave AGP alone* — the opt-out, for somebody who would rather keep the SDK's own behaviour.
 */
private fun Project.stableDebugKeystore(): File? {
    if (findProperty(STABLE_DEBUG_PROPERTY)?.toString()?.toBoolean() == false) {
        logger.lifecycle("BookWave: $STABLE_DEBUG_PROPERTY=false, so debug uses AGP's own generated key.")
        return null
    }

    val configured = (findProperty(DEBUG_KEYSTORE_PROPERTY) as? String ?: System.getenv(DEBUG_KEYSTORE_ENV))
        ?.takeIf { it.isNotBlank() }
    val keystore = configured?.let(::expandHome) ?: File(homeDirectory(), DEFAULT_DEBUG_KEYSTORE)

    // The same refusal the upload key gets, for a weaker reason but the same rule: a keystore committed by
    // accident is a keystore committed, and "it was only the debug one" is a sentence written after the
    // fact. Sharing the check also means there is one definition of "inside the repository".
    rejectKeystoreInsideRepository(keystore.canonicalFile, rootDir.canonicalFile)

    if (keystore.isFile) return keystore

    // A path somebody named explicitly is a file they expect to exist; creating one there would sign with a
    // key they did not choose and say nothing about it. Only the default path is ever created.
    if (configured != null) {
        throw GradleException(
            "BookWave debug keystore not found: ${keystore.absolutePath}\n" +
                "It was named by $DEBUG_KEYSTORE_PROPERTY or $DEBUG_KEYSTORE_ENV, so it is not created for " +
                "you.\nUnset both to use the default at ~/$DEFAULT_DEBUG_KEYSTORE, which is generated on " +
                "first use.",
        )
    }

    keystore.parentFile?.mkdirs()
    val inherited = File(homeDirectory(), ANDROID_DEBUG_KEYSTORE)
    if (inherited.isFile) {
        inherited.copyTo(keystore, overwrite = false)
        logger.lifecycle(
            "BookWave: adopted the existing Android debug key for ${keystore.absolutePath}, " +
                "so installs already on your device keep working.",
        )
        return keystore
    }

    generateDebugKeystore(keystore)
    return keystore
}

/**
 * Writes a fresh debug keystore with Android's own documented debug credentials.
 *
 * The distinguished name is the SDK's own, so a keystore generated here is indistinguishable from one the
 * SDK would have generated — which matters for anything that recognises a debug build by its certificate.
 */
private fun Project.generateDebugKeystore(keystore: File) {
    val keytool = File(System.getProperty("java.home"), "bin/keytool").takeIf { it.canExecute() }?.absolutePath
        ?: "keytool"
    val command = listOf(
        keytool, "-genkeypair",
        "-keystore", keystore.absolutePath,
        "-storepass", DEBUG_STORE_PASSWORD,
        "-alias", DEBUG_KEY_ALIAS,
        "-keypass", DEBUG_KEY_PASSWORD,
        "-keyalg", "RSA",
        "-keysize", "2048",
        "-validity", DEBUG_VALIDITY_DAYS,
        "-dname", DEBUG_DISTINGUISHED_NAME,
    )
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    if (process.waitFor() != 0 || !keystore.isFile) {
        throw GradleException(
            "BookWave could not create a debug keystore at ${keystore.absolutePath}\n\n" +
                output.trim() + "\n\n" +
                "Set $STABLE_DEBUG_PROPERTY=false to fall back to AGP's own debug key, at the cost of\n" +
                "having to uninstall the app whenever the key changes.",
        )
    }
    logger.lifecycle(
        "BookWave: created a stable debug keystore at ${keystore.absolutePath}. " +
            "The first install after this needs an uninstall; none after it will.",
    )
}

private fun Project.expandHome(path: String): File {
    val expanded = if (path.startsWith("~/")) File(homeDirectory(), path.removePrefix("~/")) else File(path)
    return if (expanded.isAbsolute) expanded else File(homeDirectory(), path)
}

private fun homeDirectory(): String = System.getProperty("user.home")

/** `bookwave.signing.debug.stable=false` keeps AGP's generated debug key. */
private const val STABLE_DEBUG_PROPERTY = "bookwave.signing.debug.stable"

/** An explicit path, for CI or for sharing one key between machines. */
private const val DEBUG_KEYSTORE_PROPERTY = "bookwave.signing.debug.keystore"
private const val DEBUG_KEYSTORE_ENV = "BOOKWAVE_DEBUG_KEYSTORE"

private const val DEFAULT_DEBUG_KEYSTORE = ".bookwave/debug.keystore"
private const val ANDROID_DEBUG_KEYSTORE = ".android/debug.keystore"

/*
 * Android's public debug-keystore constants. Not secrets, and deliberately not configurable: the whole
 * point is that a keystore created here and one the SDK created are interchangeable.
 */
private const val DEBUG_STORE_PASSWORD = "android"
private const val DEBUG_KEY_ALIAS = "androiddebugkey"
private const val DEBUG_KEY_PASSWORD = "android"

/* Overrides, for pointing the debug build at a keystore with credentials of its own. See [debugValue]. */
private const val DEBUG_STORE_PASSWORD_PROPERTY = "bookwave.signing.debug.storePassword"
private const val DEBUG_STORE_PASSWORD_ENV = "BOOKWAVE_DEBUG_STORE_PASSWORD"
private const val DEBUG_KEY_ALIAS_PROPERTY = "bookwave.signing.debug.keyAlias"
private const val DEBUG_KEY_ALIAS_ENV = "BOOKWAVE_DEBUG_KEY_ALIAS"
private const val DEBUG_KEY_PASSWORD_PROPERTY = "bookwave.signing.debug.keyPassword"
private const val DEBUG_KEY_PASSWORD_ENV = "BOOKWAVE_DEBUG_KEY_PASSWORD"
private const val DEBUG_VALIDITY_DAYS = "10000"
private const val DEBUG_DISTINGUISHED_NAME = "CN=Android Debug,O=Android,C=US"
