package borg.trikeshed.lcnc

import borg.trikeshed.lib.emptySeriesOf
import borg.trikeshed.lib.toList
import borg.trikeshed.lib.toSeries
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The wrapper graph, late-bound ([LcncWrappers], [LcncComposites],
 * [LcncVocabulary]): every type says how and by what it is bound, a stored
 * program with formal ports is a wrapper, and a node whose type names one
 * binds to it when the walk reaches it — not at boot, not at compile.
 */
class LcncWrappersTest {

    private val compiled = LcncContracts.all().associateBy { it.type }

    /** A ring with three formals and one yield: `s` required, `sep?` optional, `n` defaulted. */
    private fun twice(): LcncProgram = LcncProgram(
        "twice",
        nodes = listOf(
            LcncNode("p1", LcncContracts.SCOPE_IN, params = mapOf("name" to "s", "kind" to "text")),
            LcncNode("p2", LcncContracts.SCOPE_IN, params = mapOf("name" to "sep?")),
            LcncNode("p3", LcncContracts.SCOPE_IN, params = mapOf("name" to "n", "default" to "2")),
            LcncNode("r", LcncContracts.SCOPE_OUT, params = mapOf("name" to "out", "kind" to "text")),
        ).toSeries(),
        wires = listOf(LcncWire("p1", "value", "r", "value")).toSeries(),
    )

    @Test
    fun bindingsNameTheClassThatBoundEachType() {
        val registry = PureNodes.registry { 0L }
        val b = LcncWrappers.bindings(LcncContracts.all(), registry, { it.javaClass.name }).associateBy { it.type }
        assertEquals(LcncBindingKind.KOTLIN, b.getValue("timer").kind)
        assertTrue("PureNodes" in b.getValue("timer").provenance, b.getValue("timer").provenance)
        // Rendered by the canvas, never run by the daemon: unbound, and SAID so.
        assertEquals(LcncBindingKind.UNBOUND, b.getValue("display").kind)
        assertEquals(LcncBindingKind.UNBOUND, b.getValue("js").kind)
    }

    @Test
    fun theCanvasJsLaneIsRecognisedByItsOneBinder() {
        val registry = mapOf("pick" to LcncNodeRunner { _, _ -> emptyMap() })
        val b = LcncWrappers.bindings(
            LcncContracts.all(), registry, { "borg.trikeshed.lcnc.CanvasJsPureNodes\$pickRunner\$1" },
        ).associateBy { it.type }
        assertEquals(LcncBindingKind.CANVAS_JS, b.getValue("pick").kind)
    }

    @Test
    fun aRunnerWithoutAWrapperIsStillBoundAndVisible() {
        val registry = mapOf("secret.runner" to LcncNodeRunner { _, _ -> emptyMap() })
        val hidden = LcncWrappers.bindings(LcncContracts.all(), registry, { "x" }).first { it.type == "secret.runner" }
        assertEquals(LcncBindingKind.KOTLIN, hidden.kind)
    }

    @Test
    fun theProvenanceFunctionIsTheOneReflectiveActOncePerRunner() {
        var calls = 0
        val registry = PureNodes.registry { 0L }
        LcncWrappers.bindings(LcncContracts.all(), registry, { calls++; "p" })
        assertEquals(registry.size, calls)
    }

    @Test
    fun aStoredProgramWithFormalPortsIsAWrapper() {
        val c = LcncComposites.contractOf("twice", twice(), compiled)!!
        assertEquals("twice", c.type)
        assertEquals(listOf("s", "sep?", "n?"), c.inputs)
        assertEquals(mapOf("s" to "text", "sep" to LcncKinds.UNRESOLVED, "n" to LcncKinds.UNRESOLVED), c.inputKinds)
        assertEquals(listOf("out"), c.outputs)
        assertEquals(mapOf("out" to "text"), c.outputKinds)
        assertFalse(c.isEffect)
    }

    @Test
    fun anEffectAnywhereInsideMakesTheCompositeAnEffect() {
        val effect = LcncContracts.all().first { it.isEffect }.type
        val p = twice().let { it.copy(nodes = (it.nodes.toList() + LcncNode("k", effect)).toSeries()) }
        assertTrue(LcncComposites.contractOf("twice", p, compiled)!!.isEffect)
    }

    @Test
    fun aProgramWithoutFormalPortsIsADocumentNotAWrapper() {
        val p = LcncProgram("doc", listOf(LcncNode("t", "timer")).toSeries(), emptySeriesOf())
        assertNull(LcncComposites.contractOf("doc", p, compiled))
    }

    @Test
    fun theVocabularyIsCompiledPlusCompositesAndCompiledWinsAName() {
        val v = LcncVocabulary.resolve(mapOf("twice" to twice(), "timer" to twice()))
        assertEquals("twice (composite)", v.getValue("twice").title)
        assertEquals(compiled.getValue("timer"), v.getValue("timer"))
        assertEquals(compiled.size + 1, v.size)
    }

    @Test
    fun theOfferedPresetsContributeTheirRingsAsWrappers() {
        val corpus = LcncPresets.all().mapValues { (n, j) -> LcncProgramConfix.fromJson(n, j) }
        val v = LcncVocabulary.resolve(corpus)
        assertTrue("preset-scope-inner" in v, v.keys.filter { it.startsWith("preset-") }.toString())
    }

    @Test
    fun aCompositeTypeBindsLateToItsProgramAndRunsAsANamedRing() = runBlocking {
        val body = twice()
        val outer = LcncProgram(
            "outer",
            nodes = listOf(
                LcncNode("lit", "text.value", params = mapOf("value" to "hi")),
                LcncNode("c", "twice"),
            ).toSeries(),
            wires = listOf(LcncWire("lit", "value", "c", "s")).toSeries(),
        )
        // The STRICT check sees the composite through the late-bound vocabulary…
        assertEquals(emptyList(), LcncTypeCheck.check(outer, LcncVocabulary.resolve(mapOf("twice" to body))))
        // …and the walk binds the name when it reaches it, through the loader.
        val runner = LcncRunner(PureNodes.registry { 0L }).apply {
            subprogramLoader = { n -> if (n == "twice") body else null }
        }
        val out = runner.runAll(outer)
        assertEquals("hi", out.getValue("c")["out"])
        val b = LcncWrappers.bindings(
            LcncContracts.all(), emptyMap(), { "" },
            mapOf("twice" to LcncComposites.contractOf("twice", body, compiled)!!),
        )
        assertEquals(LcncBinding("twice", LcncBindingKind.COMPOSITE, "program:twice"), b.first { it.type == "twice" })
    }
}
