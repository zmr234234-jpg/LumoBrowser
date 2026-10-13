package com.lumobrowser.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.lumobrowser.app.data.Bookmark
import com.lumobrowser.app.data.BrowserMode
import com.lumobrowser.app.data.SiteConfig
import com.lumobrowser.app.data.SiteNetworkAssist
import com.lumobrowser.app.data.UrlTools
import com.lumobrowser.app.ui.components.LumoBackground
import com.lumobrowser.app.ui.components.lumoScreenInsets
import com.lumobrowser.app.ui.components.LumoChip
import com.lumobrowser.app.ui.components.LumoDivider
import com.lumobrowser.app.ui.components.LumoIconButton
import com.lumobrowser.app.ui.components.LumoPrimaryButton
import com.lumobrowser.app.ui.components.LumoTextButton
import com.lumobrowser.app.ui.components.lumoSurface
import com.lumobrowser.app.ui.components.surfaceTreatment
import com.lumobrowser.app.ui.theme.LumoSpacing
import com.lumobrowser.app.ui.theme.LumoTheme

/**
 * Add / edit a bookmark.
 *
 * Validation is deliberately strict about the address and lenient about everything else: the URL has
 * to survive [UrlTools.normalizeOrNull] (http/https, real host), but the title falls back to the
 * domain and description/category are optional. Duplicate detection compares the *normalised* URL so
 * `example.com` and `https://example.com/` cannot become two entries.
 */
@Composable
fun EditorScreen(
    original: Bookmark?,
    existing: List<Bookmark>,
    existingCategories: List<String>,
    onSave: (Bookmark) -> Unit,
    onBack: () -> Unit,
) {
    val s = LocalLumoStrings.current
    val clipboard = LocalClipboardManager.current

    var title by remember(original?.id) { mutableStateOf(original?.title.orEmpty()) }
    var url by remember(original?.id) { mutableStateOf(original?.url.orEmpty()) }
    var description by remember(original?.id) { mutableStateOf(original?.description.orEmpty()) }
    var category by remember(original?.id) { mutableStateOf(original?.category.orEmpty()) }
    var pinned by remember(original?.id) { mutableStateOf(original?.isPinned ?: false) }
    var error by remember(original?.id) { mutableStateOf<String?>(null) }

    // Latched once the form has been handed to the caller. The screen stays composed for the length
    // of the pop animation, so without this a second tap on Save inside that window is a second
    // submission — and a second row in the library.
    var submitted by remember(original?.id) { mutableStateOf(false) }

    // Per-site browser configuration. Keyed on `original?.id` like every other field, so switching
    // to a different bookmark cannot leave the previous site's mode behind in the form.
    var browserMode by remember(original?.id) { mutableStateOf(original?.browserMode ?: BrowserMode.Default) }

    // The per-site network choice. Same reasoning as the mode above: it belongs next to the address
    // it describes, because "this one site keeps failing on mobile data" is a property of that site,
    // not a global preference. `FollowGlobal` is the default, so a bookmark saved before this
    // setting existed keeps behaving exactly as it did.
    var networkAssist by remember(original?.id) {
        mutableStateOf(original?.networkAssist ?: SiteNetworkAssist.Default)
    }

    var advancedOpen by remember(original?.id) { mutableStateOf(false) }
    var javaScript by remember(original?.id) { mutableStateOf(original?.javaScriptEnabled ?: true) }
    var domStorage by remember(original?.id) { mutableStateOf(original?.domStorageEnabled ?: true) }
    var thirdPartyCookies by remember(original?.id) { mutableStateOf(original?.useThirdPartyCookies ?: false) }
    var desktopSite by remember(original?.id) { mutableStateOf(original?.desktopSiteEnabled ?: false) }
    var userAgent by remember(original?.id) { mutableStateOf(original?.userAgentOverride.orEmpty()) }

    val isNew = original == null

    fun submit() {
        val trimmedTitle = title.trim()
        val normalized = UrlTools.normalizeOrNull(url)

        error = when {
            normalized == null -> {
                if (url.isBlank()) s.errorUrlRequired else s.errorUrlInvalid
            }
            existing.any { it.url == normalized && it.id != original?.id } -> s.errorUrlDuplicate
            else -> null
        }

        if (error == null && normalized != null) {
            submitted = true
            onSave(
                Bookmark(
                    id = original?.id ?: 0L,
                    title = trimmedTitle.ifBlank { UrlTools.suggestTitle(normalized) },
                    url = normalized,
                    description = description.trim(),
                    category = category.trim(),
                    isPinned = pinned,
                    sortOrder = original?.sortOrder ?: 0L,
                    createdAt = original?.createdAt ?: System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                    // The mode is stored as part of the site's identity, so it survives the edit.
                    // `lastSuccessfulMode` deliberately carries over rather than resetting: it
                    // records what actually worked on this device, and re-saving the bookmark is
                    // not evidence that it stopped working.
                    browserMode = browserMode,
                    userAgentOverride = userAgent.trim(),
                    javaScriptEnabled = javaScript,
                    domStorageEnabled = domStorage,
                    desktopSiteEnabled = desktopSite,
                    useThirdPartyCookies = thirdPartyCookies,
                    lastSuccessfulMode = original?.lastSuccessfulMode,
                    lastModeChangeTime = original?.lastModeChangeTime ?: 0L,
                    networkAssist = networkAssist,
                )
            )
        }
    }

    // Mounted here as well as at the shell root on purpose: nested mounts collapse to a pass-through
    // (see LumoBackground), so inside the app this costs nothing, while a preview or a test that
    // renders this screen on its own still gets the themed backdrop rather than a blank frame.
    LumoBackground(modifier = Modifier.fillMaxSize().testTag(TestTags.EDITOR_ROOT)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // One inset expression for the whole screen instead of a hand-picked
                // status + navigation + ime stack. `safeDrawing` is the union of those three plus
                // any display cutout, so a notched phone held sideways and an open keyboard are
                // covered by the same call —and there is exactly one place to check when the
                // reported symptom is "the top of the editor is under the clock".
                .lumoScreenInsets()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = LumoSpacing.screenHorizontal),
        ) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                LumoIconButton(
                    icon = LumoIcons.Back,
                    contentDescription = s.cancel,
                    onClick = onBack,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = if (isNew) s.editorNew else s.editorEdit,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            Spacer(Modifier.height(18.dp))

            EditorField(
                label = s.fieldUrl,
                value = url,
                onValueChange = { url = it; error = null },
                placeholder = "https://example.com",
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next,
                fieldModifier = Modifier.testTag(TestTags.EDITOR_URL_INPUT),
                trailing = {
                    LumoIconButton(
                        icon = LumoIcons.Copy,
                        contentDescription = s.paste,
                        onClick = {
                            val text = clipboard.getText()?.text
                            if (!text.isNullOrBlank()) {
                                url = text.trim()
                                error = null
                            }
                        },
                        containerSize = 34.dp,
                        iconSize = 17.dp,
                        modifier = Modifier.testTag(TestTags.EDITOR_PASTE),
                    )
                },
            )

            error?.let { message ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag(TestTags.EDITOR_ERROR),
                )
            }

            Spacer(Modifier.height(14.dp))
            EditorField(
                label = s.fieldTitle,
                value = title,
                onValueChange = { title = it },
                placeholder = UrlTools.suggestTitle(UrlTools.normalize(url)),
                imeAction = ImeAction.Next,
                fieldModifier = Modifier.testTag(TestTags.EDITOR_TITLE_INPUT),
            )

            Spacer(Modifier.height(14.dp))
            EditorField(
                label = s.fieldDescription,
                value = description,
                onValueChange = { description = it },
                placeholder = "",
                singleLine = false,
                imeAction = ImeAction.Default,
                fieldModifier = Modifier.testTag(TestTags.EDITOR_DESCRIPTION_INPUT),
            )

            Spacer(Modifier.height(14.dp))
            EditorField(
                label = s.fieldCategory,
                value = category,
                onValueChange = { category = it },
                placeholder = s.fieldCategoryHint,
                imeAction = ImeAction.Done,
                onSubmit = { submit() },
                fieldModifier = Modifier.testTag(TestTags.EDITOR_CATEGORY_INPUT),
            )

            if (existingCategories.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(existingCategories, key = { it }) { name ->
                        LumoChip(
                            label = name,
                            selected = category == name,
                            onClick = { category = if (category == name) "" else name },
                        )
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            LumoDivider()
            Spacer(Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = s.pin,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                LumoChip(
                    label = if (pinned) s.pin else s.unpin,
                    selected = pinned,
                    onClick = { pinned = !pinned },
                    leading = LumoIcons.Pin,
                )
            }

            Spacer(Modifier.height(18.dp))
            LumoDivider()
            Spacer(Modifier.height(14.dp))

            // Per-site rendering. This is the whole point of the mode being stored on the bookmark
            // rather than in settings: a site that needs the desktop layout needs it here, next to
            // the address it belongs to, not as a global switch the user has to remember to flip
            // back.
            Text(
                text = s.modeTitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(BrowserMode.entries, key = { it.storageName }) { mode ->
                    LumoChip(
                        label = s.modeLabel(mode),
                        selected = browserMode == mode,
                        onClick = { browserMode = mode },
                        modifier = Modifier.testTag(TestTags.EDITOR_MODE_CHIP),
                    )
                }
            }
            Text(
                text = s.modeHint(browserMode),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )

            Spacer(Modifier.height(6.dp))
            LumoTextButton(
                text = s.modeAdvanced,
                onClick = { advancedOpen = !advancedOpen },
                modifier = Modifier.testTag(TestTags.EDITOR_MODE_ADVANCED),
            )

            // Collapsed by default. These switches can break a site as easily as fix one, so they
            // stay behind a disclosure where they cannot be nudged by accident while scrolling to
            // the save button.
            AnimatedVisibility(visible = advancedOpen) {
                Column {
                    EditorSwitchRow(
                        label = s.webJavaScript,
                        checked = javaScript,
                        onCheckedChange = { javaScript = it },
                    )
                    EditorSwitchRow(
                        label = s.webDomStorage,
                        checked = domStorage,
                        onCheckedChange = { domStorage = it },
                    )
                    EditorSwitchRow(
                        label = s.webThirdPartyCookies,
                        checked = thirdPartyCookies,
                        onCheckedChange = { thirdPartyCookies = it },
                    )
                    EditorSwitchRow(
                        label = s.webDesktopSite,
                        checked = desktopSite,
                        onCheckedChange = { desktopSite = it },
                    )
                    Spacer(Modifier.height(4.dp))
                    EditorField(
                        label = s.webUserAgent,
                        value = userAgent,
                        onValueChange = { userAgent = it },
                        placeholder = s.webUserAgentHint,
                        singleLine = true,
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            LumoDivider()
            Spacer(Modifier.height(14.dp))

            // The per-site network choice. Sits below the rendering mode because it answers a
            // different question — "what should happen when *this* site fails to load" — and is
            // deliberately not folded into the mode chips: a site can need the desktop layout and
            // still be perfectly reachable.
            Text(
                text = s.siteNetworkTitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(SiteNetworkAssist.entries, key = { it.storageName }) { option ->
                    LumoChip(
                        label = siteNetworkLabel(option),
                        selected = networkAssist == option,
                        onClick = { networkAssist = option },
                        modifier = Modifier.testTag(TestTags.EDITOR_NETWORK_CHIP),
                    )
                }
            }
            Text(
                text = siteNetworkHint(networkAssist),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )

            Spacer(Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                LumoTextButton(
                    text = s.cancel,
                    onClick = onBack,
                    modifier = Modifier.weight(1f).testTag(TestTags.EDITOR_CANCEL),
                )
                Box(modifier = Modifier.weight(1.4f)) {
                    LumoPrimaryButton(
                        text = s.save,
                        onClick = { submit() },
                        modifier = Modifier.fillMaxWidth().testTag(TestTags.EDITOR_SAVE),
                        leadingIcon = LumoIcons.Check,
                        enabled = !submitted,
                    )
                }
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

/**
 * A labelled on/off row for the advanced web settings.
 *
 * A plain M3 [Switch] rather than the app's own chip: the chip's selected state is a *highlight*,
 * which reads as "this option is the one in use" among alternatives, while these are independent
 * capabilities that are each simply on or off. The switch also carries the platform's own
 * accessibility semantics, which a styled chip would have to re-declare.
 */
@Composable
private fun EditorSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * One labelled field. A hand-rolled BasicTextField rather than OutlinedTextField because the whole
 * app uses the same translucent surface treatment and M3's built-in container/indicator would fight
 * the glass styling.
 */
@Composable
private fun EditorField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Default,
    onSubmit: (() -> Unit)? = null,
    fieldModifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val theme = LumoTheme.state
    val treatment = lumoSurface()
    val shape = RoundedCornerShape(theme.radius.md)

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .surfaceTreatment(treatment, shape)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        singleLine = singleLine,
                        minLines = if (singleLine) 1 else 3,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(theme.accent),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = keyboardType,
                            imeAction = imeAction,
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { onSubmit?.invoke() },
                            onGo = { onSubmit?.invoke() },
                            onSend = { onSubmit?.invoke() },
                        ),
                        modifier = Modifier.fillMaxWidth().then(fieldModifier),
                    )
                }
                trailing?.invoke()
            }
        }
    }
}

/** Padding helper kept next to the editor so screens can share the same content insets. */
internal val EditorContentPadding = PaddingValues(
    start = LumoSpacing.screenHorizontal,
    end = LumoSpacing.screenHorizontal,
    top = 10.dp,
    bottom = 40.dp,
)
