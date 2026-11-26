package io.legado.pack.primitives

import io.legado.pack.api.PackCallContext
import io.legado.pack.api.PackComponentInvoker
import io.legado.pack.api.PackContext
import io.legado.pack.api.PackCrypto
import io.legado.pack.api.PackHttpClient
import io.legado.pack.api.PackJsProtocol
import io.legado.pack.api.PackKeyValueStore
import io.legado.pack.api.PackLogger
import io.legado.pack.api.PackManifest
import io.legado.pack.api.PackPlatform
import io.legado.pack.api.PackRef
import io.legado.pack.api.PackResourceLimits
import io.legado.pack.api.PackStorage
import io.legado.pack.api.PackDiagnostic
import io.legado.pack.api.PackErrorCode
import io.legado.pack.api.PackException

internal class PackPrimitivesContext(
    override val ref: PackRef,
    override val manifest: PackManifest,
    override val platform: PackPlatform,
    override val storage: PackStorage,
    override val kv: PackKeyValueStore,
    override val http: PackHttpClient,
    override val crypto: PackCrypto,
    override val logger: PackLogger,
    override val components: PackComponentInvoker,
    override val limits: PackResourceLimits,
    private val httpClient: PackHttpClientImpl?,
) : PackContext, AutoCloseable {

    override fun close() {
        httpClient?.close()
    }
}

internal class PackComponentInvokerImpl(
    private val ref: PackRef,
    private val guard: PackEpochGuard,
    private val invoke: (componentId: String, command: String, argJson: String, call: PackCallContext) -> String,
) : PackComponentInvoker {

    override fun invoke(
        componentId: String,
        command: String,
        argJson: String,
        call: PackCallContext,
    ): String {
        guard.ensureActive()
        if (call.packRef != ref) {
            throw PackException(
                PackDiagnostic(
                    code = PackErrorCode.INVALID_REQUEST,
                    message = "component call context belongs to another revision",
                    path = "components.$componentId",
                ),
            )
        }
        if (argJson.length > PackJsProtocol.MAX_JSON_CHARS) {
            throw PackException(
                PackDiagnostic(
                    code = PackErrorCode.IMPORT_LIMIT_EXCEEDED,
                    message = "component argument exceeds ${PackJsProtocol.MAX_JSON_CHARS} characters",
                    path = "components.$componentId",
                ),
            )
        }
        call.checkActive()
        val result = invoke(componentId, command, argJson, call)
        call.checkActive()
        return result
    }
}