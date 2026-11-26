package io.legado.pack.api

import java.util.concurrent.CancellationException

enum class PackSeverity {
    INFO,
    WARNING,

    /** A hard failure that must be shown to the user; silent fallback is forbidden. */
    RED_CARD,
}

/**
 * Stable error/warning codes for diagnostics, results and platform transports.
 *
 * New codes may be added; existing wire values must not change.
 */
enum class PackErrorCode(val wire: String, val severity: PackSeverity) {
    MANIFEST_INVALID("MANIFEST_INVALID", PackSeverity.RED_CARD),
    MANIFEST_SCHEMA_UNSUPPORTED("MANIFEST_SCHEMA_UNSUPPORTED", PackSeverity.RED_CARD),
    PACKLIB_INCOMPATIBLE("PACKLIB_INCOMPATIBLE", PackSeverity.RED_CARD),
    COMPONENT_MISSING("COMPONENT_MISSING", PackSeverity.RED_CARD),
    PERMISSION_DENIED("PERMISSION_DENIED", PackSeverity.RED_CARD),
    TOOL_CONFLICT("TOOL_CONFLICT", PackSeverity.RED_CARD),
    ROUTE_CONFLICT("ROUTE_CONFLICT", PackSeverity.RED_CARD),
    REGISTRATION_CONFLICT("REGISTRATION_CONFLICT", PackSeverity.RED_CARD),
    NATIVE_PLATFORM("NATIVE_PLATFORM", PackSeverity.RED_CARD),
    LOAD_FAILED("LOAD_FAILED", PackSeverity.RED_CARD),
    TIMEOUT("TIMEOUT", PackSeverity.RED_CARD),
    NEEDS_RESTART("NEEDS_RESTART", PackSeverity.RED_CARD),
    RESIDUAL_FILES("RESIDUAL_FILES", PackSeverity.RED_CARD),
    CRYPTO_UNSUPPORTED("CRYPTO_UNSUPPORTED", PackSeverity.RED_CARD),
    PACK_NOT_FOUND("PACK_NOT_FOUND", PackSeverity.RED_CARD),
    PACK_DISABLED("PACK_DISABLED", PackSeverity.RED_CARD),
    COMMAND_NOT_FOUND("COMMAND_NOT_FOUND", PackSeverity.RED_CARD),
    IMPORT_LIMIT_EXCEEDED("IMPORT_LIMIT_EXCEEDED", PackSeverity.RED_CARD),
    IO_FAILED("IO_FAILED", PackSeverity.RED_CARD),
    SELFTEST_FAILED("SELFTEST_FAILED", PackSeverity.RED_CARD),
    UNSUPPORTED("UNSUPPORTED", PackSeverity.RED_CARD),
    INTERNAL("INTERNAL", PackSeverity.RED_CARD),
    INVALID_REQUEST("INVALID_REQUEST", PackSeverity.WARNING),
    NETWORK_OPEN_REQUIRES_CONSENT("NETWORK_OPEN_REQUIRES_CONSENT", PackSeverity.WARNING),
    CANCELLED("CANCELLED", PackSeverity.INFO),
}

/**
 * Single diagnostic item. [severity] deliberately derives from [code] so a
 * red-card failure cannot be downgraded by a caller.
 */
data class PackDiagnostic(
    val code: PackErrorCode,
    val message: String,
    val path: String? = null,
    val details: Map<String, String> = emptyMap(),
) {
    init {
        require(message.isNotBlank()) { "diagnostic message must not be blank" }
    }

    val severity: PackSeverity get() = code.severity
    val isRedCard: Boolean get() = severity == PackSeverity.RED_CARD
}

/** Typed failure for APIs that throw instead of returning a result object. */
class PackException(
    val diagnostic: PackDiagnostic,
    cause: Throwable? = null,
) : Exception("[${diagnostic.code.wire}] ${diagnostic.message}", cause)

/**
 * Cooperative cancellation. It extends [CancellationException] so coroutine
 * cancellation semantics remain intact; it is not a hard kill signal.
 */
class PackCancelledException(
    val reason: String,
) : CancellationException("pack call cancelled: $reason") {
    val diagnostic: PackDiagnostic = PackDiagnostic(
        code = PackErrorCode.CANCELLED,
        message = "pack call cancelled: $reason",
    )
}

/**
 * The framework deadline expired. Runtime implementations must stop scheduling
 * new work and return this failure; a blocked thread may still be running.
 */
class PackTimeoutException(
    val requestId: String,
    val timeoutMs: Long? = null,
) : CancellationException(
    "pack call timed out: $requestId" + (timeoutMs?.let { " after $it ms" } ?: ""),
) {
    val diagnostic: PackDiagnostic = PackDiagnostic(
        code = PackErrorCode.TIMEOUT,
        message = "pack call timed out: $requestId" + (timeoutMs?.let { " after $it ms" } ?: ""),
        details = buildMap {
            put("requestId", requestId)
            timeoutMs?.let { put("timeoutMs", it.toString()) }
        },
    )
}