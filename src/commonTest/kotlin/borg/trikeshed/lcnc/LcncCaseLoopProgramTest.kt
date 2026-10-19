package borg.trikeshed.lcnc

import borg.trikeshed.lib.*
import borg.trikeshed.kanban.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class LcncCaseLoopProgramTest {
    @Test fun repeatCounselUsesTheOriginalSeatWithoutRepeatingLegalIntake() = runTest {
        val offered = LcncPresets.all()
        for (name in s_["preset-tribunal-case-v2", "preset-legal-tribunal-case-v2"].view) {
            val program = LcncProgramConfix.fromJson(name, offered.getValue(name))
            val violations = LcncTypeCheck.check(program)
            assertTrue(violations.isEmpty(), "$name: ${violations.joinToString { it.render() }}")
            val graph = assertNotNull(program.kanban)
            assertTrue(graph.validate(TribunalPredicates.registry()).valid)
            val terminal = LcncCaseProgram.scope(program, "rule")
            assertEquals(1, terminal.children.view.count { it.type == "tribunal.record" })
            assertTrue(terminal.children.view.none { it.type in setOf("mux.chat", "legal.ingest", "legal.evidence") })
            assertEquals(0, graph.outgoing("rule").size)
            val originalName = name.removeSuffix("-case-v2")
            val original = LcncProgramConfix.fromJson(originalName, offered.getValue(originalName))
            val originalSeat = original.nodes.view.single { it.id == "n2" }
            val calls = ArrayList<LcncNode>()
            val prefix = ArrayList<String>()
            val registry = PureNodes.registry { 1L } + TribunalNodes.registry(TribunalDialog { node, _, prompt ->
                calls.add(node)
                "answer:$prompt" to "actual-model"
            }) + mapOf(
                "legal.ingest" to LcncNodeRunner { node, inputs ->
                    prefix.add(node.id)
                    mapOf("brief" to "grounded:${inputs["text?"]}")
                },
                "legal.evidence" to LcncNodeRunner { node, inputs ->
                    prefix.add(node.id)
                    mapOf("brief" to inputs["brief?"])
                },
            )
            val brief = "Motion: preserve the original source"
            assertTrue(LcncRunner(registry).runProcedure(program, mapOf("brief" to brief)).returns.isEmpty())
            assertTrue(calls.isEmpty())
            assertTrue(prefix.isEmpty())
            val first = LcncRunner(registry).runProcedure(program,
                LcncCaseProgram.arguments(mapOf("brief" to brief), "argue"))
            val prompt = if (name == "preset-legal-tribunal-case-v2") "grounded:$brief" else brief
            assertEquals(mapOf("prompt" to prompt, "content" to "answer:$prompt", "model" to "actual-model"), first.returns)
            assertEquals(listOf("n2"), calls.map { it.id })
            assertEquals(originalSeat.params, calls.single().params)
            val initialPrefix = if (name == "preset-legal-tribunal-case-v2") listOf("n1", "n1b") else emptyList()
            assertEquals(initialPrefix, prefix)

            val record = "Actual prior prompt: $prompt\nActual answer: ${first.returns["content"]}\nQuestion: explain the source"
            val repeated = LcncRunner(registry).runProcedure(program,
                LcncCaseProgram.arguments(mapOf("brief" to brief, "record" to record), "argue", repeatArgue = true))
            val scope = LcncCaseProgram.scope(program, "argue", repeatArgue = true)
            val seat = scope.children.view.single { it.type == "mux.chat" }
            assertEquals("case.argue.repeat", scope.id)
            assertEquals(listOf("n2", seat.id), calls.map { it.id })
            assertNotEquals("n2", seat.id)
            assertEquals(originalSeat.params, calls.last().params)
            assertEquals(initialPrefix, prefix, "Repeated counsel must not execute the first argument's legal prefix")
            assertEquals(mapOf("prompt" to record, "content" to "answer:$record", "model" to "actual-model"), repeated.returns)
            assertTrue(repeated.nodeOutputs.keys.none { it in setOf("n2", "n3", "n4", "n1", "n1b", "n5", "clarify") })

            val previousName = name.removeSuffix("-v2")
            val previous = LcncProgramConfix.fromJson(previousName, offered.getValue(previousName))
            LcncCaseProgram.validate(previous)
            assertFailsWith<IllegalArgumentException> { LcncCaseProgram.scope(previous, "argue", repeatArgue = true) }
            assertFailsWith<IllegalArgumentException> {
                LcncCaseProgram.arguments(mapOf("__case_argue_repeat" to true), "argue")
            }
            assertFailsWith<IllegalArgumentException> {
                LcncCaseProgram.arguments(mapOf("__case_rule" to true), "argue")
            }
        }
    }

    @Test fun sharedFoldPreservesTheExistingNodeFormattingAndAliases() = runTest {
        val runner = CouncilNodes.registry(CouncilDialog { error("No council seat belongs to text.fold") }).getValue("text.fold")
        val parts = listOf("", listOf("first", "[Counsel] second"), mapOf("flag" to true), 5)
        val expected = "== Retained record ==\n\n---\n\n(1) Brief\n\n---\n\n(2) first\n\n---\n\n[Counsel] second\n\n---\n\n(4) {\"flag\":true}\n\n---\n\n(5) 5"
        assertEquals(expected, CouncilNodes.fold("Retained record", "Brief", parts))
        assertEquals(mapOf("text" to expected), runner.execute(
            LcncNode("fold", "text.fold", params = mapOf("label" to "Retained record")),
            mapOf("brief?" to "Brief", "parts?" to parts)))
        val unnumbered = "lead|tail|[Keep]"
        assertEquals(unnumbered, CouncilNodes.fold("", "lead", listOf("tail", "", "[Keep]"), "|", false))
        assertEquals(mapOf("text" to unnumbered), runner.execute(
            LcncNode("fold", "text.fold", params = mapOf("separator" to "|", "numbered" to "false")),
            mapOf("brief" to "lead", "parts" to listOf("tail", "", "[Keep]"))))
    }
}
