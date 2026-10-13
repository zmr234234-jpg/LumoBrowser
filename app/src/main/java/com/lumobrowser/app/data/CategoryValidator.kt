package com.lumobrowser.app.data

/**
 * Validation rules for renaming a category, shared by the rename dialog (live, inline feedback) and
 * the ViewModel (defensive re-check before the write).
 *
 * A category has no id of its own — it is the `category` string on its bookmarks — so "duplicate"
 * means the trimmed new name already exists as a *different* category. The original category is
 * exempt, which is what lets a rename that only changes surrounding whitespace resolve to "no change".
 */
object CategoryValidator {

    /**
     * Maximum length in Unicode code points (not UTF-16 chars), so a 4-byte emoji counts as one
     * character instead of two. 20 keeps the chip and the rail label readable while still allowing
     * the usual Chinese / English / numeric / symbol / emoji names.
     */
    const val MAX_LENGTH = 20

    sealed interface Error {
        data object Empty : Error
        data object Duplicate : Error
        data object TooLong : Error
    }

    /**
     * @param raw the text as typed (not yet trimmed; surrounding whitespace is stripped, not rejected).
     * @param original the category's current stored name, so a no-op rename is allowed.
     * @param existing every category currently in the library.
     */
    fun validate(raw: String, original: String, existing: List<String>): Error? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return Error.Empty
        val length = trimmed.codePointCount(0, trimmed.length)
        if (length > MAX_LENGTH) return Error.TooLong
        if (trimmed != original && existing.any { it == trimmed }) return Error.Duplicate
        return null
    }
}
