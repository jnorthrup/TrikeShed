package borg.trikeshed.ontology

/**
 * OpenCyc — open-source fragment of Cyc (Cycorp, now released via OpenCyc.org)
 *
 * Light projection: the CycL constant namespace + selected upper collections that
 * overlap SUMO, plus transcription hooks to SUO-KIF.
 *
 * Authority: https://github.com/cycorp/OpenCyc (historical dump), docs at cyc.com/opencyc
 * CycL is the KR language; KIF is the interchange. This object is the bridge.
 */
object OpenCycOntology {

    /** Cyc constant (e.g. #$Person) and its doc. Light subset — upper tier only. */
    data class CycConstant(val cyclName: String, val doc: String, val sumoEq: SumoOntology.SumoCategory? = null)

    /** Canonical upper Cyc constants that have clear SUMO correspondents. */
    val upperConstants: List<CycConstant> = listOf(
        CycConstant("#\$Thing", "The universal collection", SumoOntology.SumoCategory.Entity),
        CycConstant("#\$Individual", "Thing that is not a collection", SumoOntology.SumoCategory.Object),
        CycConstant("#\$Collection", "Cyc collections/classes", SumoOntology.SumoCategory.Collection),
        CycConstant("#\$SetOrCollection", "Common generalization", SumoOntology.SumoCategory.SetOrClass),
        CycConstant("#\$SpatialThing", "Located in space", SumoOntology.SumoCategory.Physical),
        CycConstant("#\$TemporalThing", "Located in time / processual", SumoOntology.SumoCategory.Process),
        CycConstant("#\$Intangible", "Abstract, not spatially located", SumoOntology.SumoCategory.Abstract),
        CycConstant("#\$Agent-Generic", "Something that can act", SumoOntology.SumoCategory.Agent),
        CycConstant("#\$Artifact-Generic", "Made by an agent", SumoOntology.SumoCategory.Artifact),
        CycConstant("#\$Relation", "Cyc relations", SumoOntology.SumoCategory.Relation),
        CycConstant("#\$Attribute", "Cyc attributes", SumoOntology.SumoCategory.Attribute),
        CycConstant("#\$Proposition", "Cyc propositions / sentences", SumoOntology.SumoCategory.Proposition),
        CycConstant("#\$Quantity", "Quantities", SumoOntology.SumoCategory.Quantity),
    )

    /** Lookup by CycL literal (with or without #$). */
    fun resolve(cyclLiteral: String): CycConstant? {
        val key = if (cyclLiteral.startsWith("#\$")) cyclLiteral else "#\$$cyclLiteral"
        return upperConstants.firstOrNull { it.cyclName == key }
    }

    /** Resolve to SUMO category for KIF transcription. */
    fun toSumo(cyclLiteral: String): SumoOntology.SumoCategory? = resolve(cyclLiteral)?.sumoEq

    /** Emit CycL isa/ genls microtheory stubs that mirror SUMO subclass. */
    fun emitCycLUpper(): String = buildString {
        for (c in upperConstants) {
            val sumo = c.sumoEq?.kifName ?: continue
            // (#$isa <instance> <Collection>) + (#$genls <Sub> <Super>) → mirrored as comments
            appendLine(";; ${c.cyclName} ≈ $sumo")
            appendLine("(#\$isa ${c.cyclName} #\$Collection)")
        }
    }

    /** Known CycL predicate → KIF predicate light map. */
    val predicateMap: Map<String, String> = mapOf(
        "#\$isa" to "instance",
        "#\$genls" to "subclass",
        "#\$genlMt" to "subTheory",
        "#\$holdsIn" to "holdsDuring",
        "#\$performedBy" to "agent",
        "#\$causes" to "causes",
        "#\$contains" to "contains",
    )
}
