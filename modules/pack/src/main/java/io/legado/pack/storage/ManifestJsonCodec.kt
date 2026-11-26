package io.legado.pack.storage

import io.legado.pack.api.ComponentSpec
import io.legado.pack.api.NetworkPolicy
import io.legado.pack.api.PackException
import io.legado.pack.api.PackManifest
import io.legado.pack.manifest.ManifestParseResult
import io.legado.pack.manifest.ManifestValidationContext
import io.legado.pack.manifest.PackManifestParser

/**
 * Canonical on-disk representation of a parsed manifest.
 *
 * The manifest parser remains the single validator: decoding round-trips the
 * canonical JSON through it instead of maintaining a second, weaker schema.
 */
internal object ManifestJsonCodec {

    fun encode(manifest: PackManifest): String = buildString(1024) {
        append('{')
        field("schemaVersion", manifest.schemaVersion.toString(), first = true)
        field("id", PackJsonText.quote(manifest.id.value))
        field("name", PackJsonText.quote(manifest.name))
        field("version", PackJsonText.quote(manifest.version))
        field("versionCode", manifest.versionCode.toString())
        field("packLib", PackJsonText.quote(manifest.packLib.toString()))
        field("description", PackJsonText.quote(manifest.description))
        field("author", PackJsonText.quote(manifest.author))
        append(",\"capabilities\":")
        appendArray(manifest.capabilities.sortedBy { it.name }.map { PackJsonText.quote(it.name) })
        append(",\"network\":")
        append(networkJson(manifest.network))
        append(",\"components\":[")
        manifest.components.forEachIndexed { index, component ->
            if (index > 0) append(',')
            append(componentJson(component))
        }
        append(']')
        append(",\"commands\":[")
        manifest.commands.forEachIndexed { index, command ->
            if (index > 0) append(',')
            append('{')
            append("\"name\":").append(PackJsonText.quote(command.name))
            append(",\"component\":").append(PackJsonText.quote(command.componentId))
            append(",\"description\":").append(PackJsonText.quote(command.description))
            append(",\"source\":").append(command.sourceExported)
            append('}')
        }
        append(']')
        if (manifest.mcpTools.isNotEmpty()) {
            append(",\"mcpTools\":[")
            manifest.mcpTools.forEachIndexed { index, tool ->
                if (index > 0) append(',')
                append('{')
                append("\"name\":").append(PackJsonText.quote(tool.name))
                append(",\"command\":").append(PackJsonText.quote(tool.command))
                append(",\"description\":").append(PackJsonText.quote(tool.description))
                append(",\"inputSchema\":").append(PackJsonText.write(tool.inputSchema))
                append(",\"write\":").append(tool.isWrite)
                append('}')
            }
            append(']')
        }
        if (manifest.apiRoutes.isNotEmpty()) {
            append(",\"apiRoutes\":[")
            manifest.apiRoutes.forEachIndexed { index, route ->
                if (index > 0) append(',')
                append('{')
                append("\"name\":").append(PackJsonText.quote(route.name))
                append(",\"method\":").append(PackJsonText.quote(route.method.name))
                append(",\"command\":").append(PackJsonText.quote(route.command))
                append(",\"write\":").append(route.isWrite)
                append('}')
            }
            append(']')
        }
        if (manifest.selfTests.isNotEmpty()) {
            append(",\"selfTests\":[")
            manifest.selfTests.forEachIndexed { index, test ->
                if (index > 0) append(',')
                append('{')
                append("\"name\":").append(PackJsonText.quote(test.name))
                append(",\"command\":").append(PackJsonText.quote(test.command))
                test.timeoutMs?.let { append(",\"timeoutMs\":").append(it) }
                append('}')
            }
            append(']')
        }
        append('}')
    }

    fun decode(
        json: String,
        context: ManifestValidationContext,
    ): PackManifest {
        return when (val result = PackManifestParser(context).parseJson(json)) {
            is ManifestParseResult.Valid -> result.manifest
            is ManifestParseResult.Invalid -> {
                val first = result.diagnostics.first()
                throw PackException(
                    first,
                    IllegalStateException("stored manifest is no longer valid"),
                )
            }
        }
    }

    private fun networkJson(policy: NetworkPolicy): String = when (policy) {
        NetworkPolicy.None -> "{\"mode\":\"none\"}"
        is NetworkPolicy.Allowlist -> buildString {
            append("{\"mode\":\"patterns\",\"patterns\":[")
            policy.patterns.forEachIndexed { index, pattern ->
                if (index > 0) append(',')
                append(PackJsonText.quote(pattern.value))
            }
            append("]}")
        }

        is NetworkPolicy.Open -> buildString {
            append("{\"mode\":\"open\",\"reason\":")
            append(PackJsonText.quote(policy.reason))
            append('}')
        }
    }

    private fun componentJson(component: ComponentSpec): String = when (component) {
        is ComponentSpec.Js -> buildString {
            append('{')
            append("\"id\":").append(PackJsonText.quote(component.id))
            append(",\"type\":\"js\"")
            append(",\"entry\":").append(PackJsonText.quote(component.entry.value))
            append('}')
        }

        is ComponentSpec.Dex -> buildString {
            append('{')
            append("\"id\":").append(PackJsonText.quote(component.id))
            append(",\"type\":\"dex\"")
            append(",\"path\":").append(PackJsonText.quote(component.path.value))
            append(",\"class\":").append(PackJsonText.quote(component.className))
            append('}')
        }

        is ComponentSpec.Native -> buildString {
            append('{')
            append("\"id\":").append(PackJsonText.quote(component.id))
            append(",\"type\":\"native\"")
            append(",\"path\":").append(PackJsonText.quote(component.path.value))
            append(",\"abi\":").append(PackJsonText.quote(component.abi.wire))
            component.requestedMode?.let {
                append(",\"mode\":").append(PackJsonText.quote(it.wire))
            }
            append(",\"abiVersion\":").append(component.abiVersion)
            append('}')
        }
    }

    private fun StringBuilder.field(name: String, value: String, first: Boolean = false) {
        if (!first) append(',')
        append(PackJsonText.quote(name))
        append(':')
        append(value)
    }

    private fun StringBuilder.appendArray(values: List<String>) {
        append('[')
        values.forEachIndexed { index, value ->
            if (index > 0) append(',')
            append(value)
        }
        append(']')
    }
}