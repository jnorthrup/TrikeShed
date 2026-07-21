package borg.trikeshed.pointcut.polyglot

import borg.trikeshed.classfile.model.PointcutCoordinateSeries
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.lib.Series
import borg.trikeshed.userspace.nio.process.ProcessWorker

/**
 * Defines the taxonomy for a polyglot classfile blackboard where
 * child GraalCE VMs can contribute pointcut coordinates.
 */
interface PolyglotBlackboardTaxonomy {
    val blackboard: ConfixBlackboard
    
    /**
     * Spawns a child GraalCE VM process to resolve or intercept pointcuts,
     * merging its contributions back into the central blackboard.
     */
    suspend fun pointcutChildVm(worker: ProcessWorker, commandArgs: List<String>): PointcutCoordinateSeries
}

/**
 * Implementation of the polyglot blackboard taxonomy.
 */
class GraalPolyglotBlackboardTaxonomy(
    override val blackboard: ConfixBlackboard = ConfixBlackboard.empty()
) : PolyglotBlackboardTaxonomy {

    override suspend fun pointcutChildVm(worker: ProcessWorker, commandArgs: List<String>): PointcutCoordinateSeries {
        // Here we would use the ProcessWorker to run a GraalCE child process,
        // intercept polyglot execution, and map the output back to PointcutCoordinateSeries.
        // For now, we return empty due to missing runtime ProcessSpec dependencies.
        return borg.trikeshed.classfile.model.emptyPointcutCoordinates()
    }
}
