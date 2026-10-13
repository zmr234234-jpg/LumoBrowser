package com.lumobrowser.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.FileProvider
import java.io.File

/**
 * Sharing and clipboard helpers.
 *
 * Nothing here uploads anything: sharing hands the URL to whichever app the user picks, and the
 * clipboard write is local to the device. That is the whole extent of LumoBrowser's outbound
 * behaviour, plus the pages the user explicitly opens.
 */
object ShareUtil {

    /** Opens the system share sheet for a URL. */
    fun shareUrl(context: Context, url: String, title: String? = null) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
            if (!title.isNullOrBlank()) putExtra(Intent.EXTRA_SUBJECT, title)
        }
        val chooser = Intent.createChooser(send, null).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(chooser) }
    }

    // "Open this in another browser" is not here on purpose. It used to be, as a bare ACTION_VIEW
    // that reported nothing back — so the app could not tell the user when no other browser existed,
    // and the same request had two implementations that could disagree about it. It goes through
    // ExternalLauncher, which is the one place a handoff is built and the one place its outcome is
    // known.

    /** Copies a URL to the clipboard (local only). */
    fun copyUrl(context: Context, url: String) {
        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("url", url))
    }
}

/** Result of reading a URL out of the clipboard or a share intent. */
data class ExtractedUrl(val raw: String, val valid: Boolean)

/**
 * Reads the first http/https URL found in arbitrary pasted text.
 *
 * Used so "paste a whole paragraph containing a link" does the obvious thing instead of failing.
 */
fun extractFirstUrl(text: String?): ExtractedUrl? {
    val trimmed = text?.trim().orEmpty()
    if (trimmed.isEmpty()) return null

    val candidate = Regex("https?://\\S+", RegexOption.IGNORE_CASE).find(trimmed)?.value
        ?: Regex("(?:[a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,}(?:/\\S*)?")
            .find(trimmed)?.value
        ?: trimmed

    val cleaned = candidate.trimEnd('.', ',', ')', ']', '"', '\'')
    val normalized = com.lumobrowser.app.data.UrlTools.normalizeOrNull(cleaned)
    return ExtractedUrl(raw = cleaned, valid = normalized != null)
}

private const val FILE_PROVIDER_SUFFIX = ".fileprovider"

/**
 * FileProvider authority for this app, derived from the package name so the debug suffix
 * (`com.lumobrowser.app.debug`) resolves to its own authority without a second manifest entry.
 */
val Context.fileProviderAuthority: String
    get() = packageName + FILE_PROVIDER_SUFFIX

/**
 * Writes text to a user-chosen document (SAF [ActivityResultLauncher] result).
 *
 * Returns `true` on success. The URI is opened through the content resolver with the `"wt"` mode so
 * a previously exported backup is truncated rather than partially overwritten.
 */
fun Context.writeTextToUri(uri: Uri, text: String): Boolean = runCatching {
    contentResolver.openOutputStream(uri, "wt")?.use { stream ->
        stream.write(text.toByteArray(Charsets.UTF_8))
        stream.flush()
    } != null
}.getOrDefault(false)

/** Reads a user-chosen document as UTF-8 text. */
fun Context.readTextFromUri(uri: Uri): String? = runCatching {
    contentResolver.openInputStream(uri)?.use { stream ->
        stream.readBytes().toString(Charsets.UTF_8)
    }
}.getOrNull()

/** Reads the display name of a document URI, for the "restored from X" message. */
fun Context.displayNameOf(uri: Uri): String? = runCatching {
    contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
    }
}.getOrNull()

/** Creates a file in this app's cache dir and exposes it through the manifest FileProvider. */
fun Context.cacheFileUri(fileName: String, mimeType: String, text: String): Uri? = runCatching {
    val dir = File(cacheDir, "share").apply { mkdirs() }
    val file = File(dir, fileName).apply { writeText(text, Charsets.UTF_8) }
    FileProvider.getUriForFile(this, fileProviderAuthority, file, mimeType)
}.getOrNull()
