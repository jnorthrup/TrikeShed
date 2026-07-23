package borg.trikeshed.reduction

import borg.trikeshed.lib.j

/**
 * LCNC binding for patch-cable object metaphors without owning a parallel cable DTO.
 *
 * The object model stays outside LCNC (Forge, UI, or any later source can own the
 * concrete PatchCable class). LCNC accepts extractor lambdas and exposes the
 * resulting cable-shaped objects as ReductionCarrier<C>, so reducers can filter,
 * group, fold, and traverse patch routes without a module dependency cycle.
 */
class LcncPatchCableBinding<C : Any>(
    private val isActive: (C) -> Boolean,
    private val sourceModule: (C) -> String,
    private val destinationModule: (C) -> String,
) {
    fun activeCables(cables: List<C>): ReductionCarrier<C> =
        cables
            .filter(isActive)
            .let { active -> LcncCarrierAlg.seriesCarrier(active.size j { i: Int -> active[i] }) }

    fun cablesBySourceModule(cables: List<C>): Map<String, ReductionCarrier<C>> =
        activeCables(cables).groupBy(sourceModule)

    fun outgoing(cables: List<C>, moduleId: String): ReductionCarrier<C> =
        activeCables(cables).filter { sourceModule(it) == moduleId }

    fun reachableModules(cables: List<C>, sourceModuleId: String): Set<String> {
        val bySource = cablesBySourceModule(cables)
        val seen = linkedSetOf<String>()
        val queue = ArrayDeque<String>()
        queue.add(sourceModuleId)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val carrier = bySource[current] ?: continue
            for (i in 0 until carrier.size) {
                val next = destinationModule(carrier[i])
                if (seen.add(next)) queue.add(next)
            }
        }
        return seen
    }
}

fun <C : Any> patchCableBinding(
    isActive: (C) -> Boolean,
    sourceModule: (C) -> String,
    destinationModule: (C) -> String,
): LcncPatchCableBinding<C> = LcncPatchCableBinding(isActive, sourceModule, destinationModule)
