package com.lumobrowser.app.data

import com.lumobrowser.app.util.Json
import com.lumobrowser.app.util.JsonParseException
import com.lumobrowser.app.util.JsonValue

/**
 * Bookmark backup / restore.
 *
 * Format: UTF-8 JSON, versioned. Nothing here touches the network — the user picks a
 * destination document through the Storage Access Framework and LumoBrowser writes exactly
 * that file, which is the "explicit user action" required before any data leaves the DB.
 */
object BackupManager {

    const val SCHEMA_VERSION = 1
    const val MIME_TYPE = "application/json"

    class RestoreResult(
        val bookmarks: List<Bookmark>,
        val skipped: Int,
    ) {
        val imported: Int get() = bookmarks.size
    }

    fun export(bookmarks: List<Bookmark>, exportedAt: Long = System.currentTimeMillis()): String {
        val array = JsonValue.JsonArray(
            bookmarks.map { bookmark ->
                JsonValue.JsonObject(
                    linkedMapOf(
                        "title" to JsonValue.JsonString(bookmark.title),
                        "url" to JsonValue.JsonString(bookmark.url),
                        "description" to JsonValue.JsonString(bookmark.description),
                        "category" to JsonValue.JsonString(bookmark.category),
                        "pinned" to JsonValue.JsonBool(bookmark.isPinned),
                    )
                )
            }
        )
        val root = JsonValue.JsonObject(
            linkedMapOf(
                "schema" to JsonValue.JsonString("lumobrowser.backup"),
                "version" to JsonValue.JsonNumber(SCHEMA_VERSION.toDouble()),
                "exportedAt" to JsonValue.JsonNumber(exportedAt.toDouble()),
                "bookmarks" to array,
            )
        )
        return Json.write(root)
    }

    /**
     * Parses a backup document. Entries with an unusable URL are skipped and counted rather
     * than aborting the whole restore, so one bad row cannot cost the user their data.
     */
    fun parse(text: String): RestoreResult {
        val root = runCatching { Json.parse(text) }.getOrElse { error ->
            throw JsonParseException(error.message ?: "Not valid JSON")
        }
        val objectRoot = root.asObjectOrNull() ?: throw JsonParseException("Backup root must be an object")

        val schema = objectRoot["schema"]?.asStringOrNull()
        if (schema != null && schema != "lumobrowser.backup") {
            throw JsonParseException("Unknown backup schema '$schema'")
        }
        val version = objectRoot["version"]?.asLongOrNull() ?: SCHEMA_VERSION.toLong()
        if (version > SCHEMA_VERSION) {
            throw JsonParseException("Backup version $version is newer than this app supports ($SCHEMA_VERSION)")
        }

        val items = objectRoot["bookmarks"]?.asArrayOrNull()
            ?: throw JsonParseException("Backup has no 'bookmarks' array")

        var skipped = 0
        val parsed = ArrayList<Bookmark>(items.items.size)
        items.items.forEach { item ->
            val obj = item.asObjectOrNull()
            if (obj == null) {
                skipped++
                return@forEach
            }
            val url = UrlTools.normalizeOrNull(obj["url"]?.asStringOrNull())
            if (url == null) {
                skipped++
                return@forEach
            }
            val rawTitle = obj["title"]?.asStringOrNull()?.trim().orEmpty()
            parsed += Bookmark(
                title = rawTitle.ifBlank { UrlTools.suggestTitle(url) },
                url = url,
                description = obj["description"]?.asStringOrNull().orEmpty(),
                category = obj["category"]?.asStringOrNull().orEmpty(),
                isPinned = obj["pinned"]?.asBooleanOrNull() ?: false,
            )
        }
        return RestoreResult(bookmarks = parsed, skipped = skipped)
    }

    /** Suggested file name for a new export, e.g. `lumobrowser-backup-20250518-1432.json`. */
    fun suggestedFileName(timestamp: Long = System.currentTimeMillis()): String {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
            .format(java.util.Date(timestamp))
        return "lumobrowser-backup-$stamp.json"
    }
}
