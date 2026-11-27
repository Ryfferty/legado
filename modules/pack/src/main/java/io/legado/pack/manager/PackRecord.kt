package io.legado.pack.manager

import io.legado.pack.api.PackDiagnostic
import io.legado.pack.api.PackId
import io.legado.pack.api.PackInfo
import io.legado.pack.api.PackInstance
import io.legado.pack.api.PackManifest
import io.legado.pack.api.PackRef
import io.legado.pack.api.PackState
import io.legado.pack.diagnostics.PackMetricsTracker
import io.legado.pack.primitives.PackPrimitivesContext
import io.legado.pack.storage.StoredPack

internal class PackRuntimeScope(
    val ref: PackRef,
    val primitives: PackPrimitivesContext,
) {
    val instances: LinkedHashMap<String, PackInstance> = LinkedHashMap()
}

/**
 * Mutable in-memory record. All lifecycle fields are replaced under the
 * manager's state lock; [scope] and [worker] are only touched there or inside
 * that pack's single worker task.
 */
internal class PackRecord(
    var stored: StoredPack,
    var transition: Boolean = false,
    var runtimeNeedsRestart: Boolean = false,
    var runtimeBroken: PackDiagnostic? = null,
    var scope: PackRuntimeScope? = null,
    var worker: PackWorker? = null,
    var metrics: PackMetricsTracker = PackMetricsTracker(),
    /**
     * Monotonic generation of the state snapshots handed to the persistence
     * path. Every [persisted] call under the manager's state lock yields a
     * strictly newer generation, so the manager's metadata writer can refuse
     * a stale snapshot even when its epoch equals the live record's epoch:
     * markBroken and markHealthy run on their callers' coroutines, and two
     * same-epoch snapshots can otherwise reach `current.json` in reverse
     * snapshot order and roll a newer state back on disk.
     */
    var persistSeq: Long = 0L,
) {
    val id: PackId get() = stored.id
    val manifest: PackManifest get() = stored.manifest
    val ref: PackRef get() = stored.ref
    val enabled: Boolean get() = stored.enabled

    fun state(): PackState = when {
        runtimeNeedsRestart -> PackState.NEEDS_RESTART
        !stored.enabled -> PackState.DISABLED
        runtimeBroken != null -> PackState.BROKEN
        else -> PackState.ENABLED
    }

    fun toInfo(): PackInfo = PackInfo(
        ref = ref,
        manifest = manifest,
        state = state(),
        installedAtEpochMs = stored.installedAtEpochMs,
    )

    /** Must be called under the manager's state lock: bumps the generation. */
    fun persisted(): StoredPack {
        persistSeq += 1L
        return stored.copy(
            needsRestart = runtimeNeedsRestart,
            broken = runtimeBroken,
        )
    }
}