package io.legado.pack.runtime.js

import io.legado.pack.api.*
import io.legado.pack.runtime.RuntimeTestContext
import org.htmlunit.corejs.javascript.ContextFactory
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class JsPackRuntimeProviderTest {
    private val provider = JsPackRuntimeProvider()

    private fun load(context: RuntimeTestContext, call: PackCallContext = context.call()): PackInstance =
        provider.load(context.ref, context.manifest.components.single(), context, call)

    @Test fun helloAndScalarJsonRoundTrip() {
        val context = RuntimeTestContext("globalThis.__packExports = { hello: function(a) { return {ok:true, arg:a}; } };")
        val instance = load(context)
        assertEquals("{\"ok\":true,\"arg\":{\"x\":[1,true,null]}}", instance.invoke("hello", "{\"x\":[1,true,null]}", context.call()))
        assertTrue(instance.close().released)
    }

    @Test fun javaGlobalsReflectionFilesAndHostObjectsAreAbsent() {
        val context = RuntimeTestContext("""
            globalThis.__packExports = { hello: function() {
                return [typeof Packages, typeof java, typeof javax, typeof android,
                    typeof getClass, typeof Context, typeof load, typeof readFile,
                    typeof __packApi.getClass, typeof __packApi.kvGet.getClass,
                    typeof __packApi.kvGet.constructor, typeof ({}).getClass,
                    typeof ''.getClass, Object.getPrototypeOf(__packApi) === null];
            }};
        """.trimIndent())
        val instance = load(context)
        val expected = List(13) { "\"undefined\"" }.joinToString(",", "[", ",true]")
        assertEquals(expected, instance.invoke("hello", "null", context.call()))
        instance.close()
    }

    @Test fun independentScopeAndFactoryDoNotModifyGlobalRhino() {
        val global = ContextFactory.getGlobal()
        val context = RuntimeTestContext("var count=0; globalThis.__packExports={hello:function(){ return ++count; }};")
        val first = load(context)
        val second = load(context)
        assertEquals("1", first.invoke("hello", "null", context.call()))
        assertEquals("2", first.invoke("hello", "null", context.call()))
        assertEquals("1", second.invoke("hello", "null", context.call()))
        assertSame(global, ContextFactory.getGlobal())
        first.close(); second.close()
    }

    @Test fun differentContentUnderSameRevisionDoesNotReuseWrongCompiledScript() {
        val one = RuntimeTestContext("globalThis.__packExports={hello:function(){ return 1; }};")
        val two = RuntimeTestContext("globalThis.__packExports={hello:function(){ return 2; }};")
        val first = load(one)
        val second = load(two)
        assertEquals("1", first.invoke("hello", "null", one.call()))
        assertEquals("2", second.invoke("hello", "null", two.call()))
        first.close(); second.close()
    }

    @Test fun narrowKvAndCryptoPrimitivesOnlyReturnJsValues() {
        val context = RuntimeTestContext("""
            globalThis.__packExports={hello:function() {
                __packApi.kvPut('key','value');
                return {value:__packApi.kvGet('key'), hash:__packApi.cryptoDigest('SHA-256','YWJj')};
            }};
        """.trimIndent(), setOf(PackCapability.KV, PackCapability.CRYPTO))
        val instance = load(context)
        assertEquals("{\"value\":\"value\",\"hash\":\"ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0=\"}", instance.invoke("hello", "null", context.call()))
        instance.close()
    }

    @Test fun cryptoCipherTreatsExplicitUndefinedIvAsAbsent() {
        // The documented signature is `ivBase64?`: an explicit JS `undefined`
        // must behave like a missing argument instead of INVALID_REQUEST.
        val context = RuntimeTestContext(
            "globalThis.__packExports={hello:function(a) {" +
                "if (a === 'undef') return __packApi.cryptoCipher('undef-spec','YWJj',undefined,'ZGVm');" +
                "return __packApi.cryptoCipher('iv-spec','YWJj','aXY=','ZGVm'); }};",
            setOf(PackCapability.CRYPTO),
        )
        val instance = load(context)
        assertEquals("\"ZGVm\"", instance.invoke("hello", "\"undef\"", context.call()))
        assertNull(context.cipherCalls["undef-spec"]?.first)
        assertEquals("\"ZGVm\"", instance.invoke("hello", "\"iv\"", context.call()))
        assertTrue(context.cipherCalls["iv-spec"]?.first?.contentEquals(byteArrayOf(0x69, 0x76)) == true)
        instance.close()
    }

    @Test fun httpAdmissionFailsBeforeTheRequestIsSerialized() {
        // The capability/network check runs before the request is serialized:
        // with an oversized request body the failure must still be the
        // permission denial, not a codec limit or an internal error.
        val context = RuntimeTestContext(
            "globalThis.__packExports={hello:function(){" +
                "return __packApi.http({method:'GET',url:'http://example.com'," +
                "bodyBase64:new Array(24000002).join('A')});}};",
            setOf(PackCapability.HTTP),
        )
        val instance = load(context)
        val failure = assertThrows(PackException::class.java) { instance.invoke("hello", "null", context.call()) }
        assertEquals(PackErrorCode.PERMISSION_DENIED, failure.diagnostic.code)
        assertTrue(failure.diagnostic.message.contains("network is not declared"))
        instance.close()
    }

    @Test fun undeclaredPrimitiveFailsWithoutExposingJavaException() {
        val context = RuntimeTestContext("globalThis.__packExports={hello:function(){ try { __packApi.kvGet('x'); } catch(e) { return e; } }};")
        val instance = load(context)
        val failure = assertThrows(PackException::class.java) { instance.invoke("hello", "null", context.call()) }
        assertEquals(PackErrorCode.PERMISSION_DENIED, failure.diagnostic.code)
        instance.close()
    }

    @Test fun initializationSyntaxErrorAndMissingExportAreRedCards() {
        for (source in listOf("var = ;", "throw new Error('init');", "globalThis.__packExports={};")) {
            val context = RuntimeTestContext(source)
            val failure = assertThrows(PackException::class.java) { load(context) }
            assertTrue(failure.diagnostic.isRedCard)
        }
    }

    @Test(timeout = 10000) fun initializationLoopUsesOriginalDeadline() {
        val context = RuntimeTestContext("while(true) { try {} catch(e) {} }")
        assertThrows(PackTimeoutException::class.java) { load(context, context.call(150)) }
    }

    @Test(timeout = 10000) fun commandLoopCannotCatchObserverTimeout() {
        val context = RuntimeTestContext("globalThis.__packExports={hello:function(){ while(true){ try {} catch(e){} } }};")
        val instance = load(context)
        assertThrows(PackTimeoutException::class.java) { instance.invoke("hello", "null", context.call(100)) }
        assertTrue(instance.close().released)
    }

    @Test(timeout = 10000) fun cancellationStopsObservedLoopWithoutThreadInterrupt() {
        val context = RuntimeTestContext("globalThis.__packExports={hello:function(){ while(true){} }};")
        val instance = load(context)
        val call = context.call(5000)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val running = CountDownLatch(1)
            val result = pool.submit<Throwable?> {
                running.countDown()
                try { instance.invoke("hello", "null", call); null } catch (failure: Throwable) { failure }
            }
            assertTrue(running.await(1, TimeUnit.SECONDS))
            call.cancellation.cancel("test cancellation")
            assertTrue(result.get(2, TimeUnit.SECONDS) is PackCancelledException)
        } finally {
            pool.shutdownNow()
            instance.close()
        }
    }

    @Test fun oversizedCyclicAccessorFunctionAndNonPlainResultsFail() {
        val expressions = listOf(
            "new Array(1048578).join('x')",
            "(function(){var x={}; x.x=x; return x;})()",
            "(function(){var x={}; Object.defineProperty(x,'hidden',{value:function(){}}); return x;})()",
            "(function(){var a=[]; a.extra=1; return a;})()",
            "({get x(){while(true){} }})",
            "(function(){})", "new Date()", "NaN",
            "(function(){var x={}; for(var i=0;i<66;i++)x={x:x}; return x;})()",
        )
        for (expression in expressions) {
            val context = RuntimeTestContext("globalThis.__packExports={hello:function(){ return $expression; }};")
            val instance = load(context)
            assertThrows(PackException::class.java) { instance.invoke("hello", "null", context.call()) }
            instance.close()
        }
    }

    @Test(timeout = 10000) fun bridgeArgumentsAndJsonResultsRespectLimits() {
        val context = RuntimeTestContext("""
            globalThis.__packExports={hello:function(a) {
                if (a === 'large-arg') return __packApi.kvGet(new Array(1048578).join('x'));
                if (a === 'large-result') return __packApi.kvGet('large');
                if (a === 'large-json') return new Array(1048578).join('x');
                return __packApi.kvGet(a);
            }};
        """.trimIndent(), setOf(PackCapability.KV))
        context.values["large"] = "x".repeat(PackJsProtocol.MAX_JSON_CHARS + 1)
        val instance = load(context)
        val bridgeArgument = assertThrows(PackException::class.java) {
            instance.invoke("hello", "\"large-arg\"", context.call())
        }
        assertEquals(PackErrorCode.INVALID_REQUEST, bridgeArgument.diagnostic.code)
        val bridgeResult = assertThrows(PackException::class.java) {
            instance.invoke("hello", "\"large-result\"", context.call())
        }
        assertEquals(PackErrorCode.IMPORT_LIMIT_EXCEEDED, bridgeResult.diagnostic.code)
        val jsonResult = assertThrows(PackException::class.java) {
            instance.invoke("hello", "\"large-json\"", context.call())
        }
        assertEquals(PackErrorCode.IMPORT_LIMIT_EXCEEDED, jsonResult.diagnostic.code)
        assertTrue(instance.close().released)
    }

    @Test fun undefinedMapsToNullAndInvalidOrOversizedArgumentsFail() {
        val context = RuntimeTestContext("globalThis.__packExports={hello:function(){}};")
        val instance = load(context)
        assertEquals("null", instance.invoke("hello", "", context.call()))
        assertThrows(PackException::class.java) { instance.invoke("hello", "{bad}", context.call()) }
        val oversized = "\"" + "x".repeat(PackJsProtocol.MAX_JSON_CHARS) + "\""
        val failure = assertThrows(PackException::class.java) { instance.invoke("hello", oversized, context.call()) }
        assertEquals(PackErrorCode.IMPORT_LIMIT_EXCEEDED, failure.diagnostic.code)
        instance.close()
    }

    @Test fun closeIsIdempotentAndRejectsNewCalls() {
        val context = RuntimeTestContext("globalThis.__packExports={hello:function(){return 1;}};")
        val instance = load(context)
        assertTrue(instance.close().released)
        assertTrue(instance.close().released)
        assertTrue(instance.closed)
        assertThrows(PackException::class.java) { instance.invoke("hello", "null", context.call()) }
    }

    @Test(timeout = 10000) fun closeDoesNotClaimBlockedHostPrimitiveWasKilled() {
        val context = RuntimeTestContext("globalThis.__packExports={hello:function(){return __packApi.kvGet('x');}};", setOf(PackCapability.KV))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        context.onGet = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val instance = load(context)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val result = pool.submit<Throwable?> {
                try { instance.invoke("hello", "null", context.call()); null } catch (failure: Throwable) { failure }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val outcome = instance.close()
            assertFalse(outcome.released)
            assertTrue(outcome.stillResident)
            assertFalse(outcome.needsRestart)
            release.countDown()
            assertTrue(result.get(2, TimeUnit.SECONDS) is PackException)
            assertTrue(instance.close().released)
        } finally {
            release.countDown()
            pool.shutdownNow()
            instance.close()
        }
    }
}
