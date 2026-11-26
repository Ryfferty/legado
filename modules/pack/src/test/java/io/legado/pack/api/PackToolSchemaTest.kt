package io.legado.pack.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PackToolSchemaTest {

    @Test
    fun `missing top-level type is normalized before validation`() {
        val raw = JsonValue.Obj(
            mapOf(
                "properties" to JsonValue.Obj(
                    mapOf("query" to JsonValue.Obj(mapOf("type" to JsonValue.Str("string")))),
                ),
            ),
        )

        val normalized = PackToolSchema.normalizeObjectSchema(raw)

        assertEquals("object", (normalized["type"] as JsonValue.Str).value)
        assertTrue(PackToolSchema.isValid(normalized))
    }

    @Test
    fun `top-level type must be lowercase object`() {
        val diagnostics = PackToolSchema.validate(
            JsonValue.Obj(mapOf("type" to JsonValue.Str("OBJECT"))),
        )

        assertTrue(diagnostics.any { it.path == "$.type" && it.code == PackErrorCode.MANIFEST_INVALID })
    }

    @Test
    fun `nested schema types are validated recursively`() {
        val schema = JsonValue.Obj(
            mapOf(
                "type" to JsonValue.Str("object"),
                "properties" to JsonValue.Obj(
                    mapOf(
                        "a" to JsonValue.Obj(
                            mapOf("type" to JsonValue.Num("17")),
                        ),
                    ),
                ),
            ),
        )

        val diagnostics = PackToolSchema.validate(schema)

        assertTrue(diagnostics.any { it.path == "$.properties.a.type" })
    }

    @Test
    fun `unsupported schema keyword is rejected`() {
        val schema = JsonValue.Obj(
            mapOf(
                "type" to JsonValue.Str("object"),
                "\$ref" to JsonValue.Str("#/definitions/x"),
            ),
        )

        assertTrue(PackToolSchema.validate(schema).any { it.path == "$.\$ref" })
    }

    @Test
    fun `supported nested schema validates`() {
        val schema = JsonValue.Obj(
            mapOf(
                "type" to JsonValue.Str("object"),
                "properties" to JsonValue.Obj(
                    mapOf(
                        "query" to JsonValue.Obj(
                            mapOf(
                                "type" to JsonValue.Str("string"),
                                "minLength" to JsonValue.Num("1"),
                            ),
                        ),
                        "limit" to JsonValue.Obj(
                            mapOf(
                                "type" to JsonValue.Str("integer"),
                                "minimum" to JsonValue.Num("1"),
                                "maximum" to JsonValue.Num("100"),
                            ),
                        ),
                        "tags" to JsonValue.Obj(
                            mapOf(
                                "type" to JsonValue.Str("array"),
                                "items" to JsonValue.Obj(
                                    mapOf("type" to JsonValue.Str("string")),
                                ),
                            ),
                        ),
                        "filters" to JsonValue.Obj(
                            mapOf(
                                "type" to JsonValue.Str("object"),
                                "properties" to JsonValue.Obj(
                                    mapOf(
                                        "active" to JsonValue.Obj(
                                            mapOf("type" to JsonValue.Str("boolean")),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
                "required" to JsonValue.Arr(listOf(JsonValue.Str("query"))),
                "additionalProperties" to JsonValue.Bool(false),
            ),
        )

        assertTrue(PackToolSchema.validate(schema).isEmpty())
    }

    @Test
    fun `invalid numeric constraints and pattern are rejected`() {
        val schema = JsonValue.Obj(
            mapOf(
                "type" to JsonValue.Str("object"),
                "properties" to JsonValue.Obj(
                    mapOf(
                        "a" to JsonValue.Obj(
                            mapOf(
                                "type" to JsonValue.Str("string"),
                                "minLength" to JsonValue.Num("-1.5"),
                            ),
                        ),
                        "b" to JsonValue.Obj(
                            mapOf(
                                "type" to JsonValue.Str("array"),
                                "maxItems" to JsonValue.Num("-1"),
                            ),
                        ),
                        "c" to JsonValue.Obj(
                            mapOf(
                                "type" to JsonValue.Str("number"),
                                "multipleOf" to JsonValue.Num("0"),
                            ),
                        ),
                        "d" to JsonValue.Obj(
                            mapOf(
                                "type" to JsonValue.Str("string"),
                                "pattern" to JsonValue.Str("["),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val diagnostics = PackToolSchema.validate(schema)

        assertTrue(diagnostics.any { it.path == "$.properties.a.minLength" })
        assertTrue(diagnostics.any { it.path == "$.properties.b.maxItems" })
        assertTrue(diagnostics.any { it.path == "$.properties.c.multipleOf" })
        assertTrue(diagnostics.any { it.path == "$.properties.d.pattern" })
    }

    @Test
    fun `schema dialect must match the supported subset`() {
        val valid = JsonValue.Obj(
            mapOf(
                "\$schema" to JsonValue.Str(PackToolSchema.DIALECT_URI),
                "type" to JsonValue.Str("object"),
            ),
        )
        assertTrue(PackToolSchema.validate(valid).isEmpty())

        val invalid = JsonValue.Obj(
            mapOf(
                "\$schema" to JsonValue.Str("https://json-schema.org/draft/2020-12/schema"),
                "type" to JsonValue.Str("object"),
            ),
        )
        assertTrue(PackToolSchema.validate(invalid).any { it.path == "$.\$schema" })
    }

    @Test
    fun `valid numeric constraints pass`() {
        val schema = JsonValue.Obj(
            mapOf(
                "type" to JsonValue.Str("object"),
                "properties" to JsonValue.Obj(
                    mapOf(
                        "text" to JsonValue.Obj(
                            mapOf(
                                "type" to JsonValue.Str("string"),
                                "minLength" to JsonValue.Num("1"),
                                "maxLength" to JsonValue.Num("10"),
                            ),
                        ),
                        "amount" to JsonValue.Obj(
                            mapOf(
                                "type" to JsonValue.Str("number"),
                                "multipleOf" to JsonValue.Num("0.5"),
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertTrue(PackToolSchema.validate(schema).isEmpty())
    }

    @Test
    fun `invalid schema cannot be stored in ToolSpec`() {
        assertThrows(IllegalArgumentException::class.java) {
            ToolSpec(
                name = "demo_add",
                command = "hello",
                inputSchema = JsonValue.Obj(mapOf("type" to JsonValue.Str("OBJECT"))),
            )
            Unit
        }
    }
}