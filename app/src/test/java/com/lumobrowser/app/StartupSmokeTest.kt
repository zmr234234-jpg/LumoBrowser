package com.lumobrowser.app

import android.os.Looper
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lumobrowser.app.ui.SettingsRoute
import com.lumobrowser.app.ui.TestTags
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Cold-start smoke tests.
 *
 * These launch the **real** [MainActivity] on the JVM, so the whole production startup path runs
 * for real: `LumoApplication.onCreate`, the DataStore language mirror, `attachBaseContext` locale
 * override, `enableEdgeToEdge`, the window-brightness collector, and the full first composition.
 *
 * That matters because none of the other unit tests touch `setContent` at all — a crash in the
 * startup path is invisible to them, and the project has no device or emulator to catch it either.
 * A device-only failure that reproduces in logic shows up here as an ordinary stack trace.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StartupSmokeTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    /**
     * Keeps each test isolated.
     *
     * Room's query executor is a real JVM thread pool that outlives a Robolectric sandbox, so a
     * database left open by one test leaves a stale connection pointer behind and the *next* test
     * dies with `IllegalStateException: Illegal connection pointer ...` from
     * `ShadowLegacySQLiteConnection` instead of testing anything. Finish the Activity (which clears
     * the ViewModel and cancels its scope), drain the main looper, then close the database.
     */
    @After
    fun tearDown() {
        runCatching { rule.activityRule.scenario.close() }
        runCatching { Shadows.shadowOf(Looper.getMainLooper()).idle() }
        runCatching {
            ApplicationProvider.getApplicationContext<LumoApplication>().database.close()
        }
    }

    /** Dumps the semantics tree so a failure says *what* was on screen, not just "node missing". */
    private fun tree(): String = runCatching { rule.onRoot().printToString(maxDepth = 12) }
        .getOrElse { "<no semantics tree: ${it.message}>" }

    @Test
    fun coldStart_composesHomeScreen() {
        rule.waitForIdle()
        val found = runCatching {
            rule.onNodeWithTag(TestTags.HOME_ROOT).assertExists()
        }
        assertTrue(
            "Home root was not composed on cold start. Tree was:\n${tree()}",
            found.isSuccess,
        )
    }

    @Test
    fun coldStart_showsTitleAddAndOverflowEntries() {
        rule.waitForIdle()
        // The brief requires the bookmark and settings entries to be pinned top-right.
        rule.onNodeWithTag(TestTags.HOME_TITLE).assertExists()
        rule.onNodeWithTag(TestTags.HOME_ADD).assertExists()
        rule.onNodeWithTag(TestTags.HOME_OVERFLOW).assertExists()
        rule.onNodeWithTag(TestTags.HOME_SEARCH).assertExists()
    }

    @Test
    fun coldStart_emptyStateOffersAnAction() {
        rule.waitForIdle()
        rule.onNodeWithTag(TestTags.HOME_EMPTY).assertExists()
        rule.onNodeWithTag(TestTags.HOME_EMPTY_ACTION).assertExists()
    }

    @Test
    fun overflowMenu_reachesSettings() {
        rule.waitForIdle()
        rule.onNodeWithTag(TestTags.HOME_OVERFLOW).performClick()
        rule.waitForIdle()
        runCatching { rule.onNodeWithTag(TestTags.MENU_SETTINGS).performClick() }
            .onFailure { throw AssertionError("Settings entry unreachable from the overflow menu. Tree:\n${tree()}", it) }
        rule.waitForIdle()
        rule.onNodeWithTag(TestTags.SETTINGS_ROOT).assertExists()
    }

    @Test
    fun addEntry_reachesEditor() {
        rule.waitForIdle()
        rule.onNodeWithTag(TestTags.HOME_ADD).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(TestTags.EDITOR_ROOT).assertExists()
        rule.onNodeWithTag(TestTags.EDITOR_URL_INPUT).assertIsDisplayed()
    }

    @Test
    fun settingsScreenComposes() {
        rule.waitForIdle()
        rule.onNodeWithTag(TestTags.HOME_OVERFLOW).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(TestTags.MENU_SETTINGS).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(TestTags.SETTINGS_ROOT).assertExists()

        // The frame is up on the grid; the reset action lives on the bookmarks page.
        rule.onNodeWithTag(TestTags.settingsCategory(SettingsRoute.Bookmarks)).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(TestTags.SETTINGS_RESET).assertExists()
    }

    @Test
    fun localizedStrings_resolveToRealText() {
        // A missing translation renders as an empty label rather than throwing, so read the actual
        // semantics text — that is what proves the string table and the locale override both work.
        rule.waitForIdle()
        val config = rule.onNodeWithTag(TestTags.HOME_TITLE).fetchSemanticsNode().config
        val text = config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()
        assertTrue("Home title node carried no text at all", text.isNotBlank())
    }
}
