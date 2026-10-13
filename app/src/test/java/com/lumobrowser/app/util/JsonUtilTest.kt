package com.lumobrowser.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dependency-free JSON reader/writer. Backups are the only thing that runs through it, so the
 * cases that matter are: round-tripping our own documents, refusing malformed input instead of
 * silently producing half a file, and keeping integers integral.
 *
 * Note this test lives in the same package as [Json], so `asStringOrNull()` and friends resolve as
 * members of [JsonValue] — importing those names individually shadows them and fails to compile.
 */
class JsonUtilTest {

    @Test
    fun objectRoundTrips() {
        val source = JsonValue.JsonObject(
            linkedMapOf(
                "schema" to JsonValue.JsonString("lumobrowser.backup"),
                "version" to JsonValue.JsonNumber(1.0),
                "pinned" to JsonValue.JsonBool(true),
                "tags" to JsonValue.JsonArray(listOf(JsonValue.JsonString("a"), JsonValue.JsonString("b"))),
            )
        )
        val parsed = Json.parse(Json.write(source)).asObjectOrNull()
        assertNotNull(parsed)
        assertEquals("lumobrowser.backup", parsed!!["schema"]!!.asStringOrNull())
        assertEquals(1L, parsed["version"]!!.asLongOrNull())
        assertEquals(true, parsed["pinned"]!!.asBooleanOrNull())
        assertEquals(2, parsed["tags"]!!.asArrayOrNull()!!.items.size)
    }

    @Test
    fun stringsWithQuotesAndNewlinesAreEscaped() {
        val text = "line1\nline2 \"quoted\" \\ back"
        val encoded = Json.write(JsonValue.JsonString(text))
        assertEquals(text, Json.parse(encoded).asStringOrNull())
    }

    @Test
    fun unicodeEscapesAreDecoded() {
        assertEquals("A", Json.parse("\"\\u0041\"").asStringOrNull())
        assertEquals("\u4e2d\u6587", Json.parse("\"\\u4e2d\\u6587\"").asStringOrNull())
    }

    @Test
    fun integralDoublesPrintWithoutDecimalPoint() {
        // Backups store ids/counts; "1.0" would be readable but sloppy, and some readers treat it as
        // a float. The writer emits integers for integral values.
        assertEquals("1", Json.write(JsonValue.JsonNumber(1.0)))
        assertEquals("0", Json.write(JsonValue.JsonNumber(0.0)))
        assertEquals("1000", Json.write(JsonValue.JsonNumber(1000.0)))
    }

    @Test
    fun fractionsKeepTheirDecimalPoint() {
        assertEquals("1.5", Json.write(JsonValue.JsonNumber(1.5)))
    }

    @Test
    fun nonFiniteNumbersWriteAsZero() {
        // JSON has no NaN/Infinity literal; emitting one would produce a file we cannot parse back.
        assertEquals("0", Json.write(JsonValue.JsonNumber(Double.NaN)))
        assertEquals("0", Json.write(JsonValue.JsonNumber(Double.POSITIVE_INFINITY)))
    }

    @Test
    fun nestedStructuresRoundTrip() {
        val text = """{"a":{"b":[1,{"c":false}]}}"""
        val root = Json.parse(text).asObjectOrNull()
        val inner = root!!["a"]!!.asObjectOrNull()!!["b"]!!.asArrayOrNull()!!
        assertEquals(1L, inner.items[0].asLongOrNull())
        assertEquals(false, inner.items[1].asObjectOrNull()!!["c"]!!.asBooleanOrNull())
    }

    @Test
    fun exponentsAreParsed() {
        assertEquals(1000L, Json.parse("1e3").asLongOrNull())
        assertEquals(100L, Json.parse("1E2").asLongOrNull())
    }

    @Test
    fun nullLiteralIsRepresented() {
        val parsed = Json.parse("""{"x":null}""").asObjectOrNull()
        assertEquals(JsonValue.JsonNull, parsed!!["x"])
        assertNull(parsed["x"]!!.asStringOrNull())
    }

    @Test
    fun booleanAndNumberAreNotCoercedToStrings() {
        assertNull(Json.parse("true").asStringOrNull())
        assertNull(Json.parse("12").asStringOrNull())
        assertNull(Json.parse("\"12\"").asLongOrNull())
    }

    @Test(expected = JsonParseException::class)
    fun trailingCharactersAreRejected() {
        Json.parse("""{"a":1} extra""")
    }

    @Test(expected = JsonParseException::class)
    fun unterminatedStringIsRejected() {
        Json.parse("\"never closed")
    }

    @Test(expected = JsonParseException::class)
    fun unterminatedObjectIsRejected() {
        Json.parse("""{"a":1""")
    }

    @Test(expected = JsonParseException::class)
    fun emptyInputIsRejected() {
        Json.parse("   ")
    }

    @Test(expected = JsonParseException::class)
    fun brokenNumberIsRejected() {
        Json.parse("1.2.3")
    }

    @Test
    fun whitespaceBetweenTokensIsAllowed() {
        val parsed = Json.parse("  {\n  \"a\" :\t 1\n }  ").asObjectOrNull()
        assertEquals(1L, parsed!!["a"]!!.asLongOrNull())
    }

    @Test
    fun unknownFieldsArePreservedOnParse() {
        // Forward compatibility: a newer backup's extra keys must not break an older reader.
        val parsed = Json.parse("""{"schema":"x","future":{"deep":[1,2]}}""").asObjectOrNull()
        assertNotNull(parsed!!["future"])
        assertTrue(parsed.entries.containsKey("future"))
    }

    @Test
    fun emptyContainersRoundTrip() {
        assertEquals("{}", Json.write(JsonValue.JsonObject(emptyMap())))
        assertEquals("[]", Json.write(JsonValue.JsonArray(emptyList())))
        assertEquals(0, Json.parse("[]").asArrayOrNull()!!.items.size)
        assertEquals(0, Json.parse("{}").asObjectOrNull()!!.entries.size)
    }
}
