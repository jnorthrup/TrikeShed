// Rejected malformed Pijul materialization; intentionally inert pending a complete CCEK implementation.
package borg.trikeshed.util.oroboros.element

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import borg.trikeshed.lib.j
import borg.trikeshed.cursor.currentTimeMillis
import borg.trikeshed.memory.CouchIndexBridge
import borg.trikeshed.util.oroboros.GitCouchGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * CCEK element for Git database reconciliation.
 * Listens to Git object changes and triggers reconciliation against the git couch gateway.
 */
class GitReconcileElement(
    private val forgeHome: String,
    private val gitCouchGateway: GitCouchGateway,
    private val couchIndexBridge: CouchIndexBridge,
    private val headShaProvider: suspend () -> String,
    private val awaitObjectsDirty: suspend () -> Unit,
    parentJob: Job? = null
) : AsyncContextElement(ElementState.CREATED, parentJob) {
    companion object Key : AsyncContextKey<GitReconcileElement>()
    override val key: CoroutineContext.Key<*> = Key

    override suspend fun open() {
        if (state != ElementState.CREATED) return
        super.open()

        kotlinx.coroutines.CoroutineScope(supervisor).launch(Dispatchers.Default) {
            var lastReconcledSha = ""
            while (isActive) {
                // Wait for object-dirty signal, then reconcile
                awaitObjectsDirty()
                val currentSha = headShaProvider()
                if (currentSha != lastReconcledSha) {
                    runCatching {
                        val snap = gitCouchGateway.reconcile(
                            forgeHome = forgeHome,
                            agentId = "oroboros",
                            revision = currentSha,
                            sequence = currentTimeMillis(),
                        )
                        couchIndexBridge.indexReconciliation(
                            GitCouchGateway.GIT_PREFIX,
                            snap.paths.size j { i: Int -> snap.paths[i] },
                        )
                        lastReconcledSha = currentSha
                        println("[OROBOROS] Git→Couch reactive reconcile: ${snap.paths.size} paths @ ${currentSha.take(12)}")
                    }.onFailure {
                        // note stdout
                        println("[OROBOROS] Git→Couch reconcile failed: ${it.message}")
                    }
                }
            }
        }
    }
}
