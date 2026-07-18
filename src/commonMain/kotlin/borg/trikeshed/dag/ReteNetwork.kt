package borg.trikeshed.dag

import borg.trikeshed.cursor.BlackboardContext

/**
 * Production ReteNetwork. Owns the memories and coordinates rule matching.
 */
class ReteNetwork {
    val workingMemory = ReteWorkingMemory()
    val alphaMemory = ReteAlphaMemory()
    val betaMemory = ReteBetaMemory(BetaJoin("dependsOn", "jobId"))
    val agenda = ReteAgenda()
    val refraction = ReteRefraction()

    fun assert(
        factId: FactId,
        fields: Map<String, Any?>,
        versionCid: borg.trikeshed.job.ContentId,
        board: BlackboardContext,
    ) {
        val result = workingMemory.assert(factId, fields, versionCid, board)
        if (result.isNew) {
            alphaMemory.accept(result.fact)
            betaMemory.acceptLeft(result.fact)
            betaMemory.acceptRight(result.fact)
            evaluateRules(board.id)
            signalAgenda()
        }
    }

    fun modify(
        factId: FactId,
        fields: Map<String, Any?>,
        versionCid: borg.trikeshed.job.ContentId,
    ) {
        // Find old fact for invalidation
        val oldFacts = workingMemory.facts(factId)
        if (oldFacts.isNotEmpty()) {
            val oldFact = oldFacts.first()
            agenda.removeBySupport(oldFact.versionCid)
            refraction.invalidateBySupport(oldFact.versionCid)
        }

        val fact = workingMemory.modify(factId, fields, versionCid)
        alphaMemory.accept(fact)
        betaMemory.acceptLeft(fact)
        betaMemory.acceptRight(fact)
        evaluateRules(fact.factId.partitionId)
        signalAgenda()
    }

    fun retract(factId: FactId) {
        val facts = workingMemory.facts(factId)
        if (facts.isNotEmpty()) {
            val fact = facts.first()
            if (workingMemory.retract(factId)) {
                alphaMemory.retract(factId)
                betaMemory.retractLeft(factId)
                betaMemory.retractRight(factId)
                agenda.removeBySupport(fact.versionCid)
                refraction.invalidateBySupport(fact.versionCid)
                evaluateRules(factId.partitionId)
                signalAgenda()
            }
        }
    }

    private val signal = kotlinx.coroutines.channels.Channel<Unit>(capacity = kotlinx.coroutines.channels.Channel.CONFLATED)

    private fun signalAgenda() {
        signal.trySend(Unit)
    }

    fun evaluateRules(partitionId: String) {
        val jobs = workingMemory.query(BlackboardContext(partitionId), "lifecycle" to "submitted")
        val tokens = betaMemory.tokens().filter { it.left.factId.partitionId == partitionId }

        for (jobFact in jobs) {
            val jobId = jobFact.fields["jobId"] as? String ?: continue
            val deps = jobFact.fields["dependencies"] as? List<String> ?: emptyList()

            if (deps.isEmpty()) {
                fireStart(jobFact, emptyList())
                continue
            }

            val jobTokens = tokens.filter { it.left.factId == jobFact.factId }
            if (jobTokens.size < deps.size) continue // Wait until all dependencies are available in tokens

            val anyFailed = jobTokens.firstOrNull { it.right.fields["lifecycle"] == "failed" }
            if (anyFailed != null) {
                fireBlock(jobFact, listOf(anyFailed.right))
                continue
            }

            val allClosed = jobTokens.all { it.right.fields["lifecycle"] == "closed" }
            if (allClosed && jobTokens.size == deps.size) {
                fireStart(jobFact, jobTokens.map { it.right })
            }
        }
    }

    private fun fireStart(jobFact: ReteStoredFact, supportFacts: List<ReteStoredFact>) {
        val activation = Activation(
            activationId = "start-${jobFact.factId.localId}",
            ruleId = "start-job",
            ruleVersionCid = borg.trikeshed.job.ContentId.of("rule-start-v1".encodeToByteArray()),
            salience = 100,
            sequence = (jobFact.fields["revision"] as? Long) ?: 0L,
            supportCids = listOf(jobFact.versionCid) + supportFacts.map { it.versionCid },
            bindings = mapOf("jobId" to (jobFact.fields["jobId"] as String))
        )
        if (refraction.record(activation)) {
            agenda.add(activation)
        }
    }

    private fun fireBlock(jobFact: ReteStoredFact, supportFacts: List<ReteStoredFact>) {
        val activation = Activation(
            activationId = "block-${jobFact.factId.localId}",
            ruleId = "block-job",
            ruleVersionCid = borg.trikeshed.job.ContentId.of("rule-block-v1".encodeToByteArray()),
            salience = 100,
            sequence = (jobFact.fields["revision"] as? Long) ?: 0L,
            supportCids = listOf(jobFact.versionCid) + supportFacts.map { it.versionCid },
            bindings = mapOf("jobId" to (jobFact.fields["jobId"] as String), "reason" to "dependency failed")
        )
        if (refraction.record(activation)) {
            agenda.add(activation)
        }
    }

    suspend fun run(output: kotlinx.coroutines.channels.SendChannel<borg.trikeshed.job.JobCommand>) {
        try {
            for (sig in signal) {
                while (true) {
                    val activation = agenda.popNext() ?: break
                    val jobId = borg.trikeshed.job.JobId.of(activation.bindings["jobId"]!!)
                    val expectedRevision = activation.sequence
                    val ik = "rete-${activation.activationId}-$expectedRevision"
                    val cmd = if (activation.ruleId == "start-job") {
                        borg.trikeshed.job.JobCommand.Start(jobId, ik, expectedRevision = expectedRevision)
                    } else {
                        borg.trikeshed.job.JobCommand.Block(jobId, ik, expectedRevision = expectedRevision, reason = activation.bindings["reason"]!!)
                    }
                    output.send(cmd)
                }
            }
        } catch (e: kotlinx.coroutines.channels.ClosedSendChannelException) {
            // Channel is closed, meaning supervisor is draining/cancelled.
            // It's safe to exit the run loop gracefully.
        }
    }

    fun close() {
        signal.close()
    }
}