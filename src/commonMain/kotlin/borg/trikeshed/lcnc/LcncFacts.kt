package borg.trikeshed.lcnc

import borg.trikeshed.kif.KifExpr
import borg.trikeshed.kif.KifKnowledgeBase
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size

/**
 * The LCNC vocabulary AS TUPLES, and its questions as pattern queries.
 *
 * The daemon already has a fact substrate — [KifKnowledgeBase], the bank the
 * SUMO spine, `nal.mint` and `state.freeze` write to — with `assert` and a
 * `query(pattern)` that returns variable bindings. Every question the contract
 * table was answering with a bespoke loop (does this wire type, what mates
 * here, how often has this port fed that type, is this program an effect, who
 * binds this type) is a pattern over these relations. The Kotlin table becomes
 * ONE serialization of the tuples — `toKifFile` / [parse] round-trip every
 * contract field — and a `.kif` file is another; the round trip is test-pinned.
 *
 * Typing is EXACT ([LcncKinds]): a cable's type is its source port's type and
 * the sink must be that type. There is no `subclass` relation among kinds.
 *
 * The relations (atoms unless quoted; a port keeps its declared spelling):
 *
 *     (nodeType T) (label T "title") (source T) (sink T) (wide T) (effect T)
 *     (input T p?) (inKind T p K) (output T p) (outKind T p K)
 *     (cardinality T p MANY) (functions T p) (function T p "fn")
 *     (param T name) (paramDefault T name "v") (paramOption T name "o")
 *     (paramMultiline T name) (paramPlaceholder T name "ph") (paramCol T name "c") (paramLive T name "runner#path")
 *     (kind K)
 *     (introduces T K) (shape K key)      ; the json keys a literal's rows must carry to BE the CCEK type K
 *     (binding T how "provenance")        ; kotlin | canvas_js | composite | unbound
 *     (node G n T) (ring G n parent) (feeds G n p m q)   ; a program's instance graph, flat
 */
class LcncFacts private constructor(private val kb: KifKnowledgeBase) {

    companion object {
        /**
         * The compiled table, and any programs, as tuples.
         *
         * [into] is the bank the tuples are told to. The default is a private
         * bank per call (every type-check and mating question builds its own
         * throwaway view); the daemon hands its ONE shared bank in — the same
         * `kifBank` the SUMO spine, `nal.mint`, `state.freeze` and the plane
         * tee ([borg.trikeshed.dag.KifTee]) write — so `/api/beliefs/query
         * (nodeType ?t)` answers on the live bank. Telling the same vocabulary
         * into the same bank twice is a no-op: [KifKnowledgeBase.assert]
         * dedupes on the exact tuple string.
         */
        fun of(
            contracts: Collection<LcncPortContract>,
            programs: Map<String, LcncProgram> = emptyMap(),
            into: KifKnowledgeBase = KifKnowledgeBase(),
        ): LcncFacts {
            val f = LcncFacts(into)
            for (k in LcncKinds.CONFIX_SLOTS) f.kind(k)
            f.kind(LcncKinds.CCEK_ANY)
            for (c in contracts) f.learn(c)
            for ((g, p) in programs) f.learn(g, p)
            return f
        }

        /** A vocabulary authored as a `.kif` file; [into] as for [of]. */
        fun parse(kif: String, into: KifKnowledgeBase = KifKnowledgeBase()): LcncFacts {
            val f = LcncFacts(into)
            for (e in KifExpr.parseAll(kif)) f.kb.assert(e)
            return f
        }

        private fun str(s: String): String =
            "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

        private fun unstr(t: String): String {
            if (t.length < 2 || !t.startsWith("\"") || !t.endsWith("\"")) return t
            val sb = StringBuilder()
            var i = 1
            while (i < t.length - 1) {
                val c = t[i]
                if (c == '\\' && i + 1 < t.length - 1) { sb.append(t[i + 1]); i += 2 } else { sb.append(c); i++ }
            }
            return sb.toString()
        }
        fun accepts(source: String, target: String): Boolean =
            source == LcncKinds.UNRESOLVED || target == LcncKinds.UNRESOLVED ||
                LcncKinds.isTypeVariable(source) || LcncKinds.isTypeVariable(target) ||
                target == LcncKinds.CCEK_ANY || source == target

        fun accepts(a: LcncTypeCheck.PortKind, b: LcncTypeCheck.PortKind): Boolean = when {
            a.generic || b.generic -> true
            a.kind == null || b.kind == null -> false
            else -> accepts(a.kind, b.kind)
        }
    }

    // ── telling ──────────────────────────────────────────────────────────

    /**
     * An atom the tokenizer would split — `Map<String, Any>`, a param option with a
     * space, an empty string — is told as a KIF string; already-quoted values pass.
     * Every read goes through [unstr], so the round trip is exact either way.
     */
    private fun atom(a: String): String = when {
        a.length >= 2 && a.startsWith("\"") && a.endsWith("\"") -> a
        a.isEmpty() || a.any { it == ' ' || it == '(' || it == ')' || it == '"' || it == ';' || it == '\n' || it == '\r' || it == '\t' } -> str(a)
        else -> a
    }

    private fun tell(vararg atoms: String) = kb.assert(KifExpr.ListExpr(atoms.map { KifExpr.Atom(atom(it)) }))

    private fun kind(k: String) {
        if (k == LcncKinds.UNRESOLVED) return
        tell("kind", k)
    }

    private fun learn(c: LcncPortContract) {
        val t = c.type
        tell("nodeType", t); tell("label", t, str(c.title))
        for (p in c.inputs) tell("input", t, p)
        for ((p, k) in c.inputKinds) { tell("inKind", t, p, k); kind(k) }
        for (p in c.outputs) tell("output", t, p)
        for ((p, k) in c.outputKinds) { tell("outKind", t, p, k); kind(k) }
        for ((p, card) in c.cardinality) tell("cardinality", t, p, card.name)
        for ((p, fns) in c.functions) { tell("functions", t, p); for (fn in fns) tell("function", t, p, str(fn)) }
        for ((name, s) in c.params) {
            tell("param", t, name)
            if (s.v.isNotEmpty()) tell("paramDefault", t, name, str(s.v))
            for (o in s.opts) tell("paramOption", t, name, str(o))
            if (s.ta) tell("paramMultiline", t, name)
            if (s.ph.isNotEmpty()) tell("paramPlaceholder", t, name, str(s.ph))
            for (col in s.cols) tell("paramCol", t, name, str(col))
            if (s.optsFrom.isNotEmpty()) tell("paramLive", t, name, str(s.optsFrom))
        }
        if (c.isSource) tell("source", t)
        if (c.isSink) tell("sink", t)
        if (c.wide) tell("wide", t)
        if (c.isEffect) tell("effect", t)
        for ((k, keys) in c.kindShapes) { tell("introduces", t, k); kind(k); for (key in keys) tell("shape", k, key) }
    }

    private fun learn(g: String, p: LcncProgram) {
        fun walk(nodes: Series<LcncNode>, parent: String?) {
            for (i in 0 until nodes.size) {
                val n = nodes[i]
                tell("node", g, n.id, n.type)
                parent?.let { tell("ring", g, n.id, it) }
                if (n.children.size > 0) walk(n.children, n.id)
            }
        }
        walk(p.nodes, null)
        for (i in 0 until p.wires.size) {
            val w = p.wires[i]
            tell("feeds", g, w.fromNode, w.fromPort.removeSuffix("?"), w.toNode, w.toPort.removeSuffix("?"))
        }
    }

    /** The bindings, as tuples — one pass ([LcncWrappers.bindings]), told once. */
    fun learn(bindings: List<LcncBinding>): LcncFacts {
        for (b in bindings) tell("binding", b.type, b.kind.name.lowercase(), str(b.provenance))
        return this
    }

    fun toKifFile(): String = kb.toKifFile()

    // ── asking ───────────────────────────────────────────────────────────

    /** One pattern: null = a variable; rows are the variables' values, in argument order, distinct. */
    private fun q(pred: String, vararg args: String?): List<List<String>> {
        val pattern = KifExpr.ListExpr(
            listOf(KifExpr.Atom(pred)) + args.mapIndexed { i, a -> if (a == null) KifExpr.Var("?v$i") else KifExpr.Atom(atom(a)) },
        )
        val vars = args.indices.filter { args[it] == null }.map { "?v$it" }
        return kb.query(pattern).map { m -> vars.map { unstr(m.getValue(it)) } }.distinct()
    }

    private fun has(pred: String, vararg args: String): Boolean = q(pred, *args).isNotEmpty()

    /**
     * May a cable of [source] type plug into a port of [target] type? Exactly
     * when they are the same type, or the sink is a runner that declares `Any`.
     * An unresolved ring port claims nothing either way.
     */
    fun accepts(source: String, target: String): Boolean =
        source == LcncKinds.UNRESOLVED || target == LcncKinds.UNRESOLVED ||
            // A type variable is resolved per node by the checker; on its own it claims nothing.
            LcncKinds.isTypeVariable(source) || LcncKinds.isTypeVariable(target) ||
            target == LcncKinds.CCEK_ANY || source == target

    fun accepts(a: LcncTypeCheck.PortKind, b: LcncTypeCheck.PortKind): Boolean = when {
        a.generic || b.generic -> true
        a.kind == null || b.kind == null -> false
        else -> accepts(a.kind, b.kind)
    }

    fun types(): List<String> = q("nodeType", null).map { it[0] }
    fun kinds(): List<String> = q("kind", null).map { it[0] }.sorted()

    /** There is no subtyping among kinds; served for the canvas's sake, always empty. */
    fun hierarchy(): List<Pair<String, String>> = emptyList()

    /**
     * kind → every port type a cable of that kind may plug into: itself, `Any`, and any
     * type variable (the daemon resolves the variable; the canvas cannot). A variable as
     * a SOURCE lists every kind for the same reason. The canvas's lookup table.
     */
    fun acceptance(): Map<String, List<String>> {
        val all = kinds()
        val variables = all.filter { LcncKinds.isTypeVariable(it) }
        return all.associateWith { k ->
            if (LcncKinds.isTypeVariable(k)) all.sorted()
            else (listOf(k, LcncKinds.CCEK_ANY) + variables).distinct().sorted()
        }
    }

    fun shape(kind: String): List<String> = q("shape", kind, null).map { it[0] }
    fun shapes(): Map<String, List<String>> = q("shape", null, null).groupBy({ it[0] }, { it[1] }).toSortedMap()

    fun label(type: String): String? = q("label", type, null).firstOrNull()?.let { unstr(it[0]) }

    /** Declared input spellings of [type]; `?` marks optional. */
    fun inputs(type: String): List<String> = q("input", type, null).map { it[0] }
    fun outputs(type: String): List<String> = q("output", type, null).map { it[0] }
    fun inKind(type: String, port: String): String? = q("inKind", type, port.removeSuffix("?"), null).firstOrNull()?.get(0)
    fun outKind(type: String, port: String): String? = q("outKind", type, port.removeSuffix("?"), null).firstOrNull()?.get(0)

    /** Every (type, input spelling) a cable of [kind] may plug into — vocabulary order. */
    fun compatibleInputs(kind: String): List<Pair<String, String>> =
        q("inKind", null, null, null)
            .filter { (_, _, k) -> accepts(kind, k) }
            .mapNotNull { (t, p, _) -> inputs(t).firstOrNull { it.removeSuffix("?") == p }?.let { t to it } }

    /** Every (from output, to input) pair between two types the cable rule allows. */
    fun autoWire(from: String, to: String): List<LcncAutoWireCandidate> =
        outputs(from).flatMap { op ->
            val ok = outKind(from, op) ?: return@flatMap emptyList()
            inputs(to).mapNotNull { ip ->
                val ik = inKind(to, ip) ?: return@mapNotNull null
                if (accepts(ok, ik)) LcncAutoWireCandidate(op, ip, ok) else null
            }
        }

    /** How often, over the programs told, an output `sourceType.sourcePort` feeds each target type. */
    fun feedsInto(sourceType: String, sourcePort: String): Map<String, Int> {
        val counts = HashMap<String, Int>()
        for ((g, n, m) in q("feeds", null, null, sourcePort.removeSuffix("?"), null, null)) {
            if (!has("node", g, n, sourceType)) continue
            for ((t) in q("node", g, m, null)) counts[t] = (counts[t] ?: 0) + 1
        }
        return counts
    }

    fun isEffect(type: String): Boolean = has("effect", type)

    /** A program is an effect when any statement, at any depth, is one. */
    fun programHasEffect(g: String): Boolean = q("node", g, null, null).any { (_, t) -> has("effect", t) }

    fun bindingOf(type: String): Pair<String, String>? =
        q("binding", type, null, null).firstOrNull()?.let { it[0] to unstr(it[1]) }

    /** (type, output port, declared kind) for every LITERAL port — see [LcncKinds.isLiteral]. */
    fun literalPorts(): List<Triple<String, String, String>> {
        val withInputs = q("input", null, null).map { it[0] }.toSet()
        val params = q("param", null, null).map { it[0] to it[1] }.toSet()
        return q("outKind", null, null, null)
            .filter { (t, p, _) -> t !in withInputs && (t to p) in params }
            .map { (t, p, k) -> Triple(t, p, k) }
    }

    /** Every binding, one pass: type → (how, provenance). */
    fun bindings(): Map<String, Pair<String, String>> =
        q("binding", null, null, null).associate { (t, how, by) -> t to (how to by) }

    /**
     * A literal's type is what its value MATCHES: the CCEK type whose shape
     * every row of the authored array carries (the most demanding shape wins);
     * otherwise the Confix slot it was declared with. A value that does not
     * parse, is empty, or is not an array of objects matches nothing.
     */
    fun refineLiteral(node: LcncNode, port: String, declared: String): String {
        if (declared != "json") return declared
        val raw = node.params[port] ?: return declared
        val rows = runCatching { borg.trikeshed.parse.json.JsonSupport.parse(raw) }.getOrNull() as? List<*> ?: return declared
        if (rows.isEmpty() || rows.any { it !is Map<*, *> }) return declared
        var best: Pair<String, Int>? = null
        for ((k, keys) in shapes()) {
            if (rows.all { row -> keys.all { (row as Map<*, *>).containsKey(it) } } && keys.size > (best?.second ?: -1)) best = k to keys.size
        }
        return best?.first ?: declared
    }

    /** THE VIEW: a contract read back from the tuples, field for field. */
    fun contract(type: String): LcncPortContract? {
        if (!has("nodeType", type)) return null
        return LcncPortContract(
            type = type,
            title = label(type) ?: type,
            inputs = inputs(type),
            outputs = outputs(type),
            cardinality = q("cardinality", type, null, null).associate { (p, c) -> p to LcncCardinality.valueOf(c) },
            functions = q("functions", type, null).associate { (p) -> p to q("function", type, p, null).map { unstr(it[0]) } },
            inputKinds = q("inKind", type, null, null).associate { (p, k) -> p to k },
            outputKinds = q("outKind", type, null, null).associate { (p, k) -> p to k },
            params = q("param", type, null).associate { (name) ->
                name to LcncPortContract.LcncParamSpec(
                    v = q("paramDefault", type, name, null).firstOrNull()?.let { unstr(it[0]) } ?: "",
                    opts = q("paramOption", type, name, null).map { unstr(it[0]) },
                    ta = has("paramMultiline", type, name),
                    ph = q("paramPlaceholder", type, name, null).firstOrNull()?.let { unstr(it[0]) } ?: "",
                    cols = q("paramCol", type, name, null).map { unstr(it[0]) },
                    optsFrom = q("paramLive", type, name, null).firstOrNull()?.let { unstr(it[0]) } ?: "",
                )
            },
            isSource = has("source", type),
            isSink = has("sink", type),
            wide = has("wide", type),
            isEffect = has("effect", type),
            kindShapes = q("introduces", type, null).associate { (k) -> k to shape(k) },
        )
    }

    fun contracts(): Map<String, LcncPortContract> = types().associateWith { contract(it)!! }
}
