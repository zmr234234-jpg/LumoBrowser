package com.lumobrowser.app

import android.os.Looper
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lumobrowser.app.data.Bookmark
import com.lumobrowser.app.data.ThemeMode
import com.lumobrowser.app.data.UserPreferences
import com.lumobrowser.app.ui.SettingsRoute
import com.lumobrowser.app.ui.TestTags
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

/**
 * Exercises the flows a user actually performs, on the JVM.
 *
 * The startup smoke test proves the first frame renders. This class goes further and drives the
 * parts that only run once someone taps something — saving a bookmark through Room, tapping the
 * card to open it in the in-app WebView, and changing appearance settings. Every one of those is a
 * code path that no other test in the project reaches, and the project has no device to fall back
 * on.
 *
 * Two rules keep these tests honest:
 *  - Database and DataStore state is observed through background collectors, never `runBlocking`
 *    inside a `waitUntil` condition. Blocking the main thread there starves the very coroutine
 *    (a `viewModelScope` write) the test is waiting for.
 *  - State is cleared before each test, because Room's and DataStore's files outlive a Robolectric
 *    test method inside the same JVM.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AppFlowSmokeTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val bookmarksRef = AtomicReference<List<Bookmark>?>(null)
    private val prefsRef = AtomicReference<UserPreferences?>(null)

    private val app: LumoApplication
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun startClean() {
        runBlocking {
            app.database.bookmarkDao().deleteAll()
            app.settings.resetAll()
        }
        ioScope.launch { app.bookmarks.bookmarks.collect { bookmarksRef.set(it) } }
        ioScope.launch { app.settings.preferences.collect { prefsRef.set(it) } }
        rule.waitUntil(timeoutMillis = 20_000) {
            bookmarksRef.get() != null && prefsRef.get() != null
        }
        rule.waitForIdle()
    }

    @After
    fun tearDown() {
        runCatching { ioScope.cancel() }
        runCatching { rule.activityRule.scenario.close() }
        runCatching { Shadows.shadowOf(Looper.getMainLooper()).idle() }
        // Deliberately NOT closing the database or dropping `LumoDatabase.instance`.
        //
        // With SQLite in NATIVE mode a single Room instance is perfectly reusable across test
        // methods, and closing it here is actively harmful: the Activity created for the *next*
        // method may already hold the old instance, so writes land on a closed pool and the app
        // looks like it silently drops the user's bookmarks. Measured, not assumed — with the close
        // in place 9 of these 11 tests failed; without it, every bookmark flow passes.
    }

    private fun storedBookmarks(): List<Bookmark> = bookmarksRef.get().orEmpty()

    private fun prefs(): UserPreferences = prefsRef.get() ?: UserPreferences.Default

    /**
     * Waits for a UI condition, and on timeout reports *what* was being waited for alongside the
     * state that was actually observed. A bare `ComposeTimeoutException` says only "15 seconds
     * passed", which is not enough to tell a slow machine apart from a genuine wiring bug.
     */
    private fun awaitCondition(what: String, condition: () -> Boolean) {
        try {
            rule.waitUntil(timeoutMillis = 15_000) { condition() }
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError(
                "Timed out waiting for: $what\n" +
                    "  bookmarks observed = ${storedBookmarks().map { it.title }}\n" +
                    "  layoutMode = ${prefs().layoutMode}, themeMode = ${prefs().themeMode}, " +
                    "accentHue = ${prefs().accentHue}",
                timeout,
            )
        }
        rule.waitForIdle()
    }

    /**
     * Waits for DataStore itself to report [predicate], on wall-clock time.
     *
     * Two traps are avoided here, both measured rather than guessed:
     *  - `rule.waitUntil` advances the Compose test clock, so its 15 s budget can expire long before
     *    an off-thread DataStore write lands — which looks exactly like a setting that never saved.
     *  - `first { predicate }` never returns under `runBlocking` on this thread, even when the value
     *    already matches; plain `first()` reads it back reliably. So poll that instead.
     *
     * Polling keeps every `runBlocking` short and lets `waitForIdle` pump the `viewModelScope`
     * write, so it cannot starve the coroutine it is waiting for.
     */
    private fun awaitPersisted(what: String, predicate: (UserPreferences) -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            rule.waitForIdle()
            if (predicate(runBlocking { app.settings.preferences.first() })) return
            Thread.sleep(50)
        }
        throw AssertionError(
            "Timed out waiting for: $what\n" +
                "  DataStore reports = ${runBlocking { app.settings.preferences.first() }}",
        )
    }

    /**
     * Clicks a settings chip by tag, scrolling it into view first.
     *
     * Settings is a long scrolling panel: a chip below the fold has no visible bounds, and a click
     * aimed at it lands nowhere — which reads as "the setting silently failed to save".
     */
    private fun clickOption(tag: String, value: String) {
        rule.onNodeWithTag(TestTags.option(tag, value)).performScrollTo().performClick()
        rule.waitForIdle()
    }

    private fun awaitBookmarks(expected: Int) {
        awaitCondition("bookmark count == $expected") { storedBookmarks().size == expected }
    }

    private fun awaitCard(id: Long) {
        awaitCondition("card for bookmark $id to be laid out") {
            rule.onAllNodesWithTag(TestTags.bookmarkCard(id)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Saves a bookmark through the real editor and returns its generated id. */
    private fun addBookmark(title: String, url: String): Long {
        rule.onNodeWithTag(TestTags.HOME_ADD).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(TestTags.EDITOR_TITLE_INPUT).performTextInput(title)
        rule.onNodeWithTag(TestTags.EDITOR_URL_INPUT).performTextInput(url)
        rule.waitForIdle()
        rule.onNodeWithTag(TestTags.EDITOR_SAVE).performClick()
        rule.waitForIdle()

        awaitCondition("\"$title\" to be persisted after pressing save") {
            storedBookmarks().any { it.title == title }
        }
        // Match on the title: the list is ordered pinned-first then by sort order, so taking
        // `first()` would hand back the *oldest* bookmark and silently make callers that add two
        // bookmarks operate on the same card twice.
        return storedBookmarks().first { it.title == title }.id
    }

    private fun openSettings() {
        rule.waitForIdle()
        rule.onNodeWithTag(TestTags.HOME_OVERFLOW).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(TestTags.MENU_SETTINGS).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(TestTags.SETTINGS_ROOT).assertExists()
    }

    /**
     * Opens Settings and walks down to one of the pages behind the category grid.
     *
     * v1.04 turned Settings into a two-level structure: the screen opens on a grid of seven
     * categories, and a control is not in the semantics tree until its page is on screen. A test
     * that opened Settings and reached straight for a control's tag therefore finds nothing at all
     * -- which is the failure this helper removes rather than papers over.
     *
     * @param trail categories to tap, outermost first. `[Appearance, AppearanceTheme]` lands on the
     *   theme page: the first entry is a card on the grid, every later entry a card on the hub.
     */
    private fun openSettingsPage(vararg trail: SettingsRoute) {
        openSettings()
        trail.forEachIndexed { index, route ->
            val tag = if (index == 0) {
                TestTags.settingsCategory(route)
            } else {
                TestTags.settingsGroup(route)
            }
            rule.onNodeWithTag(tag).performScrollTo().performClick()
            rule.waitForIdle()
        }
    }

    /**
     * Leaves the current settings page, one level at a time, through the header back button.
     *
     * The back button pops the navigation stack while there is one, and only leaves Settings from
     * the grid -- which is the behaviour the screen is specified to have.
     */
    private fun leaveSettingsPage(levels: Int = 1) {
        repeat(levels) {
            rule.onNodeWithTag(TestTags.SETTINGS_BACK).performClick()
            rule.waitForIdle()
        }
    }

    /**
     * Taps a card and waits for the in-app browser to own the screen.
     *
     * The browser is immersive by design (`BrowserScreen.kt:87` starts `chromeVisible = false`), so the
     * toolbar is *not* in the tree on arrival — the small grab handle at the top edge is the affordance
     * that reveals it. Both facts are asserted here: the page area must appear immediately, and the
     * chrome must come back on request. Asserting the toolbar straight after the tap would fail against
     * a correctly working app.
     */
    private fun openBrowserFromCard(id: Long): Unit {
        rule.onNodeWithTag(TestTags.bookmarkCard(id)).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(TestTags.BROWSER_ROOT).assertExists()
        rule.onNodeWithTag(TestTags.BROWSER_GRABBER).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(TestTags.BROWSER_TOOLBAR).assertExists()
    }

    @Test
    fun savingABookmark_persistsItAndShowsACard() {
        rule.waitForIdle()
        val id = addBookmark("Example", "example.com")
        awaitCard(id)
        rule.onNodeWithTag(TestTags.bookmarkCard(id)).assertExists()

        // And it really reached the database, not just the in-memory list.
        val stored = storedBookmarks().single { it.id == id }
        check(stored.url.startsWith("https://")) {
            "Bookmark was not persisted with a normalised https URL: $stored"
        }
    }

    @Test
    fun tappingACard_opensTheInAppBrowser() {
        rule.waitForIdle()
        val id = addBookmark("Example", "example.com")
        awaitCard(id)

        // The requirement is explicit: tapping the card itself must open the page in-app. This path
        // constructs the real WebView, installs the JS bridge and starts the load — the single largest
        // untested surface before this test existed.
        openBrowserFromCard(id)
        rule.onNodeWithTag(TestTags.BROWSER_BACK).assertExists()
        rule.onNodeWithTag(TestTags.BROWSER_RELOAD).assertExists()
        rule.onNodeWithTag(TestTags.BROWSER_CLOSE).assertExists()
    }

    @Test
    fun browserClose_returnsToHome() {
        rule.waitForIdle()
        val id = addBookmark("Example", "example.com")
        awaitCard(id)
        openBrowserFromCard(id)
        rule.onNodeWithTag(TestTags.BROWSER_CLOSE).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(TestTags.HOME_ROOT).assertExists()
    }

    @Test
    fun cardMenu_deleteThenUndo_restoresTheBookmark() {
        rule.waitForIdle()
        val id = addBookmark("Example", "example.com")
        awaitCard(id)

        rule.onNodeWithTag(TestTags.bookmarkMenu(id)).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(TestTags.bookmarkDelete(id)).performClick()
        rule.waitForIdle()
        awaitBookmarks(0)

        // Deletion is destructive, so the app must offer a way back.
        rule.onNodeWithTag(TestTags.UNDO_BAR).assertExists()
        rule.onNodeWithTag(TestTags.UNDO_ACTION).performClick()
        rule.waitForIdle()

        awaitBookmarks(1)
        awaitCard(id)
    }

    @Test
    fun cardMenu_pin_persistsThePinnedFlag() {
        rule.waitForIdle()
        val id = addBookmark("Example", "example.com")
        awaitCard(id)
        check(!storedBookmarks().single().isPinned) { "New bookmark should not start pinned" }

        rule.onNodeWithTag(TestTags.bookmarkMenu(id)).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(TestTags.bookmarkPin(id)).performClick()
        rule.waitForIdle()

        rule.waitUntil(timeoutMillis = 15_000) { storedBookmarks().single().isPinned }
        awaitCard(id)
    }

    @Test
    fun cardMenu_edit_reachesTheEditor() {
        rule.waitForIdle()
        val id = addBookmark("Example", "example.com")
        awaitCard(id)

        rule.onNodeWithTag(TestTags.bookmarkMenu(id)).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(TestTags.bookmarkEdit(id)).performClick()
        rule.waitForIdle()

        rule.onNodeWithTag(TestTags.EDITOR_ROOT).assertExists()
    }

    @Test
    fun search_filtersTheGrid() {
        rule.waitForIdle()
        val first = addBookmark("Alpha", "alpha.example.com")
        awaitCard(first)
        val second = addBookmark("Beta", "beta.example.com")
        awaitCard(second)

        rule.onNodeWithTag(TestTags.HOME_SEARCH).performTextInput("Alpha")
        rule.waitForIdle()
        // The grid debounces the query, and Robolectric's main looper does not advance its own
        // clock while a Compose test waits on it — so the debounce timer would never come due.
        // Move the clock forward explicitly, then let the Room query land on its own executor.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(600))
        rule.waitForIdle()

        awaitCondition("the grid to drop the card that does not match the search") {
            rule.onAllNodesWithTag(TestTags.bookmarkCard(second)).fetchSemanticsNodes().isEmpty()
        }
        rule.onNodeWithTag(TestTags.bookmarkCard(first)).assertExists()
    }

    @Test
    fun browserOpensImmersive_thenGrabberRevealsTheToolbar() {
        rule.waitForIdle()
        val id = addBookmark("Example", "example.com")
        awaitCard(id)

        rule.onNodeWithTag(TestTags.bookmarkCard(id)).performClick()
        rule.waitForIdle()

        // Immersive contract: on arrival the page owns the screen and the toolbar is absent.
        rule.onNodeWithTag(TestTags.BROWSER_ROOT).assertExists()
        rule.onNodeWithTag(TestTags.BROWSER_GRABBER).assertExists()
        check(
            rule.onAllNodesWithTag(TestTags.BROWSER_TOOLBAR).fetchSemanticsNodes().isEmpty(),
        ) { "Toolbar must stay hidden on arrival or it covers the page the brief says it must not cover" }

        rule.onNodeWithTag(TestTags.BROWSER_GRABBER).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(TestTags.BROWSER_TOOLBAR).assertExists()
        rule.onNodeWithTag(TestTags.BROWSER_CLOSE).assertExists()
    }

    @Test
    fun settings_everyAccentSwatchAppliesWithoutCrash() {
        openSettingsPage(SettingsRoute.Appearance, SettingsRoute.AppearanceTheme)

        // Each swatch rebuilds the whole Material scheme *and* the extra colour set, so clicking
        // all of them is the cheapest way to prove the colour pipeline is wired end to end.
        var clicked = 0
        for (name in listOf("Cyan", "Violet", "Azure", "Emerald", "Amber", "Rose")) {
            val outcome = runCatching {
                rule.onNodeWithTag(TestTags.option("accent", name)).performClick()
            }
            if (outcome.isSuccess) {
                clicked++
                rule.waitForIdle()
            }
        }
        check(clicked == 6) { "Only $clicked of 6 accent swatches were reachable in Settings" }
        rule.onNodeWithTag(TestTags.SETTINGS_ROOT).assertExists()
    }

    @Test
    fun settings_choicesPersistToDataStore() {
        openSettingsPage(SettingsRoute.Appearance, SettingsRoute.AppearanceTheme)
        check(prefs().themeMode != ThemeMode.Light) { "Test premise broken: already Light" }
        // 1.09 made List the default layout, so this test now moves the layout the other way. The
        // assertion still has to be about a value that actually changed: clicking the already
        // selected chip would leave DataStore untouched and the test would pass without proving
        // anything.
        check(prefs().layoutMode != com.lumobrowser.app.data.LayoutMode.Grid) {
            "Test premise broken: already Grid"
        }

        clickOption("theme-mode", "Light")

        leaveSettingsPage()
        rule.onNodeWithTag(TestTags.settingsGroup(SettingsRoute.AppearanceLayout))
            .performScrollTo()
            .performClick()
        rule.waitForIdle()
        clickOption("layout-mode", "Grid")

        // The user demanded settings survive a restart; persistence is the real acceptance test,
        // not the fact that a chip visually highlighted.
        awaitPersisted("theme mode Light to reach DataStore") { it.themeMode == ThemeMode.Light }
        awaitPersisted("layout mode Grid to reach DataStore") {
            it.layoutMode == com.lumobrowser.app.data.LayoutMode.Grid
        }
    }

    @Test
    fun settings_surviveActivityRecreation() {
        openSettingsPage(SettingsRoute.Appearance, SettingsRoute.AppearanceTheme)
        clickOption("theme-mode", "Light")
        awaitPersisted("theme mode Light to reach DataStore") { it.themeMode == ThemeMode.Light }

        // Recreating the activity is the closest thing to a restart available here, and it is the
        // step that reads the persisted value back through the repository.
        rule.activityRule.scenario.recreate()
        rule.waitForIdle()

        val afterRestart = runBlocking { app.settings.preferences.first() }
        check(afterRestart.themeMode == ThemeMode.Light) {
            "Theme mode was lost across activity recreation; DataStore reports ${afterRestart.themeMode}"
        }
    }
}
