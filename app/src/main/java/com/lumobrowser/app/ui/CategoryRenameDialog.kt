package com.lumobrowser.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.lumobrowser.app.data.CategoryValidator
import com.lumobrowser.app.feedback.LocalLumoFeedback
import com.lumobrowser.app.feedback.LumoDialogCues
import com.lumobrowser.app.ui.components.LumoPrimaryButton
import com.lumobrowser.app.ui.components.LumoTextButton
import com.lumobrowser.app.ui.components.lumoSurface
import com.lumobrowser.app.ui.components.surfaceTreatment
import com.lumobrowser.app.ui.theme.LumoTheme

/**
 * Rename one category in place.
 *
 * The field validates live against [CategoryValidator] — the same rules the ViewModel re-checks
 * before it writes — so what the user sees here is exactly what the database will enforce. Every
 * exit path (cancel, Back, scrim tap) resolves to [onDismiss] without a write. A save that only
 * trims surrounding whitespace to the current name resolves to "no change" and dismisses without
 * writing the row.
 */
@Composable
fun CategoryRenameDialog(
    original: String,
    existing: List<String>,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalLumoStrings.current
    val feedback = LocalLumoFeedback.current
    val theme = LumoTheme.state
    val treatment = lumoSurface()
    val shape = RoundedCornerShape(theme.radius.md)
    val focusRequester = remember { FocusRequester() }

    var value by remember(original) { mutableStateOf(original) }
    var error by remember { mutableStateOf<CategoryValidator.Error?>(null) }
    var saving by remember { mutableStateOf(false) }

    BackHandler(enabled = true) { onDismiss() }
    LumoDialogCues()

    // Auto-focus the field and raise the keyboard once the field is attached.
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    fun errorText(err: CategoryValidator.Error): String = when (err) {
        CategoryValidator.Error.Empty -> strings.categoryRenameEmpty
        CategoryValidator.Error.Duplicate -> strings.categoryRenameDuplicate
        CategoryValidator.Error.TooLong -> strings.categoryRenameTooLong
    }

    fun submit() {
        if (saving) return
        val verdict = CategoryValidator.validate(value, original, existing)
        if (verdict != null) {
            error = verdict
            feedback.error()
            return
        }
        val trimmed = value.trim()
        if (trimmed == original) {
            // No actual change: close without writing the row.
            onDismiss()
            return
        }
        saving = true
        onSave(trimmed)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.categoryRenameTitle) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TestTags.CATEGORY_RENAME_DIALOG),
            ) {
                // Same hand-rolled translucent surface as the editor, not M3's outlined container,
                // so the dialog matches the app's glass styling.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .surfaceTreatment(treatment, shape)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    BasicTextField(
                        value = value,
                        onValueChange = {
                            value = it
                            // Re-arm per keystroke: a "duplicate" verdict must clear the moment the
                            // user edits, or it reads as stuck on a name they already changed.
                            error = null
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(theme.accent),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { submit() },
                            onGo = { submit() },
                            onSend = { submit() },
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .testTag(TestTags.CATEGORY_RENAME_INPUT),
                    )
                }
                if (error != null) {
                    Text(
                        text = errorText(error!!),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .testTag(TestTags.CATEGORY_RENAME_ERROR),
                    )
                }
            }
        },
        confirmButton = {
            LumoPrimaryButton(
                text = strings.save,
                onClick = { submit() },
                enabled = !saving,
                modifier = Modifier.testTag(TestTags.CATEGORY_RENAME_SAVE),
            )
        },
        dismissButton = {
            LumoTextButton(
                text = strings.cancel,
                onClick = onDismiss,
                modifier = Modifier.testTag(TestTags.CATEGORY_RENAME_CANCEL),
            )
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}
