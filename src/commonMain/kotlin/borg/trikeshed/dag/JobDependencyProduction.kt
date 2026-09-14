package borg.trikeshed.dag

import borg.trikeshed.cursor.BlackboardContext
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.iterator
import borg.trikeshed.lib.size
import borg.trikeshed.lib.filter
import borg.trikeshed.lib.view
import borg.trikeshed.lib.α
import borg.trikeshed.lib.toList
import borg.trikeshed.lib.s_
import borg.trikeshed.lib.plus
import borg.trikeshed.lib.emptySeriesOf

/**
 * JobDependencyProduction — behavior-preserving extraction of the network's
 * old hardcoded evaluateRules: submitted jobs start when dependency-free or
 * when every dependency token is closed; a failed dependency blocks. The
 * declared interest ("lifecycle" ⋈ "submitted") IS the old submittedCount
 * short-circuit, now expressed through the generalized gate.
 */
class JobDependencyProduction : ReteProduction {

    companion object {
        const val START_RULE = "start-job"
        const val BLOCK_RULE = "block-job"
    }

    override val ruleId: String = "job-dependency"
    override val salience: Int = 100

    override val interests: Series<Join<String, Any?>> = 1 j { _: Int -> "lifecycle" j ("submitted" as Any?) }

    override fun evaluate(net: ReteNetwork, partitionId: String, fire: (Activation) -> Unit) {
        val jobs = net.workingMemory.query(BlackboardContext(partitionId), "lifecycle" j "submitted")
        val tokens = net.betaMemory.tokens().filter { it.a.factId.a == partitionId }

        for (jobFact in jobs) {
            @Suppress("UNUSED_VARIABLE")
            val jobId = jobFact.fields["jobId"] as? String ?: continue
            @Suppress("UNCHECKED_CAST")
            val deps = jobFact.fields["dependencies"] as? List<String> ?: emptyList()

            if (deps.isEmpty()) {
                fire(start(jobFact, emptySeriesOf()))
                continue
            }

            val jobTokens = tokens.filter { it.a.factId.a == jobFact.factId.a && it.a.factId.b == jobFact.factId.b }
            if (jobTokens.size < deps.size) continue // Wait until all dependencies are available in tokens

            val anyFailed = jobTokens.view.firstOrNull { it.b.fields["lifecycle"] == "failed" }
            if (anyFailed != null) {
                fire(block(jobFact, s_[anyFailed.b]))
                continue
            }

            val allClosed = jobTokens.view.all { it.b.fields["lifecycle"] == "closed" }
            if (allClosed && jobTokens.size == deps.size) {
                fire(start(jobFact, jobTokens.α { it.b }))
            }
        }
    }

    private fun start(jobFact: ReteStoredFact, supportFacts: Series<ReteStoredFact>): Activation = Activation(
        activationId = "start-${jobFact.factId.b}",
        ruleId = START_RULE,
        ruleVersionCid = borg.trikeshed.job.ContentId.of("rule-start-v1".encodeToByteArray()),
        salience = 100,
        sequence = (jobFact.fields["revision"] as? Long) ?: 0L,
        supportCids = (s_[jobFact.versionCid] + supportFacts.α { it.versionCid }).toList(),
        bindings = mapOf("jobId" to (jobFact.fields["jobId"] as String)),
    )

    private fun block(jobFact: ReteStoredFact, supportFacts: Series<ReteStoredFact>): Activation = Activation(
        activationId = "block-${jobFact.factId.b}",
        ruleId = BLOCK_RULE,
        ruleVersionCid = borg.trikeshed.job.ContentId.of("rule-block-v1".encodeToByteArray()),
        salience = 100,
        sequence = (jobFact.fields["revision"] as? Long) ?: 0L,
        supportCids = (s_[jobFact.versionCid] + supportFacts.α { it.versionCid }).toList(),
        bindings = mapOf("jobId" to (jobFact.fields["jobId"] as String), "reason" to "dependency failed"),
    )
}
