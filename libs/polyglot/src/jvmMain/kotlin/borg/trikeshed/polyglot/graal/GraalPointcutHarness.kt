package borg.trikeshed.polyglot.graal

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.Value
import borg.trikeshed.polyglot.ccek.PointcutEventProducer
import borg.trikeshed.polyglot.ccek.FieldSynapse
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * GraalPointcutHarness - extends pointcut capturing to any Graal polyglot language.
 *
 * Uses manual pointcut emission via bound emitter (PolyglotPointcutEmitter).
 * Supports JS, Ruby, Python, WASM via GraalVM SDK.
 *
 * Pointcut types:
 * - L_GET (0xA5): instance field / local variable read
 * - L_SET (0xA6): instance field / local variable write
 * - P_GET (0xA7): static field read
 * - P_SET (0xA8): static field write
 *
 * Two modes supported:
 * - Same-VM: runs in current JVM (current implementation)
 * - Parent-process VM: communicates via external process (future)
 */
class GraalPointcutHarness(
    private val pointcutProducer: PointcutEventProducer? = null,
    private val enableInstrumentation: Boolean = true
) {

    val graalContext: Context = Context.newBuilder()
        .allowAllAccess(true)
        .allowHostAccess(HostAccess.ALL)
        .allowHostClassLookup { className -> true }
        .build()

    private val sequenceCounter = AtomicLong(0)
    private val methodIndexCache = ConcurrentHashMap<String, Int>()
    private val templateIndexCache = ConcurrentHashMap<String, Int>()

    init {
        // Auto-bind pointcut emitter if producer provided
        if (pointcutProducer != null) {
            graalContext.bindPointcutEmitter(this, pointcutProducer!!)
        }
        // Install Python instrumentation module from inline string
        installPythonInstrumentation()
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