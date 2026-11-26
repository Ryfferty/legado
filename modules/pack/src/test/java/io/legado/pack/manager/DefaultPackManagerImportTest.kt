package io.legado.pack.manager

import io.legado.pack.api.PackCaller
import io.legado.pack.api.PackCallStatus
import io.legado.pack.api.PackErrorCode
import io.legado.pack.api.PackException
import io.legado.pack.api.PackGrant
import io.legado.pack.api.PackId
import io.legado.pack.api.PackImportHint
import io.legado.pack.api.PackState
import io.legado.pack.registry.PackRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

class DefaultPackManagerImportTest {

    @Rule
    @JvmField
    val temp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var provider: FakeRuntimeProvider
    private lateinit var environment: FakeEnvironment
    private lateinit var registry: PackRegistry
    private lateinit var manager: DefaultPackManager

    @Before
    fun setUp() {
        root = temp.newFolder("packs")
        provider = FakeRuntimeProvider()
        environment = FakeEnvironment(root, mapOf(io.legado.pack.api.ComponentKind.JS to provider))
        registry = PackRegistry()
        manager = DefaultPackManager(environment, registry)
    }

    @After
    fun tearDown() {
        manager.close()
        registry.close()
    }

    @Test
    fun singleFileImportIsLazyAndInstancesAreCached() = runBlocking {
        val installed = PackFixtures.install(manager, PackFixtures.userscript())
        assertEquals(0, provider.probeCount.get())
        assertEquals(0, provider.loadCount.get())
        assertEquals(PackState.ENABLED, installed.info.state)
        assertEquals(1, manager.list().size)
        assertTrue(registry.snapshot().toolNames.isEmpty())

        val first = manager.invokeBlocking(
            PackId.require("hello-js"),
            "hello",
            "{\"x\":1}",
            PackCaller.Host,
        )
        assertEquals(PackCallStatus.OK, first.status)
        assertEquals("{\"x\":1}", first.dataJson)
        assertEquals(1, provider.probeCount.get())
        assertEquals(1, provider.loadCount.get())

        val second = manager.invokeBlocking(
            PackId.require("hello-js"),
            "hello",
            "{}",
            PackCaller.Host,
        )
        assertEquals(PackCallStatus.OK, second.status)
        assertEquals(1, provider.loadCount.get())
    }

    @Test
    fun recoveredManagerReadsMetadataWithoutExecutingPack() = runBlocking {
        PackFixtures.install(manager, PackFixtures.userscript())
        manager.close()

        manager = DefaultPackManager(environment, registry)
        val info = manager.info(PackId.require("hello-js"))
        assertNotNull(info)
        assertEquals(0, provider.loadCount.get())
        val result = manager.invokeBlocking(
            PackId.require("hello-js"),
            "hello",
            "{}",
            PackCaller.Host,
        )
        assertEquals(PackCallStatus.OK, result.status)
        assertEquals(1, provider.loadCount.get())
    }

    @Test
    fun zipImportMaterializesDeclaredFiles() = runBlocking {
        val manifest = PackFixtures.manifestJson(
            id = "zip-pack",
            components = """[{"id":"main","type":"js","entry":"js/main.js"}]""",
            commands = """[{"name":"hello","component":"main","source":true}]""",
        )
        val zip = PackFixtures.zip(
            manifest,
            mapOf("js/main.js" to "globalThis.__packExports = { hello: function(a){ return a; } };"),
        )
        val installed = PackFixtures.install(manager, zip)
        val revision = installed.info.ref.revision
        val revisionDir = File(File(File(root, "zip-pack"), "code"), revision.value)
        assertTrue(File(revisionDir, "pack.json").isFile)
        assertTrue(File(revisionDir, "js/main.js").isFile)
        val result = manager.invokeBlocking(
            PackId.require("zip-pack"),
            "hello",
            "{\"ok\":true}",
            PackCaller.Host,
        )
        assertEquals(PackCallStatus.OK, result.status)
    }

    @Test
    fun registrationConflictDoesNotReplaceAnExistingPack() = runBlocking {
        val toolCapabilities = """["SOURCE_COMMANDS","MCP_TOOLS"]"""
        val commands = """[{"name":"hello","component":"main","source":true},{"name":"echo","component":"main"}]"""
        val tools = """[{"name":"demo_add","command":"echo"}]"""

        val first = PackFixtures.install(
            manager,
            PackFixtures.userscript(
                id = "pack-a",
                capabilities = listOf("SOURCE_COMMANDS", "MCP_TOOLS"),
                commands = listOf("hello source", "echo"),
                mcpTools = listOf("demo_add echo read"),
            ),
            grant = { PackFixtures.defaultGrant(it, toolPermissions = mapOf("demo_add" to true)) },
        )
        assertEquals(setOf("demo_add"), registry.snapshot().toolNames)
        val firstRef = first.info.ref
        val firstLoads = provider.loadCount.get()

        val conflicting = PackFixtures.userscript(
            id = "pack-b",
            capabilities = listOf("SOURCE_COMMANDS", "MCP_TOOLS"),
            commands = listOf("hello source", "echo"),
            mcpTools = listOf("demo_add echo read"),
        )
        val preview = manager.prepareImport(ByteArrayInputStream(conflicting), PackImportHint())
        try {
            manager.commitImport(preview.token, PackFixtures.defaultGrant(preview, mapOf("demo_add" to true)))
            fail("expected TOOL_CONFLICT")
        } catch (e: PackException) {
            assertEquals(PackErrorCode.TOOL_CONFLICT, e.diagnostic.code)
        }

        assertNull(manager.info(PackId.require("pack-b")))
        assertFalse(File(root, "pack-b").exists())
        assertEquals(firstRef, manager.info(PackId.require("pack-a"))!!.ref)
        assertEquals(setOf("demo_add"), registry.snapshot().toolNames)
        val call = manager.invokeBlocking(
            PackId.require("pack-a"),
            "hello",
            "{}",
            PackCaller.Host,
        )
        assertEquals(PackCallStatus.OK, call.status)
        assertEquals(firstLoads + 1, provider.loadCount.get())
    }

    @Test
    fun failedUpgradeKeepsOldRevisionAndEpoch() = runBlocking {
        val v1 = PackFixtures.install(manager, PackFixtures.userscript(versionCode = 1))
        val oldRef = manager.info(PackId.require("hello-js"))!!.ref

        val v2 = PackFixtures.userscript(versionCode = 2, body = "globalThis.__packExports = { hello: function(){ return 2; } };")
        val preview = manager.prepareImport(ByteArrayInputStream(v2), PackImportHint())
        try {
            manager.commitImport(preview.token, PackGrant("0".repeat(64)))
            fail("expected permission failure")
        } catch (e: PackException) {
            assertEquals(PackErrorCode.PERMISSION_DENIED, e.diagnostic.code)
        }
        val unchanged = manager.info(PackId.require("hello-js"))!!
        assertEquals(oldRef, unchanged.ref)
        assertEquals(v1.info.ref.revision, unchanged.ref.revision)
        assertTrue(File(File(File(root, "hello-js"), "code"), oldRef.revision.value).isDirectory)
    }

    @Test
    fun tamperedStagingContentCannotBeCommitted() = runBlocking {
        val preview = manager.prepareImport(ByteArrayInputStream(PackFixtures.userscript()), PackImportHint())
        val staging = File(File(File(root, "hello-js"), ".staging"), preview.token)
        val main = File(staging, "main.js")
        assertTrue(main.isFile)
        main.writeText("globalThis.__packExports = { hello: function(){ return 'tampered'; } };")

        try {
            manager.commitImport(preview.token, PackFixtures.defaultGrant(preview))
            fail("expected staging integrity failure")
        } catch (e: PackException) {
            assertEquals(PackErrorCode.IO_FAILED, e.diagnostic.code)
        }
        assertNull(manager.info(PackId.require("hello-js")))
        assertEquals(0, provider.loadCount.get())
    }

    @Test
    fun openNetworkRequiresExplicitApprovalInGrant() = runBlocking {
        val content = PackFixtures.userscript(network = "open")
        val preview = manager.prepareImport(ByteArrayInputStream(content), PackImportHint())
        assertTrue(preview.requiresOpenNetworkApproval)
        try {
            manager.commitImport(preview.token, PackFixtures.defaultGrant(preview, networkOpenApproved = false))
            fail("expected consent failure")
        } catch (e: PackException) {
            assertEquals(PackErrorCode.NETWORK_OPEN_REQUIRES_CONSENT, e.diagnostic.code)
        }
        val installed = manager.commitImport(preview.token, PackFixtures.defaultGrant(preview, networkOpenApproved = true))
        assertEquals(PackState.ENABLED, installed.state)
    }

    @Test
    fun startupRecoveryRemovesStagingAndOrphanCode() = runBlocking {
        val installed = PackFixtures.install(manager, PackFixtures.userscript(id = "recover-pack"))
        val packDir = File(root, "recover-pack")
        val leftoverStaging = File(File(packDir, ".staging"), "leftover")
        leftoverStaging.mkdirs()
        File(leftoverStaging, "main.js").writeText("junk")
        val orphan = File(File(packDir, "code"), "f".repeat(64))
        orphan.mkdirs()
        File(orphan, "junk.txt").writeText("junk")

        manager.close()
        manager = DefaultPackManager(environment, registry)
        assertFalse(leftoverStaging.exists())
        assertFalse(orphan.exists())
        val info = manager.info(PackId.require("recover-pack"))
        assertNotNull(info)
        assertEquals(installed.info.ref.revision, info!!.ref.revision)
        val call = manager.invokeBlocking(
            PackId.require("recover-pack"),
            "hello",
            "{}",
            PackCaller.Host,
        )
        assertEquals(PackCallStatus.OK, call.status)
    }

    @Test
    fun prepareDoesNotTouchRuntimeProviderForZipOrUserscript() = runBlocking {
        PackFixtures.install(manager, PackFixtures.userscript(id = "hello-js"))
        val manifest = PackFixtures.manifestJson(
            id = "zip-pack",
            components = """[{"id":"main","type":"js","entry":"main.js"}]""",
            commands = """[{"name":"hello","component":"main","source":true}]""",
        )
        PackFixtures.install(manager, PackFixtures.zip(manifest, mapOf("main.js" to "globalThis.__packExports = { hello: function(a){ return a; } };")))
        assertEquals(0, provider.probeCount.get())
        assertEquals(0, provider.loadCount.get())
    }
}