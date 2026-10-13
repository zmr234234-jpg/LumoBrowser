package com.lumobrowser.app

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.lumobrowser.app.crash.CrashGuard
import com.lumobrowser.app.crash.SafeMode
import com.lumobrowser.app.data.AppLanguage
import com.lumobrowser.app.data.UserPreferences
import com.lumobrowser.app.feedback.LocalLumoFeedback
import com.lumobrowser.app.feedback.SoundCue
import com.lumobrowser.app.ui.LumoApp
import com.lumobrowser.app.ui.LumoStringsSnapshot
import com.lumobrowser.app.ui.LocalLumoStrings
import com.lumobrowser.app.ui.components.LumoStartup
import com.lumobrowser.app.ui.components.startupTimeline
import com.lumobrowser.app.ui.theme.LumoTheme
import com.lumobrowser.app.ui.theme.buildLumoTheme
import com.lumobrowser.app.util.applyWindowBrightness
import com.lumobrowser.app.util.isSystemReduceMotionEnabled
import com.lumobrowser.app.util.recreateActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {

    /** URL handed over by an external `ACTION_SEND` / `ACTION_VIEW`, cleared once consumed. */
    private var sharedUrl by mutableStateOf<String?>(null)

    /**
     * The language [attachBaseContext] actually applied to *this* activity instance; `null` means
     * the system locale was left in place.
     *
     * Recorded here rather than re-derived from the preference inside the composition, because only
     * this field describes the running Activity. Comparing the preference against a
     * freshly-remembered value instead recreates this activity forever — see
     * [shouldRecreateForLanguage].
     */
    private var appliedLanguage: AppLanguage? = null

    /**
     * Whether this Activity instance is the start of a cold start, decided once in [onCreate].
     *
     * Held rather than recomputed because the moment it describes is `onCreate` itself: a later
     * recomposition must not be able to change the answer, and `savedInstanceState` is only readable
     * during creation.
     */
    private var showStartupAnimation = false

    private val app: LumoApplication get() = application as LumoApplication

    override fun attachBaseContext(newBase: Context) {
        // Language is applied before any view exists, otherwise the first frame would be laid out
        // in the previous locale. The mirror is written by LumoApplication whenever the DataStore
        // value changes (see LanguageMirror) so this synchronous read is always current.
        val language = LanguageMirror.readLanguage(newBase)
        appliedLanguage = language
        val locale = language?.toLocale()
        if (locale == null) {
            super.attachBaseContext(newBase)
            return
        }
        val config = Configuration(newBase.resources.configuration).apply {
            setLocale(locale)
            setLayoutDirection(locale)
        }
        Locale.setDefault(locale)
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before `super.onCreate`, which is where the library's contract puts it: this reads the
        // launch theme (`Theme.LumoBrowser.Starting`, set on <activity> in the manifest), swaps the
        // activity over to `postSplashScreenTheme` (the ordinary `Theme.LumoBrowser`), and on API 31+
        // hands the system splash window its icon and background. Called after `super.onCreate` it
        // would be racing the platform's own starting-window teardown.
        //
        // No `setKeepOnScreenCondition`: the splash must leave as soon as this activity can draw. The
        // overlay in `LumoStartup` is what carries the brand from here, and it is composed
        // underneath, not gated on anything — see StartupAnimation.kt.
        installSplashScreen()

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // A crash recorded on the previous run takes priority over everything below: say what
        // happened and let the user pick a way in, rather than starting the same way that just failed
        // and hoping. This sits before `setContent`, so on this path no Compose tree is ever built —
        // the screen that explains a crash must not depend on the code that may have caused it.
        if (CrashGuard.hasPendingCrash(this)) {
            startActivity(CrashGuard.recoveryIntent(this))
            finish()
            return
        }

        sharedUrl = extractSharedUrl(intent)

        // A cold start is both halves of this: no saved state, and a process that has not shown the
        // animation yet. Either half alone is wrong — `savedInstanceState == null` is also true when
        // the system killed the process in the background and the user came back to a live task,
        // and the process flag alone would replay the animation on every configuration change.
        //
        // Claimed here rather than when the animation finishes so that a recreate mid-flight (the
        // language switch below does exactly that) cannot start it a second time.
        showStartupAnimation = savedInstanceState == null && !LumoApplication.startupAnimationShown
        if (showStartupAnimation) LumoApplication.startupAnimationShown = true

        // Window brightness follows the setting; `null` leaves the system value untouched, which
        // is the "follow system" default.
        lifecycleScope.launch {
            app.settings.preferences.collectLatest { prefs ->
                applyWindowBrightness(
                    window,
                    if (prefs.windowBrightnessFollowSystem) null else prefs.windowBrightness,
                )
            }
        }

        setContent {
            LumoRoot(
                incomingUrl = sharedUrl,
                appliedLanguage = appliedLanguage,
                showStartupAnimation = showStartupAnimation,
                safeMode = app.safeMode,
                onIncomingUrlConsumed = { sharedUrl = null },
                onDarkThemeResolved = { isDark -> applySystemBarAppearance(window, isDark) },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sharedUrl = extractSharedUrl(intent)
    }

    private fun extractSharedUrl(intent: Intent?): String? = when (intent?.action) {
        Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
        Intent.ACTION_VIEW -> intent.dataString
        else -> null
    }
}

@Composable
private fun LumoRoot(
    incomingUrl: String?,
    appliedLanguage: AppLanguage?,
    showStartupAnimation: Boolean,
    safeMode: Boolean,
    onIncomingUrlConsumed: () -> Unit,
    onDarkThemeResolved: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val app = remember(context) { context.applicationContext as LumoApplication }
    // Collected with a `null` initial value on purpose: the language decision below must be able to
    // tell "the stored preference has not arrived yet" apart from "it arrived and matches". The
    // placeholder is only used to render the first frame.
    val loaded by app.settings.preferences.collectAsStateWithLifecycle(
        initialValue = null,
    )
    val prefs = (loaded ?: UserPreferences.Default).let { stored ->
        // Safe mode is a runtime override, never written back: it lasts this one launch, and every
        // setting the user chose has to be exactly as they left it when it ends. Nothing downstream
        // needs to know it is on — zero animation strength is the same switch the settings slider
        // pulls to its minimum, and that alone skips the startup overlay (`startupTimeline` answers
        // null) and stops the drifting background from being composed at all.
        if (safeMode) SafeMode.apply(stored) else stored
    }
    val systemDark = isSystemInDarkTheme()

    // Read once per entry into the composition: the platform only changes these scales when the
    // user leaves for developer options, and re-reading would touch Settings.Global on every
    // recomposition.
    val reduceMotion = remember { isSystemReduceMotionEnabled(app) }

    val themeState = remember(prefs, systemDark, reduceMotion) {
        buildLumoTheme(prefs, systemDark, reduceMotion)
    }

    // System bar icons follow the *resolved* app theme, not the device theme.
    //
    // `enableEdgeToEdge()` chooses its icon colour from `isSystemInDarkTheme()`, which answers a
    // question about the device rather than about this app. The two can disagree: pick the light
    // theme on a dark device (or the reverse) and the icons are drawn in the background's own
    // colour, so the status bar looks empty. A SideEffect rather than a LaunchedEffect because this
    // is a window property that must match the frame being drawn, and re-applying an unchanged
    // boolean is free.
    SideEffect { onDarkThemeResolved(themeState.isDark) }

    // Recreate when the language actually changes so the new locale takes effect immediately
    // instead of after a manual restart. The mirror keeps `attachBaseContext` in sync.
    LaunchedEffect(loaded?.appLanguage) {
        val target = loaded ?: return@LaunchedEffect
        if (shouldRecreateForLanguage(appliedLanguage, target)) {
            // Write the mirror before recreating: `attachBaseContext` runs during the next creation
            // and is not a coroutine scope, so the value it reads has to be there already.
            LanguageMirror.write(context, target.appLanguage)
            context.recreateActivity()
        }
    }

    LumoTheme(themeState) {
        // The snapshot is built from this Activity's Context, whose Configuration already carries
        // the chosen language (applied in attachBaseContext), so every string resolves correctly.
        val strings = remember(context) { LumoStringsSnapshot(context) }
        // Provided once, above every screen, so the shared components always have a cue to fire and
        // no button can ship silent by accident. The instance is process-scoped and stable.
        CompositionLocalProvider(
            LocalLumoStrings provides strings,
            LocalLumoFeedback provides app.uiFeedback,
        ) {
            // The whole startup decision is made here, from three inputs that never change during
            // this Activity's life: whether this is a cold start, the motion preset, and the
            // strength the user set. `startupTimeline` answers `null` for reduced motion, which is
            // the brief's "skip it", not "shorten it".
            val startupTimeline = remember(showStartupAnimation, themeState.motion) {
                if (showStartupAnimation) startupTimeline(themeState.motion) else null
            }
            val playing = remember(startupTimeline) { mutableStateOf(startupTimeline != null) }

            // Captured at composition time because `onFinished` runs from a coroutine, where a
            // CompositionLocal cannot be read. This is the one cue nobody asked for by touching
            // anything, which is why it ships off by default; the switch that re-enables it is
            // checked in the sound layer, so it cannot be played by accident from here.
            val startupFeedback = LocalLumoFeedback.current

            LumoStartup(
                timeline = startupTimeline.takeIf { playing.value },
                // Only reaches here when the animation actually ran to its end.
                onFinished = {
                    playing.value = false
                    startupFeedback.cue(SoundCue.LaunchEnd)
                },
            ) {
                // `Surface` stays the background for every screen and keeps the theme's own colour
                // rather than the window's, so the handoff uncovers the real interface instead of a
                // second backdrop that happens to match it.
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    LumoApp(
                        preferences = prefs,
                        incomingUrl = incomingUrl,
                        onIncomingUrlConsumed = onIncomingUrlConsumed,
                    )
                }
            }
        }
    }

    // The shared URL is a one-shot hand-off: consume it so a recomposition cannot reopen it.
    LaunchedEffect(incomingUrl) {
        if (incomingUrl != null) onIncomingUrlConsumed()
    }
}

/**
 * Points the system bar icons at whichever way the *app* resolved its theme.
 *
 * The window is an Activity-level object while the theme is a composition-level value, so this is
 * the one crossing point between them. Only icon contrast is touched: both bars stay transparent
 * and edge-to-edge exactly as `enableEdgeToEdge()` left them, and nothing here reads or writes any
 * system-wide setting.
 */
internal fun applySystemBarAppearance(window: Window, isDark: Boolean) {
    val controller = WindowInsetsControllerCompat(window, window.decorView)
    // Light icons on a dark background, dark icons on a light one.
    controller.isAppearanceLightStatusBars = !isDark
    controller.isAppearanceLightNavigationBars = !isDark
}

/**
 * Whether this activity has to be recreated to pick up the stored language.
 *
 * [loaded] is `null` until the preference flow delivers its first value; that case must **not**
 * recreate. The initial value of the flow used to be [UserPreferences.Default], whose language is
 * [AppLanguage.System] — so on every single activity instance the comparison first saw "System",
 * and then saw the real stored language and recreated. With any non-system language stored that
 * never converged: each new activity recreated itself before the real value arrived, an unbounded
 * Activity restart loop that presented as the app freezing the moment the language was switched.
 *
 * The condition is stable by construction: [applied] is fixed for the lifetime of an activity
 * instance, so once the recreation has happened the two sides agree and no further restart occurs.
 */
internal fun shouldRecreateForLanguage(applied: AppLanguage?, loaded: UserPreferences?): Boolean {
    val target = loaded?.appLanguage ?: return false
    return applied != target
}

/**
 * Tiny synchronous mirror of the language preference.
 *
 * DataStore is asynchronous by design but `attachBaseContext` is not a coroutine scope, so the
 * authoritative value is mirrored into SharedPreferences whenever it changes and read back there.
 * The mirror is written by [LumoApplication] on every change, and once more immediately before a
 * language-driven recreation. It stores the enum name, not a [Locale], so "follow system" survives
 * the round trip instead of being indistinguishable from "never written".
 */
internal object LanguageMirror {
    private const val FILE = "lumo_locale_mirror"
    private const val KEY = "language"

    fun write(context: Context, language: AppLanguage) {
        runCatching {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY, language.name)
                .apply()
        }
    }

    fun readLanguage(context: Context): AppLanguage? = runCatching {
        val name = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return null
        runCatching { AppLanguage.valueOf(name) }.getOrNull()
    }.getOrNull()
}
