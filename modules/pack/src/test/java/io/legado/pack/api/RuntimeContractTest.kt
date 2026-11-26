package io.legado.pack.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeContractTest {

    private val nativeComponent = ComponentSpec.Native(
        id = "native",
        path = RelativePath.require("libs/arm64-v8a/libhello.so"),
        abi = NativeAbi.ARM64_V8A,
        requestedMode = NativeLoadMode.MEMFD,
    )

    @Test
    fun `manifest compatibility never claims native availability`() {
        val platform = PackPlatform(
            sdkInt = 36,
            supportedAbis = setOf(NativeAbi.ARM64_V8A),
            pageSizeBytes = 16_384,
        )

        val result = NativeLoadPreflight.check(nativeComponent, platform, NativeLoadMode.FILE)

        assertTrue(result is RuntimeAvailability.RequiresDeviceProbe)
    }

    @Test
    fun `memfd below api 30 is an explicit native platform failure`() {
        val platform = PackPlatform(
            sdkInt = 29,
            supportedAbis = setOf(NativeAbi.ARM64_V8A),
        )

        val result = NativeLoadPreflight.check(nativeComponent, platform, NativeLoadMode.FILE)

        assertTrue(result is RuntimeAvailability.Unavailable)
        assertEquals(
            PackErrorCode.NATIVE_PLATFORM,
            (result as RuntimeAvailability.Unavailable).failure.code,
        )
    }

    @Test
    fun `missing abi and unknown abi version are rejected before load`() {
        val platform = PackPlatform(sdkInt = 36, supportedAbis = setOf(NativeAbi.X86_64))

        assertTrue(
            NativeLoadPreflight.check(nativeComponent, platform, NativeLoadMode.FILE)
                is RuntimeAvailability.Unavailable,
        )

        val unsupportedVersion = nativeComponent.copy(abiVersion = 2)
        val armPlatform = PackPlatform(sdkInt = 36, supportedAbis = setOf(NativeAbi.ARM64_V8A))
        assertTrue(
            NativeLoadPreflight.check(unsupportedVersion, armPlatform, NativeLoadMode.FILE)
                is RuntimeAvailability.Unavailable,
        )
    }

    @Test
    fun `platform rejects invalid page size`() {
        assertThrows(IllegalArgumentException::class.java) {
            PackPlatform(
                sdkInt = 36,
                supportedAbis = setOf(NativeAbi.ARM64_V8A),
                pageSizeBytes = 5_000,
            )
            Unit
        }
    }
}