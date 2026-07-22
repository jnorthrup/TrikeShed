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
import borg.trikeshed.reduction.ReducerRegistry
import borg.trikeshed.reduction.category
import kotlinx.coroutines.Job

class LcncFanoutElement(
    private val nuidFanout: NuidFanoutElement,
    parentJob: Job? = null
) : AsyncContextElement(ElementState.CREATED, parentJob) {

    companion object Key : AsyncContextKey<LcncFanoutElement>()
    override val key: AsyncContextKey<LcncFanoutElement> = Key

    suspend fun dispatch(nuid: Nuid, payload: Any?): Any? {
        val winningCapability = nuidFanout.claimWinnerCapability(nuid, payload)
            ?: return null
        return ReducerRegistry.runFor(winningCapability, payload)
    }
}
