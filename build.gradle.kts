plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.protobuf) apply false
    alias(libs.plugins.room) apply false
    id("shelfplayer.quality")
}

/**
 * PRODUCT_SPEC 16.5 — the single verification command for agents and CI.
 *
 * `verifyDebug` fans out to every module's own `verifyDebug`, which runs ktlint, detekt with type
 * resolution, Android Lint, unit tests and the debug assembly. Room schema verification is attached
 * by `:core:database`.
 */
tasks.named("verifyDebug") {
    // `:core` and `:data` exist only as containers for `:core:*` and `:data:*`; they have no build
    // file and therefore no `verifyDebug`. Resolving the list lazily also means a module added later
    // is picked up without touching this file.
    dependsOn(
        java.util.concurrent.Callable {
            subprojects
                .filter { project -> "verifyDebug" in project.tasks.names }
                .map { project -> "${project.path}:verifyDebug" }
        },
    )
}

/**
 * PRODUCT_SPEC 16.1 — dependency locking.
 *
 * Run `./gradlew resolveAndLockAll --write-locks` (or `scripts/update-dependency-locks.sh`) to
 * regenerate every `gradle.lockfile` after a version-catalog change.
 */
tasks.register("resolveAndLockAll") {
    group = "build setup"
    description = "Resolves every lockable configuration so that --write-locks can record it."
    notCompatibleWithConfigurationCache("Resolves configurations at execution time by design.")
    doFirst {
        require(gradle.startParameter.isWriteDependencyLocks) {
            "resolveAndLockAll must be invoked with --write-locks"
        }
    }
    doLast {
        val unresolvable = mutableListOf<String>()
        allprojects.forEach { project ->
            project.configurations
                .filter { it.isCanBeResolved && !it.name.startsWith("detached") }
                .forEach { configuration ->
                    try {
                        configuration.resolve()
                    } catch (failure: org.gradle.api.artifacts.ResolveException) {
                        // Some AGP configurations are only resolvable inside a variant context.
                        // They are reported rather than swallowed (PRODUCT_SPEC 16.3).
                        unresolvable += "${project.path}:${configuration.name} (${failure.message})"
                    }
                }
        }
        if (unresolvable.isNotEmpty()) {
            logger.lifecycle(
                "Skipped ${unresolvable.size} configuration(s) that cannot be resolved standalone:\n" +
                    unresolvable.joinToString(separator = "\n") { "  - $it" },
            )
        }
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
