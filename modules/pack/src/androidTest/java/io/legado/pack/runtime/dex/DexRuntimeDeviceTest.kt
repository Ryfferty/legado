package io.legado.pack.runtime.dex

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.pack.api.*
import io.legado.pack.runtime.DeviceRuntimeContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DexRuntimeDeviceTest {
    @Test fun realDexReadOnlyLoadInvokeAndResidentClose() {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "pack-runtime-test-${UUID.randomUUID()}")
        check(root.mkdirs())
        val context = DeviceRuntimeContext(root,
            """{"id":"main","type":"dex","path":"entry.dex","class":"io.legado.pack.fixture.HelloDexEntry"}""",
            "entry.dex", "runtime-fixtures/hello-dex.dex")
        try {
            val provider = DexPackRuntimeProvider()
            assertFalse(provider.capabilities.hardCancellation)
            assertFalse(provider.capabilities.safeUnload)
            val first = provider.load(context.ref, context.manifest.components.single(), context, context.call())
            assertEquals("{\"ok\":true,\"runtime\":\"dex\"}", first.invoke("hello", "null", context.call()))
            assertEquals("{\"x\":3}", first.invoke("echo", "{\"x\":3}", context.call()))
            val close = first.close()
            assertFalse(close.released)
            assertTrue(close.stillResident)
            assertTrue(close.needsRestart)
            assertTrue(close.failure?.message?.contains("classes/classloader remain resident") == true)
            assertThrows(PackException::class.java) { first.invoke("hello", "null", context.call()) }
            // Same provider reuses the code loader, but allocates a new instance.
            val second = provider.load(context.ref, context.manifest.components.single(), context, context.call())
            assertEquals("{\"ok\":true,\"runtime\":\"dex\"}", second.invoke("hello", "null", context.call()))
            second.close()
            val code = root.walkTopDown().filter { it.isFile && it.extension == "dex" }.toList()
            assertEquals(1, code.size)
            val mode = android.system.Os.stat(code.single().absolutePath).st_mode
            assertEquals(0, mode and 146 /* 0222 */)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun nonPackEntryIsRejectedBeforeStaticInitialization() {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "pack-runtime-test-${UUID.randomUUID()}")
        check(root.mkdirs())
        val context = DeviceRuntimeContext(root,
            """{"id":"main","type":"dex","path":"entry.dex","class":"io.legado.pack.fixture.NotPackEntry"}""",
            "entry.dex", "runtime-fixtures/hello-dex.dex")
        try {
            val failure = assertThrows(PackException::class.java) {
                DexPackRuntimeProvider().load(context.ref, context.manifest.components.single(), context, context.call())
            }
            assertEquals(PackErrorCode.LOAD_FAILED, failure.diagnostic.code)
            assertTrue(failure.diagnostic.message.contains("implement host PackEntry"))
        } finally {
            root.deleteRecursively()
        }
    }
}
