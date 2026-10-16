package borg.trikeshed.relaxfactory

import com.oracle.truffle.api.Assumption
import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.ExplodeLoop
import com.oracle.truffle.api.nodes.Node
import com.oracle.truffle.api.nodes.RootNode
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.IdentityHashMap
import java.util.concurrent.locks.ReentrantLock
import org.graalvm.polyglot.Context
import kotlin.concurrent.withLock

/** All paths execute adopted Truffle nodes. Scripts are registered by the host, never by RF input. */
class ConfixDynInvoke(val statementLimit: Long = 5_000_000L) : AutoCloseable {
    class Callable(val name: String, val body: (Array<Any?>) -> Any?, val resource: AutoCloseable? = null) {
        val assumption: Assumption = Truffle.getRuntime().createAssumption("confix-call:$name")
        fun close() { assumption.invalidate(); resource?.close() }
    }

    val lock = ReentrantLock()
    val calls = mutableMapOf<String, Callable>()
    val methods = mutableMapOf<Method, Callable>()
    // RootNode(null) captures the current sharing layer. A root is never transferred between
    // the host's null layer and a guest context, or between independently owned contexts.
    val roots = IdentityHashMap<Context?, Ast>()
    var closed = false
        private set
    val readRoot: ReadRoot get() = ast().readRoot
    val writeRoot: WriteRoot get() = ast().writeRoot
    val callRoot: CallRoot get() = ast().callRoot
    val readTarget: RootCallTarget get() = ast().readTarget
    val writeTarget: RootCallTarget get() = ast().writeTarget
    val callTarget: RootCallTarget get() = ast().callTarget

    class Ast {
        val readRoot = ReadRoot()
        val writeRoot = WriteRoot()
        val callRoot = CallRoot()
        val readTarget: RootCallTarget = readRoot.callTarget
        val writeTarget: RootCallTarget = writeRoot.callTarget
        val callTarget: RootCallTarget = callRoot.callTarget
    }

    fun ast(): Ast = lock.withLock {
        check(!closed) { "Confix dyninvoke is closed" }
        val context = try { Context.getCurrent() } catch (_: IllegalStateException) { null }
        roots.getOrPut(context) { Ast() }
    }

    fun get(bean: ConfixBean, name: String, type: Class<*> = Any::class.java): Any? = lock.withLock {
        check(!closed) { "Confix dyninvoke is closed" }
        readTarget.call(bean, name, type)
    }

    fun set(bean: ConfixBean, name: String, value: Any?) = lock.withLock {
        check(!closed) { "Confix dyninvoke is closed" }
        writeTarget.call(bean, name, value)
        Unit
    }

    fun register(name: String, callable: (Array<Any?>) -> Any?) = lock.withLock {
        install(Callable(name, callable))
    }

    fun registerScript(name: String, language: String, source: String) = lock.withLock {
        check(!closed) { "Confix dyninvoke is closed" }
        require(name.isNotBlank()) { "Confix callable name is empty" }
        val script = ConfixScript(language, source, name, statementLimit, this)
        install(Callable(name, script::call, script))
    }

    fun install(callable: Callable) = lock.withLock {
        check(!closed) { "Confix dyninvoke is closed" }
        require(callable.name.isNotBlank()) { "Confix callable name is empty" }
        if (calls[callable.name] === callable) return@withLock
        CompilerDirectives.transferToInterpreterAndInvalidate()
        calls.put(callable.name, callable)?.close()
        Unit
    }

    fun call(name: String, vararg args: Any?): Any? = lock.withLock {
        check(!closed) { "Confix dyninvoke is closed" }
        val callable = calls[name] ?: throw IllegalArgumentException("No registered Confix callable '$name'")
        @Suppress("UNCHECKED_CAST")
        callTarget.call(callable, args as Array<Any?>)
    }

    fun invoke(method: Method, receiver: Any?, args: Array<Any?>): Any? = lock.withLock {
        check(!closed) { "Confix dyninvoke is closed" }
        if (receiver is ConfixBean) {
            val callable = calls[method.toGenericString()] ?: calls[method.name]
                ?: throw IllegalArgumentException("No registered Confix callable '${method.name}'")
            callTarget.call(callable, arrayOf(receiver, *args))
        } else {
            val callable = methods.getOrPut(method) {
                Callable(method.toGenericString(), { values ->
                    try {
                        method.invoke(if (Modifier.isStatic(method.modifiers)) null else values[0], *values.copyOfRange(1, values.size))
                    } catch (e: InvocationTargetException) { throw e.targetException }
                })
            }
            callTarget.call(callable, arrayOf(receiver, *args))
        }
    }

    override fun close() {
        lock.withLock {
            if (closed) return
            closed = true
            var failure: Throwable? = null
            for (callable in calls.values + methods.values) try { callable.close() } catch (e: Throwable) {
                if (failure == null) failure = e else failure.addSuppressed(e)
            }
            calls.clear()
            methods.clear()
            roots.clear()
            failure?.let { throw it }
        }
    }

    class ReadRoot : RootNode(null) {
        @field:Node.Child var read = ReadNode()
        override fun execute(frame: VirtualFrame): Any? =
            read.execute(frame.arguments[0] as ConfixBean, frame.arguments[1] as String, frame.arguments[2] as Class<*>)
        override fun getName() = "confix-get"
    }

    class ReadCache(val shape: ConfixShape, val property: String, val type: Class<*>, val slot: Int, val assumption: Assumption) : Node()

    class ReadNode : Node() {
        @field:Node.Children var caches = emptyArray<ReadCache>()
        @field:CompilationFinal var generic = false
        var hits: Long = 0
            private set

        @ExplodeLoop
        fun execute(bean: ConfixBean, property: String, type: Class<*>): Any? {
            val state = bean.state
            for (cache in caches) if (state.shape === cache.shape && property == cache.property && type === cache.type) {
                if (cache.assumption.isValid) {
                    hits++
                    return bean.read(state, cache.slot, type)
                }
                CompilerDirectives.transferToInterpreterAndInvalidate()
                val refreshed = insert(ReadCache(state.shape, property, type, cache.slot, state.assumption))
                caches = caches.map { if (it === cache) refreshed else it }.toTypedArray()
                return bean.read(state, cache.slot, type)
            }
            if (!generic) {
                CompilerDirectives.transferToInterpreterAndInvalidate()
                val live = caches.filter { it.assumption.isValid }.toTypedArray()
                if (live.size < CACHE_LIMIT) {
                    val cache = insert(ReadCache(state.shape, property, type, state.shape.slot(property), state.assumption))
                    caches = live + cache
                    return bean.read(state, cache.slot, type)
                }
                generic = true
            }
            return read(bean, state, property, type)
        }

        @TruffleBoundary
        fun read(bean: ConfixBean, state: ConfixBean.State, property: String, type: Class<*>): Any? =
            bean.read(state, state.shape.slot(property), type)
    }

    class WriteRoot : RootNode(null) {
        @field:Node.Child var write = WriteNode()
        override fun execute(frame: VirtualFrame): Any? {
            write.execute(frame.arguments[0] as ConfixBean, frame.arguments[1] as String, frame.arguments[2])
            return null
        }
        override fun getName() = "confix-set"
    }

    class WriteNode : Node() {
        @TruffleBoundary
        fun execute(bean: ConfixBean, property: String, value: Any?) = bean.set(property, value)
    }

    class CallRoot : RootNode(null) {
        @field:Node.Child var invoke = CallNode()
        @Suppress("UNCHECKED_CAST")
        override fun execute(frame: VirtualFrame): Any? = invoke.execute(frame.arguments[0] as Callable, frame.arguments[1] as Array<Any?>)
        override fun getName() = "confix-invoke"
    }

    class CallCache(
        val callable: Callable,
        @field:CompilationFinal(dimensions = 1) val types: Array<Class<*>?>,
        @field:CompilationFinal(dimensions = 1) val shapes: Array<ConfixShape?>,
    ) : Node() {
        @ExplodeLoop
        fun accepts(callable: Callable, args: Array<Any?>): Boolean {
            if (callable !== this.callable || !callable.assumption.isValid || args.size != types.size) return false
            for (index in types.indices) {
                if (args[index]?.javaClass !== types[index]) return false
                if (args[index] is ConfixBean && (args[index] as ConfixBean).shape !== shapes[index]) return false
            }
            return true
        }

        fun execute(args: Array<Any?>): Any? = callable.body(args)
    }

    class CallNode : Node() {
        @field:Node.Children var caches = emptyArray<CallCache>()
        @field:CompilationFinal var generic = false
        var hits: Long = 0
            private set

        @ExplodeLoop
        fun execute(callable: Callable, args: Array<Any?>): Any? {
            for (cache in caches) if (cache.accepts(callable, args)) {
                hits++
                return cache.execute(args)
            }
            if (!generic) {
                CompilerDirectives.transferToInterpreterAndInvalidate()
                val live = caches.filter { it.callable.assumption.isValid }.toTypedArray()
                if (live.size < CACHE_LIMIT) {
                    val cache = insert(CallCache(callable, Array(args.size) { args[it]?.javaClass }, Array(args.size) { (args[it] as? ConfixBean)?.shape }))
                    caches = live + cache
                    return cache.execute(args)
                }
                generic = true
            }
            return invoke(callable, args)
        }

        @TruffleBoundary
        fun invoke(callable: Callable, args: Array<Any?>): Any? = callable.body(args)
    }

    companion object { const val CACHE_LIMIT = 4 }
}
