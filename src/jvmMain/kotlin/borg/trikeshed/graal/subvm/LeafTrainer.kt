package borg.trikeshed.graal.subvm

import borg.trikeshed.job.ContentId
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Engine
import org.graalvm.polyglot.EnvironmentAccess
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.PolyglotAccess
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.Value
import org.graalvm.polyglot.io.IOAccess
import org.graalvm.polyglot.proxy.ProxyExecutable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Trains pointcuts to localize leaf execution and teleport it to the host.
 *
 * Phases per guest root, each transition evidenced, none assumed:
 *
 *   OBSERVED ──(≥ trainCalls returns, all self-contained, non-opaque results)──▶ SELF_CONTAINED
 *   SELF_CONTAINED ──(a Rete rule fires; [promote])──▶ SHADOWED
 *       the root is rebound to a proxy that still runs the GUEST function but ALSO runs the same
 *       source in a warm [LeafHost] and compares result cids — differential verification.
 *   SHADOWED ──(≥ shadowCalls consistent, 0 inconsistent)──▶ DELEGATED
 *       the proxy now serves from MEMO (args cid → result) or the LeafHost; the guest function
 *       is no longer executed. Any host failure or cid mismatch is a refutation → DEMOTED
 *       (original binding restored; receipt marked refuted).
 *
 * "Self-contained" = no foreign root entered and no host call during the window; self-recursion
 * is allowed (fib is a leaf). Opaque args/results (functions, foreign objects) block promotion
 * because they cannot be teleported faithfully.
 */
class LeafTrainer(
    private val isolate: InProcessIsolate,
    val trainCalls: Int = 8,
    val shadowCalls: Int = 4,
    private val onTransition: (RootProfile, Phase, Phase) -> Unit = { _, _, _ -> },
    private val onReceipt: (DelegationReceipt) -> Unit = {},
) : AutoCloseable {
    enum class Phase { OBSERVED, SELF_CONTAINED, SHADOWED, DELEGATED, DEMOTED }

    companion object { const val MEMO_CAP = 4096 }

    class RootProfile(val root: String) {
        @Volatile var phase = Phase.OBSERVED
        var calls = 0L; var selfContained = 0L; var notSelfContained = 0L; var opaqueReturns = 0L; var totalNanos = 0L
        var characters: String? = null; var sourceName: String? = null; var line = -1; var column = -1
        var consistent = 0; var inconsistent = 0; var demotedReason: String? = null
        @Volatile var promotionQueued = false
            internal set
        /** Re-entrancy depth of the serving proxy on the guest thread (self-recursion). */
        internal var depth = 0
        /**
         * args cid (hex) → result, LRU-bounded at [MEMO_CAP]: a hot root with varying arguments must not
         * grow the heap without bound over a soak; a miss after eviction is just a HOST serve.
         */
        val memo: MutableMap<String, Teleported> = object : LinkedHashMap<String, Teleported>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Teleported>?): Boolean = size > MEMO_CAP
        }
        internal var original: Value? = null
        /** The shared per-isolate [LeafHost] while this root is promoted (null once demoted — the host itself stays). */
        @Volatile var leafHost: LeafHost? = null
            internal set
        val avgNanos: Long get() = if (calls == 0L) 0 else totalNanos / calls
        override fun toString() = "$root[$phase calls=$calls self=$selfContained/$notSelfContained opaque=$opaqueReturns memo=${memo.size} ok=$consistent bad=$inconsistent avg=${avgNanos / 1000}µs]"
    }

    val profiles = ConcurrentHashMap<String, RootProfile>()

    /**
     * ONE leaf host per isolate, shared by every promoted root. A per-root host means a Truffle
     * Engine + Context (tens of MB warming on the Java heap, its own thread) per promoted root — the
     * heat soak showed exactly that as live-heap growth proportional to promotions. Roots are
     * materialized into the shared host by name; the program namespace is shared in the guest too.
     */
    @Volatile private var sharedHost: LeafHost? = null
    private fun host(): LeafHost = sharedHost ?: synchronized(this) { sharedHost ?: LeafHost(isolate.bounds, isolate.id, isolate.budget).also { sharedHost = it } }
    /** The shared leaf host, if any root has been promoted (diagnostics). */
    val leafHost: LeafHost? get() = sharedHost

    /** Listener feed from [InProcessIsolate.onRootReturn]. Runs on the guest thread under the isolate lock. */
    fun observe(o: RootObservation) {
        val p = profiles.computeIfAbsent(o.root) { RootProfile(it) }
        synchronized(p) {
            p.calls++; p.totalNanos += o.nanos
            if (o.selfContained) p.selfContained++ else p.notSelfContained++
            if (p.characters == null && o.characters != null) { p.characters = o.characters; p.sourceName = o.sourceName; p.line = o.line; p.column = o.column }
            if (p.phase == Phase.OBSERVED) {
                if (p.calls <= trainCalls && o.returnValue != null && Teleported.of(o.returnValue).isOpaque) p.opaqueReturns++
                if (p.calls >= trainCalls && p.notSelfContained == 0L && p.opaqueReturns == 0L) {
                    transition(p, Phase.SELF_CONTAINED)
                }
            }
        }
    }

    /**
     * Rule-driven: install the shadowing proxy. Returns false if the root is not eligible. The rebind
     * itself is queued onto the guest thread ([InProcessIsolate.onGuestThread]) and happens at the next
     * safe point (next eval/call, or [InProcessIsolate.settle]); until then the phase stays SELF_CONTAINED.
     */
    fun promote(root: String): Boolean {
        val p = profiles[root] ?: return false
        synchronized(p) {
            if (p.phase != Phase.SELF_CONTAINED || p.promotionQueued) return false
            p.promotionQueued = true
        }
        isolate.onGuestThread {
            synchronized(p) {
                p.promotionQueued = false
                if (p.phase != Phase.SELF_CONTAINED) return@onGuestThread
                val original = isolate.original(root) ?: run { p.demotedReason = "no executable root"; transition(p, Phase.DEMOTED); return@onGuestThread }
                // listener pointcuts captured the root's own characters; binding pointcuts did not — the leaf
                // host then mirrors the whole program, and any dependence on mutable guest state is caught by
                // the shadow comparison, not assumed away.
                val materialize = p.characters ?: isolate.program
                val host = runCatching { host().also { it.materialize(root, materialize) } }.getOrNull()
                p.original = original
                p.leafHost = host
                isolate.rebind(root, ProxyExecutable { args -> serve(p, args) })
                transition(p, Phase.SHADOWED)
            }
        }
        return true
    }

    fun demote(root: String, reason: String) {
        val p = profiles[root] ?: return
        isolate.onGuestThread {
            synchronized(p) {
                if (p.phase == Phase.DEMOTED) return@onGuestThread
                p.original?.let { isolate.restore(root, it) }
                p.leafHost?.forget(root); p.leafHost = null
                p.demotedReason = reason
                transition(p, Phase.DEMOTED)
            }
        }
    }

    private fun transition(p: RootProfile, to: Phase) { val from = p.phase; p.phase = to; onTransition(p, from, to) }

    /**
     * The proxy body. Runs on the guest thread (isolate lock held, re-entrant). Only the OUTERMOST
     * frame of a root is shadowed/served — inner self-recursive frames execute the guest original
     * directly, so verifying fib(20) costs one leaf-host call and one receipt, not 21,891.
     */
    private fun serve(p: RootProfile, args: Array<Value>): Any? {
        val original = p.original!!
        if (p.depth > 0) return original.execute(*args.map { it as Any }.toTypedArray())
        p.depth++
        try { return serveOutermost(p, original, args) } finally { p.depth-- }
    }

    private fun serveOutermost(p: RootProfile, original: Value, args: Array<Value>): Any? {
        val t0 = System.nanoTime()
        val targs = Teleported.args(*args)
        if (targs.isOpaque) return original.execute(*args.map { it as Any }.toTypedArray())  // cannot teleport → plain guest call, unrecorded
        val argsCid = targs.cid
        when (p.phase) {
            Phase.SHADOWED -> {
                val guest = original.execute(*args.map { it as Any }.toTypedArray())
                val guestT = Teleported.of(guest)
                var hostFailure: String? = null
                val hostT = p.leafHost?.let { h -> runCatching { h.call(p.root, targs) }.onFailure { hostFailure = it.toString() }.getOrNull() }
                    ?: run { if (hostFailure == null) hostFailure = "no leaf host"; null }
                val agree = hostT != null && hostT.cid == guestT.cid && !guestT.isOpaque
                if (agree) p.consistent++ else p.inconsistent++
                if (agree) p.memo[argsCid.hex] = guestT
                receipt(p, argsCid, guestT.cid, Served.SHADOW, System.nanoTime() - t0, refuted = !agree)
                // recursive frames: an inner frame may already have moved the phase on — transition once
                if (p.inconsistent > 0) demote(p.root, hostFailure?.let { "leaf host failed: $it" } ?: "host/guest disagree: host=${hostT?.cid?.hex?.take(12)} guest=${guestT.cid.hex.take(12)}")
                else if (p.consistent >= shadowCalls && p.phase == Phase.SHADOWED) transition(p, Phase.DELEGATED)
                return guest
            }
            Phase.DELEGATED -> {
                p.memo[argsCid.hex]?.let { hit ->
                    isolate.memoServed.incrementAndGet()
                    receipt(p, argsCid, hit.cid, Served.MEMO, System.nanoTime() - t0)
                    return hit.toGuest()
                }
                val host = p.leafHost
                if (host != null) {
                    val r = runCatching { host.call(p.root, targs) }.getOrNull()
                    if (r != null && !r.isOpaque) {
                        p.memo[argsCid.hex] = r
                        isolate.hostServed.incrementAndGet()
                        receipt(p, argsCid, r.cid, Served.HOST, System.nanoTime() - t0)
                        return r.toGuest()
                    }
                }
                // refutation: the host could not serve — fall back to the guest, record it, demote
                isolate.refutations.incrementAndGet()
                val guest = original.execute(*args.map { it as Any }.toTypedArray())
                receipt(p, argsCid, Teleported.of(guest).cid, Served.GUEST, System.nanoTime() - t0, refuted = true)
                demote(p.root, "host serve failed")
                return guest
            }
            else -> return original.execute(*args.map { it as Any }.toTypedArray())
        }
    }

    private fun receipt(p: RootProfile, argsCid: ContentId, resultCid: ContentId, served: Served, nanos: Long, refuted: Boolean = false) {
        onReceipt(DelegationReceipt(isolate.id, p.root, argsCid, resultCid, served, nanos, isolate.nextSeq(), refuted))
    }

    override fun close() { sharedHost?.close(); sharedHost = null }

    /**
     * The warm host-side isolate promoted leaves are teleported into: one [Context] per ISOLATE on its
     * own [Engine] with NO listener (instrumentation off = the fast path). Each promoted root is
     * [materialize]d into it by name from its captured characters (listener pointcuts) or the whole
     * program (binding pointcuts). Self-recursion inside a leaf resolves to the host's own global, so
     * it runs natively and gets JIT-hot here while the guest stays instrumented.
     */
    class LeafHost(private val bounds: FacetBounds, isolateId: String, budget: Budget) : AutoCloseable {
        private val lock = ReentrantLock()
        /**
         * The leaf runs on its own thread: the caller is the guest thread with the GUEST context entered,
         * and GraalPy refuses to enter a second context on a thread that already holds one (JS tolerates
         * it). Off-thread is also the point — the guest is not blocked by the leaf's execution model.
         */
        private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "leaf-host:$isolateId").apply { isDaemon = true } }
        private val engine = Engine.newBuilder().option("engine.WarnInterpreterOnly", "false").build()
        private val context: Context = Context.newBuilder(bounds.languageId).engine(engine)
            .allowHostAccess(HostAccess.NONE).allowHostClassLookup { false }
            .allowIO(IOAccess.NONE).allowCreateThread(false).allowNativeAccess(false)
            .allowCreateProcess(false).allowEnvironmentAccess(EnvironmentAccess.NONE).allowPolyglotAccess(PolyglotAccess.NONE)
            .build()
        private val fns = ConcurrentHashMap<String, Value>()
        private val materializedSources = HashSet<Int>()
        private val wallMillis = if (budget.wallMillis > 0) budget.wallMillis else GuestBounds.DEFAULT_WALL_MILLIS

        /** Number of roots currently materialized (diagnostics). */
        val size: Int get() = fns.size

        /** Parse [source] once (by content) and resolve [root] in the host; idempotent per root. */
        fun materialize(root: String, source: String) {
            if (fns.containsKey(root)) return
            val f = worker.submit<Value> {
                val b = context.getBindings(bounds.languageId)
                val evaluated = if (materializedSources.add(source.hashCode())) context.eval(Source.newBuilder(bounds.languageId, source, "leaf:$root").buildLiteral()) else null
                val fn = when {
                    evaluated != null && evaluated.canExecute() -> evaluated
                    else -> b.getMember(root)?.takeIf { it.canExecute() } ?: error("leaf host could not materialize '$root' from its source")
                }
                // arrow/lambda roots evaluate to a function value but leave no global: bind it so self-recursion resolves
                if (b.getMember(root) == null) b.putMember(root, fn)
                fn
            }.get()
            fns[root] = f
        }

        /** A demoted root is dropped from the serving table; its definition stays in the host (harmless, and another root may depend on it). */
        fun forget(root: String) { fns.remove(root) }

        /** Execute on the leaf thread; the wall budget bounds it (interrupt on timeout) so a runaway leaf cannot hang the guest. */
        fun call(root: String, args: Teleported.Arr): Teleported = lock.withLock {
            val fn = fns[root] ?: throw GuestException(GuestFailure.GUEST_ERROR, "'$root' is not materialized in the leaf host")
            val task = worker.submit<Teleported> { Teleported.of(fn.execute(*args.v.map { it.toGuest() }.toTypedArray())) }
            try {
                task.get(wallMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (e: java.util.concurrent.TimeoutException) {
                runCatching { context.interrupt(java.time.Duration.ofMillis(InProcessIsolate.INTERRUPT_GRACE_MS)) }
                throw GuestException(GuestFailure.INTERRUPTED, "leaf host exceeded ${wallMillis}ms")
            } catch (e: java.util.concurrent.ExecutionException) {
                throw (e.cause ?: e)
            }
        }

        override fun close() {
            worker.shutdownNow()
            runCatching { context.close(true) }; runCatching { engine.close(true) }
        }
    }
}
