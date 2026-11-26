package io.legado.pack.api

import java.util.Locale

/** Optional framework primitives a pack explicitly declares. */
enum class PackCapability {
    KV,
    HTTP,
    CRYPTO,
    LOG,
    MCP_TOOLS,
    API_ROUTES,
    SOURCE_COMMANDS,
    SELF_TEST,
    SCHEDULER;

    companion object {
        fun parse(raw: String): PackCapability? {
            if (raw != raw.trim() || raw.isEmpty()) return null
            val normalized = raw.uppercase(Locale.ROOT)
            return entries.firstOrNull { it.name == normalized }
        }
    }
}

enum class ComponentKind(val wire: String) {
    JS("js"),
    DEX("dex"),
    NATIVE("native");

    companion object {
        fun parse(raw: String): ComponentKind? =
            entries.firstOrNull { it.wire == raw.lowercase(Locale.ROOT) }
    }
}

enum class NativeAbi(val wire: String) {
    ARMEABI_V7A("armeabi-v7a"),
    ARM64_V8A("arm64-v8a"),
    X86("x86"),
    X86_64("x86_64");

    companion object {
        fun parse(raw: String): NativeAbi? =
            entries.firstOrNull { it.wire == raw.lowercase(Locale.ROOT) }
    }
}

enum class NativeLoadMode(val wire: String) {
    FILE("file"),

    /**
     * Public `Os.memfd_create` path, only available on API 30+. Requesting it
     * on an older host is an explicit NATIVE_PLATFORM failure, never a silent
     * fallback to FILE.
     */
    MEMFD("memfd");

    companion object {
        fun parse(raw: String): NativeLoadMode? =
            entries.firstOrNull { it.wire == raw.lowercase(Locale.ROOT) }
    }
}

/**
 * A declared executable component. Declaring a component here does not make
 * it runnable: a host runtime provider must report availability and load it
 * lazily on first use.
 */
sealed interface ComponentSpec {

    val id: String
    val kind: ComponentKind

    data class Js(
        override val id: String,
        val entry: RelativePath,
    ) : ComponentSpec {
        override val kind: ComponentKind get() = ComponentKind.JS

        init {
            require(ComponentSpec.isValidId(id)) { "invalid JS component id: '$id'" }
        }
    }

    data class Dex(
        override val id: String,
        val path: RelativePath,
        val className: String,
    ) : ComponentSpec {
        override val kind: ComponentKind get() = ComponentKind.DEX

        init {
            require(ComponentSpec.isValidId(id)) { "invalid DEX component id: '$id'" }
            require(ComponentSpec.isValidClassName(className)) {
                "invalid DEX class name: '$className'"
            }
        }
    }

    data class Native(
        override val id: String,
        val path: RelativePath,
        val abi: NativeAbi,
        val requestedMode: NativeLoadMode? = null,
        val abiVersion: Int = 1,
    ) : ComponentSpec {
        override val kind: ComponentKind get() = ComponentKind.NATIVE

        init {
            require(ComponentSpec.isValidId(id)) { "invalid native component id: '$id'" }
            require(abiVersion >= 1) { "abiVersion must be >= 1" }
        }
    }

    companion object {
        private val ID_FORMAT = Regex("[a-z][a-z0-9_-]{0,63}")
        private val BINARY_CLASS_NAME =
            Regex("(?:[A-Za-z_$][A-Za-z0-9_$]*\\.)*[A-Za-z_$][A-Za-z0-9_$]*")

        fun isValidId(id: String): Boolean =
            id.length <= 64 && ID_FORMAT.matches(id) && !id.endsWith("_") && !id.endsWith("-")

        fun isValidClassName(className: String): Boolean =
            className.length <= 512 && !className.contains('/') && BINARY_CLASS_NAME.matches(className)
    }
}

/** A command exported by one component. */
data class CommandSpec(
    val name: String,
    val componentId: String,
    val description: String = "",

    /**
     * Whether ordinary book-source JS may call this command through the narrow
     * `java.engineCall` bridge. Must be explicit; the bridge must reject
     * every command that does not set it.
     */
    val sourceExported: Boolean = false,
) {
    init {
        require(isValidName(name)) { "invalid command name: '$name'" }
        require(ComponentSpec.isValidId(componentId)) { "invalid component id: '$componentId'" }
        require(description.length <= 512) { "command description is too long" }
    }

    companion object {
        private val NAME_FORMAT = Regex("[a-z][a-z0-9_-]{0,63}")

        fun isValidName(name: String): Boolean =
            name.length <= 64 && NAME_FORMAT.matches(name) && !name.endsWith("_") && !name.endsWith("-")
    }
}

/** MCP tool declaration. [isWrite] is only an authorization hint for the host. */
data class ToolSpec(
    val name: String,
    val command: String,
    val description: String = "",
    val inputSchema: JsonValue.Obj = emptyObjectSchema(),
    val isWrite: Boolean = false,
) {
    init {
        require(isValidName(name)) { "invalid tool name: '$name'" }
        require(CommandSpec.isValidName(command)) { "invalid tool command: '$command'" }
        require(description.length <= 1024) { "tool description is too long" }
        require(PackToolSchema.isValid(inputSchema)) {
            "tool inputSchema is outside the supported JSON Schema subset"
        }
    }

    companion object {
        private val NAME_FORMAT = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")

        fun isValidName(name: String): Boolean =
            name.length <= 64 && NAME_FORMAT.matches(name) && !name.endsWith("_") && !name.endsWith("-")
    }
}

enum class PackHttpMethod {
    GET,
    POST;

    companion object {
        fun parse(raw: String): PackHttpMethod? {
            if (raw != raw.trim()) return null
            return entries.firstOrNull { it.name == raw.uppercase(Locale.ROOT) }
        }
    }
}

/** Declarative Web route at `/packs/<id>/api/<name>`. */
data class RouteSpec(
    val name: String,
    val method: PackHttpMethod,
    val command: String,
    val isWrite: Boolean = false,
) {
    init {
        require(isValidName(name)) { "invalid route name: '$name'" }
        require(CommandSpec.isValidName(command)) { "invalid route command: '$command'" }
        require(!isWrite || method == PackHttpMethod.POST) {
            "a write route must use POST"
        }
    }

    companion object {
        private val NAME_FORMAT = Regex("[a-z][a-z0-9_-]{0,63}")

        fun isValidName(name: String): Boolean =
            name.length <= 64 && NAME_FORMAT.matches(name) && !name.endsWith("_") && !name.endsWith("-")
    }
}

/** A declared self-test case. Execution still happens only on an explicit selfTest call. */
data class SelfTestSpec(
    val name: String,
    val command: String,
    val timeoutMs: Long? = null,
) {
    init {
        require(isValidName(name)) { "invalid self-test name: '$name'" }
        require(CommandSpec.isValidName(command)) { "invalid self-test command: '$command'" }
        require(timeoutMs == null || timeoutMs in 1L..60_000L) {
            "self-test timeout must be between 1 and 60000 ms"
        }
    }

    companion object {
        private val NAME_FORMAT = Regex("[a-z][a-z0-9_-]{0,63}")

        fun isValidName(name: String): Boolean =
            name.length <= 64 && NAME_FORMAT.matches(name) && !name.endsWith("_") && !name.endsWith("-")
    }
}

/**
 * Parsed and validated pack manifest.
 *
 * Instances are normally produced by `PackManifestParser`. Direct construction
 * defensively copies collections and enforces structural/cross-reference
 * invariants, but does not re-run the parser's schema-version, packLib,
 * unknown-field or JSON-Schema gates; hosts should parse stored manifests.
 */
class PackManifest(
    val schemaVersion: Int,
    val id: PackId,
    val name: String,
    val version: String,
    val versionCode: Long,
    val packLib: VersionRange,
    components: List<ComponentSpec>,
    commands: List<CommandSpec>,
    val description: String = "",
    val author: String = "",
    capabilities: Set<PackCapability> = emptySet(),
    val network: NetworkPolicy = NetworkPolicy.None,
    mcpTools: List<ToolSpec> = emptyList(),
    apiRoutes: List<RouteSpec> = emptyList(),
    selfTests: List<SelfTestSpec> = emptyList(),
) {
    val components: List<ComponentSpec> = immutableList(components)
    val commands: List<CommandSpec> = immutableList(commands)
    val capabilities: Set<PackCapability> = immutableSet(capabilities)
    val mcpTools: List<ToolSpec> = immutableList(mcpTools)
    val apiRoutes: List<RouteSpec> = immutableList(apiRoutes)
    val selfTests: List<SelfTestSpec> = immutableList(selfTests)

    init {
        require(schemaVersion >= 1) { "schemaVersion must be >= 1" }
        require(name.isNotBlank() && name.length <= 128) { "invalid manifest name" }
        require(version.isNotBlank() && version.length <= 64) { "invalid manifest version" }
        require(versionCode >= 1) { "versionCode must be >= 1" }
        require(description.length <= 1024) { "manifest description is too long" }
        require(author.length <= 128) { "manifest author is too long" }
        require(components.isNotEmpty()) { "manifest must declare at least one component" }
        require(components.map { it.id }.toSet().size == components.size) {
            "component ids must be unique"
        }
        require(commands.isNotEmpty()) { "manifest must declare at least one command" }
        require(commands.map { it.name }.toSet().size == commands.size) {
            "command names must be unique"
        }
        require(mcpTools.map { it.name }.toSet().size == mcpTools.size) {
            "tool names must be unique within a pack"
        }
        require(apiRoutes.map { it.name }.toSet().size == apiRoutes.size) {
            "route names must be unique within a pack"
        }
        require(selfTests.map { it.name }.toSet().size == selfTests.size) {
            "self-test names must be unique within a pack"
        }
        require(commands.all { componentById(it.componentId) != null }) {
            "every command must reference a declared component"
        }
        require(mcpTools.all { commandByName(it.command) != null }) {
            "every MCP tool must reference a declared command"
        }
        require(apiRoutes.all { commandByName(it.command) != null }) {
            "every API route must reference a declared command"
        }
        require(selfTests.all { commandByName(it.command) != null }) {
            "every self-test must reference a declared command"
        }
        require(mcpTools.isEmpty() || PackCapability.MCP_TOOLS in capabilities) {
            "MCP tools require capability MCP_TOOLS"
        }
        require(apiRoutes.isEmpty() || PackCapability.API_ROUTES in capabilities) {
            "API routes require capability API_ROUTES"
        }
        require(selfTests.isEmpty() || PackCapability.SELF_TEST in capabilities) {
            "self-tests require capability SELF_TEST"
        }
        require(commands.none { it.sourceExported } || PackCapability.SOURCE_COMMANDS in capabilities) {
            "source-exported commands require capability SOURCE_COMMANDS"
        }
    }

    fun componentById(id: String): ComponentSpec? = components.firstOrNull { it.id == id }

    fun commandByName(name: String): CommandSpec? = commands.firstOrNull { it.name == name }

    /** Permission declaration derived from the manifest; grants are checked separately. */
    val permissions: PackPermissionSet
        get() = PackPermissionSet(
            packId = id,
            capabilities = capabilities,
            network = network,
            sourceCommands = commands.filter { it.sourceExported }.map { it.name }.toSet(),
            mcpTools = mcpTools.map { it.name }.toSet(),
            writeToolNames = mcpTools.filter { it.isWrite }.map { it.name }.toSet(),
            apiRoutes = apiRoutes.map { it.name }.toSet(),
        )

    fun copy(
        schemaVersion: Int = this.schemaVersion,
        id: PackId = this.id,
        name: String = this.name,
        version: String = this.version,
        versionCode: Long = this.versionCode,
        packLib: VersionRange = this.packLib,
        components: List<ComponentSpec> = this.components,
        commands: List<CommandSpec> = this.commands,
        description: String = this.description,
        author: String = this.author,
        capabilities: Set<PackCapability> = this.capabilities,
        network: NetworkPolicy = this.network,
        mcpTools: List<ToolSpec> = this.mcpTools,
        apiRoutes: List<RouteSpec> = this.apiRoutes,
        selfTests: List<SelfTestSpec> = this.selfTests,
    ): PackManifest = PackManifest(
        schemaVersion = schemaVersion,
        id = id,
        name = name,
        version = version,
        versionCode = versionCode,
        packLib = packLib,
        components = components,
        commands = commands,
        description = description,
        author = author,
        capabilities = capabilities,
        network = network,
        mcpTools = mcpTools,
        apiRoutes = apiRoutes,
        selfTests = selfTests,
    )

    override fun equals(other: Any?): Boolean =
        other is PackManifest &&
            schemaVersion == other.schemaVersion &&
            id == other.id &&
            name == other.name &&
            version == other.version &&
            versionCode == other.versionCode &&
            packLib == other.packLib &&
            components == other.components &&
            commands == other.commands &&
            description == other.description &&
            author == other.author &&
            capabilities == other.capabilities &&
            network == other.network &&
            mcpTools == other.mcpTools &&
            apiRoutes == other.apiRoutes &&
            selfTests == other.selfTests

    override fun hashCode(): Int {
        var result = schemaVersion
        result = 31 * result + id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + version.hashCode()
        result = 31 * result + versionCode.hashCode()
        result = 31 * result + packLib.hashCode()
        result = 31 * result + components.hashCode()
        result = 31 * result + commands.hashCode()
        result = 31 * result + description.hashCode()
        result = 31 * result + author.hashCode()
        result = 31 * result + capabilities.hashCode()
        result = 31 * result + network.hashCode()
        result = 31 * result + mcpTools.hashCode()
        result = 31 * result + apiRoutes.hashCode()
        result = 31 * result + selfTests.hashCode()
        return result
    }

    override fun toString(): String =
        "PackManifest(id=${id.value}, version=$version, versionCode=$versionCode)"

    companion object {
        const val SCHEMA_VERSION: Int = 1
    }
}

/** Fixed on-disk/import format names shared by manager, tests and docs. */
object PackArchiveSpec {
    /** Entry name inside a ZIP pack. */
    const val ZIP_MANIFEST_ENTRY: String = "pack.json"

    /** Single-file JS pack header delimiters. */
    const val USERSCRIPT_HEADER_START: String = "// ==Pack=="
    const val USERSCRIPT_HEADER_END: String = "// ==/Pack=="

    /**
     * A single-file pack is materialized by the importer as this one JS entry.
     * It cannot declare DEX/native components.
     */
    const val SINGLE_FILE_JS_ENTRY: String = "main.js"

    const val MAX_MANIFEST_CHARS: Int = 1_048_576
    const val MAX_USERSCRIPT_HEADER_LINES: Int = 512
    const val MAX_USERSCRIPT_HEADER_LINE_CHARS: Int = 4_096
}