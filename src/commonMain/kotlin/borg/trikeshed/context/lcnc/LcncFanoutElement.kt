package borg.trikeshed.context.lcnc

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import borg.trikeshed.context.nuid.Capability
import borg.trikeshed.context.nuid.Nuid
import borg.trikeshed.context.nuid.NuidFanoutElement
import borg.trikeshed.reduction.LcncCarrierAlg
import borg.trikeshed.reduction.LcncReduction
import borg.trikeshed.reduction.LcncReductions
import borg.trikeshed.reduction.category
import kotlinx.coroutines.Job

class LcncFanoutElement(
    private val nuidFanout: NuidFanoutElement,
    private val reducerRegistry: Map<String, LcncReduction<*, *, *, *>> = mapOf(
        "process" to LcncReductions.forgeCascade(emptyList(), emptyList()),
        "cas" to LcncReductions.confixParse(),
        "wireproto" to LcncReductions.crmsFold()
    ),
    parentJob: Job? = null
) : AsyncContextElement(ElementState.CREATED, parentJob) {

    companion object Key : AsyncContextKey<LcncFanoutElement>()
    override val key: AsyncContextKey<LcncFanoutElement> = Key

    suspend fun dispatch(nuid: Nuid, payload: Any?): Any? {
        val winningCapability = nuidFanout.claimWinnerCapability(nuid, payload)
            ?: return null
        val reduction = reducerRegistry[winningCapability.category] ?: return null

        @Suppress("UNCHECKED_CAST")
        val typedReduction = reduction as LcncReduction<Any, Any, Any, Any>
        val typedCarrierAlg = typedReduction.carrierAlg

        val carrier = if (payload != null) {
            typedCarrierAlg.carrier(payload)
        } else {
            borg.trikeshed.reduction.emptySeriesCarrier()
        }

        // Execute the reduction, then land it on the forge semantics surface:
        // a completed reduction is a SignalFacetReduced kanban event carrying
        // the zero-cost spine mark (facet j causal j pointcut).
        val result = typedReduction.execute(carrier)
        val facetMark = FacetMark.fromCategory(winningCapability.category)
        val marked: MarkedResult<Any?> = marked(
            result,
            facet = facetMark,
            causal = CausalMark.Dispatched,
            pointcut = PointcutMark.AfterGet,
        )
        borg.trikeshed.userspace.reactor.KanbanFSM.kanbanEvents.tryEmit(
            borg.trikeshed.userspace.reactor.KanbanEvent.SignalFacetReduced(
                facetKey = winningCapability.category,
                reducedValue = result?.toString() ?: "null",
                sourceSignalId = "lcnc:" + facetMark.raw + ":" + CausalMark.Dispatched.raw + ":" + PointcutMark.AfterGet.raw,
                timestampMs = kotlinx.datetime.Clock.System.now().toEpochMilliseconds(),
            )
        )
        return marked
    }
}
