package borg.trikeshed.collections.multiindex

import borg.trikeshed.collections.associative.LinearHashMap
import borg.trikeshed.lib.size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private data class Person(val name: String, val age: Int)

class LinearHashMapTest {

    @Test fun putAndGet() {
        val m = LinearHashMap<String, Int>()
        assertNull(m.get("x"))
        m.set("a", 1)
        m.set("b", 2)
        assertEquals(1, m.get("a"))
        assertEquals(2, m.get("b"))
        assertNull(m.get("z"))
    }

    @Test fun putOverwrite() {
        val m = LinearHashMap<String, Int>()
        assertEquals(null, m.set("k", 10))
        assertEquals(10,   m.set("k", 20))
        assertEquals(20,   m.get("k"))
    }

    @Test fun remove() {
        val m = LinearHashMap<String, Int>()
        m.set("a", 1); m.set("b", 2)
        assertEquals(1, m.remove("a"))
        assertNull(m.get("a"))
        assertEquals(2, m.get("b"))
        assertNull(m.remove("a"))  // already gone
    }

    @Test fun resize() {
        val m = LinearHashMap<Int, Int>(4)
        repeat(200) { m.set(it, it * 2) }
        repeat(200) { assertEquals(it * 2, m.get(it), "key=$it") }
        assertEquals(200, m.count)
    }

    @Test fun collisionChain() {
        // force collisions: all keys hash to same slot mod 16
        val m = LinearHashMap<String, Int>(16)
        // fabricate collision-prone keys by overriding won't work in common,
        // so just exercise with many puts to trigger probing
        val keys = (0 until 7).map { "key$it" }
        keys.forEachIndexed { i, k -> m.set(k, i) }
        keys.forEachIndexed { i, k -> assertEquals(i, m.get(k)) }
    }
}

class MultiIndexContainerTest {

    private val alice = Person("Alice", 30)
    private val bob   = Person("Bob",   25)
    private val carol = Person("Carol", 35)
    private val dave  = Person("Dave",  25)   // same age as Bob

    private fun populated(): MultiIndexContainer<Person> {
        val c = MultiIndexContainer<Person>()
        c.add(alice); c.add(bob); c.add(carol); c.add(dave)
        return c
    }

    @Test fun byHashLookup() {
        val c = populated()
        val byName = c.facet(MultiIndexK.ByHash { (it as Person).name })
        assertEquals(0, byName("Alice"))
        assertEquals(1, byName("Bob"))
        assertEquals(2, byName("Carol"))
        assertNull(byName("Zara"))
    }

    @Test fun bySequence() {
        val c = populated()
        val seq = c.facet(MultiIndexK.BySequence)
        assertEquals(4, seq.size)
        assertEquals(0, seq.b(0)); assertEquals(3, seq.b(3))
    }

    @Test fun elements() {
        val c = populated()
        val elems = c.facet(MultiIndexK.Elements)
        assertEquals(4, elems.size)
        assertEquals(alice, elems.b(0))
        assertEquals(carol, elems.b(2))
    }

    @Test fun byOrderAscending() {
        val c = populated()
        val byAge = c.facet(MultiIndexK.ByOrder { (it as Person).age })
        // expected order: Bob(25), Dave(25), Alice(30), Carol(35)
        val ages = (0 until byAge.size).map { idx -> (c[byAge.b(idx)] as Person).age }
        assertEquals(listOf(25, 25, 30, 35), ages)
    }

    @Test fun byRangeInclusive() {
        val c = populated()
        val rangeKey = MultiIndexK.ByRange { (it as Person).age }
        // register sort index so it's built
        c.registerOrder(MultiIndexK.ByOrder { (it as Person).age })
        val range = c.facet(rangeKey)
        val hits = range(25, 30)
        val names = (0 until hits.size).map { idx -> (c[hits.b(idx)] as Person).name }.sorted()
        assertEquals(listOf("Alice", "Bob", "Dave"), names)
    }

    @Test fun multipleHashKeys() {
        val c = populated()
        val byName = c.facet(MultiIndexK.ByHash { (it as Person).name })
        val byAge  = c.facet(MultiIndexK.ByHash { (it as Person).age  })
        assertEquals(0, byName("Alice"))
        // age=30 → Alice at pos 0
        assertEquals(0, byAge(30))
        // age=25 → first inserted wins (Bob at pos 1 or Dave at pos 3)
        val pos25 = byAge(25)
        assertTrue(pos25 == 1 || pos25 == 3)
    }

    @Test fun addAfterFacetInit() {
        val c = MultiIndexContainer<Person>()
        c.add(alice)
        val byName = c.facet(MultiIndexK.ByHash { (it as Person).name })
        assertEquals(0, byName("Alice"))
        assertNull(byName("Eve"))
        val eve = Person("Eve", 22)
        c.add(eve)
        // re-acquire facet — should see Eve now
        val byName2 = c.facet(MultiIndexK.ByHash { (it as Person).name })
        assertEquals(1, byName2("Eve"))
    }

    @Test fun registeredHashFacetSeesLaterAddsThroughSameKey() {
        val c = MultiIndexContainer<Person>()
        val nameKey = MultiIndexK.ByHash { (it as Person).name }
        c.registerHash(nameKey)

        c.add(alice)
        val byName = c.facet(nameKey)
        assertEquals(0, byName("Alice"))

        c.add(bob)
        assertEquals(1, byName("Bob"))
    }

    @Test fun orderFacetRegisteredByRangeKeySeesLaterAdds() {
        val c = MultiIndexContainer<Person>()
        val ageRange = MultiIndexK.ByRange { (it as Person).age }
        c.registerOrder(ageRange)

        c.add(alice)
        c.add(carol)
        val range = c.facet(ageRange)
        assertEquals(listOf("Alice", "Carol"), (0 until range(0, 99).size).map { idx -> c[range(0, 99).b(idx)].name })

        c.add(bob)
        val hits = range(25, 30)
        val names = (0 until hits.size).map { idx -> c[hits.b(idx)].name }
        assertEquals(listOf("Bob", "Alice"), names)
    }
}
