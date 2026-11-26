package io.legado.pack.api

import java.math.BigDecimal

/**
 * Deliberately small JSON Schema subset supported for MCP tool input.
 *
 * The supported dialect is exactly [DIALECT_URI]. A top-level `$schema` may be
 * absent or equal to that URI; anything else is rejected. Top-level input
 * schemas must be object schemas. `type` may be omitted in the manifest;
 * [normalizeObjectSchema] inserts the lowercase `"object"` before
 * validation/preview. If `type` is present it must be exactly the lowercase
 * string `"object"`; `"OBJECT"`, arrays or other values are rejected rather
 * than silently normalized.
 *
 * Supported keywords:
 * `$schema`, `$id`, `title`, `description`, `default`, `examples`, `type`,
 * `properties`, `required`, `additionalProperties`, `items`, `enum`, `const`,
 * `format`, `minimum`, `maximum`, `exclusiveMinimum`, `exclusiveMaximum`,
 * `multipleOf`, `minLength`, `maxLength`, `minItems`, `maxItems`,
 * `uniqueItems`, `minProperties`, `maxProperties`, `allOf`, `anyOf`, `oneOf`,
 * `not`, `$defs`, `definitions`, `deprecated`, `readOnly`, `writeOnly`.
 *
 * `minLength`/`maxLength`/`minItems`/`maxItems`/`minProperties`/`maxProperties`
 * must be non-negative integers; `multipleOf` must be strictly positive.
 * `pattern` is intentionally unsupported because Java/Android and browser
 * regular-expression dialects differ; it is rejected rather than advertised as
 * portable. Other unsupported keywords (for example `$ref`,
 * `patternProperties`, `if`/`then`) are also rejected explicitly. Nested
 * schemas are validated recursively so a schema cannot pass the manifest gate
 * and fail only at MCP registration time.
 */
object PackToolSchema {

    const val DIALECT_URI: String = "urn:legado:pack:tool-schema:v1"

    private val TYPE_NAMES = setOf(
        "null",
        "boolean",
        "object",
        "array",
        "number",
        "string",
        "integer",
    )

    private val ALLOWED_KEYWORDS = setOf(
        "\$schema",
        "\$id",
        "title",
        "description",
        "default",
        "examples",
        "type",
        "properties",
        "required",
        "additionalProperties",
        "items",
        "enum",
        "const",
        "format",
        "minimum",
        "maximum",
        "exclusiveMinimum",
        "exclusiveMaximum",
        "multipleOf",
        "minLength",
        "maxLength",
        "minItems",
        "maxItems",
        "uniqueItems",
        "minProperties",
        "maxProperties",
        "allOf",
        "anyOf",
        "oneOf",
        "not",
        "\$defs",
        "definitions",
        "deprecated",
        "readOnly",
        "writeOnly",
    )

    private const val MAX_DEPTH: Int = 32

    /** Inserts a lowercase object type at the top level when absent. */
    fun normalizeObjectSchema(schema: JsonValue.Obj): JsonValue.Obj {
        if (schema.entries.containsKey("type")) return schema
        val entries = LinkedHashMap(schema.entries)
        entries["type"] = JsonValue.Str("object")
        return JsonValue.Obj(entries)
    }

    fun validate(schema: JsonValue.Obj, path: String = "$"): List<PackDiagnostic> {
        val diagnostics = mutableListOf<PackDiagnostic>()
        validateSchema(schema, path, topLevel = true, depth = 0, diagnostics)
        return diagnostics
    }

    fun isValid(schema: JsonValue.Obj): Boolean = validate(schema).isEmpty()

    private fun validateSchema(
        schema: JsonValue.Obj,
        path: String,
        topLevel: Boolean,
        depth: Int,
        diagnostics: MutableList<PackDiagnostic>,
    ) {
        if (depth > MAX_DEPTH) {
            diagnostics += diagnostic(path, "schema nesting exceeds $MAX_DEPTH levels")
            return
        }
        schema.entries.keys.forEach { key ->
            if (key !in ALLOWED_KEYWORDS) {
                diagnostics += diagnostic("$path.$key", "unsupported schema keyword '$key'")
            }
        }

        val type = schema.entries["type"]
        if (type == null) {
            if (topLevel) {
                diagnostics += diagnostic("$path.type", "top-level inputSchema requires type object")
            }
        } else {
            validateType(type, "$path.type", topLevel, diagnostics)
        }

        val properties = schema.entries["properties"]
        if (properties != null && properties !is JsonValue.Obj) {
            diagnostics += diagnostic("$path.properties", "properties must be an object")
        }
        if (properties is JsonValue.Obj) {
            properties.entries.forEach { (name, propertySchema) ->
                when (propertySchema) {
                    is JsonValue.Obj -> validateSchema(
                        propertySchema,
                        "$path.properties.$name",
                        topLevel = false,
                        depth = depth + 1,
                        diagnostics = diagnostics,
                    )

                    is JsonValue.Bool -> Unit
                    else -> diagnostics += diagnostic(
                        "$path.properties.$name",
                        "property schema must be an object or boolean",
                    )
                }
            }
        }

        val required = schema.entries["required"]
        if (required != null) {
            val requiredArray = required as? JsonValue.Arr
            if (requiredArray == null) {
                diagnostics += diagnostic("$path.required", "required must be an array of strings")
            } else {
                val seen = mutableSetOf<String>()
                requiredArray.items.forEachIndexed { index, item ->
                    val name = (item as? JsonValue.Str)?.value
                    if (name == null || name.isBlank()) {
                        diagnostics += diagnostic(
                            "$path.required[$index]",
                            "required entry must be a non-blank string",
                        )
                    } else if (!seen.add(name)) {
                        diagnostics += diagnostic(
                            "$path.required[$index]",
                            "duplicate required entry '$name'",
                        )
                    } else if (properties !is JsonValue.Obj) {
                        diagnostics += diagnostic(
                            "$path.required[$index]",
                            "required entries need a properties object",
                        )
                    } else if (name !in properties.entries) {
                        diagnostics += diagnostic(
                            "$path.required[$index]",
                            "required property '$name' is not declared in properties",
                        )
                    }
                }
            }
        }

        schema.entries["additionalProperties"]?.let { value ->
            validateSchemaOrBoolean(value, "$path.additionalProperties", depth + 1, diagnostics)
        }

        schema.entries["items"]?.let { value ->
            when (value) {
                is JsonValue.Obj -> validateSchema(
                    value,
                    "$path.items",
                    topLevel = false,
                    depth = depth + 1,
                    diagnostics = diagnostics,
                )

                is JsonValue.Bool -> Unit
                is JsonValue.Arr -> {
                    if (value.items.isEmpty()) {
                        diagnostics += diagnostic("$path.items", "items array must not be empty")
                    }
                    value.items.forEachIndexed { index, item ->
                        validateSchemaOrBoolean(item, "$path.items[$index]", depth + 1, diagnostics)
                    }
                }

                else -> diagnostics += diagnostic(
                    "$path.items",
                    "items must be an object, boolean or array of schemas",
                )
            }
        }

        listOf("allOf", "anyOf", "oneOf").forEach { key ->
            val value = schema.entries[key] ?: return@forEach
            val array = value as? JsonValue.Arr
            if (array == null) {
                diagnostics += diagnostic("$path.$key", "$key must be an array of schemas")
            } else {
                if (array.items.isEmpty()) {
                    diagnostics += diagnostic("$path.$key", "$key must not be empty")
                }
                array.items.forEachIndexed { index, item ->
                    validateSchemaOrBoolean(item, "$path.$key[$index]", depth + 1, diagnostics)
                }
            }
        }

        schema.entries["not"]?.let { value ->
            validateSchemaOrBoolean(value, "$path.not", depth + 1, diagnostics)
        }

        listOf("\$defs", "definitions").forEach { key ->
            val value = schema.entries[key] ?: return@forEach
            val definitions = value as? JsonValue.Obj
            if (definitions == null) {
                diagnostics += diagnostic("$path.$key", "$key must be an object of schemas")
            } else {
                definitions.entries.forEach { (name, definition) ->
                    validateSchemaOrBoolean(
                        definition,
                        "$path.$key.$name",
                        depth + 1,
                        diagnostics,
                    )
                }
            }
        }

        schema.entries["enum"]?.let { value ->
            validateArray(value, "$path.enum", nonEmpty = true, diagnostics)
        }
        schema.entries["examples"]?.let { value ->
            validateArray(value, "$path.examples", nonEmpty = false, diagnostics)
        }

        schema.entries["\$schema"]?.let { value ->
            val uri = (value as? JsonValue.Str)?.value
            if (uri == null) {
                diagnostics += diagnostic("$path.\$schema", "\$schema must be a string")
            } else if (uri != DIALECT_URI) {
                diagnostics += diagnostic(
                    "$path.\$schema",
                    "unsupported schema dialect '$uri'; expected $DIALECT_URI",
                )
            }
        }

        listOf("format", "\$id", "title", "description").forEach { key ->
            val value = schema.entries[key] ?: return@forEach
            if (value !is JsonValue.Str) {
                diagnostics += diagnostic("$path.$key", "$key must be a string")
            }
        }

        val minLength = validateIntegerKeyword(schema, "minLength", path, diagnostics)
        val maxLength = validateIntegerKeyword(schema, "maxLength", path, diagnostics)
        val minItems = validateIntegerKeyword(schema, "minItems", path, diagnostics)
        val maxItems = validateIntegerKeyword(schema, "maxItems", path, diagnostics)
        val minProperties = validateIntegerKeyword(schema, "minProperties", path, diagnostics)
        val maxProperties = validateIntegerKeyword(schema, "maxProperties", path, diagnostics)
        validateIntegerPair(minLength, maxLength, "minLength", "maxLength", path, diagnostics)
        validateIntegerPair(minItems, maxItems, "minItems", "maxItems", path, diagnostics)
        validateIntegerPair(
            minProperties,
            maxProperties,
            "minProperties",
            "maxProperties",
            path,
            diagnostics,
        )

        val minimum = validateNumberKeyword(schema, "minimum", path, diagnostics)
        val maximum = validateNumberKeyword(schema, "maximum", path, diagnostics)
        val exclusiveMinimum = validateNumberKeyword(schema, "exclusiveMinimum", path, diagnostics)
        val exclusiveMaximum = validateNumberKeyword(schema, "exclusiveMaximum", path, diagnostics)
        validateNumberPair(minimum, maximum, "minimum", "maximum", path, diagnostics)
        validateNumberPair(
            exclusiveMinimum,
            exclusiveMaximum,
            "exclusiveMinimum",
            "exclusiveMaximum",
            path,
            diagnostics,
        )

        val multipleOf = validateNumberKeyword(schema, "multipleOf", path, diagnostics)
        if (multipleOf != null && multipleOf.signum() <= 0) {
            diagnostics += diagnostic("$path.multipleOf", "multipleOf must be strictly positive")
        }

        listOf("uniqueItems", "deprecated", "readOnly", "writeOnly").forEach { key ->
            val value = schema.entries[key] ?: return@forEach
            if (value !is JsonValue.Bool) {
                diagnostics += diagnostic("$path.$key", "$key must be a boolean")
            }
        }
    }

    private fun validateIntegerKeyword(
        schema: JsonValue.Obj,
        key: String,
        path: String,
        diagnostics: MutableList<PackDiagnostic>,
    ): BigDecimal? {
        val value = schema.entries[key] ?: return null
        val number = numberValue(value)
        if (number == null || !isIntegerNumber(number) || number.signum() < 0) {
            diagnostics += diagnostic("$path.$key", "$key must be a non-negative integer")
            return null
        }
        return number
    }

    private fun validateNumberKeyword(
        schema: JsonValue.Obj,
        key: String,
        path: String,
        diagnostics: MutableList<PackDiagnostic>,
    ): BigDecimal? {
        val value = schema.entries[key] ?: return null
        val number = numberValue(value)
        if (number == null) {
            diagnostics += diagnostic("$path.$key", "$key must be a number")
        }
        return number
    }

    private fun validateIntegerPair(
        minimum: BigDecimal?,
        maximum: BigDecimal?,
        minimumKey: String,
        maximumKey: String,
        path: String,
        diagnostics: MutableList<PackDiagnostic>,
    ) {
        if (minimum != null && maximum != null && minimum > maximum) {
            diagnostics += diagnostic("$path.$minimumKey", "$minimumKey must be <= $maximumKey")
        }
    }

    private fun validateNumberPair(
        minimum: BigDecimal?,
        maximum: BigDecimal?,
        minimumKey: String,
        maximumKey: String,
        path: String,
        diagnostics: MutableList<PackDiagnostic>,
    ) {
        if (minimum != null && maximum != null && minimum > maximum) {
            diagnostics += diagnostic("$path.$minimumKey", "$minimumKey must be <= $maximumKey")
        }
    }

    private fun numberValue(value: JsonValue): BigDecimal? =
        (value as? JsonValue.Num)?.raw?.toBigDecimalOrNull()

    private fun isIntegerNumber(value: BigDecimal): Boolean =
        value.stripTrailingZeros().scale() <= 0

    private fun validateType(
        value: JsonValue,
        path: String,
        topLevel: Boolean,
        diagnostics: MutableList<PackDiagnostic>,
    ) {
        if (topLevel) {
            if (value !is JsonValue.Str || value.value != "object") {
                diagnostics += diagnostic(
                    path,
                    "top-level inputSchema type must be the lowercase string object",
                )
            }
            return
        }
        when (value) {
            is JsonValue.Str -> {
                if (value.value !in TYPE_NAMES) {
                    diagnostics += diagnostic(path, "unsupported JSON Schema type '${value.value}'")
                }
            }

            is JsonValue.Arr -> {
                if (value.items.isEmpty()) {
                    diagnostics += diagnostic(path, "type array must not be empty")
                }
                val seen = mutableSetOf<String>()
                value.items.forEachIndexed { index, item ->
                    val type = (item as? JsonValue.Str)?.value
                    if (type == null || type !in TYPE_NAMES) {
                        diagnostics += diagnostic(
                            "$path[$index]",
                            "unsupported JSON Schema type",
                        )
                    } else if (!seen.add(type)) {
                        diagnostics += diagnostic("$path[$index]", "duplicate JSON Schema type '$type'")
                    }
                }
            }

            else -> diagnostics += diagnostic(
                path,
                "type must be a lowercase string or array of lowercase strings",
            )
        }
    }

    private fun validateSchemaOrBoolean(
        value: JsonValue,
        path: String,
        depth: Int,
        diagnostics: MutableList<PackDiagnostic>,
    ) {
        when (value) {
            is JsonValue.Obj -> validateSchema(
                value,
                path,
                topLevel = false,
                depth = depth,
                diagnostics = diagnostics,
            )

            is JsonValue.Bool -> Unit
            else -> diagnostics += diagnostic(path, "schema must be an object or boolean")
        }
    }

    private fun validateArray(
        value: JsonValue,
        path: String,
        nonEmpty: Boolean,
        diagnostics: MutableList<PackDiagnostic>,
    ) {
        val array = value as? JsonValue.Arr
        if (array == null) {
            diagnostics += diagnostic(path, "must be an array")
        } else if (nonEmpty && array.items.isEmpty()) {
            diagnostics += diagnostic(path, "must not be an empty array")
        }
    }

    private fun diagnostic(path: String, message: String): PackDiagnostic =
        PackDiagnostic(PackErrorCode.MANIFEST_INVALID, message, path)
}