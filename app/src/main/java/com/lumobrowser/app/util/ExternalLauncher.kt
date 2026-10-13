package com.lumobrowser.app.util

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import com.lumobrowser.app.browser.externalLinkOffer
import com.lumobrowser.app.browser.isWebAddress
import com.lumobrowser.app.browser.parseIntentUrl
import com.lumobrowser.app.browser.schemeOf

/** How a handoff to another application ended. */
sealed interface HandoffOutcome {
    /** Another app took it. [appLabel] is its name when exactly one app could, else null. */
    data class Opened(val appLabel: String?) : HandoffOutcome

    /** The address is well formed but nothing on the device accepts it. */
    data object NoHandler : HandoffOutcome

    /** The address could not be turned into an intent at all — malformed, or refused by policy. */
    data object Unusable : HandoffOutcome

    /** A handler existed and refused, or the platform threw. [message] is for the notice bar. */
    data class Failed(val message: String) : HandoffOutcome
}

/**
 * Turns an address a page asked for into an intent, and only then starts it.
 *
 * This is the single point in LumoBrowser where web content can cause something to happen outside
 * the app, so it is deliberately the only one: [com.lumobrowser.app.browser.LumoWebView] reports
 * handoff requests and never resolves or launches anything itself.
 *
 * Four decisions are worth stating.
 *
 *  * **`intent://` is rebuilt from [parseIntentUrl], never from `Intent.parseUri`.** The platform
 *    parser honours `component=` and `launchFlags=`, which is how a page names an internal class
 *    inside another application — the intent-redirection vector. Our parser refuses those fields,
 *    so an intent URL reaches the target app as an ordinary action/data/package request or not at
 *    all.
 *  * **LumoBrowser excludes itself from every handoff.** Its own activity declares no `VIEW`
 *    filter today, so a resolve of a web address cannot come back as "us" — but the exclusion is
 *    kept so that adding one later cannot make "open in another browser" start this browser again.
 *  * **A web address is built here like any other handoff, and that is the 1.06 fix.** Until 1.06
 *    [build] began at [externalLinkOffer], which answers only for addresses the policy calls
 *    `ConfirmExternal` — and `http`/`https` is not one of those, it is `InApp`. So every address
 *    the cross-site switch handed over was refused *before* the intent existed, came back as
 *    [HandoffOutcome.Unusable], and the browser told the user no other browser could open it while
 *    one was installed. A web address needs no offer: it needs `ACTION_VIEW` and the address.
 *  * **Nothing is pre-flighted before the launch.** [launch] used to resolve first and give up when
 *    the resolve came back empty. On Android 11+ that resolve is filtered by this app's `<queries>`
 *    declaration while `startActivity` is not filtered at all, so a handler that existed could be
 *    refused by a lookup that could not see it. The resolve now only decides whether a chooser is
 *    worth showing; the launch is attempted either way, and [ActivityNotFoundException] is what
 *    means "no handler".
 */
object ExternalLauncher {

    /**
     * Builds the intent for [url], or null when it cannot be built.
     *
     * Returns null rather than throwing so callers can pre-flight an address they are about to show
     * the user, without starting anything.
     */
    fun build(url: String?): Intent? {
        val raw = url?.trim().orEmpty()
        if (raw.isEmpty()) return null

        val intent = when {
            // The cross-site handoff and the error page's "open in another browser". Ordinary web
            // addresses, so there is nothing to confirm and no offer to consult.
            isWebAddress(raw) -> Intent(Intent.ACTION_VIEW, Uri.parse(raw))

            schemeOf(raw) == "intent" -> buildFromIntentUrl(raw) ?: return null

            // Everything else is an app or system scheme, and the policy is what decides whether it
            // may leave at all. `tel:`, `mailto:`, `geo:` and the app-specific long tail are here.
            else -> {
                val offer = externalLinkOffer(raw) ?: return null
                if (!offer.actionable) return null
                Intent(Intent.ACTION_VIEW, Uri.parse(raw))
            }
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return intent
    }

    /**
     * The name of the app that would open [url], or null when none could or several could.
     *
     * Null for several is intentional: the sheet says "an app you choose" rather than inventing a
     * winner. Null for none is equally intentional and carries no claim — on Android 11+ the lookup
     * is bounded by this app's `<queries>` declaration, so seeing nothing is not the same statement
     * as there being nothing, and only [launch] can answer that one for real.
     */
    fun handlerLabel(context: Context, url: String?): String? {
        val intent = build(url) ?: return null
        val matches = candidates(context, intent)
        if (matches.size != 1) return null
        return labelOf(context, matches.first())
    }

    /**
     * Starts [url] in whatever accepts it. The caller is expected to have confirmed first.
     *
     * @param alwaysChoose show the platform's chooser even when the user has already picked a
     *   default handler for that kind of address. The cross-site switch promises a browser the user
     *   picks, so it passes true; an app-specific address leaves it false, because a picker in front
     *   of "open in Taobao" is friction with no decision in it. When the lookup can see only one
     *   handler — or none — there is nothing to choose between and the address goes straight there.
     */
    fun launch(
        context: Context,
        url: String?,
        alwaysChoose: Boolean = false,
    ): HandoffOutcome {
        val intent = build(url) ?: return HandoffOutcome.Unusable

        // Used for the confirmation caption and to decide whether a chooser has anything to show.
        // An empty or filtered answer here must not stop the launch below.
        val matches = candidates(context, intent)
        val label = if (matches.size == 1) labelOf(context, matches.first()) else null

        // Keep this app out of its own handoff. The extra belongs on the chooser built below, not on
        // this intent: `createChooser` copies only `EXTRA_INTENT`, the title and the URI-permission
        // flags out of its target, so an exclusion parked here is dropped on the floor — and the
        // platform's own resolver, which is what answers an unfiltered implicit start, reads the
        // extra off the chooser intent it builds for itself. Worth spelling out because putting it
        // here looks exactly like it should work, and silently does not.
        val self = selfComponents(context, intent)

        val launchable = when {
            // Nothing visible accepts it. Try anyway: queryIntentActivities is filtered by
            // <queries>, startActivity is not, and this is the case that used to be reported as
            // "no app can open this" while a perfectly good app was installed.
            //
            // No chooser is built here on purpose. A chooser with nothing in it is a dead end the
            // user has to back out of, and the platform's own resolution — which is not filtered by
            // visibility — may well find a handler this lookup could not see, and shows its own
            // picker when it finds several.
            matches.isEmpty() -> intent

            // A picker is only worth showing when there is something to pick. With one handler
            // visible there is no decision in it: `alwaysChoose` at that point would be a one-item
            // dialog in front of the only browser on the device.
            alwaysChoose && matches.size > 1 -> chooser(intent, self)

            else -> intent
        }

        return runCatching { context.startActivity(launchable) }
            .fold(
                onSuccess = { HandoffOutcome.Opened(label) },
                onFailure = { error ->
                    // ActivityNotFoundException is the expected one; anything else is worth showing
                    // verbatim rather than pretending the handoff worked.
                    if (error is ActivityNotFoundException) {
                        HandoffOutcome.NoHandler
                    } else {
                        HandoffOutcome.Failed(error.message ?: error::class.java.simpleName)
                    }
                },
            )
    }

    /**
     * Wraps [target] in the platform's chooser.
     *
     * `createChooser` rather than letting the implicit intent resolve on its own: plain resolution
     * honours whatever default the user set months ago, which would silently overrule the choice
     * the switch just offered.
     *
     * Both extras below have to be set on the chooser and not on [target]. `createChooser` copies
     * `EXTRA_INTENT`, the title and the URI-permission flags across and nothing else, and it is
     * `ChooserActivity` — not `ResolverActivity` — that reads `EXTRA_EXCLUDE_COMPONENTS`, off the
     * chooser's own extras. It also copies no `FLAG_ACTIVITY_*`, so the new-task flag this context
     * may need would be lost with it.
     */
    private fun chooser(
        target: Intent,
        self: Array<ComponentName>,
    ): Intent =
        Intent.createChooser(target, null).apply {
            // createChooser keeps only a couple of the target's flags, and this is started from a
            // context that may not be an activity.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (self.isNotEmpty()) putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, self)
        }

    /** The readable name of [info]'s activity, or null when it has none worth showing. */
    private fun labelOf(
        context: Context,
        info: ResolveInfo?,
    ): String? {
        val activity = info?.activityInfo ?: return null
        // loadLabel() is declared non-null, so there is nothing to guard against here beyond the
        // lookup itself throwing for a package that went away between the query and the read.
        return runCatching { activity.loadLabel(context.packageManager).toString() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * Every activity that accepts [intent], minus this app's own.
     *
     * `queryIntentActivities` rather than `resolveActivity`: the latter reports only the preferred
     * handler, so a device with two browsers would look like it had one.
     *
     * `MATCH_DEFAULT_ONLY` and not `0`: the platform adds that flag before it starts anything
     * implicit, so a filter without `CATEGORY_DEFAULT` is visible to a loose query and dead to the
     * launcher. Counting it here would promise a choice that disappears on the tap.
     */
    private fun candidates(
        context: Context,
        intent: Intent,
    ): List<ResolveInfo> =
        runCatching { context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY) }
            .getOrDefault(emptyList())
            .filter { it.activityInfo?.packageName != context.packageName }

    /** This app's own activities that would accept [intent], for `EXTRA_EXCLUDE_COMPONENTS`. */
    private fun selfComponents(
        context: Context,
        intent: Intent,
    ): Array<ComponentName> =
        runCatching { context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY) }
            .getOrDefault(emptyList())
            .filter { it.activityInfo?.packageName == context.packageName }
            .mapNotNull { info ->
                val activity = info.activityInfo ?: return@mapNotNull null
                ComponentName(activity.packageName, activity.name)
            }
            .toTypedArray()

    /**
     * Rebuilds an `intent://` URL as an [Intent] from the strict parse.
     *
     * Data and type have to be set together: assigning one clears the other, so a
     * `type=`-carrying intent would silently lose its data if they were set in sequence.
     */
    private fun buildFromIntentUrl(url: String): Intent? {
        val spec = parseIntentUrl(url) ?: return null
        if (!spec.isActionable) return null

        val intent = Intent()
        val data = spec.data
        val type = spec.type
        when {
            data != null && type != null -> intent.setDataAndType(Uri.parse(data), type)
            data != null -> intent.data = Uri.parse(data)
            type != null -> intent.type = type
        }

        intent.action = spec.action ?: Intent.ACTION_VIEW
        spec.packageName?.let { intent.setPackage(it) }
        spec.categories.forEach { intent.addCategory(it) }
        spec.stringExtras.forEach { (key, value) -> intent.putExtra(key, value) }
        spec.intExtras.forEach { (key, value) -> intent.putExtra(key, value) }
        spec.longExtras.forEach { (key, value) -> intent.putExtra(key, value) }
        spec.floatExtras.forEach { (key, value) -> intent.putExtra(key, value) }
        spec.boolExtras.forEach { (key, value) -> intent.putExtra(key, value) }
        return intent
    }
}
