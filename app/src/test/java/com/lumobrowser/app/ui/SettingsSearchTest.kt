package com.lumobrowser.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the settings navigation and search model.
 *
 * No Robolectric and no Compose: [SettingsSearch] and [SettingsRoute] import neither, which is the
 * whole point of keeping them in their own file. The screen supplies localised titles, so the
 * ranking rules can be pinned down here with plain strings.
 */
class SettingsSearchTest {

    private fun entry(
        id: String,
        route: SettingsRoute = SettingsRoute.Appearance,
        title: String,
        keywords: List<String> = emptyList(),
    ) = SettingsSearchEntry(id = id, route = route, title = title, keywords = keywords)

    private val index = listOf(
        entry("theme", SettingsRoute.AppearanceTheme, "Theme mode", listOf("dark", "light", "主题")),
        entry("accent", SettingsRoute.AppearanceTheme, "Accent colour", listOf("colour", "主题色")),
        entry("radius", SettingsRoute.AppearanceCards, "Corner radius", listOf("round", "圆角")),
        entry("zoom", SettingsRoute.Browser, "Text zoom", listOf("size", "缩放")),
        entry("sound", SettingsRoute.Sound, "Sound effects", listOf("audio", "音效")),
    )

    // ---------------------------------------------------------------- query handling

    @Test
    fun `empty query matches nothing`() {
        assertTrue(SettingsSearch.search("", index).isEmpty())
    }

    @Test
    fun `blank query matches nothing`() {
        assertTrue(SettingsSearch.search("   \t ", index).isEmpty())
    }

    @Test
    fun `query is trimmed before matching`() {
        val hits = SettingsSearch.search("  theme mode  ", index)
        assertEquals(1, hits.size)
        assertEquals("theme", hits[0].entry.id)
    }

    @Test
    fun `matching is case insensitive`() {
        val upper = SettingsSearch.search("THEME MODE", index)
        val lower = SettingsSearch.search("theme mode", index)
        assertEquals(1, upper.size)
        assertEquals(lower.map { it.entry.id }, upper.map { it.entry.id })
    }

    // ---------------------------------------------------------------- ranking

    @Test
    fun `exact title scores TITLE_EXACT`() {
        assertEquals(
            SettingsSearch.TITLE_EXACT,
            SettingsSearch.score("theme mode", entry("t", title = "Theme mode")),
        )
    }

    @Test
    fun `title prefix scores TITLE_PREFIX`() {
        assertEquals(
            SettingsSearch.TITLE_PREFIX,
            SettingsSearch.score("theme", entry("t", title = "Theme mode")),
        )
    }

    @Test
    fun `title substring scores TITLE_CONTAINS`() {
        assertEquals(
            SettingsSearch.TITLE_CONTAINS,
            SettingsSearch.score("eme mo", entry("t", title = "Theme mode")),
        )
    }

    @Test
    fun `exact keyword scores KEYWORD_EXACT`() {
        assertEquals(
            SettingsSearch.KEYWORD_EXACT,
            SettingsSearch.score("dark", entry("t", title = "Nothing alike", keywords = listOf("dark"))),
        )
    }

    @Test
    fun `keyword prefix scores KEYWORD_PREFIX`() {
        assertEquals(
            SettingsSearch.KEYWORD_PREFIX,
            SettingsSearch.score("dar", entry("t", title = "Nothing alike", keywords = listOf("dark"))),
        )
    }

    @Test
    fun `keyword substring scores KEYWORD_CONTAINS`() {
        assertEquals(
            SettingsSearch.KEYWORD_CONTAINS,
            SettingsSearch.score("ark", entry("t", title = "Nothing alike", keywords = listOf("dark"))),
        )
    }

    @Test
    fun `a title match outranks an exact keyword match`() {
        val hit = SettingsSearch.score("theme", entry("t", title = "Theme", keywords = listOf("theme")))
        assertEquals(SettingsSearch.TITLE_EXACT, hit)
        assertTrue(SettingsSearch.TITLE_CONTAINS > SettingsSearch.KEYWORD_EXACT)
    }

    @Test
    fun `an unrelated query scores zero`() {
        assertEquals(0, SettingsSearch.score("zzzz", entry("t", title = "Theme mode", keywords = listOf("dark"))))
    }

    // ---------------------------------------------------------------- multi-word fallback

    @Test
    fun `two words spread across title and keywords fall back to ALL_WORDS`() {
        val target = entry("t", title = "Accent colour", keywords = listOf("dark"))
        assertEquals(SettingsSearch.ALL_WORDS, SettingsSearch.score("dark accent", target))
    }

    @Test
    fun `a single word is ranked by title or keyword, never by the fallback`() {
        val target = entry("t", title = "Accent colour", keywords = listOf("dark"))
        // "dark" is an exact keyword, so it is a direct hit and never reaches the lowest-rank
        // fallback; a word that appears nowhere in the entry scores nothing at all.
        assertEquals(SettingsSearch.KEYWORD_EXACT, SettingsSearch.score("dark", target))
        assertEquals(0, SettingsSearch.score("zzz", target))
    }

    @Test
    fun `the multi-word fallback fails when any single word is missing`() {
        val target = entry("t", title = "Accent colour", keywords = listOf("dark"))
        assertEquals(0, SettingsSearch.score("dark missing", target))
    }

    @Test
    fun `ALL_WORDS is the lowest positive rank`() {
        assertTrue(SettingsSearch.ALL_WORDS < SettingsSearch.KEYWORD_CONTAINS)
        assertTrue(SettingsSearch.ALL_WORDS > 0)
    }

    // ---------------------------------------------------------------- ordering and limit

    @Test
    fun `results come back best first`() {
        val hits = SettingsSearch.search("theme mode", index)
        assertEquals("theme", hits[0].entry.id)
        assertTrue(hits[0].score >= hits.last().score)
    }

    @Test
    fun `ties keep the index order`() {
        val tied = listOf(
            entry("first", title = "Alpha", keywords = listOf("x")),
            entry("second", title = "Beta", keywords = listOf("x")),
            entry("third", title = "Gamma", keywords = listOf("x")),
        )
        val hits = SettingsSearch.search("x", tied)
        assertEquals(listOf("first", "second", "third"), hits.map { it.entry.id })
        assertTrue(hits.all { it.score == SettingsSearch.KEYWORD_EXACT })
    }

    @Test
    fun `the default limit caps the result count`() {
        val many = (1..40).map { entry("e$it", title = "Shared name") }
        assertEquals(SettingsSearch.DEFAULT_LIMIT, SettingsSearch.search("shared", many).size)
        assertEquals(12, SettingsSearch.DEFAULT_LIMIT)
    }

    @Test
    fun `an explicit limit truncates`() {
        val many = (1..40).map { entry("e$it", title = "Shared name") }
        assertEquals(3, SettingsSearch.search("shared", many, limit = 3).size)
    }

    @Test
    fun `a non-positive limit means no truncation`() {
        val many = (1..40).map { entry("e$it", title = "Shared name") }
        assertEquals(40, SettingsSearch.search("shared", many, limit = 0).size)
        assertEquals(40, SettingsSearch.search("shared", many, limit = -1).size)
    }

    @Test
    fun `a limit larger than the result set is harmless`() {
        assertEquals(index.size, SettingsSearch.search("o", index, limit = 999).size)
    }

    // ---------------------------------------------------------------- breadcrumbs

    @Test
    fun `home is the only route without a parent`() {
        assertNull(SettingsRoute.Home.parent)
        SettingsRoute.entries.filter { it != SettingsRoute.Home }.forEach { route ->
            assertTrue("$route must have a parent", route.parent != null)
        }
    }

    @Test
    fun `home trail is just home`() {
        assertEquals(listOf(SettingsRoute.Home), SettingsRoute.Home.trail())
    }

    @Test
    fun `a second-level trail is home then the page`() {
        assertEquals(listOf(SettingsRoute.Home, SettingsRoute.Browser), SettingsRoute.Browser.trail())
    }

    @Test
    fun `a third-level trail goes through its hub`() {
        assertEquals(
            listOf(SettingsRoute.Home, SettingsRoute.Appearance, SettingsRoute.AppearanceTheme),
            SettingsRoute.AppearanceTheme.trail(),
        )
        assertEquals(
            listOf(SettingsRoute.Home, SettingsRoute.About, SettingsRoute.AboutLicences),
            SettingsRoute.AboutLicences.trail(),
        )
    }

    @Test
    fun `every trail starts at home and ends at the route itself`() {
        SettingsRoute.entries.forEach { route ->
            val trail = route.trail()
            assertEquals("$route trail must start at Home", SettingsRoute.Home, trail.first())
            assertEquals("$route trail must end at itself", route, trail.last())
        }
    }

    @Test
    fun `consecutive trail entries are parent and child`() {
        SettingsRoute.entries.forEach { route ->
            val trail = route.trail()
            trail.zipWithNext().forEach { (upper, lower) ->
                assertTrue("$lower must follow its parent $upper", lower.parent == upper)
            }
        }
    }

    @Test
    fun `backing out of a search hit walks the trail one step at a time`() {
        // a hit on a third-level page is restored as the whole trail, so backing out of it lands on
        // the hub before it lands on the grid
        var stack = SettingsRoute.AppearanceCards.trail()
        assertEquals(SettingsRoute.AppearanceCards, stack.last())
        stack = stack.dropLast(1)
        assertEquals(SettingsRoute.Appearance, stack.last())
        stack = stack.dropLast(1)
        assertEquals(SettingsRoute.Home, stack.last())
    }

    // ---------------------------------------------------------------- index integrity

    @Test
    fun `every searchable entry points at a real page`() {
        index.forEach { e ->
            assertTrue(e.title.isNotBlank())
            assertTrue(SettingsRoute.entries.contains(e.route))
        }
    }

    @Test
    fun `searching every indexed title finds that entry`() {
        index.forEach { e ->
            val ids = SettingsSearch.search(e.title, index).map { it.entry.id }
            assertTrue("searching '${e.title}' must surface ${e.id}", ids.contains(e.id))
        }
    }
}
