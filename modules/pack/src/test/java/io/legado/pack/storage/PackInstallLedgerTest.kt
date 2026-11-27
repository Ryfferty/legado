package io.legado.pack.storage

import io.legado.pack.api.PackErrorCode
import io.legado.pack.api.PackException
import io.legado.pack.api.PackId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Durable high-water mark of the admission epochs (problem: uninstall deletes
 * `current.json`, so a reinstall used to restart the numbering):
 *
 * * a reserve persists and is reloaded by the next instance;
 * * the mark is monotonic — a lower reserve is a no-op;
 * * the crash window inside AtomicFiles.writeBytes (previous file already
 *   moved to `.bak`, temp not yet renamed into place) is recovered on load
 *   instead of loading an empty ledger, which would hand out epochs the
 *   backup already recorded;
 * * a malformed ledger fails closed instead of restarting the numbering.
 */
class PackInstallLedgerTest {

    @Rule
    @JvmField
    val temp = TemporaryFolder()

    @Test
    fun reservePersistsAndIsReloadedByTheNextInstance() {
        val root = temp.newFolder()
        val id = PackId.require("ledger-persist-pack")
        val first = PackInstallLedger(root)
        assertEquals(0L, first.highestEpoch(id))
        first.reserve(id, 3L)
        assertEquals(3L, first.highestEpoch(id))
        // A fresh instance must read the persisted file, not start empty.
        assertEquals(3L, PackInstallLedger(root).highestEpoch(id))
        assertTrue(File(root, PackInstallLedger.FILE_NAME).isFile)
    }

    @Test
    fun reserveIsMonotonicAndNeverDowngradesTheMark() {
        val root = temp.newFolder()
        val ledger = PackInstallLedger(root)
        val id = PackId.require("ledger-monotonic-pack")
        ledger.reserve(id, 5L)
        ledger.reserve(id, 2L)
        assertEquals(5L, PackInstallLedger(root).highestEpoch(id))
    }

    @Test
    fun loadRestoresTheBackupWhenOnlyTheBakSurvives() {
        val root = temp.newFolder()
        val id = PackId.require("ledger-backup-pack")
        PackInstallLedger(root).reserve(id, 7L)
        // Simulate the crash window inside AtomicFiles.writeBytes: the
        // previous ledger was moved to `.bak` and the process died before the
        // temp file was renamed into place, so only the backup remains.
        val file = File(root, PackInstallLedger.FILE_NAME)
        val backup = File(root, PackInstallLedger.FILE_NAME + ".bak")
        assertTrue(file.renameTo(backup))
        assertFalse(file.exists())

        // Without the restore this would report 0 and a later install would
        // reuse an epoch the backup already committed.
        assertEquals(7L, PackInstallLedger(root).highestEpoch(id))
        // The restore is durable: the main file is back in place.
        assertTrue(file.isFile)
        assertFalse(backup.exists())
    }

    @Test
    fun malformedLedgerFailsClosedInsteadOfRestartingTheNumbering() {
        val root = temp.newFolder()
        // Valid JSON object without the required schema/packs fields.
        File(root, PackInstallLedger.FILE_NAME).writeText("{}")
        try {
            PackInstallLedger(root).highestEpoch(PackId.require("ledger-broken-pack"))
            fail("expected a typed failure for a malformed ledger")
        } catch (e: PackException) {
            assertEquals(PackErrorCode.IO_FAILED, e.diagnostic.code)
            assertTrue(e.diagnostic.isRedCard)
            assertTrue(e.diagnostic.message.contains("cannot read install epochs ledger"))
        }
    }
}
