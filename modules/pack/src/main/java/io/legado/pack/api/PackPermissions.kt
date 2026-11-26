package io.legado.pack.api

import java.security.MessageDigest

/**
 * Everything a pack asks the host to grant, normalized for display and for a
 * content/permission-bound import confirmation.
 */
class PackPermissionSet(
    val packId: PackId,
    capabilities: Set<PackCapability>,
    val network: NetworkPolicy,
    sourceCommands: Set<String>,
    mcpTools: Set<String>,
    writeToolNames: Set<String> = emptySet(),
    apiRoutes: Set<String> = emptySet(),
) {
    val capabilities: Set<PackCapability> = immutableSet(capabilities)
    val sourceCommands: Set<String> = immutableSet(sourceCommands)
    val mcpTools: Set<String> = immutableSet(mcpTools)
    val writeToolNames: Set<String> = immutableSet(writeToolNames)
    val apiRoutes: Set<String> = immutableSet(apiRoutes)

    init {
        require(writeToolNames.all { it in mcpTools }) {
            "writeToolNames must be a subset of mcpTools"
        }
    }

    val requiresOpenNetworkApproval: Boolean
        get() = network is NetworkPolicy.Open

    /** Lower-case SHA-256 over a deterministic permission description. */
    fun digest(): String {
        val canonical = buildString {
            append("pack-permissions/v1\n")
            append("pack=").append(packId.value).append('\n')
            append("capabilities=")
                .append(capabilities.map { it.name }.sorted().joinToString(","))
                .append('\n')
            append("network=").append(network.canonical()).append('\n')
            append("source=").append(sourceCommands.sorted().joinToString(",")).append('\n')
            append("tools=").append(mcpTools.sorted().joinToString(",")).append('\n')
            append("writeTools=").append(writeToolNames.sorted().joinToString(",")).append('\n')
            append("routes=").append(apiRoutes.sorted().joinToString(",")).append('\n')
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .toLowerHex()
    }

    fun copy(
        packId: PackId = this.packId,
        capabilities: Set<PackCapability> = this.capabilities,
        network: NetworkPolicy = this.network,
        sourceCommands: Set<String> = this.sourceCommands,
        mcpTools: Set<String> = this.mcpTools,
        writeToolNames: Set<String> = this.writeToolNames,
        apiRoutes: Set<String> = this.apiRoutes,
    ): PackPermissionSet = PackPermissionSet(
        packId = packId,
        capabilities = capabilities,
        network = network,
        sourceCommands = sourceCommands,
        mcpTools = mcpTools,
        writeToolNames = writeToolNames,
        apiRoutes = apiRoutes,
    )

    override fun equals(other: Any?): Boolean =
        other is PackPermissionSet &&
            packId == other.packId &&
            capabilities == other.capabilities &&
            network == other.network &&
            sourceCommands == other.sourceCommands &&
            mcpTools == other.mcpTools &&
            writeToolNames == other.writeToolNames &&
            apiRoutes == other.apiRoutes

    override fun hashCode(): Int {
        var result = packId.hashCode()
        result = 31 * result + capabilities.hashCode()
        result = 31 * result + network.hashCode()
        result = 31 * result + sourceCommands.hashCode()
        result = 31 * result + mcpTools.hashCode()
        result = 31 * result + writeToolNames.hashCode()
        result = 31 * result + apiRoutes.hashCode()
        return result
    }

    override fun toString(): String = "PackPermissionSet(pack=${packId.value})"
}

/**
 * User confirmation bound to a permission digest.
 *
 * [toolPermissions] and [routePermissions] must explicitly contain `true` for
 * each allowed entry. Merely declaring a tool/route (or its MCP annotations)
 * never grants it; write tools must be switched on explicitly by the host UI.
 */
class PackGrant(
    val permissionHash: String,
    val networkOpenApproved: Boolean = false,
    toolPermissions: Map<String, Boolean> = emptyMap(),
    routePermissions: Map<String, Boolean> = emptyMap(),
) {
    val toolPermissions: Map<String, Boolean> = immutableMap(toolPermissions)
    val routePermissions: Map<String, Boolean> = immutableMap(routePermissions)

    init {
        require(FORMAT.matches(permissionHash)) {
            "permissionHash must be 64 lower-case hex characters"
        }
    }

    fun permitsTool(name: String): Boolean = toolPermissions[name] == true

    fun permitsRoute(name: String): Boolean = routePermissions[name] == true

    /**
     * True only when this grant was issued for the exact permission digest and,
     * for an open-network pack, the user explicitly approved that mode.
     */
    fun approves(permissions: PackPermissionSet): Boolean =
        permissionHash == permissions.digest() &&
            (!permissions.requiresOpenNetworkApproval || networkOpenApproved)

    fun copy(
        permissionHash: String = this.permissionHash,
        networkOpenApproved: Boolean = this.networkOpenApproved,
        toolPermissions: Map<String, Boolean> = this.toolPermissions,
        routePermissions: Map<String, Boolean> = this.routePermissions,
    ): PackGrant = PackGrant(
        permissionHash = permissionHash,
        networkOpenApproved = networkOpenApproved,
        toolPermissions = toolPermissions,
        routePermissions = routePermissions,
    )

    override fun equals(other: Any?): Boolean =
        other is PackGrant &&
            permissionHash == other.permissionHash &&
            networkOpenApproved == other.networkOpenApproved &&
            toolPermissions == other.toolPermissions &&
            routePermissions == other.routePermissions

    override fun hashCode(): Int {
        var result = permissionHash.hashCode()
        result = 31 * result + networkOpenApproved.hashCode()
        result = 31 * result + toolPermissions.hashCode()
        result = 31 * result + routePermissions.hashCode()
        return result
    }

    override fun toString(): String = "PackGrant(permissionHash=$permissionHash)"

    companion object {
        private val FORMAT = Regex("[0-9a-f]{64}")
    }
}

internal fun ByteArray.toLowerHex(): String {
    val alphabet = "0123456789abcdef"
    return buildString(size * 2) {
        for (byte in this@toLowerHex) {
            val value = byte.toInt() and 0xff
            append(alphabet[value ushr 4])
            append(alphabet[value and 0x0f])
        }
    }
}