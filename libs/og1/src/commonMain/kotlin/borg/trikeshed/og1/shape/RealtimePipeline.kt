package borg.trikeshed.og1.shape

import borg.trikeshed.cursor.RowVec
import borg.trikeshed.lib.Series
import borg.trikeshed.og1.state.CrmsPhase
import borg.trikeshed.og1.state.CrmsState
import borg.trikeshed.og1.cron.CrmsCron

/* ── RealtimePipeline — synapse-driven CRMS ─────────────────────────── */

class RealtimePipeline(
    private val onPulse: (CrmsState) -> Unit = {},
) {
    private val ring = RingSeries<RowVec>(capacity = 4096)
    private val cron = CrmsCron()
    private val eig = CrmsEigensolver()
    private var board: Blackboard? = null

    /** Secondary constructor — inject dependencies for Og1Main. */
    constructor(
        cron: CrmsCron,
        eig: CrmsEigensolver,
        board: Blackboard,
        ring: RingSeries<RowVec>,
        onPulse: (CrmsState) -> Unit = {},
    ) : this(onPulse) {
        this.board = board
        this.ring as RingSeries<RowVec>
    }

    /** Ingest P-code rows from PointcutServer. */
    fun ingest(rows: Series<RowVec>) {
        for (i in 0 until rows.a) ring.append(rows.b(i))
    }

    /** Manual pulse — tick the ring, eigensolve, advance FSM. */
    fun tick(): CrmsState {
        ring.pulse()
        val state = cron.tick()
        onPulse(state)
        return state
    }

    fun ingestAndTick(rows: Series<RowVec>): CrmsState {
        ingest(rows)
        return tick()
    }

    fun ringSize(): Int = ring.size
    fun currentPhase(): CrmsPhase = cron.currentPhase()
    fun currentState(): CrmsState = cron.currentState()
    fun shapes(): List<Shape> = cron.currentShapes()

    /** Eigensolve — rank body records by eigenvalue for current phase. */
    fun eigensolve(): Map<Shape, CrmsEigenResult> {
        val b = board ?: Blackboard().also { board = it }
        return eig.eigensolve(b, currentPhase())
    }
}
