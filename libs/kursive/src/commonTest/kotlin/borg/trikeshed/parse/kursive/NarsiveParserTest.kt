package borg.trikeshed.parse.kursive

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.asString
import borg.trikeshed.lib.get
import borg.trikeshed.lib.*
import borg.trikeshed.lib.toList
import borg.trikeshed.lib.toSeries
import kotlin.test.*
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NarsiveParserTest {
    fun labels(trace: NarsiveTrace): List<String> = trace.toList().map { it.a.asString() }

    @Test
    fun parsesTaskWithBudgetTruthAndRelationshipInSitu() {
        val source = """$0.8;0.5$ (bird --> animal). %1.0;0.9%""".toSeries()
        val parsed = Narsive.parseTask(source)
        assertNotNull(parsed)

        val names = labels(parsed.b)
        assertTrue("narsiveTask" in names)
        assertTrue("narsiveBudget" in names)
        assertTrue("narsiveJudgement" in names)
        assertTrue("narsiveRelationship" in names)
        assertTrue("narsiveCopula" in names)
        assertTrue("narsiveTruth" in names)

        val elements = parsed.b.elements(source).toList()
        assertTrue(elements.any { it.kind == NarsiveElementKind.TASK })
        assertTrue(elements.any { it.kind == NarsiveElementKind.BUDGET })
        assertTrue(elements.any { it.kind == NarsiveElementKind.TRUTH })

        val evidence = parsed.b.evidence(source).toList()
        assertTrue(evidence.any { it.confix == "()" })
        assertTrue(evidence.any { it.digits > 0U })

        val rowVecs = parsed.b.rowVecs(source)
        assertEquals("confix", rowVecs[0][0].b().name)
        assertEquals("deducedType", rowVecs[0][rowVecs[0].size - 1].b().name)
    }

    @Test
    fun parsesQuestionFromSeriesRootWithoutTruthLeak() {
        val source = """(bird --> animal)?""".toSeries()
        val parsed = Narsive.parseSentence(source)
        assertNotNull(parsed)

        val elements = parsed.b.elements(source).toList()
        assertTrue(elements.any { it.kind == NarsiveElementKind.QUESTION })
        assertTrue(elements.any { it.kind == NarsiveElementKind.RELATIONSHIP })
        assertTrue(elements.none { it.kind == NarsiveElementKind.TRUTH })
    }

    @Test
    fun parsesOperationWithVariableArgument() {
        val source = """(^say,hello,?x).""".toSeries()
        val parsed = Narsive.parseSentence(source)
        assertNotNull(parsed)

        val elements = parsed.b.elements(source).toList()
        assertTrue(elements.any { it.kind == NarsiveElementKind.OPERATION })
        assertTrue(elements.any { it.kind == NarsiveElementKind.VARIABLE })
        assertTrue(elements.count { it.kind == NarsiveElementKind.WORD } >= 2)
    }

    @Test
    fun nestedCompoundDoesNotPoisonGrammarWithLeftRecursion() {
        val source = """(&&,(bird --> animal),(animal --> mortal)).""".toSeries()
        val parsed = Narsive.parseSentence(source)
        assertNotNull(parsed)

        val elements = parsed.b.elements(source).toList()
        assertTrue(elements.any { it.kind == NarsiveElementKind.COMPOUND_TERM })
        assertTrue(elements.count { it.kind == NarsiveElementKind.RELATIONSHIP } >= 2)
        assertTrue(labels(parsed.b).count { it == "narsiveTerm" } >= 2)
    }

    @Test
    fun supervisorJobFansOutByParserKey() {
        val source = """$0.8;0.5$ (bird --> animal). %1.0;0.9%""".toSeries()
        val parsed = Narsive.parseTask(source)
        assertNotNull(parsed)

        val job = parsed.supervisorJob(source)
        assertEquals(NarsiveElementKind.TASK, job.root.kind)
        assertEquals(1, job.fanout(NarsiveElementKind.BUDGET).size)
        assertEquals(1, job.fanout(NarsiveElementKind.TRUTH).size)
        assertTrue(job.fanout(NarsiveElementKind.WORD).size >= 2)
        assertTrue(job.concurrentResolutions().size > 0)
    }

    @Test
    fun stringAndSeriesInputsShareTheSameStreamRootShape() {
        val text = """(bird --> animal)?"""
        val fromString = Narsive.parseSentence(text)
        val fromSeries = Narsive.parseSentence(text.toSeries())

        assertNotNull(fromString)
        assertNotNull(fromSeries)
        assertEquals(fromString.a.asString(), fromSeries.a.asString())
        assertEquals(labels(fromString.b), labels(fromSeries.b))
    }

    @Test
    fun bitmaskedOperatorEnumRecoversUnicodeOperatorsFromParsedElements() {
        val source = """(bird → animal). ◷""".toSeries()
        val parsed = Narsive.parseSentence(source)
        assertNotNull(parsed)

        val elements = parsed.b.elements(source)
        val mask = elements.operatorMask()
        val operators = mask.narsiveOperators()

        assertTrue(NarsiveOperator.INHERITANCE in operators)
        assertTrue(NarsiveOperator.FUTURE in operators)

        val renderedCopula = elements.toList().first { it.kind == NarsiveElementKind.COPULA }.render()
        assertEquals("→", renderedCopula.asString())
        assertEquals("-->", NarsiveOperator.INHERITANCE.render(NarsiveRenderMode.ASCII).asString())
    }

    @Test
    fun lineChunkingParsesMultipleTasksIndependently() {
        val text = """
            (bird --> animal).
            (cat --> mammal).
        """.trimIndent()
        val results = Narsive.parseTasks(text)
        assertEquals(2, results.size)
        val first = results[0]
        val second = results[1]
        assertNotNull(first)
        assertNotNull(second)
        val firstLabels = labels(first.b)
        val secondLabels = labels(second.b)
        assertTrue("narsiveRelationship" in firstLabels)
        assertTrue("narsiveRelationship" in secondLabels)
    }

    @Test
    fun lineChunkingParsesMixedTaskTypes() {
        val text = """
            $0.9$ (swan --> bird). %1.0;0.8%
            (xyz ? abc)
        """.trimIndent()
        val results = Narsive.parseTasks(text)
        assertEquals(2, results.size)
        val firstElements = results[0].b.elements(results[0].a).toList()
        assertTrue(firstElements.any { it.kind == NarsiveElementKind.BUDGET })
        assertTrue(firstElements.any { it.kind == NarsiveElementKind.TRUTH })
    }

    @Test
    fun lineChunkingProducesSupervisorJobsWithCorrectFanout() {
        val text = """
            $0.8;0.5$ (bird --> animal). %1.0;0.9%
            (^say,hello,?x).
        """.trimIndent()
        val results = Narsive.parseTasks(text)
        assertEquals(2, results.size)

        // first line supervisor job
        val job1 = results[0].supervisorJob(results[0].a)
        assertEquals(NarsiveElementKind.TASK, job1.root.kind)
        assertEquals(1, job1.fanout(NarsiveElementKind.BUDGET).size)
        assertEquals(1, job1.fanout(NarsiveElementKind.TRUTH).size)
        assertTrue(job1.fanout(NarsiveElementKind.WORD).size >= 2)

        // second line supervisor job
        val job2 = results[1].supervisorJob(results[1].a)
        assertTrue(job2.fanout(NarsiveElementKind.OPERATION).size >= 1)
        assertTrue(job2.fanout(NarsiveElementKind.VARIABLE).size >= 1)
    }

    @Test
    fun colonSyntaxProducesNamedParserTraces() {
        val source = "(bird --> animal)?".toSeries()
        val parsed = Narsive.parseSentence(source)
        assertNotNull(parsed)
        val names = labels(parsed.b)
        // colon-named productions should appear in traces
        assertTrue("narsiveTerm" in names)
        assertTrue("narsiveRelationship" in names)
        assertTrue("narsiveCopula" in names)
        assertTrue("narsiveQuestion" in names || "narsiveSentence" in names)
    }

    @Test
    fun typeEvidenceColumnCountMatchesSchema() {
        val dummyEvidence = borg.trikeshed.TypeEvidence()
        val rowVecFromKursive = dummyEvidence.toKursiveRowVec()

        assertEquals(KURSIVE_EVIDENCE_COLUMNS.size, rowVecFromKursive.size)
    }

    @Test
    fun fuzzTestingParsesGarbageWithoutCrashing() {
        val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 ()[]{}<>.?!@#\$%^&*-=+_\\|;:'\",/~`\n\t"
        for (i in 0 until 100) {
            val length = (1..50).random()
            val text = (1..length).map { chars.random() }.joinToString("")
            try {
                Narsive.parseTask(text)
            } catch (e: Exception) {
                fail("Parser crashed on input: $text. Exception: ${e.message}")
            }
        }
    }

    @Test
    fun benchmarkLargeNALProgramParsing() {
        val line = "(bird --> animal). %1.0;0.9%"
        val text = (1..500).joinToString("\n") { line }
        val time = kotlin.time.measureTime {
            Narsive.parseTasks(text)
        }
        println("Parsed 500 lines in $time")
        assertTrue(time.inWholeMilliseconds >= 0)
    }
}
