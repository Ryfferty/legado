package io.legado.app.help.pack

import io.legado.pack.api.PackId
import io.legado.pack.api.PackManifest
import io.legado.pack.api.PackRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File

/**
 * Host-owned authorization for pack MCP write tools.
 *
 * A manifest `isWrite` flag is only a declaration. This store is the actual
 * grant: every declared write tool is denied until the management surface (or
 * an explicitly authorized management call) enables it. Grants are bound to
 * the loaded revision, admission epoch and manifest permission digest, so
 * disabling, reinstalling or upgrading a pack does not silently carry an old
 * grant forward.
 *
 * The file lives under the host's `packs/.host/` directory, outside pack-owned
 * code/data, and is read/written defensively. Unreadable state fails closed.
 */
class PackToolPermissionStore(
    private val file: File,
) {

    private data class Entry(
        val revision: String,
        val epoch: Long,
        val permissionHash: String,
        val tools: Set<String>,
    )

    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>()
    private var loaded = false

    fun isWriteToolEnabled(
        ref: PackRef,
        manifest: PackManifest,
        toolName: String,
    ): Boolean = synchronized(lock) {
        loadLocked()
        val permissionHash = manifest.permissions.digest()
        val entry = entries[ref.id.value] ?: return@synchronized false
        entry.revision == ref.revision.value &&
            entry.epoch == ref.epoch &&
            entry.permissionHash == permissionHash &&
            toolName in entry.tools
    }

    /**
     * Enables/disables one declared write tool. Returns false when the manifest
     * does not declare the name as a write tool, so a read tool can never be
     * promoted into the write set through this API.
     */
    fun setWriteToolEnabled(
        ref: PackRef,
        manifest: PackManifest,
        toolName: String,
        enabled: Boolean,
    ): Boolean = synchronized(lock) {
        val declared = manifest.mcpTools.firstOrNull { it.name == toolName }
        if (declared == null || !declared.isWrite) return@synchronized false

        loadLocked()
        val packKey = ref.id.value
        val permissionHash = manifest.permissions.digest()
        val current = entries[packKey]
        val currentTools = if (
            current != null &&
            current.revision == ref.revision.value &&
            current.epoch == ref.epoch &&
            current.permissionHash == permissionHash
        ) {
            current.tools.toMutableSet()
        } else {
            // A different revision/permission set invalidates the old grant.
            mutableSetOf<String>()
        }

        if (enabled) {
            if (currentTools.size >= MAX_TOOLS_PER_PACK && toolName !in currentTools) {
                return@synchronized false
            }
            currentTools += toolName
        } else {
            currentTools -= toolName
        }

        if (currentTools.isEmpty()) {
            entries.remove(packKey)
        } else {
            entries[packKey] = Entry(
                revision = ref.revision.value,
                epoch = ref.epoch,
                permissionHash = permissionHash,
                tools = currentTools,
            )
        }
        persistLocked()
        true
    }

    fun enabledWriteTools(ref: PackRef, manifest: PackManifest): Set<String> =
        synchronized(lock) {
            loadLocked()
            val permissionHash = manifest.permissions.digest()
            val entry = entries[ref.id.value] ?: return@synchronized emptySet()
            if (entry.revision != ref.revision.value || entry.epoch != ref.epoch ||
                entry.permissionHash != permissionHash) {
                emptySet()
            } else {
                entry.tools.toSet()
            }
        }

    /** Called after uninstall so a reinstalled pack cannot inherit an old grant. */
    fun removePack(id: PackId) {
        synchronized(lock) {
            loadLocked()
            if (entries.remove(id.value) != null) {
                persistLocked()
            }
        }
    }

    fun snapshot(): Map<String, Set<String>> = synchronized(lock) {
        loadLocked()
        entries.mapValues { it.value.tools.toSet() }
    }

    private fun loadLocked() {
        if (loaded) return
        loaded = true
        if (!file.isFile) return
        val root = try {
            Json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject
        } catch (_: Exception) {
            // Fail closed: a corrupt permission file grants nothing.
            entries.clear()
            return
        }
        val packs = root["packs"] as? JsonObject ?: return
        packs.forEach { (packKey, value) ->
            if (entries.size >= MAX_PACKS) return@forEach
            val obj = value as? JsonObject ?: return@forEach
            val revision = (obj["revision"] as? JsonPrimitive)?.content ?: return@forEach
            val epoch = (obj["epoch"] as? JsonPrimitive)?.content?.toLongOrNull()
                ?: return@forEach
            val permissionHash = (obj["permissionHash"] as? JsonPrimitive)?.content
                ?: return@forEach
            val tools = (obj["tools"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.content }
                ?.filter { it.isNotBlank() }
                ?.take(MAX_TOOLS_PER_PACK)
                ?.toSet()
                ?: return@forEach
            if (tools.isNotEmpty()) {
                entries[packKey] = Entry(revision, epoch, permissionHash, tools)
            }
        }
    }

    private fun persistLocked() {
        val root = buildJsonObject {
            putJsonObject("packs") {
                entries.forEach { (packKey, entry) ->
                    putJsonObject(packKey) {
                        put("revision", entry.revision)
                        put("epoch", entry.epoch)
                        put("permissionHash", entry.permissionHash)
                        put("tools", buildJsonArray {
                            entry.tools.sorted().forEach { add(JsonPrimitive(it)) }
                        })
                    }
                }
            }
        }
        val parent = file.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IllegalStateException("cannot create pack permission directory: $parent")
        }
        val temporary = File(parent, file.name + ".tmp")
        temporary.writeText(root.toString(), Charsets.UTF_8)
        if (file.exists() && !file.delete()) {
            temporary.delete()
            throw IllegalStateException("cannot replace pack permission file: $file")
        }
        if (!temporary.renameTo(file)) {
            temporary.delete()
            throw IllegalStateException("cannot publish pack permission file: $file")
        }
    }

    private companion object {
        const val MAX_PACKS = 512
        const val MAX_TOOLS_PER_PACK = 128
    }
}