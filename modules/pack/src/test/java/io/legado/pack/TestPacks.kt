package io.legado.pack

import io.legado.pack.api.PackDiagnostic
import io.legado.pack.api.PackId
import io.legado.pack.api.PackManifest
import io.legado.pack.api.PackRef
import io.legado.pack.api.RevisionId
import io.legado.pack.manifest.ManifestParseResult
import io.legado.pack.manifest.ManifestValidationContext
import io.legado.pack.manifest.PackManifestParser

object TestPacks {

    const val DEFAULT_COMPONENTS = """[{"id":"main","type":"js","entry":"main.js"}]"""
    const val DEFAULT_COMMANDS = """[{"name":"hello","component":"main","source":true}]"""
    const val DEFAULT_CAPABILITIES = """["SOURCE_COMMANDS"]"""

    fun manifestJson(
        id: String = "hello-js",
        name: String = "Hello JS",
        version: String = "1.0.0",
        versionCode: Long = 1,
        packLib: String = ">=1.0.0 <2.0.0",
        components: String = DEFAULT_COMPONENTS,
        commands: String = DEFAULT_COMMANDS,
        capabilities: String = DEFAULT_CAPABILITIES,
        network: String? = null,
        mcpTools: String? = null,
        apiRoutes: String? = null,
        selfTests: String? = null,
        extra: String? = null,
    ): String {
        val fields = mutableListOf<String>()
        fields += "\"schemaVersion\":1"
        fields += "\"id\":${jsonString(id)}"
        fields += "\"name\":${jsonString(name)}"
        fields += "\"version\":${jsonString(version)}"
        fields += "\"versionCode\":$versionCode"
        fields += "\"packLib\":${jsonString(packLib)}"
        fields += "\"capabilities\":$capabilities"
        fields += "\"components\":$components"
        fields += "\"commands\":$commands"
        if (network != null) fields += "\"network\":$network"
        if (mcpTools != null) fields += "\"mcpTools\":$mcpTools"
        if (apiRoutes != null) fields += "\"apiRoutes\":$apiRoutes"
        if (selfTests != null) fields += "\"selfTests\":$selfTests"
        if (extra != null) fields += extra
        return fields.joinToString(separator = ",", prefix = "{", postfix = "}")
    }

    fun parseValid(
        json: String,
        context: ManifestValidationContext = ManifestValidationContext(),
    ): PackManifest {
        return when (val result = PackManifestParser(context).parseJson(json)) {
            is ManifestParseResult.Valid -> result.manifest
            is ManifestParseResult.Invalid -> throw AssertionError(
                "expected valid manifest, got: " + describe(result.diagnostics),
            )
        }
    }

    fun parseInvalid(
        json: String,
        context: ManifestValidationContext = ManifestValidationContext(),
    ): List<PackDiagnostic> {
        return when (val result = PackManifestParser(context).parseJson(json)) {
            is ManifestParseResult.Valid -> throw AssertionError("expected invalid manifest")
            is ManifestParseResult.Invalid -> result.diagnostics
        }
    }

    fun describe(diagnostics: List<PackDiagnostic>): String =
        diagnostics.joinToString("; ") { "${it.code.wire}@${it.path}: ${it.message}" }

    fun ref(id: String, revisionChar: Char = 'a', epoch: Long = 1L): PackRef =
        PackRef(
            id = PackId.require(id),
            revision = RevisionId.require(revisionChar.toString().repeat(64)),
            epoch = epoch,
        )

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) {
                    append("\\u")
                    append(char.code.toString(16).padStart(4, '0'))
                } else {
                    append(char)
                }
            }
        }
        append('"')
    }
}