package io.legado.app.help.pack

import io.legado.pack.api.ComponentKind
import io.legado.pack.api.NativeAbi
import io.legado.pack.api.NativeLoadMode
import io.legado.pack.api.PackHostEnvironment
import io.legado.pack.api.PackLibVersion
import io.legado.pack.api.PackPlatform
import io.legado.pack.api.PackRuntimeProvider
import java.io.File

class FakePackHostEnvironment(
    override val packsRoot: File,
    override val platform: PackPlatform = PackPlatform(
        sdkInt = 36,
        supportedAbis = setOf(NativeAbi.ARM64_V8A),
    ),
    override val frameworkPackLibVersion: PackLibVersion = PackLibVersion.CURRENT,
    override val defaultNativeLoadMode: NativeLoadMode = NativeLoadMode.FILE,
) : PackHostEnvironment {
    override fun runtimeProvider(kind: ComponentKind): PackRuntimeProvider? = null
}