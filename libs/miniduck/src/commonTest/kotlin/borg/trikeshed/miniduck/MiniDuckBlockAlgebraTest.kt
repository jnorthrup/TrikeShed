package borg.trikeshed.miniduck

import borg.trikeshed.lib.Twin
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * TDD spec for MiniDuck RowVec family block algebra and cursor ops.
 *
 * RowVec families:
 *   DocRowVec  — document rows with named-field access
 *   ViewRowVec — CouchDB map-reduce view output
 *   BlobRowVec — binary blob (lazy child = parsed form)
 *   JsonRowVec — JSON node (lazy children = array/object members)
 *   YamlRowVec — YAML node (lazy children = mapping/sequence members)
 *   BlockRowVec — composite block holding multiple children
 *
 * Block contract:
 *   - MUTABLE → SEAMED (immutable) via seal()
 *   - isShell = size==0 (zero-length rows can carry deferred children)
 *   - append() blocked after seal()
 */
class MiniDuckBlockAlgebraTest {

    // ── BlockRowVec seal state machine ───────────────────────────────────────

    @Test
    fun `BlockRowVec mutable starts MUTABLE`() {
        val block = BlockRowVec.mutable()
        assertEquals(BlockRowVec.State.MUTABLE, block.state)
    }

    @Test
    fun `BlockRowVec seal transitions MUTABLE to SEALED`() {
        val block = BlockRowVec.mutable()
        val sealed = block.seal()
        assertEquals(BlockRowVec.State.SEALED, sealed.state)
    }

    @Test
    fun `BlockRowVec seal is idempotent`() {
        val block = BlockRowVec.mutable()
        block.seal()
        block.seal() // must not throw
        assertEquals(BlockRowVec.State.SEALED, block.state)
    }

    @Test
    fun `BlockRowVec append blocked after seal`() {
        val block = BlockRowVec.mutable()
        block.seal()
        val thrown = runCatching {
            block.append(DocRowVec(listOf("x"), listOf(1)))
        }
        assertTrue(thrown.isFailure)
    }

    @Test
    fun `BlockRowVec seal returns same instance`() {
        val block = BlockRowVec.mutable()
        assertSame(block, block.seal())
    }

    @Test
    fun `BlockRowVec rowCount increments on append`() {
        val block = BlockRowVec.mutable()
        repeat(3) { i ->
            block.append(DocRowVec(listOf("i"), listOf(i)))
        }
        assertEquals(3, block.rowCount)
    }

    // ── RowVec families: isShell ──────────────────────────────────────────────

    @Test
    fun `DocRowVec with fields is not shell`() {
        val doc = DocRowVec(listOf("x"), listOf(1))
        assertFalse(doc.isShell)
    }

    @Test
    fun `DocRowVec zero-length with child is shell`() {
        val nested = DocRowVec(listOf("b"), listOf(2))
        val shell = DocRowVec(emptyList(), emptyList(), child = 1 j { nested })
        assertTrue(shell.isShell)
        assertEquals(0, shell.size)
    }

    @Test
    fun `BlobRowVec is always shell`() {
        val blob = BlobRowVec(byteArrayOf(1, 2, 3))
        assertTrue(blob.isShell)
    }

    @Test
    fun `ViewRowVec with docLoader is not shell until accessed`() {
        val doc = DocRowVec(listOf("body"), listOf("hello"))
        val view = ViewRowVec("doc1", "k", "v") { doc }
        // Not a shell — has a docLoader which is the lazy child factory
        assertFalse(view.isShell)
    }

    // ── DocRowVec field access ─────────────────────────────────────────────────

    @Test
    fun `DocRowVec named access returns correct value`() {
        val doc = DocRowVec(listOf("name", "age", "active"), listOf("Alice", 30, true))
        assertEquals("Alice", doc["name"])
        assertEquals(30, doc["age"])
        assertEquals(true, doc["active"])
    }

    @Test
    fun `DocRowVec named access returns null for missing key`() {
        val doc = DocRowVec(listOf("x"), listOf(1))
        assertNull(doc["missing"])
    }

    @Test
    fun `DocRowVec positional access works`() {
        val doc = DocRowVec(listOf("a", "b"), listOf("x", "y"))
        assertEquals("x", doc[0])
        assertEquals("y", doc[1])
    }

    @Test
    fun `DocRowVec child is deferred lazy`() {
        val nested = DocRowVec(listOf("b"), listOf(2))
        var accessed = false
        val doc = DocRowVec(listOf("a"), listOf(1), child = 1 j { accessed = true; nested })
        assertFalse(accessed) // lazy — not accessed yet
    }

    // ── ViewRowVec docLoader deferred ─────────────────────────────────────────

    @Test
    fun `ViewRowVec docLoader fires on child access`() {
        val doc = DocRowVec(listOf("body"), listOf("hello"))
        var loadCount = 0
        val view = ViewRowVec("id", "key", "val") { loadCount++; doc }
        assertEquals(0, loadCount)
        val ch = view.child
        assertEquals(1, loadCount)
        assertNotNull(ch)
    }

    // ── BlobRowVec child factory deferred ──────────────────────────────────────

    @Test
    fun `BlobRowVec child factory fires on child access`() {
        var invoked = false
        val blob = BlobRowVec(byteArrayOf(0x7B, 0x7D)) {
            invoked = true
            1 j { JsonRowVec("number", "42") }
        }
        assertFalse(invoked)
        val ch = blob.child
        assertTrue(invoked)
        assertNotNull(ch)
    }

    // ── JsonRowVec lazy children ──────────────────────────────────────────────

    @Test
    fun `JsonRowVec leaf has no children`() {
        val json = JsonRowVec("string", "\"hello\"")
        assertNull(json.child)
    }

    @Test
    fun `JsonRowVec array has children when factory invoked`() {
        val child1 = JsonRowVec("string", "\"a\"")
        val child2 = JsonRowVec("number", "1")
        val json = JsonRowVec("array", "[\"a\",1]") { 2 j { i -> if (i == 0) child1 else child2 } }
        val ch = json.child
        assertNotNull(ch)
        assertEquals(2, ch.size)
        assertSame(child1, ch[0])
    }

    // ── YamlRowVec ───────────────────────────────────────────────────────────

    @Test
    fun `YamlRowVec leaf has no children`() {
        val yaml = YamlRowVec("scalar", "42")
        assertNull(yaml.child)
    }

    @Test
    fun `YamlRowVec mapping has children`() {
        val entry = YamlRowVec("key", "value")
        val mapping = YamlRowVec("mapping", null) { 1 j { entry } }
        val ch = mapping.child
        assertNotNull(ch)
        assertEquals(1, ch.size)
    }

    // ── asSeries ─────────────────────────────────────────────────────────────

    @Test
    fun `DocRowVec asSeries preserves values`() {
        val doc = DocRowVec(listOf("x", "y"), listOf(10, 20))
        val s = doc.asSeries()
        assertEquals(2, s.size)
        assertEquals(10, s[0])
        assertEquals(20, s[1])
    }

    @Test
    fun `asSeries on empty DocRowVec gives size 0`() {
        val shell = DocRowVec(emptyList(), emptyList())
        val s = shell.asSeries()
        assertEquals(0, s.size)
    }
}
