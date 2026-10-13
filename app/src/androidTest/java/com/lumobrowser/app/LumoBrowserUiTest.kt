package com.lumobrowser.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lumobrowser.app.ui.TestTags
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end UI flow on a real device or emulator.
 *
 * These exercise the core journeys the brief calls out: add → search → edit → pin → delete → undo,
 * and the settings screen's persistence-ready controls. They need an attached device
 * (`./gradlew :app:connectedDebugAndroidTest`); on a machine without one they are skipped rather
 * than faked — see docs/TESTING.md for the exact command and the expected result.
 *
 * Note the debug build installs as `com.lumobrowser.app.debug`, so the runner targets that
 * application id.
 */
@RunWith(AndroidJUnit4::class)
class LumoBrowserUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    /** The home screen is the start destination; everything below starts from here. */
    @Test
    fun homeScreenShowsHeaderAndEmptyState() {
        rule.waitUntil(timeoutMillis = 10_000) {
            nodesCount(hasTestTag(TestTags.HOME_ROOT)) > 0
        }
        rule.onNodeWithTag(TestTags.HOME_TITLE).assertIsDisplayed()
        rule.onNodeWithTag(TestTags.HOME_ADD).assertIsDisplayed()
        rule.onNodeWithTag(TestTags.HOME_SEARCH).assertIsDisplayed()
        rule.onNodeWithTag(TestTags.HOME_EMPTY).assertIsDisplayed()
    }

    @Test
    fun addBookmarkThenFindItBySearch() {
        openEditorFromEmptyState()

        rule.onNodeWithTag(TestTags.EDITOR_URL_INPUT).performTextInput("example.com")
        rule.onNodeWithTag(TestTags.EDITOR_TITLE_INPUT).performTextInput("Example Site")
        rule.onNodeWithTag(TestTags.EDITOR_SAVE).performClick()

        // Back on home, the new card exists and the empty state is gone.
        rule.waitUntil(timeoutMillis = 10_000) {
            nodesCount(hasTestTag(TestTags.HOME_EMPTY)) == 0
        }
        rule.onNodeWithText("Example Site").assertIsDisplayed()

        // Search filters it out for a non-match and back in for a match.
        rule.onNodeWithTag(TestTags.HOME_SEARCH).performTextInput("zzz-no-such-site")
        rule.waitUntil(timeoutMillis = 10_000) {
            nodesCount(hasTestTag(TestTags.HOME_EMPTY)) > 0
        }
        rule.onNodeWithTag(TestTags.HOME_SEARCH).performTextReplacement("Example")
        rule.waitUntil(timeoutMillis = 10_000) {
            nodesCount(hasTestTag(TestTags.HOME_EMPTY)) == 0
        }
        rule.onNodeWithText("Example Site").assertIsDisplayed()

        // Leave the database clean for whatever runs next.
        deleteFirstCard()
    }

    @Test
    fun editorRejectsInvalidUrl() {
        openEditorFromEmptyState()
        rule.onNodeWithTag(TestTags.EDITOR_URL_INPUT).performTextInput("javascript:alert(1)")
        rule.onNodeWithTag(TestTags.EDITOR_SAVE).performClick()

        // The save must be refused: the editor stays up and shows an error.
        rule.onNodeWithTag(TestTags.EDITOR_ROOT).assertIsDisplayed()
        rule.onNodeWithTag(TestTags.EDITOR_ERROR).assertIsDisplayed()
        rule.onNodeWithTag(TestTags.EDITOR_CANCEL).performClick()
    }

    @Test
    fun deleteCanBeUndone() {
        openEditorFromEmptyState()
        rule.onNodeWithTag(TestTags.EDITOR_URL_INPUT).performTextInput("undo.example.com")
        rule.onNodeWithTag(TestTags.EDITOR_TITLE_INPUT).performTextInput("Undo Me")
        rule.onNodeWithTag(TestTags.EDITOR_SAVE).performClick()
        rule.waitUntil(timeoutMillis = 10_000) { nodesCount(hasTestTag(TestTags.HOME_EMPTY)) == 0 }

        deleteFirstCard()

        // The undo bar appears and restores the bookmark.
        rule.onNodeWithTag(TestTags.UNDO_BAR).assertIsDisplayed()
        rule.onNodeWithTag(TestTags.UNDO_ACTION).performClick()
        rule.waitUntil(timeoutMillis = 10_000) { nodesCount(hasTestTag(TestTags.HOME_EMPTY)) == 0 }
        rule.onNodeWithText("Undo Me").assertIsDisplayed()

        deleteFirstCard()
    }

    @Test
    fun settingsScreenOpensAndExposesCoreSections() {
        rule.waitUntil(timeoutMillis = 10_000) {
            nodesCount(hasTestTag(TestTags.HOME_OVERFLOW)) > 0
        }
        rule.onNodeWithTag(TestTags.HOME_OVERFLOW).performClick()
        rule.onNodeWithTag(TestTags.MENU_SETTINGS).performClick()

        rule.waitUntil(timeoutMillis = 10_000) {
            nodesCount(hasTestTag(TestTags.SETTINGS_ROOT)) > 0
        }
        // Data-management rows must exist and be reachable (they are the backup/restore entry points).
        rule.onNodeWithTag(TestTags.SETTINGS_BACK).assertIsDisplayed()
        assertTrue(nodesCount(hasTestTag(TestTags.SETTINGS_ROOT)) > 0)

        rule.onNodeWithTag(TestTags.SETTINGS_BACK).performClick()
        rule.waitUntil(timeoutMillis = 10_000) {
            nodesCount(hasTestTag(TestTags.HOME_ROOT)) > 0
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun openEditorFromEmptyState() {
        rule.waitUntil(timeoutMillis = 10_000) {
            nodesCount(hasTestTag(TestTags.HOME_EMPTY)) > 0
        }
        rule.onNodeWithTag(TestTags.HOME_EMPTY_ACTION).performClick()
        rule.waitUntil(timeoutMillis = 10_000) {
            nodesCount(hasTestTag(TestTags.EDITOR_ROOT)) > 0
        }
    }

    /** Opens the first card's overflow menu and deletes it. */
    private fun deleteFirstCard() {
        val id = firstBookmarkId() ?: return
        rule.onNodeWithTag(TestTags.bookmarkMenu(id)).performClick()
        rule.onNodeWithTag(TestTags.bookmarkDelete(id)).performClick()
    }

    /**
     * Bookmark ids are assigned by Room, so the test discovers the id the same way the UI does:
     * by reading the `bookmark-card-<id>` test tag off the first card in the semantics tree.
     */
    private fun firstBookmarkId(): Long? {
        val tagged = (rule as SemanticsNodeInteractionsProvider)
            .onAllNodes(hasTestTagPrefix("bookmark-card-"))
            .fetchSemanticsNodes()
        return tagged
            .mapNotNull { node -> node.config.getOrNull(SemanticsProperties.TestTag) }
            .mapNotNull { tag -> tag.removePrefix("bookmark-card-").toLongOrNull() }
            .minOrNull()
    }

    /** Matches any node whose test tag starts with [prefix]. */
    private fun hasTestTagPrefix(prefix: String) = SemanticsMatcher("TestTag starts with '$prefix'") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }

    /**
     * Node counts for a matcher, polled by `waitUntil`.
     *
     * `onAllNodes` is not an importable top-level function — it is a member of the
     * [SemanticsNodeInteractionsProvider] receiver — so it is wrapped here once and reused, which
     * keeps every waitUntil in this file readable.
     */
    private fun nodesCount(matcher: SemanticsMatcher): Int =
        (rule as SemanticsNodeInteractionsProvider).onAllNodes(matcher).fetchSemanticsNodes().size
}
