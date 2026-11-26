package io.legado.app.help.pack

import android.os.Looper

/**
 * Narrow thread guard for source-facing pack bridges. Package code must never
 * run on the main thread; blocking bridges also must not park the main thread
 * while the pack worker runs.
 */
object PackThreading {

    fun requireNotMainThread(api: String) {
        val onMainThread = try {
            val current = Looper.myLooper()
            val main = Looper.getMainLooper()
            current != null && main != null && current == main
        } catch (ignored: Throwable) {
            // Plain JVM tests have no Android main looper. Never turn a missing
            // platform check into a false "main thread" failure.
            false
        }
        check(!onMainThread) { "$api must not be called on the main thread" }
    }
}