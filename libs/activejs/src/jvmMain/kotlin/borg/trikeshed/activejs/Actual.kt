package borg.trikeshed.activejs

import kotlin.coroutines.CoroutineContext
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess

/**
 * JVM implementation of GraalEcmaLauncher.
 * Launches GraalVM Polyglot Context with pointcut hooks.
 */
class GraalEcmaLauncherImpl : GraalEcmaLauncher {
    private var context: GraalEcmaContextImpl? = null
    
    override fun initialize(): GraalEcmaContext {
        val graalContext = GraalEcmaContextImpl()
        this.context = graalContext
        return graalContext
    }
    
    override fun shutdown() {
        context?.close()
        context = null
    }
}

/** JVM implementation of GraalEcmaContext wrapping GraalVM Polyglot Context. */
class GraalEcmaContextImpl : GraalEcmaContext {
    private val graalContext = Context.newBuilder("js")
        .allowAllAccess(true)
        .allowHostAccess(HostAccess.ALL)
        .build()
    
    override val polyglotContext: Any = graalContext
    
    override fun eval(script: String): Any = graalContext.eval("js", script)
    
    override fun getBinding(name: String): Any? {
        val bindings = graalContext.getBindings("js")
        return if (bindings.hasMember(name)) bindings.getMember(name).asHostObject() else null
    }
    
    override fun putBinding(name: String, value: Any) {
        val bindings = graalContext.getBindings("js")
        bindings.putMember(name, value)
    }
    
    override fun installPointcutHooks(target: Any, eventHandler: (PointcutEvent) -> Unit) {
        // Install JS-side pointcut hooks that call back to Kotlin handler
        val handlerProxy = object : ValueProxy {
            override fun invoke(args: Array<Any?>?): Any? {
                if (args != null && args.size >= 6) {
                    val event = PointcutEvent(
                        seq = (args[0] as? Number)?.toLong() ?: 0,
                        nano = (args[1] as? Number)?.toLong() ?: System.nanoTime(),
                        opcode = (args[2] as? Number)?.toInt() ?: 0,
                        phase = args[3] as? String ?: "UNKNOWN",
                        target = args[4] as? String ?: "",
                        value = args[5]
                    )
                    eventHandler(event)
                }
                return null
            }
        }
        
        putBinding("__trikeshedPointcutHandler", handlerProxy)
        
        // Install the hooking script
        eval("""
            (function() {
                const handler = __trikeshedPointcutHandler;
                const originalDefineProperty = Object.defineProperty;
                Object.defineProperty = function(obj, prop, desc) {
                    if (desc && (desc.get || desc.set)) {
                        const origGet = desc.get;
                        const origSet = desc.set;
                        if (origGet) {
                            desc.get = function() {
                                const val = origGet.apply(this, arguments);
                                handler.invoke([Date.now(), performance.now(), 0xA5, "BEFORE", this.constructor.name + "#" + prop, val]);
                                return val;
                            };
                        }
                        if (origSet) {
                            desc.set = function(v) {
                                handler.invoke([Date.now(), performance.now(), 0xA6, "AFTER", this.constructor.name + "#" + prop, v]);
                                return origSet.apply(this, arguments);
                            };
                        }
                    }
                    return originalDefineProperty.apply(this, arguments);
                };
                
                // Hook function calls
                const originalApply = Function.prototype.apply;
                Function.prototype.apply = function(thisArg, args) {
                    const name = this.name || "anonymous";
                    handler.invoke([Date.now(), performance.now(), 0x10, "BEFORE", name, args]);
                    const result = originalApply.apply(this, arguments);
                    handler.invoke([Date.now(), performance.now(), 0x10, "AFTER", name, result]);
                    return result;
                };
            })();
        """.trimIndent())
    }
    
    fun close() {
        graalContext.close()
    }
}

/** Proxy interface for GraalVM host callbacks. */
interface ValueProxy {
    fun invoke(args: Array<Any?>?): Any?
}