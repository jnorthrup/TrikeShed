/*
 * Copyright (c) 2017 TrikeShed Contributors
 * AGPLv3 — see LICENSE
 */
package borg.trikeshed.flywheel

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import kotlinx.coroutines.Job
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * FlywheelElement — CCEK element holding defaults for the most recent
 * reactor coroutines (Codex, OpenCode, Jules, Claude, etc.).
 *
 * Other reactor elements can pull the same defaults via the CCEK key,
 * so the flywheel and the dispatchers share one source of truth for
 * poll interval, tend timeout, session simulation budget, and dispatch
 * policies.
 *
 * Lookups happen via the suspend extensions below ([currentPollIntervalMs],
 * [currentMaxLive], etc.). Any reactor whose coroutine is in scope can
 * call them and pick up the same numbers the flywheel is using.
 */
class FlywheelElement(
    val defaults: ReactorDefaults = ReactorDefaults(),
    parentJob: Job? = null
) : AsyncContextElement(ElementState.CREATED, parentJob) {

    companion object Key : AsyncContextKey<FlywheelElement>()
    override val key: AsyncContextKey<FlywheelElement> = Key

    /** Per-cycle timing and dispatch budgets. Mutable so the loop can clamp down on backpressure. */
    @Volatile var currentCycle: CycleBudget = CycleBudget()

    /** Most-recently dispatched session timing, surfaced for sibling elements. */
    @Volatile var lastDispatchAtMs: Long = 0L
    @Volatile var lastTendAtMs: Long = 0L
    @Volatile var lastHarvestAtMs: Long = 0L

    fun recordDispatch() { lastDispatchAtMs = System.currentTimeMillis() }
    fun recordTend() { lastTendAtMs = System.currentTimeMillis() }
    fun recordHarvest() { lastHarvestAtMs = System.currentTimeMillis() }

    /** Default per-reactor coroutine budgets. Single source of truth shared across the CCEK scope. */
    data class ReactorDefaults(
        val pollIntervalMs: Long = 20_000L,
        val tendTimeoutMs: Long = 300_000L,
        val sessionSimulationMs: Long = 10_000L,
        val maxLive: Int = 15,
        val defaultAgent: String = "codex",
        val fallbackAgent: String = "opencode",
        val trajectoryReducer: String = "trajectory",
        val maxAttemptsBeforeFreeze: Int = 3,
        val workPoolPath: String = "doc/todo.md"
    )

    data class CycleBudget(
        var dispatchesThisCycle: Int = 0,
        var harvestsThisCycle: Int = 0,
        var cycleStartedAtMs: Long = 0L
    )
}

/* ── CCEK lookup helpers (suspend, so they read the active coroutine context) ── */

suspend fun currentFlywheel(): FlywheelElement? =
    coroutineContext[FlywheelElement.Key]

suspend fun currentDefaults(): FlywheelElement.ReactorDefaults =
    currentFlywheel()?.defaults ?: FlywheelElement.ReactorDefaults()

suspend fun currentBudget(): FlywheelElement.CycleBudget =
    currentFlywheel()?.currentCycle ?: FlywheelElement.CycleBudget()

/* ── Concrete default readers used by sibling reactor elements ── */

suspend fun currentPollIntervalMs(): Long = currentDefaults().pollIntervalMs
suspend fun currentTendTimeoutMs(): Long = currentDefaults().tendTimeoutMs
suspend fun currentSessionSimulationMs(): Long = currentDefaults().sessionSimulationMs
suspend fun currentMaxLive(): Int = currentDefaults().maxLive
suspend fun currentDefaultAgent(): String = currentDefaults().defaultAgent
suspend fun currentFallbackAgent(): String = currentDefaults().fallbackAgent

/** Synchronous readers (no CCEK scope → defaults). Used at startup before the loop launches. */
fun ReactorDefaults(): FlywheelElement.ReactorDefaults = FlywheelElement.ReactorDefaults()