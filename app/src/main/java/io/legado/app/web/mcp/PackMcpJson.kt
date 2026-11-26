package io.legado.app.web.mcp

import io.legado.app.help.pack.PackJson
import io.legado.pack.api.PackDiagnostic
import io.legado.pack.api.PackResult
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * MCP-specific wrapper around the shared pack envelope. The human-readable
 * text block and `structuredContent` carry the same object, and `isError` is
 * mirrored both inside the JSON and in the MCP result.
 */
internal object PackMcpJson {

    fun result(result: PackResult): CallToolResult =
        fromPayload(PackJson.result(result), result.isError)

    fun success(
        data: JsonElement = JsonNull,
        message: String? = null,
    ): CallToolResult = fromPayload(PackJson.success(data, message), false)

    fun error(
        code: String,
        message: String,
        data: JsonElement = JsonNull,
    ): CallToolResult = fromPayload(PackJson.error(code, message, data), true)

    fun diagnostic(diagnostic: PackDiagnostic): CallToolResult =
        fromPayload(
            PackJson.payload(
                code = diagnostic.code.wire,
                data = PackJson.diagnostic(diagnostic),
                message = diagnostic.message,
                isError = true,
            ),
            true,
        )

    private fun fromPayload(payload: JsonObject, isError: Boolean): CallToolResult =
        CallToolResult(
            content = listOf(TextContent(payload.toString())),
            isError = isError,
            structuredContent = payload,
        )
}