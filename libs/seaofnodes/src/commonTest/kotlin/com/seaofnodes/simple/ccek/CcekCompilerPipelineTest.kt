package com.seaofnodes.simple.ccek

import borg.literbike.ccek.core.Context
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TDD tests for CCEK-compiler pipeline.
 * Following TDD: these tests are written FIRST, then the implementation follows.
 */
class CcekCompilerPipelineTest {

    @Test
    fun `compiler context starts empty`() {
        val ctx = compilerContext()
        assertTrue(ctx.isEmpty)
    }

    @Test
    fun `lexer phase produces tokens`() {
        val ctx = compilerContext().flow<LexerElement> { _ ->
            LexerElement(LexerKey, listOf(
                Token(TokenKind.KEYWORD, "val", 0),
                Token(TokenKind.IDENT, "x", 4),
                Token(TokenKind.OP, "=", 6),
                Token(TokenKind.INT, "42", 8),
            ))
        }
        val lexer = ctx.get<LexerElement>()
        assertNotNull(lexer)
        assertEquals(4, lexer.tokens.size)
        assertEquals(TokenKind.KEYWORD, lexer.tokens[0].kind)
    }

    @Test
    fun `parser phase produces DAG nodes`() {
        val ctx = compilerContext()
            .plus(LexerKey, LexerElement(LexerKey, emptyList()))
            .plus(ParserKey, ParserElement(ParserKey, listOf(
                DagNode(0, "Start", listOf(), listOf(), "tuple"),
                DagNode(1, "Ctrl", listOf(0), listOf(), "ctrl"),
                DagNode(2, "Con", listOf(), listOf(1), "int"),
            )))

        val parser = ctx.get<ParserElement>()
        assertNotNull(parser)
        assertEquals(3, parser.dagNodes.size)
        assertEquals("Start", parser.dagNodes[0].label)
    }

    @Test
    fun `ideal phase tracks optimizations`() {
        val ctx = compilerContext()
            .plus(IdealKey, IdealElement(IdealKey, emptyList(), peepholeCount = 5, gvnEliminated = 3))

        val ideal = ctx.get<IdealElement>()
        assertNotNull(ideal)
        assertEquals(5, ideal.peepholeCount)
        assertEquals(3, ideal.gvnEliminated)
    }

    @Test
    fun `schedule phase orders nodes`() {
        val nodes = listOf(
            ScheduledNode(DagNode(0, "Start", emptyList(), emptyList(), "tuple"), 0),
            ScheduledNode(DagNode(1, "Con", emptyList(), listOf(0), "int"), 1),
            ScheduledNode(DagNode(2, "Add", listOf(1, 1), listOf(0), "int"), 2),
        )
        val ctx = compilerContext().plus(ScheduleKey, ScheduleElement(ScheduleKey, nodes))

        val schedule = ctx.get<ScheduleElement>()
        assertNotNull(schedule)
        assertEquals(3, schedule.scheduled.size)
        assertEquals(2, schedule.scheduled.last().scheduleOrder)
    }

    @Test
    fun `codegen phase produces machine code`() {
        val code = byteArrayOf(0x48, 0x89, 0xF8, 0xC3) // x86_64: mov rax, rdi; ret
        val ctx = compilerContext().plus(CodeGenKey, CodeGenElement(CodeGenKey, code))

        val codeGen = ctx.get<CodeGenElement>()
        assertNotNull(codeGen)
        assertEquals(4, codeGen.machineCode.size)
    }

    @Test
    fun `flow transforms context through phases`() {
        val ctx = compilerContext()
            .flow<LexerElement> { _ -> LexerElement(LexerKey, listOf(Token(TokenKind.INT, "42", 0))) }
            .flow<ParserElement> { _ -> ParserElement(ParserKey, listOf(DagNode(0, "Con", emptyList(), emptyList(), "int"))) }

        assertNotNull(ctx.get<LexerElement>())
        assertNotNull(ctx.get<ParserElement>())
        assertNull(ctx.get<IdealElement>())
    }

    @Test
    fun `dag facts encode node definitions`() {
        val fact = DagFact.NodeDef(
            nid = 42,
            label = "Add",
            typeName = "int",
            inputIds = listOf(10, 20),
            controlIds = listOf(5)
        )
        assertEquals(42, fact.nid)
        assertEquals("Add", fact.label)
        assertEquals(2, fact.inputIds.size)
    }

    @Test
    fun `dag facts encode edges`() {
        val dataEdge = DagFact.Edge(10, 20, DagFact.EdgeKind.DATA)
        val ctrlEdge = DagFact.Edge(5, 15, DagFact.EdgeKind.CONTROL)
        val phiEdge = DagFact.Edge(30, 40, DagFact.EdgeKind.PHI)

        assertEquals(DagFact.EdgeKind.DATA, dataEdge.edgeType)
        assertEquals(DagFact.EdgeKind.CONTROL, ctrlEdge.edgeType)
        assertEquals(DagFact.EdgeKind.PHI, phiEdge.edgeType)
    }
}
