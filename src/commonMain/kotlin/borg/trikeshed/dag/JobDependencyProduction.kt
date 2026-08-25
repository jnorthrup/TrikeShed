package borg.trikeshed.dag

import borg.trikeshed.cursor.BlackboardContext
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j

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
        val jobs = net.workingMemory.query(BlackboardContext(partitionId), "lifecycle" to "submitted")
        val tokens = net.betaMemory.tokens().filter { it.left.factId.partitionId == partitionId }

        for (jobFact in jobs) {
            @Suppress("UNUSED_VARIABLE")
            val jobId = jobFact.fields["jobId"] as? String ?: continue
            @Suppress("UNCHECKED_CAST")
            val deps = jobFact.fields["dependencies"] as? List<String> ?: emptyList()

            if (deps.isEmpty()) {
                fire(start(jobFact, emptyList()))
                continue
            }

            val jobTokens = tokens.filter { it.left.factId == jobFact.factId }
            if (jobTokens.size < deps.size) continue // Wait until all dependencies are available in tokens

            val anyFailed = jobTokens.firstOrNull { it.right.fields["lifecycle"] == "failed" }
            if (anyFailed != null) {
                fire(block(jobFact, listOf(anyFailed.right)))
                continue
            }

            val allClosed = jobTokens.all { it.right.fields["lifecycle"] == "closed" }
            if (allClosed && jobTokens.size == deps.size) {
                fire(start(jobFact, jobTokens.map { it.right }))
            }
        }
    }

    private fun start(jobFact: ReteStoredFact, supportFacts: List<ReteStoredFact>): Activation = Activation(
        activationId = "start-${jobFact.factId.localId}",
        ruleId = START_RULE,
        ruleVersionCid = borg.trikeshed.job.ContentId.of("rule-start-v1".encodeToByteArray()),
        salience = 100,
        sequence = (jobFact.fields["revision"] as? Long) ?: 0L,
        supportCids = listOf(jobFact.versionCid) + supportFacts.map { it.versionCid },
        bindings = mapOf("jobId" to (jobFact.fields["jobId"] as String)),
    )

    private fun block(jobFact: ReteStoredFact, supportFacts: List<ReteStoredFact>): Activation = Activation(
        activationId = "block-${jobFact.factId.localId}",
        ruleId = BLOCK_RULE,
        ruleVersionCid = borg.trikeshed.job.ContentId.of("rule-block-v1".encodeToByteArray()),
        salience = 100,
        sequence = (jobFact.fields["revision"] as? Long) ?: 0L,
        supportCids = listOf(jobFact.versionCid) + supportFacts.map { it.versionCid },
        bindings = mapOf("jobId" to (jobFact.fields["jobId"] as String), "reason" to "dependency failed"),
    )
}
