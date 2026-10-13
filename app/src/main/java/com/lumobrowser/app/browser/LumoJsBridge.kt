package com.lumobrowser.app.browser

/**
 * The only JavaScript interface the app exposes to a page.
 *
 * Minimal-permission principle (product brief requirement 7): the bridge is attached to the
 * default WebView *view* only, never to iframes (`addJavascriptInterface` on a view exposes the
 * object to every frame of that view's page, so pages are additionally constrained by the fact
 * that the interface carries no getters, no file access and no callbacks into privileged APIs).
 * It exposes exactly one void method that reports whether a vibrate request arrived, so a page
 * can never read user data, trigger navigation, or invoke app internals.
 *
 * The injected object carries no `@JavascriptInterface` methods that return data, and all
 * arguments are treated as untrusted strings.
 */
class LumoJsBridge(private val onVibrateRequest: () -> Unit) {

    /**
     * Called by pages that use the legacy `navigator.vibrate` shim. The app has already declared
     * the VIBRATE permission and honours the user's haptic switch, so this request is only a hint.
     */
    @android.webkit.JavascriptInterface
    fun vibrate() {
        onVibrateRequest()
    }

    /**
     * Version handshake so a page can detect the host without probing for capabilities.
     * Returns the app version string only — no device identifiers, no user data, and no way for a
     * page to learn anything about the device beyond "a LumoBrowser of this version rendered me".
     */
    @android.webkit.JavascriptInterface
    fun hostVersion(): String = LUMO_HOST_VERSION

    companion object {
        /** Name used in `addJavascriptInterface`; also referenced by injected CSS/JS shims. */
        const val NAME = "LumoHost"
    }
}
