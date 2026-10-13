package com.lumobrowser.app.util

/**
 * A small, dependency-free JSON reader/writer.
 *
 * LumoBrowser ships no JSON library: backups are a single flat document whose schema we own,
 * so a ~200-line streaming parser is less risk (and far less APK weight) than a general
 * binding framework. The parser is strict about structure but tolerant about unknown fields,
 * which keeps old backups readable after the schema grows.
 */
sealed interface JsonValue {

    data class JsonObject(val entries: Map<String, JsonValue>) : JsonValue {
        operator fun get(key: String): JsonValue? = entries[key]
    }

    data class JsonArray(val items: List<JsonValue>) : JsonValue

    data class JsonString(val value: String) : JsonValue

    data class JsonNumber(val value: Double) : JsonValue {
        fun asLong(): Long = value.toLong()
        fun asInt(): Int = value.toInt()
    }

    data class JsonBool(val value: Boolean) : JsonValue

    data object JsonNull : JsonValue

    fun asStringOrNull(): String? = (this as? JsonString)?.value
    fun asLongOrNull(): Long? = (this as? JsonNumber)?.asLong()
    fun asBooleanOrNull(): Boolean? = (this as? JsonBool)?.value
    fun asObjectOrNull(): JsonObject? = this as? JsonObject
    fun asArrayOrNull(): JsonArray? = this as? JsonArray
}

class JsonParseException(message: String) : IllegalArgumentException(message)

object Json {

    // ---------------------------------------------------------------- writing

    fun write(value: JsonValue): String = StringBuilder().also { append(it, value) }.toString()

    private fun append(sb: StringBuilder, value: JsonValue) {
        when (value) {
            is JsonValue.JsonObject -> {
                sb.append('{')
                var first = true
                for ((key, child) in value.entries) {
                    if (!first) sb.append(',')
                    first = false
                    appendString(sb, key)
                    sb.append(':')
                    append(sb, child)
                }
                sb.append('}')
            }

            is JsonValue.JsonArray -> {
                sb.append('[')
                value.items.forEachIndexed { index, child ->
                    if (index > 0) sb.append(',')
                    append(sb, child)
                }
                sb.append(']')
            }

            is JsonValue.JsonString -> appendString(sb, value.value)
            is JsonValue.JsonNumber -> {
                val d = value.value
                if (d.isFinite() && d == Math.floor(d) && Math.abs(d) < 1e15) {
                    sb.append(d.toLong().toString())
                } else if (d.isFinite()) {
                    sb.append(d.toString())
                } else {
                    sb.append('0')
                }
            }

            is JsonValue.JsonBool -> sb.append(if (value.value) "true" else "false")
            JsonValue.JsonNull -> sb.append("null")
        }
    }

    private fun appendString(sb: StringBuilder, raw: String) {
        sb.append('"')
        for (char in raw) {
            when (char) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (char < ' ') {
                    sb.append("\\u").append(char.code.toString(16).padStart(4, '0'))
                } else {
                    sb.append(char)
                }
            }
        }
        sb.append('"')
    }

    // ---------------------------------------------------------------- reading

    fun parse(text: String): JsonValue = Parser(text).parseDocument()

    private class Parser(private val src: String) {
        private var pos = 0

        fun parseDocument(): JsonValue {
            skipWhitespace()
            val value = parseValue()
            skipWhitespace()
            if (pos != src.length) throw JsonParseException("Trailing characters at index $pos")
            return value
        }

        private fun parseValue(): JsonValue {
            skipWhitespace()
            if (pos >= src.length) throw JsonParseException("Unexpected end of input")
            return when (val c = src[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> JsonValue.JsonString(parseString())
                't', 'f' -> parseBoolean()
                'n' -> parseNull()
                else -> if (c == '-' || c.isDigit()) parseNumber()
                else throw JsonParseException("Unexpected character '$c' at index $pos")
            }
        }

        private fun parseObject(): JsonValue.JsonObject {
            expect('{')
            val map = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (peek() == '}') {
                pos++
                return JsonValue.JsonObject(map)
            }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                map[key] = parseValue()
                skipWhitespace()
                when (val c = next()) {
                    ',' -> continue
                    '}' -> return JsonValue.JsonObject(map)
                    else -> throw JsonParseException("Expected ',' or '}' but found '$c' at index ${pos - 1}")
                }
            }
        }

        private fun parseArray(): JsonValue.JsonArray {
            expect('[')
            val items = ArrayList<JsonValue>()
            skipWhitespace()
            if (peek() == ']') {
                pos++
                return JsonValue.JsonArray(items)
            }
            while (true) {
                items.add(parseValue())
                skipWhitespace()
                when (val c = next()) {
                    ',' -> continue
                    ']' -> return JsonValue.JsonArray(items)
                    else -> throw JsonParseException("Expected ',' or ']' but found '$c' at index ${pos - 1}")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (pos >= src.length) throw JsonParseException("Unterminated string")
                when (val c = src[pos++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= src.length) throw JsonParseException("Unterminated escape")
                        when (val esc = src[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > src.length) throw JsonParseException("Truncated \\u escape")
                                val hex = src.substring(pos, pos + 4)
                                val code = hex.toIntOrNull(16)
                                    ?: throw JsonParseException("Invalid \\u escape '$hex'")
                                sb.append(code.toChar())
                                pos += 4
                            }

                            else -> throw JsonParseException("Invalid escape '\\$esc'")
                        }
                    }

                    else -> sb.append(c)
                }
            }
        }

        private fun parseBoolean(): JsonValue.JsonBool {
            return when {
                src.startsWith("true", pos) -> { pos += 4; JsonValue.JsonBool(true) }
                src.startsWith("false", pos) -> { pos += 5; JsonValue.JsonBool(false) }
                else -> throw JsonParseException("Invalid literal at index $pos")
            }
        }

        private fun parseNull(): JsonValue {
            if (src.startsWith("null", pos)) {
                pos += 4
                return JsonValue.JsonNull
            }
            throw JsonParseException("Invalid literal at index $pos")
        }

        private fun parseNumber(): JsonValue.JsonNumber {
            val start = pos
            if (peek() == '-') pos++
            while (pos < src.length && (src[pos].isDigit() || src[pos] == '.' ||
                        src[pos] == 'e' || src[pos] == 'E' || src[pos] == '+' || src[pos] == '-')
            ) {
                // A '-' or '+' is only legal immediately after an exponent marker.
                val c = src[pos]
                if ((c == '-' || c == '+') && pos > start &&
                    src[pos - 1] != 'e' && src[pos - 1] != 'E'
                ) break
                pos++
            }
            val raw = src.substring(start, pos)
            val value = raw.toDoubleOrNull() ?: throw JsonParseException("Invalid number '$raw'")
            return JsonValue.JsonNumber(value)
        }

        private fun skipWhitespace() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }

        private fun peek(): Char = if (pos < src.length) src[pos] else '\u0000'

        private fun next(): Char {
            if (pos >= src.length) throw JsonParseException("Unexpected end of input")
            return src[pos++]
        }

        private fun expect(c: Char) {
            skipWhitespace()
            val actual = next()
            if (actual != c) throw JsonParseException("Expected '$c' but found '$actual' at index ${pos - 1}")
        }
    }
}
