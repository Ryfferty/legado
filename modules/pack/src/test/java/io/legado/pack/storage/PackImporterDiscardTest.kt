package io.legado.pack.storage

import io.legado.pack.api.PackErrorCode
import io.legado.pack.api.PackException
import io.legado.pack.api.PackId
import io.legado.pack.api.PackImportHint
import io.legado.pack.api.PackResourceLimits
import io.legado.pack.api.PackStorageLayout
import io.legado.pack.manager.PackFixtures
import io.legado.pack.manifest.ManifestValidationContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream

/**
 * Discard/reclaim contract of [PackImporter]:
 *
 * * reclaiming the pack skeleton must never destroy content it did not
 *   check (the emptiness checks race with a concurrent prepare creating a
 *   new staging token), so the reclaim is rmdir-only instead of recursive;
 * * a foreign staging token present at check time keeps the skeleton alive;
 * * a staging directory that cannot be deleted is a typed IO red card, not a
 *   swallowed Boolean (only enforceable where an open handle blocks
 *   deletion; otherwise the test is skipped via an assumption).
 */
class PackImporterDiscardTest {

    @Rule
    @JvmField
    val temp = TemporaryFolder()

    private fun importer(root: File): PackImporter = PackImporter(
        PackStorageLayout(root),
        PackResourceLimits(),
        ManifestValidationContext(),
    )

    private fun prepare(root: File, id: String): PreparedPack {
        val content = PackFixtures.userscript(id = id)
        return importer(root).prepare(
            input = ByteArrayInputStream(content),
            hint = PackImportHint(),
            baseRevision = null,
            baseEpoch = null,
        )
    }

    @Test
    fun discardKeepsForeignStagingTokensOfTheSamePack() {
        val root = temp.newFolder("packs")
        val first = prepare(root, "token-race-pack")

        // A second preview of the same pack id owns another token directory.
        val second = prepare(root, "token-race-pack")
        val secondDir = second.stagingDir
        assertTrue(secondDir.isDirectory)

        importer(root).discard(first)

        // The foreign token (and the skeleton that contains it) must survive
        // the discard of the first preview.
        assertTrue(secondDir.isDirectory)
        assertTrue(secondDir.listFiles()!!.isNotEmpty())
        assertTrue(File(root, "token-race-pack").isDirectory)
    }

    @Test
    fun skeletonReclaimNeverRemovesUncheckedContent() {
        val root = temp.newFolder("packs")
        val prepared = prepare(root, "skeleton-pack")
        val packDir = File(root, "skeleton-pack")
        assertTrue(packDir.isDirectory)

        // Content the emptiness checks do not cover (data/code/staging are
        // checked; kv is not) simulates a writer that added an entry after
        // the checks ran. Recursive deletion would destroy it; the rmdir-only
        // reclaim must leave it in place.
        val kvDir = File(packDir, "kv")
        assertTrue(kvDir.mkdirs())
        val rogue = File(kvDir, "rogue.txt")
        rogue.writeText("kept")

        importer(root).discard(prepared)

        assertTrue("reclaim destroyed unchecked pack content", rogue.isFile)
        assertTrue(packDir.isDirectory)
    }

    @Test
    fun discardReclaimsTheSkeletonWhenNothingRemains() {
        val root = temp.newFolder("packs")
        val prepared = prepare(root, "clean-pack")
        val packDir = File(root, "clean-pack")
        assertTrue(packDir.isDirectory)

        importer(root).discard(prepared)

        assertFalse("empty skeleton was not reclaimed", packDir.exists())
    }

    @Test
    fun discardReportsAnEmptySkeletonThatCannotBeRemoved() {
        // Probe: can this platform block the rmdir of an empty child by
        // making its parent read-only? POSIX non-root blocks it; Windows
        // ignores the directory read-only attribute, so the branch is not
        // enforceable there and the test is skipped via the assumption.
        val probeRoot = temp.newFolder()
        val probeParent = File(probeRoot, "parent")
        val probeChild = File(probeParent, "child")
        assertTrue(probeChild.mkdirs())
        probeParent.setWritable(false, false)
        val rmdirBlocked = !probeChild.delete()
        probeParent.setWritable(true, false)
        probeChild.delete()
        Assume.assumeTrue(
            "this platform does not block rmdir of an empty directory under a read-only parent; the empty-skeleton branch is not enforceable here",
            rmdirBlocked,
        )

        val root = temp.newFolder("packs")
        val prepared = prepare(root, "undeletable-skeleton-pack")
        val packDir = File(root, "undeletable-skeleton-pack")
        assertTrue(packDir.isDirectory)

        // The staging token itself is reclaimed (its parent stays writable),
        // but the pack dir is read-only, so the rmdir of the now-empty
        // `.staging` skeleton directory fails: that is a genuine empty-
        // skeleton delete failure and must surface as a typed IO red card,
        // not a silent success that leaves the residue behind.
        packDir.setWritable(false, false)
        try {
            importer(root).discard(prepared)
            fail("expected a typed IO failure for the undeletable empty skeleton")
        } catch (e: PackException) {
            assertEquals(PackErrorCode.IO_FAILED, e.diagnostic.code)
            assertTrue(e.diagnostic.isRedCard)
            assertTrue(
                "expected the empty-skeleton failure, got: ${e.diagnostic.message}",
                e.diagnostic.message.contains("cannot reclaim the empty pack skeleton"),
            )
        } finally {
            packDir.setWritable(true, false)
        }
    }

    @Test
    fun discardReportsResidualStagingAsTypedIoFailure() {
        val probeDir = temp.newFolder("probe")
        val probe = File(probeDir, "probe.txt")
        probe.writeText("probe")
        val probeStream = FileInputStream(probe)
        val deletionBlocked = !probe.delete()
        probeStream.close()
        probe.delete()
        Assume.assumeTrue(
            "this platform does not block deletion of an open file; the residual branch is not enforceable here",
            deletionBlocked,
        )

        val root = temp.newFolder("packs")
        val prepared = prepare(root, "locked-staging-pack")
        val stagedFiles = prepared.stagingDir.listFiles()?.filter { it.isFile }.orEmpty()
        assertTrue(stagedFiles.isNotEmpty())
        val lock = FileInputStream(stagedFiles.first())

        try {
            importer(root).discard(prepared)
            fail("expected a typed IO failure for the residual staging")
        } catch (e: PackException) {
            assertEquals(PackErrorCode.IO_FAILED, e.diagnostic.code)
            assertTrue(e.diagnostic.isRedCard)
            assertTrue(e.diagnostic.message.contains("cannot discard import staging"))
        } finally {
            lock.close()
        }
    }
}
