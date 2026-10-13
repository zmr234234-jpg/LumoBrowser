package com.lumobrowser.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Room DAO + repository behaviour, run on the JVM with Robolectric and an in-memory database.
 * This is the largest executable slice of "does persistence actually work" that can run without a
 * device (see docs/TESTING.md for what still needs instrumentation).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BookmarkRepositoryTest {

    private lateinit var database: LumoDatabase
    private lateinit var repository: BookmarkRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            LumoDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = BookmarkRepository(database.bookmarkDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun addThenReadBack() = runTest {
        val id = repository.add(
            title = "Example",
            url = "https://example.com/",
            description = "a site",
            category = "Work",
            pinned = false,
        )
        assertTrue(id > 0L)

        val stored = repository.get(id)
        assertNotNull(stored)
        assertEquals("Example", stored!!.title)
        assertEquals("https://example.com/", stored.url)
        assertEquals("Work", stored.category)
        assertEquals(1, repository.count())
    }

    @Test
    fun newBookmarksGetIncreasingSortOrder() = runTest {
        val first = repository.add("A", "https://a.example/", "", "", false)
        val second = repository.add("B", "https://b.example/", "", "", false)
        val order = repository.bookmarks.first().map { it.id }
        assertEquals(listOf(first, second), order)
        assertTrue(repository.get(second)!!.sortOrder > repository.get(first)!!.sortOrder)
    }

    @Test
    fun pinnedBookmarkSortsBeforeUnpinnedOnes() = runTest {
        repository.add("A", "https://a.example/", "", "", false)
        repository.add("B", "https://b.example/", "", "", false)
        val pinned = repository.add("C", "https://c.example/", "", "", false)
        repository.setPinned(pinned, true)

        assertEquals(pinned, repository.bookmarks.first().first().id)
    }

    @Test
    fun updateKeepsSortOrderAndCreationTime() = runTest {
        val id = repository.add("A", "https://a.example/", "", "", false)
        val before = repository.get(id)!!
        val edited = before.copy(title = "Renamed", url = "https://renamed.example/")

        assertTrue(repository.update(edited))

        val after = repository.get(id)!!
        assertEquals("Renamed", after.title)
        assertEquals("https://renamed.example/", after.url)
        assertEquals(before.sortOrder, after.sortOrder)
        assertEquals(before.createdAt, after.createdAt)
        assertTrue(after.updatedAt >= before.updatedAt)
    }

    @Test
    fun deleteRemovesRow() = runTest {
        val id = repository.add("A", "https://a.example/", "", "", false)
        repository.delete(id)
        assertNull(repository.get(id))
        assertEquals(0, repository.count())
    }

    @Test
    fun restoreUndoPutBackTheSameRowWithPosition() = runTest {
        val first = repository.add("A", "https://a.example/", "", "Cat", false)
        repository.add("B", "https://b.example/", "", "", false)
        val snapshot = repository.get(first)!!

        repository.delete(first)
        assertNull(repository.get(first))

        repository.restore(snapshot)
        val restored = repository.get(first)
        assertNotNull(restored)
        assertEquals(snapshot.sortOrder, restored!!.sortOrder)
        assertEquals(snapshot.createdAt, restored.createdAt)
        assertEquals("Cat", restored.category)
        assertTrue(repository.bookmarks.first().any { it.id == first })
    }

    @Test
    fun applyOrderRewritesSortOrder() = runTest {
        val a = repository.add("A", "https://a.example/", "", "", false)
        val b = repository.add("B", "https://b.example/", "", "", false)
        val c = repository.add("C", "https://c.example/", "", "", false)

        repository.applyOrder(listOf(c, a, b))

        assertEquals(listOf(c, a, b), repository.bookmarks.first().map { it.id })
    }

    @Test
    fun searchMatchesTitleUrlAndDescription() = runTest {
        repository.add("Kotlin Docs", "https://kotlinlang.org/", "language reference", "Dev", false)
        repository.add("News", "https://news.example/", "daily headlines", "Reading", false)

        assertEquals(1, repository.search("kotlin").first().size)
        assertEquals(1, repository.search("headlines").first().size)
        assertEquals(1, repository.search("news.example").first().size)
        assertEquals(0, repository.search("nonexistent-term").first().size)
    }

    @Test
    fun searchIsCaseInsensitive() = runTest {
        repository.add("Kotlin", "https://kotlinlang.org/", "", "", false)
        assertEquals(1, repository.search("KOTLIN").first().size)
        assertEquals(1, repository.search("kotlin").first().size)
    }

    @Test
    fun categoriesAreDistinctAndSorted() = runTest {
        repository.add("A", "https://a.example/", "", "Work", false)
        repository.add("B", "https://b.example/", "", "Reading", false)
        repository.add("C", "https://c.example/", "", "Work", false)
        repository.add("D", "https://d.example/", "", "", false)

        val categories = repository.categories.first()
        assertEquals(listOf("Reading", "Work"), categories)
    }

    @Test
    fun renameCategoryRewritesEveryBookmarkInPlace() = runTest {
        val a = repository.add("A", "https://a.example/", "", "Work", false)
        val b = repository.add("B", "https://b.example/", "", "Reading", false)
        val c = repository.add("C", "https://c.example/", "", "Work", false)
        val before = repository.get(a)!!

        val rewritten = repository.renameCategory("Work", "Projects")

        assertEquals(2, rewritten)
        // Only the category string changes; id, url, title, sort order, pin and history all survive.
        val afterA = repository.get(a)!!
        assertEquals("Projects", afterA.category)
        assertEquals(before.id, afterA.id)
        assertEquals(before.url, afterA.url)
        assertEquals(before.title, afterA.title)
        assertEquals(before.sortOrder, afterA.sortOrder)
        assertEquals(before.isPinned, afterA.isPinned)
        assertEquals(before.createdAt, afterA.createdAt)
        assertEquals("Projects", repository.get(c)!!.category)
        assertEquals("Reading", repository.get(b)!!.category)
        assertEquals(listOf("Projects", "Reading"), repository.categories.first())
    }

    @Test
    fun renameCategoryReturnsZeroForUnknownCategory() = runTest {
        repository.add("A", "https://a.example/", "", "Work", false)
        assertEquals(0, repository.renameCategory("Missing", "Renamed"))
        assertEquals(listOf("Work"), repository.categories.first())
    }

    @Test
    fun replaceAllSwapsTheEntireTable() = runTest {
        repository.add("Old", "https://old.example/", "", "", false)
        val before = repository.allOnce()
        assertEquals(1, before.size)

        repository.replaceAll(
            listOf(
                Bookmark(0L, "New1", "https://new1.example/", "", "Imported", false, 0L, 1L, 1L),
                Bookmark(0L, "New2", "https://new2.example/", "", "Imported", false, 0L, 1L, 1L),
            )
        )

        val after = repository.bookmarks.first()
        assertEquals(2, after.size)
        assertEquals(listOf("New1", "New2"), after.map { it.title })
        assertTrue(after.all { it.id != before.single().id })

        // And the snapshot taken before the import can put the old data back (undo).
        repository.replaceAll(before)
        assertEquals(listOf("Old"), repository.bookmarks.first().map { it.title })
    }

    @Test
    fun replaceAllGivesImportedRowsFreshSequentialOrder() = runTest {
        repository.replaceAll(
            (1..5).map {
                Bookmark(0L, "T$it", "https://t$it.example/", "", "", false, 0L, 0L, 0L)
            }
        )
        val orders = repository.bookmarks.first().map { it.sortOrder }
        assertEquals(orders.sorted(), orders)
        assertEquals(orders.size, orders.distinct().size)
    }

    @Test
    fun deleteAllEmptiesTable() = runTest {
        repository.add("A", "https://a.example/", "", "", false)
        repository.add("B", "https://b.example/", "", "", false)
        database.bookmarkDao().deleteAll()
        assertEquals(0, repository.count())
    }

    @Test
    fun getReturnsNullForUnknownId() = runTest {
        assertNull(repository.get(4242L))
    }

    @Test
    fun emptyDatabaseHasNoCategories() = runTest {
        assertEquals(emptyList<String>(), repository.categories.first())
        assertEquals(0, repository.count())
    }
}
