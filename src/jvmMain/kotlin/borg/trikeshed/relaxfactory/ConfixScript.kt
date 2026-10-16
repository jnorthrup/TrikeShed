package borg.trikeshed.relaxfactory

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.PolyglotException
import org.graalvm.polyglot.ResourceLimits
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.Value
import org.graalvm.polyglot.io.IOAccess
import org.graalvm.polyglot.proxy.ProxyArray
import org.graalvm.polyglot.proxy.ProxyObject
import org.graalvm.polyglot.proxy.Proxy
import kotlin.concurrent.withLock

/** A configured expression that evaluates to an executable in its own owned language context. */
class ConfixScript(language: String, source: String, name: String, statementLimit: Long, val bridge: ConfixDynInvoke) : AutoCloseable {
    val context: Context
    val current: Context
    val executable: Value
    var closed = false
        private set

    init {
        val builder = Context.newBuilder(language)
            .allowHostAccess(HostAccess.NONE)
            .allowHostClassLookup { false }
            .allowIO(IOAccess.NONE)
            .option("engine.WarnInterpreterOnly", "false")
        // GraalPy does not safely support statement-limit cancellation. Other languages still
        // execute through their installed engine; only JS has the established statement bound.
        if (language == "js" && statementLimit > 0) builder.resourceLimits(
            ResourceLimits.newBuilder().statementLimit(statementLimit) { true }.build(),
        )
        context = builder.build()
        try {
            executable = context.eval(Source.newBuilder(language, source, name).build())
            require(executable.canExecute()) { "Confix source '$name' did not return an executable" }
            context.enter()
            try { current = Context.getCurrent() } finally { context.leave() }
        } catch (e: Throwable) {
            context.close(true)
            closed = true
            throw e
        }
    }

    fun call(args: Array<Any?>): Any? = bridge.lock.withLock {
        check(!closed) { "Confix script context is closed" }
        context.resetLimits()
        try {
            ConfixPolyglot.from(executable.execute(*args.map { ConfixPolyglot.proxy(it, bridge) }.toTypedArray()))
        } catch (e: PolyglotException) {
            if (e.isCancelled || e.isInternalError || e.isResourceExhausted) close()
            throw e
        }
    }

    override fun close() {
        bridge.lock.withLock {
            if (closed) return
            closed = true
            try { context.close(true) } finally { bridge.roots.remove(current) }
        }
    }
}

/** Proxies preserve a Confix receiver and path so guest writes update the same indexed document. */
object ConfixPolyglot {
    fun proxy(value: Any?, bridge: ConfixDynInvoke): Any? = when (value) {
        is ConfixBean -> ObjectProxy(value, emptyList(), bridge)
        is Map<*, *> -> ProxyObject.fromMap(value.entries.associate { (key, field) ->
            require(key is String) { "Polyglot object field is not a string" }
            key to proxy(field, bridge)
        })
        is Iterable<*> -> ProxyArray.fromArray(*value.map { proxy(it, bridge) }.toTypedArray())
        is Array<*> -> ProxyArray.fromArray(*value.map { proxy(it, bridge) }.toTypedArray())
        is Char -> value.toString()
        is Enum<*> -> value.name
        is java.math.BigDecimal -> value.toString()
        is java.util.Date -> value.time
        null, is String, is Boolean, is Number -> value
        else -> throw IllegalArgumentException("Unsupported Confix argument ${value.javaClass.name}")
    }

    fun member(bean: ConfixBean, path: List<Any>, bridge: ConfixDynInvoke): Any? = when (val value = when {
        path.isEmpty() -> bean.fields()
        path.size == 1 -> bridge.get(bean, path.single() as String)
        else -> bean.at(path)
    }) {
        is Map<*, *> -> ObjectProxy(bean, path, bridge)
        is List<*> -> ArrayProxy(bean, path, bridge)
        else -> proxy(value, bridge)
    }

    class ObjectProxy(val bean: ConfixBean, val path: List<Any>, val bridge: ConfixDynInvoke) : ProxyObject {
        fun fields(): Map<*, *> = if (path.isEmpty()) bean.fields() else bean.at(path) as? Map<*, *>
            ?: throw IllegalStateException("Confix object path no longer names an object")

        override fun getMember(key: String): Any? = member(bean, path + key, bridge)
        override fun getMemberKeys(): Any = ProxyArray.fromArray(*fields().keys.toTypedArray())
        override fun hasMember(key: String): Boolean = if (path.isEmpty()) bean.shape.slot(key) >= 0 else fields().containsKey(key)
        override fun putMember(key: String, value: Value) =
            if (path.isEmpty()) bridge.set(bean, key, from(value)) else bean.put(path + key, from(value))
        override fun removeMember(key: String): Boolean = bean.remove(path + key)
    }

    class ArrayProxy(val bean: ConfixBean, val path: List<Any>, val bridge: ConfixDynInvoke) : ProxyArray {
        fun elements(): List<*> = bean.at(path) as? List<*>
            ?: throw IllegalStateException("Confix array path no longer names an array")

        fun index(index: Long): Int {
            require(index >= 0 && index < elements().size) { "Confix array index out of bounds" }
            return index.toInt()
        }

        override fun get(index: Long): Any? = member(bean, path + index(index), bridge)
        override fun getSize(): Long = elements().size.toLong()
        override fun set(index: Long, value: Value) = bean.put(path + index(index), from(value))
        override fun remove(index: Long): Boolean = bean.remove(path + index(index))
    }

    fun from(value: Value): Any? = when {
        value.isNull -> null
        value.isProxyObject -> when (val proxy = value.asProxyObject<Proxy>()) {
            is ObjectProxy -> if (proxy.path.isEmpty()) proxy.bean else proxy.bean.at(proxy.path)
            is ArrayProxy -> proxy.bean.at(proxy.path)
            is ProxyArray -> List(value.arraySize.toInt()) { from(value.getArrayElement(it.toLong())) }
            is ProxyObject -> value.memberKeys.associateWith { from(value.getMember(it)) }
            else -> throw IllegalArgumentException("Unsupported polyglot proxy result")
        }
        value.isBoolean -> value.asBoolean()
        value.isString -> value.asString()
        value.fitsInInt() -> value.asInt()
        value.fitsInLong() -> value.asLong()
        value.fitsInBigInteger() -> value.asBigInteger()
        value.fitsInDouble() -> value.asDouble()
        value.hasArrayElements() -> {
            require(value.arraySize <= Int.MAX_VALUE) { "Polyglot result array exceeds JVM indexing" }
            List(value.arraySize.toInt()) { from(value.getArrayElement(it.toLong())) }
        }
        value.hasMembers() -> value.memberKeys.associateWith { from(value.getMember(it)) }
        else -> throw IllegalArgumentException("Polyglot result is not a Confix value")
    }
}
