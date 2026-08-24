package borg.trikeshed.ontology

/**
 * SUMO — Suggested Upper Merged Ontology (IEEE P1600.1, ontologyportal/sumo)
 *
 * Light commonMain projection of the upper tier. Not the full 20k-term SUMO
 * Merge.kif — the *upper* spine that matters for TrikeShed integration:
 * Entity ⊃ {Physical, Abstract} ; Physical ⊃ {Object, Process} etc.
 * Each term carries its SUO-KIF name, WordNet mapping hook, and doc.
 *
 * Source authority: https://github.com/ontologyportal/sumo (Merge.kif, Mid-level-ontology.kif)
 * License: SUMO is IEEE-owned, open-source (GNU GPL for domain ontologies).
 *
 * This object is the commonMain-sourced spine — every KIF, CycL, RDF node
 * serializes through it. JVM actuals may load the full .kif/.owl if present.
 */
object SumoOntology {

    /** SUO-KIF category — the upper genealogy used for projection. */
    enum class SumoCategory(val kifName: String, val doc: String) {
        Entity("Entity", "The universal class of individuals"),
        Physical("Physical", "An entity that has a location in space-time"),
        Abstract("Abstract", "Properties or qualities as distinguished from any particular instance"),
        Object("Object", "Corresponds roughly to the class of ordinary objects"),
        Process("Process", "The class of things that happen and have temporal parts"),
        Quantity("Quantity", "Any specification of how many or how much"),
        Relation("Relation", "Class of relations"),
        Attribute("Attribute", "Qualities, properties, etc."),
        Proposition("Proposition", "Abstract entities that express a complete thought"),
        Agent("Agent", "Something or someone that can act on its own and produce changes"),
        Artifact("Artifact", "An object made by an agent"),
        Collection("Collection", "Collections have members like classes"),
        SetOrClass("SetOrClass", "The common generalization of sets and classes");

        fun kifTypeAxiom(instance: String): String = "(instance $instance $kifName)"
    }

    data class WordNetMapping(val synset: String, val sumoTerm: SumoCategory, val mappingRelation: String = "equivalenceMapping")

    val hierarchy: Map<SumoCategory, List<SumoCategory>> = mapOf(
        SumoCategory.Entity to listOf(SumoCategory.Physical, SumoCategory.Abstract),
        SumoCategory.Physical to listOf(SumoCategory.Object, SumoCategory.Process, SumoCategory.Quantity),
        SumoCategory.Abstract to listOf(SumoCategory.Quantity, SumoCategory.Attribute, SumoCategory.Proposition, SumoCategory.Relation, SumoCategory.SetOrClass),
        SumoCategory.Object to listOf(SumoCategory.Agent, SumoCategory.Artifact, SumoCategory.Collection)
    )

    val allKifConstants: List<String> = SumoCategory.entries.map { it.kifName }

    fun emitUpperKif(): String = buildString {
        for ((parent, children) in hierarchy) {
            for (child in children) appendLine("(subclass ${child.kifName} ${parent.kifName})")
        }
        appendLine("(documentation Entity EnglishLanguage \"The universal class of individuals.\")")
    }

    fun resolveKifToken(token: String): SumoCategory? = SumoCategory.entries.firstOrNull { it.kifName == token }

    fun isSubclass(child: SumoCategory, ancestor: SumoCategory): Boolean {
        if (child == ancestor) return true
        val visited = mutableSetOf<SumoCategory>()
        fun dfs(cur: SumoCategory): Boolean {
            if (cur in visited) return false
            visited.add(cur)
            val directParents = hierarchy.entries.filter { cur in it.value }.map { it.key }
            return directParents.any { it == ancestor || dfs(it) }
        }
        return dfs(child)
    }
}
