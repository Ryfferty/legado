package io.legado.pack.runtime

import android.os.Build
import android.system.Os
import android.system.OsConstants
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.pack.api.*
import io.legado.pack.manifest.ManifestParseResult
import io.legado.pack.manifest.PackManifestParser
import java.io.File
import java.io.InputStream

/** Fixtures are real generated D8/NDK assets; missing assets fail rather than skip. */
internal class DeviceRuntimeContext(
    private val root: File,
    componentJson: String,
    private val resource: String,
    private val fixtureAsset: String,
    /** Lets a test inject a different platform (e.g. a low sdkInt) without Build reads. */
    platformOverride: PackPlatform? = null,
) : PackContext {
    override val manifest = (PackManifestParser().parseJson("""
        {"schemaVersion":1,"id":"runtime-device","name":"Runtime smoke","version":"1.0.0","versionCode":1,
         "packLib":">=1.0.0 <2.0.0","components":[$componentJson],
         "commands":[{"name":"hello","component":"main"},{"name":"echo","component":"main"}]}
    """.trimIndent()) as ManifestParseResult.Valid).manifest
    override val ref = PackRef(manifest.id, RevisionId.require("b".repeat(64)), 1)
    override val platform = platformOverride ?: PackPlatform(Build.VERSION.SDK_INT,
        Build.SUPPORTED_ABIS.mapNotNull(NativeAbi::parse).toSet(), Os.sysconf(OsConstants._SC_PAGESIZE).toInt())
    override val limits = PackResourceLimits()
    override val storage = object : PackStorage {
        override fun openResource(relative: RelativePath): InputStream {
            check(relative.value == resource)
            return InstrumentationRegistry.getInstrumentation().context.assets.open(fixtureAsset)
        }
        override fun resourceExists(relative: RelativePath): Boolean = relative.value == resource
        override fun dataFile(relative: RelativePath): File {
            val file = File(root, relative.value)
            check(file.canonicalPath.startsWith(root.canonicalPath + File.separator))
            return file
        }
    }
    override val kv = object : PackKeyValueStore {
        override fun get(key: String): String? = error("unused")
        override fun put(key: String, value: String) = error("unused")
        override fun remove(key: String) = error("unused")
        override fun keys(): Set<String> = emptySet()
    }
    override val http = object : PackHttpClient {
        override fun execute(request: PackHttpRequest, call: PackCallContext): PackHttpResponse = error("unused")
    }
    override val crypto = object : PackCrypto {
        override fun digest(algorithm: String, data: ByteArray): ByteArray = error("unused")
        override fun hmac(algorithm: String, key: ByteArray, data: ByteArray): ByteArray = error("unused")
        override fun cipher(spec: String, key: ByteArray, iv: ByteArray?, data: ByteArray): ByteArray = error("unused")
    }
    override val logger = object : PackLogger {
        override fun log(level: PackLogLevel, message: String, details: Map<String, String>) = Unit
    }
    override val components = object : PackComponentInvoker {
        override fun invoke(componentId: String, command: String, argJson: String, call: PackCallContext): String = error("unused")
    }
    fun call(timeoutMs: Long = 10000) = PackCallContext.after("device-runtime", ref, PackCaller.Host, timeoutMs)
}
