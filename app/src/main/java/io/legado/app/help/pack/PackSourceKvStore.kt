package io.legado.app.help.pack

import io.legado.pack.api.PackCapability
import io.legado.pack.api.PackDiagnostic
import io.legado.pack.api.PackErrorCode
import io.legado.pack.api.PackException
import io.legado.pack.api.PackId
import io.legado.pack.api.PackManager
import io.legado.pack.api.PackResourceLimits
import io.legado.pack.api.PackState
import io.legado.pack.api.PackStorageLayout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Host-side `java.packKV` bridge.
 *
 * The namespace is `(pack id, host-derived source identity)`, stored below the
 * pack's private directory. A source can therefore neither read another
 * source's values nor address a pack whose manifest did not declare the KV
 * capability. The bridge exposes only four string/boolean operations; it never
 * hands out a `PackManager`, `PackContext`, `File` or stream.
 */
class PackKvBridge internal constructor(
    private val sourceKeyProvider: () -> String?,
    private val manager: PackManager,
    private val packsRoot: File,
    private val limits: PackResourceLimits = PackResourceLimits(),
) {

    fun get(pack: String, key: String): String? = withStore(pack) { store ->
        store.get(key)
    }

    fun put(pack: String, key: String, value: String): Boolean = withStore(pack) { store ->
        store.put(key, value)
    }

    fun remove(pack: String, key: String): Boolean = withStore(pack) { store ->
        store.remove(key)
    }

    /** JSON array string, because Java collections must not cross into script. */
    fun keys(pack: String): String = withStore(pack) { store ->
        buildJsonArray {
            store.keys().sorted().forEach { add(JsonPrimitive(it)) }
        }.toString()
    }

    private fun <T> withStore(pack: String, block: (SourceStore) -> T): T {
        PackThreading.requireNotMainThread("java.packKV")
        val id = PackId.parse(pack)
            ?: throw IllegalArgumentException("invalid pack id: '$pack'")
        val info = manager.info(id) ?: throw packFailure(
            PackErrorCode.PACK_NOT_FOUND,
            "pack '${id.value}' is not installed",
        )
        if (info.state != PackState.ENABLED) {
            throw packFailure(
                PackErrorCode.PACK_DISABLED,
                "pack '${id.value}' is not enabled",
            )
        }
        if (PackCapability.KV !in info.manifest.capabilities) {
            throw packFailure(
                PackErrorCode.PERMISSION_DENIED,
                "pack '${id.value}' did not declare the KV capability",
            )
        }
        val sourceKey = sourceKeyProvider()
            ?: throw packFailure(
                PackErrorCode.PERMISSION_DENIED,
                "java.packKV requires a book or RSS source identity",
            )
        val file = sourceStoreFile(id, sourceKey)
        val lock = storeLocks[file.absolutePath]
            ?: synchronized(storeLocks) {
                storeLocks.getOrPut(file.absolutePath) { Any() }
            }
        return synchronized(lock) {
            block(SourceStore(file, limits))
        }
    }

    private fun sourceStoreFile(id: PackId, sourceKey: String): File {
        val layout = PackStorageLayout(packsRoot)
        val directory = File(layout.packDir(id), SOURCE_KV_DIR)
        if (!directory.exists() && !directory.mkdirs()) {
            throw packFailure(
                PackErrorCode.IO_FAILED,
                "cannot create pack source KV directory",
            )
        }
        return File(directory, PackSourceIdentity.sha256Hex(sourceKey) + ".json")
    }

    private fun packFailure(code: PackErrorCode, message: String): PackException =
        PackException(PackDiagnostic(code = code, message = message))

    private class SourceStore(
        private val file: File,
        private val limits: PackResourceLimits,
    ) {

        fun get(key: String): String? {
            validateKey(key)
            return read().values[key]
        }

        fun put(key: String, value: String): Boolean {
            validateKey(key)
            val bytes = value.toByteArray(Charsets.UTF_8)
            if (bytes.size > limits.maxKvValueBytes) {
                throw PackException(
                    PackDiagnostic(
                        code = PackErrorCode.INVALID_REQUEST,
                        message = "pack KV value exceeds ${limits.maxKvValueBytes} bytes",
                    )
                )
            }
            val values = read().values
            if (key !in values && values.size >= MAX_KEYS) {
                throw PackException(
                    PackDiagnostic(
                        code = PackErrorCode.INVALID_REQUEST,
                        message = "pack KV source namespace exceeds $MAX_KEYS keys",
                    )
                )
            }
            if (values[key] == value) return true
            values[key] = value
            write(values)
            return true
        }

        fun remove(key: String): Boolean {
            validateKey(key)
            val values = read().values
            if (values.remove(key) == null) return false
            write(values)
            return true
        }

        fun keys(): Set<String> = read().values.keys

        private fun validateKey(key: String) {
            if (key.isBlank() || key.length > limits.maxKvKeyChars) {
                throw PackException(
                    PackDiagnostic(
                        code = PackErrorCode.INVALID_REQUEST,
                        message = "pack KV key must be 1..${limits.maxKvKeyChars} characters",
                    )
                )
            }
        }

        private fun read(): Snapshot {
            if (!file.isFile) return Snapshot(LinkedHashMap<String, String>())
            val root = try {
                Json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject
            } catch (error: Exception) {
                throw PackException(
                    PackDiagnostic(
                        code = PackErrorCode.IO_FAILED,
                        message = "pack source KV file is unreadable",
                        details = mapOf("file" to file.name),
                    ),
                    error,
                )
            }
            val raw = root["values"] as? JsonObject ?: return Snapshot(LinkedHashMap<String, String>())
            val values = LinkedHashMap<String, String>(raw.size)
            raw.forEach { (key, value) ->
                if (key.length > limits.maxKvKeyChars) return@forEach
                val text = (value as? JsonPrimitive)?.content ?: return@forEach
                if (text.toByteArray(Charsets.UTF_8).size <= limits.maxKvValueBytes) {
                    values[key] = text
                }
            }
            return Snapshot(values)
        }

        private fun write(values: Map<String, String>) {
            val root = buildJsonObject {
                put("values", buildJsonObject {
                    values.forEach { (key, value) -> put(key, value) }
                })
            }
            val parent = file.parentFile
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw PackException(
                    PackDiagnostic(
                        code = PackErrorCode.IO_FAILED,
                        message = "cannot create pack source KV directory",
                    )
                )
            }
            val temporary = File(parent, file.name + ".tmp")
            temporary.writeText(root.toString(), Charsets.UTF_8)
            if (file.exists() && !file.delete()) {
                temporary.delete()
                throw PackException(
                    PackDiagnostic(
                        code = PackErrorCode.IO_FAILED,
                        message = "cannot replace pack source KV file",
                    )
                )
            }
            if (!temporary.renameTo(file)) {
                temporary.delete()
                throw PackException(
                    PackDiagnostic(
                        code = PackErrorCode.IO_FAILED,
                        message = "cannot publish pack source KV file",
                    )
                )
            }
        }

        private companion object {
            const val MAX_KEYS = 4096
        }
    }

    private data class Snapshot(val values: LinkedHashMap<String, String>)

    private companion object {
        const val SOURCE_KV_DIR = "source-kv"
        val storeLocks = ConcurrentHashMap<String, Any>()
    }
}