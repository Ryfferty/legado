package io.legado.pack.storage

import io.legado.pack.api.JsonValue
import io.legado.pack.api.PackDiagnostic
import io.legado.pack.api.PackErrorCode
import io.legado.pack.api.PackException
import io.legado.pack.api.PackId
import java.io.File

/**
 * Durable high-water mark of the admission epochs ever committed per pack id.
 *
 * `current.json` (and the whole pack directory) is deleted on uninstall, so a
 * plain reinstall would restart the epoch at 1. Host-side bookkeeping such as
 * the write-tool permission store binds grants to (id, revision, epoch, hash);
 * a reused epoch would make a revoked grant match a reinstalled pack again.
 * The ledger lives in a hidden file directly under the packs root — outside
 * every pack directory — and survives pack deletion. It is written before any
 * pack content is moved during a commit, so a failed commit may leave a gap
 * but never a reuse.
 *
 * The file is only touched while the caller holds the manager's mutation lock
 * (commit) or during construction; the internal lock guards lazy loading.
 * Loading restores `.epochs.json.bak` first: the atomic write moves the
 * previous file to `.bak` before the temp rename, and loading an "empty"
 * ledger while only the backup survives would silently restart the
 * numbering. An unreadable or malformed ledger fails closed instead of
 * restarting the numbering.
 */
internal class PackInstallLedger(
    packsRoot: File,
) {

    private val file = File(packsRoot, FILE_NAME)
    private val lock = Any()
    private val highWater = LinkedHashMap<String, Long>()
    private var loaded = false

    /** Highest committed epoch known for [id]; 0 when the id is unknown. */
    fun highestEpoch(id: PackId): Long = synchronized(lock) {
        loadLocked()
        highWater[id.value] ?: 0L
    }

    /**
     * Raises the high-water mark for [id] to [epoch]. A lower value is a no-op
     * (the mark is monotonic by definition). Durable: an unwritable ledger
     * throws instead of letting the caller install under a reused epoch.
     */
    fun reserve(id: PackId, epoch: Long) {
        synchronized(lock) {
            loadLocked()
            val current = highWater[id.value] ?: 0L
            if (epoch <= current) return
            highWater[id.value] = epoch
            try {
                AtomicFiles.writeText(file, encode(highWater))
            } catch (e: PackException) {
                // Roll the in-memory view back so a retry does not assume the
                // reservation happened.
                highWater[id.value] = current
                throw ledgerFailure("cannot persist install epochs", e)
            } catch (e: Exception) {
                highWater[id.value] = current
                throw ledgerFailure("cannot persist install epochs: ${e.message}", e)
            }
        }
    }

    private fun loadLocked() {
        if (loaded) return
        // Crash window inside AtomicFiles.writeBytes: the previous ledger was
        // already moved to `.bak` when the process died, so the missing main
        // file must restore the backup instead of loading an empty ledger —
        // an empty view would hand out epochs the backup already recorded.
        // recoverIfNeeded throws (fails closed) when the restore itself fails.
        AtomicFiles.recoverIfNeeded(file)
        if (file.isFile) {
            try {
                val root = PackJsonText.parseObject(
                    AtomicFiles.readText(file, MAX_FILE_BYTES, file.name),
                    MAX_FILE_CHARS,
                    "epochs",
                )
                val schema = root.requiredInt("schemaVersion", "epochs")
                if (schema != SCHEMA_VERSION) {
                    throw PackException(
                        PackDiagnostic(
                            code = PackErrorCode.MANIFEST_SCHEMA_UNSUPPORTED,
                            message = "install epochs schema $schema is not supported",
                            path = "epochs.schemaVersion",
                        ),
                    )
                }
                val packs = root.optionalObject("packs", "epochs") ?: JsonValue.Obj(emptyMap())
                packs.entries.forEach { (key, _) ->
                    val id = PackId.parse(key)
                        ?: throw PackException(
                            PackDiagnostic(
                                code = PackErrorCode.MANIFEST_INVALID,
                                message = "install epochs key '$key' is not a valid pack id",
                                path = "epochs.packs",
                            ),
                        )
                    // Strict integer via the same helper the metadata codec uses.
                    val epoch = packs.optionalLong(key, "epochs.packs")
                    if (epoch == null || epoch < 0L) {
                        throw PackException(
                            PackDiagnostic(
                                code = PackErrorCode.MANIFEST_INVALID,
                                message = "install epoch for '$key' must be an integer >= 0",
                                path = "epochs.packs.$key",
                            ),
                        )
                    }
                    val previous = highWater[id.value]
                    if (previous == null || epoch > previous) {
                        highWater[id.value] = epoch
                    }
                }
            } catch (e: PackException) {
                throw ledgerFailure("cannot read install epochs ledger: ${e.diagnostic.message}", e)
            } catch (e: Exception) {
                throw ledgerFailure("cannot read install epochs ledger: ${e.message}", e)
            }
        }
        loaded = true
    }

    private fun ledgerFailure(message: String, cause: Throwable? = null): PackException =
        PackException(
            PackDiagnostic(
                code = PackErrorCode.IO_FAILED,
                message = "$message (${file.path})",
                path = file.name,
            ),
            cause,
        )

    private fun encode(packs: Map<String, Long>): String = buildString(64) {
        append("{\"schemaVersion\":").append(SCHEMA_VERSION)
        append(",\"packs\":{")
        packs.entries.sortedBy { it.key }.forEachIndexed { index, (id, epoch) ->
            if (index > 0) append(',')
            append(PackJsonText.quote(id)).append(':').append(epoch)
        }
        append("}}")
    }

    companion object {
        const val FILE_NAME: String = ".epochs.json"
        private const val SCHEMA_VERSION = 1
        private const val MAX_FILE_CHARS = 4 * 1024 * 1024
        private const val MAX_FILE_BYTES = 8L * 1024 * 1024
    }
}
