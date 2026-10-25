package com.biliuvc.live.bili

/**
 * 极简 JSON 解析器（纯 Kotlin，不依赖 org.json，保证 JVM 单元测试可直接运行）。
 *
 * 只覆盖 B 站接口用到的 JSON 子集：对象/数组/字符串/数字/布尔/null。
 * 解析失败返回 null，绝不抛异常给调用方。
 */
internal sealed class JsonValue {
    object Null : JsonValue()
    data class Bool(val value: Boolean) : JsonValue()
    data class Num(val value: Double) : JsonValue()
    data class Str(val value: String) : JsonValue()
    data class Arr(val items: List<JsonValue>) : JsonValue()
    data class Obj(val fields: Map<String, JsonValue>) : JsonValue()
}

internal fun JsonValue?.asObj(): JsonValue.Obj? = this as? JsonValue.Obj

internal fun JsonValue?.asArr(): JsonValue.Arr? = this as? JsonValue.Arr

internal fun JsonValue?.asString(): String? = (this as? JsonValue.Str)?.value

internal fun JsonValue?.asLong(): Long? = when (this) {
    is JsonValue.Num -> {
        if (!value.isFinite() || value != Math.floor(value) || Math.abs(value) > MAX_SAFE_INTEGER) null
        else value.toLong()
    }
    is JsonValue.Str -> value.trim().toLongOrNull()
    else -> null
}

internal fun JsonValue?.asInt(): Int? = asLong()?.let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else null }

internal fun JsonValue?.asBool(): Boolean? = when (this) {
    is JsonValue.Bool -> value
    is JsonValue.Num -> value != 0.0
    is JsonValue.Str -> when (value.trim().lowercase()) {
        "true", "1" -> true
        "false", "0" -> false
        else -> null
    }
    else -> null
}

internal operator fun JsonValue?.get(key: String): JsonValue? = (this as? JsonValue.Obj)?.fields?.get(key)

internal object Json {
    /** 解析 JSON 文本；非法输入返回 null。 */
    fun parse(text: String): JsonValue? {
        return try {
            Parser(text.removePrefix("\uFEFF")).parseDocument()
        } catch (e: RuntimeException) {
            null
        }
    }

    /** 将字符串序列化为带引号的 JSON 字符串字面量。 */
    fun quote(value: String): String {
        val out = StringBuilder(value.length + 2)
        out.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (ch < ' ') {
                    out.append("\\u")
                    val code = ch.code
                    out.append(HEX[code shr 12 and 0xF])
                    out.append(HEX[code shr 8 and 0xF])
                    out.append(HEX[code shr 4 and 0xF])
                    out.append(HEX[code and 0xF])
                } else {
                    out.append(ch)
                }
            }
        }
        out.append('"')
        return out.toString()
    }

    private const val HEX = "0123456789abcdef"

    private class Parser(private val text: String) {
        private var index = 0

        fun parseDocument(): JsonValue {
            skipWhitespace()
            val value = parseValue(0)
            skipWhitespace()
            return value
        }

        private fun parseValue(depth: Int): JsonValue {
            if (depth > MAX_DEPTH) throw IllegalArgumentException("json too deep")
            skipWhitespace()
            if (index >= text.length) throw IllegalArgumentException("unexpected end")
            return when (text[index]) {
                '{' -> parseObject(depth)
                '[' -> parseArray(depth)
                '"' -> JsonValue.Str(parseString())
                't' -> literal("true", JsonValue.Bool(true))
                'f' -> literal("false", JsonValue.Bool(false))
                'n' -> literal("null", JsonValue.Null)
                else -> parseNumber()
            }
        }

        private fun parseObject(depth: Int): JsonValue.Obj {
            expect('{')
            skipWhitespace()
            val fields = LinkedHashMap<String, JsonValue>()
            if (peek() == '}') {
                index++
                return JsonValue.Obj(fields)
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') throw IllegalArgumentException("expected key")
                val key = parseString()
                skipWhitespace()
                expect(':')
                fields[key] = parseValue(depth + 1)
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    '}' -> {
                        index++
                        return JsonValue.Obj(fields)
                    }
                    else -> throw IllegalArgumentException("expected , or }")
                }
            }
        }

        private fun parseArray(depth: Int): JsonValue.Arr {
            expect('[')
            skipWhitespace()
            val items = ArrayList<JsonValue>()
            if (peek() == ']') {
                index++
                return JsonValue.Arr(items)
            }
            while (true) {
                items.add(parseValue(depth + 1))
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    ']' -> {
                        index++
                        return JsonValue.Arr(items)
                    }
                    else -> throw IllegalArgumentException("expected , or ]")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                if (index >= text.length) throw IllegalArgumentException("unterminated string")
                val ch = text[index++]
                when (ch) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (index >= text.length) throw IllegalArgumentException("bad escape")
                        when (val esc = text[index++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (index + 4 > text.length) throw IllegalArgumentException("bad unicode escape")
                                val code = text.substring(index, index + 4).toInt(16)
                                index += 4
                                out.append(code.toChar())
                            }
                            else -> throw IllegalArgumentException("bad escape $esc")
                        }
                    }
                    else -> out.append(ch)
                }
            }
        }

        private fun parseNumber(): JsonValue.Num {
            val start = index
            if (peek() == '-') index++
            while (index < text.length && (text[index].isDigit() || text[index] in ".eE+-")) index++
            if (index == start) throw IllegalArgumentException("not a value")
            val number = text.substring(start, index).toDoubleOrNull() ?: throw IllegalArgumentException("bad number")
            return JsonValue.Num(number)
        }

        private fun literal(word: String, value: JsonValue): JsonValue {
            if (!text.startsWith(word, index)) throw IllegalArgumentException("bad literal")
            index += word.length
            return value
        }

        private fun expect(ch: Char) {
            if (index >= text.length || text[index] != ch) throw IllegalArgumentException("expected $ch")
            index++
        }

        private fun peek(): Char {
            if (index >= text.length) throw IllegalArgumentException("unexpected end")
            return text[index]
        }

        private fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }

        companion object {
            private const val MAX_DEPTH = 64
        }
    }
}

/** 整数安全边界（JS Number.MAX_SAFE_INTEGER）。 */
private const val MAX_SAFE_INTEGER: Double = 9007199254740991.0
