package io.legado.app.help.pack

import io.legado.pack.api.CommandSpec
import io.legado.pack.api.ComponentSpec
import io.legado.pack.api.JsonValue
import io.legado.pack.api.PackCapability
import io.legado.pack.api.PackHttpMethod
import io.legado.pack.api.PackId
import io.legado.pack.api.PackInfo
import io.legado.pack.api.PackManifest
import io.legado.pack.api.PackMetrics
import io.legado.pack.api.PackRef
import io.legado.pack.api.PackState
import io.legado.pack.api.RelativePath
import io.legado.pack.api.RevisionId
import io.legado.pack.api.RouteSpec
import io.legado.pack.api.SelfTestCaseResult
import io.legado.pack.api.SelfTestReport
import io.legado.pack.api.SelfTestSpec
import io.legado.pack.api.SelfTestStatus
import io.legado.pack.api.ToolSpec
import io.legado.pack.api.VersionRange

object PackFixtures {

    val packId: PackId = PackId.require("demo.pack")
    val revision: RevisionId = RevisionId.require("a".repeat(64))

    fun schema(): JsonValue.Obj = JsonValue.Obj(
        mapOf(
            "type" to JsonValue.Str("object"),
            "properties" to JsonValue.Obj(
                mapOf(
                    "value" to JsonValue.Obj(mapOf("type" to JsonValue.Str("string"))),
                )
            ),
            "required" to JsonValue.Arr(listOf(JsonValue.Str("value"))),
        )
    )

    fun manifest(
        toolName: String = "demo_add",
        command: String = "add",
        isWrite: Boolean = false,
        routeName: String = "echo",
        routeCommand: String = "route_echo",
        routeMethod: PackHttpMethod = PackHttpMethod.GET,
        sourceCommand: String = "source_echo",
    ): PackManifest = PackManifest(
        schemaVersion = 1,
        id = packId,
        name = "Demo Pack",
        version = "1.0.0",
        versionCode = 1,
        packLib = VersionRange.ANY,
        components = listOf(
            ComponentSpec.Js("main", RelativePath.require("main.js")),
        ),
        commands = listOf(
            CommandSpec(command, "main"),
            CommandSpec(routeCommand, "main"),
            CommandSpec(sourceCommand, "main", sourceExported = true),
            CommandSpec("selftest", "main"),
        ),
        description = "test pack",
        capabilities = setOf(
            PackCapability.MCP_TOOLS,
            PackCapability.API_ROUTES,
            PackCapability.SELF_TEST,
            PackCapability.SOURCE_COMMANDS,
            PackCapability.KV,
            PackCapability.CRYPTO,
            PackCapability.HTTP,
            PackCapability.LOG,
        ),
        mcpTools = listOf(
            ToolSpec(
                name = toolName,
                command = command,
                description = "demo tool",
                inputSchema = schema(),
                isWrite = isWrite,
            )
        ),
        apiRoutes = listOf(
            RouteSpec(routeName, routeMethod, routeCommand)
        ),
        selfTests = listOf(
            SelfTestSpec("basic", "selftest"),
        ),
    )

    fun ref(
        manifest: PackManifest = manifest(),
        epoch: Long = 1L,
        revision: RevisionId = PackFixtures.revision,
    ): PackRef = PackRef(manifest.id, revision, epoch)

    fun info(
        manifest: PackManifest = manifest(),
        epoch: Long = 1L,
        state: PackState = PackState.ENABLED,
    ): PackInfo = PackInfo(
        ref = ref(manifest, epoch),
        manifest = manifest,
        state = state,
        installedAtEpochMs = 1L,
    )

    fun report(
        packRef: PackRef = ref(),
        runId: String = "run_1",
    ): SelfTestReport = SelfTestReport(
        runId = runId,
        packRef = packRef,
        startedAtEpochMs = 1L,
        finishedAtEpochMs = 2L,
        cases = listOf(
            SelfTestCaseResult("basic", SelfTestStatus.PASS, elapsedMs = 1L),
        ),
        metrics = PackMetrics(elapsedMs = 1L),
    )
}