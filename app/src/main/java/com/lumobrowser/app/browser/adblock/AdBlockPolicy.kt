package com.lumobrowser.app.browser.adblock

/**
 * The rules of engagement between a filter list and a real page.
 *
 * [AdBlockRules] knows how to match; this knows *when matching is allowed to matter*. The two are
 * separate on purpose, because every one of the decisions below is a decision to leave a page alone,
 * and those are the ones that have to be readable at a glance when something looks wrong.
 *
 * The order is deliberate:
 *
 *  1. **The document itself is never a candidate.** Not "usually" — never. A rule that stops the
 *     page from loading is not an over-eager ad filter, it is a broken browser, and no filter list
 *     is worth that trade. Callers pass `isForMainFrame` and this refuses on it, so a future
 *     refactor cannot accidentally hand the main frame to the matcher.
 *  2. **Exceptions win.** Checked before any blocking rule, including the path fragments.
 *  3. **Host rules** then **path fragments**, with the path fragments restricted to third-party
 *     requests (see [isThirdParty]) because a path like `/banners/` is a perfectly ordinary name for
 *     a site's own image directory.
 *
 * Everything here is pure Kotlin and pure in the functional sense: no clocks, no I/O, no Android.
 */
object AdBlockPolicy {

    /**
     * Public suffixes written with two labels, where the registrable name is the last *three*.
     *
     * A full public-suffix list is a 200 KB file that would need its own updater; this is the short
     * list of the ones that actually change which requests count as third-party for this app's
     * users. Two-label suffixes missing from here behave as they did before — the site is the whole
     * suffix — which only ever makes a request look *more* first-party, so a gap costs coverage
     * rather than breaking a page.
     */
    private val TWO_LABEL_SUFFIXES = setOf(
        // Greater China — the case that motivated the list.
        "com.cn", "net.cn", "org.cn", "gov.cn", "edu.cn", "ac.cn",
        "com.tw", "org.tw", "idv.tw",
        "com.hk", "org.hk", "edu.hk", "gov.hk",
        "com.mo", "edu.mo",
        // Other common two-label suffixes.
        "co.uk", "org.uk", "ac.uk", "gov.uk", "me.uk", "net.uk",
        "co.jp", "ne.jp", "or.jp", "ac.jp", "go.jp",
        "co.kr", "or.kr", "ne.kr",
        "com.au", "net.au", "org.au", "edu.au",
        "co.nz", "net.nz", "org.nz",
        "com.sg", "com.my", "com.ph", "com.vn", "com.th", "co.id",
        "com.br", "com.mx", "com.ar", "com.co", "com.pe", "com.ve",
        "co.in", "com.pk", "com.bd", "com.np", "com.sa", "com.tr", "com.ua", "com.ru",
        "co.za", "com.eg", "com.ng",
    )

    /**
     * Extensions that only ever name a streaming manifest or fragment.
     *
     * Four, and no more. `.mp4` and `.mp3` are absent on purpose: a page can serve an advert as
     * either, and a rule that swallowed them would block media the reader asked for while rarely
     * touching the advert — the exact trade this policy exists to refuse.
     */
    private val MEDIA_SEGMENT_SUFFIXES = listOf(".m3u8", ".mpd", ".ts", ".m4s")

    /**
     * The host of [url], lower-cased, without user info, port or brackets.
     *
     * Hand-written rather than `java.net.URI` or `android.net.Uri` because this runs inside
     * `shouldInterceptRequest`, where a parser that throws on a malformed URL would turn a bad
     * address into a blocked request. Every failure path here returns an empty string, and an empty
     * host matches no rule — the failure mode is "let it through", which is the only acceptable one.
     */
    fun hostOf(url: String): String {
        val schemeEnd = url.indexOf("://")
        val start = if (schemeEnd >= 0) schemeEnd + 3 else 0
        if (start >= url.length) return ""
        var end = url.length
        for (marker in charArrayOf('/', '?', '#')) {
            val at = url.indexOf(marker, start)
            if (at in start until end) end = at
        }
        var authority = url.substring(start, end)
        val at = authority.lastIndexOf('@')
        if (at >= 0) authority = authority.substring(at + 1)

        // An IPv6 literal is bracketed, and those brackets are the only reason a colon can appear
        // before the port is: `[2606:4700::1111]:443`. Cutting at the first colon instead would read
        // that address as `2606`, so the brackets are resolved first.
        val host = if (authority.startsWith("[")) {
            val closing = authority.indexOf(']')
            // An unterminated bracket names nothing. Returning it would hand the matcher a character
            // no rule can hold, so the address is treated as unreadable and the request is let
            // through - the only acceptable failure mode here.
            if (closing < 0) return ""
            authority.substring(1, closing)
        } else {
            val colon = authority.indexOf(':')
            if (colon >= 0) authority.substring(0, colon) else authority
        }

        // A trailing root dot is a legal absolute name (`ads.example.com.`) and it is also the
        // cheapest way to walk past a host rule, so it is dropped before matching. Every other dot
        // stays: the dots in the middle are what tell one label from the next.
        return host.trim().trimEnd('.').lowercase()
    }

    /** The path and query of [url], without the scheme and authority. */
    fun pathAndQueryOf(url: String): String {
        val schemeEnd = url.indexOf("://")
        val start = if (schemeEnd >= 0) schemeEnd + 3 else 0
        val slash = url.indexOf('/', start)
        if (slash < 0) return "/"
        val hash = url.indexOf('#', slash)
        return if (hash >= 0) url.substring(slash, hash) else url.substring(slash)
    }

    /** True for the two schemes a filter list is allowed to have an opinion about. */
    fun isFilterableScheme(url: String): Boolean {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd <= 0) return false
        return when (url.substring(0, schemeEnd).lowercase()) {
            "http", "https" -> true
            else -> false
        }
    }

    /**
     * Site of [host]: the registrable name, approximated as its last two labels.
     *
     * Two-label public suffixes (`com.cn`, `co.uk`, …) are handled by [TWO_LABEL_SUFFIXES] so that
     * `news.sina.com.cn` is `sina.com.cn` rather than all of `com.cn`. Without that, every Chinese
     * site would look like every other Chinese site, which makes the path rules useless there — the
     * whole point of the list is Chinese ad paths.
     *
     * The remaining approximations are deliberate and documented: `bbc.co.uk` and `ads.bbc.co.uk`
     * both reduce to `bbc.co.uk`, and a host with nothing in front of a two-label suffix is its own
     * site. Both fall on the side that keeps pages working — a request judged first-party skips the
     * path rules and is still checked against the host rules.
     */
    fun siteOf(host: String): String {
        if (host.isEmpty()) return ""
        // Counted from the right: everything from the second-to-last dot onwards. Dropping the
        // leftmost label instead would make `cdn.www.example.com` a different site from
        // `www.example.com`, which is the direction that breaks pages.
        val lastDot = host.lastIndexOf('.')
        if (lastDot <= 0) return host
        val previousDot = host.lastIndexOf('.', lastDot - 1)
        if (previousDot < 0) return host
        val lastTwo = host.substring(previousDot + 1)
        if (lastTwo !in TWO_LABEL_SUFFIXES) return lastTwo
        // `news.example.com.cn`: one label further right is the registrable name. When there is no
        // label left, the host *is* the registrable name (`example.com.cn`), so it stays whole.
        val beforePrevious = host.lastIndexOf('.', previousDot - 1)
        return if (beforePrevious < 0) host else host.substring(beforePrevious + 1)
    }

    /** Whether a request from [pageHost] to [requestHost] leaves the site. */
    fun isThirdParty(pageHost: String, requestHost: String): Boolean {
        if (pageHost.isEmpty() || requestHost.isEmpty()) return false
        return siteOf(pageHost) != siteOf(requestHost)
    }

    /**
     * Decides one request.
     *
     * `pageHost` is the host of the document that is asking; it is only consulted for the path
     * fragments, and only to decide whether they apply at all.
     */
    fun shouldBlock(
        rules: AdBlockRules?,
        url: String,
        pageHost: String,
        isForMainFrame: Boolean,
        cache: DecisionCache? = null,
    ): Boolean = reasonFor(rules, url, pageHost, isForMainFrame, cache) != null

    /**
     * The same decision as [shouldBlock], with the rule class that made it.
     *
     * Split out so the blocked-request log can say *why* something was blocked — "host rule" and
     * "path fragment" need different answers when a page turns out to be broken, and a caller that
     * only needs the yes/no should not have to care.
     */
    fun reasonFor(
        rules: AdBlockRules?,
        url: String,
        pageHost: String,
        isForMainFrame: Boolean,
        cache: DecisionCache? = null,
    ): AdBlockReason? {
        if (rules == null) return null
        if (isForMainFrame) return null
        if (!isFilterableScheme(url)) return null

        val host = hostOf(url)
        if (host.isEmpty()) return null
        if (rules.isHostAllowed(host)) return null

        val byHost = cache?.get(host) ?: rules.isHostBlocked(host).also { cache?.put(host, it) }
        // A streaming segment is never an advert in this rule set, and blocking one costs the reader
        // the whole video while saving them nothing: the fragments that make up a film are not ads,
        // and an advert served from a blocked host is still served to the page's own player, which
        // then stalls instead of skipping. The check is deliberately last — the allow-list above and
        // the host lookup itself stay in force for everything else.
        if (byHost && !isMediaSegment(url)) return AdBlockReason.HostRule

        if (!isThirdParty(pageHost, host)) return null
        return if (rules.matchesPath(pathAndQueryOf(url))) AdBlockReason.PathRule else null
    }

    /**
     * Whether [url] names a streaming manifest or segment.
     *
     * Only the four extensions that no other kind of resource uses: `.m3u8` and `.mpd` manifests,
     * `.ts` and `.m4s` fragments. `.mp4` and `.mp3` are deliberately absent — those are ordinary
     * files a page can also serve as an advert, and a rule that swallowed them would be the very
     * "blocking things that are not adverts" failure this policy is written to avoid.
     */
    fun isMediaSegment(url: String): Boolean {
        val path = pathAndQueryOf(url).substringBefore('?').substringBefore('#').lowercase()
        return MEDIA_SEGMENT_SUFFIXES.any { path.endsWith(it) }
    }

    /**
     * Whether the rules were even consulted for this request.
     *
     * Used for the "requests checked" counter, which would be misleading if it included the main
     * document (never a candidate) or non-http schemes (never filtered). The counter and this
     * predicate have to agree, so the gates live here in one place.
     */
    fun isExaminable(url: String, isForMainFrame: Boolean): Boolean =
        !isForMainFrame && isFilterableScheme(url)

    /**
     * A bounded memory of host decisions.
     *
     * A single page routinely asks the same ad host dozens of times, and the answer is a property of
     * the host, not of the request, so it is worth remembering. Bounded because a long browsing
     * session visits a lot of hosts; cleared whenever the ruleset changes, because every entry in it
     * was an answer from the old ruleset.
     */
    class DecisionCache(private val capacity: Int = 512) {

        private val entries = object : LinkedHashMap<String, Boolean>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean =
                size > capacity
        }

        @Synchronized
        fun get(host: String): Boolean? = entries[host]

        @Synchronized
        fun put(host: String, blocked: Boolean) {
            entries[host] = blocked
        }

        @Synchronized
        fun clear() {
            entries.clear()
        }

        @Synchronized
        fun size(): Int = entries.size
    }
}
