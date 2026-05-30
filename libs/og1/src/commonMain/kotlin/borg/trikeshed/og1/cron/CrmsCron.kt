package borg.trikeshed.og1.cron

import borg.trikeshed.og1.shape.*
import borg.trikeshed.og1.state.*
import borg.trikeshed.og1.types.FanoutPlan
import borg.trikeshed.og1.fanout.poolInit
import borg.trikeshed.lib.Series
import borg.trikeshed.cursor.RowVec

/* ── CrmsCron — FSM-driven CRMS cron job runner ─────────────────────
 *
 *  The board IS the state machine.
 *  Each tick advances the CrmsState one phase.
 *  Blackboard holds the ShapeCursor projections (5 eigenvector bases).
 *  Facet-k-means-seated voter panel drives quorum transitions.
 *
 *  Phase transitions (FSM adjacency):
 *    BRAINSTORM → GAP → KMEANS → QUORUM → DELIVER → MONITOR → BRAINSTORM
 */

class CrmsCron {
    private var state: CrmsState = CrmsState()
    private val _blackboard = Blackboard()
    /** Accessible to RealtimePipeline for direct eigensolve calls. */
    val blackboard: Blackboard get() = _blackboard

    init {
        // Register all 5 cascade eigenvector bases on startup
        for (shape in ShapeSchema.Cascade.all) {
            blackboard.register(shape)
        }
    }

    /** Advance the FSM one tick. Returns the new state snapshot. */
    fun tick(): CrmsState {
        poolInit
        val results = blackboard.eigensolve(state.phase)
        return when (state.phase) {
            CrmsPhase.BRAINSTORM -> state.toGap(gapsFromResults(results))
            CrmsPhase.GAP         -> state.toKmeans(facetsFromResults(results))
            CrmsPhase.KMEANS      -> state.toQuorum(
                winnerFromResults(results),
                confidenceFromResults(results),
            )
            CrmsPhase.QUORUM      ->
                if (state.quorum.isQuorate) state.toDeliver(buildPlanFromResults(results))
                else state.toBrainstorm()
            CrmsPhase.DELIVER     -> state.toMonitor()
            CrmsPhase.MONITOR     -> state.toBrainstorm()
        }
    }

    /** Ingest rows into a shape projection. Call this from the cron job. */
    fun ingest(shape: Shape, rows: Series<RowVec>) {
        blackboard.ingest(shape, rows)
    }

    /** Ingest into all registered cascade shapes. */
    fun ingestAll(rows: Series<RowVec>) {
        for (shape in ShapeSchema.Cascade.all) {
            blackboard.ingest(shape, rows)
        }
    }

    private fun gapsFromResults(r: Map<String, CrmsEigenResult>) =
        r.entries.flatMap { (k, v) ->
            v.components.mapIndexed { i, ev -> mapOf("shape" to k, "idx" to i, "eigenvalue" to ev) }
        }

    private fun facetsFromResults(r: Map<String, CrmsEigenResult>) =
        r.entries.map { (k, v) ->
            VoterFacet(id = k, cluster = v.rank, weight = v.gap.toDouble(), vote = mapOf(k to v.gap.toDouble()))
        }

    private fun winnerFromResults(r: Map<String, CrmsEigenResult>) =
        r.entries.maxByOrNull { it.value.gap }?.key ?: ""

    private fun confidenceFromResults(r: Map<String, CrmsEigenResult>): Double {
        val max = r.values.maxOfOrNull { it.gap } ?: 0f
        return if (max > 0) 1.0 else 0.0
    }

    private fun buildPlanFromResults(r: Map<String, CrmsEigenResult>): FanoutPlan =
        state.plan  // real impl: build FanoutPlan from cluster assignments

    fun currentState(): CrmsState = state
    fun currentPhase(): CrmsPhase = state.phase
    fun currentShapes(): List<Shape> = blackboard.shapes
}
