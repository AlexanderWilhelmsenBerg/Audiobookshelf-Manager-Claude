package com.example.shelfplayer.core.database.migration

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.shelfplayer.core.database.ShelfPlayerDatabase
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * PRODUCT_SPEC 18 / 22.11 — a migration test for every version bump, because the alternative to a
 * migration is destroying a user's only copy of their listening position.
 *
 * The version-1 schema is not transcribed into this file. It is read from the committed
 * `schemas/…/1.json`, which is the artifact Room itself exported, so the test runs against the schema
 * that shipped rather than against a developer's recollection of it. A hand-copied `CREATE TABLE` that
 * drifted from the export would make this test pass while the real migration failed.
 *
 * Robolectric, because the subject is SQLite behaviour.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTo2Test {

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
     * The property that matters: rows written by version 1 are still there afterwards, with their
     * values intact and the new columns at their documented defaults.
     */
    @Test
    fun `version 1 data survives the migration to version 2`() = runTest {
        createVersion1WithData()

        val migrated = openWithMigrations()

        val server = migrated.profileDao().findServer(SERVER_ID)
        assertNotNull(server, "the version 1 server row was lost")
        assertEquals("Demo", server.displayName)
        assertEquals("https://books.example", server.baseUrl)
        // The columns version 2 adds, at the defaults the migration declares.
        assertEquals("[]", server.authMethodsJson)
        assertEquals("[]", server.capabilitiesJson)
        assertNull(server.capabilitiesDetectedAt)

        val profile = migrated.profileDao().findProfile(PROFILE_ID)
        assertNotNull(profile, "the version 1 profile row was lost")
        assertEquals("ada", profile.username)
        assertNull(profile.remoteUserId)
    }

    /**
     * Room validates the migrated schema against the one it expects and throws if they differ. Reading
     * through a DAO is what forces that validation to run, so this test fails loudly on a migration
     * that produced a *nearly* correct schema — a missing default, a wrong nullability.
     */
    @Test
    fun `the migrated schema is the one Room expects for version 2`() = runTest {
        createVersion1WithData()

        val migrated = openWithMigrations()

        assertEquals(emptyList(), migrated.libraryDao().observeLibraries(SERVER_ID).first())
        assertEquals(1, migrated.profileDao().observeProfiles().first().size)
    }

    /** A fresh install must reach the same place as a migrated one, or the two diverge silently. */
    @Test
    fun `a database created at version 2 has the same columns as a migrated one`() = runTest {
        createVersion1WithData()
        val migrated = openWithMigrations()
        val migratedColumns = columnsOf(migrated.openHelper.readableDatabase, "servers")
        migrated.close()
        database = null
        databaseFile.delete()

        val fresh = Room.databaseBuilder(context, ShelfPlayerDatabase::class.java, databaseFile.path)
            .addMigrations(*Migrations.ALL.toTypedArray())
            .build()
        database = fresh

        assertEquals(migratedColumns, columnsOf(fresh.openHelper.readableDatabase, "servers"))
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
     * Builds a real version-1 database from the exported schema and puts a row in each table this
     * migration touches.
     */
    private fun createVersion1WithData() {
        val schema = Json.parseToJsonElement(exportedSchema(version = 1)).jsonObject
        val database = schema.getValue("database").jsonObject
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseFile.path)
                .callback(object : SupportSQLiteOpenHelper.Callback(VERSION_1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        database.getValue("entities").jsonArray.forEach { entity ->
                            // Tables and their indices are separate statements in the export, and the
                            // indices are not optional: Room compares them, so a fixture database
                            // missing one fails validation for a reason that has nothing to do with
                            // the migration under test.
                            createStatementsOf(entity.jsonObject).forEach(db::execSQL)
                        }
                        // Room stores its schema fingerprint here and checks it on open. Writing the
                        // version-1 hash is what makes this a genuine version-1 database rather than an
                        // unidentified one that Room would refuse or silently recreate.
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS room_master_table " +
                                "(id INTEGER PRIMARY KEY, identity_hash TEXT)",
                        )
                        db.execSQL(
                            "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, ?)",
                            arrayOf(database.getValue("identityHash").jsonPrimitive.content),
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                        error("the fixture database must not be upgraded by its own helper")
                })
                .build(),
        )
        helper.writableDatabase.use { db ->
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
        helper.close()
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
        const val SERVER_ID = "srv_test"
        const val PROFILE_ID = "prf_test"

        /** Room's placeholder for the table name in an exported `createSql`. */
        const val TABLE_NAME_PLACEHOLDER = "\${TABLE_NAME}"
    }
}
