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

    companion object {
        // Phase constants
        const val PHASE_BEFORE = 0.toByte()
        const val PHASE_AFTER = 1.toByte()

        // Opcode constants (matching xvm FieldSynapse wire protocol)
        @JvmStatic
        const val OP_L_GET = 0xA5.toByte()
        @JvmStatic
        const val OP_L_SET = 0xA6.toByte()
        @JvmStatic
        const val OP_P_GET = 0xA7.toByte()
        @JvmStatic
        const val OP_P_SET = 0xA8.toByte()

        /** Python instrumentation module - loaded inline to avoid resource issues. */
        const val PYTHON_INSTRUMENTATION_MODULE = """
import sys
import types

print("[POINT CUT] Module loaded", file=sys.stderr)
sys.stderr.flush()

# Global emitter reference (bound by host)
_emitter = None
_instrumented_classes = set()

def _set_emitter(emitter):
    '''Called by host to register the pointcut emitter.'''
    global _emitter
    _emitter = emitter

def _emit(phase, is_static, is_write, class_name, field_name, location, seq):
    '''Emit a pointcut via the host-bound emitter.'''
    if _emitter is not None:
        _emitter.emitFieldAccess(phase, is_static, is_write, class_name, field_name, location, seq)

def _make_setattr(class_name, original_setattr=None):
    '''Create a __setattr__ that emits L_SET/P_SET pointcuts.'''
    def instrumented_setattr(self, name, value):
        # Skip private/dunder attributes
        if name.startswith('_'):
            if original_setattr:
                return original_setattr(self, name, value)
            object.__setattr__(self, name, value)
            return
        
        is_static = isinstance(self, type)
        
        # BEFORE phase
        seq = _get_next_seq()
        _emit(0, is_static, True, class_name, name, class_name + '.__setattr__', seq)
        
        try:
            if original_setattr:
                original_setattr(self, name, value)
            else:
                object.__setattr__(self, name, value)
        except Exception:
            _emit(1, is_static, True, class_name, name, class_name + '.__setattr__', seq)
            raise
        
        # AFTER phase
        _emit(1, is_static, True, class_name, name, class_name + '.__setattr__', seq)
    return instrumented_setattr

def _make_getattr(class_name, original_getattr=None):
    '''Create a __getattr__ that emits L_GET/P_GET pointcuts for missing attributes.
    
    NOTE: Python's __getattr__ is ONLY called for missing attributes.
    For attributes in __dict__, __getattr__ is NOT called.
    '''
    def instrumented_getattr(self, name):
        # Skip private/dunder attributes
        if name.startswith('_'):
            if original_getattr:
                return original_getattr(self, name)
            raise AttributeError(name)
        
        # Determine if static (class attribute) or instance
        is_static = isinstance(self, type)
        
        # BEFORE phase
        seq = _get_next_seq()
        _emit(0, is_static, False, class_name, name, class_name + '.__getattr__', seq)
        
        try:
            if original_getattr:
                result = original_getattr(self, name)
            else:
                raise AttributeError(name)
        except AttributeError:
            # Still emit AFTER on exception
            _emit(1, is_static, False, class_name, name, class_name + '.__getattr__', seq)
            raise
        
        # AFTER phase
        _emit(1, is_static, False, class_name, name, class_name + '.__getattr__', seq)
        return result
    return instrumented_getattr

_seq_counter = 0
def _get_next_seq():
    global _seq_counter
    _seq_counter += 1
    return _seq_counter

def instrument_class(cls, class_name=None):
    '''Instrument a class to emit pointcuts on attribute access.'''
    global _instrumented_classes
    
    if class_name is None:
        class_name = cls.__name__
    
    # Skip if already instrumented
    if cls in _instrumented_classes:
        return cls
    
    print("[POINT CUT] Instrumenting class: " + class_name, file=sys.stderr)
    sys.stderr.flush()
    
    # Save original methods if they exist
    original_setattr = getattr(cls, '__setattr__', None)
    original_getattr = getattr(cls, '__getattr__', None)
    
    # Install instrumented versions
    cls.__setattr__ = _make_setattr(class_name, original_setattr)
    cls.__getattr__ = _make_getattr(class_name, original_getattr)
    
    # Also instrument __delattr__ if present
    original_delattr = getattr(cls, '__delattr__', None)
    if original_delattr:
        def instrumented_delattr(self, name):
            if name.startswith('_'):
                return original_delattr(self, name)
            is_static = isinstance(self, type)
            seq = _get_next_seq()
            _emit(0, is_static, True, class_name, name, class_name + '.__delattr__', seq)
            try:
                original_delattr(self, name)
            except Exception:
                _emit(1, is_static, True, class_name, name, class_name + '.__delattr__', seq)
                raise
            _emit(1, is_static, True, class_name, name, class_name + '.__delattr__', seq)
        cls.__delattr__ = instrumented_delattr
    
    _instrumented_classes.add(cls)
    return cls

def instrument_module(mod):
    '''Instrument all classes in a module.'''
    for name in dir(mod):
        obj = getattr(mod, name)
        if isinstance(obj, type):
            instrument_class(obj, name)
    return mod

def auto_instrument(target):
    '''Automatically instrument a class or module.'''
    if isinstance(target, type):
        return instrument_class(target)
    elif isinstance(target, types.ModuleType):
        return instrument_module(target)
    else:
        raise TypeError('Cannot auto-instrument ' + str(type(target)))

# Export functions
def set_emitter(emitter):
    '''Host calls this to bind the emitter.'''
    _set_emitter(emitter)

# Make auto_instrument available as 'pointcut_instrument' module
pointcut_instrument = sys.modules[__name__]
pointcut_instrument.instrument_class = instrument_class
pointcut_instrument.instrument_module = instrument_module
pointcut_instrument.auto_instrument = auto_instrument
pointcut_instrument.set_emitter = set_emitter

# Register as importable module
sys.modules['pointcut_instrument'] = pointcut_instrument
"""
        }
    }

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
            isStatic && !isWrite -> OP_P_GET
            isStatic && isWrite -> OP_P_SET
            !isStatic && !isWrite -> OP_L_GET
            else -> OP_L_SET
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