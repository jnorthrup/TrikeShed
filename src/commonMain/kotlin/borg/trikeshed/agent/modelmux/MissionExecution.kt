package borg.trikeshed.agent.modelmux

import borg.trikeshed.context.nuid.*
import borg.trikeshed.lib.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A mission task addresses capability × subnet; handlers own actual execution. */
data class MissionTask(
    val id: String,
    val address: Nuid,
    val dependencies: Series<String> = emptySeriesOf(),
    val payload: Any? = null,
    val accept: (Any?) -> Boolean = { true },
    val role: borg.trikeshed.agent.modelmux.MissionRole = borg.trikeshed.agent.modelmux.MissionRole.WORK,
    val targets: Series<String> = emptySeriesOf(),
)

typealias MissionResults = Series<Join<String, Any?>>

enum class MissionPhase { ADMITTED, COMPLETED, FAILED, REJECTED }

/** Payload-free control receipt; the caller supplies its durable observation sink. */
data class MissionEvent(
    val task: String, val phase: borg.trikeshed.agent.modelmux.MissionPhase, val claimId: Long, val worker: String,
    val role: borg.trikeshed.agent.modelmux.MissionRole = borg.trikeshed.agent.modelmux.MissionRole.WORK, val targets: Series<String> = emptySeriesOf(),
)

/**
 * Validates the whole mission graph and returns the order tasks will be dispatched in. No effects: nothing is
 * admitted, nothing runs. [executeMission] calls this first, so a plan this accepts is exactly a plan execution accepts.
 * Throws IllegalArgumentException with a fixed message for a duplicate or empty id, a missing dependency, a cycle,
 * or a contract violation.
 */
fun planMission(
    tasks: Series<borg.trikeshed.agent.modelmux.MissionTask>,
    assertions: Series<String> = emptySeriesOf(),
): List<borg.trikeshed.agent.modelmux.MissionTask> {
    // Snapshot the caller's lazy oracle once so graph validation and effects see identical tasks.
    val indexed = tasks.view.associateBy { it.id }
    require(indexed.size == tasks.size && indexed.keys.all { it.isNotBlank() }) { "Duplicate or empty mission task id" }
    indexed.values.forEach { task ->
        require(task.dependencies.view.all { it in indexed }) { "Missing mission dependency" }
    }
    val visiting = mutableSetOf<String>()
    val visited = mutableSetOf<String>()
    val order = mutableListOf<borg.trikeshed.agent.modelmux.MissionTask>()
    fun visit(id: String) {
        if (id in visited) return
        require(visiting.add(id)) { "Mission dependency cycle" }
        val task = indexed.getValue(id)
        task.dependencies.view.forEach { visit(it) }
        visiting.remove(id)
        visited.add(id)
        order.add(task)
    }
    indexed.keys.forEach { visit(it) }
    borg.trikeshed.agent.modelmux.validateMissionContract(indexed.values.toSeries(), assertions)
    return order
}

/**
 * Zenith's dependency gating at the commonMain dispatch boundary.
 * Validate the entire graph before effects. A claim is not a result: await the
 * registered handler's completion before releasing dependent tasks. Failure
 * propagates and prevents dependent admission. The caller owns fanout drain.
 */
suspend fun executeMission(
    fanout: NuidFanoutElement,
    tasks: Series<borg.trikeshed.agent.modelmux.MissionTask>,
    observe: (borg.trikeshed.agent.modelmux.MissionEvent) -> Unit = {},
    assertions: Series<String> = emptySeriesOf(),
): borg.trikeshed.agent.modelmux.MissionResults {
    val order = planMission(tasks, assertions)
    val results = mutableListOf<Join<String, Any?>>()
    for (task in order) {
        currentCoroutineContext().ensureActive()
        val admission = fanout.dispatch(task.address, task.payload)
        check(admission.winner != null) { "No workgroup admitted mission task ${task.id}" }
        val completion = checkNotNull(admission.completion) { "Mission task ${task.id} has no execution handler" }
        observe(
            borg.trikeshed.agent.modelmux.MissionEvent(
                task.id,
                borg.trikeshed.agent.modelmux.MissionPhase.ADMITTED,
                admission.claimId,
                admission.winner,
                task.role,
                task.targets
            )
        )
        val value = try { completion.await() } catch (failure: Throwable) {
            try { observe(
                borg.trikeshed.agent.modelmux.MissionEvent(
                    task.id,
                    borg.trikeshed.agent.modelmux.MissionPhase.FAILED,
                    admission.claimId,
                    admission.winner,
                    task.role,
                    task.targets
                )
            ) }
            catch (observerFailure: Throwable) { failure.addSuppressed(observerFailure) }
            throw failure
        }
        val accepted = try { task.accept(value) } catch (failure: Throwable) {
            try { observe(
                borg.trikeshed.agent.modelmux.MissionEvent(
                    task.id,
                    borg.trikeshed.agent.modelmux.MissionPhase.REJECTED,
                    admission.claimId,
                    admission.winner,
                    task.role,
                    task.targets
                )
            ) }
            catch (observerFailure: Throwable) { failure.addSuppressed(observerFailure) }
            throw failure
        }
        if (!accepted) {
            observe(
                borg.trikeshed.agent.modelmux.MissionEvent(
                    task.id,
                    borg.trikeshed.agent.modelmux.MissionPhase.REJECTED,
                    admission.claimId,
                    admission.winner,
                    task.role,
                    task.targets
                )
            )
            error("Mission task ${task.id} failed acceptance")
        }
        observe(
            borg.trikeshed.agent.modelmux.MissionEvent(
                task.id,
                borg.trikeshed.agent.modelmux.MissionPhase.COMPLETED,
                admission.claimId,
                admission.winner,
                task.role,
                task.targets
            )
        )
        results.add(task.id j value)
    }
    return results.toSeries()
}
