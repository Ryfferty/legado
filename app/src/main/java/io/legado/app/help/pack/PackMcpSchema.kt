package io.legado.app.help.pack

import io.legado.pack.api.JsonValue
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Converts the pack schema subset into MCP SDK 0.15 [ToolSchema].
 *
 * The SDK model can represent only top-level `$schema`, `properties`,
 * `required` and `$defs`; nested property schemas stay arbitrary JSON. Pack
 * schemas that rely on other top-level structural keywords (`allOf`,
 * `additionalProperties`, ...) cannot be represented faithfully, so the bridge
 * refuses to register that tool and reports a diagnostic instead of silently
 * advertising a weaker schema.
 */
object PackMcpSchema {

    private val REPRESENTABLE_TOP_LEVEL = setOf(
        "\$schema",
        "\$id",
        "title",
        "description",
        "default",
        "examples",
        "type",
        "properties",
        "required",
        "\$defs",
        "definitions",
        "deprecated",
        "readOnly",
        "writeOnly",
    )

    fun unsupportedKeywords(schema: JsonValue.Obj): List<String> =
        schema.entries.keys
            .filterNot { it in REPRESENTABLE_TOP_LEVEL }
            .sorted()

    fun toToolSchema(schema: JsonValue.Obj): ToolSchema? {
        if (unsupportedKeywords(schema).isNotEmpty()) return null
        val type = schema["type"] as? JsonValue.Str
        if (type != null && type.value != "object") return null

        val properties = (schema["properties"] as? JsonValue.Obj)
            ?.let(::toJsonObject)
        val required = (schema["required"] as? JsonValue.Arr)
            ?.items
            ?.mapNotNull { (it as? JsonValue.Str)?.value }
        val defs = LinkedHashMap<String, JsonElement>()
        (schema["\$defs"] as? JsonValue.Obj)?.entries?.forEach {
            defs[it.key] = toJsonElement(it.value)
        }
        (schema["definitions"] as? JsonValue.Obj)?.entries?.forEach {
            defs.putIfAbsent(it.key, toJsonElement(it.value))
        }
        return ToolSchema(
            properties = properties,
            required = required,
            defs = defs.takeIf { it.isNotEmpty() }?.let(::JsonObject),
        )
    }

    fun toJsonObject(obj: JsonValue.Obj): JsonObject = buildJsonObject {
        obj.entries.forEach { (key, value) ->
            put(key, toJsonElement(value))
        }
    }

    fun toJsonElement(value: JsonValue): JsonElement = when (value) {
        is JsonValue.Obj -> toJsonObject(value)
        is JsonValue.Arr -> buildJsonArray {
            value.items.forEach { add(toJsonElement(it)) }
        }

        is JsonValue.Str -> JsonPrimitive(value.value)
        is JsonValue.Num -> value.raw.toBigDecimalOrNull()
            ?.let { JsonPrimitive(it) }
            ?: JsonPrimitive(value.raw)

        is JsonValue.Bool -> JsonPrimitive(value.value)
        JsonValue.Null -> JsonNull
    }
}