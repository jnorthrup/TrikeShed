package borg.trikeshed.lcnc.ccek

import borg.trikeshed.ccek.ArticulatedNode
import borg.trikeshed.ccek.ProjectionKind
import borg.trikeshed.ccek.UserContext
import borg.trikeshed.forge.ForgeDocument
import borg.trikeshed.userspace.reactor.MuxReactorElement
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

class CcekReactorBinding(
    context: CoroutineContext,
    val reactor: MuxReactorElement = context[MuxReactorElement] ?: MuxReactorElement(parentJob = context[Job]),
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<CcekReactorBinding>

    override val key: CoroutineContext.Key<*> get() = Key

    private val owner = SupervisorJob(context[Job] ?: reactor.supervisor)
    val reactorScope: CoroutineScope = CoroutineScope(context + reactor + owner + this + CoroutineName("lcnc-ccek-reactor"))

    fun choreograph(
        doc: ForgeDocument,
        record: Boolean = false,
        projections: Set<ProjectionKind> = ProjectionKind.ALL,
        maxConcurrency: Int = 8,
    ): ArticulatedNode =
        ArticulatedNode(
            initialDoc = doc,
            scope = reactorScope,
            record = record,
            enabledProjections = projections,
            maxConcurrency = maxConcurrency,
        )

    fun createUserContext(role: String): UserContext = UserContext(role, reactorScope)
}

fun ccekReactorBinding(context: CoroutineContext): CcekReactorBinding = CcekReactorBinding(context)
