package com.lumobrowser.app.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The single place that decides which system insets a Lumo screen honours.
 *
 * The 1.02 brief asks for one consistent insets strategy instead of per-screen improvisation, and
 * that is the whole reason this object exists. Before it, each screen chose its own combination of
 * `statusBarsPadding()` / `navigationBarsPadding()` / nothing at all — and the home screen chose
 * *nothing*, which is why the collection header and its two top-right buttons were painted straight
 * underneath the clock and the battery icon.
 *
 * Three rules, applied everywhere:
 *
 *  1. **Content is inset, decoration is not.** The backdrop stays full-bleed (a status-bar strip in
 *     a different colour from the rest of the page is exactly the "top of the layout looks broken"
 *     artefact the brief forbids); only the content inside it is pushed clear of the bars.
 *  2. **`safeDrawing`, not `systemBars`.** `safeDrawing` is `systemBars + displayCutout + ime`, so a
 *     notch/notch-hole screen and an open soft keyboard are handled by the same expression rather
 *     than by two that can disagree. On a device with no cutout it is identical to `systemBars`.
 *  3. **One modifier per screen.** [lumoScreenInsets] applies its insets once, at the outer edge of
 *     the screen's scroll container. Nesting it (or pairing it with a bare `statusBarsPadding()`)
 *     would add the status-bar height twice and produce the "huge blank strip at the top" artefact.
 */

/** Status bar + navigation bar + display cutout + IME, as one value. */
val LumoInsets: WindowInsets
    @Composable get() = WindowInsets.safeDrawing

/** The same set, vertical sides only — for a full-bleed surface that owns its own horizontal edges. */
val LumoVerticalInsets: WindowInsets
    @Composable get() = WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical)

/** Top edge only: status bar plus any cutout behind it. */
val LumoTopInsets: WindowInsets
    @Composable get() = WindowInsets.safeDrawing.only(WindowInsetsSides.Top)

/** Bottom edge only: navigation bar (gesture pill or three-button row) plus the IME. */
val LumoBottomInsets: WindowInsets
    @Composable get() = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)

/**
 * The system bars *without* the IME.
 *
 * Only for the browser: a `WebView` that resizes itself when the keyboard opens is the platform's
 * own behaviour and it is the right one, but the browser chrome is positioned against the bars, so
 * measuring it against `safeDrawing` would make the toolbar jump every time a page focuses a field.
 */
val LumoSystemBars: WindowInsets
    @Composable get() = WindowInsets.systemBars

/** The status bar alone — for the few surfaces that are pinned to the top edge and nothing else. */
val LumoStatusBar: WindowInsets
    @Composable get() = WindowInsets.statusBars

/**
 * Pushes a screen's content clear of every system edge it must avoid.
 *
 * Use on the outermost container *inside* the background layer. Screens that need their own
 * horizontal padding should chain `.padding(horizontal = …)` **after** this, never before.
 */
@Composable
fun Modifier.lumoScreenInsets(): Modifier = windowInsetsPadding(LumoInsets)

/** As [lumoScreenInsets], but leaves the bottom edge to the caller (a list that scrolls under it). */
@Composable
fun Modifier.lumoTopInsets(): Modifier = windowInsetsPadding(LumoTopInsets)

/** As [lumoScreenInsets], but leaves the top edge to the caller. */
@Composable
fun Modifier.lumoBottomInsets(): Modifier = windowInsetsPadding(LumoBottomInsets)

/** Vertical system edges only — the shape a full-bleed surface like the browser page needs. */
@Composable
fun Modifier.lumoVerticalInsets(): Modifier = windowInsetsPadding(LumoVerticalInsets)

/** The status bar alone, for a control pinned to the top edge over full-bleed content. */
@Composable
fun Modifier.lumoStatusBarInset(): Modifier = windowInsetsPadding(LumoStatusBar)
