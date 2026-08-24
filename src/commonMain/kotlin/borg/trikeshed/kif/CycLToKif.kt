package borg.trikeshed.kif

import borg.trikeshed.ontology.OpenCycOntology
import borg.trikeshed.ontology.SumoOntology

/**
 * CycL → KIF transcriber (light effort, upper ontology only)
 *
 * CycL: (#$isa Fido #$Dog) (#$genls #$Dog #$Animal) (#$holdsIn #$Mt #$Sentence)
 * KIF:  (instance Fido Dog)  (subclass Dog Animal)
 *
 * Rules:
 *  - strip #$ sigil, keep constants as KIF atoms (Cyc #$Thing → Thing, but prefer SUMO eq)
 *  - (#$isa x Y) → (instance x' y')
 *  - (#$genls X Y) → (subclass x' y')
 *  - predicateMap (performedBy → agent etc.)
 *  - unknown predicate: keep sym without #$ as KIF predicate
 *  - Microtheory (#$holdsIn mt form) → (holdsDuring mt' form')
 *  - Light: no HL quantification, no #$Microtheory reification beyond holdsDuring.
 */
object CycLToKif {

    /** Transcribe one CycL sentence (one paren form) to KIF. */
    fun transcribe(cycl: String): KifExpr {
        val trimmed = cycl.trim()
        if (trimmed.isEmpty()) return KifExpr.Atom("")
        val expr = KifExpr.parse(trimmed)
        return transExpr(expr)
    }

    /** Transcribe a CycL file (multiple sentences). */
    fun transcribeAll(cyclFile: String): List<KifExpr> =
        KifExpr.parseAll(cyclFile).map { transExpr(it) }

    fun toKifString(cycl: String): String = transcribe(cycl).toKifString()
    fun toKifStringAll(cyclFile: String): String = transcribeAll(cyclFile).joinToString("\n") { it.toKifString() }

    private fun transExpr(e: KifExpr): KifExpr = when (e) {
        is KifExpr.Atom -> transAtom(e)
        is KifExpr.Var -> e // ?X stays
        is KifExpr.Quoted -> KifExpr.Quoted(transExpr(e.expr))
        is KifExpr.ListExpr -> {
            if (e.elements.isEmpty()) e else {
                val head = e.elements[0]
                val headStr = (head as? KifExpr.Atom)?.token ?: ""
                when (headStr) {
                    "#\$isa", "isa" -> {
                        if (e.elements.size >= 3) kif("instance", transTerm(e.elements[1]), transTerm(e.elements[2])) else listTrans(e)
                    }
                    "#\$genls", "genls" -> {
                        if (e.elements.size >= 3) kif("subclass", transTerm(e.elements[1]), transTerm(e.elements[2])) else listTrans(e)
                    }
                    "#\$holdsIn", "#\$holdsDuring" -> {
                        if (e.elements.size >= 3) kif("holdsDuring", transTerm(e.elements[1]), transExpr(e.elements[2])) else listTrans(e)
                    }
                    else -> {
                        val kifPred = OpenCycOntology.predicateMap[headStr] ?: stripCycSigil(headStr)
                        KifExpr.ListExpr(listOf(KifExpr.Atom(kifPred)) + e.elements.drop(1).map { transTerm(it) })
                    }
                }
            }
        }
    }

    private fun listTrans(e: KifExpr.ListExpr): KifExpr.ListExpr =
        KifExpr.ListExpr(e.elements.map { transExpr(it) })

    private fun transTerm(e: KifExpr): KifExpr = when (e) {
        is KifExpr.Atom -> transAtom(e)
        is KifExpr.Var -> e
        is KifExpr.Quoted -> KifExpr.Quoted(transTerm(e.expr))
        is KifExpr.ListExpr -> transExpr(e)
    }

    private fun transAtom(a: KifExpr.Atom): KifExpr.Atom {
        val t = a.token
        if (!t.startsWith("#\$")) return a
        val bare = t.removePrefix("#\$")
        // prefer SUMO equivalent when known
        val sumo = OpenCycOntology.toSumo(t)
        return if (sumo != null) KifExpr.Atom(sumo.kifName) else KifExpr.Atom(bare)
    }

    private fun stripCycSigil(s: String): String = if (s.startsWith("#\$")) s.removePrefix("#\$") else s

    /** Quick sanity: round-trip wordnet-ish check. */
    fun examplePairs(): List<Pair<String, String>> = listOf(
        "(#\$isa Fido #\$Dog)" to "(instance Fido Dog)",
        "(#\$genls #\$Dog #\$Animal)" to "(subclass Dog Animal)",
        "(#\$isa #\$Thing #\$Collection)" to "(instance Thing Collection)",
    )
}
