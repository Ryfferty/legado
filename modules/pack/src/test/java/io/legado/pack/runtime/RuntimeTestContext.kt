package io.legado.pack.runtime

import io.legado.pack.api.*
import io.legado.pack.manifest.ManifestParseResult
import io.legado.pack.manifest.PackManifestParser
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

internal class RuntimeTestContext(source: String, capabilities: Set<PackCapability> = emptySet()) : PackContext {
    override val manifest: PackManifest = (PackManifestParser().parseUserScript("""
        // ==Pack==
        // @id hello-js
        // @name Hello
        // @version 1.0.0
        // @versionCode 1
        // @packLib >=1.0.0 <2.0.0
        // @command hello
        ${capabilities.joinToString("\n") { "// @capability ${it.name}" }}
        // ==/Pack==
        $source
    """.trimIndent()) as ManifestParseResult.Valid).manifest
    override val ref = PackRef(manifest.id, RevisionId.require("a".repeat(64)), 1)
    override val platform = PackPlatform(36, setOf(NativeAbi.X86_64))
    override val limits = PackResourceLimits()
    private val sourceBytes = source.toByteArray(Charsets.UTF_8)
    override val storage = object : PackStorage {
        override fun openResource(relative: RelativePath): InputStream = ByteArrayInputStream(sourceBytes)
        override fun resourceExists(relative: RelativePath): Boolean = relative.value == "main.js"
        override fun dataFile(relative: RelativePath): File = error("JS must never receive raw files")
    }
    val values = ConcurrentHashMap<String, String>()
    var onGet: (() -> Unit)? = null
    override val kv = object : PackKeyValueStore {
        override fun get(key: String): String? { onGet?.invoke(); return values[key] }
        override fun put(key: String, value: String) { values[key] = value }
        override fun remove(key: String) { values.remove(key) }
        override fun keys(): Set<String> = values.keys
    }
    override val http = object : PackHttpClient {
        override fun execute(request: PackHttpRequest, call: PackCallContext): PackHttpResponse = error("unexpected network")
    }
    override val crypto = object : PackCrypto {
        override fun digest(algorithm: String, data: ByteArray): ByteArray = java.security.MessageDigest.getInstance(algorithm).digest(data)
        override fun hmac(algorithm: String, key: ByteArray, data: ByteArray): ByteArray = error("unused")
        override fun cipher(spec: String, key: ByteArray, iv: ByteArray?, data: ByteArray): ByteArray {
            cipherCalls[spec] = iv to data
            return data
        }
    }

    /** Recorded cryptoCipher calls: spec -> (iv, data). */
    val cipherCalls = ConcurrentHashMap<String, Pair<ByteArray?, ByteArray>>()
    override val logger = object : PackLogger {
        override fun log(level: PackLogLevel, message: String, details: Map<String, String>) = Unit
    }
    override val components = object : PackComponentInvoker {
        override fun invoke(componentId: String, command: String, argJson: String, call: PackCallContext): String = error("unused")
    }
    fun call(timeoutMs: Long = 5000) = PackCallContext.after("runtime-test", ref, PackCaller.Host, timeoutMs)
}
