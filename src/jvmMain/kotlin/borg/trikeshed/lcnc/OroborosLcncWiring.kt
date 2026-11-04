package borg.trikeshed.lcnc

import borg.trikeshed.platform.HostSystem
import borg.trikeshed.module.ModuleContext
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.userspace.nio.file.spi.fileIoContext
import kotlinx.coroutines.withContext
import borg.trikeshed.kernel.*
import borg.trikeshed.lib.*

/**
 * JVM wiring for the daemon's LCNC store legos (PromptNodes/PromptStore/
 * WorkspaceSnapshotService/ProjectNodes/PureNodes). The
 * bodies are JVM bootstrap glue: the node runners themselves are already
 * common or jvm-actual — this object only sequences their registration.
 * Called from the daemon with resolved jvm values.
 */
object OroborosLcncWiring {
    /** Optional trusted scalar backend; caller retains the root until all LCNC consumers drain. */
    internal suspend fun llvm(context: ModuleContext,
                              config: String? = HostSystem.getenv("TRIKESHED_LLVM_SCALAR_PROFILE")): Join<KernelElement, LlvmScalar>? {
        if (config == null) return null
        require(config.isNotBlank() && java.nio.file.Path.of(config).isAbsolute) {
            "TRIKESHED_LLVM_SCALAR_PROFILE requires an absolute operator configuration path"
        }
        val files = checkNotNull(context.scope.coroutineContext[FileOperations])
        val profile = withContext(fileIoContext) {
            LlvmScalarProfile.parse(LlvmScalarProfile.read(files, files.canonicalPath(config), 16 * 1024)
                .decodeToString(throwOnInvalidSequence = true))
        }
        val kernel = KernelElement(context.scope.coroutineContext, KernelId("llvm"))
        try { return kernel j LlvmScalar.open(kernel, KernelId("scalar"), profile, files) }
        catch (failure: Throwable) {
            try { kernel.drain() } catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
            throw failure
        }
    }

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
