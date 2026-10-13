package com.lumobrowser.app

import android.app.Application
import android.content.res.Configuration
import com.lumobrowser.app.accessibility.AssistSettings
import com.lumobrowser.app.browser.adblock.AdBlockManager
import com.lumobrowser.app.browser.adblock.AdBlockRuleUpdater
import com.lumobrowser.app.browser.adblock.AdBlockRulesSource
import com.lumobrowser.app.browser.adblock.AdBlockStore
import com.lumobrowser.app.browser.adblock.BundledAdBlockRules
import com.lumobrowser.app.browser.adblock.DownloadedAdBlockRules
import com.lumobrowser.app.browser.adblock.LayeredAdBlockRules
import com.lumobrowser.app.browser.security.NavigationSecurityManager
import com.lumobrowser.app.crash.CrashGuard
import com.lumobrowser.app.data.AppLanguage
import com.lumobrowser.app.data.BookmarkRepository
import com.lumobrowser.app.data.LumoDatabase
import com.lumobrowser.app.data.SettingsRepository
import com.lumobrowser.app.feedback.Haptics
import com.lumobrowser.app.feedback.LumoFeedback
import com.lumobrowser.app.feedback.SoundSynth
import com.lumobrowser.app.feedback.hapticFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Process-wide wiring.
 *
 * A hand-rolled service locator rather than a DI framework: the graph is five objects, and this
 * keeps the build free of annotation processors other than Room's KSP step — faster builds, fewer
 * version constraints to babysit.
 */
class LumoApplication : Application() {

    /** Last language the user picked, mirrored synchronously for `attachBaseContext`. */
    val appLanguage = MutableStateFlow(AppLanguage.System)

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Whether this launch was requested from the crash recovery screen in safe mode.
     *
     * Latched once per process on purpose, because [CrashGuard.consumeSafeMode] clears the stored
     * request: a configuration change or a language switch recreates the Activity, and re-reading
     * the flag would drop the user back into the effects they had just asked to leave behind, in the
     * middle of the session they were trying to rescue.
     */
    val safeMode: Boolean by lazy { CrashGuard.consumeSafeMode(this) }

    val settings: SettingsRepository by lazy { SettingsRepository(this) }

    val database: LumoDatabase by lazy { LumoDatabase.get(this) }

    val bookmarks: BookmarkRepository by lazy { BookmarkRepository(database.bookmarkDao()) }

    val sound: SoundSynth by lazy { SoundSynth(this) }

    val haptics: Haptics by lazy { Haptics(this) }

    /**
     * Ambient button feedback consumed by the shared UI components.
     *
     * Built once and never replaced: the lambda below reads [sound] and [haptics] at call time and
     * those objects hold their own live configuration, so the object identity stays stable for the
     * whole process and providing it never invalidates a reader. Single source of truth — the
     * ViewModel's own feedback helpers delegate here rather than duplicating the cue mapping.
     *
     * One route for every cue, rather than one lambda per kind of moment: `hapticFor` decides
     * which cues the motor participates in, so adding a cue cannot accidentally add a vibration
     * nobody asked for, and the two feedback channels cannot drift apart.
     */
    val uiFeedback: LumoFeedback by lazy {
        LumoFeedback(onCue = { cue ->
            sound.play(cue)
            hapticFor(cue)?.let(haptics::perform)
        })
    }

    /**
     * The process's ad blocker.
     *
     * One instance for the whole app, because a filter list, a set of counters and a switch are
     * properties of the process and not of a page: opening a second tab must not re-read the rules
     * from the APK, and closing a tab must not reset anything.
     *
     * Built here rather than in a WebView so the settings screen can describe the loaded rules and
     * the live counts even when no page is open, and so `enabled` is a single volatile switch that
     * every WebView in the process consults on its next request.
     */
    val adBlocker: AdBlockManager by lazy {
        AdBlockManager(
            scope = appScope,
            source = adBlockRules,
            store = object : AdBlockStore {
                override suspend fun readTotal(): Long = settings.adBlockTotalBlocked()
                override suspend fun addBlocked(delta: Long) = settings.addAdBlockBlocked(delta)
            },
        )
    }

    /** The downloaded rule list, when there is one. Read before the bundled list, never instead of it. */
    val downloadedAdBlockRules: DownloadedAdBlockRules by lazy { DownloadedAdBlockRules(this) }

    /**
     * The navigation rules a page has to get past before the WebView acts on an address.
     *
     * One instance for the process, because the state it keeps is about the reader rather than about
     * a view: the redirect chain spans one navigation, and the pop-up window spans a page. A second
     * manager would be a second opinion about the same session — and the log in the settings screen
     * would show only the half of it that happened to be routed to the view that was on screen.
     */
    val navigationSecurity: NavigationSecurityManager by lazy { NavigationSecurityManager() }

    /**
     * The filter list the blocker actually uses.
     *
     * A downloaded list wins over the bundled one, and a download is a *merge* of the two rather than
     * a replacement (see [AdBlockRuleUpdater]), so this is a preference between two files and not a
     * choice between two behaviours.
     */
    private val adBlockRules: AdBlockRulesSource by lazy {
        LayeredAdBlockRules(BundledAdBlockRules(this), downloadedAdBlockRules)
    }

    /**
     * The one thing in the app allowed to fetch a filter list.
     *
     * Takes [adBlockRules] so the merge can start from the list that ships in the APK, and the same
     * store the layered source reads, so an update is visible to the next [AdBlockManager.reload].
     */
    val adBlockRuleUpdater: AdBlockRuleUpdater by lazy {
        AdBlockRuleUpdater(bundled = BundledAdBlockRules(this), sink = downloadedAdBlockRules)
    }

    override fun onCreate() {
        super.onCreate()
        // First, before anything else this process does: from here on a crash leaves a report behind
        // instead of a silent return to the launcher. Safe mode below is read *after* this, so a
        // crash during startup is still recorded.
        CrashGuard.install(this)
        instance = this
        // Mirror the persisted language so the next `attachBaseContext` can read it synchronously,
        // and re-mirror on every change so switching languages is picked up immediately.
        appScope.launch {
            settings.preferences.collect { prefs ->
                appLanguage.value = prefs.appLanguage
                LanguageMirror.write(this@LumoApplication, prefs.appLanguage)
                // Mirror the accessibility assist switch and its targets into the volatile holder
                // the service reads, so `onAccessibilityEvent` never touches DataStore. This runs
                // on every preference change, which is what makes the app-level switch and the
                // service act on the same value rather than on two copies that can drift.
                AssistSettings.publish(prefs)
                // Same idea for ad blocking, and the same reason it is a mirror rather than a
                // per-request read: `shouldInterceptRequest` runs on a WebView worker thread and
                // must not suspend, so the switch it consults has to be a plain field. This is what
                // makes turning the switch off apply to the very next request instead of the next
                // restart.
                adBlocker.enabled = prefs.adBlockEnabled && !safeMode
                adBlocker.setPausedSites(prefs.adBlockPausedSites)
                adBlocker.ensureLoaded()
            }
        }
    }

    /**
     * Builds the [Configuration] for the selected language.
     *
     * Uses `Locale.setDefault` plus a per-activity `createConfigurationContext` override rather
     * than `AppCompatDelegate.setApplicationLocales`, because per-app locales need API 33+ (or the
     * AndroidX locale backport with its own manifest service) and this app supports API 26.
     */
    fun localizedConfiguration(language: AppLanguage): Configuration {
        val config = Configuration(baseContext.resources.configuration)
        val locale = language.toLocale() ?: return config
        Locale.setDefault(locale)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return config
    }

    companion object {
        @Volatile
        private var instance: LumoApplication? = null

        fun from(context: android.content.Context): LumoApplication =
            context.applicationContext as LumoApplication

        fun peek(): LumoApplication? = instance

        /**
         * Whether this process has already put the cold-start animation on screen.
         *
         * Process-scoped on purpose, and claimed at Activity creation rather than at the animation's
         * end — see [MainActivity.onCreate], which is the only writer. A configuration change and a
         * language-switch recreate both go through `Activity.onCreate`, so latching here is what
         * stops the animation replaying on either of them; a genuinely fresh process resets it,
         * which is what makes it a *cold*-start animation rather than a once-per-install one.
         *
         * Deliberately not persisted to disk. "Cold start" means the process began here, and a
         * stored flag would suppress the animation on the launch the user actually waited for.
         */
        @Volatile
        var startupAnimationShown: Boolean = false
            internal set
    }
}

/** Maps the persisted language choice onto a concrete locale (`null` = follow the system). */
fun AppLanguage.toLocale(): Locale? = when (this) {
    AppLanguage.System -> null
    AppLanguage.English -> Locale.ENGLISH
    AppLanguage.ChineseSimplified -> Locale.SIMPLIFIED_CHINESE
    AppLanguage.ChineseTraditional -> Locale.TRADITIONAL_CHINESE
}
