package com.lumobrowser.app.util

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three `strings.xml` files are hand-maintained, and the pipeline that reads them
 * (`LumoStrings` -> `LumoStringsSnapshot` -> `LocalLumoStrings`) turns a missing key into a runtime
 * crash on the screen that asks for it, not into a build failure.
 *
 * That is not hypothetical: the 1.03 cycle corrupted these files and shipped omissions that only
 * showed up on a device. A locale that is missing one `name=` compiles fine, and CI never opens the
 * file. So the invariant gets its own test — cheap, JVM-only, and it fails loudly at the moment the
 * mistake is made rather than on the phone.
 */
class StringResourceParityTest {

    private val locales = listOf("values", "values-zh", "values-zh-rTW")

    /** `%1$s`, `%2$d`, and friends. The `$` is written via interpolation because this is a raw string. */
    private val specifier = Regex("""%(\d+)\${'$'}([a-zA-Z])""")

    private val entry = Regex("""<string\s+name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)

    private val resDir: File = locateResDir()

    @Test
    fun `every locale file is present and parsed`() {
        for (locale in locales) {
            val parsed = entries(locale)
            assertTrue("$locale/strings.xml parsed as empty", parsed.isNotEmpty())
        }
    }

    @Test
    fun `every locale defines exactly the same keys`() {
        val base = entries(locales.first()).keys
        for (locale in locales.drop(1)) {
            val keys = entries(locale).keys
            val missing = (base - keys).sorted()
            val extra = (keys - base).sorted()
            assertEquals("$locale is missing: $missing", emptyList<String>(), missing)
            assertEquals("$locale adds keys the base locale does not have: $extra", emptyList<String>(), extra)
        }
    }

    @Test
    fun `no key is defined twice in one file`() {
        for (locale in locales) {
            val text = file(locale).readText()
            val names = Regex("""<string\s+name="([^"]+)"""").findAll(text).map { it.groupValues[1] }.toList()
            val duplicates = names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.sorted()
            // A later <string> with the same name silently wins in AAPT, so a duplicate is a value
            // that was edited and never took effect.
            assertEquals("$locale defines these names more than once: $duplicates", emptyList<String>(), duplicates)
        }
    }

    @Test
    fun `no translation is left blank`() {
        for (locale in locales) {
            val blank = entries(locale).filterValues { it.isBlank() }.keys.sorted()
            assertEquals("$locale has empty values: $blank", emptyList<String>(), blank)
        }
    }

    @Test
    fun `format specifiers match across locales`() {
        // A translation that drops `%1$s` (or renumbers it) does not crash `getString` — it silently
        // prints the wrong thing, or throws IllegalFormatException at the call site. Comparing the
        // sets is what makes a lost placeholder a build-time problem instead of a support ticket.
        val base = entries(locales.first())
        for (locale in locales.drop(1)) {
            val translated = entries(locale)
            val mismatched = base.keys.intersect(translated.keys)
                .filter { specifiersOf(base.getValue(it)) != specifiersOf(translated.getValue(it)) }
                .sorted()
            assertEquals(
                "$locale disagrees with ${locales.first()} about format specifiers: $mismatched",
                emptyList<String>(),
                mismatched,
            )
        }
    }

    @Test
    fun `xml is not corrupted`() {
        for (locale in locales) {
            val text = file(locale).readText()
            assertEquals("$locale contains U+FFFD", 0, text.count { it == '\uFFFD' })
            assertTrue("$locale starts with a BOM", !text.startsWith('\uFEFF'))
            assertTrue("$locale is missing its closing tag", text.contains("</resources>"))
        }
    }

    private fun specifiersOf(value: String): List<String> =
        specifier.findAll(value).map { it.value }.sorted().toList()

    private fun file(locale: String) = File(resDir, "$locale/strings.xml")

    private fun entries(locale: String): Map<String, String> {
        val parsed = entry.findAll(file(locale).readText())
            .associate { it.groupValues[1] to it.groupValues[2].trim() }
        return parsed
    }

    /**
     * Unit tests run with the module directory as the working directory, but that is a Gradle
     * implementation detail and this test should not quietly pass by finding nothing. Walk up a few
     * levels and fail with the searched paths instead.
     */
    private fun locateResDir(): File {
        val tried = mutableListOf<File>()
        var dir: File? = File("").absoluteFile
        var depth = 0
        while (dir != null && depth < 4) {
            for (candidate in listOf(File(dir, "src/main/res"), File(dir, "app/src/main/res"))) {
                if (File(candidate, "values/strings.xml").isFile) return candidate
                tried += candidate
            }
            dir = dir.parentFile
            depth++
        }
        throw AssertionError("Could not find src/main/res. Looked in: ${tried.joinToString()}")
    }
}
