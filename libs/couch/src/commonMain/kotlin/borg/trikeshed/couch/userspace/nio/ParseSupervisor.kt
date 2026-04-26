package borg.trikeshed.couch.userspace.nio

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.Twin
import borg.trikeshed.userspace.concurrency.ParseScope
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * SupervisoryJob host for concurrent parse tasks.
 *
 * Connects ParseScope (already fully implemented) to the ReactorSupervisor
 * as a named branch. ParseSupervisor manages the parse tree as a hierarchy
 * of SupervisorJobs — the scope tree IS the parse tree.
 *
 * This is the "API and factory services for domain objects" layer:
 * Kline, WalEntry, NDJSON rows — all produced by parse tasks launched here.
 */
class ParseSupervisor(
    override val key: CoroutineContext.Key<ParseSupervisor> = ParseSupervisorKey,
) : AbstractCoroutineContextElement(key) {

    val supervisor: CompletableJob = SupervisorJob()

    // State transitions are single-threaded (driven from parse supervisor coroutine).
    private var _state: ParseState = ParseState.CREATED
    val state: ParseState get() = _state

    enum class ParseState {
        CREATED,
        OPEN,
        ACTIVE,
        DRAINING,
        CLOSED,
    }

    fun open() {
        check(_state == ParseState.CREATED) { "open() requires CREATED, was $_state" }
        _state = ParseState.OPEN
    }

    fun activate() {
        check(_state == ParseState.OPEN) { "activate() requires OPEN, was $_state" }
        _state = ParseState.ACTIVE
    }

    fun drain() {
        if (_state == ParseState.CLOSED) return
        _state = ParseState.DRAINING
    }

    fun close() {
        if (_state == ParseState.CLOSED) return
        _state = ParseState.CLOSED
        supervisor.complete()
    }

    /**
     * Factory: create a new parse task scope from a source Series.
     * The scope is a child of this ParseSupervisor's SupervisorJob.
     */
    fun parseTask(source: Series<Char>, span: Twin<Int>): ParseScope {
        val scope = ParseScope(source, span, supervisor)
        scope.open()
        scope.activate()
        return scope
    }

    /**
     * Fanout: concurrent parse of identified child spans.
     * Mirrors ParseScope.fanout() but managed under this Supervisor.
     */
    suspend fun fanout(
        scope: ParseScope,
        identify: (Series<Char>, Twin<Int>) -> Series<Twin<Int>>,
        childParser: (Series<Char>, Twin<Int>) -> Any?,
    ): Series<Any?> = scope.fanout(identify, childParser)
}

/** Singleton key for ParseSupervisor in CoroutineContext. */
object ParseSupervisorKey : CoroutineContext.Key<ParseSupervisor>
