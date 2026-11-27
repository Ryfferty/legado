package io.legado.pack.manager

import android.os.Build
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.pack.api.ComponentKind
import io.legado.pack.api.PackCaller
import io.legado.pack.api.PackErrorCode
import io.legado.pack.api.PackGrant
import io.legado.pack.api.PackHostEnvironment
import io.legado.pack.api.PackId
import io.legado.pack.api.PackImportHint
import io.legado.pack.api.PackLibVersion
import io.legado.pack.api.PackPlatform
import io.legado.pack.api.PackRuntimeProvider
import io.legado.pack.api.PackState
import io.legado.pack.api.NativeAbi
import io.legado.pack.api.NativeLoadMode
import io.legado.pack.registry.PackRegistry
import io.legado.pack.runtime.DETAIL_RESIDENT_AFTER_FAILURE
import io.legado.pack.runtime.nativebridge.NativePackRuntimeProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Manager-level lifecycle for a native component that dlopen()s successfully
 * and then fails (the checked-in `broken-native-<abi>.so` has no
 * `pack_get_api_v1` export). The library stays mapped in the process, so:
 *
 * * the invoke fails with NATIVE_PLATFORM and the resident-after-failure
 *   detail carried across the JNI bridge;
 * * the pack is quarantined NEEDS_RESTART (no closable instance exists);
 * * the uninstall report says the code is still resident and needs a restart
 *   instead of claiming a clean release with the disk emptied.
 */
@RunWith(AndroidJUnit4::class)
class NativeResidentUninstallDeviceTest {

    private val packId = PackId.require("resident-native-pack")

    private fun deviceAbi(): NativeAbi = NativeAbi.parse(Build.SUPPORTED_ABIS.first())
        ?: error("device ABI '${Build.SUPPORTED_ABIS.first()}' is not a declared pack ABI")

    private class DeviceEnvironment(
        override val packsRoot: File,
        override val platform: PackPlatform,
    ) : PackHostEnvironment {
        override val frameworkPackLibVersion: PackLibVersion = PackLibVersion(1, 0, 0)
        override val defaultNativeLoadMode: NativeLoadMode = NativeLoadMode.FILE
        override fun runtimeProvider(kind: ComponentKind): PackRuntimeProvider? =
            if (kind == ComponentKind.NATIVE) NativePackRuntimeProvider(NativeLoadMode.FILE) else null
    }

    private fun brokenPackZip(): ByteArray {
        val abi = deviceAbi()
        val manifestJson = """
            {"schemaVersion":1,"id":"resident-native-pack","name":"Resident native","version":"1.0.0","versionCode":1,
             "packLib":">=1.0.0 <2.0.0","components":[{"id":"main","type":"native","path":"entry.so","abi":"${abi.wire}","abiVersion":1}],
             "commands":[{"name":"hello","component":"main"}]}
        """.trimIndent()
        val so = InstrumentationRegistry.getInstrumentation().context.assets
            .open("runtime-fixtures/broken-native-${abi.wire}.so").use { it.readBytes() }
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("pack.json"))
            zip.write(manifestJson.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("entry.so"))
            zip.write(so)
            zip.closeEntry()
        }
        return output.toByteArray()
    }

    @Test
    fun mappedButFailedNativeLoadIsQuarantinedAndUninstallReportsResidency() = runBlocking {
        val root = File(
            InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
            "pack-resident-test-${UUID.randomUUID()}",
        ).also { check(it.mkdirs()) }
        val platform = PackPlatform(
            Build.VERSION.SDK_INT,
            Build.SUPPORTED_ABIS.mapNotNull(NativeAbi::parse).toSet(),
            Os.sysconf(OsConstants._SC_PAGESIZE).toInt(),
        )
        val registry = PackRegistry()
        val manager = DefaultPackManager(DeviceEnvironment(root, platform), registry)
        try {
            val preview = manager.prepareImport(
                ByteArrayInputStream(brokenPackZip()),
                PackImportHint(),
            )
            manager.commitImport(
                preview.token,
                PackGrant(permissionHash = preview.permissionHash, networkOpenApproved = true),
            )

            // The broken library maps, then fails on the missing entry symbol;
            // the bridge reports the mapping through the typed failure detail.
            val result = manager.invokeBlocking(packId, "hello", "{}", PackCaller.Host)
            assertEquals(PackErrorCode.NATIVE_PLATFORM, result.error!!.code)
            assertTrue(
                "invoke failure must carry the resident-after-failure detail: ${result.error!!.details}",
                result.error!!.details[DETAIL_RESIDENT_AFTER_FAILURE] == "true",
            )
            assertEquals(PackState.NEEDS_RESTART, manager.info(packId)!!.state)

            val report = manager.uninstall(packId)
            assertTrue("residual files remain: ${report.residualPaths}", report.residualPaths.isEmpty())
            assertTrue(
                "mapped-but-failed native code must be reported as still resident",
                report.loadedCodeStillResident,
            )
            assertTrue(report.needsRestart)
            assertFalse(report.clean)
            assertEquals(PackErrorCode.NEEDS_RESTART, report.failure!!.code)
        } finally {
            manager.close()
            registry.close()
            root.deleteRecursively()
        }
    }
}
