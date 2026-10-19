package borg.trikeshed.lcnc

import borg.trikeshed.lib.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class LcncWarmOriginRoundtripTest {
    @Test fun equalNumericBindingOriginsSurviveAnyFoldingAndCompactedListTransport() = runTest {
        val program = LcncProgram("warm-each-any-origin", s_[
            LcncNode("items", "json.value", mapOf("value" to "[true,false]")),
            LcncNode("producer", "scope", children = s_[
                LcncNode("rows", "scope", children = s_[
                    LcncNode("turn", "scope.in", mapOf("name" to "item")),
                    LcncNode("left", "scope", children = s_[
                        LcncNode("readA", "scope.in", mapOf("name" to "a", "kind" to "num")),
                        LcncNode("leftYield", "scope.out", mapOf("name" to "value", "kind" to "num"))]),
                    LcncNode("right", "scope", mapOf("else" to "true"), children = s_[
                        LcncNode("readB", "scope.in", mapOf("name" to "b", "kind" to "num")),
                        LcncNode("rightYield", "scope.out", mapOf("name" to "value", "kind" to "num"))]),
                    LcncNode("merge", "merge"),
                    LcncNode("rowYield", "scope.out", mapOf("name" to "value", "kind" to "num"))]),
                LcncNode("packedYield", "scope.out", mapOf("name" to "values", "kind" to "List<num>"))]),
            // The structural returns envelope is json; its selected list reaches groupBy without a List<num>/json wire mismatch.
            LcncNode("pick", "pick", mapOf("path" to "values")),
            LcncNode("grouped", "list.groupBy", mapOf("key" to "x")),
            LcncNode("first", "button"),
            LcncNode("expireA", "scope", children = s_[
                LcncNode("activationA", "scope.in", mapOf("name" to "activation", "kind" to "trigger")),
                LcncNode("lateA", "scope.in", mapOf("name" to "a", "kind" to "trigger"))]),
            LcncNode("second", "button"),
            LcncNode("expireB", "scope", children = s_[
                LcncNode("activationB", "scope.in", mapOf("name" to "activation", "kind" to "trigger")),
                LcncNode("lateB", "scope.in", mapOf("name" to "b", "kind" to "trigger"))]),
            LcncNode("other", "button")], s_[
            LcncWire("items", "value", "rows", "each"),
            LcncWire("turn", "value", "left", "when"), LcncWire("turn", "value", "right", "when"),
            LcncWire("readA", "value", "leftYield", "value"), LcncWire("readB", "value", "rightYield", "value"),
            LcncWire("left", "value", "merge", "a?"), LcncWire("right", "value", "merge", "b?"),
            LcncWire("merge", "value", "rowYield", "value"),
            LcncWire("rows", "value", "packedYield", "value"),
            LcncWire("producer", "returns", "pick", "x"), LcncWire("pick", "y", "grouped", "x"),
            LcncWire("first", "press", "expireA", "activation"), LcncWire("second", "press", "expireB", "activation")])
        assertTrue(LcncTypeCheck.check(program).isEmpty())
        var iterations = 0
        val readers = ArrayList<String>()
        val runner = LcncRunner(PureNodes.registry { 1L }).apply {
            onScopeEnter = { path, _ ->
                if (path.last() == "rows") iterations++
                if (path.last() == "expireA" || path.last() == "expireB") readers.add(path.last())
            }
        }
        val args = mapOf("a" to 1L, "b" to 1L)
        var result = runner.runProcedure(program, args)
        assertEquals(2, iterations)
        assertTrue(readers.isEmpty())
        assertEquals(mapOf("y" to listOf(1L, 1L)), result.retained.outputs["pick"])
        assertEquals(mapOf("groups" to mapOf("" to listOf(1L, 1L))), result.retained.outputs["grouped"])
        assertEquals(args, result.retained.bindings)

        runner.pulse = LcncPulse("first", mapOf("press" to 2L), result.retained)
        result = runner.runProcedure(program, args)
        assertEquals(mapOf("y" to listOf(1L, 1L)), result.nodeOutputs["pick"], "Actual reused values are not rewritten")
        assertEquals(mapOf("y" to listOf(1L)), result.retained.outputs["pick"])
        assertEquals(mapOf("groups" to mapOf("" to listOf(1L))), result.retained.outputs["grouped"])
        assertEquals(mapOf("b" to 1L), result.retained.bindings)
        assertEquals(listOf("expireA"), readers)
        assertEquals(2, iterations, "Projection cannot rerun the original each branches")

        // This pulse imports the exported origin rows after old slot 1 became retained slot 0.
        runner.pulse = LcncPulse("second", mapOf("press" to 3L), result.retained)
        result = runner.runProcedure(program, args)
        assertEquals(mapOf("y" to listOf(1L)), result.nodeOutputs["pick"])
        assertEquals(emptyMap(), result.retained.outputs["pick"], "The surviving row still belongs to b, not equal-valued retired a")
        assertEquals(emptyMap(), result.retained.outputs["grouped"], "ANY has no live occurrence after b expires")
        assertTrue(result.retained.bindings.isEmpty())
        assertEquals(listOf("expireA", "expireB"), readers)
        assertEquals(2, iterations)

        runner.pulse = LcncPulse("other", mapOf("press" to 4L), result.retained)
        result = runner.runProcedure(program, args)
        assertEquals(emptyMap(), result.nodeOutputs["pick"])
        assertEquals(emptyMap(), result.nodeOutputs["grouped"])
        assertTrue(result.retained.bindings.isEmpty())
        assertEquals(listOf("expireA", "expireB"), readers)
        assertEquals(2, iterations)
    }
}
