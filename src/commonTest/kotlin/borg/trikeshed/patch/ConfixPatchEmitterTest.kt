package borg.trikeshed.patch

import borg.trikeshed.pijul.*
import kotlin.test.Test
import kotlin.test.assertEquals

class ConfixPatchEmitterTest {

    @Test
    fun testPatchEmission() {
        val emitter = ConfixPatchEmitter()

        emitter.emitInsert(0, "A")
        emitter.emitInsert(1, "B")

        val patches = emitter.getPatches()
        assertEquals(2, patches.size)
        assertEquals(1, patches[0].changes.size)
        assertEquals(1, patches[1].changes.size)

        val change1 = patches[0].changes[0] as Change.Insert
        assertEquals(0, change1.pos)
        assertEquals("A", change1.content)

        val change2 = patches[1].changes[0] as Change.Insert
        assertEquals(1, change2.pos)
        assertEquals("B", change2.content)
    }
}
