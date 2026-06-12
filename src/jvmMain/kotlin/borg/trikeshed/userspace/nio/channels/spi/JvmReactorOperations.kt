package borg.trikeshed.userspace.nio.channels.spi

import borg.trikeshed.userspace.reactor.Interest
import kotlin.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWith
import kotlin.time.Duration
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.spi.SelectorProvider as JdkSelectorProvider
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.lang.Thread
import java.util.concurrent.CompletableFuture

/**
 * JVM implementation of [ReactorOperations] using Java NIO Selector.
 *
 * Single-threaded event loop: register interests -> select -> signal ready.
 * Designed to be driven by the coroutine scheduler via suspend poll().
 *
 * NO GLOBAL STATE - each instance owns its Selector and registry.
 * Thread-safe via ConcurrentHashMap; Selector runs on a dedicated thread.
 */
class JvmReactorOperations(
    private val selector: Selector = java.nio.channels.spi.SelectorProvider.provider().openSelector(),
) : ReactorOperations {

    // fd -> (Channel, interests, userData)
    private val fdRegistry = ConcurrentHashMap<Int, RegistryEntry>()
    private val fdCounter = AtomicInteger(1000)

    override fun register(fd: Int, interests: Set<Interest>, userData: Long = 0L) {
        val entry = fdRegistry[fd] ?: return
        fdRegistry[fd] = RegistryEntry(entry.channel, interests, userData)

        var ops = 0
        if (Interest.READ in interests) ops = ops or SelectionKey.OP_READ
        if (Interest.WRITE in interests) ops = ops or SelectionKey.OP_WRITE
        if (Interest.ACCEPT in interests) ops = ops or SelectionKey.OP_ACCEPT
        if (Interest.CONNECT in interests) ops = ops or SelectionKey.OP_CONNECT

        try {
            entry.channel.register(selector, ops)
        } catch (e: Exception) {
            selector.keys().firstOrNull { it.channel() == entry.channel }?.cancel()
            entry.channel.register(selector, ops)
        }
    }

    override fun deregister(fd: Int) {
        fdRegistry.remove(fd)?.channel?.let { ch ->
            selector.keys().firstOrNull { it.channel() == ch }?.cancel()
        }
    }

    override suspend fun poll(timeout: Duration): List<ReactorSignal> = suspendCancellableCoroutine { cont ->
        val future = java.util.concurrent.CompletableFuture<List<ReactorSignal>>()

        Thread(Runnable {
            val ms = if (timeout.isInfinite()) 0L else timeout.inWholeMilliseconds
            var n: Int
            try {
                n = if (timeout.isInfinite()) selector.selectNow() else selector.select(timeout.inWholeMilliseconds)
            } catch (e: Exception) {
                future.completeExceptionally(e)
                return
            }

            if (n == 0) {
                future.complete(emptyList())
                return
            }

            val ready = selector.selectedKeys().mapNotNull { key ->
                val fdEntry = fdRegistry.entries.firstOrNull { it.value.channel == key.channel() }
                val fd = fdEntry?.key ?: return@mapNotNull null
                val sig = mutableSetOf<Interest>()
                if (key.isReadable) sig.add(Interest.READ)
                if (key.isWritable) sig.add(Interest.WRITE)
                if (key.isAcceptable) sig.add(Interest.ACCEPT)
                if (key.isConnectable) sig.add(Interest.CONNECT)
                val userData = fdRegistry[fd]?.userData ?: 0L
                ReactorSignal(fd, sig, userData)
            }.toList()
            selector.selectedKeys().clear()
            future.complete(ready)
        }.start()

        future.get()
    }

    /** Bind a raw channel to an fd for reactor tracking. Returns allocated fd. */
    fun bindChannel(ch: java.nio.channels.SelectableChannel, interests: Set<Interest>, userData: Long = 0L): Int {
        val fd = fdCounter.incrementAndGet()
        fdRegistry[fd] = RegistryEntry(ch, interests, userData)
        register(fd, interests, userData)
        return fd
    }

    /** Close the Graal context and clean up. */
    fun close() {
        graalContext.close()
    }

    /** Get or assign a method index for a callsite. */
    fun getMethodIndex(callsiteKey: String): Int {
        return methodIndexCache.computeIfAbsent(callsiteKey) { methodIndexCache.size }
    }

    /** Get or assign a template index for a pattern. */
    fun getTemplateIndex(patternKey: String): Int {
        return templateIndexCache.computeIfAbsent(patternKey) { templateIndexCache.size }
    }

    /** Get next sequence number. */
    fun nextSeq(): Long = sequenceCounter.incrementAndGet()

    /** Bind the emitter to Python's pointcut_instrument module. */
    fun bindPythonInstrumentation(producer: PointcutEventProducer) {
        try {
            val ctx = graalContext
            // First ensure the module is loaded
            ctx.eval("python", "import pointcut_instrument")
            // Then bind the emitter
            ctx.eval("python", "pointcut_instrument.set_emitter(pointcutEmitter)")
        } catch (e: Exception) {
            // Python not available
            println("Python instrumentation bind failed: ${e.message}")
        }
    }

    /** Python instrumentation module - loaded from resource file. */
    val PYTHON_INSTRUMENTATION_MODULE: String
        get() = loadPythonInstrumentationModule()

    /** Load Python instrumentation module from resource file. */
    fun loadPythonInstrumentationModule(): String {
        val stream = GraalPointcutHarness::class.java.getResourceAsStream("/pointcut_instrument.py")
            ?: throw IllegalStateException("Python instrumentation module not found")
        return stream.readBytes().decodeToString()
    }

    /** Install Python-side automatic pointcut instrumentation module from inline string. */
    private fun installPythonInstrumentation() {
        try {
            val ctx: org.graalvm.polyglot.Context = graalContext
            ctx.eval("python", PYTHON_INSTRUMENTATION_MODULE)
        } catch (e: Exception) {
            // Python not available, skip
        }
    }

    /** Bind pointcut emitter to context manually (for custom setup). */
    fun bindPointcutEmitter(producer: PointcutEventProducer) {
        graalContext.bindPointcutEmitter(this, producer)
    }

    /**
     * Evaluate code in a Graal language.
     * Language IDs: "js", "ruby", "python", "wasm", "llvm"
     * Returns the result converted to a Kotlin/JVM type.
     */
    fun eval(languageId: String, source: String): Any? {
        val ctx: org.graalvm.polyglot.Context = graalContext
        val value = ctx.eval(languageId, source)
        if (value == null || value.isNull) return null

        // Try numeric conversions in order of preference
        if (value.isNumber) {
            try { return value.asInt() } catch (e: Exception) {}
            try { return value.asLong() } catch (e: Exception) {}
            try { return value.asDouble() } catch (e: Exception) {}
        }

        return when {
            value.isString -> value.asString()
            value.isBoolean -> value.asBoolean()
            value.isHostObject -> value.asHostObject()
            else -> value.toString()
        }
    }

    /** Close the Graal context and clean up. */
    fun close() {
        graalContext.close()
    }

    /** Get or assign a method index for a callsite. */
    fun getMethodIndex(callsiteKey: String): Int {
        return methodIndexCache.computeIfAbsent(callsiteKey) { methodIndexCache.size }
    }

    /** Get or assign a template index for a pattern. */
    fun getTemplateIndex(patternKey: String): Int {
        return templateIndexCache.computeIfAbsent(patternKey) { templateIndexCache.size }
    }

    /** Get next sequence number. */
    fun nextSeq(): Long = sequenceCounter.incrementAndGet()

    companion object {
        // Phase constants
        const val PHASE_BEFORE = 0.toByte()
        const val PHASE_AFTER = 1.toByte()

        // Opcode constants (matching xvm FieldSynapse wire protocol)
        const val OP_L_GET = 0xA5.toByte()
        const val OP_L_SET = 0xA6.toByte()
        const val OP_P_GET = 0xA7.toByte()
        const val OP_P_SET = 0xA8.toByte()

        /** Python instrumentation module - loaded from resource file. */
        fun loadPythonInstrumentationModule(): String {
            val stream = GraalPointcutHarness::class.java.getResourceAsStream("/pointcut_instrument.py")
                ?: throw IllegalStateException("Python instrumentation module not found")
            return stream.readBytes().decodeToString()
        }

        /** Python instrumentation module - loaded from resource file. */
        val PYTHON_INSTRUMENTATION_MODULE: String
            get() = loadPythonInstrumentationModule()

        /** Load Python instrumentation module from resource file. */
        fun loadPythonInstrumentationModule(): String {
            val stream = GraalPointcutHarness::class.java.getResourceAsStream("/pointcut_instrument.py")
                ?: throw IllegalStateException("Python instrumentation module not found")
            return stream.readBytes().decodeToString()
        }

        /** Install Python-side automatic pointcut instrumentation module from inline string. */
        private fun installPythonInstrumentation() {
            try {
                val ctx: org.graalvm.polyglot.Context = GraalPointcutHarness().graalContext
                ctx.eval("python", PYTHON_INSTRUMENTATION_MODULE)
            } catch (e: Exception) {
                // Python not available, skip
            }
        }

        /** Bind pointcut emitter to context manually (for custom setup). */
        fun bindPointcutEmitter(producer: PointcutEventProducer) {
            GraalPointcutHarness().graalContext.bindPointcutEmitter(GraalPointcutHarness(), producer)
        }

        /**
         * Evaluate code in a Graal language.
         * Language IDs: "js", "ruby", "python", "wasm", "llvm"
         * Returns the result converted to a Kotlin/JVM type.
         */
        fun eval(languageId: String, source: String): Any? {
            val ctx: org.graalvm.polyglot.Context = GraalPointcutHarness().graalContext
            val value = ctx.eval(languageId, source)
            if (value == null || value.isNull) return null

            // Try numeric conversions in order of preference
            if (value.isNumber) {
                try { return value.asInt() } catch (e: Exception) {}
                try { return value.asLong() } catch (e: Exception) {}
                try { return value.asDouble() } catch (e: Exception) {}
            }

            return when {
                value.isString -> value.asString()
                value.isBoolean -> value.asBoolean()
                value.isHostObject -> value.asHostObject()
                else -> value.toString()
            }
        }

        /** Close the Graal context and clean up. */
        fun close() {
            GraalPointcutHarness().graalContext.close()
        }

        /** Get or assign a method index for a callsite. */
        fun getMethodIndex(callsiteKey: String): Int {
            return GraalPointcutHarness().methodIndexCache.computeIfAbsent(callsiteKey) { GraalPointcutHarness().methodIndexCache.size }
        }

        /** Get or assign a template index for a pattern. */
        fun getTemplateIndex(patternKey: String): Int {
            return GraalPointcutHarness().templateIndexCache.computeIfAbsent(patternKey) { GraalPointcutHarness().templateIndexCache.size }
        }

        /** Get next sequence number. */
        fun nextSeq(): Long = GraalPointcutHarness().sequenceCounter.incrementAndGet()
    }
}

/**
 * Host-accessible emitter that Graal polyglot code can call to emit pointcuts.
 * This is bound into the Graal context as "pointcutEmitter".
 */
class PolyglotPointcutEmitter(
    private val producer: PointcutEventProducer,
    private val harness: GraalPointcutHarness
) {

    @HostAccess.Export
    fun emit(
        phase: Byte,
        opcode: Byte,
        methodName: String,
        sourceLocation: String,
        seq: Long,
        callsiteHash: Int,
        templateIdx: Int
    ) {
        val methodIdx = harness.getMethodIndex(methodName)
        val synapse = FieldSynapse(
            phase = phase,
            opcode = opcode,
            methodIdx = methodIdx,
            addr = sourceLocation.hashCode(),
            seq = seq.toInt(),
            nano = System.nanoTime(),
            callsiteHash = callsiteHash,
            templateIdx = templateIdx
        )
        producer.emit(synapse)
    }

    @HostAccess.Export
    fun emitFieldAccess(
        phase: Int,  // Use Int instead of Byte for JS compatibility
        isStatic: Boolean,
        isWrite: Boolean,
        className: String,
        fieldName: String,
        sourceLocation: String,
        seq: Long
    ) {
        val opcode = when {
            isStatic && !isWrite -> GraalPointcutHarness.OP_P_GET
            isStatic && isWrite -> GraalPointcutHarness.OP_P_SET
            !isStatic && !isWrite -> GraalPointcutHarness.OP_L_GET
            else -> GraalPointcutHarness.OP_L_SET
        }

        val callsiteKey = "$className.$fieldName${if (isStatic) " static" else ""}${if (isWrite) " write" else " read"}"
        val synapse = FieldSynapse(
            phase = 0, // BEFORE
            opcode = opcode,
            methodIdx = harness.getMethodIndex(callsiteKey),
            addr = sourceLocation.hashCode(),
            seq = seq.toInt(),
            nano = System.nanoTime(),
            callsiteHash = callsiteKey.hashCode(),
            templateIdx = 0
        )
        producer.emit(synapse)
    }
}

/**
 * Extension function to bind pointcut emitter to Graal context.
 * Usage: context.bindPointcutEmitter(harness, producer)
 */
fun Context.bindPointcutEmitter(harness: GraalPointcutHarness, producer: PointcutEventProducer) {
    val emitter = PolyglotPointcutEmitter(producer, harness)
    // Bind to all available languages - initialize each language first
    listOf("js", "ruby", "python", "R").forEach { lang ->
        try {
            // Initialize the language first
            initialize(lang)
            getBindings(lang).putMember("pointcutEmitter", emitter)
        } catch (e: Exception) {
            // Language not available, skip
        }
    }
}