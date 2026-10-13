package com.lumobrowser.app.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that decide whether a navigation stays in the app, leaves it, or is refused.
 *
 * These tests exist because the previous behaviour was wrong in both directions at once: `tel:` and
 * `mailto:` links were refused with an error instead of being offered to the dialler, while
 * `about:blank` — which `window.open()` produces on its own — was reported to the user as a blocked
 * scheme. Both are one-line assertions here and neither was covered before.
 */
class WebNavigationPolicyTest {

    // ---- stays in the app -------------------------------------------------------------------

    @Test
    fun `http and https stay in the webview`() {
        assertEquals(NavigationKind.InApp, classifyNavigation("http://example.com/a"))
        assertEquals(NavigationKind.InApp, classifyNavigation("https://example.com/a/b?c=d#e"))
    }

    @Test
    fun `about blank stays in the webview because window open produces it`() {
        // Refusing this is what turned every unadorned popup into a spurious "blocked scheme" error.
        assertEquals(NavigationKind.InApp, classifyNavigation("about:blank"))
        assertEquals(NavigationKind.InApp, classifyNavigation("about:blank#blocked"))
    }

    @Test
    fun `javascript urls are left to the webview`() {
        // Refusing them removes nothing: the page could call the same function directly. It only
        // breaks javascript-colon placeholder links, which are everywhere.
        assertEquals(NavigationKind.InApp, classifyNavigation("javascript:void(0)"))
        assertEquals(NavigationKind.InApp, classifyNavigation("javascript:history.back()"))
    }

    @Test
    fun `a scheme-less address is webview's business`() {
        assertEquals(NavigationKind.InApp, classifyNavigation("example.com/path"))
        assertEquals(NavigationKind.InApp, classifyNavigation("/relative/path"))
        assertEquals(NavigationKind.InApp, classifyNavigation("#anchor"))
        assertEquals(NavigationKind.InApp, classifyNavigation("?query=1"))
    }

    @Test
    fun `blank and null are not a handoff`() {
        assertEquals(NavigationKind.InApp, classifyNavigation(null))
        assertEquals(NavigationKind.InApp, classifyNavigation(""))
        assertEquals(NavigationKind.InApp, classifyNavigation("   "))
    }

    @Test
    fun `a host with a port is not mistaken for a scheme`() {
        // "example.com:8080" has a colon but the text before it is not a valid scheme.
        assertEquals("", schemeOf("example.com:8080/x"))
        assertEquals(NavigationKind.InApp, classifyNavigation("example.com:8080/x"))
    }

    @Test
    fun `scheme matching is case insensitive`() {
        assertEquals(NavigationKind.InApp, classifyNavigation("HTTP://EXAMPLE.COM"))
        assertEquals(NavigationKind.InApp, classifyNavigation("HtTpS://example.com"))
        assertEquals(NavigationKind.ConfirmExternal, classifyNavigation("TEL:+8613800000000"))
        assertEquals(NavigationKind.Blocked, classifyNavigation("FILE:///etc/passwd"))
    }

    // ---- leaves the app ---------------------------------------------------------------------

    @Test
    fun `telephone and mail links are offered to the user`() {
        // The single most common external link on the mobile web, and it used to show an error.
        assertEquals(NavigationKind.ConfirmExternal, classifyNavigation("tel:+8613800000000"))
        assertEquals(NavigationKind.ConfirmExternal, classifyNavigation("mailto:a@b.com?subject=x"))
        assertEquals(NavigationKind.ConfirmExternal, classifyNavigation("sms:+8613800000000"))
        assertEquals(NavigationKind.ConfirmExternal, classifyNavigation("geo:39.9,116.4?q=beijing"))
    }

    @Test
    fun `intent urls are offered so the parser gets a chance to validate them`() {
        // Classification says "ask"; parseIntentUrl decides whether it is well-formed enough to
        // launch. The two questions are separate on purpose.
        assertEquals(NavigationKind.ConfirmExternal, classifyNavigation("intent://x#Intent;end"))
    }

    @Test
    fun `common app schemes are offered`() {
        for (url in listOf(
            "market://details?id=com.example",
            "whatsapp://send?text=hi",
            "weixin://dl/business",
            "taobao://item.taobao.com/item.htm?id=1",
            "bilibili://video/1",
        )) {
            assertEquals(url, NavigationKind.ConfirmExternal, classifyNavigation(url))
        }
    }

    // ---- refused ----------------------------------------------------------------------------

    @Test
    fun `filesystem and provider schemes are refused`() {
        assertEquals(NavigationKind.Blocked, classifyNavigation("file:///etc/passwd"))
        assertEquals(NavigationKind.Blocked, classifyNavigation("content://com.other/secret"))
        assertEquals(NavigationKind.Blocked, classifyNavigation("filesystem:https://x/temporary/y"))
    }

    @Test
    fun `a top level data url is refused`() {
        // The classic address-bar spoof: the document is not at the address it claims. Subresource
        // data: URLs never reach this classifier at all.
        assertEquals(NavigationKind.Blocked, classifyNavigation("data:text/html,<h1>hi</h1>"))
    }

    @Test
    fun `webview internal schemes are refused`() {
        assertEquals(NavigationKind.Blocked, classifyNavigation("chrome://settings"))
        assertEquals(NavigationKind.Blocked, classifyNavigation("resource://x"))
    }

    @Test
    fun `an invented scheme is refused rather than forwarded to the os`() {
        // The interesting hostile case: a page invents a scheme and hopes the browser hands it to
        // whoever registered it. There is no such thing as a legitimate one nobody has heard of.
        assertEquals(NavigationKind.Blocked, classifyNavigation("totally-made-up://payload"))
        assertEquals(NavigationKind.Blocked, classifyNavigation("evil://x"))
    }

    @Test
    fun `blocked schemes win over the external allowlist`() {
        // Guard against a future edit that adds file: to the allowlist: the blocklist is checked
        // first, so that edit cannot re-open the filesystem.
        for (scheme in listOf("file", "content", "data", "blob", "chrome", "resource", "jar")) {
            assertEquals(scheme, NavigationKind.Blocked, classifyNavigation("$scheme://x"))
        }
    }

    // ---- helpers ----------------------------------------------------------------------------

    @Test
    fun `scheme extraction handles the documented shapes`() {
        assertEquals("https", schemeOf("https://example.com"))
        assertEquals("tel", schemeOf("tel:+123"))
        assertEquals("intent", schemeOf("intent://x#Intent;end"))
        assertEquals("", schemeOf("example.com"))
        assertEquals("", schemeOf(""))
        assertEquals("", schemeOf(null))
        // A leading character that cannot start a scheme.
        assertEquals("", schemeOf("1abc://x"))
        assertEquals("", schemeOf("://x"))
    }

    @Test
    fun `isExternalNavigation agrees with the classifier`() {
        assertTrue(isExternalNavigation("tel:+123"))
        assertFalse(isExternalNavigation("https://example.com"))
        assertFalse(isExternalNavigation("file:///x"))
        assertFalse(isExternalNavigation(null))
    }

    @Test
    fun `blocked reasons name the actual cause`() {
        assertEquals("file", blockedReason("file:///etc/passwd"))
        assertEquals("content", blockedReason("content://x/y"))
        assertEquals("data", blockedReason("data:text/html,x"))
        assertEquals("internal", blockedReason("chrome://settings"))
        assertEquals("unknown", blockedReason("evil://x"))
        assertEquals("malformed", blockedReason(""))
    }

    @Test
    fun `only plain web addresses can be bookmarked`() {
        // A restored backup must not be able to plant a tel: or intent: entry in the library.
        assertTrue(isBookmarkable("https://example.com"))
        assertTrue(isBookmarkable("http://example.com"))
        assertFalse(isBookmarkable("tel:+123"))
        assertFalse(isBookmarkable("intent://x#Intent;end"))
        assertFalse(isBookmarkable("javascript:alert(1)"))
        assertFalse(isBookmarkable(null))
    }

    // ---- intent parsing ---------------------------------------------------------------------

    @Test
    fun `the documented zxing example parses`() {
        val spec = parseIntentUrl(
            "intent://scan/#Intent;scheme=zxing;package=com.google.zxing.client.android;end",
        )
        assertEquals("zxing://scan/", spec?.data)
        assertEquals("com.google.zxing.client.android", spec?.packageName)
        assertTrue(spec!!.isActionable)
    }

    @Test
    fun `typed extras are parsed with their types`() {
        val spec = parseIntentUrl(
            "intent://x#Intent;action=android.intent.action.VIEW;" +
                "S.name=hello;i.count=3;l.big=9000000000;f.ratio=1.5;b.flag=true;end",
        )
        assertEquals("android.intent.action.VIEW", spec?.action)
        assertEquals("hello", spec?.stringExtras?.get("name"))
        assertEquals(3, spec?.intExtras?.get("count"))
        assertEquals(9_000_000_000L, spec?.longExtras?.get("big"))
        assertEquals(1.5f, spec?.floatExtras?.get("ratio"))
        assertEquals(true, spec?.boolExtras?.get("flag"))
    }

    @Test
    fun `a malformed typed extra is dropped rather than failing the whole intent`() {
        // The scheme is what makes this URL actionable at all: with no scheme, no action, no type
        // and no package, parseIntentUrl correctly discards the whole thing (IntentUrl.kt:202), and
        // the assertion below would pass for the wrong reason — an empty spec rather than a spec
        // that kept the good extra and dropped the bad one.
        val spec = parseIntentUrl("intent://x#Intent;scheme=example;i.count=notanumber;S.ok=yes;end")
        assertNotNull(spec)
        assertNull(spec?.intExtras?.get("count"))
        assertEquals("yes", spec?.stringExtras?.get("ok"))
    }

    @Test
    fun `an intent url with nothing actionable is discarded`() {
        // Syntactically this parses: the "#Intent;" marker is present and both parameters are
        // well-formed. It is still thrown away, because an intent with no action, no type, no
        // package and data that is a bare "//host/path" (no scheme to apply) cannot be turned into
        // anything the platform would accept — and handing the platform a half-formed intent is how
        // intent redirection starts. The app says "no app accepts this" instead of guessing.
        assertNull(parseIntentUrl("intent://x#Intent;i.count=notanumber;S.ok=yes;end"))

        // Adding the scheme is the whole difference: "intent://x" plus scheme=example is the
        // documented spelling of "example://x", which is actionable.
        assertNotNull(parseIntentUrl("intent://x#Intent;scheme=example;i.count=notanumber;end"))
    }

    @Test
    fun `the fallback url is separated from the extras and must be a web address`() {
        val good = parseIntentUrl(
            "intent://x#Intent;package=com.example.app;" +
                "S.browser_fallback_url=https%3A%2F%2Fexample.com%2Fapp;end",
        )
        // Percent-encoded fallbacks are not decoded: the platform treats the value literally, and
        // decoding here would make this app's behaviour differ from every other browser's.
        assertNull(good?.fallbackUrl)
        assertNull(good?.stringExtras?.get("browser_fallback_url"))

        val plain = parseIntentUrl(
            "intent://x#Intent;package=com.example.app;S.browser_fallback_url=https://example.com/app;end",
        )
        assertEquals("https://example.com/app", plain?.fallbackUrl)
        assertNull(plain?.stringExtras?.get("browser_fallback_url"))

        val hostile = parseIntentUrl(
            "intent://x#Intent;package=com.example.app;S.browser_fallback_url=file:///etc/passwd;end",
        )
        assertNull(hostile?.fallbackUrl)
    }

    @Test
    fun `component and launchFlags are refused`() {
        // Intent redirection: a page must not be able to name a class inside another app, nor pick
        // the launch flags this app uses.
        val spec = parseIntentUrl(
            "intent://x#Intent;component=com.victim/.PrivateActivity;" +
                "launchFlags=0x10000000;package=com.example.app;end",
        )
        assertEquals("com.example.app", spec?.packageName)
        assertFalse(spec.toString().contains("PrivateActivity"))
        assertFalse(spec.toString().contains("launchFlags"))
    }

    @Test
    fun `a data uri reaching the filesystem is refused whatever scheme was declared`() {
        assertNull(parseIntentUrl("intent://x#Intent;scheme=file;package=com.example.app;end"))
        assertNull(parseIntentUrl("intent://x#Intent;scheme=content;package=com.example.app;end"))
    }

    @Test
    fun `a malformed package name is dropped rather than passed to setPackage`() {
        // setPackage throws on a bad name, so this is the difference between a message and a crash.
        assertNull(parseIntentUrl("intent://x#Intent;package=;end"))
        assertNull(parseIntentUrl("intent://x#Intent;package=notapackage;end"))
        assertNull(parseIntentUrl("intent://x#Intent;package=1bad.name;end"))
        assertNull(parseIntentUrl("intent://x#Intent;package=com..double;end"))
        assertEquals(
            "com.example.app",
            parseIntentUrl("intent://x#Intent;package=com.example.app;end")?.packageName,
        )
    }

    @Test
    fun `an intent with nothing resolvable is rejected`() {
        assertNull(parseIntentUrl("intent://x#Intent;end"))
        assertNull(parseIntentUrl("intent://x"))
    }

    @Test
    fun `non intent urls are rejected`() {
        assertNull(parseIntentUrl("https://example.com"))
        assertNull(parseIntentUrl("tel:+123"))
        assertNull(parseIntentUrl(null))
        assertNull(parseIntentUrl(""))
    }

    @Test
    fun `categories accumulate and duplicates collapse`() {
        val spec = parseIntentUrl(
            "intent://x#Intent;category=android.intent.category.BROWSABLE;" +
                "category=android.intent.category.DEFAULT;" +
                "category=android.intent.category.DEFAULT;package=com.example.app;end",
        )
        assertEquals(
            listOf("android.intent.category.BROWSABLE", "android.intent.category.DEFAULT"),
            spec?.categories,
        )
    }

    @Test
    fun `the extras cap holds`() {
        val many = buildString {
            append("intent://x#Intent;package=com.example.app;")
            repeat(200) { append("S.k$it=v;") }
            append("end")
        }
        val spec = parseIntentUrl(many)
        assertEquals(MAX_EXTRAS_FOR_TEST, spec?.stringExtras?.size)
    }

    @Test
    fun `a very long extra value is dropped`() {
        val huge = "intent://x#Intent;package=com.example.app;S.big=" + "a".repeat(5000) + ";end"
        assertNull(parseIntentUrl(huge)?.stringExtras?.get("big"))
    }

    @Test
    fun `missing end terminator still parses`() {
        // Real pages omit it; the platform tolerates that and so must the parser.
        val spec = parseIntentUrl("intent://x#Intent;package=com.example.app;S.a=b")
        assertEquals("com.example.app", spec?.packageName)
        assertEquals("b", spec?.stringExtras?.get("a"))
    }

    @Test
    fun `target labels describe the kind of handoff`() {
        assertEquals("tel", externalTargetLabel("tel:+123"))
        assertEquals("sms", externalTargetLabel("sms:+123"))
        assertEquals("mailto", externalTargetLabel("mailto:a@b.com"))
        assertEquals("geo", externalTargetLabel("geo:1,2"))
        assertEquals("market", externalTargetLabel("market://details?id=x"))
        assertEquals("intent", externalTargetLabel("intent://x#Intent;end"))
        assertEquals("whatsapp", externalTargetLabel("whatsapp://send?text=x"))
        assertEquals("unknown", externalTargetLabel(""))
    }

    private companion object {
        /**
         * Mirrors the cap in IntentUrl.kt. Kept as a literal so a change to the production cap
         * fails this test loudly instead of silently agreeing with itself.
         */
        const val MAX_EXTRAS_FOR_TEST = 24
    }
}
