package borg.trikeshed.lcnc

import borg.trikeshed.platform.HostSystem
import borg.trikeshed.module.ModuleContext

/**
 * JVM wiring for the daemon's LCNC store legos (PromptNodes/PromptStore/
 * WorkspaceSnapshotService/ProjectNodes/PureNodes/CanvasJsPureNodes). The
 * bodies are JVM bootstrap glue: the node runners themselves are already
 * common or jvm-actual — this object only sequences their registration.
 * Called from the daemon with resolved jvm values.
 */
object OroborosLcncWiring {
    suspend fun lcncStores(
        moduleContext: ModuleContext,
        promptStore: borg.trikeshed.lcnc.PromptStore,
        snapshotService: borg.trikeshed.forge.server.WorkspaceSnapshotService,
        projectCorpus: borg.trikeshed.forge.server.JvmProjectCorpus,
    ) {
        moduleContext.lcncRunners.putAll(borg.trikeshed.lcnc.PromptNodes.registry(promptStore))
        promptStore.register(moduleContext)
        snapshotService.register(moduleContext)
        HostSystem.err("[OROBOROS] workspace snapshots: " + snapshotService.restore() + " in the ledger" + (snapshotService.head?.let { ", head " + it.cid.take(19) } ?: ""))
        promptStore.thaw(borg.trikeshed.lcnc.LcncPromptSeeds.all()).let { restored ->
            HostSystem.err("[OROBOROS] prompts: $restored head(s) restored from the ledger; ${'$'}{promptStore.list().size} on the board")
        }
        moduleContext.lcncRunners.putAll(borg.trikeshed.lcnc.ProjectNodes.registry(projectCorpus))
        moduleContext.lcncRunners.putAll(borg.trikeshed.lcnc.PureNodes.registry { borg.trikeshed.platform.HostSystem.currentTimeMillis() })
        moduleContext.lcncRunners.putAll(borg.trikeshed.lcnc.CanvasJsPureNodes.registry())
    }
}
