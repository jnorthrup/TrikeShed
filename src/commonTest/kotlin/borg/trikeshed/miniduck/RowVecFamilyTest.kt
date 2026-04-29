package borg.trikeshed.miniduck

import borg.trikeshed.lib.*
import kotlin.test.*

class RowVecFamilyTest {

    // --- BlockRowVec ---

    @Test
    fun blockStartsMutable() {
        val block = BlockRowVec.mutable()
        assertEquals(BlockRowVec.State.MUTABLE, block.state)
    }

    @Test
    fun blockIsShell() {
        val block = BlockRowVec.mutable()
        assertTrue(block.isShell)
        assertEquals(0, block.size)
    }

    @Test
    fun blockAppendAndChildCount() {
        val block = BlockRowVec.mutable()
        val row = DocRowVec(listOf("x"), listOf(42))
        block.append(row)
        assertEquals(1, block.rowCount)
        assertEquals(1, block.child.size)
        assertSame(row, block.child[0])
    }

    @Test
    fun blockSealPreventsAppend() {
        val block = BlockRowVec.mutable()
        block.seal()
        assertEquals(BlockRowVec.State.SEALED, block.state)
        assertFailsWith<IllegalStateException> {
            block.append(DocRowVec(listOf("a"), listOf(1)))
        }
    }

    @Test
    fun blockSealReturnsSelf() {
        val block = BlockRowVec.mutable()
        assertSame(block, block.seal())
    }

    @Test
    fun blockMultipleRows() {
        val block = BlockRowVec.mutable()
        repeat(5) { i -> block.append(DocRowVec(listOf("i"), listOf(i))) }
        assertEquals(5, block.rowCount)
        assertEquals(5, block.child.size)
        for (i in 0 until 5) assertEquals(i, (block.child[i] as DocRowVec)["i"])
    }

    // --- DocRowVec ---

    @Test
    fun docRowVecScalarAccess() {
        val doc = DocRowVec(listOf("name", "age"), listOf("Alice", 30))
        assertEquals(2, doc.size)
        assertEquals("Alice", doc[0])
        assertEquals(30, doc[1])
        assertEquals("Alice", doc["name"])
        assertEquals(30, doc["age"])
    }

    @Test
    fun docRowVecMissingKeyReturnsNull() {
        val doc = DocRowVec(listOf("x"), listOf(1))
        assertNull(doc["missing"])
    }

    @Test
    fun docRowVecNoChildByDefault() {
        val doc = DocRowVec(listOf("a"), listOf(1))
        assertNull(doc.child)
        assertFalse(doc.isShell)
    }

    @Test
    fun docRowVecWithChild() {
        val nested = DocRowVec(listOf("b"), listOf(2))
        val doc = DocRowVec(listOf("a"), listOf(1), child = 1 j { nested })
        assertNotNull(doc.child)
        assertEquals(1, doc.child!!.size)
        assertSame(nested, doc.child!![0])
    }

    @Test
    fun docRowVecShellCanCarryDeferredChildren() {
        val nested = DocRowVec(listOf("body"), listOf("hello"))
        val shell = DocRowVec(emptyList(), emptyList(), child = 1 j { nested })

        assertTrue(shell.isShell)
        assertEquals(0, shell.size)
        assertNotNull(shell.child)
        assertEquals(1, shell.child!!.size)
        assertSame(nested, shell.child!![0])
    }

    // --- ViewRowVec ---

    @Test
    fun viewRowVecScalarSurface() {
        val view = ViewRowVec("doc1", "keyA", 99)
        assertEquals(3, view.size)
        assertEquals("doc1", view[0])
        assertEquals("keyA", view[1])
        assertEquals(99, view[2])
    }

    @Test
    fun viewRowVecNoDocLoaderMeansNullChild() {
        val view = ViewRowVec("doc1", "k", "v")
        assertNull(view.child)
    }

    @Test
    fun viewRowVecDocLoaderDeferred() {
        val doc = DocRowVec(listOf("body"), listOf("hello"))
        var loaded = false
        val view = ViewRowVec("doc1", "k", "v") { loaded = true; doc }
        assertFalse(loaded, "doc loader must not fire until child accessed")
        val ch = view.child
        assertNotNull(ch)
        assertEquals(1, ch.size)
        assertTrue(loaded)
        assertSame(doc, ch[0])
    }

    // --- BlobRowVec ---

    @Test
    fun blobIsShell() {
        val blob = BlobRowVec(byteArrayOf(1, 2, 3), "application/octet-stream")
        assertTrue(blob.isShell)
        assertEquals(0, blob.size)
    }

    @Test
    fun blobNoFactoryMeansNullChild() {
        val blob = BlobRowVec(byteArrayOf())
        assertNull(blob.child)
    }

    @Test
    fun blobChildFactoryInvokedLazily() {
        var invoked = false
        val blob = BlobRowVec(byteArrayOf(0x7b, 0x7d)) { bytes ->
            invoked = true
            val json = JsonRowVec("object", "{}")
            1 j { json }
        }
        assertFalse(invoked)
        val ch = blob.child
        assertTrue(invoked)
        assertNotNull(ch)
        assertEquals(1, ch!!.size)
        assertEquals("object", (ch[0] as JsonRowVec).nodeType)
    }

    // --- JsonRowVec ---

    @Test
    fun jsonRowVecScalars() {
        val json = JsonRowVec("string", "\"hello\"")
        assertEquals(2, json.size)
        assertEquals("string", json[0])
        assertEquals("\"hello\"", json[1])
    }

    @Test
    fun jsonRowVecNoChildrenForLeaf() {
        val json = JsonRowVec("number", "42")
        assertNull(json.child)
    }

    @Test
    fun jsonRowVecChildrenDeferred() {
        val child1 = JsonRowVec("string", "\"a\"")
        val child2 = JsonRowVec("number", "1")
        val json = JsonRowVec("array", "[\"a\",1]") { 2 j { i -> if (i == 0) child1 else child2 } }
        val ch = json.child
        assertNotNull(ch)
        assertEquals(2, ch!!.size)
        assertSame(child1, ch[0])
        assertSame(child2, ch[1])
    }

    // --- YamlRowVec ---

    @Test
    fun yamlRowVecScalars() {
        val yaml = YamlRowVec("scalar", "hello")
        assertEquals(2, yaml.size)
        assertEquals("scalar", yaml[0])
        assertEquals("hello", yaml[1])
    }

    @Test
    fun yamlMappingHasChildren() {
        val entry = YamlRowVec("scalar", "value")
        val mapping = YamlRowVec("mapping", null) { 1 j { entry } }
        val ch = mapping.child
        assertNotNull(ch)
        assertEquals(1, ch!!.size)
        assertSame(entry, ch[0])
    }

    // --- asSeries extension ---

    @Test
    fun asSeriesOnDoc() {
        val doc = DocRowVec(listOf("x", "y"), listOf(10, 20))
        val s = doc.asSeries()
        assertEquals(2, s.size)
        assertEquals(10, s[0])
        assertEquals(20, s[1])
    }
}
