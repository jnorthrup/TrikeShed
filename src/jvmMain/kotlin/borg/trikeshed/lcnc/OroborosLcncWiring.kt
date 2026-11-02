package borg.trikeshed.lcnc

import borg.trikeshed.platform.HostSystem
import borg.trikeshed.module.ModuleContext
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.job.fileIoContext
import kotlinx.coroutines.withContext

/**
 * JVM wiring for the daemon's LCNC store legos (PromptNodes/PromptStore/
 * WorkspaceSnapshotService/ProjectNodes/PureNodes). The
 * bodies are JVM bootstrap glue: the node runners themselves are already
 * common or jvm-actual — this object only sequences their registration.
 * Called from the daemon with resolved jvm values.
 */
object OroborosLcncWiring {
    /** Only the operator's startup environment selects native code; request bodies cannot. */
    suspend fun documentReview(
        context: ModuleContext,
        corpus: ProjectCorpus,
        books: borg.trikeshed.narsese.ConstellationNodes,
    ): borg.trikeshed.forge.server.DocumentReviewWire {
        val files = checkNotNull(context.scope.coroutineContext[FileOperations])
        val native = nativeHorn(files, HostSystem.getenv("TRIKESHED_NATIVE_HORN_PROFILE"))
        return borg.trikeshed.forge.server.DocumentReviewWire.open(context, corpus, books, native)
    }

    internal suspend fun nativeHorn(files: FileOperations, config: String?): NativeHornQuery? {
        if (config == null) return null
        require(config.isNotBlank() && java.nio.file.Path.of(config).isAbsolute) {
            "TRIKESHED_NATIVE_HORN_PROFILE requires an absolute operator configuration path"
        }
        return withContext(fileIoContext) {
            val path = files.canonicalPath(config)
            val bytes = NativeHornProfile.boundedRead(files, path, 16 * 1024)
            val profile = NativeHornProfile.parse(bytes.decodeToString(throwOnInvalidSequence = true))
            profile.check(files)
            NativeHornQuery(profile)
        }
    }

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
        moduleContext.lcncRunners.putAll(borg.trikeshed.lcnc.FinanceNodes.registry(projectCorpus))
        moduleContext.lcncRunners.putAll(borg.trikeshed.lcnc.PureNodes.registry { borg.trikeshed.platform.HostSystem.currentTimeMillis() })
    }
}
