package borg.trikeshed.kanban

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The coding-agent lane's grammar and brief block (Forge genesis, Cut A). */
class PlaneBriefAgentTest {

    @Test
    fun agentAndBudgetParseWithTheirColonsOnly() {
        val spec = PlaneBrief.parseSpec("t", "GOAL: add hello.txt\nMUST: hello.txt is in the patch\nAGENT: Codex\nAGENT-BUDGET: 300\nREVIEW: human")
        assertEquals("codex", spec.agent, "lowercased")
        assertEquals(300, spec.agentBudget)
        assertTrue(spec.humanReview)
        val prose = PlaneBrief.parseSpec("t", "GOAL: an AGENT of change\nAGENT codex without a colon is prose")
        assertEquals("", prose.agent)
        assertEquals(null, prose.agentBudget)
        assertEquals("", PlaneBrief.parseSpec("t", "GOAL: x").agent)
    }

    @Test
    fun theBriefCarriesTheAgentBlockAndTheReplyShapeIsUnchanged() {
        val spec = PlaneBrief.parseSpec("t", "GOAL: add hello.txt\nMUST: hello.txt is in the patch\nAGENT: codex")
        val block = PlaneBrief.AgentBlock(cli = "codex", version = "codex-cli 0.145.0", repo = "the daemon's repository", evidenceId = "blackboard/agent/run/r1", budgetSeconds = 600)
        val with = PlaneBrief.render("j1", "t", spec, emptyList(), emptyList(), emptyList(), emptyList(), block)
        val without = PlaneBrief.render("j1", "t", spec, emptyList(), emptyList(), emptyList())
        assertTrue(with.contains("AGENT: you are codex codex-cli 0.145.0, working in a scratch clone of the daemon's repository at its current HEAD."), with)
        assertTrue(with.contains("evidence id blackboard/agent/run/r1;"), with)
        assertTrue(with.contains("Budget: 600 s."), with)
        assertFalse(without.contains("AGENT:"), without)
        val reply = with.substringAfter("REPLY (exactly this shape")
        assertEquals(without.substringAfter("REPLY (exactly this shape"), reply, "the REPLY block is byte-identical; the judge is unchanged")
        assertTrue(with.indexOf("AGENT: you are") < with.indexOf("FLOW:"), "the block sits before FLOW")
    }

    @Test
    fun fanOutChildrenNeverInheritTheAgentLine() {
        val child = BoardFanOutWorker.childSpec("GOAL: g\nMUST: m\nAGENT: codex\nAGENT-BUDGET: 30\nMODELS: a, b", "a", 2048)
        assertFalse(child.contains("AGENT"), child)
        assertTrue(child.contains("MODEL: a"), child)
        assertEquals("", PlaneBrief.parseSpec("t", child).agent)
    }
}
