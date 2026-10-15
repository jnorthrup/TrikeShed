package borg.trikeshed.graal.subvm

import borg.trikeshed.vm.Teleported

import borg.trikeshed.pointcut.VmFacet
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Engine
import org.graalvm.polyglot.EnvironmentAccess
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.PolyglotAccess
import org.graalvm.polyglot.PolyglotException
import org.graalvm.polyglot.ResourceLimits
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.Value
import org.graalvm.polyglot.io.IOAccess
import org.graalvm.polyglot.io.FileSystem as GraalFileSystem
import org.graalvm.polyglot.management.ExecutionEvent
import org.graalvm.polyglot.management.ExecutionListener
import org.graalvm.polyglot.proxy.ProxyExecutable
import org.graalvm.polyglot.proxy.ProxyObject
import java.io.InputStream
import java.io.OutputStream
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Why a guest operation ended abnormally. */
enum class GuestFailure { EXHAUSTED, INTERRUPTED, GUEST_ERROR, DEAD }

class GuestException(val kind: GuestFailure, message: String, cause: Throwable? = null) : RuntimeException("$kind: $message", cause)

/**
 * What the execution listener saw for one root return. [returnValue] is only valid inside the
 * callback (it is a context-bound Value); teleport it there or not at all.
 */
class RootObservation(
    val root: String,
    val nanos: Long,
    /** No foreign root entered and no host call happened between enter and return (self-recursion allowed). */
    val selfContained: Boolean,
    val sourceName: String?,
    val line: Int,
    val column: Int,
    /** Source characters of the root as the engine sees them — what a leaf isolate re-parses. */
    val characters: String?,
    val returnValue: Value?,
)

/**
 * A Graal polyglot [Context] behind the bounds in [GuestBounds]:
 *  - one [ReentrantLock] per isolate (SEQUENTIAL access bound; re-entrant so a guest→host→guest
 *    call chain on the same thread is legal),
 *  - its own [Engine] so the [ExecutionListener] sees only this guest, never a leaf isolate,
 *  - `HostAccess.NONE`; the single door is the `host` proxy ([HOST_BINDING]) whose `call(name, ...)`
 *    routes to functions registered with [delegate],
 *  - stop: statement limit where it is safe (JS), otherwise a watchdog `interrupt()` after the wall
 *    budget; a statements budget on a statement-unsafe facet (GraalPy) degrades to the default wall,
 *    and an engine-internal failure (the GIL-assert shape) fails closed as [GuestFailure.DEAD].
 *
 * Root enter/return are observed (roots only, not statements — cheap enough to leave on) and reported
 * through [onRootReturn]; that is the raw material the [LeafTrainer] learns from.
 */
class InProcessIsolate(
    override val id: String,
    override val facet: VmFacet,
    val budget: Budget = Budget(),
    private val fileSystem: GraalFileSystem? = null,
    private val input: InputStream? = null,
    private val output: OutputStream? = null,
    private val error: OutputStream? = output,
    /**
     * Name of a [GuestModules] module to mount as this guest's classpath (`utils/subvm/<module>`,
     * `classes/` then `lib/`). When set, the guest resolves classes from THAT loader instead of
     * from this JVM's classpath — which is how a lego can call CoreNLP or Camel without those
     * coordinates being `implementation` dependencies of `jvmMain`.
     *
     * Declared BEFORE [onRootReturn] on purpose: callers pass the observation callback as a
     * trailing lambda, so it has to stay the last parameter.
     */
    private val guestModule: String? = null,
    private val onRootReturn: (RootObservation) -> Unit = {},
) : GuestIsolate {
    override val trust = Trust.OWN
    override val bounds: FacetBounds = GuestBounds.of(facet)

    private val lock = ReentrantLock()
    private val engine: Engine = Engine.newBuilder().option("engine.WarnInterpreterOnly", "false").build()
    private val context: Context
    /** Native function roots, where supported; binding pointcuts are only the fallback. */
    private val listener: ExecutionListener?
    private class HostDelegate(val hostWallMillis: Long?, val call: (List<Teleported>) -> Teleported)
    private val delegates = ConcurrentHashMap<String, HostDelegate>()
    private var executionBudget: ExecutionBudget? = null

    private class Frame(val root: String, val enterNanos: Long) { var foreign = false; var host = false }
    private val stack = ArrayDeque<Frame>()
    // Rebinding runs under the isolate lock. Its Python metadata/import work is
    // instrumentation setup, not guest work to train or promote recursively.
    private var rebinding = false

    private val evals = AtomicLong(); private val calls = AtomicLong(); private val hostCalls = AtomicLong()
    private val rootEnters = AtomicLong(); private val interrupted = AtomicLong()
    internal val memoServed = AtomicLong(); internal val hostServed = AtomicLong(); internal val refutations = AtomicLong()
    private val seq = AtomicInteger()
    @Volatile private var alive = true

    init {
        val ioAccess = fileSystem?.let { fs ->
            val guestFs = GraalFileSystem.allowInternalResourceAccess(fs)
            IOAccess.newBuilder()
                .allowHostFileAccess(false)
                .allowHostSocketAccess(false)
                .fileSystem(guestFs)
                .build()
        } ?: IOAccess.NONE
        // hostTrusted (VmFacet.JVM only — see GuestBounds' HOST ACCESS note) is the one
        // deliberate exception to HostAccess.NONE: OWN-trust JVM-facet legos are authored
        // by the host operator specifically to call real host libraries already on this
        // JVM's classpath. Every other facet keeps the fully sandboxed policy unchanged.
        //
        // A MOUNTED GUEST MODULE IS THE NARROWER PATH, and the preferred one. `hostTrusted`
        // alone means "resolve anything on the host classpath", which is why CoreNLP had to BE
        // on the host classpath for `vm.corenlp` to work at all. With a module mounted, lookup
        // is restricted to what that module's loader can resolve — the guest can name
        // `edu.stanford.nlp.*` and `java.*`, and cannot name `borg.trikeshed.*` even though the
        // facet is trusted. That is a tighter boundary than the one it replaces, not a looser one.
        val guestLoader = guestModule?.let { GuestModules.loaderFor(it) }
        val b = Context.newBuilder(bounds.languageId).engine(engine)
            .allowHostAccess(if (bounds.hostTrusted || guestLoader != null) HostAccess.ALL else HostAccess.NONE)
            .allowHostClassLookup(
                if (guestLoader != null) java.util.function.Predicate { name: String ->
                    GuestModules.canResolve(guestLoader, name)
                } else java.util.function.Predicate { _: String -> bounds.hostTrusted },
            )
            .also { builder -> guestLoader?.let { builder.hostClassLoader(it) } }
            .allowIO(ioAccess)
            .allowCreateThread(false)
            .allowNativeAccess(false)
            .allowCreateProcess(false)
            .allowEnvironmentAccess(EnvironmentAccess.NONE)
            .allowPolyglotAccess(PolyglotAccess.NONE)
        // EnvironmentAccess.NONE still lets specific vars through via .environment(k,v) — the
        // guest's os.environ carries exactly GuestEnvironment.curated(), the SAME whitelist the
        // process tier (ProcessIsolate) enforces, nothing host-sourced either way.
        for ((k, v) in GuestEnvironment.curated()) b.environment(k, v)
        input?.let { b.`in`(it) }
        output?.let { b.out(it) }
        error?.let { b.err(it) }
        // The per-crossing budget belongs to user execution, not engine bootstrap
        // (Ruby initializes its core library when bindings are first requested).
        val initialized = AtomicBoolean(false)
        if (bounds.statementLimitSafe && budget.statements > 0)
            b.resourceLimits(ResourceLimits.newBuilder().statementLimit(budget.statements) { initialized.get() }.build())
        context = b.build()
        context.getBindings(bounds.languageId).putMember(HOST_BINDING, ProxyObject.fromMap(mapOf(
            "call" to ProxyExecutable { args -> hostCall(args) },
        )))
        context.withholdNativeModules(bounds)
        initialized.set(true)
        listener = if (bounds.rootEventsObservable) ExecutionListener.newBuilder()
            .roots(true)
            .collectReturnValue(true)
            .rootNameFilter { it != null && !bounds.rootNameNoise(it) }
            .onEnter { onEnter(it) }
            .onReturn { onReturn(it) }
            .attach(engine) else null
    }

    // ── listener ──────────────────────────────────────────────────────────
    private fun onEnter(ev: ExecutionEvent) {
        if (rebinding) return
        val name = ev.rootName ?: return
        stack.lastOrNull()?.let { top -> if (top.root != name) top.foreign = true }
        // Java interop does not pass through hostCall(). Until its effects are observed,
        // roots with host-class access cannot be classified as pure/memoizable leaves.
        stack.addLast(Frame(name, System.nanoTime()).also { it.host = bounds.hostTrusted || guestModule != null })
        rootEnters.incrementAndGet()
    }

    private fun onReturn(ev: ExecutionEvent) {
        if (rebinding) return
        val name = ev.rootName ?: return
        var frame: Frame? = null
        while (stack.isNotEmpty()) { val f = stack.removeLast(); if (f.root == name) { frame = f; break } }
        val f = frame ?: return
        val loc = ev.location
        val obs = RootObservation(
            root = name,
            nanos = System.nanoTime() - f.enterNanos,
            selfContained = !f.foreign && !f.host,
            sourceName = loc?.source?.name,
            line = loc?.startLine ?: -1,
            column = loc?.startColumn ?: -1,
            characters = loc?.characters?.toString(),
            returnValue = ev.returnValue,
        )
        // a nested return propagates "foreign/host" upward only through the enter marks; nothing to do here
        try { onRootReturn(obs) } catch (_: Throwable) { /* observers never break the guest */ }
    }

    private fun hostCall(args: Array<Value>): Any? {
        hostCalls.incrementAndGet()
        for (f in stack) f.host = true
        val name = args.firstOrNull()?.takeIf { it.isString }?.asString()
            ?: throw IllegalArgumentException("host.call(name, ...args): name must be a string")
        val delegate = delegates[name] ?: throw IllegalStateException("no host delegate named '$name'")
        val teleported = args.drop(1).map { Teleported.of(it) }
        // Host work must poll the context's pending cancellation before resuming guest code.
        // In particular, an interrupted blocking delegate must unwind at this boundary rather
        // than returning to GraalPy's lock/finally machinery with an unobserved interrupt.
        context.safepoint()
        val hostBudget = delegate.hostWallMillis?.let { millis ->
            checkNotNull(executionBudget) { "host lease outside guarded crossing" }.also { it.enterHost(millis) }
        }
        val result = try {
            delegate.call(teleported)
        } finally {
            hostBudget?.leaveHost()
            context.safepoint()
        }
        return result.toGuest()
    }

    // ── binding pointcuts (languages whose roots the listener cannot see) ─
    private val programSource = StringBuilder()
    private val bindingWrapped = HashSet<String>()
    private val pythonDelegationWrapper: Value by lazy {
        // The leaf transport is positional. Keyword calls retain guest execution and
        // observation instead of losing arguments or claiming a memo/host receipt.
        context.eval(Source.newBuilder("python", """
            (lambda wraps: lambda original, delegate:
                wraps(original)(lambda *args, **kwargs:
                    original(*args, **kwargs) if kwargs else delegate(*args)))(__import__('functools').wraps)
        """.trimIndent(), "binding-delegation.py").internal(true).buildLiteral())
    }

    /** Everything eval'd so far, in order — what a leaf host re-parses when a root has no captured characters. */
    val program: String get() = programSource.toString()

    /**
     * Wrap every new top-level executable binding with an observing proxy so calls produce
     * [RootObservation]s with the same frame semantics the listener gives JS. The wrapper is
     * transparent: it executes the original and returns its value.
     */
    private fun installBindingPointcuts() {
        val b = context.getBindings(bounds.languageId)
        for (name in b.memberKeys.toList()) {
            if (name.startsWith("_") || name == HOST_BINDING || name in bindingWrapped) continue
            val v = b.getMember(name) ?: continue
            if (!v.canExecute() || v.isMetaObject) continue
            // GraalPy's main-module bindings also expose the BUILTINS (chr, len, sum…) as executable
            // members; wrapping those would make every program root that calls a builtin look "foreign"
            // and tax every builtin call. Only roots the program defined are pointcuts.
            val meta = runCatching { v.metaObject?.metaSimpleName }.getOrNull() ?: ""
            if (meta in BINDING_NOISE_META) continue
            bindingWrapped += name
            b.putMember(name, ProxyExecutable { args -> observedCall(name, v, args) })
        }
    }

    /** Names of the roots currently under binding pointcuts (diagnostics). */
    val wrappedRoots: Set<String> get() = bindingWrapped.toSet()

    private fun observedCall(name: String, original: Value, args: Array<Value>): Any? {
        stack.lastOrNull()?.let { top -> if (top.root != name) top.foreign = true }
        val frame = Frame(name, System.nanoTime()).also { it.host = bounds.hostTrusted || guestModule != null }
        stack.addLast(frame)
        rootEnters.incrementAndGet()
        val result: Value
        try {
            result = original.execute(*args.map { it as Any }.toTypedArray())
        } finally {
            while (stack.isNotEmpty()) { val f = stack.removeLast(); if (f === frame) break }
        }
        val obs = RootObservation(name, System.nanoTime() - frame.enterNanos, !frame.foreign && !frame.host, null, -1, -1, null, result)
        try { onRootReturn(obs) } catch (_: Throwable) { }
        return result
    }

    /** The binding currently installed for a root — for binding pointcuts that is the observing wrapper's original. */
    private val bindingOriginals = HashMap<String, Value>()

    // ── contract ─────────────────────────────────────────────────────────
    override fun eval(source: String, name: String): Teleported = guarded {
        evals.incrementAndGet()
        programSource.append(source).append('\n')
        val v = context.eval(Source.newBuilder(bounds.languageId, source, name).buildLiteral())
        if (!bounds.rootEventsObservable) installBindingPointcuts()
        Teleported.of(v)
    }

    override fun call(root: String, vararg args: Teleported): Teleported = guarded {
        calls.incrementAndGet()
        val fn = context.getBindings(bounds.languageId).getMember(root)
            ?: throw GuestException(GuestFailure.GUEST_ERROR, "no guest root '$root'")
        if (!fn.canExecute()) throw GuestException(GuestFailure.GUEST_ERROR, "'$root' is not executable")
        Teleported.of(fn.execute(*args.map { it.toGuest() }.toTypedArray()))
    }

    override fun delegate(name: String, fn: (List<Teleported>) -> Teleported) {
        delegates[name] = HostDelegate(null, fn)
    }

    override fun delegate(name: String, hostWallMillis: Long, fn: (List<Teleported>) -> Teleported) {
        require(hostWallMillis > 0) { "host lease must have a positive finite deadline" }
        delegates[name] = HostDelegate(hostWallMillis, fn)
    }

    override fun interrupt(): Boolean {
        if (!alive) return false
        interrupted.incrementAndGet()
        return try {
            context.interrupt(Duration.ofMillis(INTERRUPT_GRACE_MS))
            true
        } catch (_: TimeoutException) {
            // A timed-out soft interrupt has not stopped the guest. In GraalPy an interrupted
            // finalizer can still be acquiring a lock, so that context cannot be offered again.
            close()
            false
        } catch (_: Throwable) {
            false
        }
    }

    override fun stats() = IsolateStats(
        evals.get(), calls.get(), hostCalls.get(), rootEnters.get(),
        memoServed.get(), hostServed.get(), refutations.get(), interrupted.get(),
    )

    override val isAlive: Boolean get() = alive

    override fun close() {
        alive = false
        runCatching { listener?.close() }
        runCatching { context.close(true) }
        runCatching { engine.close(true) }
    }

    /** The next receipt sequence number for this isolate. */
    fun nextSeq(): Int = seq.incrementAndGet()

    // ── trainer hooks: rebind a guest root to a proxy and back ───────────
    // These touch the context and therefore MUST run on the guest thread at a safe point: a Rete
    // rule fires on its own thread while the guest may still be executing, and Graal refuses
    // concurrent access. [onGuestThread] queues the action; [guarded] drains the queue before the
    // next eval/call, on the thread that holds the isolate lock.
    private val guestActions = java.util.concurrent.ConcurrentLinkedQueue<() -> Unit>()

    /**
     * Run [action] with exclusive context access: immediately if this thread already holds the
     * isolate (re-entrant) or the guest is idle (sequential cross-thread access is within bounds),
     * otherwise queued for the next safe point. Returns true if it ran now.
     */
    internal fun onGuestThread(action: () -> Unit): Boolean {
        if (lock.isHeldByCurrentThread) { action(); return true }
        if (lock.tryLock()) {
            try { drainGuestActions(); action(); return true } finally { lock.unlock() }
        }
        guestActions.add(action)
        return false
    }

    /** The executable behind a root: for binding pointcuts, the wrapped original (not the observing wrapper). */
    internal fun original(root: String): Value? {
        val bound = context.getBindings(bounds.languageId).getMember(root)?.takeIf { it.canExecute() } ?: return null
        return bindingOriginals[root] ?: bound
    }

    internal fun rebind(root: String, proxy: ProxyExecutable) {
        val b = context.getBindings(bounds.languageId)
        if (root in bindingWrapped && root !in bindingOriginals) bindingOriginals[root] = b.getMember(root)
        val prior = rebinding
        rebinding = true
        try {
            b.putMember(root, if (bounds.languageId == "python") pythonDelegationWrapper.execute(original(root), proxy) else proxy)
        } finally {
            rebinding = prior
        }
    }

    internal fun restore(root: String, original: Value) {
        val b = context.getBindings(bounds.languageId)
        val wrapper = bindingOriginals.remove(root)
        b.putMember(root, wrapper ?: original)
    }

    /** Number of queued guest-thread actions (tests and the hypervisor use it to know a promotion is pending). */
    val pendingGuestActions: Int get() = guestActions.size

    // ── guard: sequential access + stop strategy + failure taxonomy ─────
    private inline fun <T> guarded(block: () -> T): T {
        if (!alive) throw GuestException(GuestFailure.DEAD, "isolate $id is closed")
        val depth = lock.holdCount
        return lock.withLock {
            if (depth == 0) {
                drainGuestActions()
                // the statement budget is PER CROSSING, not per context lifetime: a long-lived isolate
                // would otherwise exhaust itself after budget.statements cumulative statements
                if (bounds.statementLimitSafe && budget.statements > 0) context.resetLimits()
            }
            // a watchdog backstops wall-clock enforcement whenever this crossing isn't actually relying on
            // an installed statement limit — either the facet doesn't use one, or this budget disabled it
            // (statements <= 0), which a caller can do deliberately to test the interrupt path on any language.
            // A statements budget on a facet whose statement limit is UNSAFE (GraalPy: the GIL assert,
            // GuestBounds.PYTHON) degrades to the default wall: the breach is a clean interrupt() from
            // outside, never a ResourceLimits trip inside the GIL bookkeeping.
            val wall = when {
                budget.wallMillis > 0 -> budget.wallMillis
                !bounds.statementLimitSafe && budget.statements > 0 -> bounds.defaultWallMillis
                else -> 0L
            }
            val interruptedBefore = interrupted.get()
            val crossing = if (depth == 0) ExecutionBudget(
                if (bounds.stop != StopStrategy.STATEMENT_LIMIT || budget.statements <= 0) wall else 0L,
            ).also { executionBudget = it; it.start() } else null
            val callback = if (depth > 0) executionBudget?.takeIf { it.enterGuest() } else null
            try {
                block()
            } catch (e: PolyglotException) {
                throw classify(e)
            } catch (e: GuestException) {
                throw e
            } catch (t: Throwable) {
                // GraalPy can detonate an internal assert while BUILDING the PolyglotException for a
                // watchdog interrupt (TruffleStackTrace.fillIn over BytecodeRootNode roots), so a raw
                // AssertionError escapes instead of the typed path. The breach is ours and the guest
                // was interrupted; the assert is exception cosmetics. Attribute to the interrupt ONLY
                // when this crossing's watchdog actually fired — anything else stays a host bug.
                if (crossing?.expired == true && interrupted.get() > interruptedBefore)
                    throw GuestException(if (alive) GuestFailure.INTERRUPTED else GuestFailure.DEAD,
                        "wall budget interrupt (engine assert during exception construction: ${t.message})", t)
                throw t
            } finally {
                callback?.leaveGuest()
                if (crossing != null) {
                    executionBudget = null
                    crossing.finish()
                }
            }
        }
    }

    private fun drainGuestActions() {
        while (true) { val a = guestActions.poll() ?: break; runCatching { a() } }
    }

    /** Run queued guest-thread actions now, without evaluating anything (a safe point on demand). */
    fun settle() { if (!alive) return; lock.withLock { drainGuestActions() } }

    /** One crossing's remaining guest time and nested, absolute host deadlines. */
    private inner class ExecutionBudget(wallMillis: Long) {
        private val monitor = Any()
        private var remainingGuestNanos = wallMillis.takeIf { it > 0 }?.let(TimeUnit.MILLISECONDS::toNanos)
        private var guestDeadlineNanos = remainingGuestNanos?.let { System.nanoTime() + it }
        private val hostDeadlines = ArrayDeque<Long>()
        private val hostGuestStates = ArrayDeque<Boolean>()
        private var guestRunning = true
        private var generation = 0L
        private var task: ScheduledFuture<*>? = null
        private var finished = false
        @Volatile var expired = false
            private set

        fun start() = synchronized(monitor) { schedule(System.nanoTime()) }

        fun enterHost(wallMillis: Long) = synchronized(monitor) {
            val now = System.nanoTime()
            hostGuestStates.addLast(guestRunning)
            hostDeadlines.addLast(now + TimeUnit.MILLISECONDS.toNanos(wallMillis))
            guestPhase(false, now)
            if (!expired) schedule(now)
        }

        fun leaveHost() = synchronized(monitor) {
            hostDeadlines.removeLast()
            val now = System.nanoTime()
            guestPhase(hostGuestStates.removeLast(), now)
            if (!expired) schedule(now)
        }

        fun enterGuest(): Boolean = synchronized(monitor) {
            if (guestRunning) return@synchronized false
            val now = System.nanoTime()
            guestPhase(true, now)
            if (!expired) schedule(now)
            true
        }

        fun leaveGuest() = synchronized(monitor) {
            val now = System.nanoTime()
            guestPhase(false, now)
            if (!expired) schedule(now)
        }

        private fun guestPhase(running: Boolean, now: Long) {
            if (guestRunning == running) return
            if (running) guestDeadlineNanos = remainingGuestNanos?.let { now + it }
            else remainingGuestNanos = guestDeadlineNanos?.let { (it - now).coerceAtLeast(0) }
            guestRunning = running
        }

        private fun schedule(now: Long) {
            task?.cancel(false)
            val ticket = ++generation
            val guestDelay = guestDeadlineNanos?.minus(now)?.takeIf { guestRunning }
            val hostDelay = hostDeadlines.minOfOrNull { it - now }
            val delay = when {
                guestDelay == null -> hostDelay
                hostDelay == null -> guestDelay
                else -> minOf(guestDelay, hostDelay)
            }
            task = delay?.let {
                WATCHDOG.schedule({
                    val stop = synchronized(monitor) {
                        if (finished || ticket != generation) false else { expired = true; true }
                    }
                    if (stop) interrupt()
                }, it.coerceAtLeast(0), TimeUnit.NANOSECONDS)
            }
        }

        fun finish() {
            val stopping = synchronized(monitor) {
                finished = true
                generation++
                if (!expired) task?.cancel(false)
                task.takeIf { expired }
            }
            // eval/call has left the polyglot context. Finish its stop before another crossing
            // can enter this isolate; an old interrupt must never land on a later request.
            try { stopping?.get() }
            catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            catch (_: ExecutionException) { /* interruption already classified at the guest boundary */ }
        }
    }

    private fun classify(e: PolyglotException): GuestException {
        if (!alive) return GuestException(GuestFailure.DEAD, e.message ?: e.toString(), e)
        val (kind, failClosed) = classify(e.isResourceExhausted, e.isInternalError, e.isInterrupted || e.isCancelled)
        if (failClosed) runCatching { close() } // the context can no longer be trusted; free it now, not at .use{} exit
        return GuestException(kind, e.message ?: e.toString(), e)
    }

    companion object {
        const val HOST_BINDING = "host"
        const val INTERRUPT_GRACE_MS = 2_000L

        /**
         * Failure taxonomy for one crossing, precedence-ordered; the Boolean says the context can no
         * longer be trusted and the isolate fails closed. An engine INTERNAL error (GraalPy's GIL
         * assert when a statement limit trips mid-loop — see GuestBounds and GraalBoundsSmokeTest) is
         * DEAD, matching the process tier's "child died": a typed failure and a downed isolate, never
         * a poisoned context served again — internal errors often also carry isCancelled, so this
         * check must precede the INTERRUPTED mapping.
         */
        fun classify(resourceExhausted: Boolean, internalError: Boolean, interrupted: Boolean): Pair<GuestFailure, Boolean> = when {
            resourceExhausted -> GuestFailure.EXHAUSTED to true
            internalError -> GuestFailure.DEAD to true
            interrupted -> GuestFailure.INTERRUPTED to false
            else -> GuestFailure.GUEST_ERROR to false
        }

        /** Meta-object names of executables that are runtime furniture, not program roots. */
        val BINDING_NOISE_META = setOf("builtin_function_or_method", "builtin_method", "method-wrapper", "wrapper_descriptor", "method_descriptor", "type", "module", "classmethod_descriptor")
        private val WATCHDOG = ScheduledThreadPoolExecutor(1) { r ->
            Thread(r, "subvm-watchdog").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
    }
}
