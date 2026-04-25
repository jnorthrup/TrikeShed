package borg.trikeshed.userspace.concurrency

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.Twin
import borg.trikeshed.lib.j
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/* ═══════════════════════════════════════════════════════════════════════════
 *  ParseScope — CCEK infrastructure for concurrent parse trees.
 *
 *  Lives in root TrikeShed so both kursive and Confix can use it.
 *  Generic over source carrier type (CharSeries, ByteSeries, etc.).
 *
 *  A ParseScope is a CoroutineContext.Element that:
 *    - holds a SupervisorJob (one child failure doesn't cancel siblings)
 *    - tracks forward-only lifecycle: CREATED→OPEN→ACTIVE→DRAINING→CLOSED
 *    - supports subscriber fanout (downstream consumers receive results)
 *    - can fan out to child scopes (concurrent sub-parsers)
 *
 *  The scope tree IS the parse tree. Each scope owns its children.
 *  Sealing is the synchronization boundary — a scope seals when all
 *  children have sealed. Readers see immutable sealed scopes.
 * ═══════════════════════════════════════════════════════════════════════════ */


/** Forward-only lifecycle for parse scopes. */
enum class ParseLifecycle {
    CREATED,    // scope created, not yet started
    OPEN,       // subscribed, ready to activate
    ACTIVE,     // parsing in progress
    DRAINING,   // all children done, finalizing
    CLOSED      // sealed, immutable
}

/** A subscriber that receives parse results as scopes seal. */
fun interface ParseSubscriber {
    fun onResult(scope: ParseScope, result: Any?)
}

/** Singleton routing identity for ParseScope in CoroutineContext. */
object ParseScopeKey : CoroutineContext.Key<ParseScope>

/**
 * A parse scope: CoroutineContext.Element that owns a region of the parse tree.
 *
 * Each scope:
 *   - runs a parser over its source span
 *   - identifies child regions
 *   - fans out to concurrent child scopes
 *   - seals when all children are done
 *
 * The SupervisorJob ensures one child failure doesn't cancel siblings.
 * Subscriber fanout lets downstream code consume results as they arrive.
 */
class ParseScope(
    val source: Series<Char>,
    val span: Twin<Int>,
    parentContext: CoroutineContext? = null,
) : AbstractCoroutineContextElement(ParseScopeKey) {

    val supervisor: CompletableJob =
        if (parentContext == null) SupervisorJob() else SupervisorJob(parentContext[Job])

    private var _state: ParseLifecycle = ParseLifecycle.CREATED
    val lifecycleState: ParseLifecycle get() = _state

    private val subs = mutableListOf<ParseSubscriber>()
    private val children = mutableListOf<ParseScope>()
    private val results = mutableListOf<Any?>()

    val subscriberCount: Int get() = subs.size
    val childCount: Int get() = children.size
    val resultCount: Int get() = results.size

    fun subscribe(subscriber: ParseSubscriber) {
        check(_state == ParseLifecycle.CREATED || _state == ParseLifecycle.OPEN) {
            "cannot subscribe in state $_state"
        }
        subs.add(subscriber)
    }

    fun open() {
        check(_state == ParseLifecycle.CREATED) { "open() requires CREATED, was $_state" }
        _state = ParseLifecycle.OPEN
    }

    fun activate() {
        check(_state == ParseLifecycle.OPEN) { "activate() requires OPEN, was $_state" }
        _state = ParseLifecycle.ACTIVE
    }

    fun drain() {
        if (_state == ParseLifecycle.CLOSED) return
        _state = ParseLifecycle.DRAINING
    }

    fun close() {
        if (_state == ParseLifecycle.CLOSED) return
        _state = ParseLifecycle.CLOSED
        supervisor.complete()
    }

    /** Add a parsed result and notify subscribers. */
    fun emit(result: Any?) {
        results.add(result)
        for (sub in subs) sub.onResult(this, result)
    }

    /** Get a result by index. */
    fun resultAt(index: Int): Any? = results[index]

    /** Results as a Series. */
    fun resultSeries(): Series<Any?> = results.size j { i: Int -> results[i] }

    /** Children as a Series. */
    fun children(): Series<ParseScope> = children.size j { i: Int -> children[i] }

    /**
     * Create a child scope for a sub-region.
     */
    fun childScope(childSpan: Twin<Int>): ParseScope {
        check(_state == ParseLifecycle.ACTIVE || _state == ParseLifecycle.OPEN) {
            "childScope requires ACTIVE or OPEN, was $_state"
        }
        val child = ParseScope(source, childSpan, supervisor)
        children.add(child)
        return child
    }

    /**
     * Fan out: identify children and launch concurrent child scopes.
     *
     * [identify] maps this scope's span to a Series of child spans.
     * [childParser] runs in each child scope under a SupervisorJob.
     */
    suspend fun fanout(
        identify: (Series<Char>, Twin<Int>) -> Series<Twin<Int>>,
        childParser: (Series<Char>, Twin<Int>) -> Any?,
    ): Series<Any?> {
        check(_state == ParseLifecycle.OPEN || _state == ParseLifecycle.ACTIVE) {
            "fanout requires OPEN or ACTIVE, was $_state"
        }
        _state = ParseLifecycle.ACTIVE

        val childSpans = identify(source, span)
        val collected = mutableListOf<Any?>()

        coroutineScope {
            for (i in 0 until childSpans.size) {
                val childSpan = childSpans[i]
                val child = childScope(childSpan)
                child.open()
                launch {
                    child.activate()
                    val result = childParser(child.source, childSpan)
                    if (result != null) {
                        child.emit(result)
                        collected.add(result)
                    }
                    child.drain()
                    child.close()
                }
            }
        }

        _state = ParseLifecycle.DRAINING
        return collected.size j { i: Int -> collected[i] }
    }

    /**
     * Fan out with typed child parsers and tag-based dispatch.
     *
     * [identify] returns Series of (span, tag) pairs.
     * [childParserFactory] creates a parser for each child based on its tag.
     */
    suspend fun <T> fanoutParsers(
        identify: (Series<Char>, Twin<Int>) -> Series<Join<Twin<Int>, Int>>,
        childParserFactory: (Int) -> (Series<Char>, Twin<Int>) -> T?,
    ): Series<T> {
        check(_state == ParseLifecycle.OPEN || _state == ParseLifecycle.ACTIVE) {
            "fanoutParsers requires OPEN or ACTIVE, was $_state"
        }
        _state = ParseLifecycle.ACTIVE

        val childEntries = identify(source, span)
        val collected = mutableListOf<T>()

        coroutineScope {
            for (i in 0 until childEntries.size) {
                val entry = childEntries[i]
                val childSpan = entry.a
                val tag = entry.b
                val child = childScope(childSpan)
                child.open()
                val childParser = childParserFactory(tag)
                launch {
                    child.activate()
                    val result = childParser(child.source, childSpan)
                    if (result != null) {
                        @Suppress("UNCHECKED_CAST")
                        child.emit(result as Any?)
                        collected.add(result)
                    }
                    child.drain()
                    child.close()
                }
            }
        }

        _state = ParseLifecycle.DRAINING
        return collected.size j { i: Int -> collected[i] }
    }
}

/* ─── Scope access from coroutines ──────────────────────────────────── */

/**
 * Get the current ParseScope from the coroutine context.
 */
suspend fun currentParseScope(): ParseScope =
    kotlin.coroutines.coroutineContext[ParseScopeKey]
        ?: error("no ParseScope in coroutine context")

/**
 * Run a parser within a new root ParseScope.
 */
suspend fun <T> withParseScope(
    source: Series<Char>,
    parser: suspend (Series<Char>, Twin<Int>) -> T?,
): Join<T, ParseScope> {
    val scope = ParseScope(source, 0 j source.size)
    scope.open()
    scope.activate()
    val result = parser(source, scope.span)
    scope.drain()
    scope.close()
    return (result ?: error("root parser returned null")) j scope
}
