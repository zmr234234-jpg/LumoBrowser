package com.lumobrowser.app.browser.adblock

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The filter list that ships inside the APK.
 *
 * There is no download and no update check, so there is nothing here that can fail at runtime for a
 * reason the user cannot see: the file is either in the package or the package is broken. That is a
 * deliberate limitation rather than an unfinished feature — the brief is explicit that online rule
 * updates must not be faked, and shipping a list that works offline on first launch is more useful
 * than shipping a URL that may or may not answer.
 *
 * The size ceiling exists even though the asset is ours: a bounded read means a corrupted or
 * unexpectedly regenerated file degrades to "no blocking" instead of allocating whatever size it
 * claims to be.
 */
class BundledAdBlockRules(private val context: Context) : AdBlockRulesSource {

    override val description: String = "assets/$ASSET_PATH"

    override suspend fun read(): String = withContext(Dispatchers.IO) {
        val bytes = context.assets.open(ASSET_PATH).use { stream ->
            val buffer = ByteArray(MAX_BYTES + 1)
            var filled = 0
            while (filled < buffer.size) {
                val read = stream.read(buffer, filled, buffer.size - filled)
                if (read <= 0) break
                filled += read
            }
            if (filled > MAX_BYTES) {
                throw IllegalStateException("$ASSET_PATH is larger than $MAX_BYTES bytes")
            }
            buffer.copyOf(filled)
        }
        bytes.toString(Charsets.UTF_8)
    }

    companion object {
        const val ASSET_PATH = "adblock/rules.txt"

        /** 512 KiB. The shipped list is a small fraction of this. */
        const val MAX_BYTES = 512 * 1024
    }
}
