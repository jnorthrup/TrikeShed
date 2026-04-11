package com.seaofnodes.simple

import borg.literbike.couchdb.DatabaseInstance
import borg.literbike.couchdb.Document
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import borg.trikeshed.manifold.Atlas
import borg.trikeshed.manifold.Chart
import borg.trikeshed.manifold.Manifold
import borg.trikeshed.manifold.coordinatesOf
import borg.trikeshed.net.channelization.ChannelGraphId
import borg.trikeshed.net.channelization.ChannelGraphState
import borg.trikeshed.net.channelization.SimpleChannelGraph
import borg.trikeshed.net.channelization.WorkerKey
import com.seaofnodes.simple.ccek.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Chapter24Backward_01_SeaOfNodesProjectionTest {
    @Test
    fun `DagNode inputs and controls should be accessible as Series_Int_`() {
        val inputs = listOf(10, 20, 30)
        val controls = listOf(5)
        val dag = DagNode(1, "Add", inputs, controls, "int")

        val inputSeries: Series<Int> = inputs.size j { inputs[it] }
        assertEquals(3, inputSeries.size)
        assertEquals(10, inputSeries[0])
        assertEquals(20, inputSeries[1])

        // RED: DagNode does not expose inputSeries: Series<Int> — only inputs: List<Int>
        val dagInputSeries: Series<Int> = dag.inputSeries
        assertEquals(inputSeries.size, dagInputSeries.size)
        assertEquals(inputSeries[0], dagInputSeries[0])
    }

    @Test
    fun `short-circuit OR IR shape matches ChannelGraph fork join`() {
        val graph =
            SimpleChannelGraph(
                id = ChannelGraphId("fork-join-test"),
                owner = WorkerKey("test-worker"),
                activationRules = emptyList(),
            )
        graph.transitionTo(ChannelGraphState.Active)
        assertNotNull(graph)

        // RED: CompilerShape does not exist — no bridge from compiler IR to ChannelGraph
        val compiled = CompilerShape.compileShortCircuitOr("a++ || b++")
        val forkRules = compiled.toActivationRules()
        assertEquals(2, forkRules.size)
    }

    @Test
    fun `chained comparison IR maps to Manifold coordinate transition`() {
        val chartLow =
            Chart<Int, Int>(
                name = "below_range",
                dimension = 1,
                contains = { p -> p < 0 },
                project = { _ -> coordinatesOf(0) },
                embed = { c -> c[0] },
            )
        val chartMid =
            Chart<Int, Int>(
                name = "in_range",
                dimension = 1,
                contains = { p -> p in 0..4 },
                project = { p -> coordinatesOf(p) },
                embed = { c -> c[0] },
            )
        val chartHigh =
            Chart<Int, Int>(
                name = "above_range",
                dimension = 1,
                contains = { p -> p > 4 },
                project = { _ -> coordinatesOf(4) },
                embed = { c -> c[0] },
            )
        val atlas = Atlas(listOf(chartLow, chartMid, chartHigh).toSeries())
        val manifold = Manifold(atlas)
        assertEquals(3, atlas.size)

        // RED: CompilerShape does not exist — no bridge counting IR comparison nodes
        val irComparisonCount = CompilerShape.countComparisons("0 < arg < arg+1 < 4")
        assertEquals(atlas.size, irComparisonCount)
    }

    @Test
    fun `SCCP constant propagation is WAM unification on constant lattice`() {
        val ctx =
            compilerContext()
                .flow<LexerElement> { _ ->
                    LexerElement(
                        LexerKey,
                        listOf(
                            Token(TokenKind.KEYWORD, "val", 0),
                            Token(TokenKind.IDENT, "x", 4),
                            Token(TokenKind.OP, "=", 6),
                            Token(TokenKind.INT, "1", 8),
                        ),
                    )
                }
        assertNotNull(ctx.get<LexerElement>())

        // RED: WamTableService does not exist anywhere in the codebase
        val wam = WamTableService()
        val binding = wam.query("x")
        assertEquals("1", binding)
    }

    @Test
    fun `CodeGen output bytes stored as CouchDB intermediary document`() {
        val machineCode = byteArrayOf(0x48, 0x89, 0xF8, 0xC3)
        val codeGenEl = CodeGenElement(CodeGenKey, machineCode)
        assertEquals(4, codeGenEl.machineCode.size)

        val db = DatabaseInstance(name = "compiler_intermediary")
        val sourceHash = "sha256:abcdef1234"

        val doc =
            Document(
                id = sourceHash,
                rev = "",
                data =
                    kotlinx.serialization.json.buildJsonObject {
                        put(
                            "machineCode",
                            kotlinx.serialization.json.JsonPrimitive(
                                machineCode.joinToString(",") { it.toString() },
                            ),
                        )
                    },
            )
        val result = db.putDocument(doc)
        assertTrue(result.isSuccess)

        // RED: DatabaseInstance has no queryView method — no MapReduce view for compiler intermediaries
        val viewResult = db.queryView("_design/compiler", "by_source_hash")
        assertEquals(sourceHash, viewResult.rows.first().id)
    }
}

// RED: CompilerShape does not exist — expresses desired consolidated shape
private object CompilerShape {
    fun compileShortCircuitOr(src: String): ForkJoinShape = TODO()

    fun countComparisons(src: String): Int = TODO()
}

// RED: ForkJoinShape does not exist
private data class ForkJoinShape(
    val branchCount: Int,
) {
    fun toActivationRules(): List<Nothing> = TODO()
}

// RED: WamTableService does not exist
private class WamTableService {
    fun query(variable: String): String = TODO()
}
