package borg.trikeshed.polyglot

import borg.trikeshed.common.TypeEvidence
import borg.trikeshed.lib.j
import borg.trikeshed.lib.toSeries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PolyglotSmokeTest {

    /* ─── LangRegistry ──────────────────────────────────────────────── */

    @Test
    fun `LangRegistry is initially empty`() {
        LangRegistry.reset()
        assertTrue(LangRegistry.all().isEmpty())
    }

    @Test
    fun `LangRegistry can register and retrieve`() {
        LangRegistry.reset()
        val fp = LangFingerprint(TypeEvidence(), 100)
        val classifier = LangClassifier { TypeEvidence() }
        LangRegistry.register(LangId.KOTLIN, fp, classifier, listOf(".kt", ".kts"))
        val entry = LangRegistry.byExtension(".kt")
        assertNotNull(entry)
        assertEquals(LangId.KOTLIN, entry.id)
    }

    /* ─── Confidence scoring ────────────────────────────────────────── */

    @Test
    fun `confidence is 1_0 for identical evidence`() {
        val ev = TypeEvidence().apply {
            digits = 10U; alpha = 20U; periods = 1U; special = 3U
        }
        assertEquals(1.0, confidence(ev, ev), 0.001)
    }

    @Test
    fun `confidence degrades with mismatched evidence`() {
        val a = TypeEvidence().apply {
            digits = 100U; alpha = 200U; periods = 10U; special = 50U
            quotes = 5U; backslashes = 20U; whitespaces = 30U
        }
        val b = TypeEvidence().apply {
            // completely different profile
            dquotes = 40U; linefeed = 15U; truefalse = 8U
            // everything else zero
        }
        val conf = confidence(a, b)
        assertTrue(conf < 0.5, "Expected low confidence for heavily mismatched evidence, got $conf")
        assertTrue(conf >= 0.0)
    }

    /* ─── NodeKind taxonomy ─────────────────────────────────────────── */

    @Test
    fun `NodeKind has control flow entries`() {
        val kinds = NodeKind.entries.map { it.name }.toSet()
        for (expected in listOf("RETURN", "IF", "LOOP", "WHILE", "FOR", "MATCH", "TRY", "THROW")) {
            assertTrue(expected in kinds, "Missing NodeKind.$expected")
        }
    }

    @Test
    fun `NodeKind has expression entries`() {
        val kinds = NodeKind.entries.map { it.name }.toSet()
        for (expected in listOf("ASSIGN", "BINARY_OP", "UNARY_OP")) {
            assertTrue(expected in kinds, "Missing NodeKind.$expected")
        }
    }

    /* ─── MLIR taxonomy ─────────────────────────────────────────────── */

    @Test
    fun `MlirDialect has expected entries`() {
        val dialects = MlirDialect.entries.map { it.namespace }.toSet()
        for (ns in listOf("func", "arith", "scf", "cf", "memref", "tensor", "linalg", "affine", "math")) {
            assertTrue(ns in dialects, "Missing MlirDialect.$ns")
        }
    }

    @Test
    fun `MlirOp qualified names are correct`() {
        assertEquals("func.func", FuncOps.func.qualifiedName)
        assertEquals("arith.addf", ArithOps.addf.qualifiedName)
        assertEquals("scf.for", ScfOps.forLoop.qualifiedName)
        assertEquals("memref.alloc", MemrefOps.alloc.qualifiedName)
        assertEquals("linalg.matmul", LinalgOps.matmul.qualifiedName)
    }

    @Test
    fun `nodeToMlir maps FUNCTION to func_dot_func`() {
        val ops = nodeToMlir(NodeKind.FUNCTION)
        assertEquals(1, ops.size)
        assertEquals(FuncOps.func, ops[0])
    }

    @Test
    fun `nodeToMlir maps RETURN to func_dot_return`() {
        val ops = nodeToMlir(NodeKind.RETURN)
        assertEquals(1, ops.size)
        assertEquals(FuncOps.ret, ops[0])
    }

    @Test
    fun `nodeToMlir maps IF to scf_dot_if`() {
        val ops = nodeToMlir(NodeKind.IF)
        assertEquals(1, ops.size)
        assertEquals(ScfOps.ifOp, ops[0])
    }

    @Test
    fun `nodeToMlir maps LOOP to scf_dot_for`() {
        val ops = nodeToMlir(NodeKind.LOOP)
        assertEquals(1, ops.size)
        assertEquals(ScfOps.forLoop, ops[0])
    }

    @Test
    fun `nodeToMlir maps COMMENT to empty`() {
        assertTrue(nodeToMlir(NodeKind.COMMENT).isEmpty())
    }

    @Test
    fun `nodeToMlir maps IMPORT to empty`() {
        assertTrue(nodeToMlir(NodeKind.IMPORT).isEmpty())
    }

    @Test
    fun `nodeToMlir maps BINARY_OP to multiple arith candidates`() {
        val ops = nodeToMlir(NodeKind.BINARY_OP)
        assertTrue(ops.size > 1, "BINARY_OP should map to multiple arith candidates")
        assertTrue(ops.any { it.dialect == MlirDialect.ARITH })
    }

    @Test
    fun `mlirRelevantKinds excludes COMMENT and IMPORT`() {
        assertTrue(NodeKind.COMMENT !in mlirRelevantKinds)
        assertTrue(NodeKind.IMPORT !in mlirRelevantKinds)
        assertTrue(NodeKind.FUNCTION in mlirRelevantKinds)
    }

    /* ─── SourceFragment ────────────────────────────────────────────── */

    @Test
    fun `SourceFragment tree preserves child count`() {
        val root = SourceFragment(
            lang = LangId.KOTLIN,
            span = 0 j 100,
            kind = NodeKind.CLASS,
            name = "Foo",
            evidence = TypeEvidence(),
            children = listOf(
                SourceFragment(LangId.KOTLIN, 10 j 40, NodeKind.FUNCTION, "bar", TypeEvidence()),
                SourceFragment(LangId.KOTLIN, 50 j 90, NodeKind.FIELD, "baz", TypeEvidence()),
            ),
        )
        assertEquals(2, root.children.size)
        assertEquals("Foo", root.name)
    }
}
