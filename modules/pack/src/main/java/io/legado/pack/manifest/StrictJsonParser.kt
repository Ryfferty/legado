package io.legado.pack.manifest

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.MalformedJsonException
import io.legado.pack.api.JsonValue
import java.io.IOException
import java.io.StringReader

internal class JsonStructureException(
    val path: String,
    message: String,
) : Exception(message)

/**
 * Small strict JSON reader used by manifest and UserScript header parsing.
 *
 * Gson's streaming reader is used only as a tokenizer; the tree is built here
 * so duplicate object names, excessive depth and oversized strings/collections
 * can be rejected before any model object exists. Gson itself is not exposed.
 */
internal class StrictJsonParser(
    private val maxDepth: Int = 64,
    private val maxStringChars: Int = 1_048_576,
    private val maxElements: Int = 100_000,
) {
    private class ParseState(var elements: Int = 0)

    fun parse(text: String): JsonValue {
        if (text.isEmpty()) throw JsonStructureException("$", "empty JSON document")
        val reader = JsonReader(StringReader(text))
        reader.setStrictness(Strictness.STRICT)
        try {
            val state = ParseState()
            val value = readValue(reader, "$", 0, state)
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw JsonStructureException("$", "trailing content after the JSON value")
            }
            return value
        } catch (e: JsonStructureException) {
            throw e
        } catch (e: MalformedJsonException) {
            throw JsonStructureException("$", e.message ?: "malformed JSON")
        } catch (e: IOException) {
            throw JsonStructureException("$", e.message ?: "I/O error while reading JSON")
        } catch (e: IllegalStateException) {
            throw JsonStructureException("$", e.message ?: "malformed JSON")
        } finally {
            runCatching { reader.close() }
        }
    }

    private fun readValue(
        reader: JsonReader,
        path: String,
        depth: Int,
        state: ParseState,
    ): JsonValue {
        if (depth > maxDepth) {
            throw JsonStructureException(path, "JSON nesting exceeds $maxDepth levels")
        }
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> readObject(reader, path, depth, state)
            JsonToken.BEGIN_ARRAY -> readArray(reader, path, depth, state)
            JsonToken.STRING -> JsonValue.Str(readBoundedString(reader.nextString(), path))
            JsonToken.NUMBER -> JsonValue.Num(reader.nextString())
            JsonToken.BOOLEAN -> JsonValue.Bool(reader.nextBoolean())
            JsonToken.NULL -> {
                reader.nextNull()
                JsonValue.Null
            }

            else -> throw JsonStructureException(path, "unexpected JSON token")
        }
    }

    private fun readObject(
        reader: JsonReader,
        path: String,
        depth: Int,
        state: ParseState,
    ): JsonValue.Obj {
        val entries = LinkedHashMap<String, JsonValue>()
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            if (name.length > 256) {
                throw JsonStructureException(path, "object field name is too long")
            }
            if (entries.containsKey(name)) {
                throw JsonStructureException("$path.$name", "duplicate field '$name'")
            }
            countElement(path, state)
            entries[name] = readValue(reader, "$path.$name", depth + 1, state)
        }
        reader.endObject()
        return JsonValue.Obj(entries)
    }

    private fun readArray(
        reader: JsonReader,
        path: String,
        depth: Int,
        state: ParseState,
    ): JsonValue.Arr {
        val items = ArrayList<JsonValue>()
        reader.beginArray()
        while (reader.hasNext()) {
            countElement(path, state)
            items += readValue(reader, "$path[${items.size}]", depth + 1, state)
        }
        reader.endArray()
        return JsonValue.Arr(items)
    }

    private fun readBoundedString(value: String, path: String): String {
        if (value.length > maxStringChars) {
            throw JsonStructureException(path, "string exceeds $maxStringChars characters")
        }
        return value
    }

    private fun countElement(path: String, state: ParseState) {
        state.elements++
        if (state.elements > maxElements) {
            throw JsonStructureException(path, "JSON contains more than $maxElements elements")
        }
    }
}