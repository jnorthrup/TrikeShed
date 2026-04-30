package borg.trikeshed.polyglot

import borg.trikeshed.collections.s_
import borg.trikeshed.lib.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * RED contract tests for MlirTaxonomy.
 * nodeToMlir is the mapping table — already implemented as spec data.
 * Tests verify every NodeKind maps correctly.
 */
class MlirTaxonomyContractTest {

    /* ─── MlirDialect ────────────────────────────────────────────── */

    @Test fun `MlirDialect has 15 entries`() {
        assertEquals(15, MlirDialect.entries.size)
    }

    @Test fun `MlirDialect namespace is lowercase ascii`() {
        for (d in MlirDialect.entries) {
            assertTrue(d.namespace.all { it in 'a'..'z' }, "namespace must be lowercase: ${d.namespace}")
        }
    }

    @Test fun `MlirDialect op produces qualified name`() {
        assertEquals("func.func", MlirDialect.FUNC.op("func"))
        assertEquals("arith.addf", MlirDialect.ARITH.op("addf"))
        assertEquals("scf.for", MlirDialect.SCF.op("for"))
        assertEquals("linalg.matmul", MlirDialect.LINALG.op("matmul"))
    }

    /* ─── MlirOp ─────────────────────────────────────────────────── */

    @Test fun `MlirOp qualifiedName is dialect_op_dot_name`() {
        val op = MlirOp(MlirDialect.ARITH, "addf", s_["f32", "f32"], s_["f32"])
        assertEquals("arith.addf", op.qualifiedName)
    }

    @Test fun `MlirOp toString includes types`() {
        val op = MlirOp(MlirDialect.MEMREF, "load", s_["memref<?xf32>"], s_["f32"])
        val s = op.toString()
        assertTrue(s.contains("memref.load"))
        assertTrue(s.contains("memref<?xf32>"))
        assertTrue(s.contains("f32"))
    }

    @Test fun `MlirOp with empty operands and results`() {
        val op = MlirOp(MlirDialect.SCF, "yield")
        assertEquals("scf.yield", op.qualifiedName)
        assertTrue(op.operandTypes.isEmpty())
        assertTrue(op.resultTypes.isEmpty())
    }

    /* ─── Canonical op constants exist ───────────────────────────── */

    @Test fun `FuncOps has func call ret constant`() {
        assertNotNull(FuncOps.func)
        assertNotNull(FuncOps.call)
        assertNotNull(FuncOps.ret)
        assertEquals("func.func", FuncOps.func.qualifiedName)
        assertEquals("func.call", FuncOps.call.qualifiedName)
        assertEquals("func.return", FuncOps.ret.qualifiedName)
    }

    @Test fun `ArithOps has all arithmetic ops`() {
        val names = listOf(ArithOps.constant, ArithOps.addi, ArithOps.addf, ArithOps.subi,
            ArithOps.subf, ArithOps.muli, ArithOps.mulf, ArithOps.divsi, ArithOps.divf,
            ArithOps.negf, ArithOps.cmpi, ArithOps.cmpf, ArithOps.select,
            ArithOps.andi, ArithOps.ori, ArithOps.xori, ArithOps.sitofp, ArithOps.fptosi)
        for (op in names) {
            assertEquals(MlirDialect.ARITH, op.dialect, "op $op not in arith dialect")
        }
    }

    @Test fun `ScfOps has for while if yield`() {
        assertEquals("scf.for", ScfOps.forLoop.qualifiedName)
        assertEquals("scf.while", ScfOps.whileLoop.qualifiedName)
        assertEquals("scf.if", ScfOps.ifOp.qualifiedName)
        assertEquals("scf.yield", ScfOps.yield.qualifiedName)
    }

    @Test fun `MemrefOps has alloc dealloc load store`() {
        assertEquals("memref.alloc", MemrefOps.alloc.qualifiedName)
        assertEquals("memref.dealloc", MemrefOps.dealloc.qualifiedName)
        assertEquals("memref.load", MemrefOps.load.qualifiedName)
        assertEquals("memref.store", MemrefOps.store.qualifiedName)
    }

    @Test fun `LinalgOps has matmul fill generic batch_matmul`() {
        assertEquals("linalg.matmul", LinalgOps.matmul.qualifiedName)
        assertEquals("linalg.fill", LinalgOps.fill.qualifiedName)
        assertEquals("linalg.generic", LinalgOps.generic.qualifiedName)
        assertEquals("linalg.batch_matmul", LinalgOps.batchMatmul.qualifiedName)
    }

    @Test fun `TensorOps has empty extract insert`() {
        assertEquals("tensor.empty", TensorOps.empty.qualifiedName)
        assertEquals("tensor.extract", TensorOps.extract.qualifiedName)
        assertEquals("tensor.insert", TensorOps.insert.qualifiedName)
    }

    @Test fun `AffineOps has for load store yield`() {
        assertEquals("affine.for", AffineOps.forLoop.qualifiedName)
        assertEquals("affine.load", AffineOps.load.qualifiedName)
        assertEquals("affine.store", AffineOps.store.qualifiedName)
        assertEquals("affine.yield", AffineOps.yield.qualifiedName)
    }

    @Test fun `MathOps has relu sigmoid sqrt exp log`() {
        assertEquals("math.relu", MathOps.relu.qualifiedName)
        assertEquals("math.sigmoid", MathOps.sigmoid.qualifiedName)
        assertEquals("math.sqrt", MathOps.sqrt.qualifiedName)
        assertEquals("math.exp", MathOps.exp.qualifiedName)
        assertEquals("math.log", MathOps.log.qualifiedName)
    }

    @Test fun `CfOps has br cond_br`() {
        assertEquals("cf.br", CfOps.br.qualifiedName)
        assertEquals("cf.cond_br", CfOps.condBr.qualifiedName)
    }

    /* ─── nodeToMlir — structural ────────────────────────────────── */

    @Test fun `MODULE maps to func_dot_func`() {
        val ops = nodeToMlir(NodeKind.MODULE)
        assertEquals(1, ops.size)
        assertEquals(FuncOps.func, ops[0])
    }

    @Test fun `NAMESPACE maps to empty`() {
        assertTrue(nodeToMlir(NodeKind.NAMESPACE).isEmpty())
    }

    @Test fun `CLASS STRUCT INTERFACE TRAIT IMPL ENUM map to empty`() {
        for (kind in listOf(NodeKind.CLASS, NodeKind.STRUCT, NodeKind.INTERFACE, NodeKind.TRAIT, NodeKind.IMPL, NodeKind.ENUM)) {
            assertTrue(nodeToMlir(kind).isEmpty(), "$kind should map to empty (lowered away)")
        }
    }

    /* ─── nodeToMlir — callables ─────────────────────────────────── */

    @Test fun `FUNCTION maps to func_dot_func`() {
        val ops = nodeToMlir(NodeKind.FUNCTION)
        assertEquals(1, ops.size)
        assertEquals(FuncOps.func, ops[0])
    }

    @Test fun `METHOD maps to func_dot_func`() {
        val ops = nodeToMlir(NodeKind.METHOD)
        assertEquals(1, ops.size)
        assertEquals(FuncOps.func, ops[0])
    }

    /* ─── nodeToMlir — memory ────────────────────────────────────── */

    @Test fun `VARIABLE maps to memref_alloca and memref_store`() {
        val ops = nodeToMlir(NodeKind.VARIABLE)
        assertEquals(2, ops.size)
        assertEquals(MemrefOps.alloca, ops[0])
        assertEquals(MemrefOps.store, ops[1])
    }

    @Test fun `PARAMETER maps to empty becomes block argument`() {
        assertTrue(nodeToMlir(NodeKind.PARAMETER).isEmpty())
    }

    @Test fun `FIELD maps to memref_load and memref_store`() {
        val ops = nodeToMlir(NodeKind.FIELD)
        assertEquals(2, ops.size)
        assertEquals(MemrefOps.load, ops[0])
        assertEquals(MemrefOps.store, ops[1])
    }

    @Test fun `ASSIGN maps to memref_store`() {
        val ops = nodeToMlir(NodeKind.ASSIGN)
        assertEquals(1, ops.size)
        assertEquals(MemrefOps.store, ops[0])
    }

    /* ─── nodeToMlir — control flow ──────────────────────────────── */

    @Test fun `BLOCK maps to empty becomes MLIR block region`() {
        assertTrue(nodeToMlir(NodeKind.BLOCK).isEmpty())
    }

    @Test fun `RETURN maps to func_dot_return`() {
        val ops = nodeToMlir(NodeKind.RETURN)
        assertEquals(1, ops.size)
        assertEquals(FuncOps.ret, ops[0])
    }

    @Test fun `IF maps to scf_dot_if`() {
        val ops = nodeToMlir(NodeKind.IF)
        assertEquals(1, ops.size)
        assertEquals(ScfOps.ifOp, ops[0])
    }

    @Test fun `LOOP maps to scf_dot_for`() {
        val ops = nodeToMlir(NodeKind.LOOP)
        assertEquals(1, ops.size)
        assertEquals(ScfOps.forLoop, ops[0])
    }

    @Test fun `WHILE maps to scf_dot_while`() {
        val ops = nodeToMlir(NodeKind.WHILE)
        assertEquals(1, ops.size)
        assertEquals(ScfOps.whileLoop, ops[0])
    }

    @Test fun `FOR maps to scf_for and affine_for candidates`() {
        val ops = nodeToMlir(NodeKind.FOR)
        assertEquals(2, ops.size)
        assertTrue(ops.contains(ScfOps.forLoop))
        assertTrue(ops.contains(AffineOps.forLoop))
    }

    @Test fun `MATCH maps to cf_switch and scf_if`() {
        val ops = nodeToMlir(NodeKind.MATCH)
        assertEquals(2, ops.size)
        assertTrue(ops.contains(CfOps.switchOp))
        assertTrue(ops.contains(ScfOps.ifOp))
    }

    @Test fun `TRY and THROW map to empty exception handling deferred`() {
        assertTrue(nodeToMlir(NodeKind.TRY).isEmpty())
        assertTrue(nodeToMlir(NodeKind.THROW).isEmpty())
    }

    /* ─── nodeToMlir — expressions ───────────────────────────────── */

    @Test fun `STATEMENT maps to empty sequence ordering`() {
        assertTrue(nodeToMlir(NodeKind.STATEMENT).isEmpty())
    }

    @Test fun `EXPRESSION maps to empty resolved by evidence`() {
        assertTrue(nodeToMlir(NodeKind.EXPRESSION).isEmpty())
    }

    @Test fun `CALL maps to func_dot_call`() {
        val ops = nodeToMlir(NodeKind.CALL)
        assertEquals(1, ops.size)
        assertEquals(FuncOps.call, ops[0])
    }

    @Test fun `LITERAL maps to arith_dot_constant`() {
        val ops = nodeToMlir(NodeKind.LITERAL)
        assertEquals(1, ops.size)
        assertEquals(ArithOps.constant, ops[0])
    }

    @Test fun `BINARY_OP maps to multiple arith candidates`() {
        val ops = nodeToMlir(NodeKind.BINARY_OP)
        assertTrue(ops.size >= 10, "BINARY_OP should map to many arith candidates, got ${ops.size}")
        assertTrue(ops.view.all { it.dialect == MlirDialect.ARITH }, "all BINARY_OP candidates must be arith dialect")
        assertTrue(ops.view.contains(ArithOps.addf))
        assertTrue(ops.contains(ArithOps.mulf))
        assertTrue(ops.contains(ArithOps.cmpf))
    }

    @Test fun `UNARY_OP maps to arith_negf and math candidates`() {
        val ops = nodeToMlir(NodeKind.UNARY_OP)
        assertTrue(ops.size >= 2)
        assertTrue(ops.contains(ArithOps.negf))
        assertTrue(ops.contains(MathOps.absf) || ops.contains(MathOps.sqrt))
    }

    /* ─── nodeToMlir — discarded ─────────────────────────────────── */

    @Test fun `TYPE_ANNOTATION and TYPE_DECL map to empty`() {
        assertTrue(nodeToMlir(NodeKind.TYPE_ANNOTATION).isEmpty())
        assertTrue(nodeToMlir(NodeKind.TYPE_DECL).isEmpty())
    }

    @Test fun `IMPORT EXPORT COMMENT UNKNOWN map to empty`() {
        for (kind in listOf(NodeKind.IMPORT, NodeKind.EXPORT, NodeKind.COMMENT, NodeKind.UNKNOWN)) {
            assertTrue(nodeToMlir(kind).isEmpty(), "$kind should map to empty")
        }
    }

    /* ─── mlirRelevantKinds ──────────────────────────────────────── */

    @Test fun `mlirRelevantKinds includes FUNCTION RETURN IF LOOP CALL LITERAL`() {
        for (kind in listOf(NodeKind.FUNCTION, NodeKind.RETURN, NodeKind.IF,
            NodeKind.LOOP, NodeKind.CALL, NodeKind.LITERAL, NodeKind.ASSIGN, NodeKind.VARIABLE))
        {
            assertTrue(mlirRelevantKinds.view.contains(kind), "$kind should be mlir-relevant")
        }
    }

    @Test fun `mlirRelevantKinds excludes COMMENT IMPORT UNKNOWN`() {
        for (kind in listOf(NodeKind.COMMENT, NodeKind.IMPORT, NodeKind.UNKNOWN,
            NodeKind.TYPE_ANNOTATION, NodeKind.TYPE_DECL))
        {
            assertFalse(mlirRelevantKinds.view.contains(kind), "$kind should NOT be mlir-relevant")
        }
    }

    @Test fun `mlirRelevantKinds size matches count of non-empty nodeToMlir`() {
        val expected = NodeKind.entries.count { nodeToMlir(it).isNotEmpty() }
        assertEquals(expected, mlirRelevantKinds.size)
    }

    /* ─── All 35 NodeKinds are covered by nodeToMlir ─────────────── */

    @Test fun `every NodeKind has a nodeToMlir entry no exception`() {
        for (kind in NodeKind.entries) {
            val result: Any = try { nodeToMlir(kind) } catch (e: Exception) { e }
            assertTrue(result is Join<*, *>, "nodeToMlir($kind) threw or returned non-Join: $result")
        }
    }

    @Test fun `nodeToMlir never returns null`() {
        for (kind in NodeKind.entries) {
            assertNotNull(nodeToMlir(kind), "nodeToMlir($kind) returned null")
        }
    }
}
