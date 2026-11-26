package io.legado.pack.api

/**
 * Fixed Web/MCP path scheme. The Web layer must authenticate and normalize the
 * `/packs/` prefix before dispatching; unmatched pack paths must return 404,
 * never fall through to static assets.
 */
object PackRoutes {
    const val PREFIX: String = "/packs/"

    private val RUN_ID = Regex("[A-Za-z0-9_-]{1,64}")

    fun packRootPath(id: PackId): String = "$PREFIX${id.value}"

    fun apiPath(id: PackId, routeName: String): String {
        require(RouteSpec.isValidName(routeName)) { "invalid route name: '$routeName'" }
        return "$PREFIX${id.value}/api/$routeName"
    }

    fun selfTestPath(id: PackId, runId: String): String {
        require(isValidRunId(runId)) { "invalid self-test runId: '$runId'" }
        return "$PREFIX${id.value}/selftest/$runId"
    }

    fun isValidRunId(runId: String): Boolean = RUN_ID.matches(runId)

    fun isPackPath(path: String): Boolean = path.startsWith(PREFIX)
}