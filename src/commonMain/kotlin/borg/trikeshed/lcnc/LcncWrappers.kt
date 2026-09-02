package borg.trikeshed.lcnc

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size

/**
 * The WRAPPER graph, late-bound.
 *
 * A node type is a wrapper: a [LcncPortContract] (the shape a port sees) over
 * a behaviour (what runs). The two are joined by nothing but a string key at
 * boot — 120 contracts in one compiled table, runners in eleven registry
 * providers assembled into one map. That join was invisible: nothing could
 * say which Kotlin file bound `pick`, whether `display` was bound at all, or
 * that a stored program with formal ports is a wrapper too.
 *
 * This file names the join, as data, so the graph can carry it:
 *
 *  - [LcncBinding] — for every type, HOW it is bound and by WHAT. Produced by
 *    ONE pass over the assembled registry ("reflection once"): the caller
 *    hands in a provenance function (the JVM passes the runner's class name)
 *    and nothing is reflected on again. No annotations: a runner is a lambda
 *    keyed by a string, and a lambda has no declaration site to annotate —
 *    annotating would mean re-homing 120 lambdas into classes and scattering
 *    the ONE vocabulary across fifteen files. The runtime class name already
 *    says which file bound the type; that is the provenance blame needs.
 *  - [LcncComposites] — a stored program whose top ring declares `scope.in`/
 *    `scope.out` IS a wrapper: its contract is DERIVED from those children
 *    (signature from the formal ports, kinds from their `kind` params, effect
 *    if any statement is one) and its binding is the program itself, resolved
 *    by name at run time through [LcncRunner.subprogramLoader]. This is the
 *    user-defined corpus entering the vocabulary as DATA — no recompile.
 *  - [LcncVocabulary] — the compiled contracts plus the composites of a
 *    corpus, resolved late, so `/api/lcnc/contracts`, the mating menu and the
 *    strict type check all see the same late-bound vocabulary.
 */
enum class LcncBindingKind {
    /** A Kotlin lambda in the registry — provenance is the class that made it. */
    KOTLIN,
    /** The canvas's own JavaScript method, run in a HostAccess.NONE GraalJS context. */
    CANVAS_JS,
    /** A stored program with formal ports, loaded by name when the walk reaches it. */
    COMPOSITE,
    /** A contract with no runner — `js`, `display`: the canvas renders it, the daemon skips it. */
    UNBOUND,
}

data class LcncBinding(val type: String, val kind: LcncBindingKind, val provenance: String)

object LcncWrappers {

    /** The single class that binds canvas JavaScript; its name is the lane's signature. */
    const val CANVAS_JS_BINDER = "CanvasJsPureNodes"

    /**
     * ONE pass over [registry] — every type in [contracts] gets a binding,
     * and every registry key outside the contracts gets one too (a runner
     * with no wrapper is a hidden type the palette cannot offer). [provenance]
     * is the one reflective act, performed here once per runner and never
     * again; [composites] are bound to the program that defines them.
     */
    fun bindings(
        contracts: Collection<LcncPortContract>,
        registry: Map<String, LcncNodeRunner>,
        provenance: (LcncNodeRunner) -> String,
        composites: Map<String, LcncPortContract> = emptyMap(),
    ): List<LcncBinding> {
        val out = ArrayList<LcncBinding>()
        val seen = HashSet<String>()
        fun bind(type: String) {
            if (!seen.add(type)) return
            val runner = registry[type]
            when {
                runner != null -> {
                    val by = provenance(runner)
                    val kind = if (by.contains(CANVAS_JS_BINDER)) LcncBindingKind.CANVAS_JS else LcncBindingKind.KOTLIN
                    out.add(LcncBinding(type, kind, by))
                }
                type in composites -> out.add(LcncBinding(type, LcncBindingKind.COMPOSITE, "program:$type"))
                else -> out.add(LcncBinding(type, LcncBindingKind.UNBOUND, ""))
            }
        }
        for (c in contracts) bind(c.type)
        for (t in composites.keys) bind(t)
        for (t in registry.keys.sorted()) bind(t)
        return out
    }
}

object LcncComposites {

    /**
     * The contract a stored program presents as a node — null when its top
     * ring declares no formal port (a program with nothing to bind is a
     * document, not a wrapper). Signature = top-level `scope.in` names as
     * inputs (a trailing `?` or a `default` makes one optional) and
     * `scope.out` names as outputs; kinds = the child's `kind` param, else
     * [LcncKinds.UNRESOLVED]; effect = any statement, at any depth, is one. The
     * same derivation the executor performs when it binds the envelope
     * ([LcncRunner.runRing]) — stated once, on the side that offers it.
     */
    fun contractOf(name: String, program: LcncProgram, contracts: Map<String, LcncPortContract>): LcncPortContract? =
        contractOf(name, program, LcncFacts.of(contracts.values, mapOf(name to program)))

    /** Same, over tuples already told — [all] tells the whole corpus once. Effect = `(node name ?n ?T) (effect ?T)`. */
    fun contractOf(name: String, program: LcncProgram, facts: LcncFacts): LcncPortContract? {
        val ins = ArrayList<Pair<String, String>>()   // (port spelling, kind)
        val outs = ArrayList<Pair<String, String>>()
        for (i in 0 until program.nodes.size) {
            val n = program.nodes[i]
            val bare = n.params["name"]?.removeSuffix("?")?.takeIf { it.isNotBlank() } ?: continue
            val kind = n.params["kind"]?.takeIf { it.isNotBlank() } ?: LcncKinds.UNRESOLVED
            when (n.type) {
                LcncContracts.SCOPE_IN -> {
                    val optional = n.params["name"]!!.endsWith("?") || n.params.containsKey("default")
                    ins.add((if (optional) "$bare?" else bare) to kind)
                }
                LcncContracts.SCOPE_OUT -> outs.add(bare to kind)
            }
        }
        if (ins.isEmpty() && outs.isEmpty()) return null
        return LcncPortContract(
            type = name,
            title = "$name (composite)",
            inputs = ins.map { it.first },
            outputs = outs.map { it.first },
            inputKinds = ins.associate { it.first.removeSuffix("?") to it.second },
            outputKinds = outs.associate { it.first to it.second },
            isEffect = facts.programHasEffect(name),
        )
    }

    /** Every program in [corpus] that presents a contract, keyed by name — the corpus told once. */
    fun all(corpus: Map<String, LcncProgram>, contracts: Map<String, LcncPortContract>): Map<String, LcncPortContract> {
        val out = LinkedHashMap<String, LcncPortContract>()
        val mine = corpus.filterKeys { it !in contracts } // a compiled type owns its name
        val facts = LcncFacts.of(contracts.values, mine)
        for ((name, program) in mine) contractOf(name, program, facts)?.let { out[name] = it }
        return out
    }
}

object LcncVocabulary {
    /**
     * The late-bound vocabulary: compiled contracts, then the composites the
     * [corpus] contributes. Compiled wins a name collision — a stored program
     * cannot shadow `timer`.
     */
    fun resolve(corpus: Map<String, LcncProgram>): Map<String, LcncPortContract> {
        val compiled = LcncContracts.all().associateBy { it.type }
        return LinkedHashMap(compiled).apply { putAll(LcncComposites.all(corpus, compiled)) }
    }
}
