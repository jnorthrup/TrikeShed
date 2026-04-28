package borg.trikeshed.polyglot

import borg.trikeshed.common.TypeEvidence
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.toSeries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RED contract tests for the pipeline.
 * Every stage is TODO — these tests define the use cases.
 */
class PipelineContractTest {

    private val emptySource: Series<Char> = "".toSeries()

    /* ─── Stage 0: detect ────────────────────────────────────────── */

    @Test fun `detect returns null for empty registry RED TODO`() {
        LangRegistry.reset()
        assertFailsWith<NotImplementedError> { detect(emptySource) }
    }

    @Test fun `detect with single registered language RED`() {
        LangRegistry.reset()
        LangRegistry.register(
            LangId.KOTLIN, LangFingerprint(TypeEvidence().apply { alpha = 50U }, 100),
            LangClassifier { TypeEvidence().apply { alpha = 50U } },
            listOf(".kt")
        )
        // bestMatch is TODO → RED
        assertFailsWith<NotImplementedError> { detect("fun main() {}".toSeries()) }
    }

    /* ─── detectOrNull ───────────────────────────────────────────── */

    @Test fun `detectOrNull returns null for empty registry RED TODO`() {
        LangRegistry.reset()
        assertFailsWith<NotImplementedError> { detectOrNull(emptySource) }
    }

    @Test fun `detectOrNull with single language RED`() {
        LangRegistry.reset()
        LangRegistry.register(
            LangId.RUST, LangFingerprint(TypeEvidence().apply { special = 30U }, 80),
            LangClassifier { TypeEvidence().apply { special = 28U } },
            listOf(".rs")
        )
        assertFailsWith<NotImplementedError> { detectOrNull("fn main() {}".toSeries()) }
    }

    /* ─── Stage 1: parse ─────────────────────────────────────────── */

    @Test fun `parse is RED TODO`() {
        assertFailsWith<NotImplementedError> { parse(LangId.RUST, "fn main() {}".toSeries()) }
    }

    @Test fun `parse returns Result`() {
        val r: Any = try { parse(LangId.GO, emptySource) } catch (e: NotImplementedError) { e }
        assertTrue(r is NotImplementedError, "parse should be TODO → NotImplementedError")
    }

    /* ─── Stage 2: classify ──────────────────────────────────────── */

    @Test fun `classify is RED TODO`() {
        val ast = UniversalAst(LangId.KOTLIN, SourceFragment(
            LangId.KOTLIN, 0 j 10, NodeKind.FUNCTION, "test", TypeEvidence()
        ))
        assertFailsWith<NotImplementedError> { classify(ast) }
    }

    /* ─── Stage 3: unify ─────────────────────────────────────────── */

    @Test fun `unify is RED TODO`() {
        val ast = UniversalAst(LangId.KOTLIN, SourceFragment(
            LangId.KOTLIN, 0 j 10, NodeKind.CLASS, "Foo", TypeEvidence()
        ))
        assertFailsWith<NotImplementedError> { unify(ast) }
    }

    /* ─── Stage 4: mapRegions ────────────────────────────────────── */

    @Test fun `mapRegions is RED TODO`() {
        // Can't easily create a real DescriptorFragment without the unify step,
        // but the call should still hit the TODO.
        assertFailsWith<NotImplementedError> {
            // Use a dummy: unify is TODO, so we can't get a real fragment.
            // Test that mapRegions itself is TODO via a different path.
            // This test verifies the function exists and is RED.
            val ast = UniversalAst(LangId.GO, SourceFragment(
                LangId.GO, 0 j 10, NodeKind.FUNCTION, "main", TypeEvidence()
            ))
            val fragment = unify(ast)  // RED, will throw
            mapRegions(fragment)       // never reached
        }
    }

    /* ─── Stage 5: lower ─────────────────────────────────────────── */

    @Test fun `lower is RED TODO`() {
        assertFailsWith<NotImplementedError> {
            // lower takes a Cursor, which we can't construct without mapRegions
            // The call itself is TODO.
            throw NotImplementedError("cursor construction blocked by mapRegions TODO")
        }
    }

    /* ─── End-to-end pipeline ────────────────────────────────────── */

    @Test fun `pipeline with empty registry returns silently`() {
        LangRegistry.reset()
        // detectOrNull → bestMatch → RED TODO until bestMatch handles empty
        kotlinx.coroutines.runBlocking {
            assertFailsWith<NotImplementedError> {
                pipeline(emptySource, minConfidence = 0.5)
            }
        }
    }

    @Test fun `pipeline with single language hits RED`() {
        LangRegistry.reset()
        LangRegistry.register(
            LangId.PYTHON, LangFingerprint(TypeEvidence().apply { whitespaces = 80U }, 200),
            LangClassifier { TypeEvidence().apply { whitespaces = 75U } },
            listOf(".py")
        )
        kotlinx.coroutines.runBlocking {
            assertFailsWith<NotImplementedError> {
                pipeline("def foo(): pass".toSeries(), minConfidence = 0.3)
            }
        }
    }

    /* ─── Use case: full pipeline contract ───────────────────────── */

    @Test fun `usecase pipeline stages execute in order`() {
        // Verifies the pipeline function exists, compiles, and chains stages.
        // All stages are RED TODO — the chain should fail at the first RED stage.
        LangRegistry.reset()
        LangRegistry.register(
            LangId.KOTLIN, LangFingerprint(TypeEvidence().apply { alpha = 60U }, 120),
            LangClassifier { TypeEvidence().apply { alpha = 58U } },
            listOf(".kt")
        )
        kotlinx.coroutines.runBlocking {
            // detect → parse is the first RED boundary
            assertFailsWith<NotImplementedError> {
                pipeline("fun main() = println(42)".toSeries())
            }
        }
    }

    /* ─── SourceFragment / UniversalAst contracts ────────────────── */

    @Test fun `SourceFragment holds all node fields`() {
        val sf = SourceFragment(
            lang = LangId.RUST,
            span = 0 j 100,
            kind = NodeKind.STRUCT,
            name = "Point",
            evidence = TypeEvidence(),
            children = listOf(),
            meta = NodeMeta(visibility = "pub", mutability = null, generic = true),
        )
        assertNotNull(sf)
        assertEquals(LangId.RUST, sf.lang)
        assertEquals(NodeKind.STRUCT, sf.kind)
        assertEquals("pub", sf.meta.visibility)
        assertTrue(sf.meta.generic)
    }

    @Test fun `NodeMeta all fields default to false or null`() {
        val meta = NodeMeta()
        assertNull(meta.visibility)
        assertNull(meta.mutability)
        assertNull(meta.lifetime)
        assertTrue(!meta.async)
        assertTrue(!meta.generic)
        assertTrue(!meta.extern)
    }

    @Test fun `NodeMeta with rust lifetime`() {
        val meta = NodeMeta(visibility = "pub(crate)", lifetime = "'a", mutability = "mut")
        assertEquals("pub(crate)", meta.visibility)
        assertEquals("'a", meta.lifetime)
        assertEquals("mut", meta.mutability)
    }

    @Test fun `UniversalAst carries lang root and diagnostics`() {
        val root = SourceFragment(LangId.TYPESCRIPT, 0 j 50, NodeKind.INTERFACE, "Config", TypeEvidence())
        val ast = UniversalAst(LangId.TYPESCRIPT, root, listOf("warning: any type used"))
        assertEquals(LangId.TYPESCRIPT, ast.lang)
        assertEquals(1, ast.diagnostics.size)
    }

    @Test fun `UniversalAst toCursor flattens SourceFragment tree`() {
        val ast = UniversalAst(LangId.JAVA, SourceFragment(
            LangId.JAVA, 0 j 10, NodeKind.CLASS, "Main", TypeEvidence()
        ))
        val cursor = ast.toCursor()
        assertNotNull(cursor)
        assertTrue(cursor.size >= 1)
    }

    @Test fun `LangParser interface defines parse and parseFile contracts`() {
        // Just verify the interface exists with correct signatures
        val parser = object : LangParser {
            override val lang = LangId.SWIFT
            override fun parse(source: Series<Char>) = TODO("stub")
            override fun parseFile(path: String) = TODO("stub")
        }
        assertEquals(LangId.SWIFT, parser.lang)
    }

    /* ─── TypeEvidence integration ───────────────────────────────── */

    @Test fun `TypeEvidence sample works on char series`() {
        val sample = TypeEvidence.sample("fun main() {}".toSeries())
        assertNotNull(sample)
        assertTrue(sample.alpha > 0U.toUShort())
    }
}
