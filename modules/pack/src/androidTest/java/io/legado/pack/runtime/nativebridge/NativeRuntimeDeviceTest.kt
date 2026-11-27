package io.legado.pack.runtime.nativebridge

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.pack.api.CloseOutcome
import io.legado.pack.api.NativeAbi
import io.legado.pack.api.NativeLoadMode
import io.legado.pack.api.PackErrorCode
import io.legado.pack.api.PackException
import io.legado.pack.api.PackPlatform
import io.legado.pack.runtime.DeviceRuntimeContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Real native ABI-1 packs on the actual device, mirroring the DEX device test
 * (fixtures are checked-in NDK builds; missing assets fail rather than skip):
 *
 * * FILE/MEMFD load, invoke and the bounded-wait close that a healthy native
 *   library must report as *released*;
 * * the MEMFD gate driven by the injected platform sdkInt, not by a Build read;
 * * the per-path failure latch: a mapped-but-failed library must not be mapped
 *   again on retry under a new handle.
 */
@RunWith(AndroidJUnit4::class)
class NativeRuntimeDeviceTest {

    private fun deviceAbi(): NativeAbi = NativeAbi.parse(Build.SUPPORTED_ABIS.first())
        ?: error("device ABI '${Build.SUPPORTED_ABIS.first()}' is not a declared pack ABI")

    private fun context(root: File, mode: String? = null, platform: PackPlatform? = null): DeviceRuntimeContext {
        val abi = deviceAbi()
        val modeField = mode?.let { ",\"mode\":\"$it\"" } ?: ""
        return DeviceRuntimeContext(
            root,
            """{"id":"main","type":"native","path":"entry.so","abi":"${abi.wire}"$modeField,"abiVersion":1}""",
            "entry.so",
            "runtime-fixtures/hello-native-${abi.wire}.so",
            platform,
        )
    }

    private fun brokenContext(root: File): DeviceRuntimeContext {
        val abi = deviceAbi()
        return DeviceRuntimeContext(
            root,
            """{"id":"main","type":"native","path":"entry.so","abi":"${abi.wire}","abiVersion":1}""",
            "entry.so",
            "runtime-fixtures/broken-native-${abi.wire}.so",
        )
    }

    @Test
    fun realNativeFileLoadInvokeAndReleasedClose() {
        val root = newRoot()
        val context = context(root)
        try {
            val provider = NativePackRuntimeProvider(NativeLoadMode.FILE)
            val instance = provider.load(context.ref, context.manifest.components.single(), context, context.call())
            assertEquals(
                "{\"ok\":true,\"runtime\":\"native\",\"abiVersion\":1}",
                instance.invoke("hello", "null", context.call()),
            )
            assertEquals("{\"x\":3}", instance.invoke("echo", "{\"x\":3}", context.call()))
            // The close waits for the asynchronous native close within the
            // host budget: a cooperative library must report released instead
            // of the bogus immediate "resident" the old code produced.
            val close = instance.close()
            assertTrue("close not released: ${close.failure?.message}", close.released)
            assertFalse(close.stillResident)
            assertFalse(close.needsRestart)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun memfdModeLoadsInvokesAndReleasesWhenApiLevelAllows() {
        assumeTrue("MEMFD requires device API 30+", Build.VERSION.SDK_INT >= 30)
        val root = newRoot()
        val context = context(root, mode = "memfd")
        try {
            val provider = NativePackRuntimeProvider(NativeLoadMode.FILE)
            val instance = provider.load(context.ref, context.manifest.components.single(), context, context.call())
            assertEquals(
                "{\"ok\":true,\"runtime\":\"native\",\"abiVersion\":1}",
                instance.invoke("hello", "null", context.call()),
            )
            val close: CloseOutcome = instance.close()
            assertTrue("close not released: ${close.failure?.message}", close.released)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun memfdGateIsDrivenByTheInjectedPlatformSdkInt() {
        // A platform injected with sdkInt 29 must be rejected before any load,
        // proving the MEMFD gate reads the injected platform, never Build.
        val root = newRoot()
        val platform = PackPlatform(29, setOf(deviceAbi()), 4096)
        val context = context(root, mode = "memfd", platform = platform)
        try {
            val provider = NativePackRuntimeProvider(NativeLoadMode.FILE)
            val failure = runCatching {
                provider.load(context.ref, context.manifest.components.single(), context, context.call())
            }.exceptionOrNull()
            assertTrue("expected NATIVE_PLATFORM, got $failure", failure is PackException)
            assertEquals(PackErrorCode.NATIVE_PLATFORM, (failure as PackException).diagnostic.code)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun mappedFailureLatchesByPathAndRetryReusesIt() {
        val root = newRoot()
        val context = brokenContext(root)
        try {
            val provider = NativePackRuntimeProvider(NativeLoadMode.FILE)
            val first = runCatching {
                provider.load(context.ref, context.manifest.components.single(), context, context.call())
            }.exceptionOrNull() as PackException
            assertEquals(PackErrorCode.NATIVE_PLATFORM, first.diagnostic.code)
            assertTrue(
                "expected the missing-entry failure, got: ${first.diagnostic.message}",
                first.diagnostic.message.contains("pack_get_api_v1"),
            )
            // Retry with the same content-addressed path must reuse the failed
            // entry instead of mapping the library under a new handle.
            val second = runCatching {
                provider.load(context.ref, context.manifest.components.single(), context, context.call())
            }.exceptionOrNull() as PackException
            assertEquals(PackErrorCode.NATIVE_PLATFORM, second.diagnostic.code)
            assertTrue(
                "expected the latched retry failure, got: ${second.diagnostic.message}",
                second.diagnostic.message.contains("already failed for this path"),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    private fun newRoot(): File =
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "pack-native-test-${UUID.randomUUID()}")
            .also { check(it.mkdirs()) }
}
