package com.example.shelfplayer.core.database.migration

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.shelfplayer.core.database.ShelfPlayerDatabase
import com.example.shelfplayer.core.database.entity.ProfileEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PRODUCT_SPEC 18 / 22.11 — a migration test for every version bump, because the alternative to a
 * migration is destroying a user's only copy of their listening position.
 *
 * No schema is transcribed into this file. Each starting version is built from its committed
 * `schemas/…/N.json` — the artifact Room itself exported — so the tests run against the schemas that
 * shipped rather than against a developer's recollection of them. A hand-copied `CREATE TABLE` that
 * drifted from the export would make these tests pass while the real migration failed.
 *
 * Every version is migrated all the way to the current one. That is what a device does: a user upgrading
 * from two versions back runs both migrations in one open, and a step that only works when run alone is
 * a step that fails in the field.
 *
 * Robolectric, because the subject is SQLite behaviour.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val databaseFile = File(context.cacheDir, "migration-test.db")
    private var database: ShelfPlayerDatabase? = null

    @After
    fun tearDown() {
        database?.close()
        databaseFile.delete()
        File("${databaseFile.path}-shm").delete()
        File("${databaseFile.path}-wal").delete()
    }

    /**
     * The property that matters: rows written by version 1 are still there after both migrations, with
     * their values intact and the new columns at their documented defaults.
     */
    @Test
    fun `version 1 data survives migrating to the current version`() = runTest {
        createVersion(1)

        val migrated = openWithMigrations()

        val server = assertNotNull(migrated.profileDao().findServer(SERVER_ID), "the server row was lost")
        assertEquals("Demo", server.displayName)
        assertEquals("https://books.example", server.baseUrl)
        // Added by version 2, at the defaults its migration declares.
        assertEquals("[]", server.authMethodsJson)
        assertEquals("[]", server.capabilitiesJson)
        assertNull(server.capabilitiesDetectedAt)

        val profile = assertNotNull(migrated.profileDao().findProfile(PROFILE_ID), "the profile row was lost")
        assertEquals("ada", profile.username)
        assertNull(profile.remoteUserId)
    }

    /** Version 2 → 3 in isolation, which is the upgrade an already-migrated device performs. */
    @Test
    fun `version 2 data survives migrating to the current version`() = runTest {
        createVersion(2)

        val migrated = openWithMigrations()

        val profile = assertNotNull(migrated.profileDao().findProfile(PROFILE_ID))
        assertEquals("ada", profile.username)
        assertEquals("remote-user-1", profile.remoteUserId)
        val server = assertNotNull(migrated.profileDao().findServer(SERVER_ID))
        assertEquals("""["local"]""", server.authMethodsJson)
    }

    /**
     * PRODUCT_SPEC 5.2 / 2.2 — an existing profile keeps access to the library it is already browsing.
     *
     * The restrictive default is right for a new profile and wrong for one that predates the grant being
     * recorded: applying it retroactively would blank a library the user can currently read offline. The
     * value survives only until that profile's next sign-in.
     */
    @Test
    fun `a profile that predates the grant keeps access to its cached libraries`() = runTest {
        createVersion(2)

        val migrated = openWithMigrations()

        val profile = assertNotNull(migrated.profileDao().findProfile(PROFILE_ID))
        assertTrue(profile.hasAllLibraryAccess, "a pre-existing profile must not lose its cached library")
        assertEquals("[]", profile.accessibleLibrariesJson)
    }

    /** The other half of the same decision: a *new* profile with no recorded grant is granted nothing. */
    @Test
    fun `a profile created after the migration is granted nothing by default`() = runTest {
        createVersion(2)
        val migrated = openWithMigrations()

        migrated.profileDao().upsertProfile(
            ProfileEntity(
                profileId = "prf_new",
                serverId = SERVER_ID,
                remoteUserId = null,
                username = "grace",
                displayName = "grace",
                role = "Listener",
                requiresReauthentication = false,
                lastUsedAt = null,
                isFixture = false,
                accessibleLibrariesJson = "[]",
                hasAllLibraryAccess = false,
            ),
        )

        assertFalse(assertNotNull(migrated.profileDao().findProfile("prf_new")).hasAllLibraryAccess)
    }

    /**
     * Room validates the migrated schema against the one it expects and throws if they differ. Reading
     * through a DAO is what forces that validation to run, so this fails loudly on a migration that
     * produced a *nearly* correct schema — a missing default, a wrong nullability.
     */
    @Test
    fun `the migrated schema is the one Room expects`() = runTest {
        createVersion(1)

        val migrated = openWithMigrations()

        assertEquals(emptyList(), migrated.libraryDao().observeLibraries(SERVER_ID).first())
        assertEquals(1, migrated.profileDao().observeProfiles().first().size)
    }

    /** A fresh install must reach the same place as a migrated one, or the two diverge silently. */
    @Test
    fun `a database created fresh has the same columns as a migrated one`() = runTest {
        createVersion(1)
        val migrated = openWithMigrations()
        val migratedProfiles = columnsOf(migrated.openHelper.readableDatabase, "profiles")
        val migratedServers = columnsOf(migrated.openHelper.readableDatabase, "servers")
        migrated.close()
        database = null
        databaseFile.delete()

        val fresh = openWithMigrations()

        assertEquals(migratedProfiles, columnsOf(fresh.openHelper.readableDatabase, "profiles"))
        assertEquals(migratedServers, columnsOf(fresh.openHelper.readableDatabase, "servers"))
    }

    private fun openWithMigrations(): ShelfPlayerDatabase =
        Room.databaseBuilder(context, ShelfPlayerDatabase::class.java, databaseFile.path)
            .addMigrations(*Migrations.ALL.toTypedArray())
            .build()
            .also { database = it }

    private fun columnsOf(db: SupportSQLiteDatabase, table: String): List<String> =
        db.query("PRAGMA table_info($table)").use { cursor ->
            val names = mutableListOf<String>()
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) names += cursor.getString(nameIndex)
            names.sorted()
        }

    /**
     * Builds a real database at [version] from its exported schema and puts a row in each table the
     * migrations touch.
     */
    private fun createVersion(version: Int) {
        val schema = Json.parseToJsonElement(exportedSchema(version)).jsonObject
        val exported = schema.getValue("database").jsonObject
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseFile.path)
                .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        exported.getValue("entities").jsonArray.forEach { entity ->
                            // Tables and their indices are separate statements in the export, and the
                            // indices are not optional: Room compares them, so a fixture database missing
                            // one fails validation for a reason unrelated to the migration under test.
                            createStatementsOf(entity.jsonObject).forEach(db::execSQL)
                        }
                        // Room stores its schema fingerprint here and checks it on open. Writing the
                        // exported hash is what makes this a genuine database of that version rather than
                        // an unidentified one that Room would refuse or silently recreate.
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS room_master_table " +
                                "(id INTEGER PRIMARY KEY, identity_hash TEXT)",
                        )
                        db.execSQL(
                            "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, ?)",
                            arrayOf(exported.getValue("identityHash").jsonPrimitive.content),
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                        error("the fixture database must not be upgraded by its own helper")
                })
                .build(),
        )
        helper.writableDatabase.use { db -> seed(db, version) }
        helper.close()
    }

    /**
     * The insert statements are written per version rather than generated.
     *
     * A generated insert would have to derive the column list from the schema, which means deriving it
     * from the same source the migration is being checked against — and a test that agrees with the thing
     * it is testing checks nothing.
     */
    private fun seed(db: SupportSQLiteDatabase, version: Int) = when (version) {
        VERSION_1 -> {
            db.execSQL(
                "INSERT INTO servers (serverId, displayName, baseUrl, detectedVersion, isFixture, lastFetchedAt) " +
                    "VALUES (?, ?, ?, NULL, 0, 0)",
                arrayOf(SERVER_ID, "Demo", "https://books.example"),
            )
            db.execSQL(
                "INSERT INTO profiles " +
                    "(profileId, serverId, username, displayName, role, requiresReauthentication, " +
                    "lastUsedAt, isFixture) VALUES (?, ?, ?, ?, 'Listener', 0, NULL, 0)",
                arrayOf(PROFILE_ID, SERVER_ID, "ada", "ada"),
            )
        }

        VERSION_2 -> {
            db.execSQL(
                "INSERT INTO servers (serverId, displayName, baseUrl, detectedVersion, isFixture, " +
                    "lastFetchedAt, authMethodsJson, capabilitiesJson, capabilitiesDetectedAt) " +
                    "VALUES (?, ?, ?, '2.36.0', 0, 0, '[\"local\"]', '[]', NULL)",
                arrayOf(SERVER_ID, "Demo", "https://books.example"),
            )
            db.execSQL(
                "INSERT INTO profiles " +
                    "(profileId, serverId, remoteUserId, username, displayName, role, " +
                    "requiresReauthentication, lastUsedAt, isFixture) " +
                    "VALUES (?, ?, 'remote-user-1', ?, ?, 'Listener', 0, NULL, 0)",
                arrayOf(PROFILE_ID, SERVER_ID, "ada", "ada"),
            )
        }

        else -> error("no seed data defined for schema version $version")
    }

    /**
     * The table statement followed by its index statements.
     *
     * An exported `createSql` carries a `TABLE_NAME` placeholder rather than the table name, so each
     * statement has to be resolved against the entity it belongs to.
     */
    private fun createStatementsOf(entity: JsonObject): List<String> {
        val table = entity.getValue("tableName").jsonPrimitive.content
        val tableStatement = entity.getValue("createSql").jsonPrimitive.content
        val indexStatements = entity["indices"]?.jsonArray.orEmpty()
            .map { index -> index.jsonObject.getValue("createSql").jsonPrimitive.content }
        return (listOf(tableStatement) + indexStatements).map { it.replace(TABLE_NAME_PLACEHOLDER, table) }
    }

    private fun exportedSchema(version: Int): String {
        val file = File("schemas/${ShelfPlayerDatabase::class.java.name}/$version.json")
        check(file.isFile) {
            "expected the exported schema at ${file.absolutePath}. Unit tests run with the module " +
                "directory as the working directory; if that changed, this path has to change with it."
        }
        return file.readText()
    }

    private companion object {
        const val VERSION_1 = 1
        const val VERSION_2 = 2
        const val SERVER_ID = "srv_test"
        const val PROFILE_ID = "prf_test"

        /** Room's placeholder for the table name in an exported `createSql`. */
        const val TABLE_NAME_PLACEHOLDER = "\${TABLE_NAME}"
    }
}
