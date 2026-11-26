package io.legado.pack.api

/**
 * Fixed minimal JS entry protocol for JS components.
 *
 * Top-level evaluation happens once on the pack worker under the first call's
 * deadline/cancellation. The script must define an object named
 * [EXPORTS_OBJECT] (`globalThis.__packExports`) whose properties are the
 * declared command names and whose values are callable functions:
 *
 * ```
 * globalThis.__packExports = {
 *   hello: function (arg) { return { ok: true }; }
 * };
 * ```
 *
 * `PackInstance.invoke(command, argJson, call)` resolves exactly
 * `__packExports[command]`, parses `argJson` (empty string means null) into
 * JSON scalars/arrays/objects, calls the function with one argument and
 * serializes the return value to JSON. `undefined` becomes null. Functions,
 * cycles, Java objects, host streams or values exceeding [MAX_JSON_CHARS] /
 * [MAX_VALUE_DEPTH] are rejected; they must never be silently converted.
 *
 * The runtime installs one ordinary JS object named [API_OBJECT]
 * (`globalThis.__packApi`) with the functions represented by
 * [PackJsPrimitiveFacade]. HTTP uses the JSON envelope defined by
 * [PackJsHttpJson]. No PackContext, File, InputStream, ClassLoader, Android
 * object, Java object/proxy or host callback may be passed into JS.
 */
object PackJsProtocol {
    const val EXPORTS_OBJECT: String = "__packExports"
    const val API_OBJECT: String = "__packApi"
    const val MAX_JSON_CHARS: Int = 1_048_576
    const val MAX_VALUE_DEPTH: Int = 64

    /** Commands map one-to-one to named function properties. */
    fun commandExportName(command: String): String {
        require(CommandSpec.isValidName(command)) { "invalid command export name: '$command'" }
        return command
    }
}

/**
 * Host-side backing functions for the fixed JS `__packApi` object. All
 * arguments/results crossing into JS are strings, numbers, booleans, null,
 * arrays or plain objects; binary data is Base64 text.
 *
 * This interface is never exposed to JS as an object. A JS runtime wraps each
 * function with a Rhino function that copies values and returns only JS
 * primitives/plain values. The current [PackCallContext] is supplied by the
 * runtime, not by script arguments.
 */
interface PackJsPrimitiveFacade {
    fun kvGet(key: String): String?

    fun kvPut(key: String, value: String)

    fun kvRemove(key: String)

    fun log(level: String, message: String)

    fun invokeComponent(
        componentId: String,
        command: String,
        argJson: String,
        call: PackCallContext,
    ): String

    /**
     * Accepts/returns JSON strings defined by [PackJsHttpJson]; no streams or
     * Java objects cross the boundary. The runtime JSON-serializes the plain JS
     * request object and JSON-parses the returned response string. Non-2xx
     * statuses remain response objects; transport/policy failures throw typed
     * [PackException]s.
     */
    fun http(requestJson: String, call: PackCallContext): String

    fun cryptoDigest(algorithm: String, dataBase64: String): String

    fun cryptoHmac(algorithm: String, keyBase64: String, dataBase64: String): String

    fun cryptoCipher(
        spec: String,
        keyBase64: String,
        ivBase64: String?,
        dataBase64: String,
    ): String
}

/**
 * ABI 1 constants shared by the Kotlin bridge and `src/main/cpp/pack_api_v1.h`.
 *
 * The host loads only [ENTRY_SYMBOL]. `open` receives an already-validated
 * read-only private path, [MODE_FILE] or [MODE_MEMFD], [ABI_VERSION], and the
 * same first-call context as the first invoke. `open`-time initialization must
 * honor that context's deadline/cancellation.
 * Each invoke receives a `pack_call_v1` with a host-owned call handle,
 * thread-safe [CALL_ACTIVE]/[CALL_CANCELLED]/[CALL_TIMEOUT] check, a signed
 * System.nanoTime-compatible `now_nanos`/`deadline_nanos` pair and
 * retain/release callbacks. The call state stays valid until native references
 * are released; timeout/cancel never authorizes use-after-release. The C
 * header defines the wrap-safe deadline comparison.
 *
 * The returned handle is owned by the host bridge. `invoke` writes a UTF-8 JSON
 * buffer allocated by the pack and returns it through `free_buffer`; the host
 * copies the bytes before calling free. `close` returns [CLOSE_RELEASED] only
 * when no call/thread/retained context/callback still references the library;
 * otherwise [CLOSE_STILL_RESIDENT] and the host must not dlclose. Errors are
 * negative values and must not carry a partially written buffer.
 */
object PackNativeAbiV1 {
    const val ABI_VERSION: Int = 1
    const val ENTRY_SYMBOL: String = "pack_get_api_v1"

    const val MODE_FILE: Int = 0
    const val MODE_MEMFD: Int = 1

    const val OPEN_OK: Int = 0
    const val CLOSE_RELEASED: Int = 0
    const val CLOSE_STILL_RESIDENT: Int = 1

    const val CALL_ACTIVE: Int = 0
    const val CALL_CANCELLED: Int = 1
    const val CALL_TIMEOUT: Int = 2

    const val ERROR_INVALID_ARGUMENT: Int = -1
    const val ERROR_LOAD_FAILED: Int = -2
    const val ERROR_CANCELLED: Int = -3
    const val ERROR_TIMEOUT: Int = -4

    fun modeOf(mode: NativeLoadMode): Int = when (mode) {
        NativeLoadMode.FILE -> MODE_FILE
        NativeLoadMode.MEMFD -> MODE_MEMFD
    }
}