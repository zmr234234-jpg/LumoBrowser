package com.lumobrowser.app.browser.adblock

/**
 * A parsed filter list.
 *
 * Deliberately plain Kotlin with no Android types: the matching rules are the part of ad blocking
 * that can be wrong in a way nobody notices until a page breaks, so they are the part that has to be
 * testable on the JVM, at speed, without a WebView or a Robolectric runtime in the way.
 *
 * Three rule kinds, in the order they are consulted:
 *
 *  * **exceptions** ([allowedHosts]) — a host and all of its subdomains that must never be blocked.
 *    Checked first, so an exception always wins over a broader rule underneath it.
 *  * **hosts** ([blockedHosts]) — a host and all of its subdomains.
 *  * **path fragments** ([pathFragments]) — a substring of the path-and-query. These are the
 *    bluntest instrument in the file and the caller is expected to apply them only to third-party
 *    sub-resources; see [AdBlockPolicy], which is where that restriction actually lives.
 *
 * Nothing here normalises, defaults or guesses. An entry that is not understood is counted in
 * [ignoredEntries] and dropped, because a filter list that silently invents rules from malformed
 * lines is a filter list nobody can reason about.
 */
class AdBlockRules internal constructor(
    val blockedHosts: Set<String>,
    val allowedHosts: Set<String>,
    val pathFragments: List<String>,
    /** The list's own version string, as written in its header. Empty when it declared none. */
    val version: String,
    /** The list's own last-updated date, as written in its header. Empty when it declared none. */
    val updated: String,
    /** Lines that were present but not understood. Surfaced so "loaded fine" cannot be a guess. */
    val ignoredEntries: Int,
) {

    /** True when this ruleset cannot block anything, whatever the request. */
    val isEmpty: Boolean get() = blockedHosts.isEmpty() && pathFragments.isEmpty()

    val hostRuleCount: Int get() = blockedHosts.size

    val pathRuleCount: Int get() = pathFragments.size

    val exceptionCount: Int get() = allowedHosts.size

    /** Total entries that would be consulted for a request. Used for reporting, not for decisions. */
    val ruleCount: Int get() = hostRuleCount + pathRuleCount

    /**
     * Whether [host] is on the exception list, directly or as a subdomain of an entry.
     *
     * The suffix test is label-aligned: `notgoogle.com` does not match a rule for `google.com`, and
     * `mail.google.com` does. Getting that wrong in the permissive direction would silently disable
     * the entire list for any host that happens to end with a blocked name.
     */
    fun isHostAllowed(host: String): Boolean = matchesAny(host, allowedHosts)

    /** Whether [host] is blocked by a host rule, directly or as a subdomain of an entry. */
    fun isHostBlocked(host: String): Boolean = matchesAny(host, blockedHosts)

    /** Whether any path fragment rule appears in [pathAndQuery]. Compared case-insensitively. */
    fun matchesPath(pathAndQuery: String): Boolean {
        if (pathFragments.isEmpty() || pathAndQuery.isEmpty()) return false
        val lowered = pathAndQuery.lowercase()
        for (fragment in pathFragments) {
            if (lowered.contains(fragment)) return true
        }
        return false
    }

    override fun toString(): String =
        "AdBlockRules(hosts=$hostRuleCount, paths=$pathRuleCount, exceptions=$exceptionCount, " +
            "version='$version', updated='$updated', ignored=$ignoredEntries)"

    companion object {

        /**
         * A ruleset that blocks nothing.
         *
         * This is what a failed load degrades to, and it is a complete answer rather than an error
         * state: browsing continues exactly as it would with the switch off. The brief is explicit
         * that a damaged or unreadable rule file must never stop a page from opening, and the
         * cheapest way to guarantee that is for the failure mode to be a value, not an exception.
         */
        val Unavailable = AdBlockRules(
            blockedHosts = emptySet(),
            allowedHosts = emptySet(),
            pathFragments = emptyList(),
            version = "",
            updated = "",
            ignoredEntries = 0,
        )

        private const val HOSTS_FILE_PREFIX_1 = "0.0.0.0"
        private const val HOSTS_FILE_PREFIX_2 = "127.0.0.1"
        private const val LOCALHOST = "localhost"

        /**
         * The shortest path fragment worth obeying.
         *
         * [matchesPath] is a substring test against every third-party address, so a
         * short fragment is not a narrow rule - it is a very wide one. `/a` occurs inside
         * `/assets/`, `/api/` and `/ajax/`; `/ad` occurs inside `/admin/` and `/adapter`. Four
         * characters is the shortest length that names an advertising path (`/ads`) rather than a
         * coincidence, and anything shorter is counted in [ignoredEntries] instead of being obeyed.
         */
        private const val MIN_PATH_FRAGMENT_LENGTH = 4

        /**
         * Parses a filter list.
         *
         * Accepts the two shapes a rule file realistically arrives in: the plain one this app ships
         * (`example.com`) and the hosts-file form (`0.0.0.0 example.com`), so a list copied from an
         * operating-system blocklist still works. `||example.com^` and `@@||example.com^` are
         * unwrapped as well, because that is how filter lists in the wild spell "this host and its
         * subdomains" and "exception to it".
         *
         * ABP-style `$` modifiers are dropped rather than guessed at: this engine implements one
         * rule kind, and pretending to honour `$third-party,script` by ignoring the qualifier would
         * be a rule that fires in cases its author excluded.
         */
        fun parse(text: String): AdBlockRules {
            val blocked = LinkedHashSet<String>()
            val allowed = LinkedHashSet<String>()
            val fragments = LinkedHashSet<String>()
            var version = ""
            var updated = ""
            var ignored = 0

            for (rawLine in text.lineSequence()) {
                val line = rawLine.trim()
                if (line.isEmpty()) continue

                if (line.startsWith("#")) {
                    // The only comments with meaning: the list's own identity, which the settings
                    // screen shows. Everything else in a comment is prose.
                    val body = line.removePrefix("#").trim()
                    when {
                        body.startsWith("version:", ignoreCase = true) ->
                            version = body.substringAfter(':').trim()

                        body.startsWith("updated:", ignoreCase = true) ->
                            updated = body.substringAfter(':').trim()
                    }
                    continue
                }

                val entry = line.substringBefore('$').trim()
                if (entry.isEmpty()) {
                    ignored++
                    continue
                }

                if (entry.startsWith("/")) {
                    // A path fragment. Stored lower-cased and without its trailing wildcard, which
                    // is how they are compared.
                    val fragment = entry.trimEnd('*').lowercase()
                    if (fragment.length >= MIN_PATH_FRAGMENT_LENGTH) {
                        fragments.add(fragment)
                    } else {
                        ignored++
                    }
                    continue
                }

                val isException = entry.startsWith("@@")
                val candidate = normalizeHostRule(entry.removePrefix("@@"))
                if (candidate == null) {
                    ignored++
                    continue
                }
                if (isException) allowed.add(candidate) else blocked.add(candidate)
            }

            return AdBlockRules(
                blockedHosts = blocked,
                allowedHosts = allowed,
                pathFragments = fragments.toList(),
                version = version,
                updated = updated,
                ignoredEntries = ignored,
            )
        }

        /**
         * Reduces one rule line to a bare host, or null when it is not a host rule at all.
         *
         * Returns null rather than something plausible-looking: every `null` here becomes a counted
         * [ignoredEntries] entry, and a rule that was dropped is visible while a rule that was
         * mangled into a different rule is not.
         */
        private fun normalizeHostRule(raw: String): String? {
            var value = raw.trim()
            if (value.isEmpty()) return null

            // `||example.com^` (domain anchor) and `|example.com|` (exact anchor).
            value = value.removePrefix("||").removePrefix("|")
            value = value.removeSuffix("^").removeSuffix("|")
            value = value.trim()

            // Hosts-file form: "0.0.0.0 example.com".
            val space = value.indexOfFirst { it == ' ' || it == '\t' }
            if (space > 0) {
                val head = value.substring(0, space)
                if (head == HOSTS_FILE_PREFIX_1 || head == HOSTS_FILE_PREFIX_2) {
                    value = value.substring(space + 1).trim()
                } else {
                    return null
                }
            }
            if (value == LOCALHOST) return null
            // A sink address on its own - a hosts-file line that lost its host. Nothing is served
            // from it, and taking it as a hostname would put an address nobody can read into the
            // rule count on the settings screen.
            if (value == HOSTS_FILE_PREFIX_1 || value == HOSTS_FILE_PREFIX_2) return null

            value = value.removePrefix(".").removeSuffix(".").lowercase()
            if (value.isEmpty()) return null

            // A rule has to be a hostname. Anything with a separator, a wildcard or a space left in
            // it is not one, and a single-label rule such as "com" would block the internet.
            if (value.length > 253) return null
            if (!value.contains('.')) return null
            for (ch in value) {
                val ok = ch == '.' || ch == '-' || ch == '_' || ch in 'a'..'z' || ch in '0'..'9'
                if (!ok) return null
            }
            return value
        }

        private fun matchesAny(host: String, rules: Set<String>): Boolean {
            if (host.isEmpty() || rules.isEmpty()) return false
            if (rules.contains(host)) return true
            var dot = host.indexOf('.')
            while (dot >= 0 && dot + 1 < host.length) {
                if (rules.contains(host.substring(dot + 1))) return true
                dot = host.indexOf('.', dot + 1)
            }
            return false
        }
    }
}
