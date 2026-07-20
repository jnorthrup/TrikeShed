package borg.trikeshed.k8s.operator

import borg.trikeshed.k8s.crd.TrikeShedResource
import borg.trikeshed.operator.BaseOperatorSdk
import borg.trikeshed.operator.K8sEvent
import borg.trikeshed.operator.Reconciler
import borg.trikeshed.ccek.CCEK
import borg.trikeshed.ccek.ForgeSignal
import borg.trikeshed.forge.ForgeBlockKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class TrikeShedOperator(private val scope: CoroutineScope) : BaseOperatorSdk<TrikeShedResource>(scope) {
    val dummyDocNode = borg.trikeshed.ccek.UserContext("dummy", scope).choreograph(
        borg.trikeshed.forge.ForgeDocument(
            rootPageId = borg.trikeshed.forge.ForgeBlockId("doc"),
            blocks = emptyMap<String, borg.trikeshed.forge.ForgeBlock>(),
            cursor = borg.trikeshed.forge.ForgeCursor(
                pageId = borg.trikeshed.forge.ForgeBlockId("doc"),
                blockId = borg.trikeshed.forge.ForgeBlockId("doc"),
            ),
        )
    )

    init {
        registerReconciler(object : Reconciler<TrikeShedResource> {
            override suspend fun reconcile(event: K8sEvent<TrikeShedResource>) {
                when (event) {
                    is K8sEvent.Added -> handleAdded(event.resource)
                    is K8sEvent.Modified -> handleModified(event.oldResource, event.newResource)
                    is K8sEvent.Deleted -> handleDeleted(event.resource)
                }
            }
        })
    }

    override suspend fun start() {
        super.start()
        dummyDocNode.start()
    }

    override suspend fun stop() {
        dummyDocNode.stop()
        super.stop()
    }

    private suspend fun handleAdded(resource: TrikeShedResource) {
        if (resource.spec.connectToCcek) {
            val signal = ForgeSignal.AppendBlock(
                kind = ForgeBlockKind.TEXT,
                text = "Resource added: ${resource.metadata.name}",
                properties = emptyMap<String, String>()
            )
            dummyDocNode.sendSignal(signal)
        }
    }

    private suspend fun handleModified(oldResource: TrikeShedResource, newResource: TrikeShedResource) {
        if (newResource.spec.connectToCcek) {
             val signal = ForgeSignal.AppendBlock(
                kind = ForgeBlockKind.TEXT,
                text = "Resource modified: ${newResource.metadata.name}",
                properties = emptyMap<String, String>()
            )
            dummyDocNode.sendSignal(signal)
        }
    }

    private suspend fun handleDeleted(resource: TrikeShedResource) {
         if (resource.spec.connectToCcek) {
             val signal = ForgeSignal.AppendBlock(
                kind = ForgeBlockKind.TEXT,
                text = "Resource deleted: ${resource.metadata.name}",
                properties = emptyMap<String, String>()
            )
            dummyDocNode.sendSignal(signal)
        }
    }
}
