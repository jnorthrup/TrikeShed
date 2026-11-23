package borg.trikeshed.rdf

import borg.trikeshed.cursor.DictColumn
import borg.trikeshed.lib.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class QuadStoreTest {
    @Test fun aQuadIsFiledOnceAndFoundAgain() {
        val store = QuadStore()
            .add("<urn:s>", "<urn:p>", "<urn:o>")
            .add("<urn:s>", "<urn:p>", "<urn:o>")
            .add("<urn:s>", "<urn:p>", "<urn:o>", "<urn:g>")
        assertEquals(2, store.size)
        assertEquals(5, store.terms)
        assertTrue(store.contains("<urn:s>", "<urn:p>", "<urn:o>"))
        assertTrue(store.contains("<urn:s>", "<urn:p>", "<urn:o>", "<urn:g>"))
        assertFalse(store.contains("<urn:s>", "<urn:p>", "<urn:x>"))
        assertEquals(_l["<urn:g>"], store.graphs())
    }

    @Test fun removeKeepsTheOtherRowsAndTheIndexHonest() {
        val store = QuadStore()
        for (i in 0 until 1000) store.add("<urn:s$i>", "<urn:p>", "<urn:o>")
        assertTrue(store.remove("<urn:s500>", "<urn:p>", "<urn:o>"))
        assertFalse(store.remove("<urn:s500>", "<urn:p>", "<urn:o>"))
        assertEquals(999, store.size)
        for (i in 0 until 1000) assertEquals(i != 500, store.contains("<urn:s$i>", "<urn:p>", "<urn:o>"))
        store.add("<urn:s500>", "<urn:p>", "<urn:o>")
        assertEquals(1000, store.size)
        assertTrue(store.contains("<urn:s500>", "<urn:p>", "<urn:o>"))
    }

    @Test fun dropRemovesTheQuadsWhoseSubjectStartsWithThePrefix() {
        val store = QuadStore()
            .add("<urn:c/a/1>", "<urn:p>", "\"x\"")
            .add("<urn:c/a/2>", "<urn:p>", "<urn:c/a/1>", "<urn:g>")
            .add("<urn:c/b/1>", "<urn:p>", "<urn:c/a/1>")
        assertEquals(2, store.drop("<urn:c/a/"))
        assertEquals(1, store.size)
        assertTrue(store.contains("<urn:c/b/1>", "<urn:p>", "<urn:c/a/1>"))
        assertFalse(store.contains("<urn:c/a/1>", "<urn:p>", "\"x\""))
        assertEquals(0, store.drop("<urn:c/a/"))
        store.add("<urn:c/a/1>", "<urn:p>", "\"x\"")
        assertEquals(2, store.size)
    }

    @Test fun scanIsTheRowsOverOneSharedDictionaryCachedUntilTheNextWrite() {
        val store = QuadStore().add("<urn:s>", "<urn:p>", "\"o\"", "<urn:g>")
        val scan = store.scan()
        assertEquals(1, scan.rows)
        val columns = Array(4) { scan.dense(it).data as DictColumn }
        assertSame(columns[0].dictionary, columns[3].dictionary)
        assertEquals("<urn:g>", columns[QuadStore.G][0])
        assertEquals("<urn:s>", columns[QuadStore.S][0])
        assertEquals("<urn:p>", columns[QuadStore.P][0])
        assertEquals("\"o\"", columns[QuadStore.O][0])
        assertSame(scan, store.scan())
        store.add("<urn:s2>", "<urn:p>", "\"o\"")
        assertEquals(2, store.scan().rows)
    }

    @Test fun turtleLoadsAndComesBackAsAGraph() {
        val store = QuadStore().load("<urn:a> <urn:p> \"x\" .\n<urn:a> <urn:p> <urn:b> .")
        assertEquals(2, store.size)
        assertEquals(2, store.toGraph().triples.size)
    }

    @Test fun agreesWithASetOfQuadsOverManyAddsAndRemoves() {
        val store = QuadStore()
        val model = HashSet<String>()
        var x = 12345
        fun next(): Int { x = x * 1103515245 + 12345; return x ushr 8 }
        repeat(60_000) {
            val g = "<urn:g${next() % 3}>"; val s = "<urn:s${next() % 40}>"; val p = "<urn:p${next() % 5}>"; val o = "\"o${next() % 60}\""
            if (next() % 5 == 0) {
                assertEquals(model.remove("$g $s $p $o"), store.remove(s, p, o, g))
            } else {
                store.add(s, p, o, g)
                model.add("$g $s $p $o")
            }
            assertEquals(model.size, store.size)
        }
        for (g in 0 until 3) for (s in 0 until 40) for (p in 0 until 5) for (o in 0 until 60)
            assertEquals("<urn:g$g> <urn:s$s> <urn:p$p> \"o$o\"" in model, store.contains("<urn:s$s>", "<urn:p$p>", "\"o$o\"", "<urn:g$g>"))
    }
}
