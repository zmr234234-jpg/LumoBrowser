package com.lumobrowser.app.browser

import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

/**
 * Renders the load-failure screen inside the WebView.
 *
 * Why not a native Compose error screen: the failure has to be able to show the *site's* own
 * error page when the server answered (a 404 body, a maintenance page), and it must preserve the
 * history position the user came from. An overlay inside the page keeps the back stack intact and
 * avoids pushing a fake `about:blank` entry.
 *
 * Why not a `data:` or `file:` URL: `normalizeOrNull` refuses both schemes on principle, and this
 * class never wants to be the one exception in the codebase. The overlay is injected into the
 * document that is already there.
 *
 * The injected markup is static, self-contained and offline-safe (no external fonts, images,
 * scripts or network requests), styled to match the app's dark tech theme. It talks back through
 * `location.href = "lumo-error://retry"`, which [LumoWebView]'s client recognises — no JS bridge
 * surface is added for it.
 */
object LumoErrorPage {

    /** Deep-link scheme the overlay uses for its buttons; intercepted in [BrowserScreen]. */
    const val SCHEME = "lumo-error"
    const val HOST_RETRY = "retry"
    const val HOST_EXTERNAL = "external"
    const val HOST_DISMISS = "dismiss"

    /** Idempotently installs the overlay element and its styles into the current document. */
    fun injectTemplate(webView: WebView) {
        runCatching { webView.evaluateJavascript(TEMPLATE_JS, null) }
    }

    /** Fills the overlay with [error]'s details and reveals it. */
    fun show(webView: WebView, error: BrowserError) {
        val payload = JSONObject().apply {
            put("code", error.code)
            put("description", error.description)
            put("url", error.url ?: "")
            put("retryable", error.retryable)
        }.toString()
        runCatching {
            webView.evaluateJavascript("window.__lumoShowError && window.__lumoShowError($payload);", null)
        }
    }

    /** Hides the overlay (used when the user dismisses it to look at the page underneath). */
    fun hide(webView: WebView) {
        runCatching {
            webView.evaluateJavascript("window.__lumoShowError && window.__lumoShowError(null);", null)
        }
    }

    /**
     * User-facing copy for an [BrowserError], returned as string resource ids resolved by the
     * caller. Kept as a pure function so it can be unit tested without an Android device.
     */
    fun messageKey(code: Int): ErrorMessageKey = when (code) {
        WebViewClient.ERROR_HOST_LOOKUP -> ErrorMessageKey.HostLookup
        WebViewClient.ERROR_CONNECT -> ErrorMessageKey.CannotConnect
        WebViewClient.ERROR_TIMEOUT -> ErrorMessageKey.Timeout
        WebViewClient.ERROR_FAILED_SSL_HANDSHAKE -> ErrorMessageKey.Certificate
        WebViewClient.ERROR_REDIRECT_LOOP -> ErrorMessageKey.RedirectLoop
        WebViewClient.ERROR_UNSUPPORTED_SCHEME -> ErrorMessageKey.UnsupportedScheme
        WebViewClient.ERROR_PROXY_AUTHENTICATION -> ErrorMessageKey.Proxy
        WebViewClient.ERROR_FILE_NOT_FOUND,
        WebViewClient.ERROR_FILE,
        -> ErrorMessageKey.NotFound
        else -> ErrorMessageKey.Generic
    }

    /** Message identifiers; the UI layer maps these onto localized string resources. */
    enum class ErrorMessageKey {
        HostLookup,
        CannotConnect,
        Timeout,
        Certificate,
        RedirectLoop,
        UnsupportedScheme,
        Proxy,
        NotFound,
        Generic,
    }

    private const val TEMPLATE_JS = """
(function() {
  // Immediately-invoked and idempotent: re-injection after a navigation must not stack overlays.
  if (window.__lumoShowError) { window.__lumoShowError(null); return; }

  var STYLE_ID = 'lumo-error-style';
  var ROOT_ID = 'lumo-error-root';

  if (!document.getElementById(STYLE_ID)) {
    var style = document.createElement('style');
    style.id = STYLE_ID;
    style.textContent = [
      '#' + ROOT_ID + ' {',
      '  position: fixed; inset: 0; z-index: 2147483647;',
      '  display: none; align-items: center; justify-content: center;',
      '  background: radial-gradient(120% 90% at 50% 0%, #101a2e 0%, #06070e 55%, #04050a 100%);',
      '  color: #e8edf7; font-family: -apple-system, "Segoe UI", Roboto, sans-serif;',
      '  padding: 28px 22px; box-sizing: border-box; -webkit-text-size-adjust: 100%;',
      '}',
      '#' + ROOT_ID + '.lumo-error-visible { display: flex; }',
      '#' + ROOT_ID + ' .lumo-error-card {',
      '  width: 100%; max-width: 460px; text-align: center;',
      '  border: 1px solid rgba(255,255,255,0.10); border-radius: 20px;',
      '  background: rgba(255,255,255,0.045); padding: 26px 20px 22px;',
      '  backdrop-filter: blur(14px); -webkit-backdrop-filter: blur(14px);',
      '}',
      '#' + ROOT_ID + ' .lumo-error-badge {',
      '  width: 52px; height: 52px; margin: 0 auto 16px; border-radius: 50%;',
      '  display: flex; align-items: center; justify-content: center;',
      '  background: rgba(255,120,120,0.14); border: 1px solid rgba(255,120,120,0.32);',
      '  font-size: 24px; line-height: 1;',
      '}',
      '#' + ROOT_ID + ' h1 { font-size: 17px; font-weight: 600; margin: 0 0 8px; letter-spacing: 0.2px; }',
      '#' + ROOT_ID + ' p { font-size: 13px; line-height: 1.6; margin: 0 0 6px; color: rgba(232,237,247,0.72); }',
      '#' + ROOT_ID + ' .lumo-error-url {',
      '  font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 11px;',
      '  color: rgba(232,237,247,0.52); word-break: break-all; margin: 10px 0 18px;',
      '}',
      '#' + ROOT_ID + ' .lumo-error-actions { display: flex; flex-direction: column; gap: 9px; }',
      '#' + ROOT_ID + ' button {',
      '  font: inherit; font-size: 14px; font-weight: 500; padding: 12px 16px;',
      '  border-radius: 12px; border: 1px solid rgba(255,255,255,0.12);',
      '  background: rgba(255,255,255,0.06); color: #e8edf7; cursor: pointer;',
      '  -webkit-tap-highlight-color: transparent;',
      '}',
      '#' + ROOT_ID + ' button.lumo-error-primary {',
      '  background: rgba(64,224,232,0.18); border-color: rgba(64,224,232,0.45); color: #7ff0f5;',
      '}',
      '#' + ROOT_ID + ' button:active { transform: scale(0.985); }',
      '@media (prefers-color-scheme: light) {',
      '  #' + ROOT_ID + ' { background: radial-gradient(120% 90% at 50% 0%, #eef3fb 0%, #f7f9fd 60%, #ffffff 100%); color: #16203a; }',
      '  #' + ROOT_ID + ' p { color: rgba(22,32,58,0.68); }',
      '  #' + ROOT_ID + ' .lumo-error-url { color: rgba(22,32,58,0.5); }',
      '  #' + ROOT_ID + ' button { background: rgba(22,32,58,0.05); border-color: rgba(22,32,58,0.12); color: #16203a; }',
      '  #' + ROOT_ID + ' button.lumo-error-primary { background: rgba(0,120,150,0.12); border-color: rgba(0,120,150,0.35); color: #00566b; }',
      '}'
    ].join('\n');
    (document.head || document.documentElement).appendChild(style);
  }

  var root = document.getElementById(ROOT_ID);
  if (!root) {
    root = document.createElement('div');
    root.id = ROOT_ID;
    root.setAttribute('role', 'alertdialog');
    root.innerHTML = [
      '<div class="lumo-error-card">',
      '  <div class="lumo-error-badge">!</div>',
      '  <h1 id="lumo-error-title"></h1>',
      '  <p id="lumo-error-desc"></p>',
      '  <div class="lumo-error-url" id="lumo-error-url"></div>',
      '  <div class="lumo-error-actions">',
      '    <button class="lumo-error-primary" id="lumo-error-retry"></button>',
      '    <button id="lumo-error-external"></button>',
      '    <button id="lumo-error-dismiss"></button>',
      '  </div>',
      '</div>'
    ].join('');
    (document.body || document.documentElement).appendChild(root);

    // Buttons report through a private scheme the native client intercepts, so no extra JS
    // bridge surface is exposed to the page.
    var go = function(host) { location.href = '${SCHEME}://' + host; };
    root.querySelector('#lumo-error-retry').addEventListener('click', function() { go('$HOST_RETRY'); });
    root.querySelector('#lumo-error-external').addEventListener('click', function() { go('$HOST_EXTERNAL'); });
    root.querySelector('#lumo-error-dismiss').addEventListener('click', function() { go('$HOST_DISMISS'); });
  }

  var TEXT = {
    hostLookup: ['Site not found', 'The domain name could not be resolved. Check the spelling or your network connection.'],
    connect: ['Cannot reach the site', 'The connection was refused or dropped before the page could load.'],
    timeout: ['The site took too long', 'The server did not respond in time. It may be busy or your connection may be slow.'],
    certificate: ['Connection not private', 'The site presented a certificate this device does not trust. LumoBrowser will not ignore certificate errors, so the page was blocked.'],
    redirect: ['Too many redirects', 'The site kept redirecting and the request was stopped to avoid a loop.'],
    scheme: ['Unsupported address', 'LumoBrowser opens web addresses (http and https) only. Other link types are blocked for safety.'],
    proxy: ['Proxy authentication required', 'A network proxy is blocking the request.'],
    notFound: ['Page not found', 'The address could not be loaded. It may have been moved or removed.'],
    generic: ['Page could not be loaded', 'Something went wrong while loading this page.']
  };

  function copyFor(code) {
    switch (code) {
      case -2: return TEXT.hostLookup;      // ERROR_HOST_LOOKUP
      case -6: return TEXT.connect;         // ERROR_CONNECT
      case -8: return TEXT.timeout;         // ERROR_TIMEOUT
      case -11: return TEXT.certificate;    // ERROR_FAILED_SSL_HANDSHAKE
      case -9: return TEXT.redirect;        // ERROR_REDIRECT_LOOP
      case -10: return TEXT.scheme;         // ERROR_UNSUPPORTED_SCHEME
      case -5: return TEXT.proxy;           // ERROR_PROXY_AUTHENTICATION
      case -13: case -14: return TEXT.notFound; // ERROR_FILE_NOT_FOUND / ERROR_FILE
      default: return TEXT.generic;
    }
  }

  window.__lumoShowError = function(detail) {
    var node = document.getElementById(ROOT_ID);
    if (!node) { return; }
    if (!detail) {
      node.classList.remove('lumo-error-visible');
      return;
    }
    var copy = copyFor(detail.code);
    node.querySelector('#lumo-error-title').textContent = copy[0];
    var desc = copy[1];
    if (detail.description) { desc += ' (' + detail.description + ')'; }
    node.querySelector('#lumo-error-desc').textContent = desc;
    node.querySelector('#lumo-error-url').textContent = detail.url || '';
    var retry = node.querySelector('#lumo-error-retry');
    retry.textContent = 'Try again';
    retry.style.display = detail.retryable ? 'block' : 'none';
    node.querySelector('#lumo-error-external').textContent = 'Open in another browser';
    node.querySelector('#lumo-error-dismiss').textContent = 'Dismiss';
    node.classList.add('lumo-error-visible');
  };
})();
"""
}
