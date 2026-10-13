package com.lumobrowser.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the URL gate. This is the security-critical piece of the app: everything that
 * reaches the WebView passes through [UrlTools.normalizeOrNull] first, so a regression here means
 * a page could hand us a `javascript:` / `intent:` / `file:` URL.
 */
class UrlToolsTest {

    @Test
    fun acceptsPlainHttpsUrl() {
        assertEquals("https://example.com/", UrlTools.normalizeOrNull("https://example.com/"))
    }

    @Test
    fun acceptsHttpUrl() {
        assertEquals("http://example.com/", UrlTools.normalizeOrNull("http://example.com/"))
    }

    @Test
    fun addsHttpsSchemeWhenMissing() {
        // A URL typed as a bare host has no path, so the normalised form has no trailing slash.
        assertEquals("https://example.com", UrlTools.normalizeOrNull("example.com"))
    }

    @Test
    fun addsHttpsSchemeForProtocolRelativeUrl() {
        assertEquals("https://example.com", UrlTools.normalizeOrNull("//example.com"))
    }

    @Test
    fun trimsSurroundingWhitespace() {
        assertEquals("https://example.com/", UrlTools.normalizeOrNull("  https://example.com/  "))
    }

    @Test
    fun keepsPathQueryAndFragment() {
        assertEquals(
            "https://example.com/a/b?c=d#e",
            UrlTools.normalizeOrNull("https://example.com/a/b?c=d#e"),
        )
    }

    @Test
    fun keepsExplicitPort() {
        assertEquals("https://example.com:8443/x", UrlTools.normalizeOrNull("https://example.com:8443/x"))
    }

    @Test
    fun rejectsJavascriptScheme() {
        assertNull(UrlTools.normalizeOrNull("javascript:alert(1)"))
    }

    @Test
    fun rejectsJavascriptSchemeWithMixedCase() {
        assertNull(UrlTools.normalizeOrNull("JaVaScRiPt:alert(1)"))
    }

    @Test
    fun rejectsDataScheme() {
        assertNull(UrlTools.normalizeOrNull("data:text/html,<h1>hi</h1>"))
    }

    @Test
    fun rejectsFileScheme() {
        assertNull(UrlTools.normalizeOrNull("file:///etc/passwd"))
    }

    @Test
    fun rejectsIntentScheme() {
        assertNull(UrlTools.normalizeOrNull("intent://scan/#Intent;scheme=zxing;end"))
    }

    @Test
    fun rejectsContentScheme() {
        assertNull(UrlTools.normalizeOrNull("content://com.android.contacts/data/1"))
    }

    @Test
    fun rejectsBlankInput() {
        assertNull(UrlTools.normalizeOrNull(""))
        assertNull(UrlTools.normalizeOrNull("   "))
    }

    @Test
    fun rejectsHostWithoutDot() {
        // "localhost" and bare words are not reachable sites for this app and are rejected so a
        // typo cannot silently become a search that leaks the text to a remote host.
        assertNull(UrlTools.normalizeOrNull("https://localhost/"))
        assertNull(UrlTools.normalizeOrNull("not a url"))
    }

    @Test
    fun rejectsInputWithEmbeddedWhitespace() {
        assertNull(UrlTools.normalizeOrNull("https://exa mple.com/"))
    }

    @Test
    fun normalizeFallsBackToTrimmedInput() {
        // The editor shows this string back to the user, so it must not be lost.
        assertEquals("javascript:alert(1)", UrlTools.normalize("javascript:alert(1)"))
    }

    @Test
    fun isValidMirrorsNormalizeOrNull() {
        assertTrue(UrlTools.isValid("example.com"))
        assertFalse(UrlTools.isValid("javascript:void(0)"))
    }

    @Test
    fun isBrowsableOnlyAllowsHttpAndHttps() {
        assertTrue(UrlTools.isBrowsable("https://example.com/"))
        assertTrue(UrlTools.isBrowsable("http://example.com/"))
        assertFalse(UrlTools.isBrowsable("mailto:someone@example.com"))
        assertFalse(UrlTools.isBrowsable("tel:+1234567890"))
        assertFalse(UrlTools.isBrowsable("about:blank"))
    }

    @Test
    fun displayDomainStripsWwwPrefix() {
        assertEquals("example.com", UrlTools.displayDomain("https://www.example.com/page"))
        assertEquals("news.example.com", UrlTools.displayDomain("https://news.example.com/"))
    }

    @Test
    fun displayDomainFallsBackForUnparsableInput() {
        assertEquals("example.com", UrlTools.displayDomain("example.com"))
    }

    @Test
    fun suggestTitleUsesHostLabel() {
        assertEquals("Example", UrlTools.suggestTitle("https://example.com/x"))
        assertEquals("News", UrlTools.suggestTitle("https://news.example.com/"))
    }

    @Test
    fun lastPathSegmentDecodesEscapes() {
        assertEquals("hello world", UrlTools.lastPathSegment("https://example.com/hello%20world"))
        assertNull(UrlTools.lastPathSegment("https://example.com/"))
        assertNull(UrlTools.lastPathSegment("https://example.com"))
    }

    @Test
    fun keepsInternationalisedHostsInPunycode() {
        // java.net.URI rejects non-ASCII authorities outright, so the host is converted to ACE
        // form: the bookmark still resolves, and nothing is silently dropped on import.
        assertEquals("https://xn--fsqu00a.xn--0zwm56d/page", UrlTools.normalizeOrNull("https://例子.测试/page"))
        assertEquals("https://xn--fsqu00a.xn--0zwm56d", UrlTools.normalizeOrNull("例子.测试"))
        // A non-ASCII path is preserved as sent; only the authority is IDN-encoded.
        assertEquals("https://example.com/路径", UrlTools.normalizeOrNull("https://example.com/路径"))
    }
}
