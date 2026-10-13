package com.lumobrowser.app.browser.adblock

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * What is known about a rule list that was downloaded, as opposed to the one in the APK.
 *
 * Every field is recorded at download time from the bytes that were actually stored, so the settings
 * screen can describe the list that is in use instead of describing the fact that a download was
 * attempted once.
 */
data class RuleUpdateInfo(
    val sourceUrl: String,
    /** When this payload was downloaded. Zero for the bundled list, which has no download time. */
    val fetchedAtMillis: Long,
    /** SHA-256 of the stored payload, so a re-download of the same list can be detected. */
    val sha256: String,
    val hostRules: Int,
    val pathRules: Int,
    val exceptions: Int,
    val bytes: Int,
    /** How many entries the download itself contributed on top of the bundled list. */
    val addedHosts: Int,
) {
    /** The first eight hex characters of the digest, which is what a person can compare by eye. */
    val shortHash: String get() = sha256.take(8)
}

/**
 * Where a downloaded rule list may be kept.
 *
 * An interface so the updater can be tested without a filesystem or a `Context`: everything the
 * updater does with the outside world goes through here or through its fetch function.
 */
interface AdBlockRuleSink {

    /** The metadata of the list currently stored, or null when there is none. */
    suspend fun previousInfo(): RuleUpdateInfo?

    /** Stores [text] and its metadata. Must not leave a half-written file behind on failure. */
    suspend fun write(text: String, info: RuleUpdateInfo)
}

/**
 * The downloaded rule list on disk, plus its metadata.
 *
 * Two deliberate choices:
 *
 *  * **The file is written by rename.** A download that is interrupted, or a process that dies
 *    mid-write, must not leave a truncated list where the filter reads from; the temporary file is
 *    renamed over the real one, which is atomic on the filesystems Android uses.
 *  * **The metadata lives in `SharedPreferences` with `commit()`.** It is a few dozen bytes written
 *    once per download, it must be readable during app startup before any coroutine runs, and
 *    `commit()` returning means it is on disk. This is not the settings store: nothing here is a user
 *    preference, and losing it costs a re-download, nothing more.
 */
class DownloadedAdBlockRules(private val context: Context) : AdBlockRuleSink {

    private val directory: File get() = File(context.filesDir, DIR_NAME)
    private val file: File get() = File(directory, FILE_NAME)
    private val prefs get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The stored list, or null when nothing was downloaded or the file is unusable. */
    suspend fun readIfPresent(): String? = withContext(Dispatchers.IO) {
        val target = file
        if (!target.isFile) return@withContext null
        val length = target.length()
        if (length <= 0L || length > MAX_BYTES) return@withContext null
        val text = try {
            target.readText(Charsets.UTF_8)
        } catch (_: Throwable) {
            return@withContext null
        }
        // A file that parses to nothing is treated as absent rather than as a list that blocks
        // nothing: the bundled list is right there and is strictly better.
        text.takeIf { it.isNotBlank() }
    }

    override suspend fun previousInfo(): RuleUpdateInfo? = withContext(Dispatchers.IO) {
        val stored = prefs.getString(KEY_HASH, null) ?: return@withContext null
        RuleUpdateInfo(
            sourceUrl = prefs.getString(KEY_URL, "") ?: "",
            fetchedAtMillis = prefs.getLong(KEY_FETCHED_AT, 0L),
            sha256 = stored,
            hostRules = prefs.getInt(KEY_HOSTS, 0),
            pathRules = prefs.getInt(KEY_PATHS, 0),
            exceptions = prefs.getInt(KEY_EXCEPTIONS, 0),
            bytes = prefs.getInt(KEY_BYTES, 0),
            addedHosts = prefs.getInt(KEY_ADDED, 0),
        )
    }

    override suspend fun write(text: String, info: RuleUpdateInfo) = withContext(Dispatchers.IO) {
        val dir = directory
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw IllegalStateException("cannot create ${dir.absolutePath}")
        }
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BYTES) throw IllegalStateException("rule list is ${bytes.size} bytes")
        val temporary = File(dir, "$FILE_NAME.tmp")
        temporary.writeBytes(bytes)
        if (!temporary.renameTo(file)) {
            // Rename can fail if the target exists on some filesystems; deleting first is the
            // documented fallback and is still followed by a rename, never by a partial copy.
            file.delete()
            if (!temporary.renameTo(file)) {
                temporary.delete()
                throw IllegalStateException("cannot replace ${file.absolutePath}")
            }
        }
        prefs.edit()
            .putString(KEY_URL, info.sourceUrl)
            .putLong(KEY_FETCHED_AT, info.fetchedAtMillis)
            .putString(KEY_HASH, info.sha256)
            .putInt(KEY_HOSTS, info.hostRules)
            .putInt(KEY_PATHS, info.pathRules)
            .putInt(KEY_EXCEPTIONS, info.exceptions)
            .putInt(KEY_BYTES, info.bytes)
            .putInt(KEY_ADDED, info.addedHosts)
            .commit()
        Unit
    }

    /** Removes the downloaded list, returning the app to the bundled one. */
    suspend fun clear() = withContext(Dispatchers.IO) {
        file.delete()
        File(directory, "$FILE_NAME.tmp").delete()
        prefs.edit().clear().commit()
        Unit
    }

    companion object {
        const val DIR_NAME = "adblock"
        const val FILE_NAME = "rules-downloaded.txt"
        const val PREFS_NAME = "lumo_adblock_rules"

        /** 2 MiB. The lists this app is willing to use are a small fraction of that. */
        const val MAX_BYTES = 2 * 1024 * 1024

        private const val KEY_URL = "source_url"
        private const val KEY_FETCHED_AT = "fetched_at"
        private const val KEY_HASH = "sha256"
        private const val KEY_HOSTS = "hosts"
        private const val KEY_PATHS = "paths"
        private const val KEY_EXCEPTIONS = "exceptions"
        private const val KEY_BYTES = "bytes"
        private const val KEY_ADDED = "added"
    }
}

/**
 * The filter list the app actually uses: the downloaded one when there is one, the bundled one
 * otherwise.
 *
 * [description] is read *after* [read], which is why [usedDownloaded] is a field rather than a
 * parameter: the manager records where its rules came from, and the only moment at which that is
 * known for certain is the moment they were read.
 */
class LayeredAdBlockRules(
    private val bundled: AdBlockRulesSource,
    private val downloaded: DownloadedAdBlockRules,
) : AdBlockRulesSource {

    @Volatile
    var usedDownloaded: Boolean = false
        private set

    override val description: String
        get() = if (usedDownloaded) "downloaded list (${DownloadedAdBlockRules.FILE_NAME})" else bundled.description

    override val usesDownloaded: Boolean get() = usedDownloaded

    override suspend fun read(): String {
        val stored = downloaded.readIfPresent()
        if (stored != null) {
            usedDownloaded = true
            return stored
        }
        usedDownloaded = false
        return bundled.read()
    }
}

/** SHA-256 of [text] as lowercase hex. */
internal fun sha256Of(text: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}
