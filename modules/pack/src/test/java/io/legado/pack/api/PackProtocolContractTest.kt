package io.legado.pack.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PackProtocolContractTest {

    @Test
    fun `js export names are declared command names`() {
        assertEquals("hello", PackJsProtocol.commandExportName("hello"))
        assertEquals("__packExports", PackJsProtocol.EXPORTS_OBJECT)
        assertEquals("__packApi", PackJsProtocol.API_OBJECT)
        assertTrue(PackJsProtocol.MAX_VALUE_DEPTH > 0)
        assertTrue(PackJsProtocol.MAX_JSON_CHARS > 0)
        assertThrows(IllegalArgumentException::class.java) {
            PackJsProtocol.commandExportName("Bad Name")
            Unit
        }
    }

    @Test
    fun `native abi one constants and mode mapping are fixed`() {
        assertEquals(1, PackNativeAbiV1.ABI_VERSION)
        assertEquals("pack_get_api_v1", PackNativeAbiV1.ENTRY_SYMBOL)
        assertEquals(0, PackNativeAbiV1.modeOf(NativeLoadMode.FILE))
        assertEquals(1, PackNativeAbiV1.modeOf(NativeLoadMode.MEMFD))
        assertEquals(0, PackNativeAbiV1.CALL_ACTIVE)
        assertEquals(1, PackNativeAbiV1.CALL_CANCELLED)
        assertEquals(2, PackNativeAbiV1.CALL_TIMEOUT)
        assertTrue(PackNativeAbiV1.ERROR_TIMEOUT < 0)
        assertTrue(PackNativeAbiV1.CLOSE_STILL_RESIDENT > 0)
    }
}