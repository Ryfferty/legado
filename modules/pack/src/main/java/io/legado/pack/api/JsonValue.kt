package io.legado.pack.api

/**
 * Minimal immutable JSON tree used by the manifest and tool schemas.
 *
 * Objects and arrays defensively copy their input and expose unmodifiable
 * views. A caller that casts the view back to MutableMap/MutableList gets a
 * fail-fast UnsupportedOperationException rather than silently changing a
 * shared preview, manifest or registration snapshot.
 */
sealed interface JsonValue {

    class Obj(entries: Map<String, JsonValue>) : JsonValue {
        val entries: Map<String, JsonValue> = immutableMap(entries)

        operator fun get(key: String): JsonValue? = entries[key]

        fun copy(entries: Map<String, JsonValue> = this.entries): Obj = Obj(entries)

        override fun equals(other: Any?): Boolean =
            other is Obj && entries == other.entries

        override fun hashCode(): Int = entries.hashCode()

        override fun toString(): String = "JsonValue.Obj($entries)"
    }

    class Arr(items: List<JsonValue>) : JsonValue {
        val items: List<JsonValue> = immutableList(items)

        fun copy(items: List<JsonValue> = this.items): Arr = Arr(items)

        override fun equals(other: Any?): Boolean =
            other is Arr && items == other.items

        override fun hashCode(): Int = items.hashCode()

        override fun toString(): String = "JsonValue.Arr($items)"
    }

    data class Str(val value: String) : JsonValue

    /**
     * Number literal is preserved verbatim. Callers that need an integer must
     * reject fraction/exponent spellings explicitly instead of silently
     * truncating.
     */
    data class Num(val raw: String) : JsonValue

    data class Bool(val value: Boolean) : JsonValue

    data object Null : JsonValue
}

fun emptyObjectSchema(): JsonValue.Obj = JsonValue.Obj(
    mapOf(
        "type" to JsonValue.Str("object"),
        "properties" to JsonValue.Obj(emptyMap()),
    ),
)