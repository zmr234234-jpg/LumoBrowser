package com.lumobrowser.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lumobrowser.app.browser.ExternalLinkOffer
import com.lumobrowser.app.ui.components.LumoPrimaryButton
import com.lumobrowser.app.ui.components.LumoTextButton
import com.lumobrowser.app.ui.components.WeightedSpacer
import com.lumobrowser.app.ui.components.lumoBottomInsets
import com.lumobrowser.app.ui.components.lumoSurface
import com.lumobrowser.app.ui.components.surfaceTreatment
import com.lumobrowser.app.ui.theme.LumoTheme

/**
 * The bottom sheet that asks before an address leaves LumoBrowser for another application.
 *
 * ## Why this is not optional
 *
 * Before 1.04 a page could put a `tel:`, `market:` or `intent://` link in front of the user and the
 * browser would either refuse it with an error page or, for the actions it already handled, start
 * another app with no warning at all. Neither is right. A browser
 * that silently launches other applications on a page's instruction is handing control of the
 * device to the page; a browser that refuses flatly breaks ordinary links.
 *
 * So the handoff is confirmed, and the confirmation panel is the *only* place a page can cause a
 * launch — [com.lumobrowser.app.browser.LumoWebView] reports the request and does nothing else.
 *
 * ## What the panel is careful to show
 *
 * The address is displayed verbatim rather than prettified, because the user is approving exactly
 * the string that will be sent. The subject (number, address, package) is shown above it in larger
 * type because that is what a person actually reads. The confirm button is gated on the address
 * being *usable*, not on this app having found a handler for it: the lookup is a preview that reads
 * the package manager once, and on Android 11+ it only sees what the manifest's `<queries>` makes
 * visible, so a wrong "nothing accepts this" would make a working link permanently untappable. The
 * real answer is reported after the tap instead. And when the address cannot be turned into an
 * intent at all — a malformed `intent://`, which is precisely the shape an intent-redirection
 * attempt takes — the panel offers only copy.
 */
@Composable
fun ExternalLinkSheet(
    /** The pending handoff, or null when there is nothing to confirm. */
    offer: ExternalLinkOffer?,
    /** Name of the single app that would open it; null when none can or several could. */
    handlerLabel: String?,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onCopy: () -> Unit,
    onOpenFallback: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val visible = offer != null

    // The exit animation needs the offer that is going away, and by then the caller has already
    // cleared it. Keeping the last non-null value means the panel slides out showing real content
    // instead of collapsing to an empty card.
    var shown by remember { mutableStateOf<ExternalLinkOffer?>(null) }
    if (offer != null) shown = offer
    val current = shown ?: return

    val s = LocalLumoStrings.current
    val theme = LumoTheme.state
    val motion = LumoTheme.motion

    val panelShape = RoundedCornerShape(theme.radius.lg)
    val enterMs = motion.duration(220)
    val exitMs = motion.duration(160)

    Box(modifier = modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(enterMs)),
            exit = fadeOut(tween(exitMs)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f))
                    // No ripple: the scrim is a backdrop, and a ripple spreading across the whole
                    // page reads as the page itself reacting rather than the panel.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onCancel() },
            )
        }

        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(tween(enterMs)) { height -> height } + fadeIn(tween(enterMs)),
            exit = slideOutVertically(tween(exitMs)) { height -> height } + fadeOut(tween(exitMs)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                modifier = Modifier
                    .lumoBottomInsets()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .fillMaxWidth()
                    .testTag(TestTags.EXTERNAL_LINK_SHEET)
                    .surfaceTreatment(lumoSurface(elevated = true), panelShape)
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = s.externalLinkTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Text(
                    text = s.externalLinkBody,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .surfaceTreatment(lumoSurface(), RoundedCornerShape(theme.radius.md))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = s.externalLinkAddressLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (current.summary.isNotBlank()) {
                        Text(
                            text = current.summary,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = current.url,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag(TestTags.EXTERNAL_LINK_ADDRESS),
                    )
                }

                // Never "nothing accepts this". A package-manager lookup is the only way to know
                // that, and on Android 11+ it is filtered by what the manifest's <queries> makes
                // visible, so its silence is not evidence of absence. Claiming it would also
                // contradict the confirm button right below, which is deliberately left working.
                // A named app when exactly one is visible, otherwise the honest "an app you choose",
                // and the real answer comes back from the attempt itself.
                val handlerText = when {
                    !current.actionable -> s.externalLinkUnusable
                    handlerLabel != null -> handlerLabel
                    else -> s.externalLinkAppChooser
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = s.externalLinkAppLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "  ",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Text(
                        text = handlerText,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (current.actionable) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag(TestTags.EXTERNAL_LINK_HANDLER),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LumoTextButton(
                        text = s.externalLinkCopy,
                        onClick = onCopy,
                        modifier = Modifier.testTag(TestTags.EXTERNAL_LINK_COPY),
                    )
                    // The page's own fallback is offered as an in-app navigation and never followed
                    // automatically: following it silently would let the page choose the address the
                    // user ends up at, which is the thing confirming a handoff is meant to prevent.
                    val fallback = current.fallbackUrl
                    if (!fallback.isNullOrBlank()) {
                        LumoTextButton(
                            text = s.externalLinkFallback,
                            onClick = { onOpenFallback(fallback) },
                            modifier = Modifier.testTag(TestTags.EXTERNAL_LINK_FALLBACK),
                        )
                    }
                    WeightedSpacer()
                    LumoTextButton(
                        text = s.externalLinkCancel,
                        onClick = onCancel,
                        modifier = Modifier.testTag(TestTags.EXTERNAL_LINK_CANCEL),
                    )
                    LumoPrimaryButton(
                        text = s.externalLinkConfirm,
                        onClick = onConfirm,
                        // Gated on the address being usable, not on this app having found a handler.
                        // The lookup is a preview: it reads the package manager once, and on
                        // Android 11+ it can only see what the manifest's <queries> makes visible.
                        // A wrong "nothing accepts this" would otherwise make a working link
                        // permanently untappable, so the tap stays allowed and the real answer is
                        // reported after it.
                        enabled = current.actionable,
                        modifier = Modifier.testTag(TestTags.EXTERNAL_LINK_CONFIRM),
                    )
                }
            }
        }
    }
}
