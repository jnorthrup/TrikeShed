package borg.trikeshed.agent.modelmux

import borg.trikeshed.context.nuid.*
import borg.trikeshed.lib.*
import kotlinx.coroutines.*

/** Contrastive search scope, not an assessment that a candidate is correct. */
enum class HarnessScope { INVARIANT, DOMAIN }
data class HarnessRevision(val id: String, val components: Series<Join<String, String>>)
data class HarnessObservation(
    val id: String, val harness: String, val model: String, val environment: String,
    val deliverable: String,
)
data class HarnessComposition(val revision: borg.trikeshed.agent.modelmux.HarnessRevision, val evidence: Series<String>)
data class HarnessStep(
    val reference: borg.trikeshed.agent.modelmux.HarnessObservation,
    val probes: Series<Join<borg.trikeshed.agent.modelmux.HarnessRevision, borg.trikeshed.agent.modelmux.HarnessObservation>>,
    val composition: borg.trikeshed.agent.modelmux.HarnessComposition,
    val successor: borg.trikeshed.agent.modelmux.HarnessObservation,
)
class HarnessOperation(val execute: suspend () -> Any?)

/**
 * One bounded Harness2 contrastive step, executed through existing NUID channels.
 * Caller callbacks own actual proposals, Bash/model execution and composition.
 * No correctness score is inferred and no global improvement library is mutated.
 */
suspend fun contrastiveHarnessStep(
    fanout: NuidFanoutElement,
    worker: Workgroup,
    address: Nuid,
    baseline: borg.trikeshed.agent.modelmux.HarnessRevision,
    execute: suspend (borg.trikeshed.agent.modelmux.HarnessRevision) -> borg.trikeshed.agent.modelmux.HarnessObservation,
    propose: suspend (borg.trikeshed.agent.modelmux.HarnessScope, borg.trikeshed.agent.modelmux.HarnessObservation) -> borg.trikeshed.agent.modelmux.HarnessRevision,
    compose: suspend (borg.trikeshed.agent.modelmux.HarnessObservation, Series<Join<borg.trikeshed.agent.modelmux.HarnessRevision, borg.trikeshed.agent.modelmux.HarnessObservation>>) -> borg.trikeshed.agent.modelmux.HarnessComposition,
): borg.trikeshed.agent.modelmux.HarnessStep {
    require(worker.canHandle(address)) { "Harness workgroup cannot handle the requested scope" }
    fanout.register(worker) { claim -> (claim.payload as borg.trikeshed.agent.modelmux.HarnessOperation).execute() }
    suspend fun <T> stage(operation: suspend () -> T): T {
        val admitted = fanout.dispatch(address,
            borg.trikeshed.agent.modelmux.HarnessOperation { operation() })
        check(admitted.winner == worker.name) { "Harness stage admitted by a different workgroup" }
        @Suppress("UNCHECKED_CAST")
        return checkNotNull(admitted.completion) { "Harness stage has no completion" }.await() as T
    }
    try {
        val reference = stage { execute(baseline) }
        require(reference.harness == baseline.id) { "Reference harness identity mismatch" }
        val probes = mutableListOf<Join<borg.trikeshed.agent.modelmux.HarnessRevision, borg.trikeshed.agent.modelmux.HarnessObservation>>()
        val identities = mutableSetOf(baseline.id)
        for (scope in borg.trikeshed.agent.modelmux.HarnessScope.entries) {
            val candidate = stage { propose(scope, reference) }
            require(candidate.id.isNotBlank() && identities.add(candidate.id)) { "Candidate identity must be distinct" }
            val observation = stage { execute(candidate) }
            require(observation.harness == candidate.id) { "Probe harness identity mismatch" }
            require(observation.model == reference.model && observation.environment == reference.environment) {
                "Probe conditions do not match reference"
            }
            probes.add(candidate j observation)
        }
        val candidates = probes.toSeries()
        val observations = listOf(reference) + probes.map { it.b }
        require(observations.map { it.id }.toSet().size == observations.size) { "Observation identities must be unique" }
        val composition = stage { compose(reference, candidates) }
        val available = observations.map { it.id }.toSet()
        require(composition.evidence.size > 0 && composition.evidence.view.all { it in available }) {
            "Composition requires actual observation references"
        }
        val successor = stage { execute(composition.revision) }
        require(successor.harness == composition.revision.id && successor.id !in available &&
            successor.model == reference.model && successor.environment == reference.environment) {
            "Successor execution identity or conditions mismatch"
        }
        return borg.trikeshed.agent.modelmux.HarnessStep(
            reference,
            candidates,
            composition,
            successor
        )
    } finally { withContext(NonCancellable) { fanout.unregister(worker.name) } }
}
