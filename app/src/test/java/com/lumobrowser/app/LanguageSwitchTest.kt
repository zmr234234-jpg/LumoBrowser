package com.lumobrowser.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Looper
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lumobrowser.app.data.AppLanguage
import com.lumobrowser.app.data.UserPreferences
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Regression tests for the language switch.
 *
 * Switching language used to freeze the app. The cause was an unbounded Activity recreate loop, and
 * it was structural rather than intermittent:
 *
 *  - `MainActivity` collected the preferences with `initialValue = UserPreferences.Default`, whose
 *    language is [AppLanguage.System].
 *  - The recreate decision compared the stored language against a value remembered inside the
 *    composition, which is always seeded from that placeholder, so it always started at `System`.
 *  - With any non-system language stored, every new Activity instance therefore decided it was
 *    out of date and recreated itself before the real preference arrived -- forever.
 *
 * [LanguageSwitchDecisionTest] pins the decision rule itself, including the convergence property
 * that makes the loop impossible. [LanguageSwitchLaunchTest] launches a real Activity and proves
 * the rule holds end to end: a matching language must not recreate the Activity, and the chosen
 * locale must actually reach the running Activity's resources.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
class LanguageSwitchDecisionTest {

    private fun prefsFor(language: AppLanguage) =
        UserPreferences.Default.copy(appLanguage = language)

    @Test
    fun placeholderInitialValue_neverTriggersRecreation() {
        // The exact defect: with the preference not loaded yet, the old code seeded `System` and
        // then saw a different stored value, so it recreated on every launch.
        assertFalse(
            "An unloaded preference must not trigger a recreation",
            shouldRecreateForLanguage(AppLanguage.System, null),
        )
        assertFalse(
            "An unloaded preference must not trigger a recreation",
            shouldRecreateForLanguage(AppLanguage.English, null),
        )
        assertFalse(
            "An unloaded preference must not trigger a recreation",
            shouldRecreateForLanguage(null, null),
        )
    }

    @Test
    fun matchingLanguage_doesNotRecreate() {
        assertFalse(shouldRecreateForLanguage(AppLanguage.English, prefsFor(AppLanguage.English)))
        assertFalse(shouldRecreateForLanguage(AppLanguage.System, prefsFor(AppLanguage.System)))
        assertFalse(
            shouldRecreateForLanguage(AppLanguage.ChineseTraditional, prefsFor(AppLanguage.ChineseTraditional)),
        )
    }

    @Test
    fun realChange_recreatesExactlyOnce() {
        assertTrue(
            "Switching away from a non-system language must recreate",
            shouldRecreateForLanguage(AppLanguage.English, prefsFor(AppLanguage.ChineseSimplified)),
        )
        assertTrue(
            "Switching back to follow-system must recreate",
            shouldRecreateForLanguage(AppLanguage.English, prefsFor(AppLanguage.System)),
        )
    }

    @Test
    fun recreationConverges_forEveryStoredLanguage() {
        // Walks the state machine the way the Activity lifecycle does: a fresh instance starts from
        // whatever `attachBaseContext` applied, then the loaded preference arrives. After at most one
        // recreation the two sides must agree, for every possible stored value.
        for (stored in AppLanguage.entries) {
            var applied: AppLanguage? = null // cold start with no mirror written yet
            var recreations = 0
            repeat(8) {
                if (shouldRecreateForLanguage(applied, prefsFor(stored))) {
                    recreations++
                    applied = stored // the recreation re-applies from the mirror
                }
            }
            assertEquals(
                "Language $stored did not converge after recreation",
                1,
                recreations,
            )
        }
    }

    @Test
    fun alreadyAppliedLanguage_isStableAcrossManyFrames() {
        // The loop only stopped being infinite if a converged instance stays converged. Re-evaluating
        // the decision repeatedly must not start recreating again.
        val applied = AppLanguage.ChineseSimplified
        repeat(50) {
            assertFalse(shouldRecreateForLanguage(applied, prefsFor(AppLanguage.ChineseSimplified)))
        }
    }

    @Test
    fun mirrorRoundTripsEveryLanguageIncludingFollowSystem() {
        val context = ApplicationProvider.getApplicationContext<LumoApplication>()
        for (language in AppLanguage.entries) {
            LanguageMirror.write(context, language)
            assertEquals(
                "LanguageMirror did not round-trip $language",
                language,
                LanguageMirror.readLanguage(context),
            )
        }
    }
}

/**
 * End-to-end half of the language regression: a real [MainActivity] with a language that is already
 * applied must come up in that locale and then stay put.
 *
 * The Activity is launched through [ActivityScenario] rather than a Compose test rule because the
 * language has to be arranged *before* the Activity exists -- a rule launches it before `@Before`
 * runs, which would make the scenario unreachable.
 *
 * Launching that way has one hard prerequisite: the ambient backdrop must be off before the launch,
 * or `ActivityScenario.launch` never returns. See [arrangeAmbientMotionOff].
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
class LanguageSwitchLaunchTest {

    private val app: LumoApplication
        get() = ApplicationProvider.getApplicationContext()

    /**
     * Counts how many Activities the framework actually creates.
     *
     * A recreate loop cannot be detected by comparing Activity *instances* returned from
     * `ActivityScenario.onActivity`, because that hands back whichever instance it holds; a loop
     * would keep the comparison passing. Counting creations is the observation a loop cannot hide
     * from. Only the first instance is retained -- keeping every one of them would let a loop
     * exhaust the test heap before any assertion could report what happened.
     */
    private class CreationCounter : Application.ActivityLifecycleCallbacks {
        var count = 0
            private set
        var first: Activity? = null
            private set

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            count++
            if (first == null) first = activity
        }
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    private lateinit var counter: CreationCounter

    @Before
    fun registerCreationCounter() {
        counter = CreationCounter()
        app.registerActivityLifecycleCallbacks(counter)
    }

    @After
    fun unregisterCreationCounter() {
        runCatching { app.unregisterActivityLifecycleCallbacks(counter) }
    }

    /**
     * Advances the main looper in bounded steps of virtual time, stopping as soon as a second
     * Activity appears. Both bounds matter:
     *
     *  - `ShadowLooper.idle()` cannot be used at all, because it runs until the queue is empty and
     *    the home screen owns an infinite background animation that reschedules a frame every time
     *    one runs. The queue never empties and the pump spins.
     *  - The early exit keeps a recreate loop from allocating a fresh Compose composition per turn
     *    until the JVM dies of `OutOfMemoryError`, which would replace a readable failure with a
     *    crashed test process.
     *
     * This bounds only the pumping done here. `ActivityScenario.launch` performs an idle of its own
     * before it returns, and nothing in this file can bound that one -- which is why the ambient
     * backdrop is switched off before launching instead (see [arrangeAmbientMotionOff]).
     */
    private fun pumpMainThread(rounds: Int = 24) {
        val looper = Shadows.shadowOf(Looper.getMainLooper())
        repeat(rounds) {
            if (counter.count > 1) return
            // Real wall-clock time, so DataStore's real background threads can make progress.
            Thread.sleep(25)
            looper.idleFor(Duration.ofMillis(25))
        }
    }

    private fun arrangeEnglishAlreadyApplied() {
        runBlocking {
            app.settings.setLanguage(AppLanguage.English)
            LanguageMirror.write(app, AppLanguage.English)
        }
        awaitStored(AppLanguage.English)
    }

    /**
     * Switches the drifting backdrop off *before* the Activity exists.
     *
     * `ActivityScenario.launch` drives the main looper through `ActivityController.visible()`, whose
     * idle returns only when the main queue is empty. An infinite animation never lets that happen:
     * the aurora (`LumoBackground.kt`, `DriftingAurora`) reschedules a frame every time one runs, so
     * the launch call itself spins and the test reports nothing at all. `pumpMainThread` cannot
     * prevent that, because the unbounded idle is inside the launch.
     *
     * The subject here is the language decision, which is independent of the backdrop, so the
     * ambient layer is switched off the way a user switches it off and the language behaviour is
     * asserted with it off. The animated default is pinned by the pure-function motion tests instead
     * (`MotionAndThemeTest`, `SimplifiedEffectsTest`).
     */
    private fun arrangeAmbientMotionOff() {
        runBlocking { app.settings.setBackgroundGlowEnabled(false) }
        awaitPreferences { !it.backgroundGlowEnabled }
    }

    /**
     * The count above is read from [CreationCounter], which is fed by the framework's own
     * `onActivityCreated`. `assertSame` on an instance handed back by `ActivityScenario.onActivity`
     * would not be enough on its own, because that returns whichever instance the scenario holds, so
     * a recreate loop could keep such a comparison passing.
     *
     * A positive control that forces a recreation with `Activity.recreate()` was tried and removed:
     * under Robolectric a Compose Activity does not survive it, and the test JVM dies with
     * `OutOfMemoryError` instead of reporting anything. What is *not* claimed here, therefore, is
     * that a forced recreation was observed -- only that the baseline launch produced exactly one
     * creation, and that any further creation would have been counted by the same callback.
     */
    @Test
    fun launchWithAlreadyAppliedLanguage_staysInThatLocaleAndDoesNotRecreate() {
        // Arrange the exact state a running app is in after the user picked English: the preference
        // is stored AND the mirror already says English, so `attachBaseContext` applies it and the
        // composition has nothing left to reconcile.
        arrangeEnglishAlreadyApplied()
        // The backdrop has to be off before the launch or `ActivityScenario.launch` never returns.
        arrangeAmbientMotionOff()

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            pumpMainThread()

            assertEquals(
                "The Activity was created more than once, so it is recreating itself -- this is " +
                    "exactly the freeze that switching language used to cause",
                1,
                counter.count,
            )

            scenario.onActivity { current ->
                assertSame(
                    "The Activity was replaced even though nothing about its language was stale",
                    counter.first,
                    current,
                )
                assertEquals(
                    "The stored language never reached the running Activity's resources",
                    "en",
                    current.resources.configuration.locales[0].language,
                )
            }

            // It must also still be a single instance after another stretch of frames.
            pumpMainThread()
            assertEquals("Recreation happened on a later frame", 1, counter.count)
        } finally {
            runCatching { scenario.close() }
        }
    }

    /** Polls DataStore on wall-clock time; never blocks the main thread inside a wait loop. */
    private fun awaitPreferences(predicate: (UserPreferences) -> Boolean) {
        val observed = AtomicReference<UserPreferences?>(null)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            scope.launch { app.settings.preferences.collect { observed.set(it) } }
            val deadline = System.nanoTime() + 10_000_000_000L
            while (System.nanoTime() < deadline) {
                observed.get()?.let { if (predicate(it)) return }
                Thread.sleep(25)
            }
            throw AssertionError(
                "Timed out waiting for the stored settings to settle; saw ${observed.get()}",
            )
        } finally {
            scope.cancel()
        }
    }

    private fun awaitStored(language: AppLanguage) = awaitPreferences { it.appLanguage == language }
}
