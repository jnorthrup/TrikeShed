package borg.trikeshed.context.lcnc

import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.nuid.Nuid
import borg.trikeshed.context.nuid.NuidFanoutElement
import borg.trikeshed.lcnc.reduction.ReducerRegistry
import kotlinx.coroutines.Job
import kotlin.coroutines.CoroutineContext

class LcncFanoutElement(
    parentJob: Job? = null
) : NuidFanoutElement(parentJob) {

    companion object Key : AsyncContextKey<LcncFanoutElement>()
    override val key: CoroutineContext.Key<*> = Key

    suspend fun dispatch(nuid: Nuid, payload: Any?): Any? {
        val winningCapability = claimWinnerCapability(nuid, payload)
            ?: return null
        return ReducerRegistry.runFor(winningCapability, payload)
    }
}
