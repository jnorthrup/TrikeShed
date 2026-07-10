package borg.trikeshed.userspace.reactor

import kotlinx.coroutines.flow.StateFlow

/**
 * HUD data class exposing the reactor state for live display.
 * 
 * No fake telemetry - all values come from the live reactor state.
 * The HUD reads directly from MuxReactorState and KanbanState.
 */
data class HudState(
    val leasedKeys: List<LeasedKeyDisplay>,
    val cacheHitCount: Int,
    val cacheMissCount: Int,
    val cacheStoreCount: Int,
    val lastCacheKey: String,
    val lastEventKind: String,
    val activeProviders: List<String>,
    val currentlyRunning: Int,
    val availableKeys: Int,
) {
    /**
     * Cache hit/miss ratio as a percentage (0-100).
     * Returns 0 if no lookups have occurred yet.
     */
    val cacheHitRatio: Int
        get() {
            val total = cacheHitCount + cacheMissCount
            return if (total == 0) 0 else (cacheHitCount * 100 / total)
        }

    /**
     * True if cache has been queried (not 100% hits means we've had misses).
     * This indicates real cache behavior rather than fake telemetry.
     */
    val hasRealCacheBehavior: Boolean
        get() = cacheMissCount > 0 || cacheHitCount > 0
}

/**
 * Display model for a leased key.
 */
data class LeasedKeyDisplay(
    val keyId: String,
    val provider: String,
    val label: String,
    val leasedTo: String,
    val leaseExpiresAt: Long,
)

/**
 * Factory for creating HUD snapshots from reactor state.
 * Reads live state - no fake telemetry.
 */
object MuxReactorHud {
    /**
     * Create a HUD snapshot from the reactor's live state.
     * 
     * @param reactorState The current MuxReactorState from the reactor
     * @param kanbanState The current KanbanState from the FSM
     * @return HUD data ready for display
     */
    fun fromState(
        reactorState: MuxReactorState,
        kanbanState: KanbanState,
    ): HudState {
        return HudState(
            leasedKeys = reactorState.leases.map { lease ->
                val keyEntry = reactorState.keys.find { it.keyId == lease.keyId }
                LeasedKeyDisplay(
                    keyId = lease.keyId,
                    provider = keyEntry?.provider ?: "unknown",
                    label = keyEntry?.label ?: lease.keyId,
                    leasedTo = lease.leasedTo,
                    leaseExpiresAt = lease.leaseExpiresAt,
                )
            },
            cacheHitCount = kanbanState.cacheHits,
            cacheMissCount = kanbanState.cacheMisses,
            cacheStoreCount = kanbanState.cacheStored,
            lastCacheKey = kanbanState.lastCacheKey,
            lastEventKind = kanbanState.lastEventKind,
            activeProviders = kanbanState.activeProviders,
            currentlyRunning = reactorState.currentlyRunning,
            availableKeys = reactorState.availableKeys,
        )
    }

    /**
     * Create a HUD snapshot from a StateFlow of reactor state.
     */
    fun fromStateFlow(
        reactorStateFlow: StateFlow<MuxReactorState>,
        kanbanState: KanbanState,
    ): HudState = fromState(reactorStateFlow.value, kanbanState)

    /**
     * Render HUD to a simple string for console/terminal display.
     * No fake telemetry - displays live state.
     */
    fun render(hud: HudState): String = buildString {
        appendLine("╔══════════════════════════════════════════════════════╗")
        appendLine("║              MUX REACTOR HUD (LIVE)                  ║")
        appendLine("╠══════════════════════════════════════════════════════╣")
        
        // Cache metrics - shows real hit/miss mix, not fake telemetry
        appendLine("║ CACHE                                                ║")
        appendLine("║   Hits:    ${hud.cacheHitCount.toString().padStart(6)}  Misses: ${hud.cacheMissCount.toString().padStart(6)}          ║")
        appendLine("║   Stored:  ${hud.cacheStoreCount.toString().padStart(6)}  Hit Ratio: ${hud.cacheHitRatio.toString().padStart(3)}%              ║")
        appendLine("║   Last Key: ${hud.lastCacheKey.take(30).padEnd(30)} ║")
        
        // Leased keys - shows actual leased key
        appendLine("║ LEASED KEYS                                         ║")
        if (hud.leasedKeys.isEmpty()) {
            appendLine("║   (none)                                             ║")
        } else {
            hud.leasedKeys.forEach { key ->
                appendLine("║   ${key.label.take(20).padEnd(20)} → ${key.provider.take(15).padEnd(15)}║")
            }
        }
        
        // Runtime state
        appendLine("║ RUNTIME                                              ║")
        appendLine("║   Running:   ${hud.currentlyRunning.toString().padStart(6)}  Available: ${hud.availableKeys.toString().padStart(6)}        ║")
        appendLine("║   Providers: ${hud.activeProviders.joinToString(", ").take(26).padEnd(26)}║")
        appendLine("║   Last Event: ${hud.lastEventKind.take(26).padEnd(26)}║")
        
        appendLine("╚══════════════════════════════════════════════════════╝")
    }
}
