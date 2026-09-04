package borg.trikeshed.ontology

import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The classifier over the PINNED corpus (Merge + Mid-level, gradle/sumo-corpus.pins).
 * Numbers are the 2026-09-04 measurement; the ref is pinned so they are exact.
 */
class SumoClassifierCorpusTest {
    private val sumo get() = SumoCorpus.pinned

    @Test
    fun thePinnedCorpusIsReadAndSized() {
        assertTrue(SumoCorpus.text("sumo/Merge.kif").length > 600_000, "Merge.kif is on the classpath")
        val s = sumo.stats
        // Top-level declarations only (nested `(subclass ?X ?Y)` inside rules are not edges).
        assertEquals(2504, s["classes"], "classes: $s")
        assertEquals(2953, s["subclassEdges"], "subclass edges: $s")
        assertEquals(3685, sumo.termCount, "terms declared by subclass/instance/domain/range/disjoint")
        assertEquals(1469, s["domainSlots"]); assertEquals(179, s["rangeSlots"]); assertEquals(3077, s["rules"])
        val shapes = sumo.shapeHistogram()
        assertEquals(0, shapes["bitmap"], "no set in a 2,558-bit universe should need a bitmap: $shapes")
        assertTrue(shapes.getValue("run") > 200, "DFS numbering puts descendant sets in runs: $shapes")
        println("[SumoClassifier] stats=$s shapes=$shapes")
    }

    @Test
    fun hierarchyIsABitTest() {
        assertTrue(sumo.isA("Human", "Entity")); assertTrue(sumo.isA("Human", "Object")); assertTrue(sumo.isA("Human", "Organism"))
        assertTrue(sumo.isA("Human", "AutonomousAgent")); assertTrue(sumo.isA("Human", "CognitiveAgent")); assertTrue(sumo.isA("Human", "Human"))
        assertFalse(sumo.subclassOf("Human", "Abstract")); assertFalse(sumo.subclassOf("Human", "Process")); assertFalse(sumo.subclassOf("Entity", "Human"))
        assertTrue(sumo.isA("Human", "Abstract"), "the TERM Human is an instance of Class ⊂ SetOrClass ⊂ Abstract — SUMO's union reading")
        assertFalse(sumo.isA("Human", "Process")); assertFalse(sumo.isA("Meter", "Physical"))
        assertTrue(sumo.isA("Meter", "UnitOfMeasure"), "instance closure") ; assertTrue(sumo.isA("Meter", "Abstract"))
        assertFalse(sumo.isA("NoSuchTerm", "Entity")); assertFalse(sumo.isA("Human", "NoSuchClass"))
        val ups = sumo.superclassesOf("Human")
        assertEquals("Entity", ups[0], "root-most first")
        assertTrue(sumo.subclassesOf("Organism").size > 100)
    }

    @Test
    fun typeConstraintsAndDisjointness() {
        assertEquals("Organism", sumo.domainOf("parent", 1))
        assertTrue(sumo.domainOk("parent", 1, "Human")); assertFalse(sumo.domainOk("parent", 1, "Meter"))
        assertTrue(sumo.domainOk("undeclaredPredicate", 1, "Meter"), "no declaration, no constraint")
        assertTrue(sumo.domainOk("subclass", 1, "Human"), "(domain subclass 1 Class): every class is an instance of Class")
        assertTrue(sumo.isA("Human", "SetOrClass"), "Class ⊂ SetOrClass, so a class term is-a SetOrClass as an instance")
        assertFalse(sumo.domainOk("subclass", 1, "Meter"), "Meter is an instance, not a class")
        assertEquals("SetOrClass", sumo.rangeOf("PowerSetFn")); assertTrue(sumo.rangeIsSubclass("PowerSetFn"))
        assertNull(sumo.rangeOf("parent"))
        assertTrue(sumo.disjoint("Physical", "Abstract"), "partition Entity Physical Abstract")
        assertTrue(sumo.disjoint("Human", "Abstract"), "inherited from Physical")
        assertTrue(sumo.disjoint("Abstract", "Human"), "symmetric")
        assertTrue(sumo.disjoint("PureSubstance", "Mixture"), "declared disjoint")
        assertFalse(sumo.disjoint("Human", "AutonomousAgent")); assertFalse(sumo.disjoint("Human", "Human"))
    }

    @Test
    fun literalsClassifyIntoTheNumberSubtree() {
        val c42 = sumo.numberClassesOf("42").let { s -> (0 until s.size).map { s[it] }.toSet() }
        assertTrue(setOf("PositiveInteger", "EvenInteger", "Integer", "RealNumber", "Number", "Quantity", "Abstract", "Entity").all { it in c42 }, "$c42")
        assertFalse("NegativeInteger" in c42)
        val m = sumo.numberClassesOf("-3.5").let { s -> (0 until s.size).map { s[it] }.toSet() }
        assertTrue("NegativeRealNumber" in m && "RealNumber" in m); assertFalse("Integer" in m)
        assertEquals(0, sumo.numberClassesOf("Human").size)
        assertTrue(sumo.domainOkLiteral("lessThan", 1, "-3.5"), "(domain lessThan 1 RealNumber)")
        assertTrue(sumo.domainOkLiteral("valence", 2, "3"), "(domain valence 2 PositiveInteger)")
        assertFalse(sumo.domainOkLiteral("valence", 2, "0")); assertFalse(sumo.domainOkLiteral("valence", 2, "2.5"))
        assertFalse(sumo.domainOkLiteral("age", 2, "12"), "age takes a TimeDuration, not a number: ${sumo.domainOf("age", 2)}")
    }

    @Test
    fun buildAndQueryCost() {
        val t0 = System.nanoTime()
        val fresh = SumoClassifier.parse(SumoCorpus.text())
        val buildMs = (System.nanoTime() - t0) / 1_000_000
        val names = (0 until fresh.termCount).map { fresh.terms[it] }
        val t1 = System.nanoTime()
        var hits = 0
        repeat(1_000_000) { i -> if (fresh.isA(names[((i.toLong() * 7919L) % names.size).toInt()], "Physical")) hits++ }
        val queryMs = (System.nanoTime() - t1) / 1_000_000
        println("[SumoClassifier] parse+build ${buildMs}ms; 1M isA in ${queryMs}ms ($hits hits); closureBytes=${fresh.stats["closureBytes"]}")
        assertTrue(buildMs < 15_000, "build ${buildMs}ms"); assertTrue(queryMs < 5_000, "1M isA ${queryMs}ms")
    }
}
