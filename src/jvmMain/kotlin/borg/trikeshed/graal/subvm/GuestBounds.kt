package borg.trikeshed.graal.subvm

import borg.trikeshed.pointcut.VmFacet
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Source

/**
 * The GraalVM polyglot bounds this sub-VM is built on, as code rather than lore.
 * Entries are backed by GraalBoundsSmokeTest and facet-specific probes — change
 * the table only with a new probe. Ruby: TruffleRuby 40.0.0 / Truffle 25.3.4.1.
 *
 *  - SEQUENTIAL: a guest [org.graalvm.polyglot.Context] forbids *concurrent* access; a second
 *    thread may enter once the first has left. One lock per isolate is the whole discipline.
 *  - JS: `ResourceLimits.statementLimit` trips cleanly (exception is exhausted+cancelled); the
 *    context is then unusable and must be `close(true)`d.
 *  - PYTHON: statementLimit cancellation inside a loop dies in the GIL bookkeeping
 *    ("trying to release the GIL with invalid hold count 0"). Python is stopped from OUTSIDE —
 *    `Context.interrupt(timeout)` from a watchdog — or run in a [ProcessIsolate].
 *  - HOST ACCESS: `HostAccess.NONE` blocks Java objects but not polyglot proxies, so the only
 *    door into the host is the `host` proxy the isolate installs ([InProcessIsolate.HOST_BINDING]) —
 *    for every facet EXCEPT [VmFacet.JVM] ([FacetBounds.hostTrusted]), which is the one deliberate
 *    exception: `vm.tika`/`vm.corenlp` legos are OWN-trust JVM-facet scripts authored by the host
 *    operator through the LCNC editor (not derived from untrusted input — document text crosses as a
 *    string parameter, never as source), and they exist specifically to call real host libraries
 *    (`Java.type('org.apache.tika.Tika')`, `edu.stanford.nlp...`) already bundled in this same JVM.
 *    `hostTrusted` is read only by [InProcessIsolate] (the OWN-trust tier) — [ProcessIsolate]'s
 *    subprocess re-resolves bounds by language id via [GuestBounds.ofLanguage], which has no JVM
 *    entry, so a JVM-facet lego requested at UNTRUSTED trust safely degrades to a sandboxed `js`
 *    guest in the child process rather than leaking host access to untrusted-declared code.
 *  - Values never cross contexts: state is [Teleport]ed to host primitives/proxies and back.
 *  - Node.js APIs (`require`, `process`, event loop) are NOT in the in-process JS engine; they
 *    need the GraalJS `node` launcher, i.e. a [ProcessIsolate] with `nodeLauncher` set.
 */
enum class StopStrategy { STATEMENT_LIMIT, INTERRUPT, PROCESS_KILL }

data class FacetBounds(
    val facet: VmFacet,
    val languageId: String,
    val statementLimitSafe: Boolean,
    val stop: StopStrategy,
    /** Root names the listener should ignore (program/module roots, internals). */
    val rootNameNoise: (String) -> Boolean,
    /** Wall budget used when the statement limit cannot be trusted. */
    val defaultWallMillis: Long,
    /**
     * Whether `ExecutionListener.roots(true)` reports this language's function roots. GraalPy
     * 25.3.4.1 reports functions, nested functions and methods with source sections (PythonRootProbeTest).
     * Languages without root events use binding pointcuts instead.
     */
    val rootEventsObservable: Boolean,
    /** [VmFacet.JVM] only — see the class doc's HOST ACCESS note. Every other facet stays HostAccess.NONE. */
    val hostTrusted: Boolean = false,
    /** False when writing a polyglot binding does not replace guest method dispatch. */
    val rootBindingsMutable: Boolean = true,
)

object GuestBounds {
    const val SEQUENTIAL_ACCESS = true
    const val DEFAULT_STATEMENT_LIMIT = 5_000_000L
    const val DEFAULT_WALL_MILLIS = 5_000L

    val JS = FacetBounds(
        facet = VmFacet.GRAAL_JS, languageId = "js",
        statementLimitSafe = true, stop = StopStrategy.STATEMENT_LIMIT,
        rootNameNoise = { it.isBlank() || it.startsWith(":") || it.startsWith("<") },
        defaultWallMillis = DEFAULT_WALL_MILLIS,
        rootEventsObservable = true,
    )
    val PYTHON = FacetBounds(
        facet = VmFacet.GRAAL_PYTHON, languageId = "python",
        statementLimitSafe = false, stop = StopStrategy.INTERRUPT,
        rootNameNoise = { it.isBlank() || it.startsWith("<") || it.startsWith(":") || it.contains("__") },
        defaultWallMillis = DEFAULT_WALL_MILLIS,
        rootEventsObservable = true,
    )
    val RUBY = FacetBounds(
        facet = VmFacet.GRAAL_RUBY, languageId = "ruby",
        // Count user statements and retain the wall watchdog for core-library calls.
        statementLimitSafe = true, stop = StopStrategy.INTERRUPT,
        rootNameNoise = { it.isBlank() || it.startsWith("<") },
        defaultWallMillis = DEFAULT_WALL_MILLIS,
        rootEventsObservable = true,
        // Object#method roots are observable, but putMember creates a local binding;
        // it does not rebind Ruby's method table. Do not claim a delegated pointcut.
        rootBindingsMutable = false,
    )
    val LLVM = FacetBounds(
        facet = VmFacet.GRAAL_LLVM, languageId = "llvm",
        statementLimitSafe = false, stop = StopStrategy.INTERRUPT,
        rootNameNoise = { it.isBlank() || it.startsWith("<") },
        defaultWallMillis = DEFAULT_WALL_MILLIS,
        rootEventsObservable = false,
    )
    /** JVM facet: GraalJS with real host access — see the class doc's HOST ACCESS note. */
    val JVM = FacetBounds(
        facet = VmFacet.JVM, languageId = "js",
        statementLimitSafe = true, stop = StopStrategy.STATEMENT_LIMIT,
        rootNameNoise = { it.isBlank() || it.startsWith(":") || it.startsWith("<") },
        defaultWallMillis = DEFAULT_WALL_MILLIS,
        rootEventsObservable = true,
        hostTrusted = true,
    )

    fun of(facet: VmFacet): FacetBounds = when (facet) {
        VmFacet.GRAAL_JS -> JS
        VmFacet.GRAAL_PYTHON -> PYTHON
        VmFacet.GRAAL_RUBY -> RUBY
        VmFacet.GRAAL_LLVM -> LLVM
        VmFacet.JVM -> JVM
        else -> throw IllegalArgumentException("no in-process guest for facet $facet")
    }

    fun ofLanguage(languageId: String): FacetBounds = when (languageId) {
        "js" -> JS; "python" -> PYTHON; "ruby" -> RUBY; "llvm" -> LLVM
        else -> throw IllegalArgumentException("unknown guest language $languageId")
    }
}

/** Refuse C extensions before GraalPy reserves its process-wide native C API state. */
fun Context.withholdNativeModules(bounds: FacetBounds) {
    if (bounds.facet != VmFacet.GRAAL_PYTHON) return
    eval(Source.newBuilder("python", """
        import _imp
        def __trikeshed_native_module(spec, file=None):
            raise ImportError('native module withheld in TrikeShed guest: ' + spec.name, name=spec.name)
        _imp.create_dynamic = __trikeshed_native_module
        del _imp, __trikeshed_native_module
    """.trimIndent(), "guest-native-modules.py").internal(true).buildLiteral())
}
