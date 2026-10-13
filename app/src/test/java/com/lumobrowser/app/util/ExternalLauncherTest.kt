package com.lumobrowser.app.util

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.lumobrowser.app.browser.externalLinkOffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * The handoff decisions, tested where they belong: on the intent this app actually hands over.
 *
 * The 1.05 suite had 505 tests and not one of them called [ExternalLauncher.build]. That is how the
 * "website redirect" switch shipped broken through two releases: [build] started from
 * [externalLinkOffer], which only answers for the schemes the *link policy* calls
 * `ConfirmExternal` — and `http`/`https` are not among them, they are `InApp`. So every web address
 * came back `null`, [ExternalLauncher.launch] turned that into `Unusable`, and the browser told the
 * user there was nothing on the device that could open the link they were looking at.
 *
 * The first test in this file is that bug, written down.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ExternalLauncherTest {

    private val application: Context get() = ApplicationProvider.getApplicationContext()

    /**
     * A context that writes down what the launcher asks the platform to do.
     *
     * [ExternalLauncher] uses exactly three things off its context — `startActivity`, `packageName`
     * and `packageManager` — so recording the first and delegating the rest is enough to assert on
     * the whole contract. It also lets a test decide whether the platform accepts the intent, which
     * is the difference between `Opened` and `NoHandler`.
     */
    private class RecordingContext(
        base: Context,
        private val failWith: Throwable? = null,
    ) : ContextWrapper(base) {

        val started = mutableListOf<Intent>()

        val last: Intent
            get() = started.lastOrNull()
                ?: throw AssertionError("nothing was handed to the platform")

        override fun startActivity(intent: Intent) {
            failWith?.let { throw it }
            started += intent
        }
    }

    /**
     * Registers a browser the package manager can resolve for [url].
     *
     * An activity plus a real intent filter, rather than the deprecated `addResolveInfoForIntent`
     * shortcut: the shortcut answers a query with a map write, so nothing about intent filters is
     * exercised and it would keep passing even if this app asked the platform for something no
     * browser could accept.
     */
    private fun addBrowser(packageName: String, url: String) {
        val component = ComponentName(packageName, "$packageName.Main")
        val shadow = Shadows.shadowOf(application.packageManager)
        shadow.addActivityIfNotPresent(component)
        shadow.addIntentFilterForActivity(
            component,
            IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addCategory(Intent.CATEGORY_BROWSABLE)
                addDataScheme(Uri.parse(url).scheme)
            },
        )
    }

    /**
     * Registers a handler whose filter is missing `CATEGORY_DEFAULT`.
     *
     * That category is not decoration: the platform adds `MATCH_DEFAULT_ONLY` before it starts
     * anything implicit, so a filter without it answers a loose query and can never be launched.
     */
    private fun addUnstartableHandler(packageName: String, url: String) {
        val component = ComponentName(packageName, "$packageName.Main")
        val shadow = Shadows.shadowOf(application.packageManager)
        shadow.addActivityIfNotPresent(component)
        shadow.addIntentFilterForActivity(
            component,
            IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                addDataScheme(Uri.parse(url).scheme)
            },
        )
    }

    // ---------------------------------------------------------------- build()

    /**
     * The 1.06 regression. Before this release `build` answered `null` for every http/https address,
     * so the switch could not hand anything to anything.
     */
    @Test
    fun webAddress_buildsAViewIntent() {
        val url = "https://example.com/"
        val intent = ExternalLauncher.build(url)
        assertNotNull("a web address must be launchable", intent)
        assertEquals(Intent.ACTION_VIEW, intent!!.action)
        assertEquals(Uri.parse(url), intent.data)
    }

    @Test
    fun webAddress_keepsTheWholeAddress() {
        val url = "http://example.com/a/b?c=d&e=f#g"
        assertEquals(Uri.parse(url), ExternalLauncher.build(url)?.data)
    }

    /**
     * The two must disagree, and that disagreement *is* the fix: the link policy answers `InApp` for
     * a web address (it stays in the WebView unless the switch says otherwise), while the launcher
     * has to be able to hand the very same address to another app. Recording both here means a
     * future change that re-couples them fails a test instead of quietly killing the switch again.
     */
    @Test
    fun webAddress_isBuiltEvenThoughTheLinkPolicyDeclinesToOfferIt() {
        val url = "https://example.com/article"
        assertNull("the link policy leaves http/https to the WebView", externalLinkOffer(url))
        assertNotNull("the launcher must still be able to hand it over", ExternalLauncher.build(url))
    }

    @Test
    fun otherPeopleSSchemes_areBuilt() {
        val urls = listOf(
            "tel:+8613800000000",
            "mailto:hello@example.com",
            "geo:31.2304,121.4737",
            "sms:+8613800000000",
            "market://details?id=com.example.app",
            "taobao://item.taobao.com/item.htm?id=1",
            "weixin://dl/scan",
            "snssdk1128://open",
        )
        for (url in urls) {
            val intent = ExternalLauncher.build(url)
            assertNotNull("$url must be launchable", intent)
            assertEquals(url, intent!!.action, Intent.ACTION_VIEW)
            assertEquals(url, Uri.parse(url), intent.data)
        }
    }

    @Test
    fun wellFormedIntentUrl_buildsTheIntentItDescribes() {
        val url = "intent://scan/#Intent;scheme=zxing;package=com.google.zxing.client.android;end"
        val intent = ExternalLauncher.build(url)
        assertNotNull(intent)
        assertEquals(Intent.ACTION_VIEW, intent!!.action)
        assertEquals("com.google.zxing.client.android", intent.`package`)
        assertEquals(Uri.parse("zxing://scan/"), intent.data)
    }

    /**
     * A link is never a way to reach the filesystem, and an unreadable scheme is never a way to
     * invent a handoff. Both answers come from the link policy, but they have to survive the trip
     * through the launcher too.
     */
    @Test
    fun refusedSchemes_buildNothing() {
        val urls = listOf(
            "file:///etc/hosts",
            "content://com.example.provider/x",
            "data:text/html,<b>hi</b>",
            "blob:https://example.com/1",
            "javascript:alert(1)",
            "about:blank",
            "chrome://settings",
            "some-scheme-nobody-has-heard-of://open",
        )
        for (url in urls) {
            assertNull("$url must not be launchable", ExternalLauncher.build(url))
        }
    }

    @Test
    fun blankAddresses_buildNothing() {
        for (url in listOf(null, "", "   ")) {
            assertNull("[$url] must not be launchable", ExternalLauncher.build(url))
        }
    }

    /**
     * A handoff leaves this app. Without the flag the target would be pushed onto LumoBrowser's own
     * task and the back stack would end up pointing at a page LUMO is no longer showing.
     */
    @Test
    fun everyBuiltIntent_asksForItsOwnTask() {
        val urls = listOf(
            "https://example.com/",
            "tel:+8613800000000",
            "intent://scan/#Intent;scheme=zxing;package=com.google.zxing.client.android;end",
        )
        for (url in urls) {
            val flags = ExternalLauncher.build(url)?.flags ?: 0
            assertTrue("$url must carry FLAG_ACTIVITY_NEW_TASK", flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        }
    }

    // --------------------------------------------------------------- launch()

    /**
     * The second half of the same bug. `queryIntentActivities` is filtered by package visibility on
     * Android 11+, `startActivity` is not — so an empty query means "I cannot see one", never "there
     * is none". Gating the launch on it refused to open apps that were installed and willing.
     */
    @Test
    fun invisibleHandler_isStillHandedTheAddress() {
        val url = "https://example.com/"
        val context = RecordingContext(application)
        val lookup = application.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)),
            0,
        )
        assertTrue("nothing is visible to a package query here: $lookup", lookup.isEmpty())
        val outcome = ExternalLauncher.launch(context, url)
        assertTrue("the launch must not be gated on the query: $outcome", outcome is HandoffOutcome.Opened)
        assertEquals(Uri.parse(url), context.last.data)
    }

    @Test
    fun whenThePlatformRefuses_launchReportsNoHandler() {
        val context = RecordingContext(application, ActivityNotFoundException("no activity"))
        assertEquals(HandoffOutcome.NoHandler, ExternalLauncher.launch(context, "tel:+8613800000000"))
    }

    @Test
    fun whenTheAddressIsRefused_launchReportsUnusableAndStartsNothing() {
        val context = RecordingContext(application)
        assertEquals(HandoffOutcome.Unusable, ExternalLauncher.launch(context, "file:///etc/hosts"))
        assertTrue("nothing may be handed over", context.started.isEmpty())
    }

    @Test
    fun whenThePlatformFailsForAnotherReason_launchReportsWhy() {
        val context = RecordingContext(application, SecurityException("not allowed"))
        val outcome = ExternalLauncher.launch(context, "tel:+8613800000000")
        assertEquals(HandoffOutcome.Failed("not allowed"), outcome)
    }

    // ----------------------------------------------------- the switch's promise

    /**
     * "A browser you pick" — with the switch on, the picker is the point, so the intent handed over
     * has to be a chooser even when the device has a default browser the user set months ago.
     */
    @Test
    fun alwaysChoose_showsThePicker() {
        val url = "https://example.com/"
        addBrowser("com.other.browser", url)
        addBrowser("com.third.browser", url)
        val context = RecordingContext(application)

        val outcome = ExternalLauncher.launch(context, url, alwaysChoose = true)
        assertTrue("$outcome", outcome is HandoffOutcome.Opened)
        assertEquals(Intent.ACTION_CHOOSER, context.last.action)

        val inner = context.last.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        assertNotNull("the chooser must carry the real intent", inner)
        assertEquals(Intent.ACTION_VIEW, inner!!.action)
        assertEquals(Uri.parse(url), inner.data)
    }

    @Test
    fun withoutAlwaysChoose_aSingleBrowserIsUsedDirectly() {
        val url = "https://example.com/"
        addBrowser("com.other.browser", url)
        val context = RecordingContext(application)

        val outcome = ExternalLauncher.launch(context, url)
        assertTrue("$outcome", outcome is HandoffOutcome.Opened)
        assertEquals("one handler is not a choice", Intent.ACTION_VIEW, context.last.action)
    }

    /**
     * A picker with one entry is not a choice, it is an extra tap in front of the only browser on
     * the device — so the switch being on must not conjure one.
     */
    @Test
    fun alwaysChoose_withOneVisibleBrowser_goesStraightThere() {
        val url = "https://example.com/"
        addBrowser("com.other.browser", url)
        val context = RecordingContext(application)

        val outcome = ExternalLauncher.launch(context, url, alwaysChoose = true)
        assertTrue("$outcome", outcome is HandoffOutcome.Opened)
        assertEquals(Intent.ACTION_VIEW, context.last.action)
    }

    /**
     * A chooser with nothing in it is a dead end the user has to back out of. When the device cannot
     * see a handler we hand the address over unresolved — the platform's own resolution is not
     * filtered by visibility, so it may well find one the query missed.
     */
    @Test
    fun alwaysChoose_withNoVisibleHandler_neverOpensAnEmptyPicker() {
        val url = "https://example.com/"
        val context = RecordingContext(application)

        ExternalLauncher.launch(context, url, alwaysChoose = true)
        assertEquals(Intent.ACTION_VIEW, context.last.action)
    }

    /**
     * What the query counts has to be what the platform would start.
     *
     * `queryIntentActivities(intent, 0)` answers with filters the launcher would refuse, so a
     * handler without `CATEGORY_DEFAULT` used to look like a second choice and put a picker in front
     * of a user who had exactly one. The candidate lookup adds `MATCH_DEFAULT_ONLY` now, which is
     * what an implicit start does anyway.
     */
    @Test
    fun aHandlerThePlatformWouldNotStart_isNotCountedAsAChoice() {
        val url = "https://example.com/"
        addBrowser("com.other.browser", url)
        addUnstartableHandler("com.never.started", url)
        val context = RecordingContext(application)

        val outcome = ExternalLauncher.launch(context, url, alwaysChoose = true)
        assertTrue("$outcome", outcome is HandoffOutcome.Opened)
        assertEquals(
            "one startable handler is still one handler",
            Intent.ACTION_VIEW,
            context.last.action,
        )
    }

    /**
     * The exclusions belong on the chooser, not on the intent inside it.
     *
     * `createChooser` copies `EXTRA_INTENT`, the title and the URI-permission flags across and
     * nothing else, and it is `ChooserActivity` that reads `EXTRA_EXCLUDE_COMPONENTS` — off the
     * chooser's own extras. An exclusion parked on the target is dropped without a word, which is
     * exactly why it needs a test: it looks like it should work.
     */
    @Test
    fun theChooserCarriesItsExclusionsOnItself() {
        val url = "https://example.com/"
        addBrowser("com.other.browser", url)
        addBrowser("com.third.browser", url)
        // LumoBrowser's own handler for the same address: the one entry the chooser must not offer.
        addBrowser(application.packageName, url)
        val context = RecordingContext(application)

        ExternalLauncher.launch(context, url, alwaysChoose = true)
        assertEquals(Intent.ACTION_CHOOSER, context.last.action)

        val excluded = context.last.getParcelableArrayExtra(
            Intent.EXTRA_EXCLUDE_COMPONENTS,
            ComponentName::class.java,
        )
        assertNotNull("the exclusion must be where ChooserActivity reads it", excluded)
        val packages = excluded!!.map { it.packageName }
        assertTrue("this app must exclude itself, got $packages", packages.contains(application.packageName))

        val inner = context.last.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        assertNull(
            "and not on the target, where createChooser silently drops it",
            inner?.getParcelableArrayExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, ComponentName::class.java),
        )
    }
}
