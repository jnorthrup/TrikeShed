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
    suspend fun vmLegos(context: ModuleContext, ownCleanup: (suspend () -> Unit) -> Unit): KernelElement? {
        val owned = llvm(context)
        owned?.let { ownCleanup { it.a.drain() } }
        SubVmLegos.register(context, owned?.b?.runner())
        UringLegos.register(context)
        CamelRouteLegos.register(context)
        return owned?.a
    }

    /** Bootstrap extraction keeps the daemon's coroutine below the JVM method-size limit. */
    suspend fun codingAgents(context: ModuleContext, enabled: Set<String>, repo: java.io.File,
                             home: java.io.File, args: Array<String>): borg.trikeshed.forge.server.AgentWire {
        val roster = borg.trikeshed.agent.AgentCliProbe.probe(enabled, borg.trikeshed.agent.AgentRegistry.KNOWN)
        val runner = borg.trikeshed.agent.JvmAgentRunner(roster, repoDir = repo, forgeHome = home,
            cas = context.casStore, attachments = context.attachments, blackboard = context.blackboard)
        context.agentRuns = runner
        context.lcncRunners.putAll(AgentNodes.registry(runner) { borg.trikeshed.platform.randomUuid().toString() })
        val wire = borg.trikeshed.forge.server.AgentWire(runner, context.blackboard,
            probedAtMs = HostSystem.currentTimeMillis(),
            enabledBy = if ("--agents" in args) "--agents" else if (HostSystem.getenv("TRIKESHED_AGENTS") != null) "TRIKESHED_AGENTS" else "default")
        HostSystem.err("[OROBOROS] coding agents: " + roster.joinToString { it.id + if (it.enabled) " " + it.version else " (" + it.why + ")" })
        return wire
    }

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
