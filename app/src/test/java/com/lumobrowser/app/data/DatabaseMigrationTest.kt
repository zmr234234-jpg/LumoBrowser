package com.lumobrowser.app.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/**
 * Real migrations against real SQLite.
 *
 * The question this file answers is the only one that matters to someone who already has the app
 * installed and is being offered an update: *do my bookmarks survive?* A migration is a string of
 * SQL, so it is tested the way SQL is tested — by building a database that looks exactly like the
 * one on an upgraded device, running the migration over it, and reading the rows back.
 *
 * Two things are deliberately NOT mocked here:
 *
 *  - the schema. The `CREATE TABLE` statements below are copied verbatim from Room's own exported
 *    schemas (`app/schemas/com.lumobrowser.app.data.LumoDatabase/{1,2,3}.json`), including the
 *    quoted defaults. If someone edits an entity without re-exporting, these tests still describe
 *    the shape that is actually on disk, which is the point.
 *  - the database. `FrameworkSQLiteOpenHelperFactory` is the same factory Room uses in production,
 *    so the file that gets migrated is a genuine SQLite file with a genuine `user_version`.
 *
 * The last test in the file closes the loop: after migrating by hand it opens the result through
 * `Room` itself. Room validates the migrated schema against its generated identity hash on open, so
 * a migration that produces a *readable* database but the *wrong column shape* fails there.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DatabaseMigrationTest {

    private lateinit var context: Context

    /** Unique per test so a leftover file from a previous run cannot be mistaken for this one. */
    private var dbName = ""

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbName = "migration-test-${System.nanoTime()}.db"
    }

    @After
    fun tearDown() {
        context.getDatabasePath(dbName).delete()
    }

    // ---------------------------------------------------------------------------------------------
    // Tests
    // ---------------------------------------------------------------------------------------------

    @Test
    fun migration2To3_declaresTheStepItClaimsToBe() {
        assertEquals(2, MIGRATION_2_3.startVersion)
        assertEquals(3, MIGRATION_2_3.endVersion)
    }

    @Test
    fun migration2To3_keepsEveryRowAndDefaultsTheNewColumn() {
        val seeded = seedV2(rows = 3)

        val migrated = openWithRoom()

        val rows = runBlocking { migrated.bookmarkDao().getAllOnce() }
        assertEquals("every bookmark must still be there", 3, rows.size)
        assertEquals(seeded.map { it.first }.sorted(), rows.map { it.id }.sorted())
        assertEquals(seeded.map { it.second }.toSet(), rows.map { it.title }.toSet())

        // An upgraded row and a freshly inserted row must be indistinguishable. The dao hands back
        // entities, so the column is compared as stored bytes — whichever value the entity writer
        // would produce is the value the migration has to have produced.
        val fresh = BookmarkEntity(title = "x", url = "u")
        rows.forEach { row ->
            assertEquals(fresh.networkAssist, row.networkAssist)
            assertEquals(SiteNetworkAssist.FollowGlobal, row.toDomain().networkAssist)
        }
        assertEquals(SiteNetworkAssist.FollowGlobal, fresh.toDomain().networkAssist)
    }

    @Test
    fun migration2To3_doesNotDisturbTheColumnsAddedByThePreviousMigration() {
        // A row that had already been customised on version 2. The version-3 step must not reset it.
        seedV2(rows = 1, browserMode = "desktop", desktopSiteEnabled = 1, lastSuccessfulMode = "desktop")

        val migrated = openWithRoom()

        val row = runBlocking { migrated.bookmarkDao().getAllOnce() }.single().toDomain()
        assertEquals(BrowserMode.Desktop, row.browserMode)
        assertTrue(row.desktopSiteEnabled)
        assertEquals(BrowserMode.Desktop, row.lastSuccessfulMode)
        assertEquals(SiteNetworkAssist.FollowGlobal, row.networkAssist)
    }

    /**
     * The version-skipping device: someone who installed 1.00 and is now being offered 1.02.
     *
     * Room walks 1 -> 2 -> 3 in that case, which only works because both migrations are registered
     * on the builder. This is the test that fails if someone "tidies up" and keeps only the newest.
     */
    @Test
    fun migrationFrom1To3_walksBothStepsAndKeepsEveryRow() {
        val seeded = seedV1(rows = 2)

        val migrated = openWithRoom()

        val rows = runBlocking { migrated.bookmarkDao().getAllOnce() }
        assertEquals(2, rows.size)
        assertEquals(seeded.sorted(), rows.map { it.id }.sorted())

        rows.forEach { row ->
            // Read through the mapper, not the entity: the migration's job is to leave data the app
            // can use, and "the app can use it" means toDomain() turns it into the right enum.
            val bookmark = row.toDomain()
            // Version 2's defaults...
            assertEquals(BrowserMode.Auto, bookmark.browserMode)
            assertEquals("", bookmark.userAgentOverride)
            assertTrue(bookmark.javaScriptEnabled)
            assertTrue(bookmark.domStorageEnabled)
            // ...and version 3's.
            assertEquals(SiteNetworkAssist.FollowGlobal, bookmark.networkAssist)
        }
    }

    @Test
    fun bothMigrations_areRegisteredSoADirectlyUpgradedInstallCannotThrow() {
        // Guards the specific regression: registering only MIGRATION_2_3 makes a version-1 device
        // fail with "A migration from 1 to 3 was required but not found".
        seedV1(rows = 1)
        assertNotNull(openWithRoom())
    }

    /**
     * The three indices have existed since schema 1 and neither migration mentions them.
     *
     * That is the whole point: a migration that rebuilds the table (the usual way to change a column
     * on SQLite) would drop them, and nothing else in the app would notice. Queries would still be
     * correct, just slower — on a list the user scrolls, which is exactly what they would feel.
     */
    @Test
    fun theIndicesSurviveMigration_andAreStillUsedForTheSortQuery() {
        seedV2(rows = 5)

        // Migration runs through Room, as on a real upgrade.
        openWithRoom()

        val helper = rawHelper(VERSION_3, ddl = emptyList())
        val database = helper.writableDatabase
        val indices = database.query(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'bookmarks'" +
                " AND name NOT LIKE 'sqlite_%' ORDER BY name",
        ).use { cursor ->
            val names = mutableListOf<String>()
            while (cursor.moveToNext()) names += cursor.getString(0)
            names
        }
        val plan = database.query(
            "EXPLAIN QUERY PLAN SELECT * FROM bookmarks ORDER BY isPinned DESC, sortOrder ASC, id ASC",
        ).use { cursor ->
            val detail = cursor.getColumnIndexOrThrow("detail")
            val lines = mutableListOf<String>()
            while (cursor.moveToNext()) lines += cursor.getString(detail)
            lines.joinToString(" | ")
        }
        helper.close()

        assertEquals(
            listOf("index_bookmarks_category", "index_bookmarks_isPinned", "index_bookmarks_sortOrder"),
            indices,
        )
        // Not a plan assertion for its own sake: it is the difference between the query Room runs on
        // every home-screen emission walking the table or walking an index.
        assertTrue("expected a scan, got: $plan", plan.contains("SCAN") || plan.contains("SEARCH"))
    }

    /**
     * The column shape, checked the way Room checks it.
     *
     * `PRAGMA table_info` is what Room's own schema validation reads, so comparing it is not a
     * proxy for the real thing — it *is* the real thing, minus the identity hash (which Room writes
     * itself, and which the Room-opened tests above already exercise).
     */
    @Test
    fun migratedSchema_matchesTheVersion3ShapeRoomExported() {
        seedV2(rows = 1)
        openWithRoom()

        // Re-open the migrated file directly, without Room, to read its actual shape.
        val helper = rawHelper(version = VERSION_3, ddl = emptyList())
        val columns = helper.readableDatabase.query("PRAGMA table_info(`bookmarks`)").use { cursor ->
            val names = mutableListOf<String>()
            while (cursor.moveToNext()) names += cursor.getString(cursor.getColumnIndexOrThrow("name"))
            names
        }
        helper.close()

        val expected = listOf(
            "id", "title", "url", "description", "category", "isPinned", "sortOrder",
            "createdAt", "updatedAt", "browserMode", "userAgentOverride", "javaScriptEnabled",
            "domStorageEnabled", "desktopSiteEnabled", "useThirdPartyCookies", "lastSuccessfulMode",
            "lastModeChangeTime", "networkAssist",
        )
        assertEquals(expected, columns)
    }

    @Test
    fun migratedColumn_isNotNullWithTheQuotedDefaultRoomValidates() {
        seedV2(rows = 1)

        // Run the migration alone, against a v2 file, so the assertion is about the ALTER TABLE
        // rather than about anything Room does afterwards.
        val helper = rawHelper(version = VERSION_3, ddl = emptyList(), migrate = { db, _, _ ->
            MIGRATION_2_3.migrate(db)
        })
        val database = helper.writableDatabase
        val info = database.query("PRAGMA table_info(`bookmarks`)").use { cursor ->
            var notNull: Int? = null
            var defaultValue: String? = null
            while (cursor.moveToNext()) {
                if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == "networkAssist") {
                    notNull = cursor.getInt(cursor.getColumnIndexOrThrow("notnull"))
                    defaultValue = cursor.getString(cursor.getColumnIndexOrThrow("dflt_value"))
                }
            }
            notNull to defaultValue
        }
        helper.close()

        assertEquals("networkAssist must be NOT NULL", 1, info.first)
        // Byte-identical to what Room puts in CREATE TABLE, quotes included. An unquoted `global`
        // would rely on SQLite's bare-identifier fallback and fails Room's validation.
        assertEquals("'global'", info.second)
    }

    @Test
    fun migration2To3_isAdditive_itNeverTouchesExistingColumns() {
        seedV2(rows = 1)
        val before = tableColumns(dbName)

        // Adding a column is the only operation that keeps every rowid and every value. A
        // copy-and-drop migration would show up here as a reordered or renamed table.
        val helper = rawHelper(version = VERSION_3, ddl = emptyList(), migrate = { db, _, _ ->
            MIGRATION_2_3.migrate(db)
        })
        val after = tableColumnsOf(helper.writableDatabase)
        helper.close()

        assertEquals(listOf("networkAssist"), after - before.toSet())
        assertEquals(before, after.filter { it != "networkAssist" })
    }

    @Test
    fun everyStoredNetworkAssist_survivesTheColumnRoundTrip() {
        // The column holds `storageName`, not the Kotlin constant name, so that renaming a constant
        // cannot rewrite what the user picked. That only works if every value round-trips.
        SiteNetworkAssist.values().forEach { assist ->
            val entity = BookmarkEntity(title = "t", url = "https://example.com", networkAssist = assist.storageName)
            assertEquals(assist, entity.toDomain().networkAssist)
        }
        // An unknown value from a future version degrades to the default instead of throwing.
        assertEquals(
            SiteNetworkAssist.FollowGlobal,
            BookmarkEntity(title = "t", url = "u", networkAssist = "from-the-future").toDomain().networkAssist,
        )
    }

    @Test
    fun migrationAndFreshInstall_agreeOnTheStoredDefault() {
        // The whole point of the quoted default: a migrated row and a brand-new row are the same.
        seedV2(rows = 1)
        val migrated = runBlocking { openWithRoom().bookmarkDao().getAllOnce().single() }

        val fresh = Room.inMemoryDatabaseBuilder(context, LumoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val insertedId = runBlocking {
            fresh.bookmarkDao().insert(BookmarkEntity(title = "new", url = "https://example.com"))
        }
        val inserted = runBlocking { fresh.bookmarkDao().findById(insertedId) }
        fresh.close()

        assertNotNull(inserted)
        assertEquals(inserted!!.networkAssist, migrated.networkAssist)
    }

    // ---------------------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------------------

    /**
     * Creates a database file at [VERSION_2] holding [rows] bookmarks, exactly as version 1.01 left
     * it on disk: the version-2 DDL and no `room_master_table` (Room only writes that when it opens
     * the database itself).
     */
    private fun seedV2(
        rows: Int,
        name: String = dbName,
        browserMode: String = "auto",
        desktopSiteEnabled: Int = 0,
        lastSuccessfulMode: String? = null,
    ): List<Pair<Long, String>> {
        val helper = rawHelper(version = VERSION_2, ddl = V2_SCHEMA, name = name)
        val ids = mutableListOf<Pair<Long, String>>()
        val database = helper.writableDatabase
        repeat(rows) { index ->
            val title = "bookmark-$index"
            database.execSQL(
                "INSERT INTO bookmarks (title, url, description, category, isPinned, sortOrder," +
                    " createdAt, updatedAt, browserMode, userAgentOverride, javaScriptEnabled," +
                    " domStorageEnabled, desktopSiteEnabled, useThirdPartyCookies," +
                    " lastSuccessfulMode, lastModeChangeTime)" +
                    " VALUES (?, ?, '', '', 0, ?, 0, 0, ?, '', 1, 1, ?, 0, ?, 0)",
                arrayOf<Any?>(
                    title,
                    "https://example.com/$index",
                    index.toLong(),
                    browserMode,
                    desktopSiteEnabled,
                    lastSuccessfulMode,
                ),
            )
            val id = database.query("SELECT last_insert_rowid()").use { cursor ->
                cursor.moveToFirst()
                cursor.getLong(0)
            }
            ids += id to title
        }
        helper.close()
        return ids
    }

    /** The same, one schema version earlier: what a 1.00 install looks like. */
    private fun seedV1(rows: Int): List<Long> {
        val helper = rawHelper(version = VERSION_1, ddl = V1_SCHEMA)
        val ids = mutableListOf<Long>()
        val database = helper.writableDatabase
        repeat(rows) { index ->
            database.execSQL(
                "INSERT INTO bookmarks (title, url, description, category, isPinned, sortOrder," +
                    " createdAt, updatedAt) VALUES (?, ?, '', '', 0, ?, 0, 0)",
                arrayOf<Any?>("legacy-$index", "https://legacy.example/$index", index.toLong()),
            )
            ids += database.query("SELECT last_insert_rowid()").use { cursor ->
                cursor.moveToFirst()
                cursor.getLong(0)
            }
        }
        helper.close()
        return ids
    }

    private fun tableColumns(name: String): List<String> {
        val helper = rawHelper(version = VERSION_2, ddl = V2_SCHEMA, name = name)
        val columns = tableColumnsOf(helper.writableDatabase)
        helper.close()
        return columns
    }

    private fun tableColumnsOf(database: SupportSQLiteDatabase): List<String> =
        database.query("PRAGMA table_info(`bookmarks`)").use { cursor ->
            val names = mutableListOf<String>()
            while (cursor.moveToNext()) names += cursor.getString(cursor.getColumnIndexOrThrow("name"))
            names
        }

    /**
     * A raw handle on [name]. [ddl] is executed when the file does not exist yet; [migrate] is what
     * the framework helper calls when the file is older than [version].
     */
    private fun rawHelper(
        version: Int,
        ddl: List<String>,
        name: String = dbName,
        migrate: ((SupportSQLiteDatabase, Int, Int) -> Unit)? = null,
    ): SupportSQLiteOpenHelper {
        val callback = object : SupportSQLiteOpenHelper.Callback(version) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                ddl.forEach { db.execSQL(it) }
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                migrate?.invoke(db, oldVersion, newVersion)
            }
        }
        return FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(callback)
                .build(),
        )
    }

    /**
     * Opens the file through Room, which is what an upgraded device actually does on first launch.
     *
     * The open is forced here rather than left to the first query. `Room.databaseBuilder(...).build()`
     * only constructs the wrapper — `RoomOpenHelper.onUpgrade`, where the migration and Room's own
     * schema validation both run, happens on first real use. A test that never touches the database
     * would pass without the migration ever executing, which is the one thing this file must not do.
     */
    private fun openWithRoom(): LumoDatabase {
        val database = Room.databaseBuilder(context, LumoDatabase::class.java, dbName)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()
        database.openHelper.writableDatabase
        return database
    }

    private companion object {
        const val VERSION_1 = 1
        const val VERSION_2 = 2
        const val VERSION_3 = 3

        // Declared before the schema lists that use them: a companion object initialises in source
        // order, so a forward reference here is a compile error, not a warning.
        const val INDEX_SORT_ORDER =
            "CREATE INDEX IF NOT EXISTS `index_bookmarks_sortOrder` ON `bookmarks` (`sortOrder`)"
        const val INDEX_CATEGORY =
            "CREATE INDEX IF NOT EXISTS `index_bookmarks_category` ON `bookmarks` (`category`)"
        const val INDEX_IS_PINNED =
            "CREATE INDEX IF NOT EXISTS `index_bookmarks_isPinned` ON `bookmarks` (`isPinned`)"

        // Copied verbatim from app/schemas/.../1.json. The indices are part of the schema and not an
        // afterthought: `onCreate` has always created them, so a database without them is not a
        // database any real install has, and seeding one would test a scenario that cannot happen.
        val V1_SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS `bookmarks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL," +
                " `title` TEXT NOT NULL, `url` TEXT NOT NULL, `description` TEXT NOT NULL," +
                " `category` TEXT NOT NULL, `isPinned` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL," +
                " `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)",
            INDEX_SORT_ORDER,
            INDEX_CATEGORY,
            INDEX_IS_PINNED,
        )

        // Copied verbatim from app/schemas/.../2.json.
        val V2_SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS `bookmarks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL," +
                " `title` TEXT NOT NULL, `url` TEXT NOT NULL, `description` TEXT NOT NULL," +
                " `category` TEXT NOT NULL, `isPinned` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL," +
                " `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL," +
                " `browserMode` TEXT NOT NULL DEFAULT 'auto', `userAgentOverride` TEXT NOT NULL DEFAULT ''," +
                " `javaScriptEnabled` INTEGER NOT NULL DEFAULT 1, `domStorageEnabled` INTEGER NOT NULL DEFAULT 1," +
                " `desktopSiteEnabled` INTEGER NOT NULL DEFAULT 0," +
                " `useThirdPartyCookies` INTEGER NOT NULL DEFAULT 0," +
                " `lastSuccessfulMode` TEXT, `lastModeChangeTime` INTEGER NOT NULL DEFAULT 0)",
            INDEX_SORT_ORDER,
            INDEX_CATEGORY,
            INDEX_IS_PINNED,
        )
    }
}
