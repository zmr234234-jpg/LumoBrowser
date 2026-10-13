package com.lumobrowser.app.data

import com.lumobrowser.app.util.JsonParseException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Backup round-trip and robustness tests. The important properties are:
 *  - a normal export/import keeps every field that matters (title, url, description, category, pin);
 *  - one bad entry does not destroy the whole file — it is counted as skipped;
 *  - a backup from a *newer* schema is refused rather than half-understood.
 */
class BackupManagerTest {

    private fun bookmark(
        id: Long = 1L,
        title: String = "Example",
        url: String = "https://example.com/",
        description: String = "desc",
        category: String = "Work",
        pinned: Boolean = false,
    ) = Bookmark(
        id = id,
        title = title,
        url = url,
        description = description,
        category = category,
        isPinned = pinned,
        sortOrder = id * 1000L,
        createdAt = 1_700_000_000_000L,
        updatedAt = 1_700_000_000_000L,
    )

    @Test
    fun roundTripPreservesAllFields() {
        val original = listOf(
            bookmark(id = 1L, pinned = true),
            bookmark(id = 2L, title = "中文站点", url = "https://例子.测试/page", description = "带 \"引号\" 和 \n换行"),
        )
        val json = BackupManager.export(original, 1_700_000_000_000L)
        val result = BackupManager.parse(json)

        assertEquals(2, result.imported)
        assertEquals(0, result.skipped)
        assertEquals(original.size, result.bookmarks.size)

        val first = result.bookmarks[0]
        assertEquals("Example", first.title)
        assertEquals("https://example.com/", first.url)
        assertEquals("Work", first.category)
        assertTrue(first.isPinned)

        val second = result.bookmarks[1]
        assertEquals("中文站点", second.title)
        // Import re-normalises every URL; an internationalised host comes back in punycode.
        assertEquals("https://xn--fsqu00a.xn--0zwm56d/page", second.url)
        assertEquals("带 \"引号\" 和 \n换行", second.description)
    }

    @Test
    fun roundTripIsStableOnAlreadyNormalisedUrls() {
        // Second-generation backups must not keep drifting: normalising twice changes nothing.
        val once = BackupManager.parse(BackupManager.export(listOf(bookmark(id = 2L, url = "https://例子.测试/page")), 0L))
        val twice = BackupManager.parse(BackupManager.export(once.bookmarks, 0L))
        assertEquals(once.bookmarks.single().url, twice.bookmarks.single().url)
        assertEquals(0, twice.skipped)
    }

    @Test
    fun exportDeclaresSchemaAndVersion() {
        val json = BackupManager.export(emptyList(), 0L)
        assertTrue(json.contains("\"lumobrowser.backup\""))
        assertTrue(json.contains("\"version\":${BackupManager.SCHEMA_VERSION}"))
    }

    @Test
    fun exportedBookmarksDoNotLeakInternalIds() {
        // Ids and timestamps are device-local; a backup should be portable, not a database dump.
        val json = BackupManager.export(listOf(bookmark(id = 987L)), 0L)
        assertTrue(!json.contains("987"))
    }

    @Test
    fun rejectsForeignSchema() {
        val foreign = """{"schema":"something.else","version":1,"bookmarks":[]}"""
        assertThrows(JsonParseException::class.java) { BackupManager.parse(foreign) }
    }

    @Test
    fun rejectsNewerSchemaVersion() {
        val future = """{"schema":"lumobrowser.backup","version":${BackupManager.SCHEMA_VERSION + 1},"bookmarks":[]}"""
        assertThrows(JsonParseException::class.java) { BackupManager.parse(future) }
    }

    @Test
    fun skipsInvalidEntriesInsteadOfFailingWholeFile() {
        val mixed = """
            {
              "schema": "lumobrowser.backup",
              "version": 1,
              "bookmarks": [
                {"title": "Good", "url": "https://good.example/"},
                {"title": "Script", "url": "javascript:alert(1)"},
                {"title": "NoScheme", "url": "not a url"},
                {"title": "File", "url": "file:///etc/passwd"}
              ]
            }
        """.trimIndent()

        val result = BackupManager.parse(mixed)
        assertEquals(1, result.imported)
        assertEquals(3, result.skipped)
        assertEquals("https://good.example/", result.bookmarks.single().url)
    }

    @Test
    fun fillsMissingTitleFromUrl() {
        val json = """
            {"schema":"lumobrowser.backup","version":1,"bookmarks":[
              {"url":"https://news.example.com/"}
            ]}
        """.trimIndent()
        val result = BackupManager.parse(json)
        assertEquals("News", result.bookmarks.single().title)
    }

    @Test
    fun normalisesUrlsOnImport() {
        val json = """
            {"schema":"lumobrowser.backup","version":1,"bookmarks":[
              {"title":"Bare","url":"example.com"}
            ]}
        """.trimIndent()
        val result = BackupManager.parse(json)
        assertEquals("https://example.com", result.bookmarks.single().url)
    }

    @Test
    fun importsEmptyBookmarkList() {
        val json = """{"schema":"lumobrowser.backup","version":1,"bookmarks":[]}"""
        val result = BackupManager.parse(json)
        assertEquals(0, result.imported)
        assertEquals(0, result.skipped)
    }

    @Test
    fun preservesPinFlagOnImport() {
        val json = """
            {"schema":"lumobrowser.backup","version":1,"bookmarks":[
              {"title":"Pinned","url":"https://example.com/","pinned":true}
            ]}
        """.trimIndent()
        assertTrue(BackupManager.parse(json).bookmarks.single().isPinned)
    }

    @Test
    fun suggestedFileNameMatchesPattern() {
        val name = BackupManager.suggestedFileName(1_700_000_000_000L)
        assertTrue("unexpected name: $name", name.startsWith("lumobrowser-backup-"))
        assertTrue(name.endsWith(".json"))
        // "lumobrowser-backup-" (19) + "yyyyMMdd-HHmm" (13) + ".json" (5) == 37
        assertEquals(37, name.length)
        assertTrue("unexpected name: $name", Regex("""^lumobrowser-backup-\d{8}-\d{4}\.json$""").matches(name))
    }

    @Test
    fun mimeTypeIsJson() {
        assertEquals("application/json", BackupManager.MIME_TYPE)
    }

    @Test
    fun parseResultIsNotSharedBetweenCalls() {
        // A previous implementation kept the last parsed list in a mutable field; make sure two
        // consecutive parses do not contaminate each other.
        val first = BackupManager.parse("""{"schema":"lumobrowser.backup","version":1,"bookmarks":[{"url":"https://a.example/"}]}""")
        val second = BackupManager.parse("""{"schema":"lumobrowser.backup","version":1,"bookmarks":[{"url":"https://b.example/"},{"url":"https://c.example/"}]}""")
        assertEquals(1, first.imported)
        assertEquals(2, second.imported)
        assertNotNull(first.bookmarks.single().url)
        assertEquals("https://a.example/", first.bookmarks.single().url)
    }
}
