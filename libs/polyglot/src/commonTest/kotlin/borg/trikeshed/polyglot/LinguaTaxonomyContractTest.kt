package borg.trikeshed.polyglot

import borg.trikeshed.common.TypeEvidence
import borg.trikeshed.cursor.name
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.toSeries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * RED contract tests for LinguaTaxonomy.
 * Every method that is TODO must have a contract test here.
 * Tests that hit TODO stubs will throw NotImplementedError → RED.
 */
class LinguaTaxonomyContractTest {

    /* ─── LangId ────────────────────────────────────────────────── */

    @Test fun `LangId has 13 entries`() {
        assertEquals(13, LangId.entries.size)
    }

    @Test fun `LangId of resolves by label`() {
        assertEquals(LangId.RUST, LangId.of("rust"))
    }

    @Test fun `LangId of returns null for unknown label`() {
        assertEquals(null, LangId.of("brainfuck"))
    }

    @Test fun `LangId labels are lowercase ascii`() {
        for (entry in LangId.entries) {
            assertTrue(entry.label.all { it in 'a'..'z' }, "label must be lowercase: ${entry.label}")
        }
    }

    /* ─── LangFingerprint ────────────────────────────────────────── */

    @Test fun `LangFingerprint holds evidence and corpusLength`() {
        val ev = TypeEvidence().apply { digits = 5U }
        val fp = LangFingerprint(ev, 42)
        assertEquals(5U.toInt(), fp.evidence.digits.toInt())
        assertEquals(42, fp.corpusLength)
    }

    @Test fun `LangFingerprint toRowVec projects corpusLength`() {
        val fp = LangFingerprint(TypeEvidence(), 42)
        val row = fp.toRowVec()
        assertEquals(42, row[row.size - 1].a)
    }

    @Test fun `LangFingerprint LANG_FP_COLUMNS has 18 columns`() {
        assertEquals(18, LangFingerprint.LANG_FP_COLUMNS.size)
    }

    @Test fun `LangFingerprint columns include corpusLength as last column`() {
        val last = LangFingerprint.LANG_FP_COLUMNS.last()
        assertEquals("corpusLength", last.name)
    }

    /* ─── LangClassifier ─────────────────────────────────────────── */

    @Test fun `LangClassifier is a fun interface`() {
        val c = LangClassifier { TypeEvidence().apply { alpha = 99U } }
        val result = c.classify("hello".toSeries())
        assertEquals(99, result.alpha.toInt())
    }

    @Test fun `LangClassifier returns fresh TypeEvidence per call`() {
        var calls = 0
        val c = LangClassifier { calls++; TypeEvidence() }
        c.classify("a".toSeries())
        c.classify("b".toSeries())
        assertEquals(2, calls)
    }

    /* ─── ClassificationResult ───────────────────────────────────── */

    @Test fun `ClassificationResult holds lang evidence confidence`() {
        val ev = TypeEvidence()
        val r = ClassificationResult(LangId.GO, ev, 0.75)
        assertEquals(LangId.GO, r.lang)
        assertEquals(0.75, r.confidence)
    }

    /* ─── confidence() ───────────────────────────────────────────── */

    @Test fun `confidence 1.0 for identical evidence`() {
        assertEquals(1.0, confidence(TypeEvidence(), TypeEvidence()))
    }

    /* ─── LangEntry ──────────────────────────────────────────────── */

    @Test fun `LangEntry holds all registration fields`() {
        val fp = LangFingerprint(TypeEvidence(), 100)
        val c = LangClassifier { TypeEvidence() }
        val entry = LangEntry(LangId.KOTLIN, fp, c, listOf(".kt", ".kts"), null)
        assertEquals(LangId.KOTLIN, entry.id)
        assertEquals(listOf(".kt", ".kts"), entry.extensions)
        assertEquals(null, entry.shebang)
    }

    @Test fun `LangEntry with shebang`() {
        val entry = LangEntry(
            LangId.PYTHON, LangFingerprint(TypeEvidence(), 50),
            LangClassifier { TypeEvidence() },
            listOf(".py"), "#!/usr/bin/env python3"
        )
        assertEquals("#!/usr/bin/env python3", entry.shebang)
    }

    @Test fun `LangEntry classify returns ClassificationResult`() {
        val entry = LangEntry(
            LangId.RUST, LangFingerprint(TypeEvidence(), 10),
            LangClassifier { TypeEvidence() },
            listOf(".rs"), null
        )
        val r = entry.classify("fn main() {}".toSeries())
        assertEquals(LangId.RUST, r.lang)
        assertTrue(r.confidence >= 0.0)
        assertTrue(r.confidence <= 1.0)
    }

    /* ─── LangRegistry ───────────────────────────────────────────── */

    @Test fun `LangRegistry is initially empty after reset`() {
        LangRegistry.reset()
        assertTrue(LangRegistry.all().isEmpty())
    }

    @Test fun `LangRegistry register returns entry`() {
        LangRegistry.reset()
        val entry = LangRegistry.register(
            LangId.JAVA, LangFingerprint(TypeEvidence(), 50),
            LangClassifier { TypeEvidence() },
            listOf(".java")
        )
        assertEquals(LangId.JAVA, entry.id)
    }

    @Test fun `LangRegistry all returns registered entries`() {
        LangRegistry.reset()
        LangRegistry.register(LangId.C, LangFingerprint(TypeEvidence(), 10), LangClassifier { TypeEvidence() }, listOf(".c"))
        LangRegistry.register(LangId.CPP, LangFingerprint(TypeEvidence(), 20), LangClassifier { TypeEvidence() }, listOf(".cpp"))
        assertEquals(2, LangRegistry.all().size)
    }

    @Test fun `LangRegistry byId finds registered language`() {
        LangRegistry.reset()
        LangRegistry.register(LangId.ZIG, LangFingerprint(TypeEvidence(), 30), LangClassifier { TypeEvidence() }, listOf(".zig"))
        assertNotNull(LangRegistry.byId(LangId.ZIG))
    }

    @Test fun `LangRegistry byId returns null for unregistered`() {
        LangRegistry.reset()
        assertEquals(null, LangRegistry.byId(LangId.SCALA))
    }

    @Test fun `LangRegistry byExtension finds by file extension`() {
        LangRegistry.reset()
        LangRegistry.register(LangId.GO, LangFingerprint(TypeEvidence(), 40), LangClassifier { TypeEvidence() }, listOf(".go"))
        assertNotNull(LangRegistry.byExtension(".go"))
    }

    @Test fun `LangRegistry byExtension returns null for unknown extension`() {
        LangRegistry.reset()
        assertEquals(null, LangRegistry.byExtension(".xyz"))
    }

    @Test fun `LangRegistry series returns Series of LangEntry`() {
        LangRegistry.reset()
        LangRegistry.register(LangId.HASKELL, LangFingerprint(TypeEvidence(), 60), LangClassifier { TypeEvidence() }, listOf(".hs"))
        val s = LangRegistry.series()
        assertEquals(1, s.a)
    }

    @Test fun `LangRegistry classifyAll returns results list`() {
        LangRegistry.reset()
        LangRegistry.register(LangId.HASKELL, LangFingerprint(TypeEvidence(), 60), LangClassifier { TypeEvidence() }, listOf(".hs"))
        val results = LangRegistry.classifyAll("source text".toSeries())
        assertEquals(1, results.size)
    }

    @Test fun `LangRegistry bestMatch returns top result`() {
        LangRegistry.reset()
        LangRegistry.register(
            LangId.PYTHON, LangFingerprint(TypeEvidence().apply { whitespaces = 100U }, 300),
            LangClassifier { TypeEvidence().apply { whitespaces = 90U } },
            listOf(".py")
        )
        LangRegistry.register(
            LangId.JAVASCRIPT, LangFingerprint(TypeEvidence().apply { dquotes = 50U }, 100),
            LangClassifier { TypeEvidence().apply { dquotes = 5U } },
            listOf(".js")
        )
        val best = LangRegistry.bestMatch("def foo():".toSeries())
        assertNotNull(best)
        assertEquals(LangId.PYTHON, best?.lang)
    }

    /* ─── Use case: full registration → classification flow ──────── */

    @Test fun `usecase register kotlin and rust classifyAll returns sorted results RED`() {
        LangRegistry.reset()
        LangRegistry.register(
            LangId.KOTLIN, LangFingerprint(TypeEvidence().apply { alpha = 80U }, 200),
            LangClassifier { TypeEvidence().apply { alpha = 75U } },
            listOf(".kt", ".kts")
        )
        LangRegistry.register(
            LangId.RUST, LangFingerprint(TypeEvidence().apply { special = 40U }, 150),
            LangClassifier { TypeEvidence().apply { special = 38U } },
            listOf(".rs")
        )
        val results = LangRegistry.classifyAll("fn main() {}".toSeries())
        assertEquals(2, results.size)
        // Kotlin classifier returns alpha=75, which is closer to Kotlin fingerprint alpha=80
        // than Rust fingerprint alpha=0 → higher confidence → first
        assertEquals(LangId.KOTLIN, results[0].lang)
        assertTrue(results[0].confidence >= 0.0)
        assertTrue(results[0].confidence <= 1.0)
    }

    @Test fun `usecase bestMatch returns top result`() {
        LangRegistry.reset()
        LangRegistry.register(
            LangId.PYTHON, LangFingerprint(TypeEvidence().apply { whitespaces = 100U }, 300),
            LangClassifier { TypeEvidence().apply { whitespaces = 90U } },
            listOf(".py")
        )
        LangRegistry.register(
            LangId.JAVASCRIPT, LangFingerprint(TypeEvidence().apply { dquotes = 50U }, 100),
            LangClassifier { TypeEvidence().apply { dquotes = 5U } },
            listOf(".js")
        )
        // RED
        assertFailsWith<NotImplementedError> {
            val best = LangRegistry.bestMatch("def foo():".toSeries())
            assertNotNull(best)
            assertEquals(LangId.PYTHON, best.lang)
        }
    }
}
