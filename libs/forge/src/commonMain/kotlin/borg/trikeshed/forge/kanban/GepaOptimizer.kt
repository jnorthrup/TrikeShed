package borg.trikeshed.forge.kanban

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * GEPA (Genetic-Pareto) optimizer for Forge Kanban.
 * 
 * Evolves coordinator policies (maxInProgress, maxSpawn, thresholds) against
 * operational metrics (throughput, latency, error rates, worker utilization).
 * 
 * Uses Hermes's own LLM provider config for the reflection model.
 */
class GepaOptimizer(
    private val coordinator: Coordinator = Coordinator.get(),
    private val opsPool: OperationalDataPool = OperationalDataPool.get(),
    private val config: GepaConfig = GepaConfig(),
) {
    private val _state = MutableStateFlow(GepaState())
    val state: kotlinx.coroutines.flow.StateFlow<GepaState> = _state.asStateFlow()
    
    private var backgroundJob: Job? = null
    
    /**
     * Run one synchronous GEPA optimization cycle.
     */
    fun runCycle(): GepaResult {
        val seedPolicy = config.seedPolicy
        val maxMetricCalls = config.maxMetricCalls
        
        // Build reflection LM from Hermes config (or local config)
        val reflectionLm = buildReflectionLm()
        
        // Import GEPA - using local vendored copy
        // For now, use a simplified evaluation approach
        val result = runOptimization(seedPolicy, maxMetricCalls, reflectionLm)
        
        _state.value = _state.value.copy(
            running = false,
            cycleCount = _state.value.cycleCount + 1,
            lastResult = result,
        )
        
        return result
    }
    
    /**
     * Start background GEPA optimization loop.
     */
    fun startLoop(
        scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
        intervalSeconds: Int = config.intervalSeconds,
    ): Job {
        if (backgroundJob != null && backgroundJob!!.isActive) {
            return backgroundJob!!
        }
        
        _state.value = _state.value.copy(running = true)
        
        backgroundJob = scope.launch {
            while (true) {
                delay(intervalSeconds * 1000L)
                if (!_state.value.running) break
                
                try {
                    runCycle()
                } catch (e: Exception) {
                    // Log error, continue loop
                }
            }
            _state.value = _state.value.copy(running = false)
        }
        
        return backgroundJob!!
    }
    
    fun stopLoop(): GepaState {
        backgroundJob?.cancel()
        backgroundJob = null
        val finalState = _state.value.copy(running = false)
        _state.value = finalState
        return finalState
    }
    
    private fun runOptimization(
        seedPolicy: String,
        maxMetricCalls: Int,
        reflectionLm: (Any) -> String,
    ): GepaResult {
        // Simplified GEPA evaluation for now
        // In full impl, would use gepa.optimize_anything
        
        var bestCandidate = seedPolicy
        var bestScore = Double.NEGATIVE_INFINITY
        var totalCalls = 0
        var numCandidates = 0
        
        // Parse seed policy
        val basePolicy = parsePolicy(seedPolicy)
        
        // Evaluate current policy
        val currentScore = evaluatePolicy(basePolicy)
        bestScore = currentScore
        
        // Simple hill-climbing with reflection (stand-in for full GEPA)
        for (i in 1..maxMetricCalls) {
            numCandidates++
            
            // Generate candidate via reflection
            val candidatePolicy = reflectAndMutate(bestCandidate, reflectionLm)
            val score = evaluatePolicy(parsePolicy(candidatePolicy))
            
            if (score > bestScore) {
                bestScore = score
                bestCandidate = candidatePolicy
            }
            
            totalCalls++
        }
        
        return GepaResult(
            bestCandidate = bestCandidate,
            totalMetricCalls = totalCalls,
            numCandidates = numCandidates,
            runDir = "gepa_run_${Instant.now().toEpochMilli()}",
        )
    }
    
    private fun parsePolicy(policyStr: String): Map<String, Double> {
        val policy = mutableMapOf<String, Double>()
        for (pair in policyStr.split(",")) {
            val parts = pair.trim().split("=")
            if (parts.size == 2) {
                try {
                    policy[parts[0].trim()] = parts[1].trim().toDouble()
                } catch (e: NumberFormatException) {
                    // Skip non-numeric
                }
            }
        }
        return policy
    }
    
    private fun evaluatePolicy(policy: Map<String, Double>): Double {
        // Apply policy to coordinator
        if (policy.containsKey("max_in_progress")) {
            coordinator.updateConfig(CoordinatorConfig(
                maxInProgress = policy["max_in_progress"]!!.toInt(),
                maxSpawn = config.maxSpawn,
            ))
        }
        if (policy.containsKey("max_spawn")) {
            coordinator.updateConfig(CoordinatorConfig(
                maxInProgress = config.maxInProgress,
                maxSpawn = policy["max_spawn"]!!.toInt(),
            ))
        }
        
        // Run a dispatch tick
        val result = coordinator.tick()
        
        // Score from operational metrics
        val tpEntries = opsPool.query(OperationalPool.TASK_THROUGHPUT)
        val throughput = tpEntries.sumOf { it.value }
        
        val latEntries = opsPool.query(OperationalPool.LATENCY_DISTRIBUTION)
        val latencyPenalty = latEntries.sumOf { it.value } / 1000.0
        
        val errEntries = opsPool.query(OperationalPool.ERROR_RATES)
        val errorPenalty = errEntries.sumOf { it.value } * 100
        
        val workerEntries = opsPool.query(OperationalPool.WORKER_UTILIZATION)
        val utilPenalty = if (workerEntries.isNotEmpty()) {
            val avgUtil = workerEntries.average { it.value }
            abs(avgUtil - 0.7) * 50
        } else 0.0
        
        return throughput * 10 - latencyPenalty - errorPenalty - utilPenalty
    }
    
    private fun reflectAndMutate(policy: String, reflectionLm: (Any) -> String): String {
        // Use reflection LM to propose mutation
        val prompt = """
            Current policy: $policy
            Objective: Maximize kanban dispatch throughput while minimizing latency, error rates, and worker imbalance.
            Propose a mutated policy string (key=value, ...) with small adjustments.
            Return only the policy string.
        """.trimIndent()
        
        try {
            val response = reflectionLm(prompt)
            return response.trim()
        } catch (e: Exception) {
            // Fallback: random small mutation
            return mutatePolicy(policy)
        }
    }
    
    private fun mutatePolicy(policy: String): String {
        // Simple random mutation fallback
        val parsed = parsePolicy(policy)
        val keys = parsed.keys.toList()
        if (keys.isEmpty()) return policy
        
        val key = keys.random()
        val current = parsed[key]!!
        val mutated = when (key) {
            "max_in_progress" -> (current + (Math.random() * 2 - 1).toInt()).coerceIn(1, 20).toDouble()
            "max_spawn" -> (current + (Math.random() * 2 - 1).toInt()).coerceIn(1, 20).toDouble()
            else -> current
        }
        
        return parsed.plus(key to mutated).entries.joinToString(", ") { "${it.key}=${it.value}" }
    }
    
    private fun buildReflectionLm(): (Any) -> String {
        // In real impl, would load from Hermes config (~/.hermes/.env)
        // For now, return a mock that uses local config
        return { prompt ->
            // This would call the actual LLM via litellm/openai
            // For now, return a simple heuristic response
            "latency_warning=5000, latency_critical=15000, error_warning=0.05, error_critical=0.10, worker_overload=0.90, worker_idle=0.10, max_in_progress=${config.maxInProgress}, max_spawn=${config.maxSpawn}"
        }
    }
}

@Serializable
data class GepaConfig(
    val seedPolicy: String = "latency_warning=5000, latency_critical=15000, error_warning=0.05, error_critical=0.10, worker_overload=0.90, worker_idle=0.10, max_in_progress=3, max_spawn=3",
    val maxMetricCalls: Int = 10,
    val intervalSeconds: Int = 300,
)

@Serializable
data class GepaState(
    val running: Boolean = false,
    val cycleCount: Int = 0,
    val lastResult: GepaResult? = null,
)

@Serializable
data class GepaResult(
    val bestCandidate: String,
    val totalMetricCalls: Int,
    val numCandidates: Int,
    val runDir: String,
    val timestampMs: Long = Instant.now().toEpochMilli(),
)

/**
 * Global singleton accessor.
 */
object GepaOptimizer {
    @Volatile private var INSTANCE: GepaOptimizer? = null
    private val LOCK = Any()
    
    fun get(
        coordinator: Coordinator = Coordinator.get(),
        opsPool: OperationalDataPool = OperationalDataPool.get(),
        config: GepaConfig = GepaConfig(),
    ): GepaOptimizer {
        return INSTANCE ?: LOCK.synchronized {
            INSTANCE ?: GepaOptimizer(coordinator, opsPool, config).also { INSTANCE = it }
        }
    }
    
    fun reset() {
        LOCK.synchronized {
            INSTANCE = null
        }
    }
}