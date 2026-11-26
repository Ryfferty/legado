package io.legado.app.help.pack

import io.legado.pack.api.JsonValue
import io.legado.pack.api.PackDiagnostic
import io.legado.pack.api.PackErrorCode
import io.legado.pack.api.PackResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class PackJsonTest {

    @Test
    fun `pack results use the structured envelope`() {
        val success = PackJson.result(
            PackResult.ok("req-1", "{\"value\":7}", 5L)
        )
        assertEquals("OK", success["code"]!!.jsonPrimitive.content)
        assertEquals(7, success["data"]!!.jsonObject["value"]!!.jsonPrimitive.int)
        assertFalse(success["isError"]!!.jsonPrimitive.boolean)

        val failure = PackJson.result(
            PackResult.failure(
                "req-2",
                PackDiagnostic(PackErrorCode.PERMISSION_DENIED, "denied"),
                3L,
            )
        )
        assertEquals("PERMISSION_DENIED", failure["code"]!!.jsonPrimitive.content)
        assertTrue(failure["isError"]!!.jsonPrimitive.boolean)
        assertEquals("denied", failure["message"]!!.jsonPrimitive.content)
    }

    @Test
    fun `self test report is serialized without losing run identity`() {
        val report = PackFixtures.report()
        val json = PackJson.report(report)

        assertEquals("run_1", json["runId"]!!.jsonPrimitive.content)
        assertEquals("demo.pack", json["pack"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertTrue(json["passed"]!!.jsonPrimitive.boolean)
        assertEquals("PASS", json["cases"]!!.jsonArray[0].jsonObject["status"]!!.jsonPrimitive.content)
        assertEquals(1, json["cases"]!!.jsonArray.size)
    }

    @Test
    fun `pack info reports declared write tools and default denied state`() {
        val root = Files.createTempDirectory("pack-json").toFile()
        val manifest = PackFixtures.manifest(toolName = "demo_write", isWrite = true)
        val info = PackFixtures.info(manifest)
        val store = PackToolPermissionStore(root.resolve("grants.json"))
        val json = PackJson.info(info, store)

        val tool = json["tools"]!!.jsonArray[0].jsonObject
        assertEquals("demo_write", tool["name"]!!.jsonPrimitive.content)
        assertTrue(tool["isWrite"]!!.jsonPrimitive.boolean)
        assertFalse(tool["writeEnabled"]!!.jsonPrimitive.boolean)
        assertEquals("demo.pack", json["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `mcp schema conversion keeps supported structure and rejects lossy top level keywords`() {
        val schema = PackFixtures.schema()
        val converted = PackMcpSchema.toToolSchema(schema)
        assertNotNull(converted)
        assertEquals(
            "string",
            converted!!.properties!!["value"]!!.jsonObject["type"]!!.jsonPrimitive.content
        )
        assertEquals(listOf("value"), converted.required)

        val lossy = JsonValue.Obj(
            mapOf(
                "type" to JsonValue.Str("object"),
                "allOf" to JsonValue.Arr(emptyList<JsonValue>()),
            )
        )
        assertNull(PackMcpSchema.toToolSchema(lossy))
        assertEquals(listOf("allOf"), PackMcpSchema.unsupportedKeywords(lossy))

        val nested = JsonValue.Obj(
            mapOf(
                "type" to JsonValue.Str("object"),
                "properties" to JsonValue.Obj(
                    mapOf(
                        "value" to JsonValue.Obj(
                            mapOf("allOf" to JsonValue.Arr(emptyList<JsonValue>()))
                        )
                    )
                ),
            )
        )
        val nestedConverted = PackMcpSchema.toToolSchema(nested)!!
        assertTrue(
            nestedConverted.properties!!["value"]!!.jsonObject.containsKey("allOf")
        )
    }

    @Test
    fun `invalid data json is preserved instead of being dropped`() {
        val result = PackJson.result(PackResult.ok("req", "not-json", 1L))
        assertEquals("not-json", result["data"]!!.jsonPrimitive.content)
        assertEquals(Json.parseToJsonElement("{\"a\":1}").jsonObject["a"]!!.jsonPrimitive.int, 1)
    }
}