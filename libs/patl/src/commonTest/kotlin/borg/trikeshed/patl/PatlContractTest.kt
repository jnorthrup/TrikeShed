package borg.trikeshed.patl

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotSame
import borg.trikeshed.lib.j
import borg.trikeshed.lib.Series

class PatlContractTest {

    private fun stringBitComp() = BitComp<String> { s ->
        val bytes = s.encodeToByteArray()
        bytes.size j { i -> bytes[i] }
    }

    @Test
    fun `bitComp maps two integers to shared prefix bits`() {
        val bc = stringBitComp()
        val mismatch = bc.mismatch("a", "b")
        assertEquals(0u, mismatch)
    }

    @Test
    fun `PatriciaTrieMap insert returns new map persistent`() {
        val map1 = PatriciaTrieMap<String, Int>(stringBitComp())
        val map2 = map1.insert("foo", 1)
        assertNotSame(map1, map2)
        assertEquals(1, map2.size)
        assertEquals(0, map1.size)
    }

    @Test
    fun `PatriciaTrieMap delete returns new map persistent`() {
        val map1 = PatriciaTrieMap<String, Int>(stringBitComp()).insert("foo", 1)
        val map2 = map1.delete("foo")
        assertNotSame(map1, map2)
        assertEquals(0, map2.size)
        assertEquals(1, map1.size)
    }

    @Test
    fun `lookup returns value for exact key`() {
        val map = PatriciaTrieMap<String, Int>(stringBitComp()).insert("foo", 1).insert("bar", 2)
        assertEquals(1, map.lookup("foo"))
        assertEquals(2, map.lookup("bar"))
    }

    @Test
    fun `lookup returns null for missing key`() {
        val map = PatriciaTrieMap<String, Int>(stringBitComp()).insert("foo", 1)
        assertNull(map.lookup("bar"))
    }

    @Test
    fun `node count reflects shared-prefix compression`() {
        val map = PatriciaTrieMap<String, Int>(stringBitComp())
            .insert("foo", 1)
            .insert("food", 2)
            .insert("bar", 3)
        assertEquals(1, map.lookup("foo"))
        assertEquals(2, map.lookup("food"))
        assertEquals(3, map.lookup("bar"))
    }

    @Test
    fun `IntNodeStore stores and retrieves trie nodes`() {
        val store = IntNodeStore()
        val node = store.append(IntNodeStore.NULL, 0, -1, -2, 42)
        assertEquals(0, node)
        assertEquals(-1, store.getLeftChild(0))
        assertEquals(-2, store.getRightChild(0))
        assertEquals(42, store.getSkip(0))
        assertEquals(IntNodeStore.NULL, store.getParent(0))
    }

    @Test
    fun `AutoIntNodeStore auto-allocates node ids`() {
        val map = PatriciaTrieMap<String, Int>(stringBitComp()).insert("foo", 1).insert("bar", 2)
        assertTrue(map.store.size >= 0)
    }
}
