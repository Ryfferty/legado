package io.legado.pack.runtime

import io.legado.pack.api.*
import io.legado.pack.manifest.JsonStructureException
import io.legado.pack.manifest.StrictJsonParser
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

internal fun runtimeFailure(code: PackErrorCode, message: String, cause: Throwable? = null): Nothing =
    throw PackException(PackDiagnostic(code, message), cause)

internal fun verifyBinding(ref: PackRef, component: ComponentSpec, context: PackContext, call: PackCallContext) {
    call.checkActive()
    if (ref != context.ref || call.packRef != ref || context.manifest.componentById(component.id) != component) {
        runtimeFailure(PackErrorCode.PERMISSION_DENIED, "runtime identity/component does not match admitted call")
    }
}

internal fun verifyCommand(context: PackContext, componentId: String, command: String, call: PackCallContext) {
    call.checkActive()
    if (call.packRef != context.ref) runtimeFailure(PackErrorCode.PERMISSION_DENIED, "stale runtime call")
    if (context.manifest.commandByName(command)?.componentId != componentId) {
        runtimeFailure(PackErrorCode.COMMAND_NOT_FOUND, "command '$command' is not declared by '$componentId'")
    }
}

internal fun readResource(context: PackContext, path: RelativePath, call: PackCallContext, maxBytes: Long): ByteArray {
    call.checkActive()
    if (!context.storage.resourceExists(path)) {
        runtimeFailure(PackErrorCode.COMPONENT_MISSING, "missing component resource: ${path.value}")
    }
    try {
        return context.storage.openResource(path).use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var size = 0L
            while (true) {
                call.checkActive()
                val count = input.read(buffer)
                if (count == -1) break
                if (count == 0) continue
                size += count
                if (size > maxBytes || size > Int.MAX_VALUE) {
                    runtimeFailure(PackErrorCode.IMPORT_LIMIT_EXCEEDED, "component exceeds $maxBytes bytes")
                }
                out.write(buffer, 0, count)
            }
            call.checkActive()
            out.toByteArray()
        }
    } catch (e: java.io.IOException) {
        runtimeFailure(PackErrorCode.IO_FAILED, "cannot read component: ${path.value}", e)
    }
}

internal fun decodeUtf8(bytes: ByteArray): String = try {
    Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
} catch (e: java.nio.charset.CharacterCodingException) {
    runtimeFailure(PackErrorCode.LOAD_FAILED, "component/result is not valid UTF-8", e)
}

internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

internal object RuntimeJson {
    fun parse(json: String, call: PackCallContext, maxChars: Int = PackJsProtocol.MAX_JSON_CHARS): JsonValue {
        call.checkActive()
        if (json.length > maxChars) runtimeFailure(PackErrorCode.IMPORT_LIMIT_EXCEEDED, "JSON exceeds $maxChars characters")
        val value = try {
            StrictJsonParser(maxDepth = PackJsProtocol.MAX_VALUE_DEPTH, maxStringChars = maxChars).parse(json.ifEmpty { "null" })
        } catch (e: JsonStructureException) {
            runtimeFailure(PackErrorCode.LOAD_FAILED, "invalid runtime JSON: ${e.message}", e)
        }
        call.checkActive()
        return value
    }
}
