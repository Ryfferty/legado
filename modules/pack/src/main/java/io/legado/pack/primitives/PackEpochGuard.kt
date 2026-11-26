package io.legado.pack.primitives

import io.legado.pack.api.PackCancelledException

/**
 * Epoch gate shared by every primitive bound to one loaded revision.
 *
 * Disable, upgrade and uninstall advance the record epoch (or remove it), so a
 * late callback, a leftover HTTP thread or a cached component that keeps
 * calling primitives fails instead of reading/writing the next revision's
 * state.
 */
public fun interface PackEpochGuard {
    fun isActive(): Boolean

    fun ensureActive() {
        if (!isActive()) {
            throw PackCancelledException("pack revision is no longer active")
        }
    }
}