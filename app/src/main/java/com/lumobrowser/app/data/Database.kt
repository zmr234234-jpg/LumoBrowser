package com.lumobrowser.app.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "bookmarks",
    indices = [
        Index(value = ["sortOrder"]),
        Index(value = ["category"]),
        Index(value = ["isPinned"]),
    ],
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "url") val url: String,
    @ColumnInfo(name = "description") val description: String = "",
    @ColumnInfo(name = "category") val category: String = "",
    @ColumnInfo(name = "isPinned") val isPinned: Boolean = false,
    @ColumnInfo(name = "sortOrder") val sortOrder: Long = 0L,
    @ColumnInfo(name = "createdAt") val createdAt: Long = 0L,
    @ColumnInfo(name = "updatedAt") val updatedAt: Long = 0L,
    // ---- Added in schema version 2 (see MIGRATION_1_2) ----
    // The browsing mode is stored as `BrowserMode.storageName`, not as the Kotlin enum name, so
    // renaming a constant cannot rewrite what the user picked. Nullable only for the "last mode
    // that actually worked" hint, which is absent until a page has loaded at least once.
    //
    // The text defaults are written as *quoted* SQL literals on purpose: Room copies the
    // `defaultValue` string verbatim into `CREATE TABLE`, and `MIGRATION_1_2` has to produce a
    // column whose `PRAGMA table_info(...).dflt_value` is byte-identical, or Room's schema
    // validation rejects the migrated database. `'auto'` on both sides is unambiguous; an
    // unquoted `auto` relies on SQLite's bare-identifier fallback and is not worth the risk.
    @ColumnInfo(name = "browserMode", defaultValue = "'auto'") val browserMode: String = "auto",
    @ColumnInfo(name = "userAgentOverride", defaultValue = "''") val userAgentOverride: String = "",
    @ColumnInfo(name = "javaScriptEnabled", defaultValue = "1") val javaScriptEnabled: Boolean = true,
    @ColumnInfo(name = "domStorageEnabled", defaultValue = "1") val domStorageEnabled: Boolean = true,
    @ColumnInfo(name = "desktopSiteEnabled", defaultValue = "0") val desktopSiteEnabled: Boolean = false,
    @ColumnInfo(name = "useThirdPartyCookies", defaultValue = "0") val useThirdPartyCookies: Boolean = false,
    @ColumnInfo(name = "lastSuccessfulMode") val lastSuccessfulMode: String? = null,
    @ColumnInfo(name = "lastModeChangeTime", defaultValue = "0") val lastModeChangeTime: Long = 0L,
    // ---- Added in schema version 3 (see MIGRATION_2_3) ----
    // Stored as `SiteNetworkAssist.storageName` for the same reason as `browserMode`: renaming a
    // Kotlin constant must not be able to rewrite what the user chose. The quoted literal matters
    // here too — see the note on the version-2 defaults above.
    @ColumnInfo(name = "networkAssist", defaultValue = "'global'") val networkAssist: String = "global",
)

fun BookmarkEntity.toDomain(): Bookmark = Bookmark(
    id = id,
    title = title,
    url = url,
    description = description,
    category = category,
    isPinned = isPinned,
    sortOrder = sortOrder,
    createdAt = createdAt,
    updatedAt = updatedAt,
    browserMode = BrowserMode.fromStorage(browserMode),
    userAgentOverride = userAgentOverride,
    javaScriptEnabled = javaScriptEnabled,
    domStorageEnabled = domStorageEnabled,
    desktopSiteEnabled = desktopSiteEnabled,
    useThirdPartyCookies = useThirdPartyCookies,
    lastSuccessfulMode = lastSuccessfulMode?.let { BrowserMode.fromStorage(it) },
    lastModeChangeTime = lastModeChangeTime,
    networkAssist = SiteNetworkAssist.fromStorage(networkAssist),
)

fun Bookmark.toEntity(): BookmarkEntity = BookmarkEntity(
    id = id,
    title = title,
    url = url,
    description = description,
    category = category,
    isPinned = isPinned,
    sortOrder = sortOrder,
    createdAt = createdAt,
    updatedAt = updatedAt,
    browserMode = browserMode.storageName,
    userAgentOverride = userAgentOverride,
    javaScriptEnabled = javaScriptEnabled,
    domStorageEnabled = domStorageEnabled,
    desktopSiteEnabled = desktopSiteEnabled,
    useThirdPartyCookies = useThirdPartyCookies,
    lastSuccessfulMode = lastSuccessfulMode?.storageName,
    lastModeChangeTime = lastModeChangeTime,
    networkAssist = networkAssist.storageName,
)

@Dao
interface BookmarkDao {

    @Query("SELECT * FROM bookmarks ORDER BY isPinned DESC, sortOrder ASC, id ASC")
    fun observeAll(): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): BookmarkEntity?

    @Query("SELECT * FROM bookmarks ORDER BY isPinned DESC, sortOrder ASC, id ASC")
    suspend fun getAllOnce(): List<BookmarkEntity>

    @Query(
        """
        SELECT * FROM bookmarks
        WHERE (:query = '' OR title LIKE '%' || :query || '%'
               OR url LIKE '%' || :query || '%'
               OR description LIKE '%' || :query || '%'
               OR category LIKE '%' || :query || '%')
        ORDER BY isPinned DESC, sortOrder ASC, id ASC
        """
    )
    fun search(query: String): Flow<List<BookmarkEntity>>

    @Query("SELECT DISTINCT category FROM bookmarks WHERE category != '' ORDER BY category ASC")
    fun observeCategories(): Flow<List<String>>

    @Query("SELECT COALESCE(MAX(sortOrder), 0) FROM bookmarks")
    suspend fun maxSortOrder(): Long

    @Query("SELECT COUNT(*) FROM bookmarks")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: BookmarkEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<BookmarkEntity>)

    @Update
    suspend fun update(entity: BookmarkEntity)

    @Delete
    suspend fun delete(entity: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM bookmarks")
    suspend fun deleteAll()

    @Query("UPDATE bookmarks SET sortOrder = :sortOrder, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateSortOrder(id: Long, sortOrder: Long, updatedAt: Long)

    @Query("UPDATE bookmarks SET isPinned = :pinned, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updatePinned(id: Long, pinned: Boolean, updatedAt: Long)

    /**
     * The browser knows the page it is showing, not the bookmark's row id, so resolving per-site
     * settings from the address needs this lookup. Exact match on the stored URL: normalisation
     * happens before the row is written, so two spellings of one address cannot both exist.
     */
    @Query("SELECT * FROM bookmarks WHERE url = :url LIMIT 1")
    suspend fun findByUrl(url: String): BookmarkEntity?

    /**
     * Records a mode the user chose from inside the browser, without touching any other column.
     *
     * A targeted `UPDATE` rather than a read-modify-write of the whole row: the browser may fire
     * this while the editor has unsaved text for the same bookmark, and rewriting the row from a
     * stale copy is exactly how an unsaved edit gets silently reverted.
     */
    @Query(
        """
        UPDATE bookmarks
        SET browserMode = :mode, lastSuccessfulMode = :lastSuccessful, lastModeChangeTime = :now
        WHERE id = :id
        """
    )
    suspend fun updateBrowserMode(id: Long, mode: String, lastSuccessful: String?, now: Long)

    /**
     * Records the per-site network-assist preference, without touching any other column.
     *
     * Same reasoning as [updateBrowserMode]: this is written from the browser while the editor may
     * be holding unsaved text for the same row, and a read-modify-write would revert it.
     */
    @Query("UPDATE bookmarks SET networkAssist = :value, updatedAt = :now WHERE id = :id")
    suspend fun updateNetworkAssist(id: Long, value: String, now: Long)
}

/**
 * Schema 1 → 2: the eight per-site browsing columns.
 *
 * Additive only. Every existing row keeps its id, title, url, description, category, pin state,
 * order and timestamps; the new columns take the same defaults a freshly inserted row would get, so
 * an upgraded database and a new install are indistinguishable afterwards. No table is dropped and
 * no data is copied, which is why this cannot lose a user's bookmarks.
 */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE bookmarks ADD COLUMN browserMode TEXT NOT NULL DEFAULT 'auto'")
        db.execSQL("ALTER TABLE bookmarks ADD COLUMN userAgentOverride TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE bookmarks ADD COLUMN javaScriptEnabled INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE bookmarks ADD COLUMN domStorageEnabled INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE bookmarks ADD COLUMN desktopSiteEnabled INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE bookmarks ADD COLUMN useThirdPartyCookies INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE bookmarks ADD COLUMN lastSuccessfulMode TEXT")
        db.execSQL("ALTER TABLE bookmarks ADD COLUMN lastModeChangeTime INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * Schema 2 → 3: the per-site network-assist preference.
 *
 * Additive, exactly like [MIGRATION_1_2], and for the same reason: an installed copy of LUMO holds
 * the user's whole library, and the only acceptable upgrade is one that leaves every row readable.
 * One `ALTER TABLE` adds one column with the default a fresh row would get, so an upgraded database
 * and a new install are indistinguishable afterwards. Nothing is copied, nothing is dropped, and
 * the columns added by the previous migration are untouched.
 */
val MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE bookmarks ADD COLUMN networkAssist TEXT NOT NULL DEFAULT 'global'")
    }
}

@Database(
    entities = [BookmarkEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class LumoDatabase : RoomDatabase() {
    abstract fun bookmarkDao(): BookmarkDao

    companion object {
        const val NAME = "lumobrowser.db"

        @Volatile
        private var instance: LumoDatabase? = null

        /**
         * The process-wide database.
         *
         * One instance per process is the documented Room contract (each `RoomDatabase` owns a
         * connection pool and an invalidation tracker); creating a second one would double both and
         * break `Flow` invalidation between the two.
         *
         * `addMigrations(MIGRATION_1_2)` is deliberately *not* paired with any destructive
         * fallback. An install that already holds bookmarks must be upgraded in place; if the
         * migration ever failed to validate, Room throwing is the correct outcome — dropping the
         * user's table to make the error go away is not.
         *
         * Both migrations are registered. A device that skipped a release still has version 1 on
         * disk, and Room walks 1 → 2 → 3 for it; registering only the newest one would make that
         * upgrade throw.
         */
        fun get(context: Context): LumoDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    LumoDatabase::class.java,
                    NAME,
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { instance = it }
            }
    }
}
