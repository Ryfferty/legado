package io.legado.pack.api

import java.io.File
import java.io.InputStream

/**
 * Import/execution bounds. These are host limits, not suggestions; a future
 * manager/primitive implementation must reject before allocating beyond them.
 */
data class PackResourceLimits(
    val maxManifestChars: Int = 1_048_576,
    val maxPackBytes: Long = 64L * 1024 * 1024,
    val maxUncompressedBytes: Long = 256L * 1024 * 1024,
    val maxFileCount: Int = 4_096,
    val maxSingleFileBytes: Long = 64L * 1024 * 1024,
    val maxKvKeyChars: Int = 256,
    val maxKvValueBytes: Int = 1024 * 1024,
    val maxLogBytesPerPack: Long = 5L * 1024 * 1024,
    val maxLogBytesTotal: Long = 100_000_000L,
    val maxHttpResponseBytes: Long = 16L * 1024 * 1024,
) {
    init {
        require(maxManifestChars > 0)
        require(maxPackBytes > 0L)
        require(maxUncompressedBytes >= maxPackBytes) {
            "maxUncompressedBytes must be >= maxPackBytes"
        }
        require(maxFileCount > 0)
        require(maxSingleFileBytes in 1L..maxUncompressedBytes)
        require(maxKvKeyChars > 0)
        require(maxKvValueBytes > 0)
        require(maxLogBytesPerPack > 0L)
        require(maxLogBytesTotal in maxLogBytesPerPack..100_000_000L) {
            "maxLogBytesTotal must be >= per-pack budget and <= 100000000"
        }
        require(maxHttpResponseBytes > 0L)
    }

    companion object {
        const val HARD_LOG_BUDGET_BYTES: Long = 100_000_000L
    }
}

/**
 * Read-only access to the currently loaded revision plus the pack's private
 * data directory. No API exposes the pack root; implementations must reject
 * paths outside the intended directory even though [RelativePath] is already
 * normalized.
 */
interface PackStorage {
    /** Opens a file from the active code revision. Caller closes the stream. */
    fun openResource(relative: RelativePath): InputStream

    fun resourceExists(relative: RelativePath): Boolean

    /**
     * Returns a path below this pack's private `data/` directory. The
     * implementation verifies containment (including symlinks) and never
     * returns a shared host directory.
     */
    fun dataFile(relative: RelativePath): File
}

/** Per-pack, epoch-validated key/value store. Keys never cross packs. */
interface PackKeyValueStore {
    fun get(key: String): String?

    fun put(key: String, value: String)

    fun remove(key: String)

    fun keys(): Set<String>
}

/**
 * Request passed to the pack's isolated HTTP client. [url] must be an absolute
 * http/https URL; implementations must reject other schemes, strip host-level
 * Cookie/Host overrides and check the pack network policy before connecting.
 */
data class PackHttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, List<String>> = emptyMap(),
    val body: ByteArray? = null,
    val followRedirects: Boolean = true,
    val maxResponseBytes: Long? = null,
) {
    init {
        require(method.isNotBlank()) { "HTTP method must not be blank" }
        require(url.isNotBlank()) { "HTTP URL must not be blank" }
        require(maxResponseBytes == null || maxResponseBytes > 0L) {
            "maxResponseBytes must be positive"
        }
    }
}

/**
 * Streaming response. Closing the response must close the body stream and the
 * underlying connection; callers must not rely on GC.
 */
interface PackHttpResponse : AutoCloseable {
    val statusCode: Int
    val headers: Map<String, List<String>>
    val finalUrl: String
    val contentLength: Long?

    fun bodyStream(): InputStream

    override fun close()
}

/**
 * Network primitive bound to one pack, its epoch and its network policy.
 * Every implementation must re-check every redirect hop and register the
 * active call with [PackCallContext.cancellation].
 */
interface PackHttpClient {
    fun execute(request: PackHttpRequest, call: PackCallContext): PackHttpResponse
}

/**
 * Minimal crypto facade. Algorithms not actually implemented must throw a
 * [PackException] with [PackErrorCode.CRYPTO_UNSUPPORTED], never return empty
 * bytes or silently substitute another algorithm.
 */
interface PackCrypto {
    fun digest(algorithm: String, data: ByteArray): ByteArray

    fun hmac(algorithm: String, key: ByteArray, data: ByteArray): ByteArray

    fun cipher(spec: String, key: ByteArray, iv: ByteArray?, data: ByteArray): ByteArray
}

/** Pack-scoped logger. The host applies ring-buffer and 100 MB disk budgets. */
interface PackLogger {
    fun log(
        level: PackLogLevel,
        message: String,
        details: Map<String, String> = emptyMap(),
    )
}

/**
 * Invokes another declared component of the same pack. Only components named
 * in the manifest may be addressed; implementations must reject anything else
 * with COMPONENT_MISSING/PERMISSION_DENIED before loading code.
 */
interface PackComponentInvoker {
    fun invoke(
        componentId: String,
        command: String,
        argJson: String,
        call: PackCallContext,
    ): String
}

/**
 * Bound runtime context handed to a package entry point. It deliberately has
 * no Android Context, ClassLoader, raw pack root, global OkHttp client or
 * global cookie store.
 *
 * It also intentionally does not store a mutable "current call": the runtime
 * receives the exact [PackCallContext] in
 * [PackRuntimeProvider.load]/[PackEntry.initialize]/[PackInstance.invoke] and
 * passes that same context to primitives. This keeps first-load deadlines and
 * cancellation from being replaced by a thread-local or a fresh clock.
 */
interface PackContext {
    val ref: PackRef
    val manifest: PackManifest
    val platform: PackPlatform
    val storage: PackStorage
    val kv: PackKeyValueStore
    val http: PackHttpClient
    val crypto: PackCrypto
    val logger: PackLogger
    val components: PackComponentInvoker
    val limits: PackResourceLimits
}