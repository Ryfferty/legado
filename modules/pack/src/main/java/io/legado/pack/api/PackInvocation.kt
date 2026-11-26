package io.legado.pack.api

/**
 * Identity of the entry that caused an invocation. Registered MCP/Web callbacks
 * capture a tool/route name together with the PackRef they were registered for;
 * the manager resolves that name to a command inside the same admission critical
 * section.
 */
sealed interface PackEntryPoint {

    val name: String

    data class SourceCommand(override val name: String) : PackEntryPoint {
        init {
            require(CommandSpec.isValidName(name)) { "invalid source command name: '$name'" }
        }
    }

    data class McpTool(override val name: String) : PackEntryPoint {
        init {
            require(ToolSpec.isValidName(name)) { "invalid MCP tool name: '$name'" }
        }
    }

    data class WebRoute(override val name: String) : PackEntryPoint {
        init {
            require(RouteSpec.isValidName(name)) { "invalid Web route name: '$name'" }
        }
    }

    data class DirectCommand(override val name: String) : PackEntryPoint {
        init {
            require(CommandSpec.isValidName(name)) { "invalid direct command name: '$name'" }
        }
    }
}

/**
 * Strongly typed invocation for registered MCP tools, Web routes and source
 * commands.
 *
 * The manager must, in one admission critical section, compare [expectedRef]
 * with the currently enabled revision/epoch, resolve [entry] to its command
 * through the manifest, verify that the entry still exists, check the relevant
 * grant (tool/route permission and sourceExported) and only then enqueue work.
 * A stale reference must fail; it must never be redirected to a newer revision
 * that happens to reuse the same command/tool name.
 */
data class PackInvocation(
    val expectedRef: PackRef,
    val entry: PackEntryPoint,
    val argJson: String,
    val caller: PackCaller,
    val timeoutMs: Long? = null,
    val cancellation: CancellationSignal = CancellationSignal(),
) {
    init {
        when (entry) {
            is PackEntryPoint.SourceCommand -> require(caller is PackCaller.Source) {
                "source command invocation requires PackCaller.Source"
            }

            is PackEntryPoint.McpTool -> require(caller is PackCaller.Mcp) {
                "MCP tool invocation requires PackCaller.Mcp"
            }

            is PackEntryPoint.WebRoute -> require(caller is PackCaller.Web) {
                "Web route invocation requires PackCaller.Web"
            }

            is PackEntryPoint.DirectCommand -> require(caller is PackCaller.Host) {
                "direct command invocation requires PackCaller.Host"
            }
        }
    }
}