package borg.trikeshed.vm

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Source-level resume operations; snapshots contain copied data, never guest values or frames. */
sealed interface VmDebugAction {
    data object INTO : VmDebugAction
    data object OVER : VmDebugAction
    data object OUT : VmDebugAction
    data object CONTINUE : VmDebugAction
    data class Line(val number: Int) : VmDebugAction
}

/** The caller owns the suspension and its cancellation. Hosting tiers supply only the source adapter. */
class VmDebugRequest(
    val context: CoroutineContext,
    val suspendAt: suspend (Map<String, Any?>) -> VmDebugAction,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<VmDebugRequest>
}
