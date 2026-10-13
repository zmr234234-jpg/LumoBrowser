package com.lumobrowser.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings persistence tests: every value the Settings screen exposes must survive a write/read
 * cycle, and out-of-range input must be clamped instead of stored as-is.
 *
 * Runs against a real (temporary) DataStore file rather than a fake, because the mechanism under
 * test *is* the persistence layer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SettingsRepositoryTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var store: DataStore<Preferences>
    private lateinit var repository: SettingsRepository

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        store = PreferenceDataStoreFactory.create(
            produceFile = { temporaryFolder.newFile("settings_${System.nanoTime()}.preferences_pb") },
        )
        repository = SettingsRepository(context, store)
    }

    @After
    fun tearDown() {
        // The temp folder rule removes the file; nothing else to release for a PreferenceDataStore.
    }

    @Test
    fun defaultsMatchDocumentedBaseline() = runTest {
        val prefs = repository.current()
        assertEquals(UserPreferences.Default, prefs)
        assertEquals(ThemeMode.Dark, prefs.themeMode)
        assertEquals(ThemeStyle.TechClassic, prefs.themeStyle)
        assertEquals(BackgroundStyle.Aurora, prefs.backgroundStyle)
        assertEquals(CardStyle.Glass, prefs.defaultCardStyle)
        // 1.09 changed this default from Grid to List: the two-column grid gives each card ~46 dp of
        // text width on a 411 dp screen, which ellipsised every realistic title. See the
        // `layoutMode` doc comment in Model.kt for the measurements.
        assertEquals(LayoutMode.List, prefs.layoutMode)
        assertEquals(AppLanguage.System, prefs.appLanguage)
        assertEquals(AnimationPreset.Immersive, prefs.animationPreset)
        assertTrue(prefs.soundEnabled)
        assertTrue(prefs.hapticsEnabled)
        assertTrue(prefs.windowBrightnessFollowSystem)
        assertFalse(prefs.dragHintShown)
    }

    @Test
    fun everyEnumSettingRoundTrips() = runTest {
        ThemeMode.entries.forEach { repository.setThemeMode(it) }
        assertEquals(ThemeMode.entries.last(), repository.current().themeMode)

        ThemeStyle.entries.forEach { repository.setThemeStyle(it) }
        assertEquals(ThemeStyle.entries.last(), repository.current().themeStyle)

        BackgroundStyle.entries.forEach { repository.setBackgroundStyle(it) }
        assertEquals(BackgroundStyle.entries.last(), repository.current().backgroundStyle)

        CardStyle.entries.forEach { repository.setCardStyle(it) }
        assertEquals(CardStyle.entries.last(), repository.current().defaultCardStyle)

        ButtonTexture.entries.forEach { repository.setButtonTexture(it) }
        assertEquals(ButtonTexture.entries.last(), repository.current().buttonTexture)

        LayoutMode.entries.forEach { repository.setLayoutMode(it) }
        assertEquals(LayoutMode.entries.last(), repository.current().layoutMode)

        AnimationPreset.entries.forEach { repository.setAnimationPreset(it) }
        assertEquals(AnimationPreset.entries.last(), repository.current().animationPreset)

        AppLanguage.entries.forEach { repository.setLanguage(it) }
        assertEquals(AppLanguage.entries.last(), repository.current().appLanguage)
    }

    @Test
    fun booleanSettingsRoundTrip() = runTest {
        repository.setUseCustomAccent(true)
        repository.setBrightnessFollowSystem(false)
        repository.setReduceMotion(true)
        repository.setSoundEnabled(false)
        repository.setHapticsEnabled(false)
        repository.setPinnedFirst(false)
        repository.setDragHintShown(true)

        val prefs = repository.current()
        assertTrue(prefs.useCustomAccent)
        assertFalse(prefs.windowBrightnessFollowSystem)
        assertTrue(prefs.reduceMotion)
        assertFalse(prefs.soundEnabled)
        assertFalse(prefs.hapticsEnabled)
        assertFalse(prefs.pinnedFirst)
        assertTrue(prefs.dragHintShown)
    }

    /**
     * The switch reads its own current value inside the edit, so two taps in a row are two flips.
     *
     * 1.05's caller computed `!preferences.browserCrossSiteExternal` from the composition it could
     * see. A second tap landing before the first write came back read the same stale `false` and
     * wrote `false` again — so the switch looked dead to anyone who tapped it twice, which is what
     * people do with a switch that appears not to have worked.
     */
    @Test
    fun togglingTheCrossSiteSwitchFlipsItEveryTime() = runTest {
        assertFalse(repository.current().browserCrossSiteExternal)

        assertTrue(repository.toggleBrowserCrossSiteExternal())
        assertTrue(repository.current().browserCrossSiteExternal)

        assertFalse(repository.toggleBrowserCrossSiteExternal())
        assertFalse(repository.current().browserCrossSiteExternal)

        assertTrue(repository.toggleBrowserCrossSiteExternal())
        assertTrue(repository.current().browserCrossSiteExternal)
    }

    /**
     * `set…` and `toggle…` write the same key, so they must not be able to disagree about it.
     */
    @Test
    fun theCrossSiteSwitchSurvivesAMixedSetAndToggle() = runTest {
        repository.setBrowserCrossSiteExternal(true)
        assertFalse(repository.toggleBrowserCrossSiteExternal())
        assertFalse(repository.current().browserCrossSiteExternal)

        repository.setBrowserCrossSiteExternal(false)
        assertTrue(repository.toggleBrowserCrossSiteExternal())
        assertTrue(repository.current().browserCrossSiteExternal)
    }

    @Test
    fun numericSettingsRoundTrip() = runTest {
        repository.setAccentHue(210f)
        repository.setCornerRadius(24)
        repository.setCardAlpha(0.6f)
        repository.setWindowBrightness(0.4f)
        repository.setAnimationStrength(0.5f)
        repository.setSoundVolume(0.3f)

        val prefs = repository.current()
        assertEquals(210f, prefs.accentHue, 0.001f)
        assertEquals(24, prefs.cornerRadiusDp)
        assertEquals(0.6f, prefs.cardAlpha, 0.001f)
        assertEquals(0.4f, prefs.windowBrightness, 0.001f)
        assertEquals(0.5f, prefs.animationStrength, 0.001f)
        assertEquals(0.3f, prefs.soundVolume, 0.001f)
    }

    @Test
    fun outOfRangeNumbersAreClamped() = runTest {
        repository.setAccentHue(999f)
        repository.setCornerRadius(999)
        repository.setCardAlpha(5f)
        repository.setWindowBrightness(-4f)
        repository.setAnimationStrength(9f)
        repository.setSoundVolume(-1f)

        val prefs = repository.current()
        assertEquals(360f, prefs.accentHue, 0.001f)
        assertEquals(32, prefs.cornerRadiusDp)
        assertEquals(1f, prefs.cardAlpha, 0.001f)
        assertEquals(0.1f, prefs.windowBrightness, 0.001f)
        assertEquals(1f, prefs.animationStrength, 0.001f)
        assertEquals(0f, prefs.soundVolume, 0.001f)
    }

    @Test
    fun negativeNumbersAreClampedUpward() = runTest {
        repository.setAccentHue(-90f)
        repository.setCornerRadius(-10)
        repository.setCardAlpha(0.01f)

        val prefs = repository.current()
        assertEquals(0f, prefs.accentHue, 0.001f)
        assertEquals(0, prefs.cornerRadiusDp)
        assertEquals(0.35f, prefs.cardAlpha, 0.001f)
    }

    @Test
    fun resetAllReturnsEverySettingToDefaults() = runTest {
        repository.setThemeMode(ThemeMode.Light)
        repository.setThemeStyle(ThemeStyle.Glass)
        repository.setAccentHue(300f)
        repository.setLayoutMode(LayoutMode.List)
        repository.setLanguage(AppLanguage.English)
        repository.setSoundEnabled(false)
        repository.setDragHintShown(true)

        repository.resetAll()

        assertEquals(UserPreferences.Default, repository.current())
    }

    @Test
    fun aFreshRepositoryOverTheSameFileSeesTheSameValues() = runTest {
        // Simulates an app restart: a new repository instance reading the same DataStore file.
        repository.setThemeMode(ThemeMode.Light)
        repository.setCornerRadius(28)
        repository.setLanguage(AppLanguage.ChineseTraditional)

        val reopened = SettingsRepository(ApplicationProvider.getApplicationContext(), store)
        val prefs = reopened.current()

        assertEquals(ThemeMode.Light, prefs.themeMode)
        assertEquals(28, prefs.cornerRadiusDp)
        assertEquals(AppLanguage.ChineseTraditional, prefs.appLanguage)
    }

    @Test
    fun unreadableEnumValueFallsBackToDefaultInsteadOfCrashing() = runTest {
        // A value written by a future/other build must not break startup.
        store.edit { it[stringPreferencesKey("theme_mode")] = "SomeFutureThemeMode" }
        assertEquals(UserPreferences.Default.themeMode, repository.current().themeMode)
    }

    @Test
    fun blankEnumValueFallsBackToDefault() = runTest {
        store.edit { it[stringPreferencesKey("layout_mode")] = "" }
        assertEquals(UserPreferences.Default.layoutMode, repository.current().layoutMode)
    }
}
