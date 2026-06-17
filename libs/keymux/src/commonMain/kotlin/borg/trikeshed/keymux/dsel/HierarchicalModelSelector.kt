package borg.trikeshed.keymux.dsel

import kotlinx.serialization.Serializable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.mutable

/**
 * Prefix transformation rule for hierarchical model IDs.
 */
@Serializable
data class PrefixTransformation(
    val pattern: String,
    val replacement: String,
    val priority: Byte,
)

/**
 * Hierarchical model selector that handles prefix transformations.
 */
class HierarchicalModelSelector(
    private val baseSelector: QuotaContainer,
) {
    private val prefixCache = ConcurrentHashMap<String, String>()
    private val transformationRules = mutable.MutableList<PrefixTransformation>()
    private val mutex = Mutex()

    fun addTransformationRule(pattern: String, replacement: String, priority: Byte) {
        mutex.withLock {
            transformationRules.add(PrefixTransformation(pattern, replacement, priority))
            // Sort by priority (higher first)
            transformationRules.sortByDescending { it.priority }
        }
    }

    /**
     * Transform hierarchical model ID by applying registered rules.
     */
    fun transform(modelId: String): String {
        // Check cache first
        return prefixCache.computeIfAbsent(modelId) {
            var transformed = modelId
            mutex.withLock {
                for (rule in transformationRules) {
                    if (transformed.startsWith(rule.pattern)) {
                        transformed = rule.replacement + transformed.substring(rule.pattern.length)
                        break // Apply first matching rule only
                    }
                }
            }
            transformed
        }
    }

    /**
     * Handle complex hierarchical transformations (e.g., /litellm/litellm/litellm/ -> /litellm/).
     */
    fun handleComplexTransformations(modelId: String): List<String> {
        val results = mutable.MutableList<String>()
        var current = modelId
        var iterations = 0
        val maxIterations = 10

        while (iterations < maxIterations) {
            val transformed = transform(current)
            if (transformed == current) break
            results.add(transformed)
            current = transformed
            iterations++
        }

        // Always include original
        if (modelId !in results) {
            results.add(0, modelId)
        }

        return results
    }

    /**
     * Select best provider approximation for a hierarchical model ID.
     */
    fun selectBestApproximation(hierarchicalModelId: String): ProviderPotential? {
        val transformations = handleComplexTransformations(hierarchicalModelId)

        for (transformed in transformations) {
            val parts = transformed.split('/').filter { it.isNotBlank() }
            if (parts.isNotEmpty()) {
                val providerName = parts[0]
                val provider = baseSelector.getProvider(providerName)
                if (provider != null) {
                    return provider
                }
            }
        }

        return null
    }

    /**
     * Get provider for a model ID by extracting provider prefix.
     */
    fun getProviderForModelId(modelId: String): ProviderPotential? {
        val transformed = transform(modelId)
        val parts = transformed.split('/').filter { it.isNotBlank() }
        if (parts.isNotEmpty()) {
            return baseSelector.getProvider(parts[0])
        }
        return null
    }
}
