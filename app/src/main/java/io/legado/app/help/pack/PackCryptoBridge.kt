package io.legado.app.help.pack

import io.legado.app.help.JsEncodeUtils

/**
 * Narrow `java.crypto` facade for script use.
 *
 * It deliberately reuses the existing [JsEncodeUtils] names and behaviour
 * (`digestHex`, `digestBase64Str`, `HMacHex`, `HMacBase64`, legacy
 * `md5`/`md5Encode`/`md5Encode16`) instead of introducing a second crypto
 * dialect. The old top-level `java.md5Encode*` functions remain untouched.
 */
object PackCryptoBridge {

    private val delegate: JsEncodeUtils = object : JsEncodeUtils {}

    fun md5(data: String): String = delegate.md5Encode(data)

    fun md5Encode(data: String): String = delegate.md5Encode(data)

    fun md5Encode16(data: String): String = delegate.md5Encode16(data)

    fun digestHex(data: String, algorithm: String): String =
        delegate.digestHex(data, algorithm)

    fun digestBase64Str(data: String, algorithm: String): String =
        delegate.digestBase64Str(data, algorithm)

    @Suppress("FunctionName")
    fun HMacHex(data: String, algorithm: String, key: String): String =
        delegate.HMacHex(data, algorithm, key)

    @Suppress("FunctionName")
    fun HMacBase64(data: String, algorithm: String, key: String): String =
        delegate.HMacBase64(data, algorithm, key)
}