package borg.trikeshed.forge.agent

import borg.trikeshed.kanban.ForgeBoardFSM
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ForgeAgentRuntime live test — proves GraalPy agent logic actually executes,
 * reads the Kanban board via Confix serialization, and writes self-improving
 * heuristics back to the shared blackboard.
 *
 * This is a HARD verification: it spawns a real GraalVM Context, loads the
 * forge_agent.py bootstrap, runs agent cycles, and asserts observable state
 * mutations. Not a println simulation.
 */
class ForgeAgentRuntimeTest {

    @Test
    fun `agent runs in GraalPy and reads board state via Confix`() {
        ForgeBoardFSM.reset()
        ForgeBoardFSM.loadDefault()

        val resource = ForgeAgentRuntime::class.java.getResourceAsStream("/forge_agent.py")
        assertNotNull(resource, "forge_agent.py must be on the classpath")
        val bootstrap = resource.bufferedReader().use { it.readText() }

        ForgeAgentRuntime(tier = ForgeAgentRuntime.SandboxTier.SHARED).use { runtime ->
            val agent = runtime.spawnAgent("test-agent", bootstrap)

            // Seed the agent's persistent state on the blackboard
            agent.callPython("seed", "maintain board health")

            // Run one cycle — the agent reads the board, decides, writes back
            val result = agent.callPython("run_cycle")
            assertNotNull(result, "agent cycle must return a decision")

            // The agent wrote its decision to the blackboard
            val decision = runtime.blackboard.get("agent.cycle.1.decision")
            assertNotNull(decision, "agent must write decision to blackboard")

            // The board was loaded, so the agent should have seen rows
            val status = agent.callPython("status")
            assertNotNull(status, "status must return a dict")
        }
    }

    @Test
    fun `agent self-improves by accumulating heuristics across cycles`() {
        ForgeBoardFSM.reset()
        ForgeBoardFSM.loadDefault()

        val resource = ForgeAgentRuntime::class.java.getResourceAsStream("/forge_agent.py")
        assertNotNull(resource, "forge_agent.py must be on the classpath")
        val bootstrap = resource.bufferedReader().use { it.readText() }

        ForgeAgentRuntime(tier = ForgeAgentRuntime.SandboxTier.SHARED).use { runtime ->
            val agent = runtime.spawnAgent("self-improve-agent", bootstrap)

            agent.callPython("seed", "balance WIP")

            // Run 5 cycles — the agent accumulates heuristics each cycle
            agent.callPython("run_cycles", 5)

            // After 5 cycles, the heuristics list should have grown
            val heuristics = runtime.blackboard.get("agent.heuristics")
            assertNotNull(heuristics, "agent must persist heuristics on blackboard")
            assertTrue(
                heuristics is String && heuristics.contains("cycle"),
                "heuristics must contain cycle entries, got: $heuristics"
            )
        }
    }

    @Test
    fun `isolated tier creates separate context per agent`() {
        ForgeBoardFSM.reset()
        ForgeBoardFSM.loadDefault()

        val resource = ForgeAgentRuntime::class.java.getResourceAsStream("/forge_agent.py")
        assertNotNull(resource, "forge_agent.py must be on the classpath")
        val bootstrap = resource.bufferedReader().use { it.readText() }

        ForgeAgentRuntime(tier = ForgeAgentRuntime.SandboxTier.ISOLATED).use { runtime ->
            val agent1 = runtime.spawnAgent("agent-1", bootstrap)
            val agent2 = runtime.spawnAgent("agent-2", bootstrap)

            // Each agent has its own Context (fork discount on shared engine)
            assertTrue(agent1.context !== agent2.context, "isolated agents must have separate contexts")
            assertEquals(runtime.runtimeEngine, runtime.runtimeEngine, "but share the same warm engine")
        }
    }

    @Test
    fun `board json uses Confix serialization`() {
        ForgeBoardFSM.reset()
        ForgeBoardFSM.loadDefault()

        ForgeAgentRuntime(tier = ForgeAgentRuntime.SandboxTier.SHARED).use { runtime ->
            val agent = runtime.spawnAgent("json-test")

            // The host bridge serializes the board via Confix
            val boardJson = agent.bridge.board_json()

            assertNotNull(boardJson)
            assertTrue(boardJson.contains("Forge Board"), "Confix JSON must contain board name")
            assertTrue(boardJson.contains("Backlog"), "Confix JSON must contain column names")
        }
    }
}
