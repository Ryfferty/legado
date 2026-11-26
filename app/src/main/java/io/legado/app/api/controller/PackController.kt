package io.legado.app.api.controller

import io.legado.app.help.pack.PackHost
import io.legado.app.help.pack.PackJson
import io.legado.pack.api.PackCaller
import io.legado.pack.api.PackCancelledException
import io.legado.pack.api.PackDiagnostic
import io.legado.pack.api.PackEntryPoint
import io.legado.pack.api.PackErrorCode
import io.legado.pack.api.PackException
import io.legado.pack.api.PackId
import io.legado.pack.api.PackInvocation
import io.legado.pack.api.PackManager
import io.legado.pack.api.PackResult
import io.legado.pack.api.PackRoutes
import io.legado.pack.api.PackTimeoutException
import io.legado.pack.api.RouteSpec
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import java.util.Locale

/**
 * `/packs/...` HTTP surface.
 *
 * The access token is checked before the request body is read. A pack route is
 * resolved against the current immutable registration snapshot and invoked
 * with the captured [io.legado.pack.api.PackRef]; a stale route therefore
 * cannot be redirected to a newer revision. Unknown pack paths return 404 and
 * never fall through to the static asset handler.
 *
 * Self-test execution (`POST /packs/<id>/selftest`) and report lookup
 * (`GET /packs/<id>/selftest[/<runId>]`) are separate: the GET path never
 * executes package code and can fetch the same `runId` returned to another
 * client.
 */
object PackController {

    const val JSON_CONTENT_TYPE = "application/json; charset=utf-8"

    const val MAX_BODY_BYTES: Long = 1_048_576L

    data class PackHttpResponse(
        val statusCode: Int,
        val body: String,
        val contentType: String = JSON_CONTENT_TYPE,
        val headers: Map<String, String> = emptyMap(),
        val closeConnection: Boolean = false,
    )

    private sealed interface Target {
        data class Route(val packId: PackId, val routeName: String) : Target
        data class SelfTestLatest(val packId: PackId) : Target
        data class SelfTestRun(val packId: PackId, val runId: String) : Target
        data class SelfTestExecute(val packId: PackId) : Target
        data object NotFound : Target
    }

    private class BodyTooLargeException : Exception()

    suspend fun handle(
        method: String,
        uri: String,
        headers: Map<String, String>,
        remoteAddress: String? = null,
        readBody: () -> String? = { null },
        manager: PackManager? = null,
        registry: io.legado.pack.registry.PackRegistry? = null,
        tokenValidator: ((Map<String, String>) -> Boolean)? = null,
    ): PackHttpResponse {
        if (!PackRoutes.isPackPath(uri)) {
            return error(404, "NOT_FOUND", "未知的扩展包路径")
        }

        val tokenAccepted = tokenValidator?.invoke(headers)
            ?: BookSourceController.hasValidJsSourceApiToken(headers)
        if (!tokenAccepted) {
            return error(
                status = 401,
                code = "UNAUTHORIZED",
                message = "Web 访问令牌未配置或不正确",
                closeConnection = true,
            )
        }

        val target = resolve(uri, method)
        if (target == Target.NotFound) {
            return error(404, "NOT_FOUND", "未知的扩展包路径")
        }
        return try {
            val packManager = manager ?: PackHost.manager
            when (target) {
                is Target.Route -> handleRoute(
                    method = method,
                    target = target,
                    headers = headers,
                    remoteAddress = remoteAddress,
                    readBody = readBody,
                    manager = packManager,
                    registry = registry,
                )

                is Target.SelfTestExecute -> handleSelfTestExecute(
                    method = method,
                    target = target,
                    headers = headers,
                    readBody = readBody,
                    manager = packManager,
                )

                is Target.SelfTestLatest -> handleSelfTestLatest(method, target, packManager)
                is Target.SelfTestRun -> handleSelfTestRun(method, target, packManager)
                Target.NotFound -> error(404, "NOT_FOUND", "未知的扩展包路径")
            }
        } catch (timeout: PackTimeoutException) {
            diagnosticResponse(timeout.diagnostic)
        } catch (cancelled: PackCancelledException) {
            diagnosticResponse(cancelled.diagnostic)
        } catch (failure: CancellationException) {
            throw failure
        } catch (ignored: BodyTooLargeException) {
            error(413, "PAYLOAD_TOO_LARGE", "扩展包请求体超过 $MAX_BODY_BYTES 字节")
        } catch (failure: PackException) {
            diagnosticResponse(failure.diagnostic)
        } catch (failure: Exception) {
            error(
                status = 500,
                code = PackErrorCode.INTERNAL.wire,
                message = failure.localizedMessage ?: "扩展包请求失败",
            )
        }
    }

    private suspend fun handleRoute(
        method: String,
        target: Target.Route,
        headers: Map<String, String>,
        remoteAddress: String?,
        readBody: () -> String?,
        manager: PackManager,
        registry: io.legado.pack.registry.PackRegistry?,
    ): PackHttpResponse {
        val snapshot = (registry ?: PackHost.registry).snapshot()
        val registration = snapshot.registrations[target.packId]
            ?: return error(404, "PACK_NOT_FOUND", "扩展包 ${target.packId.value} 未安装或未启用")
        val route = registration.manifest.apiRoutes.firstOrNull { it.name == target.routeName }
            ?: return error(404, "ROUTE_NOT_FOUND", "扩展包路由 ${target.routeName} 不存在")
        val normalizedMethod = method.uppercase(Locale.ROOT)
        if (normalizedMethod != route.method.name) {
            return error(
                status = 405,
                code = "METHOD_NOT_ALLOWED",
                message = "扩展包路由 ${target.routeName} 只接受 ${route.method.name}",
                headers = mapOf("Allow" to route.method.name),
            )
        }
        val body = if (normalizedMethod == "POST") {
            readBodyChecked(headers, readBody)
        } else {
            ""
        }
        val result = manager.invoke(
            PackInvocation(
                expectedRef = registration.ref,
                entry = PackEntryPoint.WebRoute(route.name),
                argJson = body,
                caller = PackCaller.Web(remoteAddress),
            )
        )
        return resultResponse(result)
    }

    private suspend fun handleSelfTestExecute(
        method: String,
        target: Target.SelfTestExecute,
        headers: Map<String, String>,
        readBody: () -> String?,
        manager: PackManager,
    ): PackHttpResponse {
        if (!method.equals("POST", ignoreCase = true)) {
            return error(
                status = 405,
                code = "METHOD_NOT_ALLOWED",
                message = "自检执行只接受 POST",
                headers = mapOf("Allow" to "POST"),
            )
        }
        // Consume and bound the body before running package code.
        readBodyChecked(headers, readBody)
        val report = manager.selfTest(target.packId)
        return PackHttpResponse(
            statusCode = 200,
            body = PackJson.success(PackJson.report(report)).toString(),
        )
    }

    private fun handleSelfTestLatest(
        method: String,
        target: Target.SelfTestLatest,
        manager: PackManager,
    ): PackHttpResponse {
        if (!method.equals("GET", ignoreCase = true)) {
            return error(
                status = 405,
                code = "METHOD_NOT_ALLOWED",
                message = "自检报告查询只接受 GET",
                headers = mapOf("Allow" to "GET"),
            )
        }
        return reportResponse(manager.selfTestReport(target.packId, null))
    }

    private fun handleSelfTestRun(
        method: String,
        target: Target.SelfTestRun,
        manager: PackManager,
    ): PackHttpResponse {
        if (!method.equals("GET", ignoreCase = true)) {
            return error(
                status = 405,
                code = "METHOD_NOT_ALLOWED",
                message = "自检报告查询只接受 GET",
                headers = mapOf("Allow" to "GET"),
            )
        }
        return reportResponse(manager.selfTestReport(target.packId, target.runId))
    }

    private fun reportResponse(report: io.legado.pack.api.SelfTestReport?): PackHttpResponse =
        if (report == null) {
            error(404, "REPORT_NOT_FOUND", "没有找到对应的自检报告")
        } else {
            PackHttpResponse(
                statusCode = 200,
                body = PackJson.success(PackJson.report(report)).toString(),
            )
        }

    private fun readBodyChecked(
        headers: Map<String, String>,
        readBody: () -> String?,
    ): String {
        val declaredLength = header(headers, "content-length")?.trim()?.toLongOrNull()
        if (declaredLength != null && declaredLength > MAX_BODY_BYTES) {
            throw BodyTooLargeException()
        }
        val body = readBody() ?: ""
        if (body.toByteArray(Charsets.UTF_8).size > MAX_BODY_BYTES) {
            throw BodyTooLargeException()
        }
        return body
    }

    private fun resultResponse(result: PackResult): PackHttpResponse = PackHttpResponse(
        statusCode = statusFor(result.code),
        body = PackJson.result(result).toString(),
    )

    private fun diagnosticResponse(diagnostic: PackDiagnostic): PackHttpResponse = error(
        status = statusFor(diagnostic.code.wire),
        code = diagnostic.code.wire,
        message = diagnostic.message,
        data = PackJson.diagnostic(diagnostic),
    )

    private fun error(
        status: Int,
        code: String,
        message: String,
        data: JsonObject? = null,
        headers: Map<String, String> = emptyMap(),
        closeConnection: Boolean = false,
    ): PackHttpResponse = PackHttpResponse(
        statusCode = status,
        body = if (data == null) {
            PackJson.error(code, message).toString()
        } else {
            PackJson.error(code, message, data).toString()
        },
        headers = headers,
        closeConnection = closeConnection,
    )

    private fun statusFor(code: String): Int = when (code) {
        "OK" -> 200
        "UNAUTHORIZED" -> 401
        "METHOD_NOT_ALLOWED" -> 405
        "PAYLOAD_TOO_LARGE" -> 413
        "PERMISSION_DENIED",
        "NETWORK_OPEN_REQUIRES_CONSENT",
        -> 403

        "PACK_NOT_FOUND",
        "COMMAND_NOT_FOUND",
        "ROUTE_NOT_FOUND",
        "REPORT_NOT_FOUND",
        "NOT_FOUND",
        -> 404

        "PACK_DISABLED",
        "TOOL_CONFLICT",
        "ROUTE_CONFLICT",
        "REGISTRATION_CONFLICT",
        -> 409

        "TIMEOUT" -> 504
        "CANCELLED" -> 408
        "NEEDS_RESTART" -> 503
        "INVALID_REQUEST",
        "MANIFEST_INVALID",
        "MANIFEST_SCHEMA_UNSUPPORTED",
        "PACKLIB_INCOMPATIBLE",
        -> 400

        else -> 500
    }

    private fun resolve(uri: String, method: String): Target {
        val path = uri.substringBefore('?')
        if (!path.startsWith(PackRoutes.PREFIX)) return Target.NotFound
        val segments = path.removePrefix(PackRoutes.PREFIX).split('/')
        if (segments.isEmpty() || segments[0].isEmpty()) return Target.NotFound
        val packId = PackId.parse(segments[0]) ?: return Target.NotFound
        val post = method.equals("POST", ignoreCase = true)
        return when {
            segments.size == 3 &&
                segments[1] == "api" &&
                RouteSpec.isValidName(segments[2]) -> Target.Route(packId, segments[2])

            segments.size == 2 && segments[1] == "selftest" ->
                if (post) Target.SelfTestExecute(packId) else Target.SelfTestLatest(packId)

            segments.size == 3 &&
                segments[1] == "selftest" &&
                PackRoutes.isValidRunId(segments[2]) ->
                Target.SelfTestRun(packId, segments[2])

            else -> Target.NotFound
        }
    }

    private fun header(headers: Map<String, String>, name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}