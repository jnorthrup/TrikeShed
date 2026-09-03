package borg.trikeshed.module

import borg.trikeshed.couch.CouchDatabase
import borg.trikeshed.dag.ReteNetwork
import borg.trikeshed.dag.ReteProductionRegistry
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.job.CasStore
import borg.trikeshed.narsese.BeliefBagElement
import borg.trikeshed.narsese.TurnReviewElement
import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import kotlinx.coroutines.CoroutineScope
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * ForgeModule — oroboros as a HOST for dynamic modules. A module is a unit of
 * daemon capability with a no-arg PROXY CONSTRUCTOR (so it can be instantiated
 * reflectively by FQCN — including classes that arrived via hotswapFeed after
 * boot) that assembles its own CCEK elements against a [ModuleContext] and
 * hands back an opaque [ModuleHandle].
 *
 * The god-object prohibition (PRELOAD "Reactor decomposition rules") is the
 * point: the server grows a route REGISTRY consultation, never module logic.
 * Module upgrades are detach → attach with a fresh instance — safe exactly when
 * the module's state is WAL-rebuildable, which is the module author's contract.
 */
interface ForgeModule {
    val id: String

    /** Suspend: modules replay WALs / open CCEK elements here. Claims made before a throw are rolled back by the supervisor. */
    suspend fun open(ctx: ModuleContext): ModuleHandle
}

/**
 * Everything a module may compose against. Nullable planes degrade gracefully:
 * a module MUST function (possibly reduced) when [beliefBag]/[turnReview] are
 * absent — coherence is garnish, never load-bearing.
 */
class ModuleContext(
    val couchDb: CouchDatabase,
    val rete: ReteNetwork,
    val productions: ReteProductionRegistry,
    val beliefBag: BeliefBagElement?,
    val turnReview: TurnReviewElement?,
    val blackboard: ConfixBlackboard,
    val casStore: CasStore,
    val attachments: CouchAttachmentGateway,
    val routes: ModuleRouteRegistry,
    /** SupervisorJob child of the daemon root — module coroutines die with the daemon, not vice versa. */
    val scope: CoroutineScope,
    /** Injected clock seam — modules never call wall-clock APIs directly (commonMain rule). */
    val clock: () -> Long,
    /** Module-owned state root (forge home). NEVER a worktree, NEVER /tmp. */
    val stateDir: File,
    /** HtxKey + mux reactor for provider calls (ModelMuxKanbanAgent and kin). */
    val muxContext: CoroutineContext = EmptyCoroutineContext,
    /**
     * P1: live CCEK control-plane binding. When present, whole LCNC program runs
     * launch as structured CCEK assemblies; null preserves reduced/test contexts.
     */
    val ccekBinding: borg.trikeshed.ccek.CCEK.CcekReactorBinding? = null,
    /**
     * Modules publish their LCNC runner registries here (additive); the host composes
     * them — webhook node dispatch resolves `program/node/port` against this map.
     */
    val lcncRunners: MutableMap<String, borg.trikeshed.lcnc.LcncNodeRunner> = linkedMapOf(),
    /**
     * Stored-program resolver for whole-program runs and scope recursion (spec
     * §3.1): production wiring is `panels/<name>` attachments ∪ the offered
     * [borg.trikeshed.lcnc.LcncPresets]; the default resolves presets only, so
     * a bare context still runs them.
     */
    val programLoader: suspend (String) -> borg.trikeshed.lcnc.LcncProgram? = { name ->
        borg.trikeshed.lcnc.LcncPresets.all()[name]
            ?.let { borg.trikeshed.lcnc.LcncProgramConfix.fromJson(name, it) }
    },
    /**
     * The daemon's ONE KIF bank (the tuple plane every Rete fact projects into
     * and the LCNC vocabulary is told to). A module's [borg.trikeshed.lcnc.LcncPublisher]
     * must tell the same bank the daemon's publisher tells; null (tests, reduced
     * contexts) means each publisher keeps a private bank, as before.
     */
    val kifBank: borg.trikeshed.kif.KifKnowledgeBase? = null,
)

/** The grip the supervisor holds: describe for /api/modules, drain-then-close on detach. */
interface ModuleHandle {
    val id: String
    fun describe(): Map<String, Any?>
    suspend fun drain()
    suspend fun close()
}
