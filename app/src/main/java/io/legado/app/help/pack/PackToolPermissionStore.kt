package io.legado.app.help.pack

import io.legado.pack.api.PackDiagnostic
import io.legado.pack.api.PackErrorCode
import io.legado.pack.api.PackException
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
 * Revocation is fail-closed, not best-effort bookkeeping. A revoke is only
 * complete once it is durable: [removePack] bumps a per-pack revocation
 * generation in a journal written next to the grants file, and a grant is
 * honored only while its own generation is newer than the journal entry. The
 * journal is a separate file on purpose — a failing grants-file write cannot
 * undo a revocation — so identical content reinstalled with a repeating
 * admission epoch cannot revive a revoked grant after a restart. If neither
 * file can be written, the grants file itself is deleted before giving up:
 * losing grants fails closed, keeping a revoked one does not. When even the
 * delete fails, a tombstone file next to the journal records the pack as
 * denied — creating a fresh file can succeed where replacing the existing
 * two or deleting the grants file cannot, and the denial then holds across
 * restarts and repeating admission epochs until a later explicit grant
 * clears it. Only when not even the tombstone can be written does
 * [removePack] throw: nothing persistable remains, so no startup diagnosis
 * could have been recorded either, and the pack stays denied for the rest of
 * the process.
 *
 * All three files live under the host's `packs/.host/` directory, outside
 * pack-owned code/data, and are read/written defensively. Unreadable state
 * fails closed.
 */
class PackToolPermissionStore(
    private val file: File,
) {

    private data class Entry(
        val revision: String,
        val epoch: Long,
        val permissionHash: String,
        val generation: Long,
        val tools: Set<String>,
    )

    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>()

    /** packKey -> generation of the last durable revocation. */
    private val revocations = HashMap<String, Long>()

    /**
     * Mirror of the tombstone file: packs whose revocation could not be
     * journalled and is instead enforced by plain denial, so it survives a
     * restart. A pack stays tombstoned until a later explicit grant clears
     * the entry.
     */
    private val tombstonedPacks = mutableSetOf<String>()

    /**
     * Packs whose last revocation is denied in-process: every
     * [tombstonedPacks] entry plus revocations that could not be recorded
     * anywhere at all.
     */
    private val undurableRevocations = mutableSetOf<String>()

    /** Set when the journal or tombstone cannot be parsed; no grant is honored. */
    private var stateUnreadable = false

    private var loaded = false

    private val revocationFile: File =
        File(file.parentFile, file.nameWithoutExtension + "-revocations.json")

    private val tombstoneFile: File =
        File(file.parentFile, file.nameWithoutExtension + "-undurable.json")

    fun isWriteToolEnabled(
        ref: PackRef,
        manifest: PackManifest,
        toolName: String,
    ): Boolean = synchronized(lock) {
        loadLocked()
        val packKey = ref.id.value
        if (packKey in undurableRevocations || stateUnreadable) {
            return@synchronized false
        }
        val permissionHash = manifest.permissions.digest()
        val entry = entries[packKey] ?: return@synchronized false
        entry.revision == ref.revision.value &&
            entry.epoch == ref.epoch &&
            entry.permissionHash == permissionHash &&
            entry.generation > (revocations[packKey] ?: 0L) &&
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
        val current: Entry? = entries[packKey]
        // A revoked or rebound grant does not carry tools forward.
        val carryForward = current?.takeIf { entry ->
            packKey !in undurableRevocations &&
                !stateUnreadable &&
                entry.revision == ref.revision.value &&
                entry.epoch == ref.epoch &&
                entry.permissionHash == permissionHash &&
                entry.generation > (revocations[packKey] ?: 0L)
        }
        val currentTools = carryForward?.tools?.toMutableSet() ?: mutableSetOf<String>()

        if (enabled) {
            if (currentTools.size >= MAX_TOOLS_PER_PACK && toolName !in currentTools) {
                return@synchronized false
            }
            currentTools += toolName
        } else {
            currentTools -= toolName
        }

        val generation = carryForward?.generation ?: nextGenerationLocked(packKey)
        if (currentTools.isEmpty()) {
            entries.remove(packKey)
        } else {
            entries[packKey] = Entry(
                revision = ref.revision.value,
                epoch = ref.epoch,
                permissionHash = permissionHash,
                generation = generation,
                tools = currentTools,
            )
        }
        try {
            persistLocked()
        } catch (error: Exception) {
            // Fail closed: memory must not keep a grant the disk does not hold.
            if (current == null) {
                entries.remove(packKey)
            } else {
                entries[packKey] = current
            }
            throw error
        }
        // The explicit grant is the user's intent, so a tombstone entry from
        // an earlier undurable revocation must go, or the grant would silently
        // die on the next restart. A failure propagates: reporting success
        // while the pack is still tombstoned would be a lie, and memory stays
        // consistent with the files — the grant is on disk while the
        // tombstone still denies it.
        if (packKey in tombstonedPacks) {
            tombstonedPacks.remove(packKey)
            try {
                persistTombstoneLocked()
            } catch (error: Exception) {
                tombstonedPacks.add(packKey)
                throw error
            }
        }
        undurableRevocations.remove(packKey)
        true
    }

    fun enabledWriteTools(ref: PackRef, manifest: PackManifest): Set<String> =
        synchronized(lock) {
            loadLocked()
            val packKey = ref.id.value
            if (packKey in undurableRevocations || stateUnreadable) {
                return@synchronized emptySet()
            }
            val permissionHash = manifest.permissions.digest()
            val entry = entries[packKey] ?: return@synchronized emptySet()
            if (entry.revision != ref.revision.value || entry.epoch != ref.epoch ||
                entry.permissionHash != permissionHash ||
                entry.generation <= (revocations[packKey] ?: 0L)
            ) {
                emptySet()
            } else {
                entry.tools.toSet()
            }
        }

    /**
     * Called after import/disable/enable/uninstall so a reinstalled pack cannot
     * inherit an old grant. The revocation is durable when this returns
     * normally; if no durable record could be produced it throws and the pack
     * is denied for the rest of the process.
     */
    fun removePack(id: PackId) {
        synchronized(lock) {
            loadLocked()
            val packKey = id.value
            if (entries.containsKey(packKey) || packKey in undurableRevocations) {
                revokeLocked(packKey)
            }
        }
    }

    fun snapshot(): Map<String, Set<String>> = synchronized(lock) {
        loadLocked()
        if (stateUnreadable) return@synchronized emptyMap()
        entries.filter { (packKey, entry) ->
            packKey !in undurableRevocations &&
                entry.generation > (revocations[packKey] ?: 0L)
        }.mapValues { it.value.tools.toSet() }
    }

    /**
     * Makes the revocation of [packKey] durable before returning. The journal
     * is written first so a failing grants-file write cannot undo it; the
     * grants file is rewritten second and destroyed as a last resort; when
     * even the delete fails, the tombstone file records the pack as denied,
     * which survives restarts and repeating admission epochs. Only when no
     * durable record exists at all does this throw, leaving the pack denied
     * in-process via [undurableRevocations].
     */
    private fun revokeLocked(packKey: String) {
        var durable = false
        var failure: Exception? = null
        if (!stateUnreadable) {
            val previous = revocations[packKey]
            revocations[packKey] = (previous ?: 0L) + 1L
            try {
                persistRevocationsLocked()
                durable = true
            } catch (error: Exception) {
                failure = error
                if (previous == null) {
                    revocations.remove(packKey)
                } else {
                    revocations[packKey] = previous
                }
            }
        }
        // The entry always leaves memory; a stale copy left on disk by a
        // failing write is inert because its generation is not newer than
        // the journal.
        entries.remove(packKey)
        if (!durable) {
            try {
                persistLocked()
                durable = true
            } catch (error: Exception) {
                failure = error
            }
        } else {
            // Tidy-up only: the revocation is already durable via the journal.
            runCatching { persistLocked() }
        }
        if (!durable && file.delete()) {
            // Last resort: destroy the grants file rather than leave a stale
            // grant that a restart would revive. Losing grants fails closed.
            durable = true
        }
        if (durable) {
            undurableRevocations.remove(packKey)
            return
        }
        // Fourth channel: a fresh tombstone file names the pack as denied. It
        // can succeed where replacing the two existing files or deleting the
        // grants file cannot, and the denial holds across restarts regardless
        // of a repeating admission epoch — closing the window in which the
        // in-process poison alone would vanish on restart.
        tombstonedPacks.add(packKey)
        try {
            persistTombstoneLocked()
            undurableRevocations.add(packKey)
            return
        } catch (error: Exception) {
            failure = error
            tombstonedPacks.remove(packKey)
        }
        undurableRevocations.add(packKey)
        throw PackException(
            PackDiagnostic(
                code = PackErrorCode.IO_FAILED,
                message = "扩展包 $packKey 的写授权撤销未能落盘，撤销毒标记亦写入失败，重启后旧授权可能复活",
                path = packKey,
            ),
            failure,
        )
    }

    private fun nextGenerationLocked(packKey: String): Long =
        (revocations[packKey] ?: 0L) + 1L

    private fun loadLocked() {
        if (loaded) return
        loaded = true
        if (revocationFile.isFile) {
            try {
                val root = Json.parseToJsonElement(
                    revocationFile.readText(Charsets.UTF_8)
                ).jsonObject
                val journal = root["revocations"] as? JsonObject
                journal?.forEach { (packKey, value) ->
                    if (revocations.size >= MAX_PACKS) return@forEach
                    val generation = (value as? JsonPrimitive)?.content?.toLongOrNull()
                    if (generation != null && generation > 0L) {
                        revocations[packKey] = generation
                    }
                }
            } catch (_: Exception) {
                // Fail closed: revocations are unknown, so no grant is honored
                // and the journal is never rebuilt from an unreadable state.
                stateUnreadable = true
            }
        }
        if (tombstoneFile.isFile) {
            try {
                val root = Json.parseToJsonElement(
                    tombstoneFile.readText(Charsets.UTF_8)
                ).jsonObject
                (root["packs"] as? JsonArray)?.forEach { element ->
                    val packKey = (element as? JsonPrimitive)?.content
                    if (packKey.isNullOrBlank() || tombstonedPacks.size >= MAX_PACKS) {
                        return@forEach
                    }
                    tombstonedPacks += packKey
                }
            } catch (_: Exception) {
                // Fail closed: the denied packs are unknown, so no grant is
                // honored and the tombstone is never rebuilt from an
                // unreadable state.
                stateUnreadable = true
            }
        }
        // Tombstoned packs are denied exactly like an in-process undurable
        // revocation; only a successful explicit grant clears the entry.
        undurableRevocations.addAll(tombstonedPacks)
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
            // Entries written before the revocation journal existed carry no
            // generation; treat them as generation 1, older than every
            // revocation the journal records.
            val generation = (obj["generation"] as? JsonPrimitive)?.content?.toLongOrNull()
                ?: 1L
            val permissionHash = (obj["permissionHash"] as? JsonPrimitive)?.content
                ?: return@forEach
            val tools = (obj["tools"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.content }
                ?.filter { it.isNotBlank() }
                ?.take(MAX_TOOLS_PER_PACK)
                ?.toSet()
                ?: return@forEach
            if (tools.isNotEmpty()) {
                entries[packKey] = Entry(revision, epoch, permissionHash, generation, tools)
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
                        put("generation", entry.generation)
                        put("permissionHash", entry.permissionHash)
                        put("tools", buildJsonArray {
                            entry.tools.sorted().forEach { add(JsonPrimitive(it)) }
                        })
                    }
                }
            }
        }
        writeAtomicallyLocked(file, root.toString())
    }

    private fun persistRevocationsLocked() {
        val root = buildJsonObject {
            putJsonObject("revocations") {
                revocations.forEach { (packKey, generation) ->
                    put(packKey, generation)
                }
            }
        }
        writeAtomicallyLocked(revocationFile, root.toString())
    }

    private fun persistTombstoneLocked() {
        val root = buildJsonObject {
            put("packs", buildJsonArray {
                tombstonedPacks.sorted().forEach { add(JsonPrimitive(it)) }
            })
        }
        writeAtomicallyLocked(tombstoneFile, root.toString())
    }

    private fun writeAtomicallyLocked(target: File, content: String) {
        val parent = target.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IllegalStateException("cannot create pack permission directory: $parent")
        }
        val temporary = File(parent, target.name + ".tmp")
        temporary.writeText(content, Charsets.UTF_8)
        if (target.exists() && !target.delete()) {
            temporary.delete()
            throw IllegalStateException("cannot replace pack permission file: $target")
        }
        if (!temporary.renameTo(target)) {
            temporary.delete()
            throw IllegalStateException("cannot publish pack permission file: $target")
        }
    }

    private companion object {
        const val MAX_PACKS = 512
        const val MAX_TOOLS_PER_PACK = 128
    }
}
