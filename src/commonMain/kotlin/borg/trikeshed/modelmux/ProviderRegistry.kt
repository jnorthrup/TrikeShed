package borg.trikeshed.modelmux

class ProviderRegistry {
    private val providers = mutableMapOf<String, ModelProvider>()
    private val descriptors = mutableMapOf<String, ProviderDescriptor>()

    fun register(provider: ModelProvider, descriptor: ProviderDescriptor) {
        providers[descriptor.id] = provider
        descriptors[descriptor.id] = descriptor
    }

    fun select(rule: SelectionRule): ModelProvider = when (rule) {
        is SelectionRule.SpecificProvider -> {
            if (rule.id in providers && descriptors[rule.id]?.id == rule.id) providers[rule.id]!!
            else throw IllegalArgumentException("no provider with id ${rule.id}")
        }
        is SelectionRule.MinCost -> descriptors.values.minByOrNull { it.costPer1kTokens }
            ?.let { providers[it.id]!! } ?: throw IllegalArgumentException("no providers registered")
        is SelectionRule.MinLatency -> descriptors.values.minByOrNull { it.averageLatencyMs }
            ?.let { providers[it.id]!! } ?: throw IllegalArgumentException("no providers registered")
        is SelectionRule.Fallback -> {
            if (rule.primary in providers && descriptors[rule.primary]?.id == rule.primary) providers[rule.primary]!!
            else if (rule.secondary in providers && descriptors[rule.secondary]?.id == rule.secondary) providers[rule.secondary]!!
            else throw IllegalArgumentException("no fallback provider found")
        }
    }

    fun descriptors(): List<ProviderDescriptor> = descriptors.values.toList()
}

