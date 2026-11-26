package io.legado.pack.manifest

import io.legado.pack.api.CommandSpec
import io.legado.pack.api.ComponentSpec
import io.legado.pack.api.DomainPattern
import io.legado.pack.api.JsonValue
import io.legado.pack.api.NetworkPolicy
import io.legado.pack.api.PackArchiveSpec
import io.legado.pack.api.PackCapability
import io.legado.pack.api.PackDiagnostic
import io.legado.pack.api.PackErrorCode
import io.legado.pack.api.PackHttpMethod
import io.legado.pack.api.PackId
import io.legado.pack.api.PackLibVersion
import io.legado.pack.api.PackManifest
import io.legado.pack.api.PackToolSchema
import io.legado.pack.api.NativeAbi
import io.legado.pack.api.NativeLoadMode
import io.legado.pack.api.RelativePath
import io.legado.pack.api.RouteSpec
import io.legado.pack.api.SelfTestSpec
import io.legado.pack.api.ToolSpec
import io.legado.pack.api.VersionRange
import io.legado.pack.api.emptyObjectSchema
import java.util.Locale

data class ManifestValidationContext(
    val frameworkPackLibVersion: PackLibVersion = PackLibVersion.CURRENT,
    val supportedSchemaVersion: Int = PackManifest.SCHEMA_VERSION,
    val reservedToolNames: Set<String> = emptySet(),
    val maxContentChars: Int = PackArchiveSpec.MAX_MANIFEST_CHARS,
) {
    init {
        require(supportedSchemaVersion >= 1) { "supportedSchemaVersion must be >= 1" }
        require(maxContentChars > 0) { "maxContentChars must be positive" }
    }
}

sealed interface ManifestParseResult {
    data class Valid(
        val manifest: PackManifest,
        val warnings: List<PackDiagnostic> = emptyList(),
        /** Non-null only for a single-file JS pack; the importer stores this as main.js. */
        val singleFileBody: String? = null,
        /** 1-based line where [singleFileBody] starts in the original source. */
        val singleFileBodyStartLine: Int? = null,
    ) : ManifestParseResult

    data class Invalid(
        val diagnostics: List<PackDiagnostic>,
    ) : ManifestParseResult {
        init {
            require(diagnostics.isNotEmpty()) { "Invalid result must contain diagnostics" }
        }
    }
}

/**
 * Strict manifest parser for both pack formats:
 *
 * * ZIP packs: one JSON object (entry name [PackArchiveSpec.ZIP_MANIFEST_ENTRY]).
 * * Single-file packs: a `// ==Pack==` / `// ==/Pack==` header followed by JS.
 *
 * Parsing is side-effect free. It never evaluates JS, reads a ZIP, loads a
 * class or touches the filesystem. Duplicate fields, unknown fields, invalid
 * pack/path/name shapes, duplicate tools/components/commands/routes/self-tests,
 * malformed tool schemas, an incompatible packLib gate and every declared
 * feature without its capability are typed [ManifestParseResult.Invalid]
 * results.
 */
class PackManifestParser(
    private val context: ManifestValidationContext = ManifestValidationContext(),
) {

    fun parseJson(text: String): ManifestParseResult {
        val json = text.removePrefix("\uFEFF")
        if (json.length > context.maxContentChars) {
            return ManifestParseResult.Invalid(
                listOf(
                    diagnostic(
                        PackErrorCode.IMPORT_LIMIT_EXCEEDED,
                        "$",
                        "manifest exceeds ${context.maxContentChars} characters",
                    ),
                ),
            )
        }
        val root = try {
            StrictJsonParser().parse(json)
        } catch (e: JsonStructureException) {
            return ManifestParseResult.Invalid(
                listOf(diagnostic(PackErrorCode.MANIFEST_INVALID, e.path, e.message ?: "invalid JSON")),
            )
        }
        if (root !is JsonValue.Obj) {
            return ManifestParseResult.Invalid(
                listOf(diagnostic(PackErrorCode.MANIFEST_INVALID, "$", "manifest root must be a JSON object")),
            )
        }
        return validateRoot(root)
    }

    /**
     * Parses a single-file JS pack header. The importer is expected to store
     * the body as [PackArchiveSpec.SINGLE_FILE_JS_ENTRY]; DEX/native components
     * are not representable in this format.
     */
    fun parseUserScript(source: String): ManifestParseResult {
        val lines = source.split('\n')
        var start = 0
        while (start < lines.size &&
            lines[start].trim().removePrefix("\uFEFF").isBlank()
        ) {
            start++
        }
        if (start >= lines.size) {
            return ManifestParseResult.Invalid(
                listOf(diagnostic(PackErrorCode.MANIFEST_INVALID, "header.line1", "missing pack header")),
            )
        }
        val firstLine = lines[start].trim().removePrefix("\uFEFF").trim()
        if (firstLine != PackArchiveSpec.USERSCRIPT_HEADER_START) {
            return ManifestParseResult.Invalid(
                listOf(
                    diagnostic(
                        PackErrorCode.MANIFEST_INVALID,
                        "header.line${start + 1}",
                        "expected '${PackArchiveSpec.USERSCRIPT_HEADER_START}' as the first non-blank line",
                    ),
                ),
            )
        }

        val errors = mutableListOf<PackDiagnostic>()
        var id: String? = null
        var name: String? = null
        var version: String? = null
        var packLib: String? = null
        var description: String? = null
        var author: String? = null
        var versionCodeSeen = false
        var versionCode: Long? = null
        var networkSeen = false
        var network: JsonValue.Obj? = null
        val capabilities = mutableListOf<String>()
        val commands = mutableListOf<JsonValue.Obj>()
        val tools = mutableListOf<JsonValue.Obj>()
        val routes = mutableListOf<JsonValue.Obj>()
        val selfTests = mutableListOf<JsonValue.Obj>()

        fun single(
            existing: String?,
            key: String,
            value: String,
            lineNumber: Int,
            allowBlank: Boolean = false,
        ): String? {
            if (existing != null) {
                errors += headerDiagnostic(lineNumber, "duplicate @$key")
                return existing
            }
            if (!allowBlank && value.isBlank()) {
                errors += headerDiagnostic(lineNumber, "@$key value must not be blank")
                return null
            }
            return value
        }

        var cursor = start + 1
        var scannedHeaderLines = 0
        var scannedHeaderChars = 0
        var bodyStart = -1
        while (cursor < lines.size) {
            if (scannedHeaderLines >= PackArchiveSpec.MAX_USERSCRIPT_HEADER_LINES) {
                errors += headerDiagnostic(
                    cursor + 1,
                    "header exceeds ${PackArchiveSpec.MAX_USERSCRIPT_HEADER_LINES} lines",
                )
                break
            }
            val line = lines[cursor].trimEnd('\r')
            val trimmed = line.trim()
            scannedHeaderChars += line.length + 1
            if (scannedHeaderChars > context.maxContentChars) {
                errors += headerDiagnostic(
                    cursor + 1,
                    "header exceeds ${context.maxContentChars} characters",
                )
                break
            }
            val lineNumber = cursor + 1
            if (trimmed == PackArchiveSpec.USERSCRIPT_HEADER_END) {
                bodyStart = cursor + 1
                break
            }
            if (trimmed.isBlank()) {
                cursor++
                scannedHeaderLines++
                continue
            }
            if (trimmed.length > PackArchiveSpec.MAX_USERSCRIPT_HEADER_LINE_CHARS) {
                errors += headerDiagnostic(
                    lineNumber,
                    "header line exceeds ${PackArchiveSpec.MAX_USERSCRIPT_HEADER_LINE_CHARS} characters",
                )
                cursor++
                scannedHeaderLines++
                continue
            }
            val match = HEADER_LINE.matchEntire(trimmed)
            if (match == null) {
                errors += headerDiagnostic(lineNumber, "invalid header line; expected '// @key value'")
                cursor++
                scannedHeaderLines++
                continue
            }
            val key = match.groupValues[1]
            val value = match.groupValues[2].trim()
            when (key) {
                "id" -> id = single(id, key, value, lineNumber)
                "name" -> name = single(name, key, value, lineNumber)
                "version" -> version = single(version, key, value, lineNumber)
                "packLib" -> packLib = single(packLib, key, value, lineNumber)
                "description" -> description = single(description, key, value, lineNumber, allowBlank = true)
                "author" -> author = single(author, key, value, lineNumber, allowBlank = true)
                "capability" -> {
                    if (value.isBlank()) {
                        errors += headerDiagnostic(lineNumber, "@capability value must not be blank")
                    } else {
                        capabilities += value
                    }
                }

                "versionCode" -> {
                    if (versionCodeSeen) {
                        errors += headerDiagnostic(lineNumber, "duplicate @versionCode")
                    } else {
                        versionCodeSeen = true
                        val parsed = value.toLongOrNull()
                        if (parsed == null) {
                            errors += headerDiagnostic(lineNumber, "@versionCode must be an integer")
                        } else {
                            versionCode = parsed
                        }
                    }
                }

                "network" -> {
                    if (networkSeen) {
                        errors += headerDiagnostic(lineNumber, "duplicate @network")
                    } else {
                        networkSeen = true
                        network = parseHeaderNetwork(value, lineNumber, errors)
                    }
                }

                "command" -> {
                    val parsed = parseHeaderCommand(value, lineNumber, errors)
                    if (parsed != null) commands += parsed
                }

                "mcpTool" -> {
                    val parsed = parseHeaderTool(value, lineNumber, errors)
                    if (parsed != null) tools += parsed
                }

                "apiRoute" -> {
                    val parsed = parseHeaderRoute(value, lineNumber, errors)
                    if (parsed != null) routes += parsed
                }

                "selfTest" -> {
                    val parsed = parseHeaderSelfTest(value, lineNumber, errors)
                    if (parsed != null) selfTests += parsed
                }

                else -> errors += headerDiagnostic(lineNumber, "unknown header key '@$key'")
            }
            cursor++
            scannedHeaderLines++
        }

        if (bodyStart < 0) {
            if (errors.none { it.message.contains("header exceeds") }) {
                errors += headerDiagnostic(cursor + 1, "missing '${PackArchiveSpec.USERSCRIPT_HEADER_END}'")
            }
            return ManifestParseResult.Invalid(errors.toList())
        }
        val body = lines.drop(bodyStart).joinToString("\n")
        if (body.isBlank()) {
            errors += headerDiagnostic(bodyStart + 1, "single-file JS pack body is empty")
        }
        if (errors.isNotEmpty()) {
            return ManifestParseResult.Invalid(errors.toList())
        }

        val root = LinkedHashMap<String, JsonValue>()
        root["schemaVersion"] = JsonValue.Num("1")
        id?.let { root["id"] = JsonValue.Str(it) }
        name?.let { root["name"] = JsonValue.Str(it) }
        version?.let { root["version"] = JsonValue.Str(it) }
        versionCode?.let { root["versionCode"] = JsonValue.Num(it.toString()) }
        packLib?.let { root["packLib"] = JsonValue.Str(it) }
        description?.let { root["description"] = JsonValue.Str(it) }
        author?.let { root["author"] = JsonValue.Str(it) }
        root["capabilities"] = JsonValue.Arr(capabilities.map { JsonValue.Str(it) })
        network?.let { root["network"] = it }
        root["components"] = JsonValue.Arr(
            listOf(
                JsonValue.Obj(
                    linkedMapOf(
                        "id" to JsonValue.Str("main"),
                        "type" to JsonValue.Str("js"),
                        "entry" to JsonValue.Str(PackArchiveSpec.SINGLE_FILE_JS_ENTRY),
                    ),
                ),
            ),
        )
        if (commands.isNotEmpty()) root["commands"] = JsonValue.Arr(commands)
        if (tools.isNotEmpty()) root["mcpTools"] = JsonValue.Arr(tools)
        if (routes.isNotEmpty()) root["apiRoutes"] = JsonValue.Arr(routes)
        if (selfTests.isNotEmpty()) root["selfTests"] = JsonValue.Arr(selfTests)

        return when (val validated = validateRoot(JsonValue.Obj(root))) {
            is ManifestParseResult.Valid -> validated.copy(
                singleFileBody = body,
                singleFileBodyStartLine = bodyStart + 1,
            )

            is ManifestParseResult.Invalid -> validated
        }
    }

    private fun validateRoot(root: JsonValue.Obj): ManifestParseResult {
        val errors = Errors()
        val warnings = mutableListOf<PackDiagnostic>()
        checkAllowed(root, TOP_LEVEL_FIELDS, "$", errors)

        val schemaVersion = readInt(root, "schemaVersion", "$", errors, required = true)
        if (schemaVersion != null) {
            if (schemaVersion > context.supportedSchemaVersion) {
                errors.add(
                    PackErrorCode.MANIFEST_SCHEMA_UNSUPPORTED,
                    "$.schemaVersion",
                    "manifest schema $schemaVersion is newer than supported ${context.supportedSchemaVersion}",
                )
                return ManifestParseResult.Invalid(errors.list)
            }
            if (schemaVersion < 1) {
                errors.invalid("$.schemaVersion", "schemaVersion must be >= 1")
            }
        }

        val idRaw = readString(root, "id", "$", errors, required = true, maxLength = 64)
        val id = idRaw?.let { raw ->
            PackId.parse(raw) ?: run {
                errors.invalid("$.id", "invalid pack id '$raw'")
                null
            }
        }
        val name = readString(root, "name", "$", errors, required = true, maxLength = 128)
        if (name != null && name.isBlank()) errors.invalid("$.name", "name must not be blank")
        val version = readString(root, "version", "$", errors, required = true, maxLength = 64)
        if (version != null && !PACK_VERSION.matches(version)) {
            errors.invalid("$.version", "version must contain only letters, digits and . _ + -")
        }
        val versionCode = readLong(root, "versionCode", "$", errors, required = true)
        if (versionCode != null && versionCode < 1L) {
            errors.invalid("$.versionCode", "versionCode must be >= 1")
        }
        val description = readString(root, "description", "$", errors, maxLength = 1024, allowBlank = true) ?: ""
        val author = readString(root, "author", "$", errors, maxLength = 128, allowBlank = true) ?: ""

        val packLibRaw = readString(root, "packLib", "$", errors, required = true, maxLength = 256)
        val packLibRange = packLibRaw?.let { raw ->
            VersionRange.parse(raw) ?: run {
                errors.invalid("$.packLib", "invalid packLib range '$raw'")
                null
            }
        }
        if (packLibRange != null && !packLibRange.contains(context.frameworkPackLibVersion)) {
            errors.add(
                PackErrorCode.PACKLIB_INCOMPATIBLE,
                "$.packLib",
                "pack requires packLib '$packLibRange', framework provides ${context.frameworkPackLibVersion}",
            )
        }

        val capabilities = parseCapabilities(root.entries["capabilities"], errors)
        val network = parseNetwork(root.entries["network"], errors)
        if (network is NetworkPolicy.Open) {
            warnings += diagnostic(
                PackErrorCode.NETWORK_OPEN_REQUIRES_CONSENT,
                "$.network",
                "pack requests open network access; the import UI must obtain explicit confirmation",
            )
        }
        val components = parseComponents(root.entries["components"], errors)
        val parsedCommands = parseCommands(root.entries["commands"], errors)
        val parsedTools = parseTools(root.entries["mcpTools"], errors)
        val parsedRoutes = parseRoutes(root.entries["apiRoutes"], errors)
        val parsedSelfTests = parseSelfTests(root.entries["selfTests"], errors)

        val componentIds = components.map { it.id }.toSet()
        val commandNames = parsedCommands.map { it.spec.name }.toSet()
        parsedCommands.forEach { parsed ->
            if (parsed.spec.componentId !in componentIds) {
                errors.invalid(
                    "${parsed.path}.component",
                    "command '${parsed.spec.name}' references missing component '${parsed.spec.componentId}'",
                )
            }
            if (parsed.spec.sourceExported && PackCapability.SOURCE_COMMANDS !in capabilities) {
                errors.invalid(
                    "${parsed.path}.source",
                    "source-exported command '${parsed.spec.name}' requires capability SOURCE_COMMANDS",
                )
            }
        }
        parsedTools.forEach { parsed ->
            if (parsed.spec.command !in commandNames) {
                errors.invalid(
                    "${parsed.path}.command",
                    "tool '${parsed.spec.name}' references missing command '${parsed.spec.command}'",
                )
            }
        }
        parsedRoutes.forEach { parsed ->
            if (parsed.spec.command !in commandNames) {
                errors.invalid(
                    "${parsed.path}.command",
                    "route '${parsed.spec.name}' references missing command '${parsed.spec.command}'",
                )
            }
        }
        parsedSelfTests.forEach { parsed ->
            if (parsed.spec.command !in commandNames) {
                errors.invalid(
                    "${parsed.path}.command",
                    "self-test '${parsed.spec.name}' references missing command '${parsed.spec.command}'",
                )
            }
        }
        if (parsedTools.isNotEmpty() && PackCapability.MCP_TOOLS !in capabilities) {
            errors.invalid("$.capabilities", "MCP tools require capability MCP_TOOLS")
        }
        if (parsedRoutes.isNotEmpty() && PackCapability.API_ROUTES !in capabilities) {
            errors.invalid("$.capabilities", "API routes require capability API_ROUTES")
        }
        if (parsedSelfTests.isNotEmpty() && PackCapability.SELF_TEST !in capabilities) {
            errors.invalid("$.capabilities", "self-tests require capability SELF_TEST")
        }

        if (errors.hasErrors ||
            schemaVersion == null ||
            id == null ||
            name == null ||
            version == null ||
            versionCode == null ||
            packLibRange == null
        ) {
            val diagnostics = errors.list.ifEmpty {
                listOf(diagnostic(PackErrorCode.INTERNAL, "$", "manifest could not be validated"))
            }
            return ManifestParseResult.Invalid(diagnostics)
        }

        val manifest = PackManifest(
            schemaVersion = schemaVersion,
            id = id,
            name = name,
            version = version,
            versionCode = versionCode,
            packLib = packLibRange,
            components = components,
            commands = parsedCommands.map { it.spec },
            description = description,
            author = author,
            capabilities = capabilities,
            network = network,
            mcpTools = parsedTools.map { it.spec },
            apiRoutes = parsedRoutes.map { it.spec },
            selfTests = parsedSelfTests.map { it.spec },
        )
        return ManifestParseResult.Valid(manifest, warnings)
    }

    private fun parseCapabilities(value: JsonValue?, errors: Errors): Set<PackCapability> {
        if (value == null) return emptySet()
        val array = value as? JsonValue.Arr ?: run {
            errors.invalid("$.capabilities", "capabilities must be an array")
            return emptySet()
        }
        val result = LinkedHashSet<PackCapability>()
        array.items.forEachIndexed { index, item ->
            val raw = (item as? JsonValue.Str)?.value
            if (raw == null) {
                errors.invalid("$.capabilities[$index]", "capability must be a string")
                return@forEachIndexed
            }
            val capability = PackCapability.parse(raw)
            if (capability == null) {
                errors.invalid("$.capabilities[$index]", "unknown capability '$raw'")
            } else if (!result.add(capability)) {
                errors.invalid("$.capabilities[$index]", "duplicate capability '$raw'")
            }
        }
        return result
    }

    private fun parseNetwork(value: JsonValue?, errors: Errors): NetworkPolicy {
        if (value == null) return NetworkPolicy.None
        val obj = value as? JsonValue.Obj ?: run {
            errors.invalid("$.network", "network must be an object")
            return NetworkPolicy.None
        }
        checkAllowed(obj, NETWORK_FIELDS, "$.network", errors)
        val mode = readString(obj, "mode", "$.network", errors, required = true, maxLength = 16)
        return when (mode?.lowercase(Locale.ROOT)) {
            null -> NetworkPolicy.None
            "none" -> {
                if (obj.entries.containsKey("patterns")) {
                    errors.invalid("$.network.patterns", "patterns are not allowed when mode is none")
                }
                if (obj.entries.containsKey("reason")) {
                    errors.invalid("$.network.reason", "reason is not allowed when mode is none")
                }
                NetworkPolicy.None
            }

            "patterns" -> {
                if (obj.entries.containsKey("reason")) {
                    errors.invalid("$.network.reason", "reason is only allowed for open mode")
                }
                val patternsValue = obj.entries["patterns"]
                if (patternsValue == null) {
                    errors.invalid("$.network.patterns", "patterns mode requires a patterns array")
                    return NetworkPolicy.None
                }
                val array = patternsValue as? JsonValue.Arr ?: run {
                    errors.invalid("$.network.patterns", "patterns must be an array")
                    return NetworkPolicy.None
                }
                if (array.items.isEmpty()) {
                    errors.invalid("$.network.patterns", "patterns must not be empty")
                }
                val result = mutableListOf<DomainPattern>()
                val seen = mutableSetOf<String>()
                array.items.forEachIndexed { index, item ->
                    val raw = (item as? JsonValue.Str)?.value
                    if (raw == null) {
                        errors.invalid("$.network.patterns[$index]", "pattern must be a string")
                    } else {
                        val pattern = DomainPattern.parse(raw)
                        if (pattern == null) {
                            errors.invalid("$.network.patterns[$index]", "invalid domain pattern '$raw'")
                        } else if (!seen.add(pattern.value)) {
                            errors.invalid("$.network.patterns[$index]", "duplicate domain pattern '${pattern.value}'")
                        } else {
                            result += pattern
                        }
                    }
                }
                if (result.isEmpty()) NetworkPolicy.None else NetworkPolicy.Allowlist(result)
            }

            "open" -> {
                if (obj.entries.containsKey("patterns")) {
                    errors.invalid("$.network.patterns", "patterns are not allowed when mode is open")
                }
                val reason = if (obj.entries.containsKey("reason")) {
                    readString(obj, "reason", "$.network", errors, maxLength = 512, allowBlank = true) ?: ""
                } else {
                    ""
                }
                NetworkPolicy.Open(reason)
            }

            else -> {
                errors.invalid("$.network.mode", "network mode must be none, patterns or open")
                NetworkPolicy.None
            }
        }
    }

    private fun parseComponents(value: JsonValue?, errors: Errors): List<ComponentSpec> {
        if (value == null) {
            errors.invalid("$.components", "missing required field")
            return emptyList()
        }
        val array = value as? JsonValue.Arr ?: run {
            errors.invalid("$.components", "components must be an array")
            return emptyList()
        }
        if (array.items.isEmpty()) {
            errors.invalid("$.components", "components must not be empty")
        }
        val result = mutableListOf<ComponentSpec>()
        val seen = mutableSetOf<String>()
        array.items.forEachIndexed { index, item ->
            val path = "$.components[$index]"
            val component = parseComponent(item, path, errors) ?: return@forEachIndexed
            if (!seen.add(component.id)) {
                errors.invalid("$path.id", "duplicate component id '${component.id}'")
            } else {
                result += component
            }
        }
        return result
    }

    private fun parseComponent(
        value: JsonValue,
        path: String,
        errors: Errors,
    ): ComponentSpec? {
        val obj = value as? JsonValue.Obj ?: run {
            errors.invalid(path, "component must be an object")
            return null
        }
        val typeRaw = readString(obj, "type", path, errors, required = true, maxLength = 16) ?: return null
        val id = readString(obj, "id", path, errors, required = true, maxLength = 64) ?: return null
        if (!ComponentSpec.isValidId(id)) {
            errors.invalid("$path.id", "invalid component id '$id'")
            return null
        }
        return when (typeRaw.lowercase(Locale.ROOT)) {
            "js" -> {
                checkAllowed(obj, setOf("id", "type", "entry"), path, errors)
                val entry = readRelativePath(obj, "entry", path, errors) ?: return null
                ComponentSpec.Js(id = id, entry = entry)
            }

            "dex" -> {
                checkAllowed(obj, setOf("id", "type", "path", "class"), path, errors)
                val dexPath = readRelativePath(obj, "path", path, errors) ?: return null
                val className = readString(obj, "class", path, errors, required = true, maxLength = 512)
                    ?: return null
                if (!ComponentSpec.isValidClassName(className)) {
                    errors.invalid("$path.class", "invalid DEX class name '$className'")
                    return null
                }
                ComponentSpec.Dex(id = id, path = dexPath, className = className)
            }

            "native" -> {
                checkAllowed(obj, setOf("id", "type", "path", "abi", "mode", "abiVersion"), path, errors)
                val nativePath = readRelativePath(obj, "path", path, errors) ?: return null
                val abiRaw = readString(obj, "abi", path, errors, required = true, maxLength = 16)
                    ?: return null
                val abi = NativeAbi.parse(abiRaw) ?: run {
                    errors.invalid("$path.abi", "unsupported native ABI '$abiRaw'")
                    return null
                }
                var requestedMode: NativeLoadMode? = null
                if (obj.entries.containsKey("mode")) {
                    val modeRaw = readString(obj, "mode", path, errors, required = true, maxLength = 16)
                        ?: return null
                    requestedMode = NativeLoadMode.parse(modeRaw) ?: run {
                        errors.invalid("$path.mode", "native mode must be file or memfd")
                        return null
                    }
                }
                val abiVersion = if (obj.entries.containsKey("abiVersion")) {
                    readInt(obj, "abiVersion", path, errors) ?: return null
                } else {
                    1
                }
                if (abiVersion < 1) {
                    errors.invalid("$path.abiVersion", "abiVersion must be >= 1")
                    return null
                }
                ComponentSpec.Native(
                    id = id,
                    path = nativePath,
                    abi = abi,
                    requestedMode = requestedMode,
                    abiVersion = abiVersion,
                )
            }

            else -> {
                errors.invalid("$path.type", "unknown component type '$typeRaw'")
                null
            }
        }
    }

    private fun parseCommands(value: JsonValue?, errors: Errors): List<ParsedCommand> {
        if (value == null) {
            errors.invalid("$.commands", "missing required field")
            return emptyList()
        }
        val array = value as? JsonValue.Arr ?: run {
            errors.invalid("$.commands", "commands must be an array")
            return emptyList()
        }
        if (array.items.isEmpty()) {
            errors.invalid("$.commands", "commands must not be empty")
        }
        val result = mutableListOf<ParsedCommand>()
        val seen = mutableSetOf<String>()
        array.items.forEachIndexed { index, item ->
            val path = "$.commands[$index]"
            val parsed = parseCommand(item, path, errors) ?: return@forEachIndexed
            if (!seen.add(parsed.spec.name)) {
                errors.invalid("$path.name", "duplicate command name '${parsed.spec.name}'")
            } else {
                result += parsed
            }
        }
        return result
    }

    private fun parseCommand(
        value: JsonValue,
        path: String,
        errors: Errors,
    ): ParsedCommand? {
        val obj = value as? JsonValue.Obj ?: run {
            errors.invalid(path, "command must be an object")
            return null
        }
        checkAllowed(obj, COMMAND_FIELDS, path, errors)
        val name = readString(obj, "name", path, errors, required = true, maxLength = 64) ?: return null
        if (!CommandSpec.isValidName(name)) {
            errors.invalid("$path.name", "invalid command name '$name'")
            return null
        }
        val componentId = readString(obj, "component", path, errors, required = true, maxLength = 64)
            ?: return null
        if (!ComponentSpec.isValidId(componentId)) {
            errors.invalid("$path.component", "invalid component id '$componentId'")
            return null
        }
        val description = readString(obj, "description", path, errors, maxLength = 512, allowBlank = true) ?: ""
        val source = readBoolean(obj, "source", path, errors, default = false)
        return ParsedCommand(
            CommandSpec(
                name = name,
                componentId = componentId,
                description = description,
                sourceExported = source,
            ),
            path,
        )
    }

    private fun parseTools(value: JsonValue?, errors: Errors): List<ParsedTool> {
        if (value == null) return emptyList()
        val array = value as? JsonValue.Arr ?: run {
            errors.invalid("$.mcpTools", "mcpTools must be an array")
            return emptyList()
        }
        val result = mutableListOf<ParsedTool>()
        val seen = mutableSetOf<String>()
        array.items.forEachIndexed { index, item ->
            val path = "$.mcpTools[$index]"
            val parsed = parseTool(item, path, errors) ?: return@forEachIndexed
            if (!seen.add(parsed.spec.name)) {
                errors.invalid("$path.name", "duplicate tool name '${parsed.spec.name}'")
            } else {
                result += parsed
            }
        }
        return result
    }

    private fun parseTool(
        value: JsonValue,
        path: String,
        errors: Errors,
    ): ParsedTool? {
        val obj = value as? JsonValue.Obj ?: run {
            errors.invalid(path, "tool must be an object")
            return null
        }
        checkAllowed(obj, TOOL_FIELDS, path, errors)
        val name = readString(obj, "name", path, errors, required = true, maxLength = 64) ?: return null
        if (!ToolSpec.isValidName(name)) {
            errors.invalid("$path.name", "invalid tool name '$name'")
            return null
        }
        if (name in context.reservedToolNames) {
            errors.add(
                PackErrorCode.TOOL_CONFLICT,
                "$path.name",
                "tool name '$name' is reserved by the host",
            )
        }
        val command = readString(obj, "command", path, errors, required = true, maxLength = 64)
            ?: return null
        if (!CommandSpec.isValidName(command)) {
            errors.invalid("$path.command", "invalid command name '$command'")
            return null
        }
        val description = readString(obj, "description", path, errors, maxLength = 1024, allowBlank = true)
            ?: ""
        val schema = if (obj.entries.containsKey("inputSchema")) {
            val raw = obj.entries["inputSchema"]
            val schemaObj = raw as? JsonValue.Obj ?: run {
                errors.invalid("$path.inputSchema", "inputSchema must be an object")
                return null
            }
            val normalized = PackToolSchema.normalizeObjectSchema(schemaObj)
            val schemaDiagnostics = PackToolSchema.validate(normalized, "$path.inputSchema")
            if (schemaDiagnostics.isNotEmpty()) {
                errors.list.addAll(schemaDiagnostics)
                return null
            }
            normalized
        } else {
            emptyObjectSchema()
        }
        val write = readBoolean(obj, "write", path, errors, default = false)
        return ParsedTool(
            ToolSpec(
                name = name,
                command = command,
                description = description,
                inputSchema = schema,
                isWrite = write,
            ),
            path,
        )
    }

    private fun parseRoutes(value: JsonValue?, errors: Errors): List<ParsedRoute> {
        if (value == null) return emptyList()
        val array = value as? JsonValue.Arr ?: run {
            errors.invalid("$.apiRoutes", "apiRoutes must be an array")
            return emptyList()
        }
        val result = mutableListOf<ParsedRoute>()
        val seen = mutableSetOf<String>()
        array.items.forEachIndexed { index, item ->
            val path = "$.apiRoutes[$index]"
            val parsed = parseRoute(item, path, errors) ?: return@forEachIndexed
            if (!seen.add(parsed.spec.name)) {
                errors.invalid("$path.name", "duplicate route name '${parsed.spec.name}'")
            } else {
                result += parsed
            }
        }
        return result
    }

    private fun parseRoute(
        value: JsonValue,
        path: String,
        errors: Errors,
    ): ParsedRoute? {
        val obj = value as? JsonValue.Obj ?: run {
            errors.invalid(path, "route must be an object")
            return null
        }
        checkAllowed(obj, ROUTE_FIELDS, path, errors)
        val name = readString(obj, "name", path, errors, required = true, maxLength = 64) ?: return null
        if (!RouteSpec.isValidName(name)) {
            errors.invalid("$path.name", "invalid route name '$name'")
            return null
        }
        val methodRaw = readString(obj, "method", path, errors, required = true, maxLength = 8)
            ?: return null
        val method = PackHttpMethod.parse(methodRaw) ?: run {
            errors.invalid("$path.method", "route method must be GET or POST")
            return null
        }
        val command = readString(obj, "command", path, errors, required = true, maxLength = 64)
            ?: return null
        if (!CommandSpec.isValidName(command)) {
            errors.invalid("$path.command", "invalid command name '$command'")
            return null
        }
        val write = readBoolean(obj, "write", path, errors, default = false)
        if (write && method != PackHttpMethod.POST) {
            errors.invalid("$path.write", "a write route must use POST")
            return null
        }
        return ParsedRoute(RouteSpec(name, method, command, write), path)
    }

    private fun parseSelfTests(value: JsonValue?, errors: Errors): List<ParsedSelfTest> {
        if (value == null) return emptyList()
        val array = value as? JsonValue.Arr ?: run {
            errors.invalid("$.selfTests", "selfTests must be an array")
            return emptyList()
        }
        val result = mutableListOf<ParsedSelfTest>()
        val seen = mutableSetOf<String>()
        array.items.forEachIndexed { index, item ->
            val path = "$.selfTests[$index]"
            val parsed = parseSelfTest(item, path, errors) ?: return@forEachIndexed
            if (!seen.add(parsed.spec.name)) {
                errors.invalid("$path.name", "duplicate self-test name '${parsed.spec.name}'")
            } else {
                result += parsed
            }
        }
        return result
    }

    private fun parseSelfTest(
        value: JsonValue,
        path: String,
        errors: Errors,
    ): ParsedSelfTest? {
        val obj = value as? JsonValue.Obj ?: run {
            errors.invalid(path, "self-test must be an object")
            return null
        }
        checkAllowed(obj, SELF_TEST_FIELDS, path, errors)
        val name = readString(obj, "name", path, errors, required = true, maxLength = 64) ?: return null
        if (!SelfTestSpec.isValidName(name)) {
            errors.invalid("$path.name", "invalid self-test name '$name'")
            return null
        }
        val command = readString(obj, "command", path, errors, required = true, maxLength = 64)
            ?: return null
        if (!CommandSpec.isValidName(command)) {
            errors.invalid("$path.command", "invalid command name '$command'")
            return null
        }
        val timeout = if (obj.entries.containsKey("timeoutMs")) {
            readLong(obj, "timeoutMs", path, errors) ?: return null
        } else {
            null
        }
        if (timeout != null && timeout !in 1L..60_000L) {
            errors.invalid("$path.timeoutMs", "timeoutMs must be between 1 and 60000")
            return null
        }
        return ParsedSelfTest(SelfTestSpec(name, command, timeout), path)
    }

    private fun parseHeaderNetwork(
        value: String,
        lineNumber: Int,
        errors: MutableList<PackDiagnostic>,
    ): JsonValue.Obj? {
        return when {
            value == "none" -> JsonValue.Obj(mapOf("mode" to JsonValue.Str("none")))
            value.startsWith("patterns:") -> {
                val rest = value.removePrefix("patterns:").trim()
                val patterns = rest.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                if (patterns.isEmpty()) {
                    errors += headerDiagnostic(lineNumber, "@network patterns requires a non-empty list")
                    null
                } else {
                    JsonValue.Obj(
                        linkedMapOf(
                            "mode" to JsonValue.Str("patterns"),
                            "patterns" to JsonValue.Arr(patterns.map { JsonValue.Str(it) }),
                        ),
                    )
                }
            }

            value == "open" -> JsonValue.Obj(mapOf("mode" to JsonValue.Str("open")))
            value.startsWith("open:") -> JsonValue.Obj(
                linkedMapOf(
                    "mode" to JsonValue.Str("open"),
                    "reason" to JsonValue.Str(value.removePrefix("open:").trim()),
                ),
            )

            else -> {
                errors += headerDiagnostic(
                    lineNumber,
                    "@network must be 'none', 'patterns:<list>' or 'open[:<reason>]'",
                )
                null
            }
        }
    }

    private fun parseHeaderCommand(
        value: String,
        lineNumber: Int,
        errors: MutableList<PackDiagnostic>,
    ): JsonValue.Obj? {
        val parts = value.split(WHITESPACE).filter { it.isNotEmpty() }
        if (parts.size !in 1..2) {
            errors += headerDiagnostic(lineNumber, "@command must be '<name> [source]'")
            return null
        }
        val source = when (parts.getOrNull(1)) {
            null -> false
            "source" -> true
            else -> {
                errors += headerDiagnostic(lineNumber, "@command second token must be 'source'")
                return null
            }
        }
        return JsonValue.Obj(
            linkedMapOf(
                "name" to JsonValue.Str(parts[0]),
                "component" to JsonValue.Str("main"),
                "source" to JsonValue.Bool(source),
            ),
        )
    }

    private fun parseHeaderTool(
        value: String,
        lineNumber: Int,
        errors: MutableList<PackDiagnostic>,
    ): JsonValue.Obj? {
        val (name, afterName) = splitToken(value)
        if (name.isEmpty() || afterName.isEmpty()) {
            errors += headerDiagnostic(lineNumber, "@mcpTool must be '<name> <command> [write|read] [schema-json]'")
            return null
        }
        val (command, afterCommand) = splitToken(afterName)
        if (command.isEmpty()) {
            errors += headerDiagnostic(lineNumber, "@mcpTool is missing its command")
            return null
        }
        var tail = afterCommand
        val write = when {
            tail == "write" -> {
                tail = ""
                true
            }

            tail.startsWith("write ") -> {
                tail = tail.removePrefix("write").trim()
                true
            }

            tail == "read" -> {
                tail = ""
                false
            }

            tail.startsWith("read ") -> {
                tail = tail.removePrefix("read").trim()
                false
            }

            else -> false
        }
        val schema = if (tail.isEmpty()) {
            null
        } else {
            try {
                val parsed = StrictJsonParser().parse(tail)
                val obj = parsed as? JsonValue.Obj
                if (obj == null) {
                    errors += headerDiagnostic(lineNumber, "@mcpTool schema must be a JSON object")
                    null
                } else {
                    obj
                }
            } catch (e: JsonStructureException) {
                errors += headerDiagnostic(
                    lineNumber,
                    "@mcpTool schema is invalid JSON: ${e.message ?: "parse error"}",
                )
                null
            }
        }
        if (schema == null && tail.isNotEmpty()) return null
        val entries = linkedMapOf<String, JsonValue>(
            "name" to JsonValue.Str(name),
            "command" to JsonValue.Str(command),
            "write" to JsonValue.Bool(write),
        )
        schema?.let { entries["inputSchema"] = it }
        return JsonValue.Obj(entries)
    }

    private fun parseHeaderRoute(
        value: String,
        lineNumber: Int,
        errors: MutableList<PackDiagnostic>,
    ): JsonValue.Obj? {
        val parts = value.split(WHITESPACE).filter { it.isNotEmpty() }
        if (parts.size !in 3..4) {
            errors += headerDiagnostic(lineNumber, "@apiRoute must be '<name> <GET|POST> <command> [write]'")
            return null
        }
        val write = when (parts.getOrNull(3)) {
            null -> false
            "write" -> true
            else -> {
                errors += headerDiagnostic(lineNumber, "@apiRoute fourth token must be 'write'")
                return null
            }
        }
        return JsonValue.Obj(
            linkedMapOf(
                "name" to JsonValue.Str(parts[0]),
                "method" to JsonValue.Str(parts[1]),
                "command" to JsonValue.Str(parts[2]),
                "write" to JsonValue.Bool(write),
            ),
        )
    }

    private fun parseHeaderSelfTest(
        value: String,
        lineNumber: Int,
        errors: MutableList<PackDiagnostic>,
    ): JsonValue.Obj? {
        val parts = value.split(WHITESPACE).filter { it.isNotEmpty() }
        if (parts.size !in 2..3) {
            errors += headerDiagnostic(lineNumber, "@selfTest must be '<name> <command> [timeoutMs]'")
            return null
        }
        val timeoutRaw = parts.getOrNull(2)
        val timeout = timeoutRaw?.toLongOrNull()
        if (timeoutRaw != null && timeout == null) {
            errors += headerDiagnostic(lineNumber, "@selfTest timeout must be an integer")
            return null
        }
        val entries = linkedMapOf<String, JsonValue>(
            "name" to JsonValue.Str(parts[0]),
            "command" to JsonValue.Str(parts[1]),
        )
        timeout?.let { entries["timeoutMs"] = JsonValue.Num(it.toString()) }
        return JsonValue.Obj(entries)
    }

    private fun headerDiagnostic(lineNumber: Int, message: String): PackDiagnostic =
        diagnostic(PackErrorCode.MANIFEST_INVALID, "header.line$lineNumber", message)

    private fun diagnostic(
        code: PackErrorCode,
        path: String,
        message: String,
        details: Map<String, String> = emptyMap(),
    ): PackDiagnostic = PackDiagnostic(code, message, path, details)

    private fun checkAllowed(
        obj: JsonValue.Obj,
        allowed: Set<String>,
        path: String,
        errors: Errors,
    ) {
        obj.entries.keys.forEach { key ->
            if (key !in allowed) {
                errors.invalid("$path.$key", "unknown field '$key'")
            }
        }
    }

    private fun readString(
        obj: JsonValue.Obj,
        key: String,
        path: String,
        errors: Errors,
        required: Boolean = false,
        maxLength: Int = 1024,
        allowBlank: Boolean = false,
    ): String? {
        val value = obj.entries[key]
        if (value == null) {
            if (required) errors.invalid("$path.$key", "missing required field")
            return null
        }
        val string = (value as? JsonValue.Str)?.value
        if (string == null) {
            errors.invalid("$path.$key", "must be a string")
            return null
        }
        if (string.length > maxLength) {
            errors.invalid("$path.$key", "must be at most $maxLength characters")
            return null
        }
        if (!allowBlank && string.isBlank()) {
            errors.invalid("$path.$key", "must not be blank")
            return null
        }
        return string
    }

    private fun readRelativePath(
        obj: JsonValue.Obj,
        key: String,
        path: String,
        errors: Errors,
    ): RelativePath? {
        val raw = readString(obj, key, path, errors, required = true, maxLength = 512) ?: return null
        val relative = RelativePath.parse(raw)
        if (relative == null) {
            errors.invalid("$path.$key", "invalid relative path '$raw'")
        }
        return relative
    }

    private fun readBoolean(
        obj: JsonValue.Obj,
        key: String,
        path: String,
        errors: Errors,
        default: Boolean,
    ): Boolean {
        val value = obj.entries[key] ?: return default
        val bool = value as? JsonValue.Bool
        if (bool == null) {
            errors.invalid("$path.$key", "must be a boolean")
            return default
        }
        return bool.value
    }

    private fun readInt(
        obj: JsonValue.Obj,
        key: String,
        path: String,
        errors: Errors,
        required: Boolean = false,
    ): Int? {
        val long = readLong(obj, key, path, errors, required) ?: return null
        if (long !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
            errors.invalid("$path.$key", "integer is out of range")
            return null
        }
        return long.toInt()
    }

    private fun readLong(
        obj: JsonValue.Obj,
        key: String,
        path: String,
        errors: Errors,
        required: Boolean = false,
    ): Long? {
        val value = obj.entries[key]
        if (value == null) {
            if (required) errors.invalid("$path.$key", "missing required field")
            return null
        }
        val number = value as? JsonValue.Num
        if (number == null || !INTEGER_LITERAL.matches(number.raw)) {
            errors.invalid("$path.$key", "must be an integer")
            return null
        }
        val parsed = number.raw.toLongOrNull()
        if (parsed == null) {
            errors.invalid("$path.$key", "integer is out of range")
            return null
        }
        return parsed
    }

    private fun splitToken(value: String): Pair<String, String> {
        val trimmed = value.trim()
        val separator = trimmed.indexOfFirst { it.isWhitespace() }
        return if (separator < 0) {
            trimmed to ""
        } else {
            trimmed.substring(0, separator) to trimmed.substring(separator + 1).trim()
        }
    }

    private data class ParsedCommand(val spec: CommandSpec, val path: String)

    private data class ParsedTool(val spec: ToolSpec, val path: String)

    private data class ParsedRoute(val spec: RouteSpec, val path: String)

    private data class ParsedSelfTest(val spec: SelfTestSpec, val path: String)

    private class Errors {
        val list = mutableListOf<PackDiagnostic>()

        val hasErrors: Boolean get() = list.isNotEmpty()

        fun add(
            code: PackErrorCode,
            path: String,
            message: String,
            details: Map<String, String> = emptyMap(),
        ) {
            list += PackDiagnostic(code, message, path, details)
        }

        fun invalid(path: String, message: String) =
            add(PackErrorCode.MANIFEST_INVALID, path, message)
    }

    companion object {
        private val TOP_LEVEL_FIELDS = setOf(
            "schemaVersion",
            "id",
            "name",
            "version",
            "versionCode",
            "packLib",
            "description",
            "author",
            "capabilities",
            "network",
            "components",
            "commands",
            "mcpTools",
            "apiRoutes",
            "selfTests",
        )
        private val NETWORK_FIELDS = setOf("mode", "patterns", "reason")
        private val COMMAND_FIELDS = setOf("name", "component", "description", "source")
        private val TOOL_FIELDS = setOf("name", "description", "command", "inputSchema", "write")
        private val ROUTE_FIELDS = setOf("name", "method", "command", "write")
        private val SELF_TEST_FIELDS = setOf("name", "command", "timeoutMs")
        private val HEADER_LINE = Regex("^//\\s*@([A-Za-z][A-Za-z0-9]*)(?:\\s+(.*))?$")
        private val WHITESPACE = Regex("\\s+")
        private val INTEGER_LITERAL = Regex("-?(?:0|[1-9][0-9]*)")
        private val PACK_VERSION = Regex("[0-9A-Za-z][0-9A-Za-z._+-]{0,63}")
    }
}