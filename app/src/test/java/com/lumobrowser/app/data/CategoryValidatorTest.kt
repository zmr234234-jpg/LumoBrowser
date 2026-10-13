package com.lumobrowser.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CategoryValidatorTest {

    @Test
    fun acceptsNormalName() {
        assertNull(CategoryValidator.validate("工作", "Old", listOf("Old")))
    }

    @Test
    fun trimsSurroundingWhitespace() {
        // " 工作 " trims to "工作", which is not a duplicate of "Old".
        assertNull(CategoryValidator.validate("  工作  ", "Old", listOf("Old")))
    }

    @Test
    fun rejectsEmptyAndWhitespaceOnly() {
        assertEquals(CategoryValidator.Error.Empty, CategoryValidator.validate("", "Old", listOf("Old")))
        assertEquals(CategoryValidator.Error.Empty, CategoryValidator.validate("   ", "Old", listOf("Old")))
        assertEquals(CategoryValidator.Error.Empty, CategoryValidator.validate("\t\n", "Old", listOf("Old")))
    }

    @Test
    fun rejectsDuplicateOfAnotherCategory() {
        assertEquals(
            CategoryValidator.Error.Duplicate,
            CategoryValidator.validate("Reading", "Work", listOf("Work", "Reading")),
        )
    }

    @Test
    fun allowsSameNameAsOriginal() {
        // Renaming "Work" to "Work" (or with only extra whitespace) is a no-op, not a duplicate.
        assertNull(CategoryValidator.validate("Work", "Work", listOf("Work")))
        assertNull(CategoryValidator.validate("  Work  ", "Work", listOf("Work")))
    }

    @Test
    fun rejectsNameLongerThanTwentyCodePoints() {
        val longName = "字".repeat(21)
        assertEquals(CategoryValidator.Error.TooLong, CategoryValidator.validate(longName, "Old", listOf("Old")))
    }

    @Test
    fun acceptsExactlyTwentyCodePoints() {
        val twenty = "字".repeat(20)
        assertNull(CategoryValidator.validate(twenty, "Old", listOf("Old")))
    }

    @Test
    fun countsEmojiAsSingleCodePoint() {
        // 19 CJK chars + one 4-byte emoji = 20 code points, within limit.
        val name = "字".repeat(19) + "🚀"
        assertNull(CategoryValidator.validate(name, "Old", listOf("Old")))
    }

    @Test
    fun acceptsLatinDigitsSymbols() {
        assertNull(CategoryValidator.validate("Bookmarks 2026!", "Old", listOf("Old")))
    }
}
