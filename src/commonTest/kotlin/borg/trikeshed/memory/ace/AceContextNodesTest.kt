package borg.trikeshed.memory.ace

import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.job.ContentId
import borg.trikeshed.lcnc.LcncContracts
import borg.trikeshed.lcnc.LcncNode
import borg.trikeshed.lcnc.LcncPresets
import borg.trikeshed.lcnc.LcncProgramConfix
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * R8/K gates: the context family are real LCNC citizens — contracts exist, the
 * preset parses, the fold is a deterministic merge (never an LLM rewrite), and
 * running assemble mints a real context-receipt the blackboard pane reads.
 */
class AceContextNodesTest {

    @Test
    fun foldIsDeterministicAppendOnlyAndDedups() {
        val a = BulletId(1) j ContentId.of("alpha".encodeToByteArray())
        val b = BulletId(2) j ContentId.of("beta".encodeToByteArray())
        val dupContent = BulletId(3) j ContentId.of("alpha".encodeToByteArray())
        val empty = emptyList<PlaybookBullet>().toSeries()
        val once = AcePlaybookFold.fold(empty, listOf(a, b, dupContent).toSeries())
        val twice = AcePlaybookFold.fold(empty, listOf(a, b, dupContent).toSeries())
        assertEquals(2, once.size, "content dedup drops the duplicate bullet")
        val bytes1 = AcePlaybookFold.playbookBytes(once) { null }
        val bytes2 = AcePlaybookFold.playbookBytes(twice) { null }
        assertTrue(bytes1.contentEquals(bytes2), "same inputs → byte-identical playbook")

        // Append-only: folding a delta with an existing id changes nothing.
        val again = AcePlaybookFold.fold(once, listOf(BulletId(1) j ContentId.of("mutated".encodeToByteArray())).toSeries())
        assertEquals(once.size, again.size, "existing bullet ids never rewrite — collapse prevention")
    }

    @Test
    fun assembleMintsARealContextReceiptAndConfigForksTheChain() = runTest {
        val blackboard = ConfixBlackboard()
        val registry = AceContextNodes.registry(blackboard)
        val node = LcncNode("n2", "context.assemble", params = mapOf("model" to "m1", "effort" to "medium", "tools" to "b,a"))
        val inputs = mapOf("toolsSystem" to "T", "playbook" to "P", "envelope" to "E", "tail" to "V")

        val out1 = registry.getValue("context.assemble").run(node, inputs)
        val out2 = registry.getValue("context.assemble").run(node, inputs)
        assertEquals(out1["chainHead"], out2["chainHead"], "identical program+config → identical chain head")

        val head = out1["chainHead"].toString()
        val receipt = blackboard.get("context-receipt/${head.removePrefix("sha256:")}")
        assertTrue(receipt is Map<*, *> && receipt["chainHead"] == head, "context receipt landed: $receipt")

        val forked = registry.getValue("context.assemble")
            .run(LcncNode("n2", "context.assemble", params = mapOf("model" to "m2", "effort" to "medium", "tools" to "b,a")), inputs)
        assertNotEquals(out1["chainHead"], forked["chainHead"], "config change forks the chain explicitly")
    }

    @Test
    fun foldRunnerProducesCanonicalPlaybookText() = runTest {
        val registry = AceContextNodes.registry()
        val bullets = """[{"id":2,"content":"second"},{"id":1,"content":"first"},{"id":3,"content":"first"}]"""
        val out = registry.getValue("context.fold").run(LcncNode("n1", "context.fold"), mapOf("bullets" to bullets))
        val text = out["playbook"].toString()
        assertTrue(text.startsWith("ace-playbook-v1"), "canonical header")
        assertTrue(text.indexOf("first") < text.indexOf("second"), "id order is the born order")
        assertEquals(1, Regex("first").findAll(text).count(), "content dedup applied")
    }

    @Test
    fun presetContextParsesAndEveryTypeHasAContract() {
        val json = LcncPresets.all().getValue("preset-context")
        val program = LcncProgramConfix.fromJson("preset-context", json)
        assertTrue(program.nodes.size >= 3)
        val contracts = LcncContracts.all().map { it.type }.toSet()
        for (i in 0 until program.nodes.size) {
            val t = program.nodes[i].type
            assertTrue(t in contracts, "preset-context node type '$t' missing a contract")
        }
    }
}
