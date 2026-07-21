package borg.trikeshed.context.lcnc

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import borg.trikeshed.context.nuid.Capability
import borg.trikeshed.context.nuid.Nuid
import borg.trikeshed.context.nuid.NuidFanoutElement
import borg.trikeshed.lcnc.reduction.LcncCarrierAlg
import borg.trikeshed.lcnc.reduction.LcncReduction
import borg.trikeshed.lcnc.reduction.LcncReductions
import borg.trikeshed.lcnc.reduction.category
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
            borg.trikeshed.lcnc.reduction.emptySeriesCarrier()
        }

        return typedReduction.execute(carrier)
    }
}
