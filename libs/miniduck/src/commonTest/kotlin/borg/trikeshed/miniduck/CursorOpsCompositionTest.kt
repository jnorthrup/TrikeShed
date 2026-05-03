package borg.trikeshed.miniduck

import borg.trikeshed.cursor.*
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.isam.meta.IOMemento.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.json.*
import kotlin.test.*
import kotlin.test.Test.*

/**
 * Algebraic composition tests for CursorOps.
 *
 * Verifies: take, drop, where, project, orderBy, columns, minus, then
 * all compose correctly over both SimpleCursor examples and DocRowVec docs
 * built from JSON parsed through Confix.
 */
class CursorOpsCompositionTest {

    /** Example tabular cursor using SimpleCursor over JSON parsed through Confix. */
    private fun sampleCursor(): Cursor = sampleSimpleCursor()

    private fun sampleSimpleCursor(): Cursor = SimpleCursor(
        scalars = listOf(
            ColumnMeta("name", IOMemento.IoString),
            ColumnMeta("age", IOMemento.IoInt),
        ).toSeries(),
        data = docs.size j { i -> parseDocCells(docs[i]) },
    )

    private fun sampleDocCursor(): Cursor = docs.size j { i -> parseDocRow(docs[i]) }

    private fun parseJsonObject(json: String): Map<*, *> {
        val parsed = JsonSupport.parse(json) as? Map<*, *> ?: error("expected JSON object: $json")
        return parsed
    }

    private fun parseDocCells(json: String): Series<Any> {
        val parsed = parseJsonObject(json)
        val name = parsed["name"] ?: error("missing name in $json")
        val age = parsed["age"] ?: error("missing age in $json")
        return listOf(name, age).map { it as Any }.toSeries()
    }

    private fun parseDocRow(json: String): RowVec {
        val parsed = parseJsonObject(json)
        val entries = parsed.entries.toList()
        return DocRowVec(
            keys = entries.map { it.key.toString() },
            cells = entries.map { it.value },
        )
    }

    companion object {
        private val docs = listOf(
            """{"name":"alice","age":30}""",
            """{"name":"bob","age":25}""",
            """{"name":"carol","age":35}""",
            """{"name":"dave","age":25}""",
            """{"name":"eve","age":40}""",
        )
    }

    @Test
    fun takeReturnsFirstN() {
        val c = sampleCursor().take(3)
        assertEquals(3, c.size)
        assertEquals("alice", (c at 0).getValue("name"))
        assertEquals("carol", (c at 2).getValue("name"))
    }

    @Test
    fun takeBeyondSizeClamps() {
        val c = sampleCursor().take(100)
        assertEquals(5, c.size)
    }

    @Test
    fun dropSkipsFirstN() {
        val c = sampleCursor().drop(2)
        assertEquals(3, c.size)
        assertEquals("carol", (c at 0).getValue("name"))
        assertEquals("eve", (c at 2).getValue("name"))
    }

    @Test
    fun dropBeyondSizeReturnsEmpty() {
        val c = sampleCursor().drop(10)
        assertEquals(0, c.size)
    }

    @Test
    fun whereFiltersByEqPredicate() {
        val c = sampleCursor().where(Eq("age", 25))
        assertEquals(2, c.size)
        assertEquals("bob", (c at 0).getValue("name"))
        assertEquals("dave", (c at 1).getValue("name"))
    }

    @Test
    fun projectSelectsColumns() {
        val c = sampleCursor().project("name")
        assertEquals(5, c.size)
        val row0 = c at 0
        assertEquals("alice", row0.getValue("name"))
        assertEquals(listOf("name"), row0.keys.toList())
    }

    @Test
    fun orderByAscending() {
        val c = sampleCursor().orderBy("age")
        assertEquals(5, c.size)
        assertEquals("bob", (c at 0).getValue("name"))   // age 25
        assertEquals("dave", (c at 1).getValue("name"))  // age 25
        assertEquals("eve", (c at 4).getValue("name"))   // age 40
    }

    @Test
    fun orderByDescending() {
        val c = sampleCursor().orderBy(OrderSpec("age", desc = true))
        assertEquals(5, c.size)
        assertEquals("eve", (c at 0).getValue("name"))   // age 40
    }

    @Test
    fun minusDropsColumn() {
        val c = sampleCursor() - "age"
        assertEquals(5, c.size)
        val row0 = c at 0
        assertEquals(listOf("name"), row0.keys.toList())
    }

    @Test
    fun takeThenDrop() {
        val c = sampleCursor().take(4).drop(1)
        assertEquals(3, c.size)
        assertEquals("bob", (c at 0).getValue("name"))
    }

    @Test
    fun whereThenOrderBy() {
        val c = sampleCursor()
            .where(Ge("age", 25))
            .orderBy("name")
        assertEquals(5, c.size)
        assertEquals("alice", (c at 0).getValue("name"))
        assertEquals("eve", (c at 4).getValue("name"))
    }

    @Test
    fun thenChainsTransforms() {
        val c = sampleCursor() then { it.take(3) } then  { it.drop(1) }
        assertEquals(2, c.size)
        assertEquals("bob", (c at 0).getValue("name"))
    }

    @Test
    fun negativeIndexAt() {
        val c = sampleCursor()
        assertEquals("eve", (c at -1).getValue("name"))
        assertEquals("dave", (c at -2).getValue("name"))
    }

    @Test
    fun emptyCursorStaysEmpty() {
        val c = emptyCursor()
        assertEquals(0, c.size)
        val taken = c.take(5)
        assertEquals(0, taken.size)
    }

    @Test
    fun columnsProjection() {
        val c = sampleCursor().columns(0) // project first column "name"
        assertEquals(5, c.size)
        val row0 = c at 0
        assertEquals(1, row0.keys.size)
    }

    @Test
    fun simpleCursorExampleSurfacesParsedValues() {
        val c = sampleSimpleCursor()
        assertEquals(5, c.size)
        assertEquals("alice", (c at 0).getValue("name"))
        assertEquals(25, (c at 1).getValue("age"))
    }

    @Test
    fun docRowVecExampleAlsoComposes() {
        val c = sampleDocCursor().project("name").orderBy("name")
        assertEquals(5, c.size)
        assertEquals(listOf("name"), (c at 0).keys.toList())
        assertEquals("alice", (c at 0).getValue("name"))
        assertEquals("eve", (c at 4).getValue("name"))
    }
}
