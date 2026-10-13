package com.lumobrowser.app.ui

/**
 * Navigation and search model for the settings screen.
 *
 * This file deliberately imports nothing from Android and nothing from Compose. Everything here is
 * plain Kotlin, so the ranking rules and the breadcrumb rules can be exercised by an ordinary JVM
 * unit test (`SettingsSearchTest`) rather than by a Robolectric-backed one. The screen supplies the
 * localised titles; the logic that decides *what matches* and *how deep you are* lives here.
 */

/** Every page the settings screen can show. [Home] is the category grid. */
enum class SettingsRoute {
    Home,
    Appearance,
    AppearanceTheme,
    AppearanceBackground,
    AppearanceCards,
    AppearanceLayout,
    Browser,
    Bookmarks,
    Sound,
    Network,
    Performance,
    Privacy,
    About,
    AboutLicences,
    ;

    /** The page one step back from this one. `null` only for [Home]. */
    val parent: SettingsRoute?
        get() = when (this) {
            Home -> null
            AppearanceTheme, AppearanceBackground, AppearanceCards, AppearanceLayout -> Appearance
            AboutLicences -> About
            Privacy -> Network
            else -> Home
        }

    /**
     * Breadcrumb from [Home] down to this route.
     *
     * A search result can jump straight to a third-level page, so the screen restores the whole
     * trail instead of a bare destination: backing out of a search hit has to land on the page the
     * hit belongs to before it lands on the grid.
     */
    fun trail(): List<SettingsRoute> {
        val trail = ArrayList<SettingsRoute>()
        var cursor: SettingsRoute? = this
        while (cursor != null) {
            trail.add(cursor)
            cursor = cursor.parent
        }
        return trail.asReversed()
    }
}

/** One searchable setting: what it is called, and which page it lives on. */
data class SettingsSearchEntry(
    val id: String,
    val route: SettingsRoute,
    val title: String,
    val keywords: List<String> = emptyList(),
)

/** A matched entry, with the score that decided its place in the result list. */
data class SettingsSearchHit(val entry: SettingsSearchEntry, val score: Int)

/**
 * Ranks settings against a query.
 *
 * Matching is intentionally generous — this is a settings screen, not a web search, so a query that
 * matches anything at all should surface something. Ordering is deliberately strict, though: an
 * exact title beats a title prefix beats a substring beats a keyword, and the caller's own ordering
 * breaks ties, so the list never reshuffles between two keystrokes that match equally well.
 */
object SettingsSearch {

    /** How many results the screen shows at once. */
    const val DEFAULT_LIMIT: Int = 12

    const val TITLE_EXACT: Int = 100
    const val TITLE_PREFIX: Int = 80
    const val TITLE_CONTAINS: Int = 60
    const val KEYWORD_EXACT: Int = 50
    const val KEYWORD_PREFIX: Int = 40
    const val KEYWORD_CONTAINS: Int = 30
    const val ALL_WORDS: Int = 20

    /**
     * @param query raw text from the search field; trimmed and case-folded here.
     * @param entries the index, in the order the screen wants ties broken.
     * @param limit maximum number of hits, or a non-positive value for no limit.
     */
    fun search(
        query: String,
        entries: List<SettingsSearchEntry>,
        limit: Int = DEFAULT_LIMIT,
    ): List<SettingsSearchHit> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return emptyList()

        val ranked = ArrayList<Pair<Int, SettingsSearchHit>>(entries.size)
        entries.forEachIndexed { index, entry ->
            val score = score(needle, entry)
            if (score > 0) ranked.add(index to SettingsSearchHit(entry, score))
        }
        ranked.sortWith(
            compareByDescending<Pair<Int, SettingsSearchHit>> { it.second.score }
                .thenBy { it.first },
        )
        val ordered = ranked.map { it.second }
        return if (limit > 0 && ordered.size > limit) ordered.subList(0, limit) else ordered
    }

    /** The score for one entry, or `0` when it does not match at all. */
    fun score(needle: String, entry: SettingsSearchEntry): Int {
        val title = entry.title.lowercase()
        val keywords = entry.keywords.map { it.lowercase() }

        titleScore(needle, title)?.let { return it }
        keywordScore(needle, keywords)?.let { return it }

        // "dark accent", "button radius": people type two half-remembered words rather than one
        // exact label, so fall back to "every word appears somewhere" at the lowest rank.
        val words = needle.split(' ', '\t', '\n').filter { it.isNotEmpty() }
        if (words.size < 2) return 0
        val haystack = keywords + title
        return if (words.all { word -> haystack.any { it.contains(word) } }) ALL_WORDS else 0
    }

    private fun titleScore(needle: String, title: String): Int? = when {
        title == needle -> TITLE_EXACT
        title.startsWith(needle) -> TITLE_PREFIX
        title.contains(needle) -> TITLE_CONTAINS
        else -> null
    }

    private fun keywordScore(needle: String, keywords: List<String>): Int? = when {
        keywords.any { it == needle } -> KEYWORD_EXACT
        keywords.any { it.startsWith(needle) } -> KEYWORD_PREFIX
        keywords.any { it.contains(needle) } -> KEYWORD_CONTAINS
        else -> null
    }
}
