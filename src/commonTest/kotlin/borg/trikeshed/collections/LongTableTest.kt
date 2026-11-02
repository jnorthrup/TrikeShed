package borg.trikeshed.collections

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LongTableTest {
    @Test fun agreesWithHashMapUnderRandomPutAndRemove() {
        val random = Random(20261017)
        val table = LongTable<Long>()
        val model = HashMap<Long, Long>()
        repeat(200_000) { step ->
            // A narrow key range keeps long probe clusters, wrap-around and backward shifts in play.
            val key = random.nextLong(-512, 512) * 0x1_0000_0001L
            if (random.nextInt(3) < 2) assertEquals(model.put(key, step.toLong()), table.put(key, step.toLong()))
            else assertEquals(model.remove(key), table.remove(key))
            assertEquals(model.size, table.size)
        }
        for ((key, value) in model) assertEquals(value, table[key])
        var visited = 0
        table.forEach { key, value ->
            assertEquals(model[key], value)
            visited++
        }
        assertEquals(model.size, visited)
        val any = model.keys.first()
        table.clear()
        assertTrue(table.isEmpty())
        assertNull(table[any])
    }

    @Test fun expectedSizesBeyondTheCapacityFailBeforeAllocating() {
        assertFailsWith<IllegalArgumentException> { LongTable<String>(Int.MAX_VALUE) }
        assertFailsWith<IllegalArgumentException> { LongTable<String>(LongTable.MAX_SIZE + 1) }
        assertFailsWith<IllegalArgumentException> { LongTable<String>(-1) }
    }

    @Test fun expectedSizesPresizeWithinTheLoad() {
        assertEquals(8, LongTable<String>(6).slots())
        assertEquals(16, LongTable<String>(7).slots())
        assertEquals(1 shl 17, LongTable<String>(65_536).slots())
    }

    @Test fun replacingABindingAtFullLoadDoesNotGrow() {
        val table = LongTable<String>(6)
        for (k in 0L until 6L) assertNull(table.put(k, "v$k"))
        assertEquals(8, table.slots())
        assertEquals("v3", table.put(3L, "w3"))
        assertEquals(8, table.slots())
        assertEquals(6, table.size)
        assertNull(table.put(6L, "v6"))
        assertEquals(16, table.slots())
        assertEquals("w3", table[3L])
        for (k in 0L..6L) assertTrue(k in table)
    }
}
