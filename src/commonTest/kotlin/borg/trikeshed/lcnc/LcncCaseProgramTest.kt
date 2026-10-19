package borg.trikeshed.lcnc

import borg.trikeshed.lib.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class LcncCaseProgramTest {
    @Test fun falseScopesCannotOverwriteTheSelectedOriginalCounselYields() = runTest {
        for (name in s_["preset-tribunal-case", "preset-legal-tribunal-case"].view) {
            val source = LcncPresets.all()
            val program = LcncProgramConfix.fromJson(name, source.getValue(name))
            val violations = LcncTypeCheck.check(program)
            assertTrue(violations.isEmpty(), "$name: ${violations.joinToString { it.render() }}")
            val original = LcncProgramConfix.fromJson(name.removeSuffix("-case"), source.getValue(name.removeSuffix("-case")))
            val originalSeat = original.nodes.view.single { it.id == "n2" }
            val calls = ArrayList<LcncNode>()
            val prefix = ArrayList<String>()
            val brief = "Motion: retain the actual brief"
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
            LcncCaseProgram.validate(program)
            assertTrue(LcncRunner(registry).runProcedure(program, mapOf("brief" to brief)).returns.isEmpty())
            assertTrue(calls.isEmpty()); assertTrue(prefix.isEmpty())
            val result = LcncRunner(registry).runProcedure(program, LcncCaseProgram.arguments(mapOf("brief" to brief), "argue"))
            val prompt = if (name == "preset-legal-tribunal-case") "grounded:$brief" else brief
            assertEquals(listOf("n2"), calls.map { it.id })
            assertEquals(originalSeat.params, calls.single().params)
            assertEquals(if (name == "preset-legal-tribunal-case") listOf("n1", "n1b") else emptyList(), prefix)
            assertEquals(mapOf("prompt" to prompt, "content" to "answer:$prompt", "model" to "actual-model"), result.returns)
            assertTrue(result.nodeOutputs.keys.none { it in setOf("n3", "n4", "n5", "clarify") })
        }
    }
}
