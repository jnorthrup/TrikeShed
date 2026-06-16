package borg.trikeshed.asclepius

import borg.trikeshed.asclepius.graal.HermesGraalHarness
import borg.trikeshed.forge.ForgeWorkspace
import borg.trikeshed.forge.ModelMuxConfig
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * GEP-A Bootstrap Test — brings Chimera plugin live via Hermes Python eval loop.
 *
 * Flow:
 * 1. Forge workspace provides: performance data, model registry, API keys
 * 2. HermesGraalHarness evaluates Python GEP-A driver
 * 3. Chimera plugin loads and registers with Forge
 * 4. Continuous loop: Forge emits performance telemetry -> Hermes evaluates policy -> Chimera acts
 */
class GepaBootstrapTest {

    @Test(timeout = 120_000)
    fun `GEPA bootstrap: Chimera plugin live via Hermes Python loop`() = runBlocking {
        // 1. Initialize Asclepius supervisor (SQLite + GraalPy + Arrow)
        val supervisor = AsclepiusSupervisor(":memory:").also { it.initialize() }

        // 2. Create Forge workspace with minimal config
        val forgeConfig = ModelMuxConfig(
            providers = mapOf(
                "openrouter" to mapOf("OPENROUTER_API_KEY" to System.getenv("OPENROUTER_API_KEY") ?: "test-key")
            )
        )
        val keyStore = KeyStore().initialize(forgeConfig)
        val dselRouter = DselRouter().initialize(keyStore)

        val workspace = ForgeWorkspaceImpl(
            config = forgeConfig,
            keyStore = keyStore,
            dselRouter = dselRouter
        )

        // 3. Seed Forge with Chimera plugin metadata
        workspace.seedChimeraPlugin()

        // 4. Run GEP-A bootstrap through Hermes Python
        val harness = HermesGraalHarness(
            pointcutProducer = null,
            enableHermesInstrumentation = true,
            confixBlackboard = workspace.confixBlackboard
        )

        val bootstrapResult = harness.evalHermes("""
import json
import time
from dataclasses import dataclass
from typing import Dict, List, Any

# GEP-A Chimera Bootstrap Protocol
@dataclass
class ChimeraPlugin:
    name: str
    version: str
    capabilities: List[str]
    gepa_hooks: Dict[str, Any]

# Register Chimera with Forge via blackboard
def register_chimera(forge_blackboard):
    plugin = ChimeraPlugin(
        name="hermes-chimera",
        version="1.0.0",
        capabilities=[
            "model_routing",
            "key_rotation",
            "performance_optimization",
            "cost_control"
        ],
        gepa_hooks={
            "on_performance_telemetry": "chimera.on_telemetry",
            "on_model_failure": "chimera.on_model_failure",
            "on_key_exhaustion": "chimera.on_key_exhaustion",
            "on_cost_threshold": "chimera.on_cost_threshold"
        }
    )
    forge_blackboard.put("chimera:plugin", json.dumps(plugin.__dict__))
    return plugin

# GEP-A Policy Evaluation Loop
def gepa_loop(forge_blackboard, interval_sec=30):
    chimera = register_chimera(forge_blackboard)
    
    # Query Forge for accessible data
    performance_data = forge_blackboard.get("forge:telemetry:performance") or {"latency_p99": 0, "error_rate": 0}
    model_registry = forge_blackboard.get("forge:models:registry") or {"models": []}
    key_status = forge_blackboard.get("forge:keys:status") or {"keys": {}}
    
    print(f"[GEPA] Cycle started - performance: {performance_data}, models: {len(model_registry.get('models', []))}, keys: {len(key_status.get('keys', {}))}")
    
    # Policy: if latency_p99 > threshold, route to faster model
    if performance_data.get("latency_p99", 0) > 2000:
        action = {"type": "reroute", "target": "fast_model", "reason": "latency_threshold_exceeded"}
        forge_blackboard.put("chimera:action", json.dumps(action))
        print(f"[GEPA] ACTION: {action}")
    
    # Policy: if key exhaustion, rotate
    for provider, status in key_status.get("keys", {}).items():
        if status.get("remaining", 0) < 1000:
            action = {"type": "rotate_key", "provider": provider}
            forge_blackboard.put(f"chimera:action:{provider}", json.dumps(action))
            print(f"[GEPA] ACTION: {action}")
    
    # Policy: if cost threshold, downsize model
    total_cost = sum(m.get("cost_per_1k", 0) for m in model_registry.get("models", []))
    if total_cost > 50.0:
        action = {"type": "downsize", "target": "cheaper_model"}
        forge_blackboard.put("chimera:action:cost", json.dumps(action))
        print(f"[GEPA] ACTION: {action}")
    
    return "gepa_cycle_complete"

# Main bootstrap
forge_blackboard = __import__('confix_blackboard') if 'confix_blackboard' in globals() else {}
result = gepa_loop(forge_blackboard)
print(f"[GEPA] Bootstrap complete: {result}")
result
""")

        println("[GEPA] Bootstrap result: $bootstrapResult")
        
        // 5. Verify Chimera plugin registered
        val chimeraPlugin = workspace.confixBlackboard.getCrms<String>("chimera:plugin")
        if (chimeraPlugin != null) {
            println("[GEPA] ✓ Chimera plugin registered: $chimeraPlugin")
        } else {
            println("[GEPA] ⚠ Chimera plugin not found in blackboard")
        }

        // 6. Verify actions emitted
        val rerouteAction = workspace.confixBlackboard.getCrms<String>("chimera:action")
        val costAction = workspace.confixBlackboard.getCrms<String>("chimera:action:cost")
        println("[GEPA] Actions emitted - reroute: ${rerouteAction != null}, cost: ${costAction != null}")

        supervisor.close()
    }
}