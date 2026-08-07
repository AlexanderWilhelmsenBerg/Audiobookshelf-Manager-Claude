plugins {
    id("shelfplayer.android.library")
    id("shelfplayer.android.room")
    id("shelfplayer.hilt")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.example.shelfplayer.core.database"
}

/**
 * PRODUCT_SPEC 9.3 — Room entities never leave this module.
 *
 * `:data:*` maps entities to `:core:model` types; nothing in `:domain` or `:app` can name an
 * `*Entity`, because `:core:database` is only ever an `implementation` dependency.
 */
dependencies {
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(projects.core.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    // PRODUCT_SPEC 18 — a migration test per version bump. MigrationTestHelper opens the exported
    // schema for the old version, so the committed `schemas/` files are what the test runs against.
    testImplementation(libs.androidx.room.testing)
}

/**
 * PRODUCT_SPEC 16.5 — `verifyDebug` depends on Room schema verification.
 *
 * The exported schema is the reviewable artifact behind PRODUCT_SPEC 13.1's ban on destructive
 * migrations: without it a column change is invisible in a diff, and the only way to survive one is
 * `fallbackToDestructiveMigration`.
 *
 * This task proves the export is wired and materialised. CI additionally runs
 * `git diff --exit-code -- core/database/schemas`, which is what catches an *uncommitted* schema
 * change (see .github/workflows/pull-request.yml).
 */
val databaseClassName = "com.example.shelfplayer.core.database.ShelfPlayerDatabase"

// Must match ShelfPlayerDatabase's @Database(version = ...).
val databaseVersion = 7

val verifyRoomSchemas by tasks.registering {
    group = "verification"
    description = "Fails if Room did not export a schema for database version $databaseVersion."
    dependsOn("kspDebugKotlin")

    val schemaDir = layout.projectDirectory.dir("schemas").asFile
    val expectedFile = File(File(schemaDir, databaseClassName), "$databaseVersion.json")

    doLast {
        check(expectedFile.isFile) {
            "Room did not export ${expectedFile.path}. Either the `room { schemaDirectory(...) }` " +
                "configuration stopped applying, or @Database(version) no longer matches " +
                "`databaseVersion` in this build script."
        }
    }
}

tasks.named("verifyDebug") {
    dependsOn(verifyRoomSchemas)
}
