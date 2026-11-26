package io.legado.pack.storage

import io.legado.pack.api.JsonValue
import io.legado.pack.api.PackArchiveSpec
import io.legado.pack.api.PackDiagnostic
import io.legado.pack.api.PackErrorCode
import io.legado.pack.api.PackException
import io.legado.pack.manifest.JsonStructureException
import io.legado.pack.manifest.StrictJsonParser

/**
 * Small JSON text helper used by manager-owned metadata, self-test reports and
 * the per-pack cookie jar.
 *
 * The public API deliberately exposes only [JsonValue]; this writer exists so
 * internal files do not depend on a serializer whose unknown-field or
 * duplicate-field behaviour is outside our control. Parsing delegates to the
 * same strict tokenizer used for manifests.
 */
internal object PackJsonText {

    fun quote(value: String): String = buildString(value.length + 2) {
        append('"')
        appendEscaped(value)
        append('"')
    }

    fun write(value: JsonValue): String = buildString {
        appendValue(value)
    }

    fun writeStringMap(values: Map<String, String>): String = buildString {
        append('{')
        values.entries.sortedBy { it.key }.forEachIndexed { index, entry ->
            if (index > 0) append(',')
            append(quote(entry.key))
            append(':')
            append(quote(entry.value))
        }
        append('}')
    }

    fun parseObject(
        text: String,
        maxChars: Int = PackArchiveSpec.MAX_MANIFEST_CHARS,
        path: String = "$",
    ): JsonValue.Obj {
        val value = parseValue(text, maxChars, path)
        return value as? JsonValue.Obj ?: throw PackException(
            PackDiagnostic(
                code = PackErrorCode.MANIFEST_INVALID,
                message = "JSON document must be an object",
                path = path,
            ),
        )
    }

    fun parseValue(
        text: String,
        maxChars: Int = PackArchiveSpec.MAX_MANIFEST_CHARS,
        path: String = "$",
    ): JsonValue {
        if (text.length > maxChars) {
            throw PackException(
                PackDiagnostic(
                    code = PackErrorCode.IMPORT_LIMIT_EXCEEDED,
                    message = "JSON document exceeds $maxChars characters",
                    path = path,
                ),
            )
        }
        return try {
            StrictJsonParser(maxStringChars = maxChars).parse(text)
        } catch (e: JsonStructureException) {
            throw PackException(
                PackDiagnostic(
                    code = PackErrorCode.MANIFEST_INVALID,
                    message = e.message ?: "malformed JSON",
                    path = e.path,
                ),
                e,
            )
        }
    }

    private fun StringBuilder.appendValue(value: JsonValue) {
        when (value) {
            is JsonValue.Obj -> {
                append('{')
                value.entries.entries.forEachIndexed { index, entry ->
                    if (index > 0) append(',')
                    append(quote(entry.key))
                    append(':')
                    appendValue(entry.value)
                }
                append('}')
            }

            is JsonValue.Arr -> {
                append('[')
                value.items.forEachIndexed { index, item ->
                    if (index > 0) append(',')
                    appendValue(item)
                }
                append(']')
            }

            is JsonValue.Str -> append(quote(value.value))
            is JsonValue.Num -> append(value.raw)
            is JsonValue.Bool -> append(if (value.value) "true" else "false")
            JsonValue.Null -> append("null")
        }
    }

    private fun StringBuilder.appendEscaped(value: String) {
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) {
                    append("\\u")
                    append(char.code.toString(16).padStart(4, '0'))
                } else {
                    append(char)
                }
            }
        }
    }
}

internal fun JsonValue.Obj.requiredString(key: String, path: String = "$"): String {
    val value = entries[key] ?: throw invalidJsonField(path, key, "missing required field")
    return (value as? JsonValue.Str)?.value
        ?: throw invalidJsonField(path, key, "must be a string")
}

internal fun JsonValue.Obj.optionalString(key: String, path: String = "$"): String? {
    val value = entries[key] ?: return null
    if (value is JsonValue.Null) return null
    return (value as? JsonValue.Str)?.value
        ?: throw invalidJsonField(path, key, "must be a string")
}

internal fun JsonValue.Obj.optionalBoolean(key: String, path: String = "$"): Boolean? {
    val value = entries[key] ?: return null
    if (value is JsonValue.Null) return null
    return (value as? JsonValue.Bool)?.value
        ?: throw invalidJsonField(path, key, "must be a boolean")
}

internal fun JsonValue.Obj.requiredLong(key: String, path: String = "$"): Long {
    val value = entries[key] ?: throw invalidJsonField(path, key, "missing required field")
    val raw = (value as? JsonValue.Num)?.raw
        ?: throw invalidJsonField(path, key, "must be an integer")
    if (!INTEGER.matches(raw)) throw invalidJsonField(path, key, "must be an integer")
    return raw.toLongOrNull() ?: throw invalidJsonField(path, key, "integer is out of range")
}

internal fun JsonValue.Obj.optionalLong(key: String, path: String = "$"): Long? {
    val value = entries[key] ?: return null
    if (value is JsonValue.Null) return null
    val raw = (value as? JsonValue.Num)?.raw
        ?: throw invalidJsonField(path, key, "must be an integer")
    if (!INTEGER.matches(raw)) throw invalidJsonField(path, key, "must be an integer")
    return raw.toLongOrNull() ?: throw invalidJsonField(path, key, "integer is out of range")
}

internal fun JsonValue.Obj.requiredInt(key: String, path: String = "$"): Int {
    val value = requiredLong(key, path)
    if (value !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
        throw invalidJsonField(path, key, "integer is out of range")
    }
    return value.toInt()
}

internal fun JsonValue.Obj.requiredObject(key: String, path: String = "$"): JsonValue.Obj {
    val value = entries[key] ?: throw invalidJsonField(path, key, "missing required field")
    return value as? JsonValue.Obj ?: throw invalidJsonField(path, key, "must be an object")
}

internal fun JsonValue.Obj.optionalObject(key: String, path: String = "$"): JsonValue.Obj? {
    val value = entries[key] ?: return null
    if (value is JsonValue.Null) return null
    return value as? JsonValue.Obj ?: throw invalidJsonField(path, key, "must be an object")
}

internal fun JsonValue.Obj.requiredArray(key: String, path: String = "$"): JsonValue.Arr {
    val value = entries[key] ?: throw invalidJsonField(path, key, "missing required field")
    return value as? JsonValue.Arr ?: throw invalidJsonField(path, key, "must be an array")
}

internal fun JsonValue.Obj.optionalArray(key: String, path: String = "$"): JsonValue.Arr? {
    val value = entries[key] ?: return null
    if (value is JsonValue.Null) return null
    return value as? JsonValue.Arr ?: throw invalidJsonField(path, key, "must be an array")
}

internal fun readStringMap(value: JsonValue?, path: String): Map<String, String> {
    if (value == null || value is JsonValue.Null) return emptyMap()
    val obj = value as? JsonValue.Obj ?: throw invalidJsonField(path, "", "must be an object")
    val result = LinkedHashMap<String, String>(obj.entries.size)
    obj.entries.forEach { (key, item) ->
        val text = (item as? JsonValue.Str)?.value
            ?: throw invalidJsonField(path, key, "must be a string")
        result[key] = text
    }
    return result
}

internal fun readBooleanMap(value: JsonValue?, path: String): Map<String, Boolean> {
    if (value == null || value is JsonValue.Null) return emptyMap()
    val obj = value as? JsonValue.Obj ?: throw invalidJsonField(path, "", "must be an object")
    val result = LinkedHashMap<String, Boolean>(obj.entries.size)
    obj.entries.forEach { (key, item) ->
        val flag = (item as? JsonValue.Bool)?.value
            ?: throw invalidJsonField(path, key, "must be a boolean")
        result[key] = flag
    }
    return result
}

private fun invalidJsonField(path: String, key: String, message: String): PackException =
    PackException(
        PackDiagnostic(
            code = PackErrorCode.MANIFEST_INVALID,
            message = message,
            path = if (key.isEmpty()) path else "$path.$key",
        ),
    )

private val INTEGER = Regex("-?(?:0|[1-9][0-9]*)")