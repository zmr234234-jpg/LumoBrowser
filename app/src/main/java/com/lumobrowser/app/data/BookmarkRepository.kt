package com.lumobrowser.app.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Single source of truth for bookmarks.
 *
 * Ordering model: [BookmarkEntity.sortOrder] is a sparse Long. New bookmarks are appended at
 * `max + STEP`, and a drag-and-drop reorder rewrites only the affected rows to `STEP`
 * multiples, which keeps manual ordering stable across restarts without a separate join table.
 */
class BookmarkRepository(private val dao: BookmarkDao) {

    val bookmarks: Flow<List<Bookmark>> =
        dao.observeAll().map { rows -> rows.map(BookmarkEntity::toDomain) }

    val categories: Flow<List<String>> = dao.observeCategories()

    fun search(query: String): Flow<List<Bookmark>> =
        dao.search(query.trim()).map { rows -> rows.map(BookmarkEntity::toDomain) }

    suspend fun get(id: Long): Bookmark? = dao.findById(id)?.toDomain()

    suspend fun add(
        title: String,
        url: String,
        description: String,
        category: String,
        pinned: Boolean,
        siteConfig: SiteConfig = SiteConfig.Default,
        now: Long = System.currentTimeMillis(),
    ): Long {
        val next = dao.maxSortOrder() + STEP
        // Built as a domain Bookmark first so the browser fields travel through exactly the same
        // conversion as everywhere else; a second hand-written entity literal is how one of the
        // eight new columns ends up silently missing from newly created rows.
        return dao.insert(
            Bookmark(
                title = title.trim(),
                url = UrlTools.normalize(url),
                description = description.trim(),
                category = category.trim(),
                isPinned = pinned,
                sortOrder = if (pinned) -next else next,
                createdAt = now,
                updatedAt = now,
            ).withSiteConfig(siteConfig).toEntity()
        )
    }

    suspend fun update(bookmark: Bookmark, now: Long = System.currentTimeMillis()): Boolean {
        val existing = dao.findById(bookmark.id) ?: return false
        dao.update(
            bookmark.copy(
                title = bookmark.title.trim(),
                url = UrlTools.normalize(bookmark.url),
                description = bookmark.description.trim(),
                category = bookmark.category.trim(),
                updatedAt = now,
            ).toEntity().copy(sortOrder = existing.sortOrder, createdAt = existing.createdAt)
        )
        return true
    }

    suspend fun delete(bookmark: Bookmark) = dao.delete(bookmark.toEntity())

    suspend fun delete(id: Long) = dao.deleteById(id)

    /** Re-inserts a previously deleted bookmark keeping its original identity and position. */
    suspend fun restore(bookmark: Bookmark) = dao.insert(bookmark.toEntity())

    suspend fun setPinned(id: Long, pinned: Boolean, now: Long = System.currentTimeMillis()) =
        dao.updatePinned(id, pinned, now)

    /** Applies a full user-defined ordering (already drag-sorted) in one transaction-ish pass. */
    suspend fun applyOrder(orderedIds: List<Long>, now: Long = System.currentTimeMillis()) {
        orderedIds.forEachIndexed { index, id ->
            dao.updateSortOrder(id, (index + 1).toLong() * STEP, now)
        }
    }

    suspend fun replaceAll(bookmarks: List<Bookmark>) {
        dao.deleteAll()
        dao.insertAll(bookmarks.mapIndexed { index, bookmark ->
            bookmark.copy(sortOrder = (index + 1).toLong() * STEP).toEntity().copy(id = 0L)
        })
    }

    suspend fun count(): Int = dao.count()

    /**
     * Looks a bookmark up by its exact stored URL.
     *
     * The browser knows the address it is showing but not the row id, and the address is what the
     * user recognises when the app reports "this mode is now remembered for this site".
     */
    suspend fun findByUrl(url: String): Bookmark? = dao.findByUrl(url)?.toDomain()

    /**
     * Persists a mode change for one site.
     *
     * Deliberately a targeted column update rather than a read-modify-write of the whole row: this
     * can fire while the editor still holds unsaved text for the same bookmark, and writing back a
     * stale copy is exactly how an in-progress edit gets silently reverted.
     */
    suspend fun updateBrowserMode(
        id: Long,
        mode: BrowserMode,
        lastSuccessful: BrowserMode?,
        now: Long = System.currentTimeMillis(),
    ) = dao.updateBrowserMode(id, mode.storageName, lastSuccessful?.storageName, now)

    /** One-shot read of every bookmark; used to snapshot the collection before a destructive import. */
    suspend fun allOnce(): List<Bookmark> = dao.getAllOnce().map(BookmarkEntity::toDomain)

    /**
     * Renames a category in place.
     *
     * The caller (the ViewModel) is responsible for trimming and validating the new name — the
     * repository only performs the targeted rewrite so a category keeps every bookmark that belongs
     * to it. No bookmark's URL, title, icon, sort order, pin state or history is touched.
     */
    suspend fun renameCategory(old: String, new: String, now: Long = System.currentTimeMillis()): Int =
        dao.renameCategory(old, new, now)

    companion object {
        const val STEP = 1000L
    }
}
