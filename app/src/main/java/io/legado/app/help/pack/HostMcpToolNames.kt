package io.legado.app.help.pack

import java.util.Locale

/**
 * MCP tool names owned by the host. Pack manifests may never register any of
 * these names (case-insensitively); [PackHost] installs this set as the
 * reserved names of the registration registry before any pack is admitted.
 *
 * Keep [BUILTIN] in sync with the tools registered by `McpToolServer`.
 * [PACK_MANAGEMENT] belongs to this framework and is registered by
 * `McpPackTools`; both groups are host-owned.
 */
object HostMcpToolNames {

    val BUILTIN: Set<String> = linkedSetOf(
        "save_source",
        "debug_source",
        "list_sources",
        "get_source",
        "delete_sources",
        "get_http_logs",
        "get_http_log",
        "set_http_log_recording",
        "get_cookies",
        "set_cookie",
        "clear_cookies",
        "eval_js",
        "check_source",
    )

    val PACK_MANAGEMENT: Set<String> = linkedSetOf(
        "pack_list",
        "pack_info",
        "pack_selftest",
        "pack_selftest_report",
        "pack_tool_permissions",
        "pack_set_write_tool",
        "pack_set_enabled",
        "pack_uninstall",
    )

    val RESERVED: Set<String> = LinkedHashSet<String>(
        BUILTIN.size + PACK_MANAGEMENT.size
    ).apply {
        addAll(BUILTIN)
        addAll(PACK_MANAGEMENT)
    }

    private val RESERVED_LOWER: Set<String> =
        RESERVED.mapTo(HashSet<String>(RESERVED.size)) { it.lowercase(Locale.ROOT) }

    /** MCP names are case-sensitive, so reservation is enforced case-insensitively. */
    fun isReserved(name: String): Boolean =
        name.lowercase(Locale.ROOT) in RESERVED_LOWER
}