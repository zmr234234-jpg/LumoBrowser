package com.lumobrowser.app.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The left-hand category strip's two text rules.
 *
 * These are the parts of the 1.09 rail that can be decided without a screen, and they are the parts
 * most likely to be got wrong by a later edit: the strip stacks *vertically*, but that says nothing
 * about how each name should be *set*. A Han-only name reads top-to-bottom one character per line;
 * a name carrying Latin letters or digits must stay on one line, because breaking `esim` into four
 * lines is a legibility regression rather than a feature.
 */
class CategoryRailTest {

    /** The zero-width space the rail inserts between stacked characters. */
    private val zwsp = '\u200B'

    @Test
    fun aHanOnlyNameIsStackedOneCharacterPerLine() {
        val label = railLabel("学习")
        assertEquals("学${zwsp}习", label)
    }

    @Test
    fun aSingleHanCharacterIsLeftAlone() {
        // One character has nothing to separate: the rule must not append a trailing separator.
        assertEquals("书", railLabel("书"))
    }

    @Test
    fun aLatinNameIsNotBrokenUp() {
        // The exact case the brief calls out by name.
        assertEquals("esim", railLabel("esim"))
    }

    @Test
    fun anUppercaseLatinNameIsNotBrokenUp() {
        assertEquals("API", railLabel("API"))
    }

    @Test
    fun aMixedNameIsNotStackedCharacterByCharacter() {
        // "充值" is Han-only and stacks; "充值2" carries a digit and therefore does not. The whole
        // name goes down one path or the other — the rule is per name, never per character.
        val mixed = railLabel("充值2")
        assertEquals("充值2", mixed)
        assertFalse(mixed.contains(zwsp))
    }

    @Test
    fun theBuiltInLabelsCountAsMixedAndStayReadable() {
        // 全部 / 未分类 are Han-only and stack, which is correct for a two-or-three character label.
        assertEquals("全${zwsp}部", railLabel("全部"))
        assertEquals("未${zwsp}分${zwsp}类", railLabel("未分类"))
        // An English built-in label is untouched.
        assertEquals("All", railLabel("All"))
    }

    @Test
    fun digitsAndPunctuationDoNotCountAsHan() {
        assertFalse(isCjkOnly("2024"))
        assertFalse(isCjkOnly("a"))
        assertFalse(isCjkOnly("学习1"))
    }

    @Test
    fun whitespaceIsIgnoredWhenDecidingTheScript() {
        // `isCjkOnly` decides on the meaningful characters; `railLabel` drops whitespace from the
        // stacked form, because the line break it inserts already separates the characters.
        assertTrue(isCjkOnly("学 习"))
        // Asserted as code points rather than as a literal so the expectation cannot be silently
        // changed by an editor normalising invisible characters: HAN, ZWSP, HAN — the space is gone
        // because the line break it becomes already separates the two characters.
        val expected = intArrayOf(0x5B66, 0x200B, 0x4E60)
        val actual = railLabel("学 习").codePoints().toArray()
        assertArrayEquals(expected, actual)
    }

    @Test
    fun anEmptyOrBlankNameIsEqualToItself() {
        // No crash and no separator: an empty category name is a data problem to be seen, not to be
        // made worse by the layout code.
        assertEquals("", railLabel(""))
        assertFalse(isCjkOnly(""))
        assertFalse(isCjkOnly("   "))
        assertEquals("   ", railLabel("   "))
    }

    @Test
    fun aLongMixedNameIsReturnedUnchangedSoWrappingCanHandleIt() {
        // Truncation is the Text composable's job (maxLines + ellipsis); this function must not
        // start trimming characters, or the two would disagree about what the name is.
        val long = "A very long category name indeed"
        assertEquals(long, railLabel(long))
    }
}
