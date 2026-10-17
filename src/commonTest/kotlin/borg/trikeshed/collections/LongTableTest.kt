package borg.trikeshed.collections

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
