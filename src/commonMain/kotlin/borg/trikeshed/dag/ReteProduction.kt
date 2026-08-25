package borg.trikeshed.dag

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series

/**
 * ReteProduction — the rule seam the network never had. Productions declare
 * their alpha INTERESTS (field ⋈ value pairs); the network keeps incremental
 * per-partition interest counters and skips [evaluate] entirely when every
 * interest count is zero — the generalization of the old hardcoded
 * `lifecycle=="submitted"` short-circuit that silently starved any other rule.
 *
 * Kept deliberately shallow (production-lesson: 1–3 chain depth, wide and
 * auditable): a production reads working memory, decides, and FIREs
 * activations; it never mutates memory itself.
 */
interface ReteProduction {
    val ruleId: String
    val salience: Int

    /** Alpha interests: fields whose presence (field == value) makes this rule worth evaluating. */
    val interests: Series<Join<String, Any?>>

    fun evaluate(net: ReteNetwork, partitionId: String, fire: (Activation) -> Unit)
}

/**
 * Registry consulted by [ReteNetwork.evaluateRules] in salience order.
 * Registration returns a disposer — the module-handle discipline: a detached
 * module takes its rules with it.
 *
 * COW behind a volatile reference (commonMain: no JVM monitors): readers are
 * lock-free; WRITERS must already be serialized — registration flows through
 * ModuleSupervisor's attach mutex / the daemon boot path, never concurrently.
 */
class ReteProductionRegistry {
    @kotlin.concurrent.Volatile
    private var productions: List<ReteProduction> = emptyList()

    fun register(p: ReteProduction): AutoCloseable {
        val current = productions
        require(current.none { it.ruleId == p.ruleId }) { "duplicate ruleId ${p.ruleId}" }
        productions = (current + p).sortedByDescending { it.salience }
        return AutoCloseable { productions = productions.filter { it !== p } }
    }

    fun all(): List<ReteProduction> = productions

    val size: Int get() = productions.size
}
