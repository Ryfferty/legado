package io.legado.pack.runtime.js

import io.legado.pack.api.*
import io.legado.pack.runtime.*
import org.htmlunit.corejs.javascript.*
import org.htmlunit.corejs.javascript.Function
import org.htmlunit.corejs.javascript.lc.type.TypeInfo
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

/** Independent interpreter: no global factory, source bindings or Java host objects. */
class JsPackRuntimeProvider : PackRuntimeProvider {
    override val kind = ComponentKind.JS
    override val trust = RuntimeTrust.SANDBOXED_JS
    // An instruction observer cannot terminate an engine built-in or a blocked host primitive.
    override val capabilities = RuntimeCapabilities(false, true, IsolationLevel.ENFORCED, IsolationLevel.ENFORCED, IsolationLevel.ENFORCED)
    private val factory = SandboxFactory()
    private val cache = LinkedHashMap<String, Script>(16, 0.75f, true)
    private var cachedChars = 0
    private val sourceSizes = HashMap<String, Int>()

    override fun probe(component: ComponentSpec, platform: PackPlatform): RuntimeAvailability =
        if (component is ComponentSpec.Js) RuntimeAvailability.Available else RuntimeAvailability.Unavailable(
            PackDiagnostic(PackErrorCode.UNSUPPORTED, "JS provider received ${component.kind.wire}"),
        )

    override fun load(ref: PackRef, component: ComponentSpec, context: PackContext, call: PackCallContext): PackInstance {
        if (component !is ComponentSpec.Js) runtimeFailure(PackErrorCode.UNSUPPORTED, "not a JS component")
        verifyBinding(ref, component, context, call)
        val source = decodeUtf8(readResource(context, component.entry, call, minOf(context.limits.maxSingleFileBytes, MAX_SOURCE_BYTES)))
        val instance = JsInstance(ref, component.id, context)
        try {
            instance.enter(call) { cx ->
                val scope = cx.initSafeStandardObjects(null, true)
                instance.scope = scope
                // initSafeStandardObjects omits LiveConnect. Pin dangerous names to undefined
                // as defense against engine-version changes; do not install JavaImporter.
                for (name in FORBIDDEN_GLOBALS) {
                    scope.defineProperty(name, Undefined.instance, ScriptableObject.READONLY or ScriptableObject.PERMANENT or ScriptableObject.DONTENUM)
                }
                val codec = cx.evaluateString(scope, JSON_CODEC, "pack-json-codec-v1", 1, null) as Scriptable
                instance.encoder = ScriptableObject.getProperty(codec, "encode") as Function
                instance.resolver = ScriptableObject.getProperty(codec, "resolve") as Function
                instance.installApi(cx, scope)
                val key = "sandbox-v1:${context.manifest.versionCode}:${ref.revision.value}:${sha256(source.toByteArray(Charsets.UTF_8))}"
                val script = synchronized(cache) { cache[key] } ?: run {
                    call.checkActive()
                    val compiled = cx.compileString(source, "pack:${ref.id.value}/${component.entry.value}", 1, null)
                    // Compilation is bounded in input size and checked before/after. Rhino's
                    // compiler does not provide instruction callbacks while parsing source.
                    call.checkActive()
                    synchronized(cache) {
                        if (!cache.containsKey(key)) {
                            cache[key] = compiled
                            sourceSizes[key] = source.length
                            cachedChars += source.length
                            while (cache.size > MAX_CACHE_ENTRIES || cachedChars > MAX_CACHE_CHARS) {
                                val first = cache.keys.first()
                                cache.remove(first)
                                cachedChars -= sourceSizes.remove(first) ?: 0
                            }
                        }
                    }
                    compiled
                }
                script.exec(cx, scope)
                call.checkActive()
                val exports = ScriptableObject.getProperty(scope, PackJsProtocol.EXPORTS_OBJECT)
                if (exports !is NativeObject) runtimeFailure(PackErrorCode.LOAD_FAILED, "__packExports must be a plain JS object")
                instance.exports = exports
                context.manifest.commands.filter { it.componentId == component.id }.forEach { command ->
                    instance.resolve(cx, command.name)
                }
            }
            return instance
        } catch (failure: Throwable) {
            instance.close()
            throw failure
        }
    }

    private inner class JsInstance(
        override val ref: PackRef,
        override val componentId: String,
        private val context: PackContext,
    ) : PackInstance {
        private val closeRequested = AtomicBoolean()
        private val lock = ReentrantLock()
        private var cleaned = false
        override val closed: Boolean get() = closeRequested.get()
        var scope: TopLevel? = null
        var exports: NativeObject? = null
        var encoder: Function? = null
        var resolver: Function? = null
        private val primitives = ContextPrimitives(context)

        fun <T> enter(call: PackCallContext, action: (Context) -> T): T {
            call.checkActive()
            if (closed) runtimeFailure(PackErrorCode.PACK_DISABLED, "JS instance is closed")
            if (lock.isHeldByCurrentThread || !lock.tryLock()) runtimeFailure(PackErrorCode.LOAD_FAILED, "concurrent/reentrant JS use is not admitted")
            try {
                val current = Context.getCurrentContext()
                if (current != null && (current !is SandboxContext || current.owner !== factory)) {
                    runtimeFailure(PackErrorCode.LOAD_FAILED, "pack JS must run outside the book-source Rhino context")
                }
                val cx = factory.enterContext() as SandboxContext
                val previous = cx.frame
                cx.frame = Frame(call, closeRequested)
                return try {
                    call.checkActive()
                    action(cx).also {
                        cx.frame!!.check()
                    }
                } catch (abort: SandboxAbort) {
                    // Bridge wrappers catch Exception, so the original failure
                    // arrives here instead of reaching the typed handlers
                    // below; unwrap it into a typed PackException instead of
                    // rethrowing it raw (which would surface as INTERNAL).
                    throw typedFailure(abort.failure)
                } catch (limit: JsCodecLimitExceeded) {
                    runtimeFailure(
                        PackErrorCode.IMPORT_LIMIT_EXCEEDED,
                        "JS JSON limit exceeded: ${limit.original.details().take(1024)}",
                        limit.original,
                    )
                } catch (e: RhinoException) {
                    // No message sniffing: script-controlled text must never
                    // select the wire error code. Framework limits are typed
                    // through JsCodecLimitExceeded at the codec call sites.
                    runtimeFailure(PackErrorCode.LOAD_FAILED, "JS execution failed: ${e.details().take(1024)}", e)
                } catch (e: StackOverflowError) {
                    runtimeFailure(PackErrorCode.LOAD_FAILED, "JS interpreter stack limit exceeded", e)
                } finally {
                    cx.frame = previous
                    cx.close()
                }
            } finally {
                if (closed) cleanup()
                lock.unlock()
            }
        }

        override fun invoke(command: String, argJson: String, call: PackCallContext): String {
            verifyCommand(context, componentId, command, call)
            return enter(call) { cx ->
                val arg = toJs(cx, RuntimeJson.parse(argJson, call), scope!!)
                val result = resolve(cx, command).call(cx, scope!!, exports, arrayOf(arg))
                call.checkActive()
                val json = encodeWithCodec(cx, result, PackJsProtocol.MAX_JSON_CHARS)
                RuntimeJson.parse(json, call)
                json
            }
        }

        /**
         * Invokes the framework JSON codec. Codec failures are classified by
         * error type at this framework-owned call site: the codec only throws
         * its own RangeError/TypeError, so a RangeError is one of the
         * framework's limits and becomes the typed [JsCodecLimitExceeded]
         * marker. Everything else keeps the generic RhinoException mapping.
         */
        private fun encodeWithCodec(cx: Context, value: Any?, limit: Int): String {
            val raw = try {
                encoder!!.call(cx, scope!!, null, arrayOf(value, limit))
            } catch (e: RhinoException) {
                if (codecErrorIsRangeError(e)) throw JsCodecLimitExceeded(e)
                throw e
            }
            if (raw !is CharSequence) {
                runtimeFailure(PackErrorCode.INTERNAL, "pack JSON codec returned a non-string")
            }
            return raw.toString()
        }

        private fun codecErrorIsRangeError(e: RhinoException): Boolean = when (e) {
            is EcmaError -> "RangeError" == e.getName()
            is JavaScriptException -> {
                val value = e.getValue()
                value is Scriptable && "RangeError" == (ScriptableObject.getProperty(value, "name") as? String)
            }
            else -> false
        }

        /**
         * Fallback typing for failures unwrapped from [SandboxAbort]: already
         * typed exceptions (including cooperative cancellation/timeout, which
         * must keep their CancellationException semantics) pass through; the
         * framework's codec limit marker maps to IMPORT_LIMIT_EXCEEDED;
         * everything else becomes a typed LOAD_FAILED PackException instead of
         * escaping as an untyped internal error.
         */
        private fun typedFailure(failure: Exception): Exception = when (failure) {
            is PackException -> failure
            is PackCancelledException -> failure
            is PackTimeoutException -> failure
            is JsCodecLimitExceeded -> runtimeFailure(
                PackErrorCode.IMPORT_LIMIT_EXCEEDED,
                "JS JSON limit exceeded: ${failure.original.details().take(1024)}",
                failure.original,
            )
            is RhinoException -> runtimeFailure(
                PackErrorCode.LOAD_FAILED,
                "JS execution failed: ${failure.details().take(1024)}",
                failure,
            )
            else -> runtimeFailure(
                PackErrorCode.LOAD_FAILED,
                "JS bridge failed: ${failure.javaClass.simpleName}: ${failure.message?.take(256)}",
                failure,
            )
        }

        fun resolve(cx: Context, command: String): Function {
            return resolver!!.call(cx, scope!!, null, arrayOf(exports, PackJsProtocol.commandExportName(command))) as? Function
                ?: runtimeFailure(PackErrorCode.COMMAND_NOT_FOUND, "JS command '$command' has no own function export")
        }

        fun installApi(cx: Context, scope: TopLevel) {
            val api = cx.newObject(scope) as ScriptableObject
            api.prototype = null
            fun bridge(name: String, action: (Array<Any?>, PackCallContext) -> Any?) {
                val function = object : BaseFunction() {
                    override fun getFunctionName(): String = name
                    override fun call(cx: Context, scope: VarScope, thisObj: Scriptable?, args: Array<Any?>): Any? {
                        val frame = (cx as SandboxContext).frame ?: throw SandboxAbort(IllegalStateException("missing pack call"))
                        return try {
                            frame.check()
                            action(args, frame.call).also { frame.check() }
                        } catch (e: Exception) {
                            // Never let Rhino wrap a Java Throwable as a catchable JS host object.
                            throw SandboxAbort(e)
                        }
                    }
                }
                function.parentScope = scope
                function.prototype = null
                function.sealObject()
                ScriptableObject.defineProperty(api, name, function, ScriptableObject.READONLY or ScriptableObject.PERMANENT)
            }
            fun string(args: Array<Any?>, index: Int): String {
                val value = args.getOrNull(index)
                if (value !is CharSequence || value.length > PackJsProtocol.MAX_JSON_CHARS) {
                    runtimeFailure(PackErrorCode.INVALID_REQUEST, "primitive argument $index must be a bounded string")
                }
                return value.toString()
            }

            /** Explicit JS `undefined` (and a missing argument) mean "absent". */
            fun optionalString(args: Array<Any?>, index: Int): String? {
                val value = args.getOrNull(index)
                if (value == null || value === Undefined.instance) return null
                if (value !is CharSequence || value.length > PackJsProtocol.MAX_JSON_CHARS) {
                    runtimeFailure(PackErrorCode.INVALID_REQUEST, "primitive argument $index must be a bounded string")
                }
                return value.toString()
            }
            bridge("kvGet") { a, _ -> primitives.kvGet(string(a, 0))?.also {
                if (it.length > PackJsProtocol.MAX_JSON_CHARS) runtimeFailure(PackErrorCode.IMPORT_LIMIT_EXCEEDED, "KV result limit exceeded")
            } }
            bridge("kvPut") { a, _ -> primitives.kvPut(string(a, 0), string(a, 1)); Undefined.instance }
            bridge("kvRemove") { a, _ -> primitives.kvRemove(string(a, 0)); Undefined.instance }
            bridge("log") { a, _ -> primitives.log(string(a, 0), string(a, 1)); Undefined.instance }
            bridge("invokeComponent") { a, active ->
                val json = primitives.invokeComponent(string(a, 0), string(a, 1), string(a, 2), active)
                RuntimeJson.parse(json, active)
                json
            }
            bridge("http") { a, active ->
                // Admission first: an undeclared network capability must fail
                // before the request is serialized (no network I/O happens
                // either way, so "undeclared means disabled" is unchanged).
                primitives.ensureHttpAdmitted()
                val activeCx = Context.getCurrentContext()!!
                val json = encodeWithCodec(activeCx, a.getOrNull(0), PackJsHttpJson.MAX_HTTP_JSON_CHARS)
                toJs(activeCx, RuntimeJson.parse(primitives.http(json, active), active, PackJsHttpJson.MAX_HTTP_JSON_CHARS), scope)
            }
            bridge("cryptoDigest") { a, _ -> boundedResult(primitives.cryptoDigest(string(a, 0), string(a, 1))) }
            bridge("cryptoHmac") { a, _ -> boundedResult(primitives.cryptoHmac(string(a, 0), string(a, 1), string(a, 2))) }
            bridge("cryptoCipher") { a, _ ->
                boundedResult(primitives.cryptoCipher(string(a, 0), string(a, 1), optionalString(a, 2), string(a, 3)))
            }
            // Internal guard is not installed on globalThis; the encoder captures it.
            api.sealObject()
            scope.defineProperty(PackJsProtocol.API_OBJECT, api, ScriptableObject.READONLY or ScriptableObject.PERMANENT)
        }

        private fun boundedResult(value: String): String {
            if (value.length > PackJsProtocol.MAX_JSON_CHARS) runtimeFailure(PackErrorCode.IMPORT_LIMIT_EXCEEDED, "primitive result limit exceeded")
            return value
        }

        override fun close(): CloseOutcome {
            closeRequested.set(true)
            if (lock.isHeldByCurrentThread || !lock.tryLock()) return CloseOutcome.resident("JS invocation/primitive is still active", needsRestart = false)
            return try {
                cleanup()
                CloseOutcome.released()
            } finally {
                lock.unlock()
            }
        }

        private fun cleanup() {
            if (!cleaned) {
                scope = null
                exports = null
                encoder = null
                resolver = null
                cleaned = true
            }
        }
    }

    private class Frame(val call: PackCallContext, val closed: AtomicBoolean) {
        fun check() {
            try {
                call.checkActive()
                if (closed.get()) runtimeFailure(PackErrorCode.PACK_DISABLED, "JS instance closed while executing")
            } catch (failure: Exception) {
                throw SandboxAbort(failure)
            }
        }
    }

    // Error(String?, Throwable?, boolean, boolean) needs API 24; keep the
    // no-stack-trace semantics on minSdk 23 by overriding fillInStackTrace.
    private class SandboxAbort(val failure: Exception) : Error(failure) {
        override fun fillInStackTrace(): Throwable = this
    }

    /**
     * Private typed marker: the framework JSON codec hit one of its own
     * limits. Thrown only at framework-owned codec call sites, so error-code
     * classification never depends on script-controlled message text.
     */
    private class JsCodecLimitExceeded(val original: RhinoException) : RuntimeException(original)
    private class SandboxContext(val owner: SandboxFactory) : Context(owner) {
        var frame: Frame? = null
    }

    private class SandboxFactory : ContextFactory() {
        override fun makeContext(): Context = SandboxContext(this).apply {
            languageVersion = Context.VERSION_ES6
            setInterpretedMode(true)
            setClassShutter { false }
            wrapFactory = NoJavaObjects
            instructionObserverThreshold = 1000
            maximumInterpreterStackDepth = 256
        }

        override fun hasFeature(cx: Context, featureIndex: Int): Boolean = when (featureIndex) {
            Context.FEATURE_E4X, Context.FEATURE_ENHANCED_JAVA_ACCESS, Context.FEATURE_ENABLE_JAVA_MAP_ACCESS -> false
            else -> super.hasFeature(cx, featureIndex)
        }

        override fun observeInstructionCount(cx: Context, instructionCount: Int) {
            (cx as SandboxContext).frame?.check() ?: throw SandboxAbort(IllegalStateException("missing instruction budget"))
        }
    }

    private object NoJavaObjects : WrapFactory() {
        private fun denied(): Nothing = throw SandboxAbort(PackException(PackDiagnostic(PackErrorCode.PERMISSION_DENIED, "Java objects are forbidden in pack JS")))
        override fun wrapAsJavaObject(cx: Context, scope: VarScope?, javaObject: Any, staticType: TypeInfo?): Scriptable = denied()
        override fun wrapJavaClass(cx: Context, scope: VarScope, javaClass: Class<*>): Scriptable = denied()
        override fun wrapNewObject(cx: Context, scope: VarScope, javaObject: Any): Scriptable = denied()
    }

    private fun toJs(cx: Context, value: JsonValue, scope: TopLevel): Any? = when (value) {
        JsonValue.Null -> null
        is JsonValue.Str -> value.value
        is JsonValue.Bool -> value.value
        is JsonValue.Num -> value.raw.toDouble().also {
            if (!it.isFinite()) runtimeFailure(PackErrorCode.INVALID_REQUEST, "JSON number cannot be represented by JavaScript")
        }
        is JsonValue.Arr -> cx.newArray(scope, value.items.map { toJs(cx, it, scope) }.toTypedArray())
        is JsonValue.Obj -> (cx.newObject(scope) as ScriptableObject).also { obj ->
            value.entries.forEach { (key, item) -> ScriptableObject.defineProperty(obj, key, toJs(cx, item, scope), 0) }
        }
    }

    companion object {
        private const val MAX_SOURCE_BYTES = 2_097_152L
        private const val MAX_CACHE_ENTRIES = 32
        private const val MAX_CACHE_CHARS = 8_388_608
        private val FORBIDDEN_GLOBALS = arrayOf(
            "Packages", "java", "javax", "android", "com", "org", "edu", "net", "JavaAdapter", "JavaImporter",
            "getClass", "load", "loadClass", "readFile", "readUrl", "quit", "print", "Context",
        )

        // Capture built-ins before package initialization. No toJSON/getters/coercions
        // execute while copying a result. Every loop runs in the observed interpreter.
        // Array index keys must go through String(i): the Rhino fork hands '' + i to
        // getOwnPropertyDescriptor as a ConsString, which fails the property lookup.
        private val JSON_CODEC = """
            (function () {
                var names = Object.getOwnPropertyNames, symbols = Object.getOwnPropertySymbols;
                var desc = Object.getOwnPropertyDescriptor, proto = Object.getPrototypeOf;
                var isArray = Array.isArray, quote = JSON.stringify;
                var objectProto = Object.prototype, arrayProto = Array.prototype;
                return {
                    resolve: function (exports, name) {
                        var d = desc(exports, name);
                        return d && !d.get && !d.set && typeof d.value === 'function' ? d.value : null;
                    },
                    encode: function (value, limit) {
                        var chunks = [], length = 0, stack = [], elements = 0;
                        function add(s) {
                            length += s.length;
                            if (length > limit) throw new RangeError('pack JSON character limit exceeded');
                            chunks[chunks.length] = s;
                        }
                        function walk(v, depth) {
                            if (depth > 64 || ++elements > 100000) throw new RangeError('pack JSON structure limit exceeded');
                            if (v === null || typeof v === 'undefined') { add('null'); return; }
                            if (typeof v === 'string') {
                                if (v.length > limit) throw new RangeError('pack JSON string limit exceeded');
                                add(quote(v)); return;
                            }
                            if (typeof v === 'boolean') { add(v ? 'true' : 'false'); return; }
                            if (typeof v === 'number') {
                                if (v !== v || v === Infinity || v === -Infinity) throw new TypeError('non-finite JSON number');
                                add(quote(v)); return;
                            }
                            if (typeof v !== 'object') throw new TypeError('non-JSON pack result');
                            var arr = isArray(v), p = proto(v);
                            if (p !== null && p !== (arr ? arrayProto : objectProto)) throw new TypeError('non-plain pack result');
                            if (symbols && symbols(v).length) throw new TypeError('symbol properties are not JSON');
                            for (var s = 0; s < stack.length; ++s) if (stack[s] === v) throw new TypeError('cyclic pack result');
                            stack[stack.length] = v;
                            var keys = names(v), first = true, d, k;
                            if (keys.length > 100001) throw new RangeError('too many JSON fields');
                            for (var x = 0; x < keys.length; ++x) {
                                k = keys[x]; d = desc(v, k);
                                if (d.get || d.set) throw new TypeError('JSON accessors are forbidden');
                                if (arr) {
                                    if (k === 'length') continue;
                                    var index = +k;
                                    if (index < 0 || index >= v.length || Math.floor(index) !== index || '' + index !== k) {
                                        throw new TypeError('non-index array property is not JSON');
                                    }
                                } else if (!d.enumerable) {
                                    throw new TypeError('non-enumerable object property is not JSON');
                                }
                            }
                            add(arr ? '[' : '{');
                            if (arr) {
                                if (v.length > 100000) throw new RangeError('JSON array limit exceeded');
                                for (var i = 0; i < v.length; ++i) {
                                    if (i) add(',');
                                    d = desc(v, String(i));
                                    walk(d ? d.value : null, depth + 1);
                                }
                            } else {
                                for (var j = 0; j < keys.length; ++j) {
                                    k = keys[j]; d = desc(v, k);
                                    if (!d.enumerable) continue;
                                    if (!first) add(','); first = false;
                                    add(quote(k)); add(':'); walk(d.value, depth + 1);
                                }
                            }
                            add(arr ? ']' : '}'); stack.length--;
                        }
                        walk(value, 0);
                        var output = '';
                        for (var i = 0; i < chunks.length; ++i) output += chunks[i];
                        return output;
                    }
                };
            })()
        """.trimIndent()
    }
}
