package io.legado.app.web.mcp

import io.legado.app.api.controller.PackController
import io.legado.app.help.pack.FakePackManager
import io.legado.app.help.pack.HostMcpToolNames
import io.legado.app.help.pack.PackFixtures
import io.legado.pack.api.PackCaller
import io.legado.pack.api.PackEntryPoint
import io.legado.pack.api.PackHttpMethod
import io.legado.pack.api.PackState
import io.legado.pack.registry.PackRegistration
import io.legado.pack.registry.PackRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PackControllerTest {

    private val token = mapOf("X-Legado-Token" to "secret")
    private val tokenValidator: (Map<String, String>) -> Boolean = {
        it["X-Legado-Token"] == "secret"
    }

    @Test
    fun `authentication runs before any request body is read`() = runBlocking {
        val manager = FakePackManager(listOf(PackFixtures.info()))
        val registry = newRegistry(
            PackFixtures.manifest(routeMethod = PackHttpMethod.POST)
        )
        var bodyReads = 0

        val response = PackController.handle(
            method = "POST",
            uri = "/packs/demo.pack/api/echo",
            headers = mapOf("X-Legado-Token" to "wrong"),
            remoteAddress = "127.0.0.1",
            readBody = {
                bodyReads++
                "{\"value\":\"x\"}"
            },
            manager = manager,
            registry = registry,
            tokenValidator = tokenValidator,
        )

        assertEquals(401, response.statusCode)
        assertEquals(0, bodyReads)
        assertNull(manager.lastInvocation)
    }

    @Test
    fun `pack route invocation carries the registered ref and web caller`() = runBlocking {
        val manager = FakePackManager(listOf(PackFixtures.info()))
        val manifest = PackFixtures.manifest(routeMethod = PackHttpMethod.POST)
        val registry = newRegistry(manifest)
        var bodyReads = 0

        val response = PackController.handle(
            method = "POST",
            uri = "/packs/demo.pack/api/echo",
            headers = token,
            remoteAddress = "192.168.1.8",
            readBody = {
                bodyReads++
                "{\"value\":\"hello\"}"
            },
            manager = manager,
            registry = registry,
            tokenValidator = tokenValidator,
        )

        assertEquals(200, response.statusCode)
        assertEquals(1, bodyReads)
        val invocation = manager.lastInvocation!!
        assertEquals(PackEntryPoint.WebRoute("echo"), invocation.entry)
        assertEquals("{\"value\":\"hello\"}", invocation.argJson)
        assertEquals("192.168.1.8", (invocation.caller as PackCaller.Web).remoteAddress)
        assertEquals(PackFixtures.ref(manifest), invocation.expectedRef)
    }

    @Test
    fun `unknown routes methods and large bodies are rejected without dispatch`() = runBlocking {
        val manager = FakePackManager(listOf(PackFixtures.info()))
        val registry = newRegistry(PackFixtures.manifest(routeMethod = PackHttpMethod.GET))

        val unknown = PackController.handle(
            "GET",
            "/packs/demo.pack/api/missing",
            token,
            manager = manager,
            registry = registry,
            tokenValidator = tokenValidator,
        )
        assertEquals(404, unknown.statusCode)

        val wrongMethod = PackController.handle(
            "POST",
            "/packs/demo.pack/api/echo",
            token,
            manager = manager,
            registry = registry,
            tokenValidator = tokenValidator,
        )
        assertEquals(405, wrongMethod.statusCode)
        assertEquals("GET", wrongMethod.headers["Allow"])

        var bodyReads = 0
        val postRegistry = newRegistry(PackFixtures.manifest(routeMethod = PackHttpMethod.POST))
        val tooLarge = PackController.handle(
            method = "POST",
            uri = "/packs/demo.pack/api/echo",
            headers = token + ("Content-Length" to (PackController.MAX_BODY_BYTES + 1).toString()),
            readBody = {
                bodyReads++
                "{}"
            },
            manager = manager,
            registry = postRegistry,
            tokenValidator = tokenValidator,
        )
        assertEquals(413, tooLarge.statusCode)
        assertEquals(0, bodyReads)
        assertNull(manager.lastInvocation)

        val insecurePath = PackController.handle(
            "GET",
            "/packs/../api/echo",
            token,
            manager = manager,
            registry = registry,
            tokenValidator = tokenValidator,
        )
        assertEquals(404, insecurePath.statusCode)
    }

    @Test
    fun `self test execution and report lookup are separate and share runId`() = runBlocking {
        val manager = FakePackManager(listOf(PackFixtures.info()))
        val report = PackFixtures.report()
        manager.putReport(report)
        val registry = newRegistry(PackFixtures.manifest())

        val executed = PackController.handle(
            method = "POST",
            uri = "/packs/demo.pack/selftest",
            headers = token,
            readBody = { "" },
            manager = manager,
            registry = registry,
            tokenValidator = tokenValidator,
        )
        assertEquals(200, executed.statusCode)
        val executedJson = kotlinx.serialization.json.Json
            .parseToJsonElement(executed.body).jsonObject
        assertEquals(
            "run_1",
            executedJson["data"]!!.jsonObject["runId"]!!.jsonPrimitive.content,
        )
        assertEquals(1, manager.selfTestCount)

        val fetched = PackController.handle(
            method = "GET",
            uri = "/packs/demo.pack/selftest/run_1",
            headers = token,
            manager = manager,
            registry = registry,
            tokenValidator = tokenValidator,
        )
        assertEquals(200, fetched.statusCode)
        assertEquals(1, manager.selfTestCount)
        assertTrue(fetched.body.contains("\"runId\":\"run_1\""))

        val latest = PackController.handle(
            method = "GET",
            uri = "/packs/demo.pack/selftest",
            headers = token,
            manager = manager,
            registry = registry,
            tokenValidator = tokenValidator,
        )
        assertEquals(200, latest.statusCode)
        assertEquals(1, manager.selfTestCount)

        val missing = PackController.handle(
            method = "GET",
            uri = "/packs/demo.pack/selftest/missing",
            headers = token,
            manager = manager,
            registry = registry,
            tokenValidator = tokenValidator,
        )
        assertEquals(404, missing.statusCode)
    }

    @Test
    fun `disabled pack produces a structured 409 without dispatch`() = runBlocking {
        val manager = FakePackManager(
            listOf(PackFixtures.info(state = PackState.DISABLED))
        )
        val registry = newRegistry(PackFixtures.manifest())

        val response = PackController.handle(
            "GET",
            "/packs/demo.pack/api/echo",
            token,
            manager = manager,
            registry = registry,
            tokenValidator = tokenValidator,
        )

        assertEquals(409, response.statusCode)
        assertTrue(response.body.contains("\"code\":\"PACK_DISABLED\""))
        assertTrue(response.body.contains("\"isError\":true"))
    }

    private fun newRegistry(manifest: io.legado.pack.api.PackManifest): PackRegistry {
        val registry = PackRegistry(reservedToolNames = HostMcpToolNames.RESERVED)
        val applied = registry.replaceAll(
            listOf(PackRegistration(PackFixtures.ref(manifest), manifest))
        )
        assertTrue(applied is io.legado.pack.registry.RegistrationResult.Applied)
        return registry
    }
}