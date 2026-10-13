package com.lumobrowser.app.accessibility

/**
 * Serialises the user's declared assist targets into one string for DataStore.
 *
 * ## Format
 *
 * One target per line, three `|`-separated fields per line:
 *
 * ```
 * packageName|label|buttonText
 * ```
 *
 * `\`, `|` and newline are escaped inside every field (`\\`, `\p`, `\n`), so a label containing a
 * pipe — which is a perfectly ordinary thing for a button to say — survives a round trip.
 *
 * ## Why a hand-rolled codec rather than JSON
 *
 * The project already carries a JSON reader for backups, and a second format is a second thing to
 * get wrong. It is done anyway because this string is written on every keystroke of a text field
 * the user is typing into, and a line-oriented format fails in a way a human can read: it is
 * possible to look at the stored value and see what the app thinks the user said. That property is
 * worth more here than format reuse, because the whole feature rests on the user being able to
 * check and correct what LUMO believes about their phone.
 */
object AssistTargetCodec {

    /** Whole targets are dropped rather than repaired; see [decode]. */
    fun encode(targets: List<AssistTarget>): String =
        targets.filter { it.isUsable }
            .joinToString("\n") { target ->
                listOf(target.packageName, target.label, target.buttonText).joinToString("|") { escape(it) }
            }

    /**
     * Reads the stored string back.
     *
     * A line that does not have exactly three fields, or that decodes to an unusable target, is
     * dropped while the rest are kept. That is the opposite of [com.lumobrowser.app.vpn.VpnProfileCodec],
     * which rejects the whole record, and the difference is deliberate: a VPN profile is one
     * endpoint that is either coherent or not, while this is a list of independent declarations by
     * the user. Losing every target because one of them was typed before the format changed would
     * silently switch off the feature the user configured.
     */
    fun decode(stored: String): List<AssistTarget> {
        if (stored.isBlank()) return emptyList()
        return stored.split('\n').mapNotNull { line ->
            val fields = line.split('|')
            if (fields.size != 3) return@mapNotNull null
            val target = AssistTarget(
                packageName = unescape(fields[0]),
                label = unescape(fields[1]),
                buttonText = unescape(fields[2]),
            )
            target.takeIf { it.isUsable }
        }
    }

    /** Appends or replaces the target for its package, preserving the order of the others. */
    fun upsert(stored: String, target: AssistTarget): String {
        val existing = decode(stored)
        val merged = existing.filterNot { it.packageName == target.packageName } + target
        return encode(merged.sortedBy { it.packageName })
    }

    /** Removes the target for [packageName]. */
    fun remove(stored: String, packageName: String): String =
        encode(decode(stored).filterNot { it.packageName == packageName })

    private fun escape(value: String): String = buildString(value.length) {
        for (character in value) {
            when (character) {
                '\\' -> append("\\\\")
                '|' -> append("\\p")
                '\n' -> append("\\n")
                '\r' -> append("\\n")
                else -> append(character)
            }
        }
    }

    private fun unescape(value: String): String = buildString(value.length) {
        var index = 0
        while (index < value.length) {
            val character = value[index]
            if (character != '\\' || index == value.lastIndex) {
                append(character)
                index++
                continue
            }
            when (val escaped = value[index + 1]) {
                '\\' -> append('\\')
                'p' -> append('|')
                'n' -> append('\n')
                // An unknown escape keeps *both* characters. The input was written by an older or
                // newer version of this file, and the honest response to "I do not recognise this"
                // is to preserve what was there, not to eat a character and change what the user
                // declared. Appending only `escaped` would do exactly that — it turns a stored
                // `C:\vpn` into `C:vpn` while the comment claims the value was preserved.
                else -> append('\\').append(escaped)
            }
            index += 2
        }
    }
}
